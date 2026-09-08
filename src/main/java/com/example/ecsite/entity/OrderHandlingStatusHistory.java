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
@Table(name = "order_handling_status_histories")
public class OrderHandlingStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false, length = 30)
    private OrderHandlingStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30)
    private OrderHandlingStatus toStatus;

    @Column(name = "changed_by_account_id", nullable = false)
    private Long changedByAccountId;

    @Column(name = "changed_by_username", nullable = false, length = 100)
    private String changedByUsername;

    @Column(
            name = "changed_at",
            nullable = false,
            insertable = false,
            updatable = false)
    private LocalDateTime changedAt;

    protected OrderHandlingStatusHistory() {
    }

    public static OrderHandlingStatusHistory create(
            Order order,
            OrderHandlingStatus fromStatus,
            OrderHandlingStatus toStatus,
            Long changedByAccountId,
            String changedByUsername) {

        OrderHandlingStatusHistory history =
                new OrderHandlingStatusHistory();

        history.order = order;
        history.fromStatus = fromStatus;
        history.toStatus = toStatus;
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

    public OrderHandlingStatus getFromStatus() {
        return fromStatus;
    }

    public OrderHandlingStatus getToStatus() {
        return toStatus;
    }

    public Long getChangedByAccountId() {
        return changedByAccountId;
    }

    public String getChangedByUsername() {
        return changedByUsername;
    }

    public LocalDateTime getChangedAt() {
        return changedAt;
    }
}
