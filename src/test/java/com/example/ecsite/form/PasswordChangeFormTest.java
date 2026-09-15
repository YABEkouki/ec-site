package com.example.ecsite.form;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class PasswordChangeFormTest {

    private Validator validator;

    @BeforeEach
    void setUp() {

        ValidatorFactory factory =
                Validation.buildDefaultValidatorFactory();

        validator = factory.getValidator();
    }

    @Test
    void validPasswordChangeFormHasNoValidationErrors() {

        PasswordChangeForm form = createValidForm();

        Set<ConstraintViolation<PasswordChangeForm>> violations =
                validator.validate(form);

        assertTrue(violations.isEmpty());
    }

    @Test
    void currentPasswordIsRequired() {

        PasswordChangeForm form = createValidForm();
        form.setCurrentPassword("");

        Set<ConstraintViolation<PasswordChangeForm>> violations =
                validator.validate(form);

        assertTrue(
                hasViolation(
                        violations,
                        "currentPassword",
                        "現在のパスワードを入力してください。"));
    }

    @Test
    void newPasswordIsRequired() {

        PasswordChangeForm form = createValidForm();
        form.setNewPassword("");

        Set<ConstraintViolation<PasswordChangeForm>> violations =
                validator.validate(form);

        assertTrue(
                hasViolation(
                        violations,
                        "newPassword",
                        "新しいパスワードを入力してください。"));
    }

    @Test
    void newPasswordMustBeAtLeastEightCharacters() {

        PasswordChangeForm form = createValidForm();
        form.setNewPassword("1234567");

        Set<ConstraintViolation<PasswordChangeForm>> violations =
                validator.validate(form);

        assertTrue(
                hasViolation(
                        violations,
                        "newPassword",
                        "新しいパスワードは8文字以上72文字以下で入力してください。"));
    }

    @Test
    void newPasswordMustBeAtMostSeventyTwoCharacters() {

        PasswordChangeForm form = createValidForm();
        form.setNewPassword("a".repeat(73));

        Set<ConstraintViolation<PasswordChangeForm>> violations =
                validator.validate(form);

        assertTrue(
                hasViolation(
                        violations,
                        "newPassword",
                        "新しいパスワードは8文字以上72文字以下で入力してください。"));
    }

    @Test
    void confirmPasswordIsRequired() {

        PasswordChangeForm form = createValidForm();
        form.setConfirmPassword("");

        Set<ConstraintViolation<PasswordChangeForm>> violations =
                validator.validate(form);

        assertTrue(
                hasViolation(
                        violations,
                        "confirmPassword",
                        "確認用パスワードを入力してください。"));
    }

    private PasswordChangeForm createValidForm() {

        PasswordChangeForm form = new PasswordChangeForm();

        form.setCurrentPassword("current-password");
        form.setNewPassword("new-password");
        form.setConfirmPassword("new-password");

        return form;
    }

    private boolean hasViolation(
            Set<ConstraintViolation<PasswordChangeForm>> violations,
            String propertyName,
            String message) {

        return violations.stream()
                .anyMatch(violation ->
                        violation.getPropertyPath()
                                .toString()
                                .equals(propertyName)
                        && violation.getMessage()
                                .equals(message));
    }
}
