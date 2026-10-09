package com.example.ecsite.service.payment;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.example.ecsite.config.PaymentDiscrepancyAuditNotificationProperties;

/** Coordinates one notification per tick. Commit failures never trigger an immediate SMTP replay. */
@Service
@ConditionalOnProperty(prefix = "app.payment.discrepancy-audit.notification", name = "enabled", havingValue = "true")
public class PaymentAuditNotificationDeliveryService {
    private final UUID owner = UUID.randomUUID();
    private final PaymentAuditNotificationObservationService observer;
    private final PaymentAuditNotificationDeliveryTransaction transactions;
    private final PaymentAuditNotificationSmtpExecutor smtp;
    private final PaymentDiscrepancyAuditNotificationProperties properties;

    public PaymentAuditNotificationDeliveryService(PaymentAuditNotificationObservationService observer,
            PaymentAuditNotificationDeliveryTransaction transactions, PaymentAuditNotificationSmtpExecutor smtp,
            PaymentDiscrepancyAuditNotificationProperties properties) {
        this.observer = observer; this.transactions = transactions; this.smtp = smtp; this.properties = properties;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void tick() {
        if (!properties.enabled()) return;
        observer.observe();
        transactions.recoverExpired();
        // Reserve the zero-queue worker before claiming or incrementing an attempt count.
        var reservation = smtp.reserve();
        if (reservation.isEmpty()) return;
        try (var slot = reservation.get()) {
            var claim = transactions.claim(owner);
            if (claim.isEmpty()) return;
            var mail = transactions.prepare(claim.get());
            if (mail.isEmpty()) return;
            // Proxy returned after commit. No transaction, connection or row lock spans SMTP.
            var outcome = slot.send(mail.get(), properties.smtp().sendDeadline());
            transactions.complete(claim.get(), outcome.result(), outcome.code());
        }
    }
}
