package com.example.ecsite.service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.sql.SQLException;
import java.util.List;
import java.util.stream.Stream;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.TransactionSystemException;

import com.example.ecsite.entity.PaymentDiscrepancy;
import com.example.ecsite.payment.PaymentFlowStatus;
import com.example.ecsite.payment.PaymentGatewayException;

class PaymentDiscrepancyAuditRetryFacadeTest {
    private final PaymentDiscrepancyAuditItemService item = mock(PaymentDiscrepancyAuditItemService.class);
    private final PaymentDiscrepancyAuditRetryFacade facade = new PaymentDiscrepancyAuditRetryFacade(item);
    private final PaymentDiscrepancyAuditResult success = new PaymentDiscrepancyAuditResult(
            PaymentDiscrepancyAuditResult.Status.INCONSISTENT, null, PaymentFlowStatus.SUCCEEDED, List.of(1L));

    @ParameterizedTest
    @MethodSource("retryableFailures")
    void recoversAndReturnsOnlySuccessfulAttempt(RuntimeException conflict) {
        when(item.auditWithResult(1L)).thenThrow(conflict).thenReturn(success);
        assertThat(facade.auditWithResult(1L)).isSameAs(success);
        verify(item, times(2)).auditWithResult(1L);
    }

    @Test
    void stopsAtThreeAttemptsAndPropagatesLastFailure() {
        var first = optimistic();
        var second = optimistic();
        var last = optimistic();
        when(item.auditWithResult(1L)).thenThrow(first, second, last);
        assertThatThrownBy(() -> facade.audit(1L)).isSameAs(last);
        verify(item, times(3)).auditWithResult(1L);
    }

    @ParameterizedTest
    @MethodSource("nonRetryableFailures")
    void propagatesOtherFailuresWithoutRetry(RuntimeException failure) {
        when(item.auditWithResult(1L)).thenThrow(failure);
        assertThatThrownBy(() -> facade.auditWithResult(1L)).isSameAs(failure);
        verify(item, times(1)).auditWithResult(1L);
    }

    static Stream<RuntimeException> retryableFailures() {
        return Stream.of(optimistic(), new TransactionSystemException("commit failed", optimistic()),
                constraint("23505", "uq_payment_discrepancies_open"));
    }
    static Stream<RuntimeException> nonRetryableFailures() {
        return Stream.of(constraint("23503", "fk_payment_discrepancies_payment"),
                constraint("23514", "chk_payment_discrepancies_detection_count"),
                constraint("23505", "uq_payments_provider_payment_id"),
                constraint("23514", "uq_payment_discrepancies_open"),
                constraint("23505", null), new PaymentGatewayException("unavailable"),
                new TransactionSystemException("commit outcome unknown", new SQLException("connection lost", "08006")),
                new TransactionSystemException("unknown"),
                new IllegalStateException("uq_payment_discrepancies_open 23505"));
    }
    private static ObjectOptimisticLockingFailureException optimistic() {
        return new ObjectOptimisticLockingFailureException(PaymentDiscrepancy.class, 1L);
    }
    private static RuntimeException constraint(String state, String name) {
        return new DataIntegrityViolationException("structured DB failure", new ConstraintViolationException(
                "DB failure", new SQLException("DB failure", state), "SQL", name));
    }
}
