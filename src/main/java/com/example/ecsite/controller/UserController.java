package com.example.ecsite.controller;

import org.springframework.mail.MailException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.dto.UserRegistrationResult;
import com.example.ecsite.exception.EmailAlreadyExistsException;
import com.example.ecsite.exception.UsernameAlreadyExistsException;
import com.example.ecsite.form.UserForm;
import com.example.ecsite.service.EmailVerificationService;
import com.example.ecsite.service.MailService;
import com.example.ecsite.service.UserService;

import jakarta.validation.Valid;

@Controller
public class UserController {

    private final UserService userService;
    private final EmailVerificationService emailVerificationService;
    private final MailService mailService;

    public UserController(
            UserService userService,
            EmailVerificationService emailVerificationService,
            MailService mailService) {

        this.userService = userService;
        this.emailVerificationService = emailVerificationService;
        this.mailService = mailService;
    }

    @GetMapping("/signup")
    public String showSignupForm(Model model) {

        model.addAttribute("userForm", new UserForm());

        return "users/signup";
    }

    @PostMapping("/signup")
    public String signup(
            @Valid @ModelAttribute("userForm") UserForm userForm,
            BindingResult bindingResult,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            return "users/signup";
        }

        if (!userService.passwordsMatch(userForm)) {
            bindingResult.rejectValue(
                    "confirmPassword",
                    "password.mismatch",
                    "パスワードが一致しません。");

            return "users/signup";
        }

        if (userService.usernameExists(userForm.getUsername())) {
            bindingResult.rejectValue(
                    "username",
                    "username.duplicate",
                    "このユーザー名は既に使用されています。");
        }

        if (userService.emailExists(userForm.getEmail())) {
            bindingResult.rejectValue(
                    "email",
                    "email.duplicate",
                    "このメールアドレスは既に使用されています。");
        }

        if (bindingResult.hasErrors()) {
            return "users/signup";
        }

        UserRegistrationResult registrationResult;

        try {
            registrationResult = userService.register(userForm);

        } catch (UsernameAlreadyExistsException e) {

            bindingResult.rejectValue(
                    "username",
                    "username.duplicate",
                    "このユーザー名は既に使用されています。");

            return "users/signup";

        } catch (EmailAlreadyExistsException e) {

            bindingResult.rejectValue(
                    "email",
                    "email.duplicate",
                    "このメールアドレスは既に使用されています。");

            return "users/signup";
        }

        try {
            String rawToken = emailVerificationService.issueToken(
                    registrationResult.userId());

            mailService.sendEmailVerification(
                    registrationResult.email(),
                    rawToken);

        } catch (MailException e) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "会員登録は完了しましたが、確認メールの送信に失敗しました。ログイン後、マイページから再送してください。");
        }

        return "redirect:/login?registered";
    }

}
