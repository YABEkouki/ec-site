package com.example.ecsite.entity;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Persistence foundation; notification execution is implemented in a later stage. */
@Entity
@Table(name = "payment_audit_notification_attempts")
public class PaymentAuditNotificationAttempt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notification_id", nullable = false)
    private PaymentAuditNotification notification;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Column(name = "claim_token", nullable = false)
    private UUID claimToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 24)
    private PaymentAuditNotificationAttemptResult result;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "finished_at", nullable = true)
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code", nullable = true, length = 40)
    private PaymentAuditNotificationFailureCode failureCode;

    @Column(name = "subject", nullable = false, columnDefinition = "text")
    private String subject;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "recipients", nullable = false, columnDefinition = "text[]")
    private String[] recipients;

    public String[] getRecipients() {
        return recipients == null ? null : recipients.clone();
    }

    public void setRecipients(String[] recipients) {
        this.recipients = recipients == null ? null : recipients.clone();
    }

    public PaymentAuditNotificationAttempt() {
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

    public int getAttemptNo() {
        return attemptNo;
    }

    public void setAttemptNo(int attemptNo) {
        this.attemptNo = attemptNo;
    }

    public UUID getClaimToken() {
        return claimToken;
    }

    public void setClaimToken(UUID claimToken) {
        this.claimToken = claimToken;
    }

    public PaymentAuditNotificationAttemptResult getResult() {
        return result;
    }

    public void setResult(PaymentAuditNotificationAttemptResult result) {
        this.result = result;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public PaymentAuditNotificationFailureCode getFailureCode() {
        return failureCode;
    }

    public void setFailureCode(PaymentAuditNotificationFailureCode failureCode) {
        this.failureCode = failureCode;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

}
