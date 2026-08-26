package com.example.ecsite.form;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class UserProfileFormValidationTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation
                .buildDefaultValidatorFactory()
                .getValidator();
    }

    @Test
    void validFormHasNoValidationErrors() {

        UserProfileForm form = createValidForm();

        Set<ConstraintViolation<UserProfileForm>> violations =
                validator.validate(form);

        assertTrue(violations.isEmpty());
    }

    @Test
    void blankNameIsRejected() {

        UserProfileForm form = createValidForm();
        form.setName(" ");

        assertHasViolation(form, "name");
    }

    @Test
    void invalidPostalCodeIsRejected() {

        UserProfileForm form = createValidForm();
        form.setPostalCode("123456");

        assertHasViolation(form, "postalCode");
    }

    @Test
    void invalidPhoneIsRejected() {

        UserProfileForm form = createValidForm();
        form.setPhone("123");

        assertHasViolation(form, "phone");
    }

    private void assertHasViolation(
            UserProfileForm form,
            String propertyName) {

        Set<ConstraintViolation<UserProfileForm>> violations =
                validator.validate(form);

        assertFalse(violations.isEmpty());

        assertTrue(
                violations.stream()
                        .anyMatch(v -> propertyName.equals(
                                v.getPropertyPath().toString())));
    }

    private UserProfileForm createValidForm() {

        UserProfileForm form = new UserProfileForm();

        form.setName("山田 太郎");
        form.setPostalCode("123-4567");
        form.setPrefecture("東京都");
        form.setCity("新宿区");
        form.setAddressLine("西新宿1-1-1");
        form.setPhone("090-1234-5678");

        return form;
    }
}