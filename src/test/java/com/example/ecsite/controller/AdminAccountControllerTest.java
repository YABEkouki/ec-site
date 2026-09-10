package com.example.ecsite.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.entity.AdminAccount;
import com.example.ecsite.form.AdminAccountCreateForm;
import com.example.ecsite.form.AdminAccountEditForm;
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.AdminAccountService;

@ExtendWith(MockitoExtension.class)
class AdminAccountControllerTest {

    @Mock
    private AdminAccountService adminAccountService;

    @Mock
    private Model model;

    @Mock
    private BindingResult bindingResult;

    @Mock
    private RedirectAttributes redirectAttributes;

    @Mock
    private AdminUserDetails loginUser;

    private AdminAccountController adminAccountController;

    @BeforeEach
    void setUp() {
        adminAccountController = new AdminAccountController(adminAccountService);
    }

    @Test
    void listDisplaysAdminAccounts() {

        AdminAccount account = createAccount(
                "admin1",
                true);

        when(adminAccountService.findAll())
                .thenReturn(List.of(account));

        when(loginUser.getId())
                .thenReturn(1L);

        String viewName = adminAccountController.list(
                loginUser,
                model);

        assertEquals(
                "admin/accounts/list",
                viewName);

        verify(model).addAttribute(
                "adminAccounts",
                List.of(account));

        verify(model).addAttribute(
                "loginAdminId",
                1L);
    }

    @Test
    void newFormDisplaysCreateForm() {

        String viewName = adminAccountController.newForm(model);

        assertEquals(
                "admin/accounts/new",
                viewName);

        verify(model).addAttribute(
                eq("adminAccountCreateForm"),
                any(AdminAccountCreateForm.class));
    }

