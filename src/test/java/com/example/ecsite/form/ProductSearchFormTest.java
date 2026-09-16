package com.example.ecsite.form;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProductSearchFormTest {

    @Test
    void priceRangeIsValidWhenBothPricesAreNotSpecified() {

        ProductSearchForm form = new ProductSearchForm();

        assertTrue(form.isPriceRangeValid());
    }

    @Test
    void priceRangeIsValidWhenOnlyMinimumPriceIsSpecified() {

        ProductSearchForm form = new ProductSearchForm();
        form.setMinPrice(1000);

        assertTrue(form.isPriceRangeValid());
    }

    @Test
    void priceRangeIsValidWhenOnlyMaximumPriceIsSpecified() {

        ProductSearchForm form = new ProductSearchForm();
        form.setMaxPrice(5000);

        assertTrue(form.isPriceRangeValid());
    }

    @Test
    void priceRangeIsValidWhenMinimumPriceEqualsMaximumPrice() {

        ProductSearchForm form = new ProductSearchForm();
        form.setMinPrice(3000);
        form.setMaxPrice(3000);

        assertTrue(form.isPriceRangeValid());
    }

    @Test
    void priceRangeIsValidWhenMinimumPriceIsLessThanMaximumPrice() {

        ProductSearchForm form = new ProductSearchForm();
        form.setMinPrice(1000);
        form.setMaxPrice(5000);

        assertTrue(form.isPriceRangeValid());
    }

    @Test
    void priceRangeIsInvalidWhenMinimumPriceIsGreaterThanMaximumPrice() {

        ProductSearchForm form = new ProductSearchForm();
        form.setMinPrice(5000);
        form.setMaxPrice(1000);

        assertFalse(form.isPriceRangeValid());
    }
}
