package com.example.ecsite.service;

public class PaymentAuditNotificationHandlingConflictException extends RuntimeException {
    private final boolean unknownChanged;
    public boolean isUnknownChanged() { return unknownChanged; }
    public PaymentAuditNotificationHandlingConflictException() { this(false); }
    public PaymentAuditNotificationHandlingConflictException(boolean unknownChanged) {
        super("通知または対応状態が更新されています。再読み込みしてください。");
        this.unknownChanged = unknownChanged;
    }
}
