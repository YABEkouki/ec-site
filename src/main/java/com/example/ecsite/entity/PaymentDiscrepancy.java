package com.example.ecsite.entity;

import java.time.LocalDateTime;

import com.example.ecsite.payment.PaymentFlowStatus;

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
@Table(name = "payment_discrepancies")
public class PaymentDiscrepancy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Enumerated(EnumType.STRING)
    @Column(name = "local_status", nullable = false, length = 50)
    private PaymentStatus localStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_status", nullable = false, length = 50)
    private PaymentFlowStatus providerStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentDiscrepancyRecordStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "handling_status", nullable = false, length = 30)
    private PaymentDiscrepancyHandlingStatus handlingStatus;

    @Column(name = "handling_status_updated_at")
    private LocalDateTime handlingStatusUpdatedAt;

    @Column(name = "first_detected_at", nullable = false)
    private LocalDateTime firstDetectedAt;

    @Column(name = "last_detected_at", nullable = false)
    private LocalDateTime lastDetectedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "detection_count", nullable = false)
    private int detectionCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected PaymentDiscrepancy() {
    }

    public PaymentDiscrepancy(
            Payment payment,
            PaymentStatus localStatus,
            PaymentFlowStatus providerStatus,
            LocalDateTime detectedAt) {

        this.payment = payment;
        this.localStatus = localStatus;
        this.providerStatus = providerStatus;
        this.status = PaymentDiscrepancyRecordStatus.OPEN;
        this.handlingStatus = PaymentDiscrepancyHandlingStatus.UNCONFIRMED;
        this.firstDetectedAt = detectedAt;
        this.lastDetectedAt = detectedAt;
        this.detectionCount = 1;
        this.createdAt = detectedAt;
        this.updatedAt = detectedAt;
    }

    public void detectAgain(LocalDateTime detectedAt) {
        this.lastDetectedAt = detectedAt;
        this.detectionCount++;
        this.updatedAt = detectedAt;
    }

    public void resolve(LocalDateTime resolvedAt) {
        if (status == PaymentDiscrepancyRecordStatus.RESOLVED) {
            return;
        }

        this.status = PaymentDiscrepancyRecordStatus.RESOLVED;
        this.resolvedAt = resolvedAt;
        this.updatedAt = resolvedAt;
    }

    public void changeHandlingStatus(
            PaymentDiscrepancyHandlingStatus handlingStatus,
            LocalDateTime changedAt) {

        this.handlingStatus = handlingStatus;
        this.handlingStatusUpdatedAt = changedAt;
    }

    public Long getId() {
        return id;
    }

    public Payment getPayment() {
        return payment;
    }

    public PaymentStatus getLocalStatus() {
        return localStatus;
    }

    public PaymentFlowStatus getProviderStatus() {
        return providerStatus;
    }

    public PaymentDiscrepancyRecordStatus getStatus() {
        return status;
    }

    public PaymentDiscrepancyHandlingStatus getHandlingStatus() {
        return handlingStatus;
    }

    public LocalDateTime getHandlingStatusUpdatedAt() {
        return handlingStatusUpdatedAt;
    }

    public LocalDateTime getFirstDetectedAt() {
        return firstDetectedAt;
    }

    public LocalDateTime getLastDetectedAt() {
        return lastDetectedAt;
    }

    public LocalDateTime getResolvedAt() {
        return resolvedAt;
    }

    public int getDetectionCount() {
        return detectionCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
