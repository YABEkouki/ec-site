package com.example.ecsite.entity;

import java.io.Serializable;
import java.util.Objects;

public class PaymentAuditNotificationHandlingHistoryUnknownId implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long historyId;
    private int attemptNo;

    public PaymentAuditNotificationHandlingHistoryUnknownId() {}
    public PaymentAuditNotificationHandlingHistoryUnknownId(Long historyId, int attemptNo) {
        this.historyId = historyId;
        this.attemptNo = attemptNo;
    }
    public Long getHistoryId() { return historyId; }
    public int getAttemptNo() { return attemptNo; }
    @Override public boolean equals(Object other) {
        return other instanceof PaymentAuditNotificationHandlingHistoryUnknownId id
            && Objects.equals(historyId, id.historyId) && attemptNo == id.attemptNo;
    }
    @Override public int hashCode() { return Objects.hash(historyId, attemptNo); }
}
