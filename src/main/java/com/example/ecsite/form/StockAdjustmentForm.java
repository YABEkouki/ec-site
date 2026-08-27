package com.example.ecsite.form;

import jakarta.validation.constraints.NotNull;

public class StockAdjustmentForm {

    @NotNull(message = "調整数を入力してください。")
    private Integer quantity;

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }
}