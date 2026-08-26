package com.example.ecsite.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        @Test
        void paidAndShippedTimestampsAreRecorded() {

                Order order = new Order(1L, 1000);

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

                Order order = new Order(1L, 1000);

                assertNull(order.getCancelledAt());

                order.cancel();

                assertNotNull(order.getCancelledAt());
        }

        @Test
        void invalidShippingDoesNotRecordTimestamp() {

                Order order = new Order(1L, 1000);

                assertThrows(
                                InvalidOrderStatusException.class,
                                order::markAsShipped);

                assertNull(order.getShippedAt());
        }

        @Test
        void orderedOrderCanBeCancelledAccordingToStatus() {

                Order order = new Order(1L, 1000);

                assertTrue(order.canCancel());
        }

        @Test
        void paidOrderCannotBeCancelledAccordingToStatus() {

                Order order = new Order(1L, 1000);
                order.markAsPaid();

                assertFalse(order.canCancel());
        }

        @Test
        void orderedOrderCanBeMarkedAsPaid() {

                Order order = new Order(1L, 1000);

                assertTrue(order.canMarkAsPaid());
        }

        @Test
        void paidOrderCannotBeMarkedAsPaidAgain() {

                Order order = new Order(1L, 1000);
                order.markAsPaid();

                assertFalse(order.canMarkAsPaid());
        }

        @Test
        void paidOrderCanBeMarkedAsShipped() {

                Order order = new Order(1L, 1000);
                order.markAsPaid();

                assertTrue(order.canMarkAsShipped());
        }

        @Test
        void orderedOrderCannotBeMarkedAsShipped() {

                Order order = new Order(1L, 1000);

                assertFalse(order.canMarkAsShipped());
        }

}
