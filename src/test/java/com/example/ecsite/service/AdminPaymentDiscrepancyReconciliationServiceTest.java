package com.example.ecsite.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.Payment;
import com.example.ecsite.entity.PaymentDiscrepancy;
import com.example.ecsite.entity.PaymentDiscrepancyHandlingStatus;
import com.example.ecsite.entity.PaymentMethod;
import com.example.ecsite.entity.PaymentProvider;
import com.example.ecsite.entity.PaymentStatus;
import com.example.ecsite.payment.PaymentFlowStatus;
import com.example.ecsite.payment.PaymentGatewayException;
import com.example.ecsite.repository.PaymentDiscrepancyRepository;
import com.example.ecsite.service.payment.PaymentDiscrepancyAuditItemService;
import com.example.ecsite.service.payment.PaymentDiscrepancyAuditResult;

class AdminPaymentDiscrepancyReconciliationServiceTest {

    private final PaymentDiscrepancyRepository repository = mock(PaymentDiscrepancyRepository.class);
    private final PaymentDiscrepancyAuditItemService auditService = mock(PaymentDiscrepancyAuditItemService.class);
    private final AdminPaymentDiscrepancyReconciliationService service =
            new AdminPaymentDiscrepancyReconciliationService(repository, auditService);

    @ParameterizedTest
    @EnumSource(PaymentDiscrepancyAuditResult.Status.class)
    void auditsAssociatedPaymentAndReturnsResult(PaymentDiscrepancyAuditResult.Status status) {
        PaymentDiscrepancy discrepancy = existingDiscrepancy();
        boolean skipped = status == PaymentDiscrepancyAuditResult.Status.SKIPPED;
        PaymentDiscrepancyAuditResult expected = new PaymentDiscrepancyAuditResult(
                status,
                skipped ? PaymentDiscrepancyAuditResult.SkipReason.PENDING_TRANSACTION : null,
                switch (status) {
                    case CONSISTENT -> PaymentFlowStatus.REQUIRES_CAPTURE;
                    case INCONSISTENT -> PaymentFlowStatus.SUCCEEDED;
                    case IN_PROGRESS -> PaymentFlowStatus.PROCESSING;
                    case SKIPPED -> null;
                },
                switch (status) {
                    case CONSISTENT, INCONSISTENT -> List.of(10L);
                    case IN_PROGRESS, SKIPPED -> List.of();
                });
        when(auditService.auditWithResult(42L)).thenReturn(expected);

        PaymentDiscrepancyAuditResult result = service.reconcile(10L);

        assertThat(result).isSameAs(expected);
        verify(auditService).auditWithResult(42L);
        assertThat(discrepancy.getHandlingStatus()).isEqualTo(PaymentDiscrepancyHandlingStatus.IN_PROGRESS);
        assertThat(discrepancy.getHandlingStatusUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 5, 10, 0));
    }

    @Test
    void rejectsMissingDiscrepancyWithoutAuditing() {
        assertThatThrownBy(() -> service.reconcile(999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999");
        verifyNoInteractions(auditService);
    }

    @Test
    void propagatesAuditFailure() {
        existingDiscrepancy();
        PaymentGatewayException failure = new PaymentGatewayException("unavailable");
        when(auditService.auditWithResult(42L)).thenThrow(failure);

        assertThatThrownBy(() -> service.reconcile(10L)).isSameAs(failure);
    }

    private PaymentDiscrepancy existingDiscrepancy() {
        LocalDateTime time = LocalDateTime.of(2026, 10, 5, 10, 0);
        Payment payment = new Payment(mock(Order.class), PaymentProvider.PAYJP, PaymentMethod.CARD, 1000, time);
        ReflectionTestUtils.setField(payment, "id", 42L);
        PaymentDiscrepancy discrepancy = new PaymentDiscrepancy(
                payment, PaymentStatus.AUTHORIZED, PaymentFlowStatus.SUCCEEDED, time);
        discrepancy.changeHandlingStatus(PaymentDiscrepancyHandlingStatus.IN_PROGRESS, time);
        when(repository.findByIdWithPaymentAndOrder(10L)).thenReturn(Optional.of(discrepancy));
        return discrepancy;
    }
}
