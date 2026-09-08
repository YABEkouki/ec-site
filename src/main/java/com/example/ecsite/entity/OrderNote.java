package com.example.ecsite.entity;

import java.time.LocalDateTime;

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
@Table(name = "order_notes")
public class OrderNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(nullable = false, length = 1000)
    private String note;

    @Column(name = "created_by_account_id", nullable = false)
    private Long createdByAccountId;

    @Column(name = "created_by_username", nullable = false, length = 100)
    private String createdByUsername;

    @Column(
            name = "created_at",
            nullable = false,
            insertable = false,
            updatable = false)
    private LocalDateTime createdAt;

    protected OrderNote() {
    }

    public static OrderNote create(
            Order order,
            String note,
            Long createdByAccountId,
            String createdByUsername) {

        OrderNote orderNote = new OrderNote();
        orderNote.order = order;
        orderNote.note = note;
        orderNote.createdByAccountId = createdByAccountId;
        orderNote.createdByUsername = createdByUsername;

        return orderNote;
    }

    public Long getId() {
        return id;
    }

    public Order getOrder() {
        return order;
    }

    public String getNote() {
        return note;
    }

    public Long getCreatedByAccountId() {
        return createdByAccountId;
    }

    public String getCreatedByUsername() {
        return createdByUsername;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
