package com.example.ecsite.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.example.ecsite.exception.InvalidOrderStatusException;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "total_amount", nullable = false)
    private int totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderStatus status = OrderStatus.ORDERED;

    @Column(name = "ordered_at", nullable = false)
    private LocalDateTime orderedAt;

    @Column(name = "shipping_name", length = 100)
    private String shippingName;

    @Column(name = "shipping_postal_code", length = 8)
    private String shippingPostalCode;

    @Column(name = "shipping_prefecture", length = 20)
    private String shippingPrefecture;

    @Column(name = "shipping_city", length = 100)
    private String shippingCity;

    @Column(name = "shipping_address_line", length = 200)
    private String shippingAddressLine;

    @Column(name = "shipping_phone", length = 20)
    private String shippingPhone;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    public Order() {
    }

    public Order(Long userId, int totalAmount) {
        this.userId = userId;
        this.totalAmount = totalAmount;
        this.status = OrderStatus.ORDERED;
        this.orderedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public int getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(int totalAmount) {
        this.totalAmount = totalAmount;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public LocalDateTime getOrderedAt() {
        return orderedAt;
    }

    public void setOrderedAt(LocalDateTime orderedAt) {
        this.orderedAt = orderedAt;
    }

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "shipped_at")
    private LocalDateTime shippedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    public LocalDateTime getPaidAt() {
        return paidAt;
    }

    public LocalDateTime getShippedAt() {
        return shippedAt;
    }

    public LocalDateTime getCancelledAt() {
        return cancelledAt;
    }

    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public void setShippingAddress(
            String shippingName,
            String shippingPostalCode,
            String shippingPrefecture,
            String shippingCity,
            String shippingAddressLine,
            String shippingPhone) {

        this.shippingName = shippingName;
        this.shippingPostalCode = shippingPostalCode;
        this.shippingPrefecture = shippingPrefecture;
        this.shippingCity = shippingCity;
        this.shippingAddressLine = shippingAddressLine;
        this.shippingPhone = shippingPhone;
    }

    public String getShippingName() {
        return shippingName;
    }

    public String getShippingPostalCode() {
        return shippingPostalCode;
    }

    public String getShippingPrefecture() {
        return shippingPrefecture;
    }

    public String getShippingCity() {
        return shippingCity;
    }

    public String getShippingAddressLine() {
        return shippingAddressLine;
    }

    public String getShippingPhone() {
        return shippingPhone;
    }

    public void markAsPaid() {

        if (status != OrderStatus.ORDERED) {
            throw new InvalidOrderStatusException(
                    "注文受付中の注文だけを"
                            + "支払済みに変更できます。");
        }

        status = OrderStatus.PAID;
        paidAt = LocalDateTime.now();
    }

    public void markAsShipped() {

        if (status != OrderStatus.PAID) {
            throw new InvalidOrderStatusException(
                    "支払済みの注文だけを"
                            + "発送済みに変更できます。");
        }

        status = OrderStatus.SHIPPED;
        shippedAt = LocalDateTime.now();
    }

    public void cancel() {

        if (status != OrderStatus.ORDERED) {
            throw new InvalidOrderStatusException(
                    "注文受付中の注文だけを"
                            + "キャンセルできます。");
        }

        status = OrderStatus.CANCELLED;
        cancelledAt = LocalDateTime.now();
    }

}