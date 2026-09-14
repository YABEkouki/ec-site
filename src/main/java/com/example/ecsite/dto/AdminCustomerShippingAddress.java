package com.example.ecsite.dto;

public record AdminCustomerShippingAddress(
        String name,
        String recipientName,
        String postalCode,
        String prefecture,
        String city,
        String addressLine,
        String phone,
        boolean defaultAddress) {
}
