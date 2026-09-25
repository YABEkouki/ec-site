package com.example.ecsite.controller;

import org.springframework.mail.MailException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.example.ecsite.entity.User;
import com.example.ecsite.form.PasswordForgotForm;
import com.example.ecsite.form.PasswordResetForm;
import com.example.ecsite.service.MailService;
import com.example.ecsite.service.PasswordResetResult;
import com.example.ecsite.service.PasswordResetService;
import com.example.ecsite.service.UserService;

import jakarta.validation.Valid;

@Controller
public class PasswordResetController {

    private final PasswordResetService passwordResetService;
    private final UserService userService;
    private final MailService mailService;

    public PasswordResetController(
            PasswordResetService passwordResetService,
            UserService userService,
            MailService mailService) {

        this.passwordResetService = passwordResetService;
        this.userService = userService;
        this.mailService = mailService;
    }

    @GetMapping("/password/forgot")
    public String showForgotForm(Model model) {

        model.addAttribute(
                "passwordForgotForm",
                new PasswordForgotForm());

        return "password/forgot";
    }

    @PostMapping("/password/forgot")
    public String requestPasswordReset(
            @Valid @ModelAttribute("passwordForgotForm")
            PasswordForgotForm passwordForgotForm,
            BindingResult bindingResult,
            Model model) {

        if (bindingResult.hasErrors()) {
            return "password/forgot";
        }

        String email = passwordForgotForm.getEmail();
        String rawToken = passwordResetService.issueToken(email);

        if (rawToken != null) {

            User user = userService.findByEmail(email);

            if (user != null) {
                try {
                    mailService.sendPasswordReset(
                            user.getEmail(),
                            rawToken);

                } catch (MailException e) {
                    passwordResetService.invalidateToken(rawToken);
                }
            }
        }

        model.addAttribute(
                "message",
                "ご入力いただいたメールアドレスが登録されている場合、"
                        + "パスワード再設定用のメールを送信します。");

        return "password/forgot-complete";
    }

    @GetMapping("/password/reset")
    public String showResetForm(
            @RequestParam("token") String token,
            Model model) {

        PasswordResetResult result =
                passwordResetService.validateToken(token);

        if (result != PasswordResetResult.VALID) {
            model.addAttribute(
                    "errorMessage",
                    "このパスワード再設定リンクは無効または期限切れです。"
                            + "もう一度パスワード再設定を行ってください。");

            return "password/reset-invalid";
        }

        model.addAttribute(
                "passwordResetForm",
                new PasswordResetForm());

        model.addAttribute("token", token);

        return "password/reset";
    }

    @PostMapping("/password/reset")
    public String resetPassword(
            @RequestParam("token") String token,
            @Valid @ModelAttribute("passwordResetForm")
            PasswordResetForm passwordResetForm,
            BindingResult bindingResult,
            Model model) {

        PasswordResetResult validationResult =
                passwordResetService.validateToken(token);

        if (validationResult != PasswordResetResult.VALID) {
            model.addAttribute(
                    "errorMessage",
                    "このパスワード再設定リンクは無効または期限切れです。"
                            + "もう一度パスワード再設定を行ってください。");

            return "password/reset-invalid";
        }

        if (bindingResult.hasErrors()) {
            model.addAttribute("token", token);
            return "password/reset";
        }

        if (!passwordResetForm.getNewPassword()
                .equals(passwordResetForm.getConfirmPassword())) {

            bindingResult.rejectValue(
                    "confirmPassword",
                    "mismatch",
                    "新しいパスワードと確認用パスワードが一致しません。");

            model.addAttribute("token", token);

            return "password/reset";
        }

        PasswordResetResult result =
                passwordResetService.resetPassword(
                        token,
                        passwordResetForm.getNewPassword());

        if (result != PasswordResetResult.VALID) {
            model.addAttribute(
                    "errorMessage",
                    "このパスワード再設定リンクは無効または期限切れです。"
                            + "もう一度パスワード再設定を行ってください。");

            return "password/reset-invalid";
        }

        return "password/reset-complete";
    }
}
