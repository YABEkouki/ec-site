package com.example.ecsite.entity;

import java.time.Instant;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Persistence foundation; notification execution is implemented in a later stage. */
@Entity
@Table(name = "payment_audit_notification_items")
public class PaymentAuditNotificationItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notification_id", nullable = false)
    private PaymentAuditNotification notification;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warning_type", nullable = false)
    private PaymentAuditNotificationState state;

    @Column(name = "episode_no", nullable = false)
    private long episodeNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_status", nullable = false, length = 24)
    private PaymentAuditNotificationItemStatus itemStatus;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "first_observed_at", nullable = false)
    private Instant firstObservedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "related_at", nullable = true)
    private Instant relatedAt;

    @Column(name = "warning_count", nullable = true)
    private Long warningCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_code", nullable = true, length = 50)
    private PaymentDiscrepancyAuditRunErrorCode errorCode;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "resolved_at", nullable = true)
    private Instant resolvedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "removed_at", nullable = true)
    private Instant removedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public PaymentAuditNotificationItem() {
    }

    public Long getId() {
        return id;
    }

    public PaymentAuditNotification getNotification() {
        return notification;
    }

    public void setNotification(PaymentAuditNotification notification) {
        this.notification = notification;
    }

    public PaymentAuditNotificationState getState() {
        return state;
    }

    public void setState(PaymentAuditNotificationState state) {
        this.state = state;
    }

    public long getEpisodeNo() {
        return episodeNo;
    }

    public void setEpisodeNo(long episodeNo) {
        this.episodeNo = episodeNo;
    }

    public PaymentAuditNotificationItemStatus getItemStatus() {
        return itemStatus;
    }

    public void setItemStatus(PaymentAuditNotificationItemStatus itemStatus) {
        this.itemStatus = itemStatus;
    }

    public Instant getFirstObservedAt() {
        return firstObservedAt;
    }

    public void setFirstObservedAt(Instant firstObservedAt) {
        this.firstObservedAt = firstObservedAt;
    }

    public Instant getRelatedAt() {
        return relatedAt;
    }

    public void setRelatedAt(Instant relatedAt) {
        this.relatedAt = relatedAt;
    }

    public Long getWarningCount() {
        return warningCount;
    }

    public void setWarningCount(Long warningCount) {
        this.warningCount = warningCount;
    }

    public PaymentDiscrepancyAuditRunErrorCode getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(PaymentDiscrepancyAuditRunErrorCode errorCode) {
        this.errorCode = errorCode;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public Instant getRemovedAt() {
        return removedAt;
    }

    public void setRemovedAt(Instant removedAt) {
        this.removedAt = removedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

}
