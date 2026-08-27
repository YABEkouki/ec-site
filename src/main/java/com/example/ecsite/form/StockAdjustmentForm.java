package com.example.ecsite.form;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class StockAdjustmentForm {

    @NotNull(message = "調整数を入力してください。")
    private Integer quantity;

    @Size(max = 500, message = "調整理由は500文字以内で入力してください。")
    private String reason;

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}