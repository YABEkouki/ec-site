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

        StockMovement movement =
                StockMovement.createAdminAdjustment(
                        product,
                        10,
                        7,
                        -3,
                        user,
                        "棚卸し差異");

        assertEquals(
                StockMovementType.ADMIN_ADJUSTMENT,
                movement.getMovementType());

        assertEquals(product, movement.getProduct());
        assertEquals("テスト商品", movement.getProductName());

        assertEquals(-3, movement.getQuantity());
        assertEquals(10, movement.getStockBefore());
        assertEquals(7, movement.getStockAfter());

        assertEquals(user, movement.getChangedByUser());
        assertEquals("admin", movement.getChangedByUsername());

        assertEquals("棚卸し差異", movement.getReason());
    }

    @Test
    void createAdminAdjustmentAllowsNullReason() {

        Product product = new Product();
        product.setName("テスト商品");

        User user = new User();
        user.setUsername("admin");

        StockMovement movement =
                StockMovement.createAdminAdjustment(
                        product,
                        5,
                        10,
                        5,
                        user,
                        null);

        assertNull(movement.getReason());
    }
}