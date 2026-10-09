package com.example.ecsite.entity;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Persistence foundation; notification execution is implemented in a later stage. */
@Entity
@Table(name = "payment_audit_notifications")
public class PaymentAuditNotification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private PaymentAuditNotificationStatus status;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;

    @Column(name = "from_address", nullable = false, length = 254)
    private String fromAddress;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "recipients", nullable = false, columnDefinition = "text[]")
    private String[] recipients;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "recipient_set_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    private String recipientSetHash;

    @Column(name = "admin_url", nullable = false, columnDefinition = "text")
    private String adminUrl;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "retry_delay_ms", nullable = false)
    private long retryDelayMs;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "next_attempt_at", nullable = true)
    private Instant nextAttemptAt;

    @Column(name = "claim_token", nullable = true)
    private UUID claimToken;

    @Column(name = "claimed_by", nullable = true)
    private UUID claimedBy;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "lease_until", nullable = true)
    private Instant leaseUntil;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "sent_at", nullable = true)
    private Instant sentAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "closed_at", nullable = true)
    private Instant closedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "close_reason", nullable = true, length = 40)
    private PaymentAuditNotificationCloseReason closeReason;

    @Column(name = "delivery_uncertain", nullable = false)
    private boolean deliveryUncertain;

    public PaymentAuditNotification() {
    }

    public Long getId() {
        return id;
    }

    public PaymentAuditNotificationStatus getStatus() {
        return status;
    }

    public void setStatus(PaymentAuditNotificationStatus status) {
        this.status = status;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }

    public void setEvaluatedAt(Instant evaluatedAt) {
        this.evaluatedAt = evaluatedAt;
    }

    public String getFromAddress() {
        return fromAddress;
    }

    public void setFromAddress(String fromAddress) {
        this.fromAddress = fromAddress;
    }

    public String[] getRecipients() {
        return recipients == null ? null : recipients.clone();
    }

    public void setRecipients(String[] recipients) {
        this.recipients = recipients == null ? null : recipients.clone();
    }

    public String getRecipientSetHash() {
        return recipientSetHash;
    }

    public void setRecipientSetHash(String recipientSetHash) {
        this.recipientSetHash = recipientSetHash;
    }

    public String getAdminUrl() {
        return adminUrl;
    }

    public void setAdminUrl(String adminUrl) {
        this.adminUrl = adminUrl;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getRetryDelayMs() {
        return retryDelayMs;
    }

    public void setRetryDelayMs(long retryDelayMs) {
        this.retryDelayMs = retryDelayMs;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public UUID getClaimToken() {
        return claimToken;
    }

    public void setClaimToken(UUID claimToken) {
        this.claimToken = claimToken;
    }

    public UUID getClaimedBy() {
        return claimedBy;
    }

    public void setClaimedBy(UUID claimedBy) {
        this.claimedBy = claimedBy;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(Instant leaseUntil) {
        this.leaseUntil = leaseUntil;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
    }

    public PaymentAuditNotificationCloseReason getCloseReason() {
        return closeReason;
    }

    public void setCloseReason(PaymentAuditNotificationCloseReason closeReason) {
        this.closeReason = closeReason;
    }

    public boolean isDeliveryUncertain() {
        return deliveryUncertain;
    }

    public void setDeliveryUncertain(boolean deliveryUncertain) {
        this.deliveryUncertain = deliveryUncertain;
    }

}
