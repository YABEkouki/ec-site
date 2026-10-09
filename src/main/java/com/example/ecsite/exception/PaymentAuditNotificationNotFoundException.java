package com.example.ecsite.exception;

public class PaymentAuditNotificationNotFoundException extends RuntimeException {
    public PaymentAuditNotificationNotFoundException(Long id) {
        super("通知ID " + id + " は存在しません。");
    }
}
