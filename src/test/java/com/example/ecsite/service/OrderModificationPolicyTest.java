package com.example.ecsite.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.LocalTime;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class OrderModificationPolicyTest {

    private static final LocalDateTime ORDERED_AT = LocalDateTime.of(2026, 10, 16, 10, 0);
    private static final LocalDateTime DEADLINE = ORDERED_AT.withHour(14);
    private final OrderModificationPolicy policy = new OrderModificationPolicy();

    private Order order() {
        return new Order(1L, 100, ORDERED_AT, DEADLINE);
    }

    @ParameterizedTest
    @CsvSource({"-1,true,false", "0,false,true", "1,false,true"})
    void deadlineBoundary(long nanos, boolean modification, boolean preparation) {
        Order order = order();
        LocalDateTime now = DEADLINE.plusNanos(nanos);
        assertThat(policy.allowsModification(order, now)).isEqualTo(modification);
        assertThat(policy.allowsPreparationStart(order, now)).isEqualTo(preparation);
        assertThat(policy.allowsModification(order, now))
                .isEqualTo(order.isWithinModificationPeriod(now));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 1})
    void alreadyPreparedClosesBothWindows(long nanos) {
        Order order = order();
        // JPA-loaded metadata has getters only; no preparation operation exists yet.
        ReflectionTestUtils.setField(order, "shippingPreparationStartedAt", DEADLINE);
        ReflectionTestUtils.setField(order, "shippingPreparationStartedByAccountId", 7L);
        ReflectionTestUtils.setField(order, "shippingPreparationStartedByUsername", "admin");
        assertDenied(order, DEADLINE.plusNanos(nanos));
        assertThat(order.getShippingPreparationStartedAt()).isEqualTo(DEADLINE);
        assertThat(order.getShippingPreparationStartedByAccountId()).isEqualTo(7L);
        assertThat(order.getShippingPreparationStartedByUsername()).isEqualTo("admin");
    }

    @ParameterizedTest
    @ValueSource(strings = {"shippingPreparationStartedAt", "shippingPreparationStartedByAccountId", "shippingPreparationStartedByUsername"})
    void partialPreparationMetadataFailsClosed(String field) {
        Order order = order();
        Object value = switch (field) {
            case "shippingPreparationStartedAt" -> DEADLINE;
            case "shippingPreparationStartedByAccountId" -> 7L;
            default -> "admin";
        };
        ReflectionTestUtils.setField(order, field, value);
        assertDenied(order, DEADLINE.minusNanos(1));
        assertDenied(order, DEADLINE);
    }

    @Test
    void missingInputsFailClosed() {
        assertDenied(null, DEADLINE);
        assertDenied(order(), null);
        assertDenied(new Order(), DEADLINE);
        assertDenied(new Order(1L, 100, ORDERED_AT, null), DEADLINE);
        assertDenied(new Order(1L, 100, null, DEADLINE), DEADLINE);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0})
    void deadlineMustBeAfterOrderTime(long nanos) {
        Order order = new Order(1L, 100, ORDERED_AT, ORDERED_AT.plusNanos(nanos));
        assertDenied(order, ORDERED_AT.minusNanos(2));
        assertDenied(order, DEADLINE);
    }

    @Test
    void operationSpecificStatusChecksRemainSeparate() {
        Order order = order();
        order.markAsPaid();
        LocalDateTime now = DEADLINE.minusNanos(1);
        assertThat(policy.allowsModification(order, now)).isTrue();
        assertThat(order.canChangeShippingAddress(now)).isTrue();
        assertThat(order.canChangeItemsByUser(now)).isFalse();
        assertThat(order.canCancelByUser(now)).isFalse();
        assertThat(policy.allowsPreparationStart(order, DEADLINE)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"SHIPPED", "CANCELLED"})
    void terminalOrderPolicyTrueDoesNotGrantOperationPermission(OrderStatus status) {
        Order order = order();
        if (status == OrderStatus.SHIPPED) {
            order.markAsPaid();
            order.markAsShipped();
        } else {
            order.cancel(ORDERED_AT.plusHours(1));
        }
        assertThat(order.getStatus()).isEqualTo(status);
        assertThat(order.getShippingPreparationStartedAt()).isNull();
        assertThat(order.getShippingPreparationStartedByAccountId()).isNull();
        assertThat(order.getShippingPreparationStartedByUsername()).isNull();

        // Policy true only describes the time/metadata window. Services must
        // separately reject terminal order states; this is not permission to act.
        LocalDateTime beforeDeadline = DEADLINE.minusNanos(1);
        assertThat(policy.allowsModification(order, beforeDeadline)).isTrue();
        assertThat(policy.allowsModification(order, DEADLINE)).isFalse();
        assertThat(policy.allowsPreparationStart(order, DEADLINE)).isTrue();
        assertThat(order.canChangeShippingAddress(beforeDeadline)).isFalse();
        assertThat(order.canChangeItemsByUser(beforeDeadline)).isFalse();
        assertThat(order.canCancelByUser(beforeDeadline)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        "2026-10-16T10:00,2026-10-16T14:00",
        "2026-10-16T14:00,2026-10-17T14:00",
        "2026-10-16T15:00,2026-10-17T14:00",
        "2026-10-16T23:59:59.999999999,2026-10-17T14:00",
        "2026-10-17T00:00,2026-10-17T14:00",
        "2026-12-31T15:00,2027-01-01T14:00"
    })
    void calculatedDeadlinesMatchBothPolicyWindows(LocalDateTime orderedAt, LocalDateTime expected) {
        var calculator = new OrderDeadlineCalculator(LocalTime.of(14, 0));
        LocalDateTime deadline = calculator.calculate(orderedAt);
        assertThat(deadline).isEqualTo(expected).isAfter(orderedAt);
        Order order = new Order(1L, 100, orderedAt, deadline);
        assertThat(policy.allowsModification(order, deadline.minusNanos(1))).isTrue();
        assertThat(policy.allowsModification(order, deadline)).isFalse();
        assertThat(policy.allowsPreparationStart(order, deadline.minusNanos(1))).isFalse();
        assertThat(policy.allowsPreparationStart(order, deadline)).isTrue();
    }

    private void assertDenied(Order order, LocalDateTime now) {
        assertThat(policy.allowsModification(order, now)).isFalse();
        assertThat(policy.allowsPreparationStart(order, now)).isFalse();
    }
}
