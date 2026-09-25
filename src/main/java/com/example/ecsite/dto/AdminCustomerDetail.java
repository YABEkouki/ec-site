package com.example.ecsite.dto;

import java.time.LocalDateTime;
import java.util.List;

public record AdminCustomerDetail(
        Long userId,
        String username,
        String email,
        LocalDateTime emailVerifiedAt,
        boolean enabled,
        String name,
        String postalCode,
        String prefecture,
        String city,
        String addressLine,
        String phone,
        List<AdminCustomerShippingAddress> shippingAddresses) {
}
