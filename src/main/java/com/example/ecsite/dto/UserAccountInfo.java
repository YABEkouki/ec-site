package com.example.ecsite.dto;

import java.time.LocalDateTime;

public record UserAccountInfo(
        String username,
        String email,
        LocalDateTime emailVerifiedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime previousLoginAt,
        LocalDateTime lastLoginAt) {
}
