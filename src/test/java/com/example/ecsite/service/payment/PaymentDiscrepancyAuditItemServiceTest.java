package com.example.ecsite.service.payment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.ecsite.entity.Payment;
import com.example.ecsite.entity.PaymentDiscrepancy;
import com.example.ecsite.entity.PaymentDiscrepancyRecordStatus;
import com.example.ecsite.entity.PaymentMethod;
import com.example.ecsite.entity.PaymentProvider;
import com.example.ecsite.entity.PaymentStatus;
import com.example.ecsite.entity.PaymentTransaction;
import com.example.ecsite.entity.PaymentTransactionStatus;
import com.example.ecsite.payment.PaymentFlowState;
import com.example.ecsite.payment.PaymentFlowStatus;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.repository.PaymentDiscrepancyRepository;
import com.example.ecsite.repository.PaymentRepository;
import com.example.ecsite.repository.PaymentTransactionRepository;

class PaymentDiscrepancyAuditItemServiceTest {

    private PaymentRepository paymentRepository;
    private PaymentTransactionRepository transactionRepository;
    private PaymentDiscrepancyRepository discrepancyRepository;
    private PaymentGateway paymentGateway;

    private PaymentDiscrepancyAuditItemService service;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        transactionRepository = mock(PaymentTransactionRepository.class);
        discrepancyRepository = mock(PaymentDiscrepancyRepository.class);
        paymentGateway = mock(PaymentGateway.class);

        Clock clock = Clock.fixed(
                Instant.parse("2026-10-06T03:00:00Z"),
                ZoneId.of("Asia/Tokyo"));

