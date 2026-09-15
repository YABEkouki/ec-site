package com.example.ecsite.security;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import com.example.ecsite.service.AdminAccountService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class AdminAuthenticationSuccessHandlerTest {

    @Test
    void recordsSuccessfulLoginForAuthenticatedAdmin() throws Exception {

        AdminAccountService adminAccountService =
                mock(AdminAccountService.class);

        AdminAuthenticationSuccessHandler successHandler =
                new AdminAuthenticationSuccessHandler(adminAccountService);

        HttpServletRequest request =
                mock(HttpServletRequest.class);

        HttpServletResponse response =
                mock(HttpServletResponse.class);

        Authentication authentication =
                mock(Authentication.class);

        AdminUserDetails adminUserDetails =
                mock(AdminUserDetails.class);

        when(authentication.getPrincipal())
                .thenReturn(adminUserDetails);

        when(adminUserDetails.getId())
                .thenReturn(10L);

        successHandler.onAuthenticationSuccess(
                request,
                response,
                authentication);

        verify(adminAccountService)
                .recordSuccessfulLogin(10L);
    }
}
