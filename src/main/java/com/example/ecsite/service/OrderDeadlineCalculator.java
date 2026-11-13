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

    /**
     * Change deadline from the formal acceptance time (Asia/Tokyo wall clock):
     * accepted before the deadline time -> same day; at or after it -> next day.
     * V81 enforces the same rule for 14:00 in the database.
     */
    public LocalDateTime calculate(LocalDateTime acceptedAt) {

        LocalDateTime deadline =
                acceptedAt.toLocalDate().atTime(changeDeadlineTime);

        if (acceptedAt.isBefore(deadline)) {
            return deadline;
        }

        return deadline.plusDays(1);
    }
}
