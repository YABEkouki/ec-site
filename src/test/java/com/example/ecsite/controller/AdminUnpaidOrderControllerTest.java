package com.example.ecsite.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.ecsite.config.SecurityConfig;
import com.example.ecsite.config.UnpaidOrderClosureProperties;
import com.example.ecsite.security.AdminAuthenticationSuccessHandler;
import com.example.ecsite.security.CustomerAuthenticationSuccessHandler;
import com.example.ecsite.service.AdminUserDetailsService;
import com.example.ecsite.service.CustomUserDetailsService;
import com.example.ecsite.service.unpaidclosure.AdminUnpaidOrderQueries;
import com.example.ecsite.service.unpaidclosure.AdminUnpaidOrderQueries.*;

/** Feature142-B: the unpaid order list. */
@WebMvcTest(AdminUnpaidOrderController.class)
@Import(SecurityConfig.class)
class AdminUnpaidOrderControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private AdminUnpaidOrderQueries queries;
    @MockitoBean private UnpaidOrderClosureProperties properties;
    @MockitoBean private AdminUserDetailsService adminUserDetailsService;
    @MockitoBean private CustomUserDetailsService customUserDetailsService;
    @MockitoBean private AdminAuthenticationSuccessHandler adminAuthenticationSuccessHandler;
    @MockitoBean private CustomerAuthenticationSuccessHandler customerAuthenticationSuccessHandler;

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 25, 9, 0);

    @Test void showsEveryKindOfRow() throws Exception {
        when(properties.after()).thenReturn(Duration.ofHours(24));
        when(queries.list(View.ALL, 0)).thenReturn(new Result(List.of(
                new Row(26, AT, 1030, "PENDING", true, null, null, null, null, 0, false),
                new Row(40, AT, 5980, "REQUIRES_ACTION", false, "SENDING", "SYSTEM", AT.plusDays(1), "UNCONFIRMED", 2, true),
                new Row(41, AT, 100, "PENDING", true, "REJECTED", "<b>closer</b>", AT.plusDays(1), "REJECTED", 1, true),
                new Row(42, AT, 100, null, false, null, null, null, null, 0, false)), 0, 60, true));
        mvc.perform(get("/admin/unpaid-orders").with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("自動終了：無効")))
                .andExpect(content().string(containsString("1,030円")))
                .andExpect(content().string(containsString("/admin/orders/26")))
                .andExpect(content().string(containsString("取消結果を確認中")))
                .andExpect(content().string(containsString("2回（UNCONFIRMED）")))
                .andExpect(content().string(containsString("PAY.JPが取消を拒否")))
                .andExpect(content().string(containsString("決済なし")))
                .andExpect(content().string(not(containsString("<b>closer</b>"))))
                .andExpect(content().string(containsString("view=ALL&amp;page=1")));
    }

    @Test void showsTheAutomaticWaitAndAnEmptyResult() throws Exception {
        when(properties.enabled()).thenReturn(true);
        when(properties.after()).thenReturn(Duration.ofMinutes(90));
        when(queries.list(View.STUCK, 2)).thenReturn(new Result(List.of(), 2, 0, false));
        mvc.perform(get("/admin/unpaid-orders?view=STUCK&page=2").with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("自動終了：有効（注文から90分たった注文）")))
                .andExpect(content().string(containsString("該当する注文はありません")));
    }

    @Test void refusesAnUnknownViewAndOtherUsers() throws Exception {
        mvc.perform(get("/admin/unpaid-orders?view=NOPE").with(user("admin").roles("ADMIN"))).andExpect(status().isBadRequest());
        mvc.perform(get("/admin/unpaid-orders").with(user("customer").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/unpaid-orders")).andExpect(status().is3xxRedirection());
    }
}
