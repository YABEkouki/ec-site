package com.example.ecsite.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

@Entity
@Table(name = "order_content_change_histories")
public class OrderContentChangeHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_source", nullable = false, length = 20)
    private OrderContentChangeSource changeSource;

    @Column(length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "changed_by_type", nullable = false, length = 20)
    private OrderContentChangeHistoryActorType changedByType;

    @Column(name = "changed_by_account_id")
    private Long changedByAccountId;

    @Column(name = "changed_by_username", nullable = false, length = 100)
    private String changedByUsername;

    @Column(name = "old_item_subtotal", nullable = false)
    private int oldItemSubtotal;

    @Column(name = "new_item_subtotal", nullable = false)
    private int newItemSubtotal;

    @Column(name = "old_charge_total", nullable = false)
    private int oldChargeTotal;

    @Column(name = "new_charge_total", nullable = false)
    private int newChargeTotal;

    @Column(name = "old_tax_amount", nullable = false)
    private int oldTaxAmount;

    @Column(name = "new_tax_amount", nullable = false)
    private int newTaxAmount;

    @Column(name = "old_total_amount", nullable = false)
    private int oldTotalAmount;

    @Column(name = "new_total_amount", nullable = false)
    private int newTotalAmount;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    @OneToMany(
            mappedBy = "history",
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    private List<OrderContentChangeItem> items = new ArrayList<>();

    @OneToMany(
            mappedBy = "history",
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    private List<OrderContentChangeCharge> charges = new ArrayList<>();

    public OrderContentChangeHistory() {
    }

    public OrderContentChangeHistory(
            Order order,
            OrderContentChangeSource changeSource,
            String reason,
            OrderContentChangeHistoryActorType changedByType,
            Long changedByAccountId,
            String changedByUsername,
            int oldItemSubtotal,
            int newItemSubtotal,
            int oldChargeTotal,
            int newChargeTotal,
            int oldTaxAmount,
            int newTaxAmount,
            int oldTotalAmount,
            int newTotalAmount,
            LocalDateTime changedAt) {

        this.order = order;
        this.changeSource = changeSource;
        this.reason = reason;
        this.changedByType = changedByType;
        this.changedByAccountId = changedByAccountId;
        this.changedByUsername = changedByUsername;
        this.oldItemSubtotal = oldItemSubtotal;
        this.newItemSubtotal = newItemSubtotal;
        this.oldChargeTotal = oldChargeTotal;
        this.newChargeTotal = newChargeTotal;
        this.oldTaxAmount = oldTaxAmount;
        this.newTaxAmount = newTaxAmount;
        this.oldTotalAmount = oldTotalAmount;
        this.newTotalAmount = newTotalAmount;
        this.changedAt = changedAt;
    }

    public Long getId() {
        return id;
    }

    public Order getOrder() {
        return order;
    }

    public OrderContentChangeSource getChangeSource() {
        return changeSource;
    }

    public String getReason() {
        return reason;
    }

    public OrderContentChangeHistoryActorType getChangedByType() {
        return changedByType;
    }

    public Long getChangedByAccountId() {
        return changedByAccountId;
    }

    public String getChangedByUsername() {
        return changedByUsername;
    }

    public int getOldItemSubtotal() {
        return oldItemSubtotal;
    }

    public int getNewItemSubtotal() {
        return newItemSubtotal;
    }

    public int getOldChargeTotal() {
        return oldChargeTotal;
    }

    public int getNewChargeTotal() {
        return newChargeTotal;
    }

    public int getOldTaxAmount() {
        return oldTaxAmount;
    }

    public int getNewTaxAmount() {
        return newTaxAmount;
    }

    public int getOldTotalAmount() {
        return oldTotalAmount;
    }

    public int getNewTotalAmount() {
        return newTotalAmount;
    }

    public LocalDateTime getChangedAt() {
        return changedAt;
    }

    public List<OrderContentChangeItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public List<OrderContentChangeCharge> getCharges() {
        return Collections.unmodifiableList(charges);
    }

    public void addItem(OrderContentChangeItem item) {
        items.add(item);
        item.setHistory(this);
    }

    public void addCharge(OrderContentChangeCharge charge) {
        charges.add(charge);
        charge.setHistory(this);
    }
}