    @Test
    void createRegistersAccountAndRedirects() {

        AdminAccountCreateForm form = createForm(
                "admin1",
                "password123",
                "password123");

        when(bindingResult.hasErrors())
                .thenReturn(false);

        String viewName = adminAccountController.create(
                form,
                bindingResult,
                redirectAttributes);

        assertEquals(
                "redirect:/admin/accounts",
                viewName);

        verify(adminAccountService)
                .create(form);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "successMessage",
                        "管理者アカウントを登録しました。");
    }

    @Test
    void createReturnsFormWhenPasswordDoesNotMatch() {

        AdminAccountCreateForm form = createForm(
                "admin1",
                "password123",
                "different123");

        when(bindingResult.hasErrors())
                .thenReturn(true);

        String viewName = adminAccountController.create(
                form,
                bindingResult,
                redirectAttributes);

        assertEquals(
                "admin/accounts/new",
                viewName);

        verify(bindingResult)
                .rejectValue(
                        "confirmPassword",
                        "passwordMismatch",
                        "パスワードが一致しません。");

        verify(adminAccountService, never())
                .create(form);
    }

    @Test
    void createReturnsFormWhenServiceRejectsUsername() {

        AdminAccountCreateForm form = createForm(
                "admin1",
                "password123",
                "password123");

        when(bindingResult.hasErrors())
                .thenReturn(false);

        org.mockito.Mockito
                .doThrow(
                        new IllegalArgumentException(
                                "このユーザー名は既に使用されています。"))
                .when(adminAccountService)
                .create(form);

        String viewName = adminAccountController.create(
                form,
                bindingResult,
                redirectAttributes);

        assertEquals(
                "admin/accounts/new",
                viewName);

        verify(bindingResult)
                .rejectValue(
                        "username",
                        "duplicateUsername",
                        "このユーザー名は既に使用されています。");
    }

    @Test
    void editFormDisplaysExistingAccount() {

        AdminAccount account = createAccount(
                "admin1",
                true);

        when(adminAccountService.findById(2L))
                .thenReturn(account);

        when(loginUser.getId())
                .thenReturn(1L);

        String viewName = adminAccountController.editForm(
                2L,
                loginUser,
                model);

        assertEquals(
                "admin/accounts/edit",
                viewName);

        verify(model)
                .addAttribute(
                        "adminAccountId",
                        2L);

        verify(model)
                .addAttribute(
                        "loginAdminId",
                        1L);

        verify(model)
                .addAttribute(
                        eq("adminAccountEditForm"),
                        any(AdminAccountEditForm.class));
    }

    @Test
    void updateChangesAccountAndRedirects() {

        AdminAccount account = createAccount(
                "admin1",
                true);

        when(loginUser.getId())
                .thenReturn(1L);

        when(adminAccountService.findById(2L))
                .thenReturn(account);

        when(bindingResult.hasErrors())
                .thenReturn(false);

        AdminAccountEditForm form = editForm(
                "admin2",
                "",
                "",
                true);

        MockHttpServletRequest request = new MockHttpServletRequest();

        String viewName = adminAccountController.update(
                2L,
                loginUser,
                form,
                bindingResult,
                request,
                model,
                redirectAttributes);

        assertEquals(
                "redirect:/admin/accounts",
                viewName);

        verify(adminAccountService)
                .update(
                        2L,
                        form,
                        1L);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "successMessage",
                        "管理者アカウントを更新しました。");
    }

    @Test
    void updateRejectsSelfDisable() {

        AdminAccount account = createAccount(
                "admin1",
                true);

        when(loginUser.getId())
                .thenReturn(1L);

        when(adminAccountService.findById(1L))
                .thenReturn(account);

        when(bindingResult.hasErrors())
                .thenReturn(false);

        AdminAccountEditForm form = editForm(
                "admin1",
                "",
                "",
                false);

        org.mockito.Mockito
                .doThrow(
                        new IllegalArgumentException(
                                "ログイン中の管理者アカウントを無効化することはできません。"))
                .when(adminAccountService)
                .update(
                        1L,
                        form,
                        1L);

        MockHttpServletRequest request = new MockHttpServletRequest();

        String viewName = adminAccountController.update(
                1L,
                loginUser,
                form,
                bindingResult,
                request,
                model,
                redirectAttributes);

        assertEquals(
                "admin/accounts/edit",
                viewName);

        verify(bindingResult)
                .reject(
                        "adminAccountUpdateError",
                        "ログイン中の管理者アカウントを無効化することはできません。");
    }

    @Test
    void updateSelfUsernameChangeInvalidatesSession() {

        AdminAccount account = createAccount(
                "admin1",
                true);

        when(loginUser.getId())
                .thenReturn(1L);

        when(adminAccountService.findById(1L))
                .thenReturn(account);

        when(bindingResult.hasErrors())
                .thenReturn(false);

        AdminAccountEditForm form = editForm(
                "admin2",
                "",
                "",
                true);

        MockHttpServletRequest request = new MockHttpServletRequest();

        request.getSession();

        String viewName = adminAccountController.update(
                1L,
                loginUser,
                form,
                bindingResult,
                request,
                model,
                redirectAttributes);

        assertEquals(
                "redirect:/admin/login",
                viewName);

        verify(adminAccountService)
                .update(
                        1L,
                        form,
                        1L);
    }

    private AdminAccountCreateForm createForm(
            String username,
            String password,
            String confirmPassword) {

        AdminAccountCreateForm form = new AdminAccountCreateForm();

        form.setUsername(username);
        form.setPassword(password);
        form.setConfirmPassword(confirmPassword);

        return form;
    }

    private AdminAccountEditForm editForm(
            String username,
            String password,
            String confirmPassword,
            boolean enabled) {

        AdminAccountEditForm form = new AdminAccountEditForm();

        form.setUsername(username);
        form.setPassword(password);
        form.setConfirmPassword(confirmPassword);
        form.setEnabled(enabled);

        return form;
    }

    private AdminAccount createAccount(
            String username,
            boolean enabled) {

        AdminAccount account = new AdminAccount();

        account.setUsername(username);
        account.setPassword("encoded-password");
        account.setEnabled(enabled);

        return account;
    }

    @Test
    void updateSelfAccountForcesEnabledTrue()
            throws Exception {

        AdminUserDetails loginUser = new AdminUserDetails(
                1L,
                "admin",
                "password",
                true,
                List.of(
                        new SimpleGrantedAuthority(
                                "ROLE_ADMIN")));

        AdminAccount before = new AdminAccount();
        before.setUsername("admin");
        before.setEnabled(true);

        AdminAccountEditForm form = new AdminAccountEditForm();
        form.setUsername("admin");
        form.setEnabled(false);

        when(adminAccountService.findById(1L))
                .thenReturn(before);

        BindingResult bindingResult = mock(BindingResult.class);

        when(bindingResult.hasErrors())
                .thenReturn(false);

        MockHttpServletRequest request = new MockHttpServletRequest();

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        adminAccountController.update(
                1L,
                loginUser,
                form,
                bindingResult,
                request,
                mock(Model.class),
                redirectAttributes);

        verify(adminAccountService).update(
                eq(1L),
                argThat(updatedForm -> updatedForm.isEnabled()),
                eq(1L));
    }

}
