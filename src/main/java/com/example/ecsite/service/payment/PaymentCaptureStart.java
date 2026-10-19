package com.example.ecsite.service.payment;

public record PaymentCaptureStart(
        Long paymentId,
        Long transactionId,
        String providerPaymentId,
        int amount,
        String idempotencyKey,
        PaymentStartDisposition disposition,
        PaymentOperationSnapshot snapshot, CaptureShipmentOperations.InitialPost initialPost) {
    public PaymentCaptureStart {
        if ((disposition == PaymentStartDisposition.NEW) != (initialPost != null))
            throw new IllegalArgumentException("Only NEW may carry an initial POST capability");
        if (disposition == PaymentStartDisposition.NEW && (snapshot == null || !snapshot.equals(initialPost.snapshot())
                || !java.util.Objects.equals(paymentId, snapshot.paymentId())
                || !java.util.Objects.equals(transactionId, snapshot.transactionId())
                || !java.util.Objects.equals(providerPaymentId, snapshot.providerPaymentId())
                || amount != snapshot.amount() || !java.util.Objects.equals(idempotencyKey, snapshot.idempotencyKey())))
            throw new IllegalArgumentException("Initial POST capability does not match the start result");
    }
    public PaymentCaptureStart(Long paymentId, Long transactionId, String providerPaymentId, int amount,
            String idempotencyKey, PaymentStartDisposition disposition, PaymentOperationSnapshot snapshot) {
        this(paymentId, transactionId, providerPaymentId, amount, idempotencyKey, disposition, snapshot, null);
    }
}
