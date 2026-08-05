package com.example.ecsite.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.example.ecsite.exception.InvalidOrderStatusException;

class OrderTest {

    @Test
    void newOrderStartsWithOrderedStatus() {

        Order order = new Order(1L, 1000);

        assertEquals(
                OrderStatus.ORDERED,
                order.getStatus());
    }

    @Test
    void orderCanMoveFromOrderedToPaidToShipped() {

        Order order = new Order(1L, 1000);

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

        Order order = new Order(1L, 1000);

        assertThrows(
                InvalidOrderStatusException.class,
                order::markAsShipped);

        assertEquals(
                OrderStatus.ORDERED,
                order.getStatus());
    }

    @Test
    void orderedOrderCanBeCancelled() {

        Order order = new Order(1L, 1000);

        order.cancel();

        assertEquals(
                OrderStatus.CANCELLED,
                order.getStatus());
    }

    @Test
    void paidOrderCannotBeCancelled() {

        Order order = new Order(1L, 1000);
        order.markAsPaid();

        assertThrows(
                InvalidOrderStatusException.class,
                order::cancel);

        assertEquals(
                OrderStatus.PAID,
                order.getStatus());
    }
}