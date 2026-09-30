package com.example.ecsite.form;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public class OrderItemChangeForm {

    @NotNull
    private Integer contentRevision;

    @Valid
    private List<Item> items = new ArrayList<>();

    public Integer getContentRevision() {
        return contentRevision;
    }

    public void setContentRevision(Integer contentRevision) {
        this.contentRevision = contentRevision;
    }

    public List<Item> getItems() {
        return items;
    }

    public void setItems(List<Item> items) {
        this.items = items;
    }

    public static class Item {

        @NotNull
        private Long orderItemId;

        @NotNull
        private Integer quantity;

        public Long getOrderItemId() {
            return orderItemId;
        }

        public void setOrderItemId(Long orderItemId) {
            this.orderItemId = orderItemId;
        }

        public Integer getQuantity() {
            return quantity;
        }

        public void setQuantity(Integer quantity) {
            this.quantity = quantity;
        }
    }
}
