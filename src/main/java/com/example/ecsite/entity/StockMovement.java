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
@Table(name = "stock_movements")
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "product_name", nullable = false, length = 100)
    private String productName;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 30)
    private StockMovementType movementType;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "stock_before", nullable = false)
    private Integer stockBefore;

    @Column(name = "stock_after", nullable = false)
    private Integer stockAfter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "changed_by_user_id", nullable = false)
    private User changedByUser;

    @Column(name = "changed_by_username", nullable = false, length = 100)
    private String changedByUsername;

    @Column(length = 500)
    private String reason;

    @Column(
            name = "changed_at",
            nullable = false,
            insertable = false,
            updatable = false)
    private LocalDateTime changedAt;

    protected StockMovement() {
    }

    public static StockMovement createAdminAdjustment(
            Product product,
            int stockBefore,
            int stockAfter,
            int quantity,
            User changedByUser,
            String reason) {

        StockMovement movement = new StockMovement();

        movement.product = product;
        movement.productName = product.getName();

        movement.movementType =
                StockMovementType.ADMIN_ADJUSTMENT;

        movement.quantity = quantity;
        movement.stockBefore = stockBefore;
        movement.stockAfter = stockAfter;

        movement.changedByUser = changedByUser;
        movement.changedByUsername =
                changedByUser.getUsername();

        movement.reason = reason;

        return movement;
    }

    public Long getId() {
        return id;
    }

    public Product getProduct() {
        return product;
    }

    public String getProductName() {
        return productName;
    }

    public StockMovementType getMovementType() {
        return movementType;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public Integer getStockBefore() {
        return stockBefore;
    }

    public Integer getStockAfter() {
        return stockAfter;
    }

    public User getChangedByUser() {
        return changedByUser;
    }

    public String getChangedByUsername() {
        return changedByUsername;
    }

    public String getReason() {
        return reason;
    }

    public LocalDateTime getChangedAt() {
        return changedAt;
    }
}