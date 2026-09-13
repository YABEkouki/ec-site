package com.example.ecsite.dto;

public record AdminAssigneeActionRequiredSummary(
        Long adminAccountId,
        String username,
        boolean enabled,
        long orderCount) {
}
