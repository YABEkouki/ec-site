package com.example.ecsite.service.payment;

import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import com.example.ecsite.config.PaymentDiscrepancyAuditNotificationProperties;

@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class PaymentAuditNotificationManualResendDeliveryService {
    private final UUID owner=UUID.randomUUID();
    private final PaymentAuditNotificationManualResendDeliveryTransaction transactions;
    private final ObjectProvider<PaymentAuditNotificationSmtpExecutor> smtp;
    private final PaymentDiscrepancyAuditNotificationProperties properties;
    public PaymentAuditNotificationManualResendDeliveryService(PaymentAuditNotificationManualResendDeliveryTransaction transactions,ObjectProvider<PaymentAuditNotificationSmtpExecutor> smtp,PaymentDiscrepancyAuditNotificationProperties properties){this.transactions=transactions;this.smtp=smtp;this.properties=properties;}
    /** Recovery is independent of ON/OFF and each candidate has its own parent-first transaction. */
    public void recoverExpired(){for(long id:transactions.expiredIds())transactions.recoverOne(id);}
    /** Bounded to one request. Reserve the existing worker before acquiring a claim. */
    public void deliverOne(){
        if(!properties.enabled())return;
        var executor=smtp.getIfAvailable();if(executor==null)throw new IllegalStateException("Enabled notification SMTP executor required");
        var reservation=executor.reserve();if(reservation.isEmpty())return;
        try(var slot=reservation.get()){
            var claim=transactions.claim(owner);if(claim.isEmpty())return;
            var mail=transactions.prepare(claim.get());if(mail.isEmpty())return;
            var outcome=slot.send(mail.get(),properties.smtp().sendDeadline());
            transactions.complete(claim.get(),outcome.result(),outcome.code());
        }
    }
}
