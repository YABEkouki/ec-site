package com.example.ecsite.service;

import java.time.LocalDateTime;
import java.time.LocalTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OrderDeadlineCalculator {

    private final LocalTime changeDeadlineTime;

    public OrderDeadlineCalculator(
            @Value("${app.order.change-deadline-time}")
            LocalTime changeDeadlineTime) {
        this.changeDeadlineTime = changeDeadlineTime;
    }

    public LocalDateTime calculate(LocalDateTime orderedAt) {

        LocalDateTime deadline =
                orderedAt.toLocalDate().atTime(changeDeadlineTime);

        if (orderedAt.isBefore(deadline)) {
            return deadline;
        }

        return deadline.plusDays(1);
    }
}
