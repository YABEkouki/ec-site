package com.example.ecsite.entity;

import java.time.Instant;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "payment_audit_notification_handling_history_unknowns")
@IdClass(PaymentAuditNotificationHandlingHistoryUnknownId.class)
public class PaymentAuditNotificationHandlingHistoryUnknown {
    @Id
    @Column(name = "history_id", nullable = false)
    private Long historyId;

    @Column(name = "notification_id", nullable = false)
    private Long notificationId;

    @Id
    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    public PaymentAuditNotificationHandlingHistoryUnknown() {}

    public Long getHistoryId() { return historyId; }
    public void setHistoryId(Long historyId) { this.historyId = historyId; }
    public Long getNotificationId() { return notificationId; }
    public void setNotificationId(Long notificationId) { this.notificationId = notificationId; }
    public int getAttemptNo() { return attemptNo; }
    public void setAttemptNo(int attemptNo) { this.attemptNo = attemptNo; }
}
