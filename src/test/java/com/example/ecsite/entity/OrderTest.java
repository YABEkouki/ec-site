package com.example.ecsite.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.service.pricing.OrderAmount;

class OrderTest {

    @Test
    void newOrderStartsWithOrderedStatus() {

        Order order = createOrder();

        assertEquals(
                OrderStatus.ORDERED,
                order.getStatus());
    }

    @Test
    void orderCanMoveFromOrderedToPaidToShipped() {

        Order order = createOrder();

        order.markAsPaid();

        assertEquals(
                OrderStatus.PAID,
                order.getStatus());

        order.markAsShipped();

        assertEquals(
                OrderStatus.SHIPPED,
                order.getStatus());
    }

    @Test
    void orderedOrderCannotBeShippedDirectly() {

        Order order = createOrder();

        assertThrows(
                InvalidOrderStatusException.class,
                order::markAsShipped);

        assertEquals(
                OrderStatus.ORDERED,
                order.getStatus());
    }

    @Test
    void orderedOrderCanBeCancelled() {

        Order order = createOrder();

        LocalDateTime cancelledAt = LocalDateTime.of(2026, 9, 28, 12, 0);

        order.cancel(cancelledAt);

        assertEquals(
                OrderStatus.CANCELLED,
                order.getStatus());
    }

    @Test
    void paidOrderCannotBeCancelled() {

        Order order = createOrder();
        order.markAsPaid();

        LocalDateTime cancelledAt = LocalDateTime.of(2026, 9, 28, 12, 0);

        assertThrows(
                InvalidOrderStatusException.class,
                () -> order.cancel(cancelledAt));
        assertEquals(
                OrderStatus.PAID,
                order.getStatus());
    }

    @Test
    void paidAndShippedTimestampsAreRecorded() {

        Order order = createOrder();

        assertNull(order.getPaidAt());
        assertNull(order.getShippedAt());

        order.markAsPaid();

        assertNotNull(order.getPaidAt());
        assertNull(order.getShippedAt());

        order.markAsShipped();

        assertNotNull(order.getPaidAt());
        assertNotNull(order.getShippedAt());
    }

    @Test
    void cancelledTimestampIsRecorded() {

        Order order = createOrder();

        LocalDateTime cancelledAt = LocalDateTime.of(2026, 9, 28, 12, 34, 56);

        assertNull(order.getCancelledAt());

        order.cancel(cancelledAt);

        assertEquals(
                cancelledAt,
                order.getCancelledAt());
    }

    @Test
    void invalidShippingDoesNotRecordTimestamp() {

        Order order = createOrder();

        assertThrows(
                InvalidOrderStatusException.class,
                order::markAsShipped);

        assertNull(order.getShippedAt());
    }

    @Test
    void orderedOrderCanBeCancelledAccordingToStatus() {

        Order order = createOrder();

        assertTrue(order.canCancel());
    }

    @Test
    void paidOrderCannotBeCancelledAccordingToStatus() {

        Order order = createOrder();
        order.markAsPaid();

        assertFalse(order.canCancel());
    }

    @Test
    void orderedOrderCanBeMarkedAsPaid() {

        Order order = createOrder();

        assertTrue(order.canMarkAsPaid());
    }

    @Test
    void paidOrderCannotBeMarkedAsPaidAgain() {

        Order order = createOrder();
        order.markAsPaid();

        assertFalse(order.canMarkAsPaid());
    }

    @Test
    void paidOrderCanBeMarkedAsShipped() {

        Order order = createOrder();
        order.markAsPaid();

        assertTrue(order.canMarkAsShipped());
    }

    @Test
    void orderedOrderCannotBeMarkedAsShipped() {

        Order order = createOrder();

        assertFalse(order.canMarkAsShipped());
    }

    @Test
    void newOrderStartsWithNoneHandlingStatus() {

        Order order = createOrder();

        assertEquals(
                OrderHandlingStatus.NONE,
                order.getHandlingStatus());
    }

