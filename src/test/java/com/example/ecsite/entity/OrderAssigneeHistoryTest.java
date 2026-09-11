package com.example.ecsite.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class OrderAssigneeHistoryTest {

    @Test
    void createSetsAssigneeChangeSnapshot() {
        Order order = new Order();
        UUID changeEventId = UUID.randomUUID();

        OrderAssigneeHistory history = OrderAssigneeHistory.create(
                order,
                10L,
                "admin-before",
                20L,
                "admin-after",
                30L,
                "operator",
                changeEventId);

        assertThat(history.getOrder()).isSameAs(order);
        assertThat(history.getFromAdminAccountId()).isEqualTo(10L);
        assertThat(history.getFromAdminUsername()).isEqualTo("admin-before");
        assertThat(history.getToAdminAccountId()).isEqualTo(20L);
        assertThat(history.getToAdminUsername()).isEqualTo("admin-after");
        assertThat(history.getChangedByAccountId()).isEqualTo(30L);
        assertThat(history.getChangedByUsername()).isEqualTo("operator");
        assertThat(history.getChangeEventId()).isEqualTo(changeEventId);
    }

    @Test
    void createAllowsUnassignedBeforeAndAfter() {
        Order order = new Order();
        UUID changeEventId = UUID.randomUUID();

        OrderAssigneeHistory history = OrderAssigneeHistory.create(
                order,
                null,
                null,
                null,
                null,
                30L,
                "operator",
                changeEventId);

        assertThat(history.getFromAdminAccountId()).isNull();
        assertThat(history.getFromAdminUsername()).isNull();
        assertThat(history.getToAdminAccountId()).isNull();
        assertThat(history.getToAdminUsername()).isNull();
    }
}
