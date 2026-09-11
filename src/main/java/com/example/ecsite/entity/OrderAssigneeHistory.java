package com.example.ecsite.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "order_assignee_histories")
public class OrderAssigneeHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "from_admin_account_id")
    private Long fromAdminAccountId;

    @Column(name = "from_admin_username", length = 100)
    private String fromAdminUsername;

    @Column(name = "to_admin_account_id")
    private Long toAdminAccountId;

    @Column(name = "to_admin_username", length = 100)
    private String toAdminUsername;

    @Column(name = "changed_by_account_id", nullable = false)
    private Long changedByAccountId;

    @Column(name = "changed_by_username", nullable = false, length = 100)
    private String changedByUsername;

    @Column(name = "change_event_id", nullable = false)
    private UUID changeEventId;

    @Column(
            name = "changed_at",
            nullable = false,
            insertable = false,
            updatable = false)
    private LocalDateTime changedAt;

    protected OrderAssigneeHistory() {
    }

    public static OrderAssigneeHistory create(
            Order order,
            Long fromAdminAccountId,
            String fromAdminUsername,
            Long toAdminAccountId,
            String toAdminUsername,
            Long changedByAccountId,
            String changedByUsername,
            UUID changeEventId) {

        OrderAssigneeHistory history = new OrderAssigneeHistory();

        history.order = order;
        history.fromAdminAccountId = fromAdminAccountId;
        history.fromAdminUsername = fromAdminUsername;
        history.toAdminAccountId = toAdminAccountId;
        history.toAdminUsername = toAdminUsername;
        history.changedByAccountId = changedByAccountId;
        history.changedByUsername = changedByUsername;
        history.changeEventId = changeEventId;

        return history;
    }

    public Long getId() {
        return id;
    }

    public Order getOrder() {
        return order;
    }

    public Long getFromAdminAccountId() {
        return fromAdminAccountId;
    }

    public String getFromAdminUsername() {
        return fromAdminUsername;
    }

    public Long getToAdminAccountId() {
        return toAdminAccountId;
    }

    public String getToAdminUsername() {
        return toAdminUsername;
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
