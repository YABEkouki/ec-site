package com.example.ecsite.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.exception.IncorrectCurrentPasswordException;
import com.example.ecsite.exception.SameAsCurrentPasswordException;
import com.example.ecsite.form.PasswordChangeForm;
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.AdminAccountService;

import jakarta.validation.Valid;

@Controller
public class AdminMyAccountController {

    private final AdminAccountService adminAccountService;

    public AdminMyAccountController(
            AdminAccountService adminAccountService) {

        this.adminAccountService = adminAccountService;
    }

    @GetMapping("/admin/account")
    public String index(
            @AuthenticationPrincipal AdminUserDetails loginAdmin,
            Model model) {

        model.addAttribute(
                "account",
                adminAccountService.getAccountInfo(loginAdmin.getId()));

        return "admin/account/index";
    }

    @GetMapping("/admin/account/password/edit")
    public String editPassword(Model model) {

        model.addAttribute(
                "passwordChangeForm",
                new PasswordChangeForm());

        return "admin/account/password-form";
    }

    @PostMapping("/admin/account/password")
    public String changePassword(
            @Valid @ModelAttribute("passwordChangeForm") PasswordChangeForm passwordChangeForm,
            BindingResult bindingResult,
            @AuthenticationPrincipal AdminUserDetails loginAdmin,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            return "admin/account/password-form";
        }

        if (!passwordChangeForm.getNewPassword()
                .equals(passwordChangeForm.getConfirmPassword())) {

            bindingResult.rejectValue(
                    "confirmPassword",
                    "mismatch",
                    "新しいパスワードと確認用パスワードが一致しません。");

            return "admin/account/password-form";
        }

        try {

            adminAccountService.changePassword(
                    loginAdmin.getId(),
                    passwordChangeForm.getCurrentPassword(),
                    passwordChangeForm.getNewPassword());

        } catch (IncorrectCurrentPasswordException e) {

            bindingResult.rejectValue(
                    "currentPassword",
                    "incorrect",
                    e.getMessage());

            return "admin/account/password-form";

        } catch (SameAsCurrentPasswordException e) {

            bindingResult.rejectValue(
                    "newPassword",
                    "sameAsCurrent",
                    e.getMessage());

            return "admin/account/password-form";
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "パスワードを変更しました。");

        return "redirect:/admin/account";
    }
}
