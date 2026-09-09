package com.example.ecsite.dto;

import java.time.LocalDateTime;

import com.example.ecsite.entity.Order;

public record AdminActionRequiredOrderDto(
        Order order,
        LocalDateTime handlingStatusUpdatedAt,
        Long elapsedDays) {
}
