package com.example.ecsite.service.payment;

public enum PaymentResultApplication {
    APPLIED, ALREADY_APPLIED, IGNORED_STALE, CONFLICT;

    public void requireAccepted() {
        if (this == IGNORED_STALE || this == CONFLICT) {
            throw new IllegalStateException("決済結果と現在の操作が一致しません。注文状態を再確認してください。");
        }
    }
}
