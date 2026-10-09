package com.example.ecsite.controller;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.example.ecsite.config.SecurityConfig;
import com.example.ecsite.dto.*;
import com.example.ecsite.entity.*;
import com.example.ecsite.form.AdminPaymentDiscrepancyAuditRunSearchForm;
import com.example.ecsite.security.*;
import com.example.ecsite.service.*;
import com.example.ecsite.service.payment.PaymentDiscrepancyAuditRunSummary;

@WebMvcTest(AdminPaymentDiscrepancyAuditRunController.class)
@Import(SecurityConfig.class)
class AdminPaymentDiscrepancyAuditRunControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean AdminPaymentDiscrepancyAuditRunService service;
    @MockitoBean AdminUserDetailsService adminUserDetailsService;
    @MockitoBean CustomUserDetailsService customUserDetailsService;
    @MockitoBean AdminAuthenticationSuccessHandler adminAuthenticationSuccessHandler;
    @MockitoBean CustomerAuthenticationSuccessHandler customerAuthenticationSuccessHandler;
    @BeforeEach void setUp() {
        when(service.search(any(),anyInt(),anyInt())).thenReturn(Page.empty(PageRequest.of(0,20)));
        when(service.monitoringSummary()).thenReturn(new AdminPaymentDiscrepancyAuditMonitoringSummary(null,null,0,0,true,LocalDateTime.of(2026,10,8,9,0),Duration.ofHours(24),List.of()));
    }
    @Test void adminSeesEmptyHistoryAndSharedNavigation() throws Exception {
        mvc.perform(get("/admin/payment-discrepancy-audits").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(view().name("admin/payment-discrepancy-audits/list"))
            .andExpect(content().string(containsString("定期監査の履歴はありません")))
            .andExpect(content().string(containsString("href=\"/admin/payment-discrepancy-audits\"")))
            .andExpect(content().string(containsString("href=\"/admin/payment-discrepancies\"")));
        verify(service).search(any(),eq(0),eq(20));
    }
    @Test void anonymousUsesAdminLogin() throws Exception {
        mvc.perform(get("/admin/payment-discrepancy-audits"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/login"));
        verifyNoInteractions(service);
    }
    @Test void customerIsForbidden() throws Exception {
        mvc.perform(get("/admin/payment-discrepancy-audits").with(user("customer").roles("USER")))
            .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @ParameterizedTest @ValueSource(strings={"status=UNKNOWN", "from=2026-02-30", "to=bad", "size=no", "page=no"})
    void invalidSearchReturnsBadRequest(String query) throws Exception {
        mvc.perform(get("/admin/payment-discrepancy-audits?"+query).with(user("admin").roles("ADMIN")))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void searchAndPaginationRetainConditions() throws Exception {
        var run = PaymentDiscrepancyAuditRun.start("node",Instant.parse("2026-10-08T00:00:00Z"));
        run.finish(Instant.parse("2026-10-08T00:00:01Z"),new PaymentDiscrepancyAuditRunSummary(
            PaymentDiscrepancyAuditRunStatus.FAILED,2,0,0,0,0,2,1000,PaymentDiscrepancyAuditRunErrorCode.ALL_ITEMS_FAILED));
        var item = AdminPaymentDiscrepancyAuditRunListItem.from(run);
        when(service.search(any(),eq(1),eq(20))).thenReturn(new PageImpl<>(List.of(item),PageRequest.of(1,20),41));
        mvc.perform(get("/admin/payment-discrepancy-audits").param("status","FAILED").param("from","2026-10-08")
            .param("to","2026-10-09").param("page","1").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(content().string(containsString("status=FAILED")))
            .andExpect(content().string(containsString("from=2026-10-08")))
            .andExpect(content().string(containsString("to=2026-10-09")))
            .andExpect(content().string(containsString("page=2")))
            .andExpect(content().string(containsString("2026-10-08 09:00:00")));
        var captor = ArgumentCaptor.forClass(AdminPaymentDiscrepancyAuditRunSearchForm.class);
        verify(service).search(captor.capture(),eq(1),eq(20));
        assertThat(captor.getValue().getStatus()).isEqualTo(PaymentDiscrepancyAuditRunStatus.FAILED);
        assertThat(captor.getValue().getFrom()).isEqualTo(LocalDate.of(2026,10,8));
    }
    @Test void runningCountsAreNotPresentedAsCompletedZero() throws Exception {
        var item = AdminPaymentDiscrepancyAuditRunListItem.from(PaymentDiscrepancyAuditRun.start("node",Instant.parse("2026-10-08T00:00:00Z")));
        when(service.monitoringSummary()).thenReturn(new AdminPaymentDiscrepancyAuditMonitoringSummary(item,null,3,1,true,LocalDateTime.of(2026,10,8,9,0),Duration.ofHours(24),List.of()));
        mvc.perform(get("/admin/payment-discrepancy-audits").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(content().string(containsString("未確定")))
            .andExpect(content().string(containsString("実行中")))
            .andExpect(content().string(containsString("全件SKIPPED")))
            .andExpect(content().string(not(containsString("監査を実行"))));
    }
    @Test void pageAndSizeAreClamped() throws Exception {
        mvc.perform(get("/admin/payment-discrepancy-audits").param("page","-1").param("size","101")
            .with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
        verify(service).search(any(),eq(0),eq(100));
    }
    @Test void noWarningsShowsMonitoringStatus() throws Exception {
        mvc.perform(get("/admin/payment-discrepancy-audits").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("現在、設定された監視条件に該当する警告はありません。")))
            .andExpect(content().string(containsString("定期監査Scheduler：有効")));
    }
    @Test void disabledSchedulerIsExplicitAndNoHistoryIsNotAnAlarm() throws Exception {
        when(service.monitoringSummary()).thenReturn(new AdminPaymentDiscrepancyAuditMonitoringSummary(
            null,null,0,0,false,LocalDateTime.of(2026,10,8,9,0),Duration.ofHours(24),List.of()));
        mvc.perform(get("/admin/payment-discrepancy-audits").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("定期監査Scheduler：無効")))
            .andExpect(content().string(containsString("履歴なし・監査遅延の警告を抑止")));
    }
    @Test void multipleWarningsRenderSafeDetailsAndDiscrepancyLink() throws Exception {
        var time=LocalDateTime.of(2026,10,8,9,0);
        var warnings=List.of(
            new AdminPaymentDiscrepancyAuditWarning(AdminPaymentDiscrepancyAuditWarning.Type.LATEST_FAILURE,
                "直近の定期監査処理が失敗しました（FAILED）。",time,null,PaymentDiscrepancyAuditRunErrorCode.ALL_ITEMS_FAILED),
            new AdminPaymentDiscrepancyAuditWarning(AdminPaymentDiscrepancyAuditWarning.Type.LONG_UNHANDLED,
                "長期未対応の決済不整合があります。",time,2L,null));
        when(service.monitoringSummary()).thenReturn(new AdminPaymentDiscrepancyAuditMonitoringSummary(
            null,null,2,2,true,time,Duration.ofHours(24),warnings));
        mvc.perform(get("/admin/payment-discrepancy-audits").param("status","FAILED")
            .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("直近の定期監査失敗")))
            .andExpect(content().string(containsString("長期未対応の決済不整合")))
            .andExpect(content().string(containsString("ALL_ITEMS_FAILED")))
            .andExpect(content().string(containsString("2026-10-08 09:00:00")))
            .andExpect(content().string(containsString("href=\"/admin/payment-discrepancies\"")))
            .andExpect(content().string(not(containsString("pk_test_dummy"))))
            .andExpect(content().string(not(containsString("sk_test_dummy"))));
    }

    @Test void latestSummaryUsesTwoColumnsAndEscapesInstanceId() throws Exception {
        var run = PaymentDiscrepancyAuditRun.start("<script>alert('instance')</script>",
            Instant.parse("2026-10-08T00:00:00Z"));
        run.finish(Instant.parse("2026-10-08T00:00:01Z"), new PaymentDiscrepancyAuditRunSummary(
            PaymentDiscrepancyAuditRunStatus.PARTIAL_FAILURE,4,2,1,1,1,1,1000,
            PaymentDiscrepancyAuditRunErrorCode.ITEM_FAILURE));
        var item = AdminPaymentDiscrepancyAuditRunListItem.from(run);
        when(service.monitoringSummary()).thenReturn(new AdminPaymentDiscrepancyAuditMonitoringSummary(
            item,null,0,0,true,LocalDateTime.of(2026,10,8,9,0),Duration.ofHours(24),List.of()));
        mvc.perform(get("/admin/payment-discrepancy-audits").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(result -> {
                var matcher = java.util.regex.Pattern.compile(
                    "<table id=\"latest-audit-summary\"[^>]*>(.*?)</table>", java.util.regex.Pattern.DOTALL)
                    .matcher(result.getResponse().getContentAsString());
                assertThat(matcher.find()).as("latest summary is a table").isTrue();
                String table = matcher.group(1);
                assertThat(java.util.regex.Pattern.compile("<tr>").matcher(table).results().count()).isEqualTo(14);
                assertThat(java.util.regex.Pattern.compile("<th scope=\"row\">").matcher(table).results().count()).isEqualTo(14);
                assertThat(java.util.regex.Pattern.compile("<td[ >]").matcher(table).results().count()).isEqualTo(14);
                assertThat(table).contains("2026-10-08 09:00:00", "2026-10-08 09:00:01", "PARTIAL_FAILURE",
                    ">4</td>", ">2</td>", ">0</td>", "ITEM_FAILURE", "&lt;script&gt;")
                    .doesNotContain("<script>");
            });
    }
    @Test void historyHasKeyboardAccessibleHorizontalScrollContainer() throws Exception {
        var item = AdminPaymentDiscrepancyAuditRunListItem.from(PaymentDiscrepancyAuditRun.start(
            "node",Instant.parse("2026-10-08T00:00:00Z")));
        when(service.search(any(),anyInt(),anyInt())).thenReturn(new PageImpl<>(List.of(item)));
        mvc.perform(get("/admin/payment-discrepancy-audits").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(result -> {
                String html = result.getResponse().getContentAsString();
                assertThat(html).contains("id=\"audit-history-scroll\"", "tabindex=\"0\"", "role=\"region\"");
                var matcher = java.util.regex.Pattern.compile(
                    "<div id=\"audit-history-scroll\"[^>]*>(.*?)</div>", java.util.regex.Pattern.DOTALL).matcher(html);
                assertThat(matcher.find()).isTrue();
                String table = matcher.group(1);
                assertThat(java.util.regex.Pattern.compile("<th>").matcher(table).results().count()).isEqualTo(13);
                assertThat(java.util.regex.Pattern.compile("<td>").matcher(table).results().count()).isEqualTo(13);
            });
    }

    @Test void notificationShortcutKeepsAuditSearchAndPaging() throws Exception {
        var run=PaymentDiscrepancyAuditRun.start("node",Instant.parse("2026-10-08T00:00:00Z"));
        var row=AdminPaymentDiscrepancyAuditRunListItem.from(run);
        when(service.search(any(),eq(1),eq(20))).thenReturn(new PageImpl<>(List.of(row),PageRequest.of(1,20),41));
        String body=mvc.perform(get("/admin/payment-discrepancy-audits").param("status","RUNNING")
                .param("from","2026-10-08").param("to","2026-10-09").param("page","1").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String main=body.substring(body.indexOf("<main>"));
        assertThat(main).contains("href=\"/admin/payment-audit-notifications\"","決済監査メール通知履歴を見る",
            "status=RUNNING","from=2026-10-08","to=2026-10-09","page=2","size=20");
        verify(service).search(any(),eq(1),eq(20));
        verify(service).monitoringSummary();verifyNoMoreInteractions(service);
    }

}
