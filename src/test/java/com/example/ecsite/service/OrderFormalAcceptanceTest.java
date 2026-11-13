package com.example.ecsite.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.ecsite.controller.OrderShippingPreparationViewFactory;
import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderAcceptanceMode;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.exception.InvalidOrderStatusException;

/** Feature127: formal acceptance time and acceptance-based change deadline. */
class OrderFormalAcceptanceTest {

    private final OrderDeadlineCalculator calculator = new OrderDeadlineCalculator(LocalTime.of(14, 0));
    private final OrderModificationPolicy policy = new OrderModificationPolicy();

    private Order accepted(LocalDateTime orderedAt, LocalDateTime acceptedAt) {
        Order order = Order.awaitingAcceptance(1L, orderedAt);
        order.acceptFormally(acceptedAt, calculator.calculate(acceptedAt));
        return order;
    }

    /** Design doc 2.2 table: operation start vs formal acceptance vs shipping day. */
    @ParameterizedTest
    @CsvSource({
            "2026-11-12T13:50,        2026-11-12T13:51,        2026-11-12T14:00",
            "2026-11-12T13:59,        2026-11-12T13:59:30,     2026-11-12T14:00",
            "2026-11-12T13:59,        2026-11-12T14:02,        2026-11-13T14:00",
            "2026-11-12T13:59,        2026-11-12T14:08,        2026-11-13T14:00",
            "2026-11-12T14:01,        2026-11-12T14:02,        2026-11-13T14:00",
            "2026-11-12T13:59:59,     2026-11-12T13:59:59.999999, 2026-11-12T14:00",
            "2026-11-12T13:59:59,     2026-11-12T14:00,        2026-11-13T14:00",
            "2026-11-12T23:59,        2026-11-13T00:01,        2026-11-13T14:00",
            "2026-12-31T23:59,        2027-01-01T14:30,        2027-01-02T14:00"
    })
    void deadlineIsBasedOnFormalAcceptanceNotOperationStart(LocalDateTime orderedAt, LocalDateTime acceptedAt,
            LocalDateTime expectedDeadline) {
        Order order = accepted(orderedAt, acceptedAt);
        assertThat(order.getAcceptedAt()).isEqualTo(acceptedAt);
        assertThat(order.getChangeDeadlineAt()).isEqualTo(expectedDeadline);
        assertThat(order.getOrderedAt()).isEqualTo(orderedAt);
        // Same-day shipping preparation becomes possible only at the acceptance-based deadline.
        assertThat(policy.allowsPreparationStart(order, expectedDeadline.minusNanos(1000))).isFalse();
        assertThat(policy.allowsModification(order, expectedDeadline.minusNanos(1000))).isTrue();
        assertThat(policy.allowsPreparationStart(order, expectedDeadline)).isTrue();
        assertThat(policy.allowsModification(order, expectedDeadline)).isFalse();
    }

    @Test
    void threeDsPendingOrderIsNeitherModifiableNorSameDayShippingCandidate() {
        LocalDateTime orderedAt = LocalDateTime.of(2026, 11, 12, 13, 59);
        Order order = Order.awaitingAcceptance(1L, orderedAt);
        assertThat(order.isAwaitingAcceptance()).isTrue();
        assertThat(order.isFormallyAccepted()).isFalse();
        assertThat(order.getChangeDeadlineAt()).isNull();
        for (LocalDateTime now : new LocalDateTime[] {orderedAt.plusSeconds(10),
                LocalDateTime.of(2026, 11, 12, 14, 0), LocalDateTime.of(2026, 11, 13, 14, 0)}) {
            assertThat(policy.allowsModification(order, now)).isFalse();
            assertThat(policy.allowsPreparationStart(order, now)).isFalse();
            assertThat(order.isWithinModificationPeriod(now)).isFalse();
            assertThat(order.canCancelByUser(now)).isFalse();
            assertThat(order.canChangeItemsByUser(now)).isFalse();
            assertThat(order.canChangeShippingAddress(now)).isFalse();
        }
    }

    @Test
    void acceptanceIsRecordedOnceAndNeverRecalculated() {
        LocalDateTime acceptedAt = LocalDateTime.of(2026, 11, 12, 13, 51);
        Order order = accepted(acceptedAt.minusMinutes(1), acceptedAt);
        assertThat(order.acceptFormally(acceptedAt.plusHours(2), acceptedAt.plusDays(1))).isFalse();
        assertThat(order.getAcceptedAt()).isEqualTo(acceptedAt);
        assertThat(order.getChangeDeadlineAt()).isEqualTo(LocalDateTime.of(2026, 11, 12, 14, 0));
    }

    @Test
    void legacyOrderKeepsStoredDeadlineAndIsNotReaccepted() {
        LocalDateTime orderedAt = LocalDateTime.of(2026, 11, 12, 13, 59);
        Order legacy = new Order(1L, 100, orderedAt, orderedAt.withHour(14).withMinute(0));
        assertThat(legacy.getAcceptanceMode()).isEqualTo(OrderAcceptanceMode.LEGACY);
        assertThat(legacy.isFormallyAccepted()).isTrue();
        assertThat(legacy.isAwaitingAcceptance()).isFalse();
        assertThat(legacy.acceptFormally(orderedAt.plusMinutes(3), orderedAt.plusDays(1))).isFalse();
        assertThat(legacy.getAcceptedAt()).isNull();
        assertThat(legacy.getChangeDeadlineAt()).isEqualTo(LocalDateTime.of(2026, 11, 12, 14, 0));
    }

    @Test
    void invalidAcceptanceInputsAreRejected() {
        Order order = Order.awaitingAcceptance(1L, LocalDateTime.of(2026, 11, 12, 10, 0));
        LocalDateTime at = LocalDateTime.of(2026, 11, 12, 10, 1);
        assertThatThrownBy(() -> order.acceptFormally(null, at)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order.acceptFormally(at, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order.acceptFormally(at, at)).isInstanceOf(IllegalArgumentException.class);
        ReflectionTestUtils.setField(order, "status", OrderStatus.CANCELLED);
        assertThatThrownBy(() -> order.acceptFormally(at, at.withHour(14)))
                .isInstanceOf(InvalidOrderStatusException.class);
        assertThat(order.getAcceptedAt()).isNull();
        assertThat(order.getChangeDeadlineAt()).isNull();
    }

    @Test
    void shippingPreparationViewShowsAwaitingAcceptance() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 13, 15, 0);
        var factory = new OrderShippingPreparationViewFactory(policy,
                Clock.fixed(now.atZone(ZoneId.of("Asia/Tokyo")).toInstant(), ZoneId.of("Asia/Tokyo")));
        var view = factory.create(Order.awaitingAcceptance(1L, LocalDateTime.of(2026, 11, 12, 13, 59)));
        assertThat(view.state()).isEqualTo(OrderShippingPreparationViewFactory.State.AWAITING_ACCEPTANCE);
        assertThat(view.canStart()).isFalse();
        assertThat(view.message()).contains("正式受注前");
    }
}
