package com.example.ecsite.security;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.example.ecsite.service.AdminAccountService;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class AdminAuthenticationSuccessHandler
        extends SavedRequestAwareAuthenticationSuccessHandler {

    private final AdminAccountService adminAccountService;

    public AdminAuthenticationSuccessHandler(AdminAccountService adminAccountService) {
        this.adminAccountService = adminAccountService;

        setDefaultTargetUrl("/admin");
        setAlwaysUseDefaultTargetUrl(true);
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication)
            throws IOException, ServletException {

        AdminUserDetails adminUserDetails =
                (AdminUserDetails) authentication.getPrincipal();

        adminAccountService.recordSuccessfulLogin(adminUserDetails.getId());

        super.onAuthenticationSuccess(request, response, authentication);
    }
}
