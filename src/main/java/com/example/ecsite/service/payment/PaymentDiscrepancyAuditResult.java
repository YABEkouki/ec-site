package com.example.ecsite.service.payment;

import java.util.List;

import com.example.ecsite.payment.PaymentFlowStatus;

/**
 * 監査の判定結果。スキップ時のproviderStatusはnull、関連記録がない場合のID一覧は空。
 */
public record PaymentDiscrepancyAuditResult(
        Status status,
        SkipReason skipReason,
        PaymentFlowStatus providerStatus,
        List<Long> discrepancyIds) {

    public PaymentDiscrepancyAuditResult {
        discrepancyIds = List.copyOf(discrepancyIds);
    }

    public enum Status {
        CONSISTENT,
        INCONSISTENT,
        IN_PROGRESS,
        SKIPPED
    }

    public enum SkipReason {
        PAYMENT_NOT_FOUND,
        UNSUPPORTED_PAYMENT,
        MISSING_PROVIDER_PAYMENT_ID,
        PENDING_TRANSACTION
    }
}
