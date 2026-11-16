package com.example.ecsite.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.ecsite.config.SecurityConfig;
import com.example.ecsite.security.AdminAuthenticationSuccessHandler;
import com.example.ecsite.security.CustomerAuthenticationSuccessHandler;
import com.example.ecsite.service.AdminUserDetailsService;
import com.example.ecsite.service.CustomUserDetailsService;
import com.example.ecsite.service.orderchange.AdminOrderHoldViewService;
import com.example.ecsite.service.orderchange.AdminOrderHoldViewService.Category;
import com.example.ecsite.service.orderchange.AdminOrderHoldViewService.Item;
import com.example.ecsite.service.orderchange.AdminOrderHoldViewService.Kind;

@WebMvcTest(AdminOrderHoldController.class)
@Import(SecurityConfig.class)
class AdminOrderHoldControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminOrderHoldViewService holds;

    @MockitoBean
    private AdminUserDetailsService adminUserDetailsService;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @MockitoBean
    private AdminAuthenticationSuccessHandler adminAuthenticationSuccessHandler;

    @MockitoBean
    private CustomerAuthenticationSuccessHandler customerAuthenticationSuccessHandler;

    private static Item item(long order, Category category, String state, boolean stale) {
        return new Item(order, "ORDERED", category.name().startsWith("EDIT") ? Kind.ADMISSION : Kind.OPERATION,
                UUID.nameUUIDFromBytes(("hold-" + order).getBytes()), category, state, "<i>reason</i>",
                category == Category.NOT_ADOPTED_CANCELLING ? "NOT_ADOPTED" : null,
                category == Category.NOT_ADOPTED_CANCELLING ? "HOLD" : null,
                category == Category.NOT_ADOPTED_CANCELLING ? "AUTHORIZATION_INCOMPLETE" : null,
                null, null, "2026/11/16 14:05:00", stale ? 31 : 2, stale);
    }

    @Test
    void listRendersHoldsAndSummaryForAdministrator() throws Exception {
        var items = List.of(item(7L, Category.CHANGE_MANUAL_REVIEW, "MANUAL_REVIEW", false),
                item(8L, Category.NOT_ADOPTED_CANCELLING, "AUTHORIZED", true));
        when(holds.list()).thenReturn(items);
        when(holds.staleAfterMinutes()).thenReturn(30L);

        mockMvc.perform(get("/admin/order-holds").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/order-holds/list"))
                .andExpect(model().attribute("holdItems", items))
                .andExpect(model().attribute("holdSummary", new AdminOrderHoldViewService.Summary(2, 1, 1)))
                .andExpect(content().string(containsString("注文変更：手動確認待ち")))
                .andExpect(content().string(containsString("不成立：不要な新与信の取消待ち")))
                .andExpect(content().string(containsString("NOT_ADOPTED / HOLD")))
                .andExpect(content().string(containsString("href=\"/admin/orders/7\"")))
                .andExpect(content().string(containsString("31分")))
                .andExpect(content().string(containsString("&lt;i&gt;reason&lt;/i&gt;")))
                .andExpect(content().string(not(containsString("<i>reason</i>"))))
                .andExpect(content().string(not(containsString("<form action=\"/admin/order-holds"))));
    }

    @Test
    void emptyListSaysNothingIsHeld() throws Exception {
        when(holds.list()).thenReturn(List.of());
        mockMvc.perform(get("/admin/order-holds").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("出荷が保留されている注文はありません")));
    }

    @Test
    void databaseFailureIsReportedWithoutClaimingNothingIsHeld() throws Exception {
        when(holds.list()).thenThrow(new DataAccessResourceFailureException("down"));
        mockMvc.perform(get("/admin/order-holds").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("保留注文の情報を取得できませんでした")))
                .andExpect(content().string(not(containsString("出荷が保留されている注文はありません"))));
    }

    @Test
    void listRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/admin/order-holds"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));
        verifyNoInteractions(holds);
    }

    @Test
    void listRejectsNonAdmin() throws Exception {
        mockMvc.perform(get("/admin/order-holds").with(user("customer").roles("USER")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(holds);
    }

    @Test
    void listIsReadOnly() throws Exception {
        mockMvc.perform(post("/admin/order-holds").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(holds);
    }
}
