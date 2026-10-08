package com.example.ecsite.dto;

import java.time.LocalDateTime;
import com.example.ecsite.entity.PaymentDiscrepancyAuditRunErrorCode;

public record AdminPaymentDiscrepancyAuditWarning(
        Type type, String description, LocalDateTime relatedAt, Long count,
        PaymentDiscrepancyAuditRunErrorCode errorCode) {
    public enum Type {
        CONSECUTIVE_FAILURES("定期監査の連続失敗"),
        LATEST_FAILURE("直近の定期監査失敗"),
        LONG_RUNNING("長時間RUNNING"),
        DELAYED("定期監査の遅延目安超過"),
        NO_HISTORY("定期監査の履歴なし"),
        LONG_UNHANDLED("長期未対応の決済不整合");

        private final String title;
        Type(String title) { this.title = title; }
        public String getTitle() { return title; }
    }
}
