package com.example.ecsite.entity;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.ecsite.exception.InvalidOrderStatusException;

/** Feature136-B: Order.requireContentMutable mirrors the V98 freeze in memory. */
class OrderPreparationFreezeTest {
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 11, 20, 14, 0);

    private static OrderItem item() {
        return new OrderItem(1L, "p", 1L, "c", 1L, "STANDARD", "標準", new BigDecimal("10"), 100, 2);
    }

    private static Order prepared() {
        Order order = new Order(1L, 200, DEADLINE.minusHours(4), DEADLINE);
        order.addItem(item());
        order.beginShippingPreparation(DEADLINE, 7L, "admin");
        return order;
    }

    @Test
    void unpreparedOrdersStayMutable() {
        Order order = new Order(1L, 200, DEADLINE.minusHours(4), DEADLINE);
        order.addItem(item());
        order.setTotalAmount(300);
        order.incrementContentRevision();
        order.getItems().getFirst().setQuantity(3);
        order.setShippingAddress("n", "100-0001", "東京都", "千代田区", "1-1", "0312345678");
        order.cancel(DEADLINE);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void everyContentMutatorRefusesAPreparedOrder() {
        Order order = prepared();
        OrderItem existing = order.getItems().getFirst();
        List<Runnable> mutations = List.of(
                () -> order.setTotalAmount(1),
                () -> order.incrementContentRevision(),
                () -> order.addItem(item()),
                () -> order.removeItem(existing),
                () -> order.replaceCharges(List.of()),
                () -> order.setShippingAddress("n", "100-0001", "東京都", "千代田区", "1-1", "0312345678"),
                () -> order.cancel(DEADLINE),
                () -> existing.setQuantity(5),
                () -> existing.setPrice(1),
                () -> existing.setProductName("x"));
        for (Runnable mutation : mutations) {
            assertThatThrownBy(mutation::run).isInstanceOf(InvalidOrderStatusException.class)
                    .hasMessageContaining("発送準備を開始した注文");
        }
        assertThat(order.getTotalAmount()).isEqualTo(200);
        assertThat(order.getContentRevision()).isZero();
        assertThat(order.getItems()).singleElement().satisfies(i -> assertThat(i.getQuantity()).isEqualTo(2));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.ORDERED);
        assertThatCode(order::markAsPaid).doesNotThrowAnyException();
    }
}
