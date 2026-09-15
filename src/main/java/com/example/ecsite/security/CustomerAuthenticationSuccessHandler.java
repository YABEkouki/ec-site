package com.example.ecsite.security;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.example.ecsite.service.UserService;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class CustomerAuthenticationSuccessHandler
        extends SavedRequestAwareAuthenticationSuccessHandler {

    private final UserService userService;

    public CustomerAuthenticationSuccessHandler(UserService userService) {
        this.userService = userService;

        setDefaultTargetUrl("/");
        setAlwaysUseDefaultTargetUrl(true);
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication)
            throws IOException, ServletException {

        CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();

        userService.recordSuccessfulLogin(userDetails.getId());

        super.onAuthenticationSuccess(request, response, authentication);
    }
}
