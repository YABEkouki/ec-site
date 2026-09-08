package com.example.ecsite.form;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class AdminOrderNoteFormTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation
                .buildDefaultValidatorFactory()
                .getValidator();
    }

    @Test
    void noteIsRequired() {

        AdminOrderNoteForm form = new AdminOrderNoteForm();
        form.setNote("");

        Set<ConstraintViolation<AdminOrderNoteForm>> violations =
                validator.validate(form);

        assertFalse(violations.isEmpty());
    }

    @Test
    void whitespaceOnlyNoteIsInvalid() {

        AdminOrderNoteForm form = new AdminOrderNoteForm();
        form.setNote("   ");

        Set<ConstraintViolation<AdminOrderNoteForm>> violations =
                validator.validate(form);

        assertFalse(violations.isEmpty());
    }

    @Test
    void noteWith1000CharactersIsValid() {

        AdminOrderNoteForm form = new AdminOrderNoteForm();
        form.setNote("a".repeat(1000));

        Set<ConstraintViolation<AdminOrderNoteForm>> violations =
                validator.validate(form);

        assertTrue(violations.isEmpty());
    }

    @Test
    void noteWith1001CharactersIsInvalid() {

        AdminOrderNoteForm form = new AdminOrderNoteForm();
        form.setNote("a".repeat(1001));

        Set<ConstraintViolation<AdminOrderNoteForm>> violations =
                validator.validate(form);

        assertFalse(violations.isEmpty());
    }
}
