package com.example.ecsite.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.example.ecsite.exception.InvalidOrderStatusException;

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

        order.cancel();

        assertEquals(
                OrderStatus.CANCELLED,
                order.getStatus());
    }

    @Test
    void paidOrderCannotBeCancelled() {

        Order order = createOrder();
        order.markAsPaid();

        assertThrows(
                InvalidOrderStatusException.class,
                order::cancel);

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

        assertNull(order.getCancelledAt());

        order.cancel();

        assertNotNull(order.getCancelledAt());
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
