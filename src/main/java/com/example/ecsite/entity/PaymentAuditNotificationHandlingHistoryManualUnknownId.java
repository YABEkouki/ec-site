package com.example.ecsite.entity;

import java.io.Serializable;
import java.util.Objects;

public class PaymentAuditNotificationHandlingHistoryManualUnknownId implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long historyId;
    private Long manualAttemptId;

    public PaymentAuditNotificationHandlingHistoryManualUnknownId() {}
    public PaymentAuditNotificationHandlingHistoryManualUnknownId(Long historyId, Long manualAttemptId) {
        this.historyId = historyId;
        this.manualAttemptId = manualAttemptId;
    }
    public Long getHistoryId() { return historyId; }
    public Long getManualAttemptId() { return manualAttemptId; }
    @Override public boolean equals(Object other) {
        return other instanceof PaymentAuditNotificationHandlingHistoryManualUnknownId id
            && Objects.equals(historyId, id.historyId) && Objects.equals(manualAttemptId, id.manualAttemptId);
    }
    @Override public int hashCode() { return Objects.hash(historyId, manualAttemptId); }
}
