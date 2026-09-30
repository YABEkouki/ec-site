package com.example.ecsite.dto;

import java.util.List;

public record OrderItemChangePreview(
        List<Item> items,
        int oldItemSubtotal,
        int newItemSubtotal,
        int oldChargeTotal,
        int newChargeTotal,
        int oldTaxAmount,
        int newTaxAmount,
        int oldTotalAmount,
        int newTotalAmount) {

    public record Item(
            Long orderItemId,
            String productName,
            int price,
            int oldQuantity,
            int newQuantity,
            int oldSubtotal,
            int newSubtotal) {

        public boolean isRemoved() {
            return newQuantity == 0;
        }

        public boolean isChanged() {
            return oldQuantity != newQuantity;
        }
    }

    public boolean isAmountChanged() {
        return oldItemSubtotal != newItemSubtotal
                || oldChargeTotal != newChargeTotal
                || oldTaxAmount != newTaxAmount
                || oldTotalAmount != newTotalAmount;
    }

    public boolean isChanged() {
        return items.stream().anyMatch(Item::isChanged);
    }
}
