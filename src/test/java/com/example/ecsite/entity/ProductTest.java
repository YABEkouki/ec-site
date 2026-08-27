package com.example.ecsite.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.example.ecsite.exception.InvalidStockAdjustmentException;

class ProductTest {

    @Test
    void adjustStockCanIncreaseStock() {

        Product product = new Product();
        product.setStock(10);

        product.adjustStock(5);

        assertEquals(
                15,
                product.getStock());
    }

    @Test
    void adjustStockCanDecreaseStock() {

        Product product = new Product();
        product.setStock(10);

        product.adjustStock(-3);

        assertEquals(
                7,
                product.getStock());
    }

    @Test
    void adjustStockCanReduceStockToZero() {

        Product product = new Product();
        product.setStock(3);

        product.adjustStock(-3);

        assertEquals(
                0,
                product.getStock());
    }

    @Test
    void adjustStockRejectsNegativeResult() {

        Product product = new Product();
        product.setStock(2);

        assertThrows(
                InvalidStockAdjustmentException.class,
                () -> product.adjustStock(-3));

        assertEquals(
                2,
                product.getStock());
    }

    @Test
    void adjustStockRejectsZeroAdjustment() {

        Product product = new Product();
        product.setStock(10);

        assertThrows(
                InvalidStockAdjustmentException.class,
                () -> product.adjustStock(0));

        assertEquals(
                10,
                product.getStock());
    }
}