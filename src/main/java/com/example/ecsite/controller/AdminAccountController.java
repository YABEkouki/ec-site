package com.example.ecsite.controller;

import java.util.Objects;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.entity.AdminAccount;
import com.example.ecsite.form.AdminAccountCreateForm;
import com.example.ecsite.form.AdminAccountEditForm;
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.AdminAccountService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@Controller
public class AdminAccountController {

    private final AdminAccountService adminAccountService;

    public AdminAccountController(AdminAccountService adminAccountService) {
        this.adminAccountService = adminAccountService;
    }

    @GetMapping("/admin/accounts")
    public String list(
            @AuthenticationPrincipal AdminUserDetails loginUser,
            Model model) {

        model.addAttribute("adminAccounts", adminAccountService.findAll());
        model.addAttribute("loginAdminId", loginUser.getId());

        return "admin/accounts/list";
    }

    @GetMapping("/admin/accounts/new")
    public String newForm(Model model) {
        model.addAttribute("adminAccountCreateForm", new AdminAccountCreateForm());
        return "admin/accounts/new";
    }

    @PostMapping("/admin/accounts")
    public String create(
            @Valid @ModelAttribute AdminAccountCreateForm adminAccountCreateForm,
            BindingResult bindingResult,
            RedirectAttributes redirectAttributes) {

        if (!Objects.equals(
                adminAccountCreateForm.getPassword(),
                adminAccountCreateForm.getConfirmPassword())) {
            bindingResult.rejectValue(
                    "confirmPassword",
                    "passwordMismatch",
                    "パスワードが一致しません。");
        }

        if (bindingResult.hasErrors()) {
            return "admin/accounts/new";
        }

        try {
            adminAccountService.create(adminAccountCreateForm);
        } catch (IllegalArgumentException e) {
            bindingResult.rejectValue(
                    "username",
                    "duplicateUsername",
                    e.getMessage());
            return "admin/accounts/new";
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "管理者アカウントを登録しました。");

        return "redirect:/admin/accounts";
    }

    @GetMapping("/admin/accounts/{id}/edit")
    public String editForm(
            @PathVariable Long id,
            @AuthenticationPrincipal AdminUserDetails loginUser,
            Model model) {

        AdminAccount adminAccount = adminAccountService.findById(id);

        AdminAccountEditForm form = new AdminAccountEditForm();
        form.setUsername(adminAccount.getUsername());
        form.setEnabled(adminAccount.isEnabled());

        model.addAttribute("adminAccountEditForm", form);
        model.addAttribute("adminAccountId", id);
        model.addAttribute("loginAdminId", loginUser.getId());

        return "admin/accounts/edit";
    }

    @PostMapping("/admin/accounts/{id}/edit")
    public String update(
            @PathVariable Long id,
            @AuthenticationPrincipal AdminUserDetails loginUser,
            @Valid @ModelAttribute AdminAccountEditForm adminAccountEditForm,
            BindingResult bindingResult,
            HttpServletRequest request,
            Model model,
            RedirectAttributes redirectAttributes) {

        String password = adminAccountEditForm.getPassword();
        String confirmPassword = adminAccountEditForm.getConfirmPassword();

        boolean passwordEntered = password != null && !password.isBlank();

        boolean confirmPasswordEntered = confirmPassword != null && !confirmPassword.isBlank();

        if (password != null && !password.isBlank() && password.length() < 8) {
            bindingResult.rejectValue(
                    "password",
                    "passwordTooShort",
                    "パスワードは8文字以上で入力してください。");
        }

        if (passwordEntered || confirmPasswordEntered) {
            if (password == null
                    || confirmPassword == null
                    || password.isBlank()
                    || confirmPassword.isBlank()
                    || !password.equals(confirmPassword)) {

                bindingResult.rejectValue(
                        "confirmPassword",
                        "passwordMismatch",
                        "パスワードが一致しません。");
            }
        }

        if (bindingResult.hasErrors()) {
            model.addAttribute("adminAccountId", id);
            model.addAttribute("loginAdminId", loginUser.getId());
            return "admin/accounts/edit";
        }

        AdminAccount before = adminAccountService.findById(id);

        boolean selfEdit = id.equals(loginUser.getId());

        boolean usernameChanged = id.equals(loginUser.getId())
                && !before.getUsername()
                        .equals(adminAccountEditForm.getUsername().trim());

        if (selfEdit && !adminAccountEditForm.isEnabled()) {
            adminAccountEditForm.setEnabled(true);
        }

        try {
            adminAccountService.update(
                    id,
                    adminAccountEditForm,
                    loginUser.getId());
        } catch (IllegalArgumentException e) {

            if (e.getMessage().contains("ユーザー名")) {
                bindingResult.rejectValue(
                        "username",
                        "duplicateUsername",
                        e.getMessage());
            } else {
                bindingResult.reject(
                        "adminAccountUpdateError",
                        e.getMessage());
            }

            model.addAttribute("adminAccountId", id);
            model.addAttribute("loginAdminId", loginUser.getId());

            return "admin/accounts/edit";
        }

        if (usernameChanged) {
            request.getSession().invalidate();

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "ユーザー名を変更しました。新しいユーザー名で再度ログインしてください。");

            return "redirect:/admin/login";
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "管理者アカウントを更新しました。");

        return "redirect:/admin/accounts";
    }
}
