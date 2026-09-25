package com.example.ecsite.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.example.ecsite.service.EmailVerificationResult;
import com.example.ecsite.service.EmailVerificationService;

@Controller
public class EmailVerificationController {

    private final EmailVerificationService emailVerificationService;

    public EmailVerificationController(
            EmailVerificationService emailVerificationService) {
        this.emailVerificationService = emailVerificationService;
    }

    @GetMapping("/email/verify")
    public String verify(
            @RequestParam(name = "token", required = false) String token,
            Model model) {

        EmailVerificationResult result =
                emailVerificationService.verify(token);

        model.addAttribute("result", result);

        return "email/verify-result";
    }
}
