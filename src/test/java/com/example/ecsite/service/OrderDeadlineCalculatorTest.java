package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

class OrderDeadlineCalculatorTest {

    private final OrderDeadlineCalculator calculator =
            new OrderDeadlineCalculator(LocalTime.of(14, 0));

    @Test
    void calculate_締切時刻より前なら当日の締切時刻を返す() {

        LocalDateTime orderedAt =
                LocalDateTime.of(2026, 9, 28, 13, 59, 59);

        LocalDateTime result = calculator.calculate(orderedAt);

        assertEquals(
                LocalDateTime.of(2026, 9, 28, 14, 0),
                result);
    }

    @Test
    void calculate_締切時刻ちょうどなら翌日の締切時刻を返す() {

        LocalDateTime orderedAt =
                LocalDateTime.of(2026, 9, 28, 14, 0);

        LocalDateTime result = calculator.calculate(orderedAt);

        assertEquals(
                LocalDateTime.of(2026, 9, 29, 14, 0),
                result);
    }

    @Test
    void calculate_締切時刻より後なら翌日の締切時刻を返す() {

        LocalDateTime orderedAt =
                LocalDateTime.of(2026, 9, 28, 14, 0, 1);

        LocalDateTime result = calculator.calculate(orderedAt);

        assertEquals(
                LocalDateTime.of(2026, 9, 29, 14, 0),
                result);
    }
}
