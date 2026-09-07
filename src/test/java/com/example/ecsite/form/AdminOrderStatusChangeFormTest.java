package com.example.ecsite.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class AdminOrderStatusChangeFormTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation
                .buildDefaultValidatorFactory()
                .getValidator();
    }

    @Test
    void internalNoteAllows500Characters() {

        AdminOrderStatusChangeForm form =
                new AdminOrderStatusChangeForm();

        form.setInternalNote("a".repeat(500));

        Set<ConstraintViolation<AdminOrderStatusChangeForm>> violations =
                validator.validate(form);

        assertTrue(violations.isEmpty());
    }

    @Test
    void internalNoteRejectsMoreThan500Characters() {

        AdminOrderStatusChangeForm form =
                new AdminOrderStatusChangeForm();

        form.setInternalNote("a".repeat(501));

        Set<ConstraintViolation<AdminOrderStatusChangeForm>> violations =
                validator.validate(form);

        assertEquals(
                1,
                violations.size());

        ConstraintViolation<AdminOrderStatusChangeForm> violation =
                violations.iterator().next();

        assertEquals(
                "internalNote",
                violation.getPropertyPath().toString());
    }
}
