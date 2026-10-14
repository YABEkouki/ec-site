package com.example.ecsite.entity;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Independent request. Result reasons use existing failure/close enums or manual cancellation codes. */
@Entity
@Table(name = "payment_audit_notification_manual_resends")
public class PaymentAuditNotificationManualResend {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "notification_id", nullable = false)
    private Long notificationId;

    @Column(name = "request_no", nullable = false)
    private int requestNo;

    @Column(name = "request_nonce", nullable = false)
    private UUID requestNonce;

    @Column(name = "actor_user_id", nullable = false)
    private Long actorUserId;

    @Column(name = "actor_username", nullable = false, length = 100)
    private String actorUsername;

    @Column(name = "reason", nullable = false, length = 1000)
    private String reason;

    @Column(name = "risk_acknowledged", nullable = false)
    private boolean riskAcknowledged;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private PaymentAuditNotificationManualResendStatus status;

    @Column(name = "result_reason_code", nullable = true, length = 40)
    private String resultReasonCode;

    @Column(name = "claim_token", nullable = true)
    private UUID claimToken;

    @Column(name = "claimed_by", nullable = true)
    private UUID claimedBy;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "lease_until", nullable = true)
    private Instant leaseUntil;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "started_at", nullable = true)
    private Instant startedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "finished_at", nullable = true)
    private Instant finishedAt;

    public PaymentAuditNotificationManualResend() {}

    public Long getId() { return id; }
    public Long getNotificationId() { return notificationId; }
    public void setNotificationId(Long value) { notificationId = value; }
    public int getRequestNo() { return requestNo; }
    public void setRequestNo(int value) { requestNo = value; }
    public UUID getRequestNonce() { return requestNonce; }
    public void setRequestNonce(UUID value) { requestNonce = value; }
    public Long getActorUserId() { return actorUserId; }
    public void setActorUserId(Long value) { actorUserId = value; }
    public String getActorUsername() { return actorUsername; }
    public void setActorUsername(String value) { actorUsername = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
    public boolean isRiskAcknowledged() { return riskAcknowledged; }
    public void setRiskAcknowledged(boolean value) { riskAcknowledged = value; }
    public PaymentAuditNotificationManualResendStatus getStatus() { return status; }
    public void setStatus(PaymentAuditNotificationManualResendStatus value) { status = value; }
    public String getResultReasonCode() { return resultReasonCode; }
    public void setResultReasonCode(String value) { resultReasonCode = value; }
    public UUID getClaimToken() { return claimToken; }
    public void setClaimToken(UUID value) { claimToken = value; }
    public UUID getClaimedBy() { return claimedBy; }
    public void setClaimedBy(UUID value) { claimedBy = value; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Instant value) { leaseUntil = value; }
    public long getVersion() { return version; }
    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant value) { requestedAt = value; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant value) { startedAt = value; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant value) { finishedAt = value; }
}
