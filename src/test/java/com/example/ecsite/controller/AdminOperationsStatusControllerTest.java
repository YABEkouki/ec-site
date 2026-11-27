package com.example.ecsite.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.ecsite.config.SecurityConfig;
import com.example.ecsite.security.AdminAuthenticationSuccessHandler;
import com.example.ecsite.security.CustomerAuthenticationSuccessHandler;
import com.example.ecsite.service.AdminUserDetailsService;
import com.example.ecsite.service.CustomUserDetailsService;
import com.example.ecsite.service.operations.OperationsStatusService;

/** Feature145-B: the read-only operations status screen. */
@WebMvcTest(AdminOperationsStatusController.class)
@Import(SecurityConfig.class)
class AdminOperationsStatusControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private OperationsStatusService service;
    @MockitoBean private AdminUserDetailsService adminUserDetailsService;
    @MockitoBean private CustomUserDetailsService customUserDetailsService;
    @MockitoBean private AdminAuthenticationSuccessHandler adminAuthenticationSuccessHandler;
    @MockitoBean private CustomerAuthenticationSuccessHandler customerAuthenticationSuccessHandler;

    static final LocalDateTime AT = LocalDateTime.of(2026, 11, 27, 9, 30, 5);

    static OperationsStatusService.Status fixture(OperationsStatusService.RuntimeStatus runtime, List<String> warnings) {
        return new OperationsStatusService.Status(
                List.of(new OperationsStatusService.Process(2, "お客様への注文メール", "CUSTOMER_NOTIFICATION_ENABLED", true, true, "メール",
                                List.of(new OperationsStatusService.Setting("間隔", "30s")), "最後の送信", AT, List.of(new OperationsStatusService.Count("送信待ち・送信中", 3)), "/admin/customer-notifications"),
                        new OperationsStatusService.Process(5, "終わらなかった注文変更の自動終了", "ORDER_CHANGE_CLOSURE_ENABLED", false, true, "PAY.JP POST（取消）",
                                List.of(new OperationsStatusService.Setting("自動処理の期限からの待ち", "60m")), "最後の自動終了の受付", null, List.of(), null)),
                List.of(new OperationsStatusService.Process(0, "決済照合の監査", "app.payment.discrepancy-audit.enabled", true, true, "PAY.JP GET",
                        List.of(), "最後の実行の開始", AT, List.of(new OperationsStatusService.Count("直近24時間の失敗・一部失敗", 0)), "/admin/payment-discrepancy-audits")),
                runtime, new OperationsStatusService.Connection("TEST", "https://api.pay.jp", false), warnings, AT);
    }

    @Test void showsProcessesRuntimeAndWarnings() throws Exception {
        when(service.read()).thenReturn(fixture(new OperationsStatusService.RuntimeStatus(true, "RUNNING", true, 19L, "417c973d-a2f3-4fee-a866-58acac69efef", 1, 0, 0),
                List.of("<b>PAY.JP</b> の売上確定の接続が LIVE ではありません（TEST）。")));
        mvc.perform(get("/admin/operations").with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("運用の状態")))
                .andExpect(content().string(containsString("2026/11/27 09:30:05 時点")))
                .andExpect(content().string(containsString("&lt;b&gt;PAY.JP&lt;/b&gt; の売上確定の接続")))
                .andExpect(content().string(not(containsString("<b>PAY.JP</b>"))))
                .andExpect(content().string(containsString("CUSTOMER_NOTIFICATION_ENABLED")))
                .andExpect(content().string(containsString("有効")))
                .andExpect(content().string(containsString("無効")))
                .andExpect(content().string(containsString("間隔：30s")))
                .andExpect(content().string(containsString("送信待ち・送信中：3件")))
                .andExpect(content().string(containsString("記録なし")))
                .andExpect(content().string(containsString("/admin/customer-notifications")))
                .andExpect(content().string(containsString("使えます")))
                .andExpect(content().string(containsString("世代 417c973d）")))
                .andExpect(content().string(not(containsString("a2f3-4fee"))))
                .andExpect(content().string(containsString("処理中の一括編集 1 件")))
                .andExpect(content().string(containsString("href=\"/admin/operations\"")));
    }

    @Test void showsAnUnreadableRuntimeWithoutWarnings() throws Exception {
        when(service.read()).thenReturn(fixture(new OperationsStatusService.RuntimeStatus(false, null, false, null, null, 0, 0, 0), List.of()));
        mvc.perform(get("/admin/operations").with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("読み取れません")))
                .andExpect(content().string(not(containsString("id=\"operations-warnings\""))));
    }

    @Test void isReadOnlyAndForAdministratorsOnly() throws Exception {
        mvc.perform(post("/admin/operations").with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().is4xxClientError());
        mvc.perform(get("/admin/operations").with(user("customer").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/operations")).andExpect(status().is3xxRedirection());
    }
}
