package com.example.ecsite.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class PaymentTransactionTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 1, 10, 0);

    private static final LocalDateTime COMPLETED_AT = LocalDateTime.of(2026, 10, 1, 10, 1);

    @Test
    void newTransactionStartsPending() {
        PaymentTransaction transaction = createTransaction();

        assertEquals(PaymentTransactionStatus.PENDING, transaction.getStatus());
        assertEquals(PaymentTransactionType.AUTHORIZE, transaction.getTransactionType());
        assertEquals(10_000, transaction.getAmount());
        assertEquals(3, transaction.getOrderContentRevision());
        assertEquals("payment-test-key", transaction.getIdempotencyKey());
        assertEquals(CREATED_AT, transaction.getCreatedAt());
        assertNull(transaction.getCompletedAt());
    }

    @Test
    void pendingCanBecomeSucceeded() {
        PaymentTransaction transaction = createTransaction();

        transaction.markSucceeded("provider-tx-1", COMPLETED_AT);

        assertEquals(PaymentTransactionStatus.SUCCEEDED, transaction.getStatus());
        assertEquals("provider-tx-1", transaction.getProviderTransactionId());
        assertNull(transaction.getFailureCode());
        assertNull(transaction.getFailureMessage());
        assertEquals(COMPLETED_AT, transaction.getCompletedAt());
    }

    @Test
    void pendingCanBecomeFailed() {
        PaymentTransaction transaction = createTransaction();

        transaction.markFailed(
                "provider-tx-2",
                "card_declined",
                "Card was declined",
                COMPLETED_AT);

        assertEquals(PaymentTransactionStatus.FAILED, transaction.getStatus());
        assertEquals("provider-tx-2", transaction.getProviderTransactionId());
        assertEquals("card_declined", transaction.getFailureCode());
        assertEquals("Card was declined", transaction.getFailureMessage());
        assertEquals(COMPLETED_AT, transaction.getCompletedAt());
    }

    @Test
    void succeededTransactionCannotBeChangedToFailed() {
        PaymentTransaction transaction = createTransaction();
        transaction.markSucceeded("provider-tx-1", COMPLETED_AT);

        assertThrows(
                IllegalStateException.class,
                () -> transaction.markFailed(
                        "provider-tx-1",
                        "error",
                        "error",
                        COMPLETED_AT.plusMinutes(1)));
    }

    @Test
    void failedTransactionCannotBeChangedToSucceeded() {
        PaymentTransaction transaction = createTransaction();
        transaction.markFailed(
                "provider-tx-2",
                "card_declined",
                "Card was declined",
                COMPLETED_AT);

        assertThrows(
                IllegalStateException.class,
                () -> transaction.markSucceeded(
                        "provider-tx-2",
                        COMPLETED_AT.plusMinutes(1)));
    }

    @Test
    void amountMustBePositive() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PaymentTransaction(
                        createPayment(),
                        PaymentTransactionType.AUTHORIZE,
                        0,
                        0,
                        "payment-test-key",
                        CREATED_AT));
    }

    @Test
    void orderContentRevisionCannotBeNegative() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PaymentTransaction(
                        createPayment(),
                        PaymentTransactionType.AUTHORIZE,
                        10_000,
                        -1,
                        "payment-test-key",
                        CREATED_AT));
    }

    @Test
    void idempotencyKeyIsRequired() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PaymentTransaction(
                        createPayment(),
                        PaymentTransactionType.AUTHORIZE,
                        10_000,
                        0,
                        " ",
                        CREATED_AT));
    }

    private PaymentTransaction createTransaction() {
        return new PaymentTransaction(
                createPayment(),
                PaymentTransactionType.AUTHORIZE,
                10_000,
                3,
                "payment-test-key",
                CREATED_AT);
    }

    private Payment createPayment() {
        return new Payment(
                new Order(),
                PaymentProvider.MOCK,
                PaymentMethod.CARD,
                10_000,
                CREATED_AT);
    }
}
