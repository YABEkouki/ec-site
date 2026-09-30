package com.example.ecsite.entity;

import java.math.BigDecimal;
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
@Table(name = "order_charges")
public class OrderCharge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_type", nullable = false, length = 30)
    private OrderChargeType chargeType;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private int amount;

    @Column(name = "tax_category_id", nullable = false)
    private Long taxCategoryId;

    @Column(name = "tax_category_code", nullable = false, length = 30)
    private String taxCategoryCode;

    @Column(name = "tax_category_name", nullable = false, length = 100)
    private String taxCategoryName;

    @Column(name = "tax_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal taxRate;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public OrderCharge() {
    }

    public OrderCharge(
            OrderChargeType chargeType,
            String name,
            int amount,
            Long taxCategoryId,
            String taxCategoryCode,
            String taxCategoryName,
            BigDecimal taxRate,
            int displayOrder) {

        this.chargeType = chargeType;
        this.name = name;
        this.amount = amount;
        this.taxCategoryId = taxCategoryId;
        this.taxCategoryCode = taxCategoryCode;
        this.taxCategoryName = taxCategoryName;
        this.taxRate = taxRate;
        this.displayOrder = displayOrder;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Order getOrder() {
        return order;
    }

    public void setOrder(Order order) {
        this.order = order;
    }

    public OrderChargeType getChargeType() {
        return chargeType;
    }

    public String getName() {
        return name;
    }

    public int getAmount() {
        return amount;
    }

    public void updateAmount(int amount) {
        this.amount = amount;
    }

    public Long getTaxCategoryId() {
        return taxCategoryId;
    }

    public String getTaxCategoryCode() {
        return taxCategoryCode;
    }

    public String getTaxCategoryName() {
        return taxCategoryName;
    }

    public BigDecimal getTaxRate() {
        return taxRate;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
