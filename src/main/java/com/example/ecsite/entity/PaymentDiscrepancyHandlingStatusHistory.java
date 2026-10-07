package com.example.ecsite.entity;

import java.time.LocalDateTime;
import java.util.UUID;

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
@Table(name = "payment_discrepancy_handling_status_histories")
public class PaymentDiscrepancyHandlingStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_discrepancy_id", nullable = false)
    private PaymentDiscrepancy paymentDiscrepancy;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false, length = 30)
    private PaymentDiscrepancyHandlingStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30)
    private PaymentDiscrepancyHandlingStatus toStatus;

    @Column(name = "changed_by_account_id", nullable = false)
    private Long changedByAccountId;

    @Column(name = "changed_by_username", nullable = false, length = 100)
    private String changedByUsername;

    @Column(name = "change_event_id", nullable = false)
    private UUID changeEventId;

    @Column(name = "changed_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime changedAt;

    protected PaymentDiscrepancyHandlingStatusHistory() {
    }

    public static PaymentDiscrepancyHandlingStatusHistory create(
            PaymentDiscrepancy paymentDiscrepancy,
            PaymentDiscrepancyHandlingStatus fromStatus,
            PaymentDiscrepancyHandlingStatus toStatus,
            Long changedByAccountId,
            String changedByUsername,
            UUID changeEventId) {

        PaymentDiscrepancyHandlingStatusHistory history =
                new PaymentDiscrepancyHandlingStatusHistory();

        history.paymentDiscrepancy = paymentDiscrepancy;
        history.fromStatus = fromStatus;
        history.toStatus = toStatus;
        history.changedByAccountId = changedByAccountId;
        history.changedByUsername = changedByUsername;
        history.changeEventId = changeEventId;

        return history;
    }

    public Long getId() {
        return id;
    }

    public PaymentDiscrepancy getPaymentDiscrepancy() {
        return paymentDiscrepancy;
    }

    public PaymentDiscrepancyHandlingStatus getFromStatus() {
        return fromStatus;
    }

    public PaymentDiscrepancyHandlingStatus getToStatus() {
        return toStatus;
    }

    public Long getChangedByAccountId() {
        return changedByAccountId;
    }

    public String getChangedByUsername() {
        return changedByUsername;
    }

    public UUID getChangeEventId() {
        return changeEventId;
    }

    public LocalDateTime getChangedAt() {
        return changedAt;
    }
}
