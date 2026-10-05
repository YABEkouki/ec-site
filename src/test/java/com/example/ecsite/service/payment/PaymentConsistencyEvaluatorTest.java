package com.example.ecsite.service.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.Payment;
import com.example.ecsite.entity.PaymentTransaction;

class PaymentConsistencyEvaluatorTest {

    private final PaymentConsistencyEvaluator evaluator =
            new PaymentConsistencyEvaluator();

    @Test
    void returnsConsistentWhenAmountsAndRevisionMatch() {

        Order order = mock(Order.class);
        Payment payment = mock(Payment.class);
        PaymentTransaction authorizationTransaction =
                mock(PaymentTransaction.class);

        when(order.getTotalAmount()).thenReturn(5_500);
        when(order.getContentRevision()).thenReturn(2);
        when(payment.getAmount()).thenReturn(5_500);
        when(authorizationTransaction.getAmount()).thenReturn(5_500);
        when(authorizationTransaction.getOrderContentRevision()).thenReturn(2);

        PaymentConsistency result = evaluator.evaluate(
                order,
                payment,
                authorizationTransaction);

        assertEquals(
                PaymentConsistencyStatus.CONSISTENT,
                result.status());

        assertEquals(5_500, result.orderAmount());
        assertEquals(5_500, result.paymentAmount());
        assertEquals(5_500, result.authorizationAmount());
        assertEquals(2, result.orderContentRevision());
        assertEquals(2, result.authorizationContentRevision());
        assertTrue(result.canCapture());
    }

    @Test
    void returnsAmountDecreasedWhenOrderAmountIsLower() {

        PaymentConsistency result = evaluate(
                4_550,
                5_500,
                5_500,
                1,
                0);

        assertEquals(
                PaymentConsistencyStatus.AMOUNT_DECREASED,
                result.status());

        assertFalse(result.canCapture());
    }

    @Test
    void returnsAmountIncreasedWhenOrderAmountIsHigher() {

        PaymentConsistency result = evaluate(
                7_700,
                5_500,
                5_500,
                1,
                0);

        assertEquals(
                PaymentConsistencyStatus.AMOUNT_INCREASED,
                result.status());

        assertFalse(result.canCapture());
    }

    @Test
    void returnsRevisionMismatchWhenAmountsMatchButRevisionDiffers() {

        PaymentConsistency result = evaluate(
                5_500,
                5_500,
                5_500,
                1,
                0);

        assertEquals(
                PaymentConsistencyStatus.REVISION_MISMATCH,
                result.status());

        assertFalse(result.canCapture());
    }

    @Test
    void returnsAuthorizationMismatchWhenPaymentAndAuthorizationAmountsDiffer() {

        PaymentConsistency result = evaluate(
                5_500,
                5_500,
                5_000,
                1,
                1);

        assertEquals(
                PaymentConsistencyStatus.AUTHORIZATION_MISMATCH,
                result.status());

        assertFalse(result.canCapture());
    }

    @Test
    void authorizationMismatchTakesPriorityOverOrderAmountDifference() {

        PaymentConsistency result = evaluate(
                4_000,
                5_500,
                5_000,
                1,
                0);

        assertEquals(
                PaymentConsistencyStatus.AUTHORIZATION_MISMATCH,
                result.status());

        assertFalse(result.canCapture());
    }

    private PaymentConsistency evaluate(
            int orderAmount,
            int paymentAmount,
            int authorizationAmount,
            int orderContentRevision,
            int authorizationContentRevision) {

        Order order = mock(Order.class);
        Payment payment = mock(Payment.class);
        PaymentTransaction authorizationTransaction =
                mock(PaymentTransaction.class);

        when(order.getTotalAmount()).thenReturn(orderAmount);
        when(order.getContentRevision()).thenReturn(orderContentRevision);
        when(payment.getAmount()).thenReturn(paymentAmount);
        when(authorizationTransaction.getAmount()).thenReturn(authorizationAmount);
        when(authorizationTransaction.getOrderContentRevision())
                .thenReturn(authorizationContentRevision);

        return evaluator.evaluate(
                order,
                payment,
                authorizationTransaction);
    }
}
