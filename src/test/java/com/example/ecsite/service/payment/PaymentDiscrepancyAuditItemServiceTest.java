package com.example.ecsite.service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.PaymentDiscrepancyHandlingStatus;
import com.example.ecsite.entity.Payment;
import com.example.ecsite.entity.PaymentDiscrepancy;
import com.example.ecsite.entity.PaymentDiscrepancyRecordStatus;
import com.example.ecsite.entity.PaymentMethod;
import com.example.ecsite.entity.PaymentProvider;
import com.example.ecsite.entity.PaymentStatus;
import com.example.ecsite.entity.PaymentTransaction;
import com.example.ecsite.entity.PaymentTransactionStatus;
import com.example.ecsite.entity.PaymentTransactionType;
import com.example.ecsite.payment.PaymentFlowState;
import com.example.ecsite.payment.PaymentFlowStatus;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.payment.PaymentGatewayException;
import com.example.ecsite.repository.PaymentDiscrepancyRepository;
import com.example.ecsite.repository.PaymentRepository;
import com.example.ecsite.repository.PaymentTransactionRepository;

class PaymentDiscrepancyAuditItemServiceTest {

    private PaymentRepository paymentRepository;
    private PaymentTransactionRepository transactionRepository;
    private PaymentDiscrepancyRepository discrepancyRepository;
    private PaymentGateway paymentGateway;

    private PaymentDiscrepancyAuditItemService service;

