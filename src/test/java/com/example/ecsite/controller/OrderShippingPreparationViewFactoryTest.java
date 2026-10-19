package com.example.ecsite.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.service.OrderModificationPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class OrderShippingPreparationViewFactoryTest {
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 10, 16, 14, 0);
    private Order order() { return new Order(1L, 100, DEADLINE.withHour(10), DEADLINE); }
    private OrderShippingPreparationViewFactory factory(LocalDateTime now) {
        return new OrderShippingPreparationViewFactory(new OrderModificationPolicy(),
                Clock.fixed(now.atZone(ZoneId.of("Asia/Tokyo")).toInstant(), ZoneId.of("Asia/Tokyo")));
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"ORDERED", "PAID"})
    void deadlineIsCandidateWithoutRequiringAnyPaymentData(OrderStatus status) {
        Order order = order();
        ReflectionTestUtils.setField(order, "status", status);
        assertThat(factory(DEADLINE).create(order).canStart()).isTrue();
        assertThat(order.getShippingPreparationStartedAt()).isNull();
        assertThat(order.getStatus()).isEqualTo(status);
    }

    @Test
    void beforeDeadlineHidesButton() {
        var view = factory(DEADLINE.minusNanos(1)).create(order());
        assertThat(view.canStart()).isFalse();
        assertThat(view.state()).isEqualTo(OrderShippingPreparationViewFactory.State.BEFORE_DEADLINE);
    }

    @Test
    void afterDeadlineIsCandidate() {
        assertThat(factory(DEADLINE.plusNanos(1)).create(order()).canStart()).isTrue();
    }

    @Test
    void preparedUsesOriginalSnapshotEvenBeforeDeadline() {
        Order order = order();
        order.beginShippingPreparation(DEADLINE, 7L, "old-admin");
        var view = factory(DEADLINE.minusHours(1)).create(order);
        assertThat(view.canStart()).isFalse();
        assertThat(view.state()).isEqualTo(OrderShippingPreparationViewFactory.State.STARTED);
        assertThat(view.startedAt()).isEqualTo(DEADLINE);
        assertThat(view.startedByAccountId()).isEqualTo(7L);
        assertThat(view.startedByUsername()).isEqualTo("old-admin");
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"SHIPPED", "CANCELLED"})
    void terminalStatusNeverShowsButtonAndLegacyNullMetadataIsValid(OrderStatus status) {
        Order order = order();
        ReflectionTestUtils.setField(order, "status", status);
        var view = factory(DEADLINE).create(order);
        assertThat(view.canStart()).isFalse();
        assertThat(view.state().name()).isEqualTo(status.name());
        order.beginShippingPreparation(DEADLINE, 7L, "admin");
        assertThat(factory(DEADLINE).create(order).startedByUsername()).isEqualTo("admin");
    }

    @ParameterizedTest
    @ValueSource(strings = {"shippingPreparationStartedAt", "shippingPreparationStartedByAccountId", "shippingPreparationStartedByUsername"})
    void partialMetadataIsInconsistent(String field) {
        Order order = order();
        ReflectionTestUtils.setField(order, field, switch (field) {
            case "shippingPreparationStartedAt" -> DEADLINE;
            case "shippingPreparationStartedByAccountId" -> 7L;
            default -> "admin";
        });
        assertThat(factory(DEADLINE).create(order).state()).isEqualTo(OrderShippingPreparationViewFactory.State.INCONSISTENT);
        assertThat(factory(DEADLINE).create(order).canStart()).isFalse();
    }

    @Test
    void invalidCompleteMetadataIsInconsistent() {
        Order order = order();
        order.beginShippingPreparation(DEADLINE, 7L, "admin");
        ReflectionTestUtils.setField(order, "shippingPreparationStartedByUsername", " ");
        assertThat(factory(DEADLINE).create(order).state()).isEqualTo(OrderShippingPreparationViewFactory.State.INCONSISTENT);
    }

    @ParameterizedTest
    @CsvSource({"10,0", "14,1", "15,1"})
    void usesExistingDeadlineCalculation(int orderHour, int daysLater) {
        LocalDateTime orderedAt = DEADLINE.withHour(orderHour);
        LocalDateTime deadline = new com.example.ecsite.service.OrderDeadlineCalculator(LocalTime.of(14, 0))
                .calculate(orderedAt);
        assertThat(deadline).isEqualTo(DEADLINE.plusDays(daysLater));
        Order order = new Order(1L, 100, orderedAt, deadline);
        assertThat(factory(deadline.minusNanos(1)).create(order).canStart()).isFalse();
        assertThat(factory(deadline).create(order).canStart()).isTrue();
    }

    @Test
    void invalidDeadlineIsUnavailable() {
        Order order = order();
        ReflectionTestUtils.setField(order, "changeDeadlineAt", null);
        assertThat(factory(DEADLINE).create(order).state()).isEqualTo(OrderShippingPreparationViewFactory.State.UNAVAILABLE);
        assertThat(factory(DEADLINE).create(null).canStart()).isFalse();
    }
}
