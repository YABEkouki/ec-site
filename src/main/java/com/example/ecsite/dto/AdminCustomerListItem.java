package com.example.ecsite.dto;

public record AdminCustomerListItem(
        Long userId,
        String username,
        String name,
        boolean enabled) {
}
