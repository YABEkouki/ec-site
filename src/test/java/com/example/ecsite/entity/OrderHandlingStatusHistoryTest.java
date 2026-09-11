package com.example.ecsite.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class OrderHandlingStatusHistoryTest {

    @Test
    void createSetsChangeEventId() {
        Order order = new Order();
        UUID changeEventId = UUID.randomUUID();

        OrderHandlingStatusHistory history = OrderHandlingStatusHistory.create(
                order,
                OrderHandlingStatus.NEEDS_ACTION,
                OrderHandlingStatus.IN_PROGRESS,
                10L,
                "admin",
                changeEventId);

        assertThat(history.getOrder()).isSameAs(order);
        assertThat(history.getFromStatus())
                .isEqualTo(OrderHandlingStatus.NEEDS_ACTION);
        assertThat(history.getToStatus())
                .isEqualTo(OrderHandlingStatus.IN_PROGRESS);
        assertThat(history.getChangedByAccountId()).isEqualTo(10L);
        assertThat(history.getChangedByUsername()).isEqualTo("admin");
        assertThat(history.getChangeEventId()).isEqualTo(changeEventId);
    }
}
