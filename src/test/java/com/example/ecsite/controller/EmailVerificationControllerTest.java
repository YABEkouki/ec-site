package com.example.ecsite.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.example.ecsite.service.EmailVerificationResult;
import com.example.ecsite.service.EmailVerificationService;

class EmailVerificationControllerTest {

    private final EmailVerificationService emailVerificationService =
            org.mockito.Mockito.mock(EmailVerificationService.class);

    private final MockMvc mockMvc =
            MockMvcBuilders.standaloneSetup(
                    new EmailVerificationController(
                            emailVerificationService))
                    .build();

    @Test
    void verifyReturnsVerifiedResult() throws Exception {

        when(emailVerificationService.verify("valid-token"))
                .thenReturn(EmailVerificationResult.VERIFIED);

        mockMvc.perform(
                get("/email/verify")
                        .param("token", "valid-token"))
                .andExpect(status().isOk())
                .andExpect(view().name("email/verify-result"))
                .andExpect(model().attribute(
                        "result",
                        is(EmailVerificationResult.VERIFIED)));

        verify(emailVerificationService)
                .verify("valid-token");
    }

    @Test
    void verifyReturnsExpiredResult() throws Exception {

        when(emailVerificationService.verify("expired-token"))
                .thenReturn(EmailVerificationResult.EXPIRED);

        mockMvc.perform(
                get("/email/verify")
                        .param("token", "expired-token"))
                .andExpect(status().isOk())
                .andExpect(view().name("email/verify-result"))
                .andExpect(model().attribute(
                        "result",
                        is(EmailVerificationResult.EXPIRED)));

        verify(emailVerificationService)
                .verify("expired-token");
    }

    @Test
    void verifyReturnsInvalidResultWhenTokenIsMissing()
            throws Exception {

        when(emailVerificationService.verify(null))
                .thenReturn(EmailVerificationResult.INVALID);

        mockMvc.perform(get("/email/verify"))
                .andExpect(status().isOk())
                .andExpect(view().name("email/verify-result"))
                .andExpect(model().attribute(
                        "result",
                        is(EmailVerificationResult.INVALID)));

        verify(emailVerificationService)
                .verify(null);
    }
}
