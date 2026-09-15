package com.example.ecsite.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.dto.AdminAccountInfo;
import com.example.ecsite.exception.IncorrectCurrentPasswordException;
import com.example.ecsite.exception.SameAsCurrentPasswordException;
import com.example.ecsite.form.PasswordChangeForm;
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.AdminAccountService;

@ExtendWith(MockitoExtension.class)
class AdminMyAccountControllerTest {

    @Mock
    private AdminAccountService adminAccountService;

    @Mock
    private Model model;

    private AdminMyAccountController controller;

    @BeforeEach
    void setUp() {

        controller = new AdminMyAccountController(
                adminAccountService);
    }

    @Test
    void indexDisplaysLoggedInAdminAccountInformation() {

        AdminUserDetails loginAdmin =
                mock(AdminUserDetails.class);

        when(loginAdmin.getId())
                .thenReturn(10L);

        AdminAccountInfo accountInfo =
                new AdminAccountInfo(
                        "admin1",
                        LocalDateTime.of(2026, 9, 1, 10, 0),
                        LocalDateTime.of(2026, 9, 10, 11, 0),
                        LocalDateTime.of(2026, 9, 14, 9, 0),
                        LocalDateTime.of(2026, 9, 15, 9, 0));

        when(adminAccountService.getAccountInfo(10L))
                .thenReturn(accountInfo);

        String viewName =
                controller.index(loginAdmin, model);

        assertEquals(
                "admin/account/index",
                viewName);

        verify(adminAccountService)
                .getAccountInfo(10L);

        verify(model)
                .addAttribute(
                        "account",
                        accountInfo);
    }

    @Test
    void editPasswordDisplaysPasswordChangeForm() {

        String viewName =
                controller.editPassword(model);

        assertEquals(
                "admin/account/password-form",
                viewName);

        verify(model)
                .addAttribute(
                        eq("passwordChangeForm"),
                        any(PasswordChangeForm.class));
    }

    @Test
    void changePasswordReturnsFormWhenValidationFails() {

        AdminUserDetails loginAdmin =
                mock(AdminUserDetails.class);

        PasswordChangeForm form =
                new PasswordChangeForm();

        BindingResult bindingResult =
                mock(BindingResult.class);

        when(bindingResult.hasErrors())
                .thenReturn(true);

        RedirectAttributes redirectAttributes =
                mock(RedirectAttributes.class);

        String viewName =
                controller.changePassword(
                        form,
                        bindingResult,
                        loginAdmin,
                        redirectAttributes);

        assertEquals(
                "admin/account/password-form",
                viewName);

        verify(adminAccountService, never())
                .changePassword(
                        any(),
                        any(),
                        any());
    }

    @Test
    void changePasswordReturnsFormWhenConfirmationDoesNotMatch() {

        AdminUserDetails loginAdmin =
                mock(AdminUserDetails.class);

        PasswordChangeForm form =
                createPasswordChangeForm(
                        "current-password",
                        "new-password",
                        "different-password");

        BindingResult bindingResult =
                mock(BindingResult.class);

        when(bindingResult.hasErrors())
                .thenReturn(false);

        RedirectAttributes redirectAttributes =
                mock(RedirectAttributes.class);

        String viewName =
                controller.changePassword(
                        form,
                        bindingResult,
                        loginAdmin,
                        redirectAttributes);

        assertEquals(
                "admin/account/password-form",
                viewName);

        verify(bindingResult)
                .rejectValue(
                        "confirmPassword",
                        "mismatch",
                        "新しいパスワードと確認用パスワードが一致しません。");

        verify(adminAccountService, never())
                .changePassword(
                        any(),
                        any(),
                        any());
    }

    @Test
    void changePasswordReturnsFormWhenCurrentPasswordIsIncorrect() {

        AdminUserDetails loginAdmin =
                mock(AdminUserDetails.class);

        when(loginAdmin.getId())
                .thenReturn(10L);

        PasswordChangeForm form =
                createPasswordChangeForm(
                        "wrong-password",
                        "new-password",
                        "new-password");

        BindingResult bindingResult =
                mock(BindingResult.class);

        when(bindingResult.hasErrors())
                .thenReturn(false);

        RedirectAttributes redirectAttributes =
                mock(RedirectAttributes.class);

        doThrow(new IncorrectCurrentPasswordException())
                .when(adminAccountService)
                .changePassword(
                        10L,
                        "wrong-password",
                        "new-password");

        String viewName =
                controller.changePassword(
                        form,
                        bindingResult,
                        loginAdmin,
                        redirectAttributes);

        assertEquals(
                "admin/account/password-form",
                viewName);

        verify(bindingResult)
                .rejectValue(
                        "currentPassword",
                        "incorrect",
                        "現在のパスワードが正しくありません。");
    }

    @Test
    void changePasswordReturnsFormWhenNewPasswordIsSameAsCurrentPassword() {

        AdminUserDetails loginAdmin =
                mock(AdminUserDetails.class);

        when(loginAdmin.getId())
                .thenReturn(10L);

        PasswordChangeForm form =
                createPasswordChangeForm(
                        "same-password",
                        "same-password",
                        "same-password");

        BindingResult bindingResult =
                mock(BindingResult.class);

        when(bindingResult.hasErrors())
                .thenReturn(false);

        RedirectAttributes redirectAttributes =
                mock(RedirectAttributes.class);

        doThrow(new SameAsCurrentPasswordException())
                .when(adminAccountService)
                .changePassword(
                        10L,
                        "same-password",
                        "same-password");

        String viewName =
                controller.changePassword(
                        form,
                        bindingResult,
                        loginAdmin,
                        redirectAttributes);

        assertEquals(
                "admin/account/password-form",
                viewName);

        verify(bindingResult)
                .rejectValue(
                        "newPassword",
                        "sameAsCurrent",
                        "新しいパスワードには現在のパスワードと異なるパスワードを入力してください。");
    }

    @Test
    void changePasswordChangesLoggedInAdminsPassword() {

        AdminUserDetails loginAdmin =
                mock(AdminUserDetails.class);

        when(loginAdmin.getId())
                .thenReturn(10L);

        PasswordChangeForm form =
                createPasswordChangeForm(
                        "current-password",
                        "new-password",
                        "new-password");

        BindingResult bindingResult =
                mock(BindingResult.class);

        when(bindingResult.hasErrors())
                .thenReturn(false);

        RedirectAttributes redirectAttributes =
                mock(RedirectAttributes.class);

        String viewName =
                controller.changePassword(
                        form,
                        bindingResult,
                        loginAdmin,
                        redirectAttributes);

        assertEquals(
                "redirect:/admin/account",
                viewName);

        verify(adminAccountService)
                .changePassword(
                        10L,
                        "current-password",
                        "new-password");

        verify(redirectAttributes)
                .addFlashAttribute(
                        "successMessage",
                        "パスワードを変更しました。");
    }

    private PasswordChangeForm createPasswordChangeForm(
            String currentPassword,
            String newPassword,
            String confirmPassword) {

        PasswordChangeForm form =
                new PasswordChangeForm();

        form.setCurrentPassword(currentPassword);
        form.setNewPassword(newPassword);
        form.setConfirmPassword(confirmPassword);

        return form;
    }
}
