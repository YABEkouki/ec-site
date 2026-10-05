package com.example.ecsite.entity;

public record PaymentTransactionInitiator(
        PaymentTransactionInitiatorType type,
        Long id,
        String username,
        String internalNote) {

    public PaymentTransactionInitiator {
        if (type == null) {
            throw new IllegalArgumentException("決済操作開始者種別は必須です。");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("決済操作開始者名は必須です。");
        }
        if (username.length() > 255) {
            throw new IllegalArgumentException("決済操作開始者名は255文字以内である必要があります。");
        }
        if (internalNote != null && internalNote.length() > 1000) {
            throw new IllegalArgumentException("内部メモは1000文字以内である必要があります。");
        }
    }
}
