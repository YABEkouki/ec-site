package com.example.ecsite.service;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import com.example.ecsite.entity.*;
import com.example.ecsite.repository.*;
import com.example.ecsite.config.PaymentDiscrepancyAuditNotificationProperties;
import com.example.ecsite.exception.PaymentAuditNotificationNotFoundException;

@Service
public class AdminPaymentAuditNotificationManualResendViewService {
    private final AdminPaymentAuditNotificationManualResendService acceptance;
    private final PaymentAuditNotificationRepository notifications;
    private final PaymentAuditNotificationManualResendRepository requests;
    private final PaymentAuditNotificationManualResendAttemptRepository attempts;
    private final PaymentDiscrepancyAuditNotificationProperties properties;
    public AdminPaymentAuditNotificationManualResendViewService(AdminPaymentAuditNotificationManualResendService acceptance,PaymentAuditNotificationRepository notifications,PaymentAuditNotificationManualResendRepository requests,PaymentAuditNotificationManualResendAttemptRepository attempts,PaymentDiscrepancyAuditNotificationProperties properties){this.acceptance=acceptance;this.notifications=notifications;this.requests=requests;this.attempts=attempts;this.properties=properties;}
    public record History(int requestNo,LocalDateTime requestedAt,String actor,String reason,PaymentAuditNotificationManualResendStatus status,LocalDateTime startedAt,LocalDateTime finishedAt,String resultReason,PaymentAuditNotificationAttemptResult attemptResult){
        @Override public String toString(){return "ManualResendHistory[requestNo="+requestNo+", status="+status+"]";}
    }
    public record View(long notificationId,AdminPaymentAuditNotificationManualResendService.Eligibility eligibility,int nextRequestNo,List<String> maskedRecipients,List<History> histories){
        public View{maskedRecipients=List.copyOf(maskedRecipients);histories=List.copyOf(histories);}
        @Override public String toString(){return "ManualResendView[notificationId="+notificationId+", recipientCount="+maskedRecipients.size()+"]";}
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public View read(long id,long actor,boolean confirmation){
        var n=notifications.findById(id).orElseThrow(()->new PaymentAuditNotificationNotFoundException(id));
        var eligibility=acceptance.prepare(id,actor,confirmation);
        var rows=requests.findByNotificationIdOrderByRequestNoAsc(id);
        var effective=Arrays.stream(n.getRecipients()).map(AdminPaymentAuditNotificationManualResendViewService::normalize).filter(properties.recipients()::contains).distinct().toList();
        // Fixed masks disclose no local part or domain, including short/unusual addresses.
        var masks=effective.stream().map(address->"***@***").toList();
        var history=rows.stream().map(r->{var a=attempts.findByManualResendId(r.getId());return new History(r.getRequestNo(),jst(r.getRequestedAt()),r.getActorUsername(),r.getReason(),r.getStatus(),jst(r.getStartedAt()),jst(r.getFinishedAt()),r.getResultReasonCode(),a.map(PaymentAuditNotificationManualResendAttempt::getResult).orElse(null));}).toList();
        return new View(id,eligibility,rows.size()+1,masks,history);
    }
    private static String normalize(String address){String s=address.trim();int at=s.lastIndexOf('@');return at<1?"":s.substring(0,at)+s.substring(at).toLowerCase(Locale.ROOT);}
    private static LocalDateTime jst(Instant i){return i==null?null:LocalDateTime.ofInstant(i,ZoneId.of("Asia/Tokyo"));}
}