        service = new PaymentDiscrepancyAuditItemService(
                paymentRepository,
                transactionRepository,
                discrepancyRepository,
                paymentGateway,
                new PaymentDiscrepancyEvaluator(),
                clock);
    }

    @Test
    void skipsWhenPaymentDoesNotExist() {
        when(paymentRepository.findById(1L))
                .thenReturn(Optional.empty());

        service.audit(1L);

        verify(paymentGateway, never())
                .retrievePaymentFlow(any());
    }

    @Test
    void skipsWhenPendingTransactionExists() {
        Payment payment = payment(
                PaymentStatus.AUTHORIZED,
                "pfw_test");

        PaymentTransaction transaction = mock(PaymentTransaction.class);

        when(transaction.getStatus())
                .thenReturn(PaymentTransactionStatus.PENDING);

        when(paymentRepository.findById(1L))
                .thenReturn(Optional.of(payment));

        when(transactionRepository
                .findByPaymentIdOrderByCreatedAtAscIdAsc(1L))
                .thenReturn(List.of(transaction));

        service.audit(1L);

        verify(paymentGateway, never())
                .retrievePaymentFlow(any());
    }

    @Test
    void createsOpenDiscrepancyWhenStatusesAreInconsistent() {
        Payment payment = payment(
                PaymentStatus.AUTHORIZED,
                "pfw_test");

        when(paymentRepository.findById(1L))
                .thenReturn(Optional.of(payment));

        when(transactionRepository
                .findByPaymentIdOrderByCreatedAtAscIdAsc(1L))
                .thenReturn(List.of());

        when(paymentGateway.retrievePaymentFlow("pfw_test"))
                .thenReturn(paymentFlowState(
                        PaymentFlowStatus.SUCCEEDED));

        when(discrepancyRepository
                .findByPaymentIdAndLocalStatusAndProviderStatusAndStatus(
                        1L,
                        PaymentStatus.AUTHORIZED,
                        PaymentFlowStatus.SUCCEEDED,
                        PaymentDiscrepancyRecordStatus.OPEN))
                .thenReturn(Optional.empty());

        service.audit(1L);

        verify(discrepancyRepository)
                .save(any(PaymentDiscrepancy.class));
    }

    @Test
    void resolvesOpenDiscrepancyWhenStatusesAreConsistent() {
        Payment payment = payment(
                PaymentStatus.AUTHORIZED,
                "pfw_test");

        PaymentDiscrepancy discrepancy = mock(PaymentDiscrepancy.class);

        when(paymentRepository.findById(1L))
                .thenReturn(Optional.of(payment));

        when(transactionRepository
                .findByPaymentIdOrderByCreatedAtAscIdAsc(1L))
                .thenReturn(List.of());

        when(paymentGateway.retrievePaymentFlow("pfw_test"))
                .thenReturn(paymentFlowState(
                        PaymentFlowStatus.REQUIRES_CAPTURE));

        when(discrepancyRepository.findByPaymentIdAndStatus(
                1L,
                PaymentDiscrepancyRecordStatus.OPEN))
                .thenReturn(List.of(discrepancy));

        service.audit(1L);

        verify(discrepancy)
                .resolve(any());

        verify(discrepancyRepository, never())
                .save(any());
    }

    @Test
    void updatesExistingOpenDiscrepancyWhenSameMismatchIsDetectedAgain() {
        Payment payment = payment(
                PaymentStatus.AUTHORIZED,
                "pfw_test");

        PaymentDiscrepancy discrepancy = mock(PaymentDiscrepancy.class);

        when(paymentRepository.findById(1L))
                .thenReturn(Optional.of(payment));

        when(transactionRepository
                .findByPaymentIdOrderByCreatedAtAscIdAsc(1L))
                .thenReturn(List.of());

        when(paymentGateway.retrievePaymentFlow("pfw_test"))
                .thenReturn(paymentFlowState(
                        PaymentFlowStatus.SUCCEEDED));

        when(discrepancyRepository
                .findByPaymentIdAndLocalStatusAndProviderStatusAndStatus(
                        1L,
                        PaymentStatus.AUTHORIZED,
                        PaymentFlowStatus.SUCCEEDED,
                        PaymentDiscrepancyRecordStatus.OPEN))
                .thenReturn(Optional.of(discrepancy));

        service.audit(1L);

        verify(discrepancy).detectAgain(any());

        verify(discrepancyRepository, never())
                .save(any());
    }

    @Test
    void doesNothingWhenProviderStatusIsInProgress() {
        Payment payment = payment(
                PaymentStatus.PENDING,
                "pfw_test");

        when(paymentRepository.findById(1L))
                .thenReturn(Optional.of(payment));

        when(transactionRepository
                .findByPaymentIdOrderByCreatedAtAscIdAsc(1L))
                .thenReturn(List.of());

        when(paymentGateway.retrievePaymentFlow("pfw_test"))
                .thenReturn(paymentFlowState(
                        PaymentFlowStatus.PROCESSING));

        service.audit(1L);

        verify(discrepancyRepository, never())
                .save(any());

        verify(discrepancyRepository, never())
                .findByPaymentIdAndStatus(
                        any(),
                        any());
    }

    @Test
    void skipsWhenProviderPaymentIdIsBlank() {
        Payment payment = payment(
                PaymentStatus.AUTHORIZED,
                " ");

        when(paymentRepository.findById(1L))
                .thenReturn(Optional.of(payment));

        service.audit(1L);

        verify(paymentGateway, never())
                .retrievePaymentFlow(any());

        verify(transactionRepository, never())
                .findByPaymentIdOrderByCreatedAtAscIdAsc(any());
    }

    private Payment payment(
            PaymentStatus status,
            String providerPaymentId) {

        Payment payment = mock(Payment.class);

        when(payment.getId()).thenReturn(1L);
        when(payment.getProvider()).thenReturn(PaymentProvider.PAYJP);
        when(payment.getPaymentMethod()).thenReturn(PaymentMethod.CARD);
        when(payment.getStatus()).thenReturn(status);
        when(payment.getProviderPaymentId())
                .thenReturn(providerPaymentId);

        return payment;
    }

    private PaymentFlowState paymentFlowState(
            PaymentFlowStatus status) {

        return new PaymentFlowState(
                "pfw_test",
                status,
                null,
                null);
    }
}
