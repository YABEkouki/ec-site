package com.example.ecsite.dto;

import java.time.LocalDate;

public record DailySalesSummary(
                LocalDate date,
                long orderCount,
                long salesOrderCount,
                long salesAmount) {
}