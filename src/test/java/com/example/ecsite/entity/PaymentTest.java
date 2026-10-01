package com.example.ecsite.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class PaymentTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 1, 10, 0);

    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 10, 1, 10, 5);

    @Test
    void newPaymentStartsPending() {
        Payment payment = createPayment();

        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        assertEquals(10_000, payment.getAmount());
        assertEquals(CREATED_AT, payment.getCreatedAt());
        assertEquals(CREATED_AT, payment.getUpdatedAt());
    }

    @Test
    void pendingCanBecomeRequiresAction() {
        Payment payment = createPayment();

        payment.markRequiresAction(UPDATED_AT);

        assertEquals(PaymentStatus.REQUIRES_ACTION, payment.getStatus());
        assertEquals(UPDATED_AT, payment.getUpdatedAt());
    }

    @Test
    void pendingCanBecomeAuthorized() {
        Payment payment = createPayment();

        payment.markAuthorized(UPDATED_AT);

        assertEquals(PaymentStatus.AUTHORIZED, payment.getStatus());
    }

    @Test
    void requiresActionCanBecomeAuthorized() {
        Payment payment = createPayment();
        payment.markRequiresAction(UPDATED_AT);

        LocalDateTime authorizedAt = UPDATED_AT.plusMinutes(1);
        payment.markAuthorized(authorizedAt);

        assertEquals(PaymentStatus.AUTHORIZED, payment.getStatus());
        assertEquals(authorizedAt, payment.getUpdatedAt());
    }

    @Test
    void authorizedCanBecomeCaptured() {
        Payment payment = createPayment();
        payment.markAuthorized(UPDATED_AT);

        payment.markCaptured(UPDATED_AT.plusMinutes(1));

        assertEquals(PaymentStatus.CAPTURED, payment.getStatus());
    }

    @Test
    void authorizedCanBecomeCancelled() {
        Payment payment = createPayment();
        payment.markAuthorized(UPDATED_AT);

        payment.markCancelled(UPDATED_AT.plusMinutes(1));

        assertEquals(PaymentStatus.CANCELLED, payment.getStatus());
    }

    @Test
    void pendingCanBecomeFailed() {
        Payment payment = createPayment();

        payment.markFailed(UPDATED_AT);

        assertEquals(PaymentStatus.FAILED, payment.getStatus());
    }

    @Test
    void requiresActionCanBecomeFailed() {
        Payment payment = createPayment();
        payment.markRequiresAction(UPDATED_AT);

        payment.markFailed(UPDATED_AT.plusMinutes(1));

        assertEquals(PaymentStatus.FAILED, payment.getStatus());
    }

    @Test
    void pendingCannotBecomeCaptured() {
        Payment payment = createPayment();

        assertThrows(
                IllegalStateException.class,
                () -> payment.markCaptured(UPDATED_AT));
    }

    @Test
    void capturedCannotBecomeCancelled() {
        Payment payment = createPayment();
        payment.markAuthorized(UPDATED_AT);
        payment.markCaptured(UPDATED_AT.plusMinutes(1));

        assertThrows(
                IllegalStateException.class,
                () -> payment.markCancelled(UPDATED_AT.plusMinutes(2)));
    }

    @Test
    void cancelledCannotBecomeAuthorizedAgain() {
        Payment payment = createPayment();
        payment.markAuthorized(UPDATED_AT);
        payment.markCancelled(UPDATED_AT.plusMinutes(1));

        assertThrows(
                IllegalStateException.class,
                () -> payment.markAuthorized(UPDATED_AT.plusMinutes(2)));
    }

    @Test
    void failedCannotBecomeAuthorizedAgain() {
        Payment payment = createPayment();
        payment.markFailed(UPDATED_AT);

        assertThrows(
                IllegalStateException.class,
                () -> payment.markAuthorized(UPDATED_AT.plusMinutes(1)));
    }

    @Test
    void amountMustBePositive() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Payment(
                        new Order(),
                        PaymentProvider.MOCK,
                        PaymentMethod.CARD,
                        0,
                        CREATED_AT));
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
