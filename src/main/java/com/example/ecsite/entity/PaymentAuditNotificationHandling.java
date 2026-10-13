package com.example.ecsite.entity;

import java.time.Instant;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "payment_audit_notification_handlings")
public class PaymentAuditNotificationHandling {
    @Id
    @Column(name = "notification_id", nullable = false)
    private Long notificationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "handling_status", nullable = false, length = 24)
    private PaymentAuditNotificationHandlingStatus handlingStatus;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_resolved_history_id")
    private Long lastResolvedHistoryId;

    public PaymentAuditNotificationHandling() {}

    public Long getNotificationId() { return notificationId; }
    public void setNotificationId(Long notificationId) { this.notificationId = notificationId; }
    public PaymentAuditNotificationHandlingStatus getHandlingStatus() { return handlingStatus; }
    public void setHandlingStatus(PaymentAuditNotificationHandlingStatus handlingStatus) { this.handlingStatus = handlingStatus; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Long getLastResolvedHistoryId() { return lastResolvedHistoryId; }
    public void setLastResolvedHistoryId(Long lastResolvedHistoryId) { this.lastResolvedHistoryId = lastResolvedHistoryId; }
}
