package com.example.ecsite.controller;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.example.ecsite.config.SecurityConfig;
import com.example.ecsite.payment.savedcard.SavedCardGatewayException;
import com.example.ecsite.security.AdminAuthenticationSuccessHandler;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.security.CustomerAuthenticationSuccessHandler;
import com.example.ecsite.service.AdminUserDetailsService;
import com.example.ecsite.service.CustomUserDetailsService;
import com.example.ecsite.service.savedcard.SavedCardOperationStore;
import com.example.ecsite.service.savedcard.SavedCardService;

/** Feature139-A: my page saved card screens, CSP and the Setup Flow return URL. */
@WebMvcTest(SavedCardController.class)
@Import(SecurityConfig.class)
class SavedCardControllerTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private SavedCardService cards;
    @MockitoBean private AdminUserDetailsService adminUserDetailsService;
    @MockitoBean private CustomUserDetailsService customUserDetailsService;
    @MockitoBean private AdminAuthenticationSuccessHandler adminAuthenticationSuccessHandler;
    @MockitoBean private CustomerAuthenticationSuccessHandler customerAuthenticationSuccessHandler;

    private static RequestPostProcessor customer() {
        return user(new CustomUserDetails(42L, "fixture", "unused", true, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @Test
    void listShowsCardsWithoutStoringAnything() throws Exception {
        when(cards.enabled()).thenReturn(true);
        when(cards.cards(42L)).thenReturn(new SavedCardService.CardsView(List.of(
                new SavedCardService.CardView("pm_new", "Visa", "4242", 3, 2030, true, false),
                new SavedCardService.CardView("pm_old", "JCB", "0000", 12, 2025, false, true)), false, false));
        mvc.perform(get("/mypage/cards").with(customer()))
                .andExpect(status().isOk()).andExpect(view().name("mypage/cards"))
                .andExpect(content().string(allOf(containsString("Visa **** 4242"), containsString("03/2030"),
                        containsString("（期限切れ）"), containsString("/mypage/cards/pm_old/default"),
                        not(containsString("/mypage/cards/pm_new/default")), containsString("/mypage/cards/new"))));
    }

    @Test
    void listExplainsWhenUnavailableOrUnreachable() throws Exception {
        mvc.perform(get("/mypage/cards").with(customer()))
                .andExpect(status().isOk()).andExpect(model().attribute("unavailableMessage", SavedCardController.UNAVAILABLE));
        verify(cards, never()).cards(anyLong());

        when(cards.enabled()).thenReturn(true);
        when(cards.cards(42L)).thenThrow(new SavedCardGatewayException(SavedCardGatewayException.Kind.UNKNOWN, null, "transport"));
        mvc.perform(get("/mypage/cards").with(customer()))
                .andExpect(status().isOk()).andExpect(model().attribute("unavailableMessage", SavedCardController.PROVIDER_FAILED));
    }

    @Test
    void anonymousUsersAreSentToLogin() throws Exception {
        mvc.perform(get("/mypage/cards")).andExpect(status().is3xxRedirection());
        verify(cards, never()).enabled();
    }

    @Test
    void registrationPageLoadsPaymentsJsUnderNonceCsp() throws Exception {
        when(cards.startRegistration(42L)).thenReturn(new SavedCardService.RegistrationStart("sfw_1", "sfw_1_secret_abc"));
        when(cards.publicKey()).thenReturn("pk_test_fixture");
        mvc.perform(post("/mypage/cards/new").with(customer()).with(csrf()))
                .andExpect(status().isOk()).andExpect(view().name("mypage/card-new"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Security-Policy", allOf(containsString("script-src 'nonce-"),
                        containsString("https://js.pay.jp"), containsString("frame-src https://js.pay.jp"))))
                .andExpect(content().string(allOf(containsString("https://js.pay.jp/payments.js"),
                        containsString("confirmSetup"), containsString("\\/mypage\\/cards\\/return"))));
    }

    @Test
    void registrationRequiresCsrfAndReportsLimitsAndBusyState() throws Exception {
        mvc.perform(post("/mypage/cards/new").with(customer())).andExpect(status().isForbidden());
        verify(cards, never()).startRegistration(anyLong());

        when(cards.startRegistration(42L)).thenThrow(new SavedCardService.LimitException());
        mvc.perform(post("/mypage/cards/new").with(customer()).with(csrf()))
                .andExpect(redirectedUrl("/mypage/cards")).andExpect(flash().attribute("errorMessage", SavedCardController.LIMIT));
    }

    @Test
    void returnUrlKeepsOnlyTheSetupFlowId() throws Exception {
        mvc.perform(get("/mypage/cards/return").queryParam("setup_flow_id", "sfw_1")
                        .queryParam("setup_flow_client_secret", "sfw_1_secret_abc").with(customer()))
                .andExpect(status().isOk()).andExpect(view().name("mypage/card-return"))
                .andExpect(model().attribute("setupFlowId", "sfw_1"))
                .andExpect(header().string("Content-Security-Policy", allOf(containsString("script-src 'nonce-"),
                        not(containsString("js.pay.jp")))))
                .andExpect(content().string(allOf(containsString("/mypage/cards/complete"), not(containsString("secret_abc")))));

        mvc.perform(get("/mypage/cards/return").queryParam("setup_flow_id", "sfw_1\"><script>").with(customer()))
                .andExpect(status().isOk()).andExpect(model().attribute("setupFlowId", org.hamcrest.Matchers.nullValue()))
                .andExpect(content().string(containsString("登録の手続きが見つかりません。")));
        verify(cards, never()).completeRegistration(anyLong(), anyString());
    }

    @Test
    void completionReportsEachResult() throws Exception {
        when(cards.completeRegistration(42L, "sfw_1")).thenReturn(SavedCardService.RegistrationResult.REGISTERED);
        when(cards.completeRegistration(42L, "sfw_2")).thenReturn(SavedCardService.RegistrationResult.DUPLICATE);
        when(cards.completeRegistration(42L, "sfw_3")).thenThrow(new SavedCardOperationStore.BusyException());
        mvc.perform(post("/mypage/cards/complete").param("setupFlowId", "sfw_1").with(customer()).with(csrf()))
                .andExpect(redirectedUrl("/mypage/cards")).andExpect(flash().attribute("successMessage", "カードを登録しました。"));
        mvc.perform(post("/mypage/cards/complete").param("setupFlowId", "sfw_2").with(customer()).with(csrf()))
                .andExpect(flash().attribute("errorMessage", "このカードはすでに登録されています。"));
        mvc.perform(post("/mypage/cards/complete").param("setupFlowId", "sfw_3").with(customer()).with(csrf()))
                .andExpect(flash().attribute("errorMessage", SavedCardController.BUSY));
    }

    @Test
    void deleteAndDefaultUseTheLoggedInUser() throws Exception {
        mvc.perform(post("/mypage/cards/pm_a/delete").with(customer()).with(csrf()))
                .andExpect(redirectedUrl("/mypage/cards")).andExpect(flash().attribute("successMessage", "カードを削除しました。"));
        verify(cards).delete(42L, "pm_a");

        doThrow(new SavedCardService.NotOwnedException()).when(cards).setDefault(eq(42L), eq("pm_b"));
        mvc.perform(post("/mypage/cards/pm_b/default").with(customer()).with(csrf()))
                .andExpect(flash().attribute("errorMessage", SavedCardController.NOT_FOUND));

        mvc.perform(post("/mypage/cards/pm_a/delete").with(customer())).andExpect(status().isForbidden());
    }
}
