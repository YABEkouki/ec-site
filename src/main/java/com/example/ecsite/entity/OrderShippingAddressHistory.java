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
@Table(name = "order_shipping_address_histories")
public class OrderShippingAddressHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(name = "changed_by_type", nullable = false, length = 20)
    private OrderShippingAddressHistoryActorType changedByType;

    @Column(name = "changed_by_account_id")
    private Long changedByAccountId;

    @Column(name = "changed_by_username", nullable = false, length = 100)
    private String changedByUsername;

    @Column(name = "old_shipping_name", length = 100)
    private String oldShippingName;

    @Column(name = "old_shipping_postal_code", length = 8)
    private String oldShippingPostalCode;

    @Column(name = "old_shipping_prefecture", length = 20)
    private String oldShippingPrefecture;

    @Column(name = "old_shipping_city", length = 100)
    private String oldShippingCity;

    @Column(name = "old_shipping_address_line", length = 200)
    private String oldShippingAddressLine;

    @Column(name = "old_shipping_phone", length = 20)
    private String oldShippingPhone;

    @Column(name = "new_shipping_name", length = 100)
    private String newShippingName;

    @Column(name = "new_shipping_postal_code", length = 8)
    private String newShippingPostalCode;

    @Column(name = "new_shipping_prefecture", length = 20)
    private String newShippingPrefecture;

    @Column(name = "new_shipping_city", length = 100)
    private String newShippingCity;

    @Column(name = "new_shipping_address_line", length = 200)
    private String newShippingAddressLine;

    @Column(name = "new_shipping_phone", length = 20)
    private String newShippingPhone;

    @Column(
            name = "changed_at",
            nullable = false,
            insertable = false,
            updatable = false)
    private LocalDateTime changedAt;

    protected OrderShippingAddressHistory() {
    }

    public static OrderShippingAddressHistory create(
            Order order,
            OrderShippingAddressHistoryActorType changedByType,
            Long changedByAccountId,
            String changedByUsername,
            String oldShippingName,
            String oldShippingPostalCode,
            String oldShippingPrefecture,
            String oldShippingCity,
            String oldShippingAddressLine,
            String oldShippingPhone,
            String newShippingName,
            String newShippingPostalCode,
            String newShippingPrefecture,
            String newShippingCity,
            String newShippingAddressLine,
            String newShippingPhone) {

        OrderShippingAddressHistory history =
                new OrderShippingAddressHistory();

        history.order = order;
        history.changedByType = changedByType;
        history.changedByAccountId = changedByAccountId;
        history.changedByUsername = changedByUsername;

        history.oldShippingName = oldShippingName;
        history.oldShippingPostalCode = oldShippingPostalCode;
        history.oldShippingPrefecture = oldShippingPrefecture;
        history.oldShippingCity = oldShippingCity;
        history.oldShippingAddressLine = oldShippingAddressLine;
        history.oldShippingPhone = oldShippingPhone;

        history.newShippingName = newShippingName;
        history.newShippingPostalCode = newShippingPostalCode;
        history.newShippingPrefecture = newShippingPrefecture;
        history.newShippingCity = newShippingCity;
        history.newShippingAddressLine = newShippingAddressLine;
        history.newShippingPhone = newShippingPhone;

        return history;
    }

    public Long getId() {
        return id;
    }

    public Order getOrder() {
        return order;
    }

    public OrderShippingAddressHistoryActorType getChangedByType() {
        return changedByType;
    }

    public Long getChangedByAccountId() {
        return changedByAccountId;
    }

    public String getChangedByUsername() {
        return changedByUsername;
    }

    public String getOldShippingName() {
        return oldShippingName;
    }

    public String getOldShippingPostalCode() {
        return oldShippingPostalCode;
    }

    public String getOldShippingPrefecture() {
        return oldShippingPrefecture;
    }

    public String getOldShippingCity() {
        return oldShippingCity;
    }

    public String getOldShippingAddressLine() {
        return oldShippingAddressLine;
    }

    public String getOldShippingPhone() {
        return oldShippingPhone;
    }

    public String getNewShippingName() {
        return newShippingName;
    }

    public String getNewShippingPostalCode() {
        return newShippingPostalCode;
    }

    public String getNewShippingPrefecture() {
        return newShippingPrefecture;
    }

    public String getNewShippingCity() {
        return newShippingCity;
    }

    public String getNewShippingAddressLine() {
        return newShippingAddressLine;
    }

    public String getNewShippingPhone() {
        return newShippingPhone;
    }

    public LocalDateTime getChangedAt() {
        return changedAt;
    }
}
