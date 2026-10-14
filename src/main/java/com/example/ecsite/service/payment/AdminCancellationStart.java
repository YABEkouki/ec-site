package com.example.ecsite.service.payment;

/** The local cancellation is already committed when this start result reaches the orchestrator. */
public record AdminCancellationStart(boolean localCancelled, PaymentCancellationStart paymentStart) {
    public AdminCancellationStart {
        if (localCancelled == (paymentStart != null)) {
            throw new IllegalArgumentException("取消開始結果が不正です。");
        }
    }

    public static AdminCancellationStart local() {
        return new AdminCancellationStart(true, null);
    }

    public static AdminCancellationStart provider(PaymentCancellationStart start) {
        return new AdminCancellationStart(false, start);
    }
}
