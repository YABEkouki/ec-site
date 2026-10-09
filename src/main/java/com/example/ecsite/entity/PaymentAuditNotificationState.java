package com.example.ecsite.entity;

import java.time.Instant;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Persistence foundation; notification execution is implemented in a later stage. */
@Entity
@Table(name = "payment_audit_notification_states")
public class PaymentAuditNotificationState {
    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "warning_type", nullable = false, length = 32)
    private PaymentAuditNotificationWarningType warningType;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "episode_no", nullable = false)
    private long episodeNo;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "first_observed_at", nullable = true)
    private Instant firstObservedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "last_evaluated_at", nullable = true)
    private Instant lastEvaluatedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "last_observed_at", nullable = true)
    private Instant lastObservedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "resolved_at", nullable = true)
    private Instant resolvedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public PaymentAuditNotificationState() {
    }

    public PaymentAuditNotificationWarningType getWarningType() {
        return warningType;
    }

    public void setWarningType(PaymentAuditNotificationWarningType warningType) {
        this.warningType = warningType;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public long getEpisodeNo() {
        return episodeNo;
    }

    public void setEpisodeNo(long episodeNo) {
        this.episodeNo = episodeNo;
    }

    public Instant getFirstObservedAt() {
        return firstObservedAt;
    }

    public void setFirstObservedAt(Instant firstObservedAt) {
        this.firstObservedAt = firstObservedAt;
    }

    public Instant getLastEvaluatedAt() {
        return lastEvaluatedAt;
    }

    public void setLastEvaluatedAt(Instant lastEvaluatedAt) {
        this.lastEvaluatedAt = lastEvaluatedAt;
    }

    public Instant getLastObservedAt() {
        return lastObservedAt;
    }

    public void setLastObservedAt(Instant lastObservedAt) {
        this.lastObservedAt = lastObservedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public long getVersion() {
        return version;
    }

}
