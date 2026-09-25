package com.example.ecsite.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;

import com.example.ecsite.entity.User;
import com.example.ecsite.form.PasswordForgotForm;
import com.example.ecsite.form.PasswordResetForm;
import com.example.ecsite.service.MailService;
import com.example.ecsite.service.PasswordResetResult;
import com.example.ecsite.service.PasswordResetService;
import com.example.ecsite.service.UserService;

@ExtendWith(MockitoExtension.class)
class PasswordResetControllerTest {

    @Mock
    private PasswordResetService passwordResetService;

    @Mock
    private UserService userService;

    @Mock
    private MailService mailService;

    @Mock
    private Model model;

    private PasswordResetController controller;

    @BeforeEach
    void setUp() {

        controller = new PasswordResetController(
                passwordResetService,
                userService,
                mailService);
    }

    @Test
    void showForgotFormDisplaysForgotForm() {

        String view = controller.showForgotForm(model);

        assertEquals(
                "password/forgot",
                view);

        verify(model).addAttribute(
                org.mockito.ArgumentMatchers.eq(
                        "passwordForgotForm"),
                org.mockito.ArgumentMatchers
                        .any(PasswordForgotForm.class));
    }

    @Test
    void requestPasswordResetSendsMailForRegisteredEmail() {

        PasswordForgotForm form =
                createForgotForm("user@example.com");

        BindingResult bindingResult =
                createBindingResult(
                        form,
                        "passwordForgotForm");

        User user = new User();
        user.setEmail("user@example.com");

        when(passwordResetService
                .issueToken("user@example.com"))
                .thenReturn("raw-token");

        when(userService
                .findByEmail("user@example.com"))
                .thenReturn(user);

        String view =
                controller.requestPasswordReset(
                        form,
                        bindingResult,
                        model);

        assertEquals(
                "password/forgot-complete",
                view);

        verify(mailService)
                .sendPasswordReset(
                        "user@example.com",
                        "raw-token");

        verify(model)
                .addAttribute(
                        "message",
                        "ご入力いただいたメールアドレスが登録されている場合、"
                                + "パスワード再設定用のメールを送信します。");
    }

    @Test
    void requestPasswordResetReturnsSameResponseForUnknownEmail() {

        PasswordForgotForm form =
                createForgotForm("unknown@example.com");

        BindingResult bindingResult =
                createBindingResult(
                        form,
                        "passwordForgotForm");

        when(passwordResetService
                .issueToken("unknown@example.com"))
                .thenReturn(null);

        String view =
                controller.requestPasswordReset(
                        form,
                        bindingResult,
                        model);

        assertEquals(
                "password/forgot-complete",
                view);

        verify(mailService, never())
                .sendPasswordReset(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());

        verify(model)
                .addAttribute(
                        "message",
                        "ご入力いただいたメールアドレスが登録されている場合、"
                                + "パスワード再設定用のメールを送信します。");
    }

    @Test
    void requestPasswordResetInvalidatesTokenWhenMailFails() {

        PasswordForgotForm form =
                createForgotForm("user@example.com");

        BindingResult bindingResult =
                createBindingResult(
                        form,
                        "passwordForgotForm");

        User user = new User();
        user.setEmail("user@example.com");

        when(passwordResetService
                .issueToken("user@example.com"))
                .thenReturn("raw-token");

        when(userService
                .findByEmail("user@example.com"))
                .thenReturn(user);

        doThrow(new MailSendException(
                "mail send failed"))
                .when(mailService)
                .sendPasswordReset(
                        "user@example.com",
                        "raw-token");

        String view =
                controller.requestPasswordReset(
                        form,
                        bindingResult,
                        model);

        assertEquals(
                "password/forgot-complete",
                view);

        verify(passwordResetService)
                .invalidateToken("raw-token");

        verify(model)
                .addAttribute(
                        "message",
                        "ご入力いただいたメールアドレスが登録されている場合、"
                                + "パスワード再設定用のメールを送信します。");
    }

