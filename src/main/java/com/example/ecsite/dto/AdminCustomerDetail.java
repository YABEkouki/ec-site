package com.example.ecsite.dto;

import java.util.List;

public record AdminCustomerDetail(
        Long userId,
        String username,
        boolean enabled,
        String name,
        String postalCode,
        String prefecture,
        String city,
        String addressLine,
        String phone,
        List<AdminCustomerShippingAddress> shippingAddresses) {
}
