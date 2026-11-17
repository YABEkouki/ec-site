package com.example.ecsite.service.notification;

/** Feature133-B: one prepared customer mail; its text is already stored in the attempt row before sending. */
public record CustomerOrderNotificationMail(String recipient, String subject, String body) {
    @Override public String toString() {
        return "CustomerOrderNotificationMail[redacted]";
    }
}
