package com.example.ecsite.controller;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.example.ecsite.config.SecurityConfig;
import com.example.ecsite.dto.AdminPaymentAuditNotificationListItem;
import com.example.ecsite.entity.*;
import com.example.ecsite.form.AdminPaymentAuditNotificationSearchForm;
import com.example.ecsite.security.*;
import com.example.ecsite.service.*;
import com.example.ecsite.web.AdminPaymentAuditNotificationDisplay;

@WebMvcTest(value=AdminPaymentAuditNotificationController.class,
    properties="app.payment.discrepancy-audit.notification.enabled=false")
@Import({SecurityConfig.class, AdminPaymentAuditNotificationDisplay.class})
class AdminPaymentAuditNotificationControllerTest {
    private static final String URL="/admin/payment-audit-notifications";
    @Autowired MockMvc mvc;
    @MockitoBean AdminPaymentAuditNotificationService service;
    @MockitoBean AdminUserDetailsService adminUserDetailsService;
    @MockitoBean CustomUserDetailsService customUserDetailsService;
    @MockitoBean AdminAuthenticationSuccessHandler adminAuthenticationSuccessHandler;
    @MockitoBean CustomerAuthenticationSuccessHandler customerAuthenticationSuccessHandler;
    @BeforeEach void reset() {
        when(service.search(any(),anyInt(),anyInt())).thenAnswer(a -> Page.empty(PageRequest.of(0,a.getArgument(2))));
    }
    private String html(String query) throws Exception {
        return mvc.perform(get(URL+query).with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(view().name("admin/payment-audit-notifications/list"))
            .andReturn().getResponse().getContentAsString();
    }
    private AdminPaymentAuditNotificationListItem item(PaymentAuditNotificationStatus status, boolean uncertain) {
        return new AdminPaymentAuditNotificationListItem(42L,LocalDateTime.of(2026,10,9,10,11,12),status,
            List.of(PaymentAuditNotificationWarningType.LATEST_FAILURE,PaymentAuditNotificationWarningType.LONG_UNHANDLED),
            2,2,3,LocalDateTime.of(2026,10,9,11,12,13),LocalDateTime.of(2026,10,9,12,13,14),uncertain);
    }
    @Test void adminSeesEmptyListWhenNotificationsOff() throws Exception {
        assertThat(html("").replaceAll("<[^>]*>", "")).contains("通知送信履歴", "検索結果はありません", "0 件", "20");
        verify(service).search(any(),eq(0),eq(20));
    }
    @Test void anonymousRedirectsToAdminLogin() throws Exception {
        mvc.perform(get(URL)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/login"));
        verifyNoInteractions(service);
    }
    @Test void userIsForbidden() throws Exception {
        mvc.perform(get(URL).with(user("user").roles("USER"))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @ParameterizedTest @CsvSource({"PENDING,送信待ち","CLAIMED,配送準備中","SENDING,送信処理中","RETRY_WAIT,再試行待ち","SENT,送信済み","EXHAUSTED,再試行上限到達","CANCELLED,送信取消"})
    void rendersAllStatusesAndOnlyApplicableSchedule(PaymentAuditNotificationStatus status,String label) throws Exception {
        doReturn(new PageImpl<>(List.of(item(status,true)))).when(service).search(any(),anyInt(),anyInt());
        String body=html("");
        String table=body.substring(body.indexOf("<tbody>"),body.indexOf("</tbody>"));
        assertThat(table).contains(label,"status-badge","2026-10-09 10:11:12","2026-10-09 11:12:13",
            "2 / 3","直近の定期監査失敗","長期未対応の決済不整合","2 件","配送結果不確実","/admin/payment-audit-notifications/42");
        String badge=switch(status) {
            case PENDING,CLAIMED -> "status-ordered";
            case SENDING -> "status-shipped";
            case SENT -> "status-paid";
            case RETRY_WAIT,EXHAUSTED,CANCELLED -> "status-cancelled";
        };
        assertThat(table).contains(badge);
        if(status==PaymentAuditNotificationStatus.PENDING) assertThat(table).contains("初回送信予定","2026-10-09 12:13:14");
        else if(status==PaymentAuditNotificationStatus.RETRY_WAIT) assertThat(table).contains("次回再試行予定","2026-10-09 12:13:14");
        else assertThat(table).doesNotContain("2026-10-09 12:13:14","初回送信予定","次回再試行予定");
    }
    @Test void uncertaintyIsAbsentWhenFalse() throws Exception {
        doReturn(new PageImpl<>(List.of(item(PaymentAuditNotificationStatus.SENT,false)))).when(service).search(any(),anyInt(),anyInt());
        String body=html("");
        assertThat(body.substring(body.indexOf("<tbody>"),body.indexOf("</tbody>"))).doesNotContain("配送結果不確実");
    }
    @Test void allFiveConditionsAndPaginationAreRetained() throws Exception {
        doReturn(new PageImpl<>(List.of(item(PaymentAuditNotificationStatus.SENT,false)),PageRequest.of(1,20),61)).when(service).search(any(),eq(1),eq(20));
        String body=html("?notificationId=42&status=SENT&warningType=LATEST_FAILURE&from=2026-10-08&to=2026-10-09&page=1");
        assertThat(body).contains("notificationId=42","status=SENT","warningType=LATEST_FAILURE","from=2026-10-08","to=2026-10-09",
            "page=0","page=2","page=3","先頭","前へ","次へ","最終");
        assertThat(body.replaceAll("<[^>]*>", "")).contains("2 / 4");
        var captor=ArgumentCaptor.forClass(AdminPaymentAuditNotificationSearchForm.class);
        verify(service).search(captor.capture(),eq(1),eq(20));
        var f=captor.getValue();
        assertThat(f.getNotificationId()).isEqualTo(42L);
        assertThat(f.getStatus()).isEqualTo(PaymentAuditNotificationStatus.SENT);
        assertThat(f.getWarningType()).isEqualTo(PaymentAuditNotificationWarningType.LATEST_FAILURE);
        assertThat(f.getFrom()).isEqualTo(LocalDate.of(2026,10,8));
        assertThat(f.getTo()).isEqualTo(LocalDate.of(2026,10,9));
        assertThat(body).contains("name=\"page\" value=\"0\"");
    }
    @ParameterizedTest @ValueSource(strings={"notificationId=no","notificationId=0","notificationId=-1","notificationId=999999999999999999999","status=INVALID","warningType=INVALID","from=2026-02-30","to=no","from=2026-10-10&to=2026-10-09","from=0000-01-01","to=+10000-01-01","page=no","size=no"})
    void invalidInputsShowErrorsWithoutSearching(String query) throws Exception {
        assertThat(html("?"+query)).contains("入力内容を確認してください");
        verifyNoInteractions(service);
    }
    @ParameterizedTest @ValueSource(strings={"notificationId","status","warningType","from","to"})
    void rejectedValuesAreRetainedAndEscaped(String field) throws Exception {
        String body=mvc.perform(get(URL).param(field,"<script>alert(1)</script>").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("&lt;script&gt;alert(1)&lt;/script&gt;").doesNotContain("<script>");
        verifyNoInteractions(service);
    }
    @ParameterizedTest @ValueSource(ints={20,50,100})
    void sizeChangeFormResetsPage(int size) throws Exception {
        String body=html("?size="+size);
        verify(service).search(any(),eq(0),eq(size));
        assertThat(body).contains("name=\"page\" value=\"0\"","value=\""+size+"\" selected");
    }
    @ParameterizedTest @CsvSource({"-1,0","2147483647,2147483647","999999999999999999999999,2147483647"})
    void pageIsSafelyPassedToBackendCorrection(String input,int expected) throws Exception {
        html("?page="+input);
        verify(service).search(any(),eq(expected),eq(20));
    }
    @Test void noSensitiveFieldsOrDeliveryActionsAreRendered() throws Exception {
        doReturn(new PageImpl<>(List.of(item(PaymentAuditNotificationStatus.EXHAUSTED,true)))).when(service).search(any(),anyInt(),anyInt());
        assertThat(html("")).doesNotContain("claimToken","claim_token","leaseOwner","lease_owner","recipients_hash","hidden-subject","hidden-body","@example.test","手動再送");
        verify(service).search(any(),eq(0),eq(20));
        verifyNoMoreInteractions(service);
    }
    @ParameterizedTest @CsvSource({"CONSECUTIVE_FAILURES,定期監査の連続失敗","LATEST_FAILURE,直近の定期監査失敗","LONG_RUNNING,長時間RUNNING","DELAYED,定期監査の遅延目安超過","NO_HISTORY,定期監査の履歴なし","LONG_UNHANDLED,長期未対応の決済不整合"})
    void warningLabelsAndMissingTimesAreSafe(PaymentAuditNotificationWarningType type,String label) throws Exception {
        var row=new AdminPaymentAuditNotificationListItem(99L,LocalDateTime.of(2026,10,9,0,0),
            PaymentAuditNotificationStatus.PENDING,List.of(type),1,0,3,null,null,false);
        doReturn(new PageImpl<>(List.of(row))).when(service).search(any(),anyInt(),anyInt());
        String body=html("");
        String table=body.substring(body.indexOf("<tbody>"),body.indexOf("</tbody>"));
        assertThat(table).contains(label,"1 件","0 / 3","初回送信予定","—");
    }
    @Test void sizeAndSearchFormDoNotRetainCurrentPage() throws Exception {
        doReturn(new PageImpl<>(List.of(item(PaymentAuditNotificationStatus.SENT,false)),PageRequest.of(1,50),101))
            .when(service).search(any(),eq(1),eq(50));
        String body=html("?page=1&size=50&status=SENT");
        var matcher=java.util.regex.Pattern.compile("<form[^>]*action=\"/admin/payment-audit-notifications\"[^>]*>(.*?)</form>", java.util.regex.Pattern.DOTALL).matcher(body);
        assertThat(matcher.find()).isTrue();
        String form=matcher.group(1);
        assertThat(form).contains("name=\"page\" value=\"0\"","requestSubmit()","value=\"50\" selected",
            "value=\"SENT\" selected");
    }
    @Test void unknownNotificationIdIsAnEmptyResult() throws Exception {
        assertThat(html("?notificationId=99999")).contains("検索結果はありません").doesNotContain("入力内容を確認してください");
    }
}
