package com.example.ecsite.service.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.repository.PaymentRepository;
import com.example.ecsite.repository.PaymentTransactionRepository;
import com.example.ecsite.service.OrderModificationGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentShippingPreparationValidationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 16, 14, 0);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final PaymentTransactionRepository transactions = mock(PaymentTransactionRepository.class);
    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final OrderModificationGuard modificationGuard = mock(OrderModificationGuard.class);
    private final PaymentService service = new PaymentService(payments, transactions,
            new PaymentConsistencyEvaluator(), modificationGuard, mock(CaptureShipmentOperations.class));

    private Order order(int amount, int revision) {
        Order order = new Order(1L, amount, NOW.minusHours(4), NOW);
        ReflectionTestUtils.setField(order, "id", 10L);
        for (int i = 0; i < revision; i++) order.incrementContentRevision();
        return order;
    }

    private Payment payment(Order order, long id, PaymentStatus status) {
        Payment payment = new Payment(order, PaymentProvider.PAYJP, PaymentMethod.CARD, 1000, NOW);
        ReflectionTestUtils.setField(payment, "id", id);
        payment.setProviderPaymentId("pf_" + id, NOW);
        switch (status) {
            case PENDING -> { }
            case REQUIRES_ACTION -> payment.markRequiresAction(NOW);
            case AUTHORIZED -> payment.markAuthorized(NOW);
            case CAPTURED -> { payment.markAuthorized(NOW); payment.markCaptured(NOW); }
            case CANCELLED -> { payment.markAuthorized(NOW); payment.markCancelled(NOW); }
            case FAILED -> payment.markFailed(NOW);
        }
        return payment;
    }

    private void authorization(Payment payment, int amount, int revision) {
        var authorization = new PaymentTransaction(payment, PaymentTransactionType.AUTHORIZE,
                amount, revision, UUID.randomUUID().toString(), NOW);
        authorization.markSucceeded(payment.getProviderPaymentId(), NOW);
        when(transactions.findByPaymentIdAndTransactionTypeAndStatus(payment.getId(),
                PaymentTransactionType.AUTHORIZE, PaymentTransactionStatus.SUCCEEDED))
                .thenReturn(Optional.of(authorization));
    }

    private void verifyNoWrites() {
        verify(payments, never()).save(any());
        verify(payments, never()).saveAndFlush(any());
        verify(transactions, never()).save(any());
        verify(transactions, never()).saveAndFlush(any());
        verifyNoInteractions(gateway, modificationGuard);
    }

    @ParameterizedTest
    @CsvSource({"1000,0", "700,1"})
    void acceptsConsistentOrDecreasedAmountWithoutRequiringRevisionEquality(int amount, int revision) {
        Order order = order(amount, revision);
        Payment payment = payment(order, 20L, PaymentStatus.AUTHORIZED);
        when(payments.findByOrderIdForUpdate(10L)).thenReturn(List.of(payment));
        authorization(payment, 1000, 0);
        service.validateCardPaymentForShippingPreparation(order);
        var calls = inOrder(payments, transactions);
        calls.verify(payments).findByOrderIdForUpdate(10L);
        calls.verify(transactions).findByPaymentIdOrderByCreatedAtAscIdAsc(20L);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(payment.getAmount()).isEqualTo(1000);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.ORDERED);
        assertThat(order.getTotalAmount()).isEqualTo(amount);
        assertThat(order.getContentRevision()).isEqualTo(revision);
        verifyNoWrites();
    }

    @ParameterizedTest
    @CsvSource({"1200,0,1000", "1000,1,1000", "1000,0,900"})
    void rejectsIncreasedAmountRevisionMismatchAndAuthorizationMismatch(int amount, int revision, int authorized) {
        Order order = order(amount, revision);
        Payment payment = payment(order, 20L, PaymentStatus.AUTHORIZED);
        when(payments.findByOrderIdForUpdate(10L)).thenReturn(List.of(payment));
        authorization(payment, authorized, 0);
        assertThatThrownBy(() -> service.validateCardPaymentForShippingPreparation(order)).isInstanceOf(IllegalStateException.class);
        verifyNoWrites();
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "\t\n", "\u3000"})
    void rejectsMissingOrBlankProviderId(String providerId) {
        Order order = order(1000, 0);
        Payment payment = payment(order, 20L, PaymentStatus.AUTHORIZED);
        payment.setProviderPaymentId(providerId, NOW);
        when(payments.findByOrderIdForUpdate(10L)).thenReturn(List.of(payment));
        assertThatThrownBy(() -> service.validateCardPaymentForShippingPreparation(order)).isInstanceOf(IllegalStateException.class);
        verifyNoWrites();
    }

    @Test void rejectsNoActivePayment() {
        Order order = order(1000, 0);
        when(payments.findByOrderIdForUpdate(10L)).thenReturn(List.of(
                payment(order, 20L, PaymentStatus.FAILED), payment(order, 21L, PaymentStatus.CANCELLED)));
        assertThatThrownBy(() -> service.validateCardPaymentForShippingPreparation(order)).isInstanceOf(IllegalStateException.class);
        verifyNoWrites();
    }

    @Test void rejectsNoPayments() {
        assertThatThrownBy(() -> service.validateCardPaymentForShippingPreparation(order(1000, 0))).isInstanceOf(IllegalStateException.class);
        verifyNoWrites();
    }

    @Test void rejectsMultipleActivePayments() {
        Order order = order(1000, 0);
        when(payments.findByOrderIdForUpdate(10L)).thenReturn(List.of(
                payment(order, 20L, PaymentStatus.AUTHORIZED), payment(order, 21L, PaymentStatus.PENDING)));
        assertThatThrownBy(() -> service.validateCardPaymentForShippingPreparation(order)).isInstanceOf(IllegalStateException.class);
        verifyNoWrites();
    }

    @Test void ignoresFinishedPaymentHistoryForActiveCount() {
        Order order = order(1000, 0);
        Payment active = payment(order, 22L, PaymentStatus.AUTHORIZED);
        when(payments.findByOrderIdForUpdate(10L)).thenReturn(List.of(
                payment(order, 20L, PaymentStatus.FAILED), payment(order, 21L, PaymentStatus.CANCELLED), active));
        authorization(active, 1000, 0);
        service.validateCardPaymentForShippingPreparation(order);
        verify(transactions).findByPaymentIdOrderByCreatedAtAscIdAsc(20L);
        verify(transactions).findByPaymentIdOrderByCreatedAtAscIdAsc(21L);
        verifyNoWrites();
    }

    @ParameterizedTest @EnumSource(PaymentTransactionType.class)
    void rejectsEveryPendingOperationOnActivePayment(PaymentTransactionType type) {
        assertPendingRejected(type, PaymentStatus.AUTHORIZED);
    }

    @ParameterizedTest @EnumSource(PaymentTransactionType.class)
    void rejectsEveryPendingOperationOnPastFailedPayment(PaymentTransactionType type) {
        assertPendingRejected(type, PaymentStatus.FAILED);
    }

    @ParameterizedTest @EnumSource(PaymentTransactionType.class)
    void rejectsEveryPendingOperationOnPastCancelledPayment(PaymentTransactionType type) {
        assertPendingRejected(type, PaymentStatus.CANCELLED);
    }

    private void assertPendingRejected(PaymentTransactionType type, PaymentStatus status) {
        Order order = order(1000, 0);
        Payment target = payment(order, 20L, status);
        List<Payment> list = status == PaymentStatus.AUTHORIZED ? List.of(target)
                : List.of(target, payment(order, 21L, PaymentStatus.AUTHORIZED));
        when(payments.findByOrderIdForUpdate(10L)).thenReturn(list);
        var pending = new PaymentTransaction(target, type, 1000, 0, UUID.randomUUID().toString(), NOW);
        when(transactions.findByPaymentIdOrderByCreatedAtAscIdAsc(20L)).thenReturn(List.of(pending));
        assertThatThrownBy(() -> service.validateCardPaymentForShippingPreparation(order)).isInstanceOf(IllegalStateException.class);
        assertThat(pending.getStatus()).isEqualTo(PaymentTransactionStatus.PENDING);
        verifyNoWrites();
    }

    @ParameterizedTest @EnumSource(value = PaymentStatus.class, names = {"PENDING", "REQUIRES_ACTION", "CAPTURED"})
    void rejectsNonAuthorizedActivePayment(PaymentStatus status) {
        Order order = order(1000, 0);
        when(payments.findByOrderIdForUpdate(10L)).thenReturn(List.of(payment(order, 20L, status)));
        assertThatThrownBy(() -> service.validateCardPaymentForShippingPreparation(order)).isInstanceOf(IllegalStateException.class);
        verifyNoWrites();
    }

    @Test void rejectsOtherProvider() {
        Order order = order(1000, 0);
        Payment payment = new Payment(order, PaymentProvider.MOCK, PaymentMethod.CARD, 1000, NOW);
        payment.markAuthorized(NOW);
        when(payments.findByOrderIdForUpdate(10L)).thenReturn(List.of(payment));
        assertThatThrownBy(() -> service.validateCardPaymentForShippingPreparation(order)).isInstanceOf(IllegalStateException.class);
        verifyNoWrites();
    }

    @Test void rejectsPaymentBelongingToAnotherOrder() {
        Order target = order(1000, 0);
        Order other = order(1000, 0);
        ReflectionTestUtils.setField(other, "id", 99L);
        when(payments.findByOrderIdForUpdate(10L)).thenReturn(List.of(payment(other, 20L, PaymentStatus.AUTHORIZED)));
        assertThatThrownBy(() -> service.validateCardPaymentForShippingPreparation(target)).isInstanceOf(IllegalStateException.class);
        verifyNoWrites();
    }

    @Test void rejectsMissingSuccessfulAuthorization() {
        Order order = order(1000, 0);
        when(payments.findByOrderIdForUpdate(10L)).thenReturn(List.of(payment(order, 20L, PaymentStatus.AUTHORIZED)));
        assertThatThrownBy(() -> service.validateCardPaymentForShippingPreparation(order)).isInstanceOf(IllegalStateException.class);
        verifyNoWrites();
    }

    @ParameterizedTest @EnumSource(value = OrderStatus.class, names = {"PAID", "SHIPPED", "CANCELLED"})
    void rejectsOtherOrderStatesBeforeReadingPayments(OrderStatus status) {
        Order order = order(1000, 0);
        if (status == OrderStatus.CANCELLED) order.cancel(NOW);
        else { order.markAsPaid(); if (status == OrderStatus.SHIPPED) order.markAsShipped(); }
        assertThatThrownBy(() -> service.validateCardPaymentForShippingPreparation(order)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(payments, transactions, gateway);
    }
}
