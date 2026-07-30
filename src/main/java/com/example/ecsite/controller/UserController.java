package com.example.ecsite.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

import com.example.ecsite.form.UserForm;
import com.example.ecsite.service.UserService;

import jakarta.validation.Valid;

@Controller
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/signup")
    public String showSignupForm(Model model) {

        model.addAttribute("userForm", new UserForm());

        return "users/signup";
    }

    @PostMapping("/signup")
    public String signup(
            @Valid @ModelAttribute UserForm userForm,
            BindingResult bindingResult,
            Model model) {

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

        if (bindingResult.hasErrors()) {
            return "users/signup";
        }

        userService.register(userForm);

        return "redirect:/login";
    }

}
