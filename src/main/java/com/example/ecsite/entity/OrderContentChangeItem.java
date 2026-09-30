package com.example.ecsite.entity;

import java.math.BigDecimal;

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
@Table(name = "order_content_change_items")
public class OrderContentChangeItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "history_id", nullable = false)
    private OrderContentChangeHistory history;

    @Column(name = "order_item_id")
    private Long orderItemId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "product_name", nullable = false, length = 100)
    private String productName;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 20)
    private OrderContentChangeType changeType;

    @Column(name = "old_price")
    private Integer oldPrice;

    @Column(name = "new_price")
    private Integer newPrice;

    @Column(name = "old_quantity")
    private Integer oldQuantity;

    @Column(name = "new_quantity")
    private Integer newQuantity;

    @Column(name = "old_subtotal")
    private Integer oldSubtotal;

    @Column(name = "new_subtotal")
    private Integer newSubtotal;

    @Column(name = "old_tax_category_id")
    private Long oldTaxCategoryId;

    @Column(name = "new_tax_category_id")
    private Long newTaxCategoryId;

    @Column(name = "old_tax_category_code", length = 30)
    private String oldTaxCategoryCode;

    @Column(name = "new_tax_category_code", length = 30)
    private String newTaxCategoryCode;

    @Column(name = "old_tax_category_name", length = 100)
    private String oldTaxCategoryName;

    @Column(name = "new_tax_category_name", length = 100)
    private String newTaxCategoryName;

    @Column(name = "old_tax_rate", precision = 5, scale = 2)
    private BigDecimal oldTaxRate;

    @Column(name = "new_tax_rate", precision = 5, scale = 2)
    private BigDecimal newTaxRate;

    public OrderContentChangeItem() {
    }

    public Long getId() {
        return id;
    }

    public OrderContentChangeHistory getHistory() {
        return history;
    }

    public void setHistory(OrderContentChangeHistory history) {
        this.history = history;
    }

    public Long getOrderItemId() {
        return orderItemId;
    }

    public Long getProductId() {
        return productId;
    }

    public String getProductName() {
        return productName;
    }

    public OrderContentChangeType getChangeType() {
        return changeType;
    }

    public Integer getOldPrice() {
        return oldPrice;
    }

    public Integer getNewPrice() {
        return newPrice;
    }

    public Integer getOldQuantity() {
        return oldQuantity;
    }

    public Integer getNewQuantity() {
        return newQuantity;
    }

    public Integer getOldSubtotal() {
        return oldSubtotal;
    }

    public Integer getNewSubtotal() {
        return newSubtotal;
    }

    public Long getOldTaxCategoryId() {
        return oldTaxCategoryId;
    }

    public Long getNewTaxCategoryId() {
        return newTaxCategoryId;
    }

    public String getOldTaxCategoryCode() {
        return oldTaxCategoryCode;
    }

    public String getNewTaxCategoryCode() {
        return newTaxCategoryCode;
    }

    public String getOldTaxCategoryName() {
        return oldTaxCategoryName;
    }

    public String getNewTaxCategoryName() {
        return newTaxCategoryName;
    }

    public BigDecimal getOldTaxRate() {
        return oldTaxRate;
    }

    public BigDecimal getNewTaxRate() {
        return newTaxRate;
    }

    public static OrderContentChangeItem updated(
            Long orderItemId,
            Long productId,
            String productName,
            int price,
            int oldQuantity,
            int newQuantity,
            int oldSubtotal,
            int newSubtotal,
            Long taxCategoryId,
            String taxCategoryCode,
            String taxCategoryName,
            BigDecimal taxRate) {

        OrderContentChangeItem item = new OrderContentChangeItem();

        item.orderItemId = orderItemId;
        item.productId = productId;
        item.productName = productName;
        item.changeType = OrderContentChangeType.UPDATED;

        item.oldPrice = price;
        item.newPrice = price;

        item.oldQuantity = oldQuantity;
        item.newQuantity = newQuantity;

        item.oldSubtotal = oldSubtotal;
        item.newSubtotal = newSubtotal;

        item.oldTaxCategoryId = taxCategoryId;
        item.newTaxCategoryId = taxCategoryId;
        item.oldTaxCategoryCode = taxCategoryCode;
        item.newTaxCategoryCode = taxCategoryCode;
        item.oldTaxCategoryName = taxCategoryName;
        item.newTaxCategoryName = taxCategoryName;
        item.oldTaxRate = taxRate;
        item.newTaxRate = taxRate;

        return item;
    }

    public static OrderContentChangeItem removed(
            Long orderItemId,
            Long productId,
            String productName,
            int price,
            int oldQuantity,
            int oldSubtotal,
            Long taxCategoryId,
            String taxCategoryCode,
            String taxCategoryName,
            BigDecimal taxRate) {

        OrderContentChangeItem item = new OrderContentChangeItem();

        item.orderItemId = orderItemId;
        item.productId = productId;
        item.productName = productName;
        item.changeType = OrderContentChangeType.REMOVED;

        item.oldPrice = price;
        item.newPrice = null;

        item.oldQuantity = oldQuantity;
        item.newQuantity = null;

        item.oldSubtotal = oldSubtotal;
        item.newSubtotal = null;

        item.oldTaxCategoryId = taxCategoryId;
        item.newTaxCategoryId = null;
        item.oldTaxCategoryCode = taxCategoryCode;
        item.newTaxCategoryCode = null;
        item.oldTaxCategoryName = taxCategoryName;
        item.newTaxCategoryName = null;
        item.oldTaxRate = taxRate;
        item.newTaxRate = null;

        return item;
    }

}
