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
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentProvider provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 30)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentStatus status;

    @Column(nullable = false)
    private int amount;

    @Column(name = "provider_payment_id", length = 255)
    private String providerPaymentId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Payment() {
    }

    public Payment(
            Order order,
            PaymentProvider provider,
            PaymentMethod paymentMethod,
            int amount,
            LocalDateTime createdAt) {

        if (order == null) {
            throw new IllegalArgumentException("注文は必須です。");
        }
        if (provider == null) {
            throw new IllegalArgumentException("決済プロバイダーは必須です。");
        }
        if (paymentMethod == null) {
            throw new IllegalArgumentException("決済方法は必須です。");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("決済金額は1円以上である必要があります。");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("作成日時は必須です。");
        }

        this.order = order;
        this.provider = provider;
        this.paymentMethod = paymentMethod;
        this.status = PaymentStatus.PENDING;
        this.amount = amount;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Order getOrder() {
        return order;
    }

    public PaymentProvider getProvider() {
        return provider;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public int getAmount() {
        return amount;
    }

    public String getProviderPaymentId() {
        return providerPaymentId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setProviderPaymentId(String providerPaymentId, LocalDateTime updatedAt) {
        requireUpdatedAt(updatedAt);
        this.providerPaymentId = providerPaymentId;
        this.updatedAt = updatedAt;
    }

    public void markRequiresAction(LocalDateTime updatedAt) {
        requireStatus(PaymentStatus.PENDING, "追加認証待ち");
        changeStatus(PaymentStatus.REQUIRES_ACTION, updatedAt);
    }

    public void markAuthorized(LocalDateTime updatedAt) {
        if (status != PaymentStatus.PENDING
                && status != PaymentStatus.REQUIRES_ACTION) {
            throw new IllegalStateException("現在の決済状態では与信済みに変更できません。");
        }
        changeStatus(PaymentStatus.AUTHORIZED, updatedAt);
    }

    public void markCaptured(LocalDateTime updatedAt) {
        requireStatus(PaymentStatus.AUTHORIZED, "売上確定");
        changeStatus(PaymentStatus.CAPTURED, updatedAt);
    }

    public void markCancelled(LocalDateTime updatedAt) {
        requireStatus(PaymentStatus.AUTHORIZED, "取消済み");
        changeStatus(PaymentStatus.CANCELLED, updatedAt);
    }

    public void markFailed(LocalDateTime updatedAt) {
        if (status != PaymentStatus.PENDING
                && status != PaymentStatus.REQUIRES_ACTION) {
            throw new IllegalStateException("現在の決済状態では失敗状態に変更できません。");
        }
        changeStatus(PaymentStatus.FAILED, updatedAt);
    }

    private void requireStatus(PaymentStatus expected, String operation) {
        if (status != expected) {
            throw new IllegalStateException(
                    "現在の決済状態では" + operation + "に変更できません。");
        }
    }

    private void changeStatus(PaymentStatus newStatus, LocalDateTime updatedAt) {
        requireUpdatedAt(updatedAt);
        this.status = newStatus;
        this.updatedAt = updatedAt;
    }

    private void requireUpdatedAt(LocalDateTime updatedAt) {
        if (updatedAt == null) {
            throw new IllegalArgumentException("更新日時は必須です。");
        }
    }
}
