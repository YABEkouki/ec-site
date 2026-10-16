package com.example.ecsite.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import com.example.ecsite.entity.Order;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class OrderModificationGuardTest {
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 10, 16, 14, 0);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderModificationGuard guard = new OrderModificationGuard(orders, new OrderModificationPolicy());

    private Order order() {
        return new Order(1L, 100, DEADLINE.minusHours(4), DEADLINE);
    }

    @Test void returnsDatabaseTimeBeforeDeadline() {
        LocalDateTime now = DEADLINE.minusNanos(1);
        when(orders.findCurrentTimeInTokyo()).thenReturn(now);
        assertThat(guard.requireAllowed(order())).isEqualTo(now);
        verify(orders).findCurrentTimeInTokyo();
        verifyNoMoreInteractions(orders);
    }

    @ParameterizedTest @ValueSource(longs = {0, 1})
    void rejectsAtAndAfterDeadline(long nanos) {
        when(orders.findCurrentTimeInTokyo()).thenReturn(DEADLINE.plusNanos(nanos));
        assertThatThrownBy(() -> guard.requireAllowed(order()))
                .isInstanceOf(InvalidOrderStatusException.class)
                .hasMessage("この注文の変更受付は終了しています。");
    }

    @Test void rejectsPreparedOrderBeforeDeadline() {
        Order order = order();
        ReflectionTestUtils.setField(order, "shippingPreparationStartedAt", DEADLINE.minusHours(2));
        ReflectionTestUtils.setField(order, "shippingPreparationStartedByAccountId", 7L);
        ReflectionTestUtils.setField(order, "shippingPreparationStartedByUsername", "admin");
        when(orders.findCurrentTimeInTokyo()).thenReturn(DEADLINE.minusHours(1));
        assertThatThrownBy(() -> guard.requireAllowed(order)).isInstanceOf(InvalidOrderStatusException.class);
    }

    @Test void missingDatabaseTimeFailsClosed() {
        assertThatThrownBy(() -> guard.requireAllowed(order())).isInstanceOf(InvalidOrderStatusException.class);
    }
}
