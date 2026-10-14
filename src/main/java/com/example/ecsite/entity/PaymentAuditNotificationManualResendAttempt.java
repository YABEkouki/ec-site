package com.example.ecsite.entity;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One SMTP attempt per request; message snapshots are never included in toString(). */
@Entity
@Table(name = "payment_audit_notification_manual_resend_attempts")
public class PaymentAuditNotificationManualResendAttempt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "manual_resend_id", nullable = false)
    private Long manualResendId;

    @Column(name = "notification_id", nullable = false)
    private Long notificationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 24)
    private PaymentAuditNotificationAttemptResult result;

    @Column(name = "claim_token", nullable = false)
    private UUID claimToken;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "finished_at", nullable = true)
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_reason_code", nullable = true, length = 40)
    private PaymentAuditNotificationFailureCode resultReasonCode;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "recipients", nullable = false, columnDefinition = "text[]")
    private String[] recipients;

    @Column(name = "subject", nullable = false, columnDefinition = "text")
    private String subject;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    public PaymentAuditNotificationManualResendAttempt() {}

    public Long getId() { return id; }
    public Long getManualResendId() { return manualResendId; }
    public void setManualResendId(Long value) { manualResendId = value; }
    public Long getNotificationId() { return notificationId; }
    public void setNotificationId(Long value) { notificationId = value; }
    public PaymentAuditNotificationAttemptResult getResult() { return result; }
    public void setResult(PaymentAuditNotificationAttemptResult value) { result = value; }
    public UUID getClaimToken() { return claimToken; }
    public void setClaimToken(UUID value) { claimToken = value; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant value) { startedAt = value; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant value) { finishedAt = value; }
    public PaymentAuditNotificationFailureCode getResultReasonCode() { return resultReasonCode; }
    public void setResultReasonCode(PaymentAuditNotificationFailureCode value) { resultReasonCode = value; }
    public String[] getRecipients() { return recipients == null ? null : recipients.clone(); }
    public void setRecipients(String[] value) { recipients = value == null ? null : value.clone(); }
    public String getSubject() { return subject; }
    public void setSubject(String value) { subject = value; }
    public String getBody() { return body; }
    public void setBody(String value) { body = value; }
}
