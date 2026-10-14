package com.example.ecsite.entity;

import jakarta.persistence.*;

/** RESOLVED confirmation of a same-notification UNKNOWN manual attempt; generated discriminators are database-only. */
@Entity
@Table(name = "payment_audit_notification_handling_history_manual_unknowns")
@IdClass(PaymentAuditNotificationHandlingHistoryManualUnknownId.class)
public class PaymentAuditNotificationHandlingHistoryManualUnknown {
    @Id
    @Column(name = "history_id", nullable = false)
    private Long historyId;

    @Id
    @Column(name = "manual_attempt_id", nullable = false)
    private Long manualAttemptId;

    @Column(name = "notification_id", nullable = false)
    private Long notificationId;

    public PaymentAuditNotificationHandlingHistoryManualUnknown() {}

    public Long getHistoryId() { return historyId; }
    public void setHistoryId(Long value) { historyId = value; }
    public Long getManualAttemptId() { return manualAttemptId; }
    public void setManualAttemptId(Long value) { manualAttemptId = value; }
    public Long getNotificationId() { return notificationId; }
    public void setNotificationId(Long value) { notificationId = value; }
}
