package com.example.ecsite.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import com.example.ecsite.entity.*;
import com.example.ecsite.repository.*;
import com.example.ecsite.exception.PaymentAuditNotificationNotFoundException;
import static com.example.ecsite.service.PaymentAuditNotificationManualResendRejectedException.Reason.*;

/** Acceptance only: never invokes observation, delivery, SMTP or handling updates. */
@Service
public class AdminPaymentAuditNotificationManualResendService {
    private final PaymentAuditNotificationRepository notifications;
    private final PaymentAuditNotificationManualResendRepository requests;
    private final PaymentAuditNotificationManualResendAttemptRepository attempts;
    private final PaymentAuditNotificationHandlingRepository handlings;
    private final PaymentAuditNotificationManualResendToken tokens;
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    public AdminPaymentAuditNotificationManualResendService(PaymentAuditNotificationRepository notifications,
        PaymentAuditNotificationManualResendRepository requests,PaymentAuditNotificationManualResendAttemptRepository attempts,
        PaymentAuditNotificationHandlingRepository handlings,PaymentAuditNotificationManualResendToken tokens,JdbcTemplate jdbc,
        @Value("${app.payment.discrepancy-audit.notification.enabled:false}") boolean enabled){
        this.notifications=notifications;this.requests=requests;this.attempts=attempts;this.handlings=handlings;this.tokens=tokens;this.jdbc=jdbc;this.enabled=enabled;
    }
    public record Eligibility(boolean eligible,PaymentAuditNotificationManualResendRejectedException.Reason rejection,
        boolean signingAvailable,boolean riskAcknowledgementRequired,String snapshotToken){
        @Override public String toString(){return "Eligibility[eligible="+eligible+", rejection="+rejection+", signingAvailable="+signingAvailable+", riskAcknowledgementRequired="+riskAcknowledgementRequired+"]";}
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Eligibility prepare(long notificationId,long actorId){
        return prepare(notificationId,actorId,true);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Eligibility prepare(long notificationId,long actorId,boolean issueToken){
        if(actorId<=0)throw new IllegalArgumentException("Invalid administrator identity");
        var notification=notifications.findById(notificationId).orElseThrow(()->new PaymentAuditNotificationNotFoundException(notificationId));
        var history=requests.findByNotificationIdOrderByRequestNoAsc(notificationId);
        var handling=handlings.findById(notificationId);
        var rejection=rejectReason(notification,history,handling,dbNow());
        boolean risk=history.size()==1 && history.getFirst().getStatus()==PaymentAuditNotificationManualResendStatus.FAILED;
        String token=issueToken && rejection==null && tokens.isConfigured()?tokens.issue(notificationId,actorId,UUID.randomUUID(),generation(history),handling.isPresent(),handling.map(PaymentAuditNotificationHandling::getVersion).orElse(0L)):null;
        return new Eligibility(rejection==null,rejection,tokens.isConfigured(),risk,token);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public long request(long notificationId,long actorId,String actorUsername,String token,String reason,boolean riskAcknowledged){
        var snapshot=tokens.verify(token,notificationId,actorId);
        PaymentAuditNotificationHandlingComments.validate(reason,true);
        if(actorUsername==null || actorUsername.isBlank() || actorUsername.codePointCount(0,actorUsername.length())>100)throw new IllegalArgumentException("Invalid administrator identity");
        var notification=requests.lockNotification(notificationId).orElseThrow(()->new PaymentAuditNotificationNotFoundException(notificationId));
        var duplicate=requests.findByRequestNonce(snapshot.nonce());
        if(duplicate.isPresent()){
            var existing=duplicate.get();
            if(existing.getNotificationId()!=notificationId || existing.getActorUserId()!=actorId || !existing.getActorUsername().equals(actorUsername) || !existing.getReason().equals(reason) || existing.isRiskAcknowledged()!=riskAcknowledged)throw rejected(NONCE_CONFLICT);
            return existing.getId();
        }
        var history=requests.findByNotificationIdOrderByRequestNoAsc(notificationId);
        var handling=handlings.findById(notificationId);
        if(!generation(history).equals(snapshot.generation()) || handling.isPresent()!=snapshot.handlingExists() || handling.map(PaymentAuditNotificationHandling::getVersion).orElse(0L)!=snapshot.handlingVersion())throw rejected(STALE_SNAPSHOT);
        Instant now=dbNow();
        var rejection=rejectReason(notification,history,handling,now); if(rejection!=null)throw rejected(rejection);
        if(history.size()==1 && history.getFirst().getStatus()==PaymentAuditNotificationManualResendStatus.FAILED && !riskAcknowledged)throw rejected(RISK_ACKNOWLEDGEMENT_REQUIRED);
        var request=new PaymentAuditNotificationManualResend();
        request.setNotificationId(notificationId);request.setRequestNo(history.size()+1);request.setRequestNonce(snapshot.nonce());
        request.setActorUserId(actorId);request.setActorUsername(actorUsername);request.setReason(reason);request.setRiskAcknowledged(riskAcknowledged);
        request.setStatus(PaymentAuditNotificationManualResendStatus.PENDING);request.setRequestedAt(now);
        return requests.saveAndFlush(request).getId();
    }
    private PaymentAuditNotificationManualResendRejectedException.Reason rejectReason(PaymentAuditNotification notification,List<PaymentAuditNotificationManualResend> history,Optional<PaymentAuditNotificationHandling> handling,Instant now){
        long id=notification.getId();
        if(!enabled)return NOTIFICATION_DISABLED;
        if(notification.getStatus()!=PaymentAuditNotificationStatus.EXHAUSTED)return NOT_EXHAUSTED;
        if(notification.isDeliveryUncertain())return DELIVERY_UNCERTAIN;
        if(!handlings.findUnknownAttemptNumbers(id).isEmpty() || !attempts.findUnknownIdsByNotificationId(id).isEmpty() || history.stream().anyMatch(r->r.getStatus()==PaymentAuditNotificationManualResendStatus.UNKNOWN))return UNKNOWN_PRESENT;
        if(handling.map(PaymentAuditNotificationHandling::getHandlingStatus).orElse(PaymentAuditNotificationHandlingStatus.UNREVIEWED)==PaymentAuditNotificationHandlingStatus.RESOLVED)return HANDLING_RESOLVED;
        if(history.size()>=2)return LIMIT_REACHED;
        if(history.stream().anyMatch(r->Set.of(PaymentAuditNotificationManualResendStatus.PENDING,PaymentAuditNotificationManualResendStatus.CLAIMED,PaymentAuditNotificationManualResendStatus.SENDING).contains(r.getStatus())))return ACTIVE_REQUEST;
        if(!history.isEmpty()){
            var previous=history.getLast();
            if(previous.getStatus()!=PaymentAuditNotificationManualResendStatus.FAILED && previous.getStatus()!=PaymentAuditNotificationManualResendStatus.CANCELLED)return PREVIOUS_RESULT;
            if(now.isBefore(previous.getRequestedAt().plus(Duration.ofMinutes(30))))return TOO_SOON;
        }
        return null;
    }
    private Instant dbNow(){return Objects.requireNonNull(jdbc.queryForObject("select clock_timestamp()",java.sql.Timestamp.class)).toInstant();}
    private static String generation(List<PaymentAuditNotificationManualResend> history){
        StringBuilder value=new StringBuilder();
        for(var r:history)value.append(r.getId()).append(':').append(r.getRequestNo()).append(':').append(r.getVersion()).append(':').append(r.getStatus()).append(':').append(r.getRequestedAt()).append(';');
        try{return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("Snapshot digest unavailable");}
    }
    private static PaymentAuditNotificationManualResendRejectedException rejected(PaymentAuditNotificationManualResendRejectedException.Reason reason){return new PaymentAuditNotificationManualResendRejectedException(reason);}
}
