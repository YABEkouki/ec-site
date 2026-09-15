package com.example.ecsite.dto;

import java.time.LocalDateTime;

public record UserAccountInfo(
        String username,
        LocalDateTime createdAt,
        LocalDateTime previousLoginAt,
        LocalDateTime lastLoginAt) {
}
