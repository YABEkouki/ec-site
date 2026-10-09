package com.example.ecsite.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.example.ecsite.config.SecurityConfig;
import com.example.ecsite.dto.*;
import com.example.ecsite.entity.*;
import com.example.ecsite.exception.PaymentAuditNotificationNotFoundException;
import com.example.ecsite.security.*;
import com.example.ecsite.service.*;
import com.example.ecsite.web.AdminPaymentAuditNotificationDisplay;

@WebMvcTest(value=AdminPaymentAuditNotificationController.class,properties="app.payment.discrepancy-audit.notification.enabled=false")
@Import({SecurityConfig.class,AdminPaymentAuditNotificationDisplay.class})
class AdminPaymentAuditNotificationDetailControllerTest {
    private static final String URL="/admin/payment-audit-notifications/42";
    private static final LocalDateTime TIME=LocalDateTime.of(2026,10,9,10,11,12);
    @Autowired MockMvc mvc;
    @MockitoBean AdminPaymentAuditNotificationService service;
    @MockitoBean AdminUserDetailsService adminUserDetailsService;
    @MockitoBean CustomUserDetailsService customUserDetailsService;
    @MockitoBean AdminAuthenticationSuccessHandler adminAuthenticationSuccessHandler;
    @MockitoBean CustomerAuthenticationSuccessHandler customerAuthenticationSuccessHandler;
    private AdminPaymentAuditNotificationDetail detail(PaymentAuditNotificationStatus status,PaymentAuditNotificationCloseReason reason,
            List<AdminPaymentAuditNotificationWarningItem> warnings,List<AdminPaymentAuditNotificationAttemptItem> attempts) {
        return new AdminPaymentAuditNotificationDetail(42L,status,TIME,TIME.plusSeconds(1),TIME.minusSeconds(1),
            2,3,TIME.plusSeconds(2),TIME.plusSeconds(3),TIME.plusSeconds(4),reason,true,2,warnings,attempts);
    }
    @BeforeEach void setup() {
        when(service.findById(42L)).thenReturn(detail(PaymentAuditNotificationStatus.SENT,null,List.of(),List.of()));
    }
    private String html(String returnUrl) throws Exception {
        var request=get(URL).with(user("admin").roles("ADMIN"));
        if(returnUrl!=null) request.param("returnUrl",returnUrl);
        return mvc.perform(request).andExpect(status().isOk()).andExpect(view().name("admin/payment-audit-notifications/detail"))
            .andReturn().getResponse().getContentAsString();
    }
    private String section(String html,String id) {
        var matcher=java.util.regex.Pattern.compile("<section[^>]*id=\""+id+"\"[^>]*>(.*?)</section>",java.util.regex.Pattern.DOTALL).matcher(html);
        assertThat(matcher.find()).isTrue();return matcher.group(1);
    }
    @Test void adminReadsBasicInformationWhenNotificationsOff() throws Exception {
        String body=section(html(null),"notification-summary");
        assertThat(body).contains("通知ID","42","送信済み","2026-10-09 10:11:12","2026-10-09 10:11:13",
            "2026-10-09 10:11:11","2 / 3","2026-10-09 10:11:14","2026-10-09 10:11:16","配送結果不確実","送信先件数");
        assertThat(body).doesNotContain("2026-10-09 10:11:15");
        verify(service).findById(42L);verifyNoMoreInteractions(service);
    }
    @Test void anonymousUsesAdminLogin() throws Exception {
        mvc.perform(get(URL)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/login"));
        verifyNoInteractions(service);
    }
    @Test void userIsForbidden() throws Exception {
        mvc.perform(get(URL).with(user("user").roles("USER"))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void missingIdIs404RatherThanGlobal500() throws Exception {
        when(service.findById(42L)).thenThrow(new PaymentAuditNotificationNotFoundException(42L));
        mvc.perform(get(URL).with(user("admin").roles("ADMIN"))).andExpect(status().isNotFound());
    }
    @ParameterizedTest @ValueSource(strings={"bad","0","-1","99999999999999999999999","1.5"})
    void invalidIdIs400AndNeverRead(String id) throws Exception {
        mvc.perform(get("/admin/payment-audit-notifications/"+id).with(user("admin").roles("ADMIN")))
            .andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
    @ParameterizedTest @CsvSource({"PENDING,送信待ち","CLAIMED,配送準備中","SENDING,送信処理中","RETRY_WAIT,再試行待ち","SENT,送信済み","EXHAUSTED,再試行上限到達","CANCELLED,送信取消"})
    void statusesAndSchedulesAreDistinct(PaymentAuditNotificationStatus status,String label) throws Exception {
        when(service.findById(42L)).thenReturn(detail(status,null,List.of(),List.of()));
        String basic=section(html(null),"notification-summary");
        assertThat(basic).contains(label,"配送結果不確実");
        if(status==PaymentAuditNotificationStatus.PENDING) assertThat(basic).contains("初回送信予定","2026-10-09 10:11:15");
        else if(status==PaymentAuditNotificationStatus.RETRY_WAIT) assertThat(basic).contains("次回再試行予定","2026-10-09 10:11:15");
        else assertThat(basic).doesNotContain("初回送信予定","次回再試行予定","2026-10-09 10:11:15");
    }
    @ParameterizedTest @CsvSource({"RESOLVED,対象警告の解消","NO_VALID_RECIPIENTS,有効な送信先なし","MAX_ATTEMPTS,最大試行回数到達"})
    void closeReasonsAreJapanese(PaymentAuditNotificationCloseReason reason,String label) throws Exception {
        when(service.findById(42L)).thenReturn(detail(PaymentAuditNotificationStatus.CANCELLED,reason,List.of(),List.of()));
        assertThat(section(html(null),"notification-summary")).contains(label);
    }
    @Test void absentTimesCountsAndAttemptHistoryUseDashes() throws Exception {
        when(service.findById(42L)).thenReturn(new AdminPaymentAuditNotificationDetail(42L,PaymentAuditNotificationStatus.PENDING,
            null,null,null,0,3,null,null,null,null,false,0,List.of(new AdminPaymentAuditNotificationWarningItem(
                PaymentAuditNotificationWarningType.NO_HISTORY,5L,PaymentAuditNotificationItemStatus.INCLUDED,null,null,null,null,null,null)),List.of()));
        String body=html(null);
        assertThat(body).contains("—","送信試行履歴はありません。");
        assertThat(section(body,"notification-summary")).doesNotContain("配送結果不確実");
    }
    @Test void allHistoricalWarningItemsAndEpisodesAreDisplayed() throws Exception {
        var warnings=Arrays.stream(PaymentAuditNotificationWarningType.values()).map(t -> new AdminPaymentAuditNotificationWarningItem(
            t,7L, t==PaymentAuditNotificationWarningType.LATEST_FAILURE ? PaymentAuditNotificationItemStatus.REMOVED_RESOLVED : PaymentAuditNotificationItemStatus.INCLUDED,
            TIME,TIME.plusSeconds(1),25L,PaymentDiscrepancyAuditRunErrorCode.ITEM_FAILURE,TIME.plusSeconds(2),TIME.plusSeconds(3))).toList();
        when(service.findById(42L)).thenReturn(detail(PaymentAuditNotificationStatus.SENT,null,warnings,List.of()));
        String body=html(null), warning=section(body,"notification-warnings");
        assertThat(warning).contains("定期監査の連続失敗","直近の定期監査失敗","長時間RUNNING","定期監査の遅延目安超過","定期監査の履歴なし","長期未対応の決済不整合",
            "通知対象に含む","解消により除外","警告関連件数",">25<",">7<","ITEM_FAILURE","2026-10-09 10:11:14","2026-10-09 10:11:15");
        assertThat(body).contains("この画面は通知に保存された警告情報を表示しています。現在の警告状態とは異なる場合があります。")
            .doesNotContain("警告観測回数","現在も警告が発生中");
    }
    @Test void attemptResultsRemainDistinctAndOrderedWithSafeCodes() throws Exception {
        var history=List.of(new AdminPaymentAuditNotificationAttemptItem(1,PaymentAuditNotificationAttemptResult.FAILURE,TIME,TIME.plusSeconds(1),PaymentAuditNotificationFailureCode.SMTP_SEND_FAILED),
            new AdminPaymentAuditNotificationAttemptItem(2,PaymentAuditNotificationAttemptResult.UNKNOWN,TIME,TIME.plusSeconds(2),PaymentAuditNotificationFailureCode.SEND_DEADLINE_EXCEEDED),
            new AdminPaymentAuditNotificationAttemptItem(3,PaymentAuditNotificationAttemptResult.SUCCESS,TIME,TIME.plusSeconds(3),null),
            new AdminPaymentAuditNotificationAttemptItem(4,PaymentAuditNotificationAttemptResult.IN_PROGRESS,TIME,null,null));
        when(service.findById(42L)).thenReturn(detail(PaymentAuditNotificationStatus.SENT,null,List.of(),history));
        String attempts=section(html(null),"notification-attempts");
        assertThat(attempts).contains("送信失敗","結果不明","送信成功","処理中","SMTP_SEND_FAILED","SEND_DEADLINE_EXCEEDED",
            "終了日時は実際のメール送信時刻とは限りません。UNKNOWN（結果不明）では、結果確定・回収の日時です。");
        assertThat(attempts.indexOf("送信失敗")).isLessThan(attempts.indexOf("結果不明</"));
        assertThat(attempts.indexOf("結果不明</")).isLessThan(attempts.indexOf("送信成功"));
        assertThat(attempts.indexOf("送信成功")).isLessThan(attempts.indexOf("処理中"));
    }
    @Test void validReturnUrlKeepsAllSevenConditions() throws Exception {
        String url="/admin/payment-audit-notifications?notificationId=42&status=SENT&warningType=LATEST_FAILURE&from=2026-10-08&to=2026-10-09&page=2&size=50";
        mvc.perform(get(URL).param("returnUrl",url).with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(model().attribute("returnUrl",url));
        assertThat(html(url)).contains("通知履歴一覧に戻る","notificationId=42&amp;status=SENT","page=2&amp;size=50");
    }
    @ParameterizedTest @ValueSource(strings={"https://evil.test","//evil.test","/admin/orders","/admin/payment-audit-notifications/1","/admin/payment-audit-notifications/../orders","/admin/payment-audit-notifications#x","/admin/payment-audit-notifications?x=%0d%0a","/admin/payment-audit-notifications?x=%ZZ","/admin/payment-audit-notifications?x=\\evil","/admin/payment-audit-notifications?x=<script>"})
    void unsafeReturnUrlsFallBack(String url) throws Exception {
        mvc.perform(get(URL).param("returnUrl",url).with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(model().attribute("returnUrl","/admin/payment-audit-notifications"));
    }
    @Test void returnUrlQueryIsEscapedAndSecretsAreAbsent() throws Exception {
        String body=html("/admin/payment-audit-notifications?status=%22%3E%3Cscript%3E&size=20");
        assertThat(body).contains("&amp;size=20").doesNotContain("<script>","hidden-from","hidden-first","hidden-body","hidden-subject","claim_token","lease_owner","recipient_set_hash");
    }
}
