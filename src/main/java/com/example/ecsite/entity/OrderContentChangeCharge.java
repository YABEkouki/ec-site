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
@Table(name = "order_content_change_charges")
public class OrderContentChangeCharge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "history_id", nullable = false)
    private OrderContentChangeHistory history;

    @Column(name = "order_charge_id")
    private Long orderChargeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_type", nullable = false, length = 30)
    private OrderChargeType chargeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 20)
    private OrderContentChangeType changeType;

    @Column(name = "old_name", length = 100)
    private String oldName;

    @Column(name = "new_name", length = 100)
    private String newName;

    @Column(name = "old_amount")
    private Integer oldAmount;

    @Column(name = "new_amount")
    private Integer newAmount;

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

    @Column(name = "old_display_order")
    private Integer oldDisplayOrder;

    @Column(name = "new_display_order")
    private Integer newDisplayOrder;

    public OrderContentChangeCharge() {
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

    public Long getOrderChargeId() {
        return orderChargeId;
    }

    public OrderChargeType getChargeType() {
        return chargeType;
    }

    public OrderContentChangeType getChangeType() {
        return changeType;
    }

    public String getOldName() {
        return oldName;
    }

    public String getNewName() {
        return newName;
    }

    public Integer getOldAmount() {
        return oldAmount;
    }

    public Integer getNewAmount() {
        return newAmount;
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

    public Integer getOldDisplayOrder() {
        return oldDisplayOrder;
    }

    public Integer getNewDisplayOrder() {
        return newDisplayOrder;
    }

    public static OrderContentChangeCharge updated(
            Long orderChargeId,
            OrderChargeType chargeType,
            String oldName,
            String newName,
            int oldAmount,
            int newAmount,
            Long oldTaxCategoryId,
            Long newTaxCategoryId,
            String oldTaxCategoryCode,
            String newTaxCategoryCode,
            String oldTaxCategoryName,
            String newTaxCategoryName,
            BigDecimal oldTaxRate,
            BigDecimal newTaxRate,
            int oldDisplayOrder,
            int newDisplayOrder) {

        OrderContentChangeCharge charge = new OrderContentChangeCharge();

        charge.orderChargeId = orderChargeId;
        charge.chargeType = chargeType;
        charge.changeType = OrderContentChangeType.UPDATED;

        charge.oldName = oldName;
        charge.newName = newName;
        charge.oldAmount = oldAmount;
        charge.newAmount = newAmount;

        charge.oldTaxCategoryId = oldTaxCategoryId;
        charge.newTaxCategoryId = newTaxCategoryId;
        charge.oldTaxCategoryCode = oldTaxCategoryCode;
        charge.newTaxCategoryCode = newTaxCategoryCode;
        charge.oldTaxCategoryName = oldTaxCategoryName;
        charge.newTaxCategoryName = newTaxCategoryName;
        charge.oldTaxRate = oldTaxRate;
        charge.newTaxRate = newTaxRate;

        charge.oldDisplayOrder = oldDisplayOrder;
        charge.newDisplayOrder = newDisplayOrder;

        return charge;
    }

}
