package com.example.ecsite.form;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Set;

import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class StockAdjustmentFormTest {

    private final Validator validator = Validation
            .buildDefaultValidatorFactory()
            .getValidator();

    @Test
    void quantityIsRequired() {

        StockAdjustmentForm form = new StockAdjustmentForm();

        Set<ConstraintViolation<StockAdjustmentForm>> violations =
                validator.validate(form);

        assertFalse(violations.isEmpty());
    }
}