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
@Table(name = "order_status_histories")
public class OrderStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30)
    private OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30)
    private OrderStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "changed_by_type", nullable = false, length = 20)
    private OrderStatusHistoryActorType changedByType;

    @Column(name = "changed_by_account_id")
    private Long changedByAccountId;

    @Column(name = "changed_by_username", nullable = false, length = 100)
    private String changedByUsername;

    @Column(name = "internal_note", length = 500)
    private String internalNote;

    @Column(name = "changed_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime changedAt;

    protected OrderStatusHistory() {
    }

    public static OrderStatusHistory create(
            Order order,
            OrderStatus fromStatus,
            OrderStatus toStatus,
            OrderStatusHistoryActorType changedByType,
            Long changedByAccountId,
            String changedByUsername) {

        OrderStatusHistory history = new OrderStatusHistory();

        history.order = order;
        history.fromStatus = fromStatus;
        history.toStatus = toStatus;
        history.changedByType = changedByType;
        history.changedByAccountId = changedByAccountId;
        history.changedByUsername = changedByUsername;

        return history;
    }

    public Long getId() {
        return id;
    }

    public Order getOrder() {
        return order;
    }

    public OrderStatus getFromStatus() {
        return fromStatus;
    }

    public OrderStatus getToStatus() {
        return toStatus;
    }

    public OrderStatusHistoryActorType getChangedByType() {
        return changedByType;
    }

    public Long getChangedByAccountId() {
        return changedByAccountId;
    }

    public String getChangedByUsername() {
        return changedByUsername;
    }

    public String getInternalNote() {
        return internalNote;
    }

    public void setInternalNote(String internalNote) {
        this.internalNote = internalNote;
    }

    public LocalDateTime getChangedAt() {
        return changedAt;
    }
}
