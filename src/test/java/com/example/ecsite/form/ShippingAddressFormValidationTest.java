package com.example.ecsite.form;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class ShippingAddressFormValidationTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation
                .buildDefaultValidatorFactory()
                .getValidator();
    }

    @Test
    void validFormHasNoValidationErrors() {

        ShippingAddressForm form = createValidForm();

        Set<ConstraintViolation<ShippingAddressForm>> violations =
                validator.validate(form);

        assertTrue(violations.isEmpty());
    }

    @Test
    void blankAddressNameIsRejected() {

        ShippingAddressForm form = createValidForm();
        form.setName("");

        assertHasViolation(form, "name");
    }

    @Test
    void blankRecipientNameIsRejected() {

        ShippingAddressForm form = createValidForm();
        form.setRecipientName("");

        assertHasViolation(form, "recipientName");
    }

    @Test
    void invalidPostalCodeIsRejected() {

        ShippingAddressForm form = createValidForm();
        form.setPostalCode("123456");

        assertHasViolation(form, "postalCode");
    }

    @Test
    void invalidPhoneIsRejected() {

        ShippingAddressForm form = createValidForm();
        form.setPhone("abc");

        assertHasViolation(form, "phone");
    }

    private void assertHasViolation(
            ShippingAddressForm form,
            String propertyName) {

        Set<ConstraintViolation<ShippingAddressForm>> violations =
                validator.validate(form);

        assertFalse(violations.isEmpty());

        assertTrue(
                violations.stream()
                        .anyMatch(v -> propertyName.equals(
                                v.getPropertyPath().toString())));
    }

    private ShippingAddressForm createValidForm() {

        ShippingAddressForm form = new ShippingAddressForm();

        form.setName("自宅");
        form.setRecipientName("山田 太郎");
        form.setPostalCode("123-4567");
        form.setPrefecture("東京都");
        form.setCity("新宿区");
        form.setAddressLine("西新宿1-1-1");
        form.setPhone("090-1234-5678");

        return form;
    }
}