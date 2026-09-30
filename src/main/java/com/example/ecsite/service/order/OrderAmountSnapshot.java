package com.example.ecsite.service.order;

import com.example.ecsite.entity.Order;

public record OrderAmountSnapshot(
        int itemSubtotal,
        int chargeTotal,
        int taxAmount,
        int totalAmount) {

    public static OrderAmountSnapshot from(Order order) {
        return new OrderAmountSnapshot(
                order.getItemSubtotal(),
                order.getChargeTotal(),
                order.getTaxAmount(),
                order.getTotalAmount());
    }
}
