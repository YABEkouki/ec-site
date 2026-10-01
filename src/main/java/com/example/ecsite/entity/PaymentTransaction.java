package com.example.ecsite.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "payment_transactions")
public class PaymentTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 30)
    private PaymentTransactionType transactionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentTransactionStatus status;

    @Column(nullable = false)
    private int amount;

    @Column(name = "order_content_revision", nullable = false)
    private int orderContentRevision;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "provider_transaction_id", length = 255)
    private String providerTransactionId;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "failure_message", length = 500)
    private String failureMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    protected PaymentTransaction() {
    }

    public PaymentTransaction(
            Payment payment,
            PaymentTransactionType transactionType,
            int amount,
            int orderContentRevision,
            String idempotencyKey,
            LocalDateTime createdAt) {

        if (payment == null) {
            throw new IllegalArgumentException("決済は必須です。");
        }
        if (transactionType == null) {
            throw new IllegalArgumentException("決済操作種別は必須です。");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("決済操作金額は1円以上である必要があります。");
        }
        if (orderContentRevision < 0) {
            throw new IllegalArgumentException("注文内容リビジョンは0以上である必要があります。");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("冪等性キーは必須です。");
        }
        if (idempotencyKey.length() > 100) {
            throw new IllegalArgumentException("冪等性キーは100文字以内である必要があります。");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("作成日時は必須です。");
        }

        this.payment = payment;
        this.transactionType = transactionType;
        this.status = PaymentTransactionStatus.PENDING;
        this.amount = amount;
        this.orderContentRevision = orderContentRevision;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Payment getPayment() {
        return payment;
    }

    public PaymentTransactionType getTransactionType() {
        return transactionType;
    }

    public PaymentTransactionStatus getStatus() {
        return status;
    }

    public int getAmount() {
        return amount;
    }

    public int getOrderContentRevision() {
        return orderContentRevision;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getProviderTransactionId() {
        return providerTransactionId;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void markSucceeded(
            String providerTransactionId,
            LocalDateTime completedAt) {

        requirePending();
        requireCompletedAt(completedAt);

        this.status = PaymentTransactionStatus.SUCCEEDED;
        this.providerTransactionId = providerTransactionId;
        this.failureCode = null;
        this.failureMessage = null;
        this.completedAt = completedAt;
    }

    public void markFailed(
            String providerTransactionId,
            String failureCode,
            String failureMessage,
            LocalDateTime completedAt) {

        requirePending();
        requireCompletedAt(completedAt);

        this.status = PaymentTransactionStatus.FAILED;
        this.providerTransactionId = providerTransactionId;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
        this.completedAt = completedAt;
    }

    private void requirePending() {
        if (status != PaymentTransactionStatus.PENDING) {
            throw new IllegalStateException("完了済みの決済操作は変更できません。");
        }
    }

    private void requireCompletedAt(LocalDateTime completedAt) {
        if (completedAt == null) {
            throw new IllegalArgumentException("完了日時は必須です。");
        }
    }
}
