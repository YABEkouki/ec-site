package com.example.ecsite.dto;

public record UserAccountUpdateResult(
        String username,
        String email,
        boolean emailChanged) {
}
