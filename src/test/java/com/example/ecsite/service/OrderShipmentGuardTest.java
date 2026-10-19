package com.example.ecsite.service;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDateTime;
import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.exception.InvalidOrderStatusException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.test.util.ReflectionTestUtils;

class OrderShipmentGuardTest {
    private final OrderShipmentGuard guard = new OrderShipmentGuard();
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 16, 14, 0);
    private Order prepared() {
        Order order = new Order();
        order.beginShippingPreparation(now, 7L, "admin");
        return order;
    }
    @Test void unpreparedIsRejected() {
        assertThatThrownBy(() -> guard.requirePrepared(new Order())).isInstanceOf(InvalidOrderStatusException.class);
    }
    @Test void nullOrderIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> guard.requirePrepared(null));
    }
    @ParameterizedTest @EnumSource(OrderStatus.class)
    void completeMetadataIsAcceptedRegardlessOfStatusOrMissingDeadline(OrderStatus status) {
        Order order = prepared();
        ReflectionTestUtils.setField(order, "status", status);
        assertThatCode(() -> guard.requirePrepared(order)).doesNotThrowAnyException();
    }
    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    void everyPartialCombinationIsRejected(int mask) {
        Order order = new Order();
        if ((mask & 1) != 0) ReflectionTestUtils.setField(order, "shippingPreparationStartedAt", now);
        if ((mask & 2) != 0) ReflectionTestUtils.setField(order, "shippingPreparationStartedByAccountId", 7L);
        if ((mask & 4) != 0) ReflectionTestUtils.setField(order, "shippingPreparationStartedByUsername", "admin");
        assertThatThrownBy(() -> guard.requirePrepared(order)).isInstanceOf(InvalidOrderStatusException.class);
    }
    @ParameterizedTest @NullSource @ValueSource(longs = {0, -1})
    void invalidActorIsRejected(Long id) {
        Order order = prepared(); ReflectionTestUtils.setField(order, "shippingPreparationStartedByAccountId", id);
        assertThatThrownBy(() -> guard.requirePrepared(order)).isInstanceOf(InvalidOrderStatusException.class);
    }
    @ParameterizedTest @NullSource @ValueSource(strings = {"", " ", "\t\n", "　"})
    void invalidUsernameIsRejected(String username) {
        Order order = prepared(); ReflectionTestUtils.setField(order, "shippingPreparationStartedByUsername", username);
        assertThatThrownBy(() -> guard.requirePrepared(order)).isInstanceOf(InvalidOrderStatusException.class);
    }
    @Test void maximumUsernameIsAcceptedButOverlongIsRejected() {
        Order order = prepared(); ReflectionTestUtils.setField(order, "shippingPreparationStartedByUsername", "x".repeat(100));
        guard.requirePrepared(order);
        ReflectionTestUtils.setField(order, "shippingPreparationStartedByUsername", "x".repeat(101));
        assertThatThrownBy(() -> guard.requirePrepared(order)).isInstanceOf(InvalidOrderStatusException.class);
    }
}
