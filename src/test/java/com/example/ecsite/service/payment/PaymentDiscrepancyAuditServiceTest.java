package com.example.ecsite.service.payment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.domain.Pageable;

import com.example.ecsite.config.PaymentDiscrepancyAuditProperties;
import com.example.ecsite.entity.Payment;
import com.example.ecsite.entity.PaymentMethod;
import com.example.ecsite.entity.PaymentProvider;
import com.example.ecsite.repository.PaymentRepository;

class PaymentDiscrepancyAuditServiceTest {

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);

    private final PaymentDiscrepancyAuditRetryFacade itemService = mock(PaymentDiscrepancyAuditRetryFacade.class);

    private final PaymentDiscrepancyAuditProperties properties = new PaymentDiscrepancyAuditProperties(
            true,
            Duration.ofMinutes(5),
            100);

    private final PaymentDiscrepancyAuditService service = new PaymentDiscrepancyAuditService(
            paymentRepository,
            itemService,
            properties);

    @Test
    void auditsEligiblePaymentsInOrder() {
        Payment payment1 = payment(10L);
        Payment payment2 = payment(20L);

        org.mockito.Mockito.when(
                paymentRepository
                        .findByProviderAndPaymentMethodAndProviderPaymentIdIsNotNullAndIdGreaterThanOrderByIdAsc(
                                eq(PaymentProvider.PAYJP),
                                eq(PaymentMethod.CARD),
                                eq(0L),
                                any(Pageable.class)))
                .thenReturn(List.of(payment1, payment2));

        service.auditPayments();

        InOrder order = inOrder(itemService);
        order.verify(itemService).audit(10L);
        order.verify(itemService).audit(20L);
    }

    @Test
    void continuesFromLastPaymentIdOnNextRun() {
        Payment payment1 = payment(10L);
        Payment payment2 = payment(20L);
        Payment payment3 = payment(30L);

        org.mockito.Mockito.when(
                paymentRepository
                        .findByProviderAndPaymentMethodAndProviderPaymentIdIsNotNullAndIdGreaterThanOrderByIdAsc(
                                eq(PaymentProvider.PAYJP),
                                eq(PaymentMethod.CARD),
                                eq(0L),
                                any(Pageable.class)))
                .thenReturn(List.of(payment1, payment2));

        org.mockito.Mockito.when(
                paymentRepository
                        .findByProviderAndPaymentMethodAndProviderPaymentIdIsNotNullAndIdGreaterThanOrderByIdAsc(
                                eq(PaymentProvider.PAYJP),
                                eq(PaymentMethod.CARD),
                                eq(20L),
                                any(Pageable.class)))
                .thenReturn(List.of(payment3));

        service.auditPayments();
        service.auditPayments();

        InOrder order = inOrder(itemService);
        order.verify(itemService).audit(10L);
        order.verify(itemService).audit(20L);
        order.verify(itemService).audit(30L);
    }

    @Test
    void wrapsToBeginningAfterReachingEnd() {
        Payment payment1 = payment(10L);
        Payment payment2 = payment(20L);

        org.mockito.Mockito.when(
                paymentRepository
                        .findByProviderAndPaymentMethodAndProviderPaymentIdIsNotNullAndIdGreaterThanOrderByIdAsc(
                                eq(PaymentProvider.PAYJP),
                                eq(PaymentMethod.CARD),
                                eq(0L),
                                any(Pageable.class)))
                .thenReturn(List.of(payment1));

        org.mockito.Mockito.when(
                paymentRepository
                        .findByProviderAndPaymentMethodAndProviderPaymentIdIsNotNullAndIdGreaterThanOrderByIdAsc(
                                eq(PaymentProvider.PAYJP),
                                eq(PaymentMethod.CARD),
                                eq(10L),
                                any(Pageable.class)))
                .thenReturn(List.of(payment2));

        org.mockito.Mockito.when(
                paymentRepository
                        .findByProviderAndPaymentMethodAndProviderPaymentIdIsNotNullAndIdGreaterThanOrderByIdAsc(
                                eq(PaymentProvider.PAYJP),
                                eq(PaymentMethod.CARD),
                                eq(20L),
                                any(Pageable.class)))
                .thenReturn(List.of());

        service.auditPayments();
        service.auditPayments();
        service.auditPayments();

        verify(itemService, org.mockito.Mockito.times(2))
                .audit(10L);
        verify(itemService).audit(20L);
    }

    @Test
    void continuesWhenOnePaymentFails() {
        Payment payment1 = payment(10L);
        Payment payment2 = payment(20L);

        org.mockito.Mockito.when(
                paymentRepository
                        .findByProviderAndPaymentMethodAndProviderPaymentIdIsNotNullAndIdGreaterThanOrderByIdAsc(
                                eq(PaymentProvider.PAYJP),
                                eq(PaymentMethod.CARD),
                                eq(0L),
                                any(Pageable.class)))
                .thenReturn(List.of(payment1, payment2));

        doThrow(new RuntimeException("test"))
                .when(itemService)
                .audit(10L);

        service.auditPayments();

        verify(itemService).audit(10L);
        verify(itemService).audit(20L);
    }

    private Payment payment(Long id) {
        Payment payment = mock(Payment.class);
        org.mockito.Mockito.when(payment.getId())
                .thenReturn(id);
        return payment;
    }

}

