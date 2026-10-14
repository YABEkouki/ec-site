package com.example.ecsite.service.payment;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.*;
import com.example.ecsite.config.PaymentDiscrepancyAuditNotificationProperties;
import com.example.ecsite.dto.AdminPaymentDiscrepancyAuditWarning;
import com.example.ecsite.entity.*;
import com.example.ecsite.repository.*;

/** Independent short transactions. Never sends SMTP or mutates original notification history. */
@Service
@Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
public class PaymentAuditNotificationManualResendDeliveryTransaction {
    private final PaymentAuditNotificationManualResendRepository requests;
    private final PaymentAuditNotificationManualResendAttemptRepository attempts;
    private final PaymentAuditNotificationHandlingRepository handlings;
    private final PaymentAuditNotificationStateRepository states;
    private final PaymentAuditNotificationItemRepository items;
    private final PaymentDiscrepancyAuditNotificationProperties properties;
    private final JdbcTemplate jdbc;
    public record Claim(long id,long notificationId,UUID token) {}
    public PaymentAuditNotificationManualResendDeliveryTransaction(PaymentAuditNotificationManualResendRepository requests,
        PaymentAuditNotificationManualResendAttemptRepository attempts,PaymentAuditNotificationHandlingRepository handlings,
        PaymentAuditNotificationStateRepository states,PaymentAuditNotificationItemRepository items,
        PaymentDiscrepancyAuditNotificationProperties properties,JdbcTemplate jdbc){
        this.requests=requests;this.attempts=attempts;this.handlings=handlings;this.states=states;this.items=items;this.properties=properties;this.jdbc=jdbc;
    }
    public Optional<Claim> claim(UUID owner){
        if(!properties.enabled())return Optional.empty();
        Objects.requireNonNull(owner);
        return requests.findNextForClaim().map(r->{
            UUID token=UUID.randomUUID();r.setStatus(PaymentAuditNotificationManualResendStatus.CLAIMED);
            r.setClaimToken(token);r.setClaimedBy(owner);r.setLeaseUntil(now().plus(properties.claimLease()));
            return new Claim(r.getId(),r.getNotificationId(),token);
        });
    }
    public Optional<PaymentAuditNotificationMail> prepare(Claim claim){
        if(!properties.enabled())return Optional.empty();
        var current=new EnumMap<PaymentAuditNotificationWarningType,PaymentAuditNotificationState>(PaymentAuditNotificationWarningType.class);
        for(var state:states.findAllForObservation())current.put(state.getWarningType(),state);
        if(current.size()!=PaymentAuditNotificationWarningType.values().length)throw new IllegalStateException("Six warning states are required");
        var parent=requests.lockNotification(claim.notificationId());
        var found=requests.findForUpdate(claim.id());Instant now=now();
        if(parent.isEmpty() || found.isEmpty() || !owned(found.get(),claim,PaymentAuditNotificationManualResendStatus.CLAIMED,now))return Optional.empty();
        var r=found.get();var n=parent.get();
        if(attempts.findForDelivery(r.getId()).isPresent())throw new IllegalStateException("Pre-send request already has an attempt");
        if(n.getStatus()!=PaymentAuditNotificationStatus.EXHAUSTED || n.isDeliveryUncertain()
            || !handlings.findUnknownAttemptNumbers(n.getId()).isEmpty() || !attempts.findUnknownIdsByNotificationId(n.getId()).isEmpty()){
            cancel(r,now,"RESEND_PROHIBITED");return Optional.empty();
        }
        if(handlings.findById(n.getId()).map(PaymentAuditNotificationHandling::getHandlingStatus).orElse(PaymentAuditNotificationHandlingStatus.UNREVIEWED)==PaymentAuditNotificationHandlingStatus.RESOLVED){
            cancel(r,now,"HANDLING_RESOLVED");return Optional.empty();
        }
        var remaining=new ArrayList<PaymentAuditNotificationItem>();
        for(var item:items.findByNotificationIdOrderByIdAsc(n.getId())){
            var state=current.get(item.getState().getWarningType());
            if(item.getItemStatus()==PaymentAuditNotificationItemStatus.INCLUDED && state.isActive() && state.getEpisodeNo()==item.getEpisodeNo())remaining.add(item);
        }
        if(remaining.isEmpty()){cancel(r,now,"RESOLVED");return Optional.empty();}
        var recipients=Arrays.stream(n.getRecipients()).map(PaymentAuditNotificationManualResendDeliveryTransaction::normalize)
            .filter(properties.recipients()::contains).distinct().toList();
        if(recipients.isEmpty()){cancel(r,now,"NO_VALID_RECIPIENTS");return Optional.empty();}
        remaining.sort(Comparator.comparing(i->i.getState().getWarningType()));
        var mail=render(r,n,remaining,recipients);
        r.setStatus(PaymentAuditNotificationManualResendStatus.SENDING);r.setStartedAt(now);r.setLeaseUntil(now.plus(properties.claimLease()));
        var a=new PaymentAuditNotificationManualResendAttempt();a.setManualResendId(r.getId());a.setNotificationId(n.getId());
        a.setResult(PaymentAuditNotificationAttemptResult.IN_PROGRESS);a.setClaimToken(claim.token());a.setStartedAt(now);
        a.setRecipients(recipients.toArray(String[]::new));a.setSubject(mail.subject());a.setBody(mail.body());attempts.saveAndFlush(a);
        return Optional.of(mail);
    }
    public boolean complete(Claim claim,PaymentAuditNotificationAttemptResult result,PaymentAuditNotificationFailureCode code){
        if(result==null || result==PaymentAuditNotificationAttemptResult.IN_PROGRESS || (result==PaymentAuditNotificationAttemptResult.SUCCESS)!=(code==null))throw new IllegalArgumentException("Final result and safe code required");
        if(requests.lockNotification(claim.notificationId()).isEmpty())return false;
        var found=requests.findForUpdate(claim.id());Instant now=now();
        if(found.isEmpty() || !owned(found.get(),claim,PaymentAuditNotificationManualResendStatus.SENDING,now))return false;
        finish(found.get(),claim.token(),result,code,now);return true;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=true)
    public List<Long> expiredIds(){return requests.findExpiredIdsForRecovery();}
    public boolean recoverOne(long id){
        var parentId=requests.findNotificationIdForRecovery(id);
        if(parentId.isEmpty() || requests.lockNotification(parentId.get()).isEmpty())return false;
        var found=requests.findForUpdate(id);Instant now=now();if(found.isEmpty())return false;var r=found.get();
        if(r.getLeaseUntil()==null || r.getLeaseUntil().isAfter(now))return false;
        if(r.getStatus()==PaymentAuditNotificationManualResendStatus.CLAIMED){
            if(attempts.findForDelivery(id).isPresent())throw new IllegalStateException("Claimed request must not have an attempt");
            r.setStatus(PaymentAuditNotificationManualResendStatus.PENDING);clearClaim(r);return true;
        }
        if(r.getStatus()==PaymentAuditNotificationManualResendStatus.SENDING){finish(r,r.getClaimToken(),PaymentAuditNotificationAttemptResult.UNKNOWN,PaymentAuditNotificationFailureCode.CLAIM_LOST,now);return true;}
        return false;
    }
    private void finish(PaymentAuditNotificationManualResend r,UUID token,PaymentAuditNotificationAttemptResult result,PaymentAuditNotificationFailureCode code,Instant now){
        var a=attempts.findForDelivery(r.getId()).orElseThrow(()->new IllegalStateException("Manual attempt required"));
        if(a.getResult()!=PaymentAuditNotificationAttemptResult.IN_PROGRESS || !token.equals(a.getClaimToken()))throw new IllegalStateException("Manual attempt ownership lost");
        a.setResult(result);a.setResultReasonCode(code);a.setFinishedAt(now);
        r.setStatus(switch(result){case SUCCESS->PaymentAuditNotificationManualResendStatus.SENT;case FAILURE->PaymentAuditNotificationManualResendStatus.FAILED;case UNKNOWN->PaymentAuditNotificationManualResendStatus.UNKNOWN;default->throw new IllegalArgumentException("Final result required");});
        r.setResultReasonCode(code==null?null:code.name());r.setFinishedAt(now);clearClaim(r);
    }
    private static boolean owned(PaymentAuditNotificationManualResend r,Claim claim,PaymentAuditNotificationManualResendStatus status,Instant now){return r.getNotificationId()==claim.notificationId() && r.getStatus()==status && claim.token().equals(r.getClaimToken()) && r.getLeaseUntil()!=null && r.getLeaseUntil().isAfter(now);}
    private Instant now(){return Objects.requireNonNull(jdbc.queryForObject("select clock_timestamp()",java.sql.Timestamp.class)).toInstant();}
    private static void clearClaim(PaymentAuditNotificationManualResend r){r.setClaimToken(null);r.setClaimedBy(null);r.setLeaseUntil(null);}
    private static void cancel(PaymentAuditNotificationManualResend r,Instant now,String code){r.setStatus(PaymentAuditNotificationManualResendStatus.CANCELLED);r.setFinishedAt(now);r.setResultReasonCode(code);clearClaim(r);}
    private static String normalize(String address){String value=address.trim();int at=value.lastIndexOf('@');return at<1 || at==value.length()-1?"":value.substring(0,at)+value.substring(at).toLowerCase(Locale.ROOT);}
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.of("Asia/Tokyo"));
    private static PaymentAuditNotificationMail render(PaymentAuditNotificationManualResend r,PaymentAuditNotification n,List<PaymentAuditNotificationItem> remaining,List<String> recipients){
        var body=new StringBuilder("PAY.JP決済監査メール通知の手動再送です。\n\n元通知ID：").append(n.getId()).append("\n再送要求ID：").append(r.getId()).append("\n判定日時：").append(TIME.format(n.getEvaluatedAt())).append("\n");
        for(var item:remaining){var type=item.getState().getWarningType();body.append("\n警告種別：").append(AdminPaymentDiscrepancyAuditWarning.Type.valueOf(type.name()).getTitle()).append("（").append(type.name()).append("）\n初回観測日時：").append(TIME.format(item.getFirstObservedAt())).append("\n");
            if(item.getRelatedAt()!=null)body.append("関連日時：").append(TIME.format(item.getRelatedAt())).append("\n");
            if(item.getWarningCount()!=null)body.append("件数：").append(item.getWarningCount()).append("\n");
            if(item.getErrorCode()!=null)body.append("エラーコード：").append(item.getErrorCode().name()).append("\n");
        }
        body.append("\n管理者監視画面：\n").append(n.getAdminUrl()).append("\n\n管理画面にログインして状況を確認してください。\n");
        return new PaymentAuditNotificationMail(n.getFromAddress(),recipients,"【ECサイト】PAY.JP決済監査の警告（手動再送）",body.toString());
    }
}
