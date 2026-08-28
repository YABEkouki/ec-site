package com.example.ecsite.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class StockMovementTest {

    @Test
    void createAdminAdjustmentCreatesMovementSnapshot() {

        Product product = new Product();
        product.setId(1L);
        product.setName("テスト商品");

        User user = new User();
        user.setUsername("admin");

        StockMovement movement = StockMovement.createAdminAdjustment(
                product,
                10,
                7,
                -3,
                10L,
                "admin",
                "棚卸し差異");

        assertEquals(
                StockMovementActorType.ADMIN,
                movement.getChangedByType());
        assertEquals(10L, movement.getChangedByAccountId());
        assertEquals("admin", movement.getChangedByUsername());

        assertEquals(product, movement.getProduct());
        assertEquals("テスト商品", movement.getProductName());

        assertEquals(-3, movement.getQuantity());
        assertEquals(10, movement.getStockBefore());
        assertEquals(7, movement.getStockAfter());

        assertEquals(
                StockMovementActorType.ADMIN,
                movement.getChangedByType());
        assertEquals(10L, movement.getChangedByAccountId());
        assertEquals("admin", movement.getChangedByUsername());

        assertNull(movement.getOrderId());
        assertEquals("棚卸し差異", movement.getReason());
    }

    @Test
    void createAdminAdjustmentAllowsNullReason() {

        Product product = new Product();
        product.setName("テスト商品");

        User user = new User();
        user.setUsername("admin");

        StockMovement movement = StockMovement.createAdminAdjustment(
                product,
                5,
                10,
                5,
                10L,
                "admin",
                null);

        assertNull(movement.getReason());
    }

    @Test
    void createOrderPlacementCreatesSystemMovementSnapshot() {

        Product product = new Product();
        product.setId(1L);
        product.setName("テスト商品");

        StockMovement movement = StockMovement.createOrderPlacement(
                product,
                10,
                7,
                -3,
                100L);

        assertEquals(
                StockMovementType.ORDER_PLACEMENT,
                movement.getMovementType());

        assertEquals(product, movement.getProduct());
        assertEquals("テスト商品", movement.getProductName());

        assertEquals(-3, movement.getQuantity());
        assertEquals(10, movement.getStockBefore());
        assertEquals(7, movement.getStockAfter());

        assertEquals(
                StockMovementActorType.SYSTEM,
                movement.getChangedByType());
        assertNull(movement.getChangedByAccountId());
        assertEquals("SYSTEM", movement.getChangedByUsername());

        assertEquals(100L, movement.getOrderId());
        assertNull(movement.getReason());
    }

    @Test
    void createOrderCancellationCreatesSystemMovementSnapshot() {

        Product product = new Product();
        product.setId(1L);
        product.setName("テスト商品");

        StockMovement movement = StockMovement.createOrderCancellation(
                product,
                7,
                10,
                3,
                100L);

        assertEquals(
                StockMovementType.ORDER_CANCELLATION,
                movement.getMovementType());

        assertEquals(product, movement.getProduct());
        assertEquals("テスト商品", movement.getProductName());

        assertEquals(3, movement.getQuantity());
        assertEquals(7, movement.getStockBefore());
        assertEquals(10, movement.getStockAfter());

        assertEquals(
                StockMovementActorType.SYSTEM,
                movement.getChangedByType());
        assertNull(movement.getChangedByAccountId());
        assertEquals("SYSTEM", movement.getChangedByUsername());

        assertEquals(100L, movement.getOrderId());
        assertNull(movement.getReason());
    }
}