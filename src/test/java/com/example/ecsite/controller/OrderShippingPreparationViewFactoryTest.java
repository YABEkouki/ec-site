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
    void warehouseExportPreparationIsStartedAndAutomatic() {
        Order order = order();
        order.beginShippingPreparationByWarehouseExport(DEADLINE.plusMinutes(5));
        var view = factory(DEADLINE.plusHours(1)).create(order);
        assertThat(view.state()).isEqualTo(OrderShippingPreparationViewFactory.State.STARTED);
        assertThat(view.automatic()).isTrue();
        assertThat(view.startedByAccountId()).isNull();
        assertThat(order.hasCompleteShippingPreparation()).isTrue();
        assertThat(order.isShippingPreparedByWarehouseExport()).isTrue();
        var admin = order();
        admin.beginShippingPreparation(DEADLINE, 7L, "admin");
        assertThat(factory(DEADLINE).create(admin).automatic()).isFalse();
        assertThat(admin.isShippingPreparedByWarehouseExport()).isFalse();
    }

    @Test
    void warehouseExportNameWithoutTimeIsInconsistentAndPreparationIsNeverOverwritten() {
        Order withoutTime = order();
        ReflectionTestUtils.setField(withoutTime, "shippingPreparationStartedByUsername", Order.WAREHOUSE_EXPORT_PREPARER);
        assertThat(factory(DEADLINE).create(withoutTime).state()).isEqualTo(OrderShippingPreparationViewFactory.State.INCONSISTENT);
        Order prepared = order();
        prepared.beginShippingPreparationByWarehouseExport(DEADLINE);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> prepared.beginShippingPreparation(DEADLINE, 7L, "admin"))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> prepared.beginShippingPreparationByWarehouseExport(DEADLINE))
                .isInstanceOf(IllegalStateException.class);
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

    /** Feature136-C1: accepted 15:00 on 11/20 (deadline 11/21 14:00) is a same-day candidate only on 11/20. */
    private Order acceptedAfterCutoff() {
        Order order = order();
        LocalDateTime accepted = LocalDateTime.of(2026, 11, 20, 15, 0);
        ReflectionTestUtils.setField(order, "acceptanceMode", com.example.ecsite.entity.OrderAcceptanceMode.AUTHORIZED_ACCEPTANCE);
        ReflectionTestUtils.setField(order, "orderedAt", accepted.minusMinutes(5));
        ReflectionTestUtils.setField(order, "acceptedAt", accepted);
        ReflectionTestUtils.setField(order, "changeDeadlineAt", LocalDateTime.of(2026, 11, 21, 14, 0));
        return order;
    }

    @Test
    void orderAcceptedAfterCutoffIsASameDayCandidateOnItsAcceptanceDayOnly() {
        var sameDay = factory(LocalDateTime.of(2026, 11, 20, 18, 0)).create(acceptedAfterCutoff());
        assertThat(sameDay.state()).isEqualTo(OrderShippingPreparationViewFactory.State.SAME_DAY_CANDIDATE);
        assertThat(sameDay.canStart()).isTrue();
        assertThat(sameDay.sameDay()).isTrue();
        assertThat(sameDay.message()).contains("14時以降に受け付けた注文", "変更・キャンセルができなくなります");
        var nextMorning = factory(LocalDateTime.of(2026, 11, 21, 9, 0)).create(acceptedAfterCutoff());
        assertThat(nextMorning.state()).isEqualTo(OrderShippingPreparationViewFactory.State.BEFORE_DEADLINE);
        assertThat(nextMorning.canStart()).isFalse();
        var afterDeadline = factory(LocalDateTime.of(2026, 11, 21, 14, 0)).create(acceptedAfterCutoff());
        assertThat(afterDeadline.state()).isEqualTo(OrderShippingPreparationViewFactory.State.CANDIDATE);
        assertThat(afterDeadline.sameDay()).isFalse();
    }

    @Test
    void orderAcceptedBeforeCutoffIsNeverASameDayCandidate() {
        Order order = order();
        LocalDateTime accepted = LocalDateTime.of(2026, 11, 20, 13, 59);
        ReflectionTestUtils.setField(order, "acceptanceMode", com.example.ecsite.entity.OrderAcceptanceMode.AUTHORIZED_ACCEPTANCE);
        ReflectionTestUtils.setField(order, "orderedAt", accepted.minusMinutes(5));
        ReflectionTestUtils.setField(order, "acceptedAt", accepted);
        ReflectionTestUtils.setField(order, "changeDeadlineAt", LocalDateTime.of(2026, 11, 20, 14, 0));
        assertThat(factory(LocalDateTime.of(2026, 11, 20, 13, 59, 30)).create(order).state())
                .isEqualTo(OrderShippingPreparationViewFactory.State.BEFORE_DEADLINE);
    }

    @Test
    void legacyOrderIsNeverASameDayCandidate() {
        Order order = new Order(1L, 100, LocalDateTime.of(2026, 11, 20, 15, 0), LocalDateTime.of(2026, 11, 21, 14, 0));
        assertThat(factory(LocalDateTime.of(2026, 11, 20, 16, 0)).create(order).state())
                .isEqualTo(OrderShippingPreparationViewFactory.State.BEFORE_DEADLINE);
    }
}
