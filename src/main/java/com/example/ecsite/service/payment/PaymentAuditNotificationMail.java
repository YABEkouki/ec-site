package com.example.ecsite.service.payment;

import java.util.List;

/** Detached immutable attempt snapshot. Never carries JPA entities into the SMTP thread. */
public record PaymentAuditNotificationMail(String from, List<String> recipients, String subject, String body) {
    public PaymentAuditNotificationMail {
        recipients = List.copyOf(recipients);
    }
    @Override public String toString() { return "PaymentAuditNotificationMail[recipientCount=" + recipients.size() + "]"; }
}
