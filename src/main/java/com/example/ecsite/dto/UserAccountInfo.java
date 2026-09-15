package com.example.ecsite.dto;

import java.time.LocalDateTime;

public record UserAccountInfo(
        String username,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime previousLoginAt,
        LocalDateTime lastLoginAt) {
}
