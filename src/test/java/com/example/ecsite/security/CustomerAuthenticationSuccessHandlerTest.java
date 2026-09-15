package com.example.ecsite.security;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import com.example.ecsite.service.UserService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class CustomerAuthenticationSuccessHandlerTest {

    @Test
    void recordsSuccessfulLoginForAuthenticatedUser() throws Exception {

        UserService userService = mock(UserService.class);

        CustomerAuthenticationSuccessHandler successHandler =
                new CustomerAuthenticationSuccessHandler(userService);

        HttpServletRequest request =
                mock(HttpServletRequest.class);

        HttpServletResponse response =
                mock(HttpServletResponse.class);

        Authentication authentication =
                mock(Authentication.class);

        CustomUserDetails userDetails =
                mock(CustomUserDetails.class);

        when(authentication.getPrincipal())
                .thenReturn(userDetails);

        when(userDetails.getId())
                .thenReturn(10L);

        successHandler.onAuthenticationSuccess(
                request,
                response,
                authentication);

        verify(userService)
                .recordSuccessfulLogin(10L);
    }

}