    @Test
    void handlingStatusCanBeChanged() {

        Order order = createOrder();

        order.changeHandlingStatus(
                OrderHandlingStatus.IN_PROGRESS);

        assertEquals(
                OrderHandlingStatus.IN_PROGRESS,
                order.getHandlingStatus());
    }

    @Test
    void assignedAdminAccountCanBeChanged() {

        Order order = createOrder();

        AdminAccount adminAccount = new AdminAccount();
        adminAccount.setUsername("admin01");

        order.changeAssignedAdminAccount(adminAccount);

        assertEquals(
                adminAccount,
                order.getAssignedAdminAccount());
    }

    @Test
    void assignedAdminAccountCanBeCleared() {

        Order order = createOrder();

        AdminAccount adminAccount = new AdminAccount();
        adminAccount.setUsername("admin01");

        order.changeAssignedAdminAccount(adminAccount);
        order.changeAssignedAdminAccount(null);

        assertNull(order.getAssignedAdminAccount());
    }

    @Test
    void userCanCancelBeforeChangeDeadline() {

        LocalDateTime orderedAt = LocalDateTime.of(2026, 9, 28, 10, 0);
        LocalDateTime changeDeadlineAt = LocalDateTime.of(2026, 9, 28, 14, 0);

        Order order = new Order(
                1L,
                1000,
                orderedAt,
                changeDeadlineAt);

        assertTrue(order.canCancelByUser(
                LocalDateTime.of(2026, 9, 28, 13, 59, 59)));
    }

    @Test
    void userCannotCancelAtChangeDeadline() {

        LocalDateTime orderedAt = LocalDateTime.of(2026, 9, 28, 10, 0);
        LocalDateTime changeDeadlineAt = LocalDateTime.of(2026, 9, 28, 14, 0);

        Order order = new Order(
                1L,
                1000,
                orderedAt,
                changeDeadlineAt);

        assertFalse(order.canCancelByUser(
                LocalDateTime.of(2026, 9, 28, 14, 0)));
    }

    @Test
    void userCannotCancelAfterChangeDeadline() {

        LocalDateTime orderedAt = LocalDateTime.of(2026, 9, 28, 10, 0);
        LocalDateTime changeDeadlineAt = LocalDateTime.of(2026, 9, 28, 14, 0);

        Order order = new Order(
                1L,
                1000,
                orderedAt,
                changeDeadlineAt);

        assertFalse(order.canCancelByUser(
                LocalDateTime.of(2026, 9, 28, 14, 0, 1)));
    }

    @Test
    void userCannotCancelPaidOrderEvenBeforeChangeDeadline() {

        LocalDateTime orderedAt = LocalDateTime.of(2026, 9, 28, 10, 0);
        LocalDateTime changeDeadlineAt = LocalDateTime.of(2026, 9, 28, 14, 0);

        Order order = new Order(
                1L,
                1000,
                orderedAt,
                changeDeadlineAt);

        order.markAsPaid();

        assertFalse(order.canCancelByUser(
                LocalDateTime.of(2026, 9, 28, 13, 0)));
    }

    @Test
    void applyAmountUpdatesAllOrderAmounts() {

        Order order = new Order(
                1L,
                0,
                LocalDateTime.of(2026, 9, 29, 12, 0),
                LocalDateTime.of(2026, 9, 30, 12, 0));

        OrderAmount amount = new OrderAmount(
                4400,
                List.of(),
                550,
                450,
                4950);

        order.applyAmount(amount);

        assertThat(order.getItemSubtotal()).isEqualTo(4400);
        assertThat(order.getChargeTotal()).isEqualTo(550);
        assertThat(order.getTaxAmount()).isEqualTo(450);
        assertThat(order.getTotalAmount()).isEqualTo(4950);
    }

    private Order createOrder() {

        LocalDateTime orderedAt = LocalDateTime.of(2026, 9, 28, 10, 0);
        LocalDateTime changeDeadlineAt = LocalDateTime.of(2026, 9, 28, 14, 0);

        return new Order(
                1L,
                1000,
                orderedAt,
                changeDeadlineAt);
    }

}