    @Test
    void requestPasswordResetReturnsFormWhenValidationFails() {

        PasswordForgotForm form =
                createForgotForm("");

        BindingResult bindingResult =
                createBindingResult(
                        form,
                        "passwordForgotForm");

        bindingResult.rejectValue(
                "email",
                "invalid",
                "メールアドレスを入力してください。");

        String view =
                controller.requestPasswordReset(
                        form,
                        bindingResult,
                        model);

        assertEquals(
                "password/forgot",
                view);

        verify(passwordResetService, never())
                .issueToken(
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void showResetFormDisplaysFormForValidToken() {

        when(passwordResetService
                .validateToken("valid-token"))
                .thenReturn(
                        PasswordResetResult.VALID);

        String view =
                controller.showResetForm(
                        "valid-token",
                        model);

        assertEquals(
                "password/reset",
                view);

        verify(model).addAttribute(
                org.mockito.ArgumentMatchers.eq(
                        "passwordResetForm"),
                org.mockito.ArgumentMatchers
                        .any(PasswordResetForm.class));

        verify(model)
                .addAttribute(
                        "token",
                        "valid-token");
    }

    @Test
    void showResetFormDisplaysInvalidPageForInvalidToken() {

        when(passwordResetService
                .validateToken("invalid-token"))
                .thenReturn(
                        PasswordResetResult.EXPIRED);

        String view =
                controller.showResetForm(
                        "invalid-token",
                        model);

        assertEquals(
                "password/reset-invalid",
                view);

        verify(model)
                .addAttribute(
                        "errorMessage",
                        "このパスワード再設定リンクは無効または期限切れです。"
                                + "もう一度パスワード再設定を行ってください。");
    }

    @Test
    void resetPasswordReturnsFormWhenPasswordValidationFails() {

        PasswordResetForm form =
                createResetForm(
                        "short",
                        "short");

        BindingResult bindingResult =
                createBindingResult(
                        form,
                        "passwordResetForm");

        bindingResult.rejectValue(
                "newPassword",
                "invalid",
                "新しいパスワードは8文字以上72文字以下で入力してください。");

        when(passwordResetService
                .validateToken("valid-token"))
                .thenReturn(
                        PasswordResetResult.VALID);

        String view =
                controller.resetPassword(
                        "valid-token",
                        form,
                        bindingResult,
                        model);

        assertEquals(
                "password/reset",
                view);

        verify(model)
                .addAttribute(
                        "token",
                        "valid-token");

        verify(passwordResetService, never())
                .resetPassword(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void resetPasswordRejectsPasswordMismatch() {

        PasswordResetForm form =
                createResetForm(
                        "new-password",
                        "different-password");

        BindingResult bindingResult =
                createBindingResult(
                        form,
                        "passwordResetForm");

        when(passwordResetService
                .validateToken("valid-token"))
                .thenReturn(
                        PasswordResetResult.VALID);

        String view =
                controller.resetPassword(
                        "valid-token",
                        form,
                        bindingResult,
                        model);

        assertEquals(
                "password/reset",
                view);

        assertTrue(
                bindingResult.hasFieldErrors(
                        "confirmPassword"));

        verify(passwordResetService, never())
                .resetPassword(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void resetPasswordDisplaysInvalidPageWhenTokenIsInvalid() {

        PasswordResetForm form =
                createResetForm(
                        "new-password",
                        "new-password");

        BindingResult bindingResult =
                createBindingResult(
                        form,
                        "passwordResetForm");

        when(passwordResetService
                .validateToken("invalid-token"))
                .thenReturn(
                        PasswordResetResult.USED);

        String view =
                controller.resetPassword(
                        "invalid-token",
                        form,
                        bindingResult,
                        model);

        assertEquals(
                "password/reset-invalid",
                view);

        verify(passwordResetService, never())
                .resetPassword(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void resetPasswordCompletesForValidToken() {

        PasswordResetForm form =
                createResetForm(
                        "new-password",
                        "new-password");

        BindingResult bindingResult =
                createBindingResult(
                        form,
                        "passwordResetForm");

        when(passwordResetService
                .validateToken("valid-token"))
                .thenReturn(
                        PasswordResetResult.VALID);

        when(passwordResetService
                .resetPassword(
                        "valid-token",
                        "new-password"))
                .thenReturn(
                        PasswordResetResult.VALID);

        String view =
                controller.resetPassword(
                        "valid-token",
                        form,
                        bindingResult,
                        model);

        assertEquals(
                "password/reset-complete",
                view);

        verify(passwordResetService)
                .resetPassword(
                        "valid-token",
                        "new-password");
    }

    @Test
    void resetPasswordHandlesTokenBecomingInvalidBeforeUpdate() {

        PasswordResetForm form =
                createResetForm(
                        "new-password",
                        "new-password");

        BindingResult bindingResult =
                createBindingResult(
                        form,
                        "passwordResetForm");

        when(passwordResetService
                .validateToken("valid-token"))
                .thenReturn(
                        PasswordResetResult.VALID);

        when(passwordResetService
                .resetPassword(
                        "valid-token",
                        "new-password"))
                .thenReturn(
                        PasswordResetResult.USED);

        String view =
                controller.resetPassword(
                        "valid-token",
                        form,
                        bindingResult,
                        model);

        assertEquals(
                "password/reset-invalid",
                view);
    }

    private PasswordForgotForm createForgotForm(
            String email) {

        PasswordForgotForm form =
                new PasswordForgotForm();

        form.setEmail(email);

        return form;
    }

    private PasswordResetForm createResetForm(
            String newPassword,
            String confirmPassword) {

        PasswordResetForm form =
                new PasswordResetForm();

        form.setNewPassword(newPassword);
        form.setConfirmPassword(confirmPassword);

        return form;
    }

    private BindingResult createBindingResult(
            Object form,
            String objectName) {

        return new BeanPropertyBindingResult(
                form,
                objectName);
    }
}