    @AfterEach
    void neverInvokesPaymentOperationsOrPaymentWrites() {
        verify(paymentGateway, never()).prepareAuthorization(any());
        verify(paymentGateway, never()).retrieveAuthorization(any());
        verify(paymentGateway, never()).capture(any());
        verify(paymentGateway, never()).cancelAuthorization(any());
        verify(paymentRepository, never()).findByIdForUpdate(any());
        verify(paymentRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

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

        when(discrepancyRepository.save(any(PaymentDiscrepancy.class))).thenAnswer(invocation -> {
            PaymentDiscrepancy saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 10L);
            return saved;
        });

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
        when(discrepancy.getId()).thenReturn(10L);

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
        when(discrepancy.getId()).thenReturn(10L);

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

    @Test
    void returnsConsistentAndResolvesAllOpenRecordsWithoutChangingHandlingStatus() {
        Payment payment = realPayment(PaymentStatus.AUTHORIZED);
        PaymentDiscrepancy first = discrepancy(payment, 10L);
        PaymentDiscrepancy second = discrepancy(payment, 11L);
        LocalDateTime handlingChangedAt = LocalDateTime.of(2026, 10, 5, 10, 0);
        first.changeHandlingStatus(PaymentDiscrepancyHandlingStatus.IN_PROGRESS, handlingChangedAt);
        second.changeHandlingStatus(PaymentDiscrepancyHandlingStatus.CONFIRMED, handlingChangedAt);
        when(paymentGateway.retrievePaymentFlow("pfw_test"))
                .thenReturn(paymentFlowState(PaymentFlowStatus.REQUIRES_CAPTURE));
        when(discrepancyRepository.findByPaymentIdAndStatus(1L, PaymentDiscrepancyRecordStatus.OPEN))
                .thenReturn(List.of(first, second));

        PaymentDiscrepancyAuditResult result = service.auditWithResult(1L);

        assertThat(result.status()).isEqualTo(PaymentDiscrepancyAuditResult.Status.CONSISTENT);
        assertThat(result.skipReason()).isNull();
        assertThat(result.providerStatus()).isEqualTo(PaymentFlowStatus.REQUIRES_CAPTURE);
        assertThat(result.discrepancyIds()).containsExactly(10L, 11L);
        assertThat(first.getStatus()).isEqualTo(PaymentDiscrepancyRecordStatus.RESOLVED);
        assertThat(second.getStatus()).isEqualTo(PaymentDiscrepancyRecordStatus.RESOLVED);
        assertThat(first.getHandlingStatus()).isEqualTo(PaymentDiscrepancyHandlingStatus.IN_PROGRESS);
        assertThat(second.getHandlingStatus()).isEqualTo(PaymentDiscrepancyHandlingStatus.CONFIRMED);
        assertThat(first.getHandlingStatusUpdatedAt()).isEqualTo(handlingChangedAt);
        assertThat(second.getHandlingStatusUpdatedAt()).isEqualTo(handlingChangedAt);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
    }

    @Test
    void returnsConsistentWithNoRelatedRecords() {
        realPayment(PaymentStatus.AUTHORIZED);
        when(paymentGateway.retrievePaymentFlow("pfw_test"))
                .thenReturn(paymentFlowState(PaymentFlowStatus.REQUIRES_CAPTURE));

        PaymentDiscrepancyAuditResult result = service.auditWithResult(1L);

        assertThat(result.status()).isEqualTo(PaymentDiscrepancyAuditResult.Status.CONSISTENT);
        assertThat(result.discrepancyIds()).isEmpty();
    }

    @Test
    void returnsInconsistentWithNewRecordId() {
        Payment payment = realPayment(PaymentStatus.AUTHORIZED);
        when(paymentGateway.retrievePaymentFlow("pfw_test"))
                .thenReturn(paymentFlowState(PaymentFlowStatus.SUCCEEDED));
        when(discrepancyRepository.save(any(PaymentDiscrepancy.class))).thenAnswer(invocation -> {
            PaymentDiscrepancy saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 20L);
            return saved;
        });

        PaymentDiscrepancyAuditResult result = service.auditWithResult(1L);

        assertThat(result.status()).isEqualTo(PaymentDiscrepancyAuditResult.Status.INCONSISTENT);
        assertThat(result.providerStatus()).isEqualTo(PaymentFlowStatus.SUCCEEDED);
        assertThat(result.discrepancyIds()).containsExactly(20L);
        ArgumentCaptor<PaymentDiscrepancy> captor = ArgumentCaptor.forClass(PaymentDiscrepancy.class);
        verify(discrepancyRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(PaymentDiscrepancyRecordStatus.OPEN);
        assertThat(captor.getValue().getLocalStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(captor.getValue().getHandlingStatus()).isEqualTo(PaymentDiscrepancyHandlingStatus.UNCONFIRMED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
    }

    @Test
    void returnsExistingMismatchIdAndPreservesHandlingStatus() {
        Payment payment = realPayment(PaymentStatus.AUTHORIZED);
        PaymentDiscrepancy existing = discrepancy(payment, 21L);
        LocalDateTime changedAt = LocalDateTime.of(2026, 10, 5, 10, 0);
        existing.changeHandlingStatus(PaymentDiscrepancyHandlingStatus.COMPLETED, changedAt);
        when(paymentGateway.retrievePaymentFlow("pfw_test"))
                .thenReturn(paymentFlowState(PaymentFlowStatus.SUCCEEDED));
        when(discrepancyRepository.findByPaymentIdAndLocalStatusAndProviderStatusAndStatus(
                1L, PaymentStatus.AUTHORIZED, PaymentFlowStatus.SUCCEEDED, PaymentDiscrepancyRecordStatus.OPEN))
                .thenReturn(Optional.of(existing));

        PaymentDiscrepancyAuditResult result = service.auditWithResult(1L);

        assertThat(result.status()).isEqualTo(PaymentDiscrepancyAuditResult.Status.INCONSISTENT);
        assertThat(result.discrepancyIds()).containsExactly(21L);
        assertThat(existing.getDetectionCount()).isEqualTo(2);
        assertThat(existing.getStatus()).isEqualTo(PaymentDiscrepancyRecordStatus.OPEN);
        assertThat(existing.getHandlingStatus()).isEqualTo(PaymentDiscrepancyHandlingStatus.COMPLETED);
        assertThat(existing.getHandlingStatusUpdatedAt()).isEqualTo(changedAt);
        verify(discrepancyRepository, never()).save(any());
    }

    @Test
    void returnsInProgressWithoutChangingRecords() {
        Payment payment = realPayment(PaymentStatus.PENDING);
        when(paymentGateway.retrievePaymentFlow("pfw_test"))
                .thenReturn(paymentFlowState(PaymentFlowStatus.PROCESSING));

        PaymentDiscrepancyAuditResult result = service.auditWithResult(1L);

        assertThat(result.status()).isEqualTo(PaymentDiscrepancyAuditResult.Status.IN_PROGRESS);
        assertThat(result.providerStatus()).isEqualTo(PaymentFlowStatus.PROCESSING);
        assertThat(result.discrepancyIds()).isEmpty();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verifyNoInteractions(discrepancyRepository);
    }

    @Test
    void returnsMissingPaymentSkipReason() {
        assertSkipped(service.auditWithResult(1L), PaymentDiscrepancyAuditResult.SkipReason.PAYMENT_NOT_FOUND);
    }

    @Test
    void returnsUnsupportedPaymentSkipReason() {
        Payment payment = realPayment(PaymentStatus.PENDING);
        ReflectionTestUtils.setField(payment, "provider", PaymentProvider.MOCK);
        assertSkipped(service.auditWithResult(1L), PaymentDiscrepancyAuditResult.SkipReason.UNSUPPORTED_PAYMENT);
    }

    @Test
    void returnsMissingProviderIdSkipReasonForNullAndBlankIds() {
        Payment payment = realPayment(PaymentStatus.PENDING);
        payment.setProviderPaymentId(null, LocalDateTime.of(2026, 10, 5, 10, 0));
        assertSkipped(service.auditWithResult(1L), PaymentDiscrepancyAuditResult.SkipReason.MISSING_PROVIDER_PAYMENT_ID);
        payment.setProviderPaymentId(" ", LocalDateTime.of(2026, 10, 5, 10, 0));
        assertSkipped(service.auditWithResult(1L), PaymentDiscrepancyAuditResult.SkipReason.MISSING_PROVIDER_PAYMENT_ID);
    }

    @Test
    void returnsPendingSkipWithoutCallingPayJpOrChangingTransaction() {
        Payment payment = realPayment(PaymentStatus.AUTHORIZED);
        PaymentTransaction transaction = new PaymentTransaction(
                payment, PaymentTransactionType.CAPTURE,
                1000, 1, "test-key", LocalDateTime.of(2026, 10, 5, 10, 0));
        when(transactionRepository.findByPaymentIdOrderByCreatedAtAscIdAsc(1L))
                .thenReturn(List.of(transaction));

        assertSkipped(service.auditWithResult(1L), PaymentDiscrepancyAuditResult.SkipReason.PENDING_TRANSACTION);

        assertThat(transaction.getStatus()).isEqualTo(PaymentTransactionStatus.PENDING);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
    }

    @Test
    void propagatesGatewayFailureWithoutChangingRecords() {
        Payment payment = realPayment(PaymentStatus.AUTHORIZED);
        PaymentGatewayException failure = new PaymentGatewayException("unavailable");
        when(paymentGateway.retrievePaymentFlow("pfw_test")).thenThrow(failure);

        assertThatThrownBy(() -> service.auditWithResult(1L)).isSameAs(failure);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        verifyNoInteractions(discrepancyRepository);
    }

    private void assertSkipped(PaymentDiscrepancyAuditResult result,
            PaymentDiscrepancyAuditResult.SkipReason reason) {
        assertThat(result.status()).isEqualTo(PaymentDiscrepancyAuditResult.Status.SKIPPED);
        assertThat(result.skipReason()).isEqualTo(reason);
        assertThat(result.providerStatus()).isNull();
        assertThat(result.discrepancyIds()).isEmpty();
        verifyNoInteractions(paymentGateway, discrepancyRepository);
    }

    private Payment realPayment(PaymentStatus status) {
        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 5, 10, 0);
        Payment payment = new Payment(mock(Order.class), PaymentProvider.PAYJP, PaymentMethod.CARD, 1000, createdAt);
        ReflectionTestUtils.setField(payment, "id", 1L);
        if (status == PaymentStatus.AUTHORIZED) {
            payment.markAuthorized(createdAt);
        }
        payment.setProviderPaymentId("pfw_test", createdAt);
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        return payment;
    }

    private PaymentDiscrepancy discrepancy(Payment payment, Long id) {
        PaymentDiscrepancy discrepancy = new PaymentDiscrepancy(payment, PaymentStatus.AUTHORIZED,
                PaymentFlowStatus.SUCCEEDED, LocalDateTime.of(2026, 10, 5, 10, 0));
        ReflectionTestUtils.setField(discrepancy, "id", id);
        return discrepancy;
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
