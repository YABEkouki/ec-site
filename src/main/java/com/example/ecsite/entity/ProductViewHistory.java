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
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "product_view_histories",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_product_view_histories_user_product",
                columnNames = {"user_id", "product_id"}))
public class ProductViewHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "last_viewed_at", nullable = false)
    private LocalDateTime lastViewedAt;

    protected ProductViewHistory() {
    }

    public ProductViewHistory(
            User user,
            Product product,
            LocalDateTime lastViewedAt) {
        this.user = user;
        this.product = product;
        this.lastViewedAt = lastViewedAt;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public Product getProduct() {
        return product;
    }

    public LocalDateTime getLastViewedAt() {
        return lastViewedAt;
    }

    public void updateLastViewedAt(LocalDateTime lastViewedAt) {
        this.lastViewedAt = lastViewedAt;
    }
}
