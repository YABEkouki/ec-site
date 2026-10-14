package com.example.ecsite.controller;

import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import com.example.ecsite.config.SecurityConfig;
import com.example.ecsite.security.*;
import com.example.ecsite.service.*;
import com.example.ecsite.web.AdminPaymentAuditNotificationManualResendDisplay;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@WebMvcTest(AdminPaymentAuditNotificationManualResendController.class)
@Import({SecurityConfig.class,AdminPaymentAuditNotificationManualResendDisplay.class})
class AdminPaymentAuditNotificationManualResendControllerTest {
    final String base="/admin/payment-audit-notifications/42/manual-resend";
    final AdminUserDetails admin=new AdminUserDetails(7L,"fixture-admin","unused",true,List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    @Autowired MockMvc mvc;
    @MockitoBean AdminPaymentAuditNotificationManualResendViewService views;
    @MockitoBean AdminPaymentAuditNotificationManualResendService acceptance;
    @MockitoBean AdminUserDetailsService adminUserDetailsService;
    @MockitoBean CustomUserDetailsService customUserDetailsService;
    @MockitoBean AdminAuthenticationSuccessHandler adminAuthenticationSuccessHandler;
    @MockitoBean CustomerAuthenticationSuccessHandler customerAuthenticationSuccessHandler;
    AdminPaymentAuditNotificationManualResendViewService.View preview(boolean risk){return new AdminPaymentAuditNotificationManualResendViewService.View(42,new AdminPaymentAuditNotificationManualResendService.Eligibility(true,null,true,risk,"signed-snapshot"),risk?2:1,List.of("***@***"),List.of());}
    @BeforeEach void setup(){when(views.read(42,7,false)).thenReturn(preview(false));when(views.read(42,7,true)).thenReturn(preview(false));}
    @Test void inputAndConfirmEscapeReasonAndMaskRecipients() throws Exception {mvc.perform(get(base).with(user(admin))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("再送理由")));var html=mvc.perform(post(base+"/confirm").with(user(admin)).with(csrf()).param("reason","  <script>test</script>  ")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertThat(html).contains("&lt;script&gt;","***@***","表示している宛先は現在の設定").doesNotContain("<script>");verifyNoInteractions(acceptance);}
    @Test void backKeepsReasonAndReturnUrl() throws Exception {mvc.perform(post(base+"/back").with(user(admin)).with(csrf()).param("reason","  理由  ").param("returnUrl","/admin/payment-audit-notifications?page=2&size=20")).andExpect(status().isOk()).andExpect(model().attribute("returnUrl","/admin/payment-audit-notifications?page=2&size=20"));}
    @Test void acceptedIsPrgAndActorComesFromPrincipal() throws Exception {when(acceptance.request(42,7,"fixture-admin","signed-snapshot","理由",false)).thenReturn(1L);mvc.perform(post(base+"/submit").with(user(admin)).with(csrf()).param("reason","理由").param("snapshotToken","signed-snapshot").param("actorId","999").param("recipients","untrusted@example.invalid")).andExpect(status().is3xxRedirection()).andExpect(flash().attribute("successMessage","再送要求を受け付けました"));verify(acceptance).request(42,7,"fixture-admin","signed-snapshot","理由",false);}
    @ParameterizedTest @ValueSource(strings={"", " ", "\u200b"}) void invalidReasonsNeverAccept(String reason) throws Exception {mvc.perform(post(base+"/confirm").with(user(admin)).with(csrf()).param("reason",reason)).andExpect(status().isOk()).andExpect(view().name("admin/payment-audit-notifications/manual-resend-input"));verifyNoInteractions(acceptance);}
    @Test void failedSecondNeedsAcknowledgement() throws Exception {when(views.read(42,7,true)).thenReturn(preview(true));mvc.perform(post(base+"/confirm").with(user(admin)).with(csrf()).param("reason","理由")).andExpect(view().name("admin/payment-audit-notifications/manual-resend-input"));mvc.perform(post(base+"/confirm").with(user(admin)).with(csrf()).param("reason","理由").param("riskAcknowledged","true")).andExpect(view().name("admin/payment-audit-notifications/manual-resend-confirm")).andExpect(content().string(org.hamcrest.Matchers.containsString("重複して届く")));}
    @Test void csrfAndRoles() throws Exception {mvc.perform(post(base+"/submit").with(user(admin)).param("reason","理由")).andExpect(status().isForbidden());mvc.perform(post(base+"/submit").with(user("user").roles("USER")).with(csrf()).param("reason","理由")).andExpect(status().isForbidden());mvc.perform(post(base+"/submit").with(csrf()).param("reason","理由")).andExpect(status().is3xxRedirection());verifyNoInteractions(acceptance);}
    @Test void invalidExpiredAndOldTokensRedirectSafely() throws Exception {when(acceptance.request(anyLong(),anyLong(),anyString(),anyString(),anyString(),anyBoolean())).thenThrow(new IllegalArgumentException("sensitive internal value"));mvc.perform(post(base+"/submit").with(user(admin)).with(csrf()).param("reason","理由").param("snapshotToken","bad").param("returnUrl","https://outside.invalid")).andExpect(status().is3xxRedirection()).andExpect(flash().attribute("errorMessage",org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sensitive"))));}
    @Test void ineligibleConfirmHasNoSubmissionForm() throws Exception {when(views.read(42,7,true)).thenReturn(new AdminPaymentAuditNotificationManualResendViewService.View(42,new AdminPaymentAuditNotificationManualResendService.Eligibility(false,PaymentAuditNotificationManualResendRejectedException.Reason.NOTIFICATION_DISABLED,false,false,null),1,List.of(),List.of()));mvc.perform(post(base+"/confirm").with(user(admin)).with(csrf()).param("reason","理由")).andExpect(view().name("admin/payment-audit-notifications/manual-resend-input"));}
    @Test void invalidIdAndMissing() throws Exception {mvc.perform(get("/admin/payment-audit-notifications/bad/manual-resend").with(user(admin))).andExpect(status().isBadRequest());when(views.read(42,7,false)).thenThrow(new com.example.ecsite.exception.PaymentAuditNotificationNotFoundException(42L));mvc.perform(get(base).with(user(admin))).andExpect(status().isNotFound());}

    @Test void backRendersReasonRiskAndReturnUrlExactly() throws Exception {
        when(views.read(42,7,false)).thenReturn(preview(true));
        String html=mvc.perform(post(base+"/back").with(user(admin)).with(csrf()).param("reason","  <tag>理由</tag>  ").param("riskAcknowledged","true").param("returnUrl","/admin/payment-audit-notifications?page=2&size=50")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("  &lt;tag&gt;理由&lt;/tag&gt;  ","checked=\"checked\"","/admin/payment-audit-notifications?page=2&amp;size=50");verifyNoInteractions(acceptance);
    }
    @Test void acceptedLocationPreservesSearchExactly() throws Exception {
        mvc.perform(post(base+"/submit").with(user(admin)).with(csrf()).param("reason","理由").param("snapshotToken","signed-snapshot").param("returnUrl","/admin/payment-audit-notifications?page=2&size=50"))
            .andExpect(redirectedUrl("/admin/payment-audit-notifications/42?returnUrl=/admin/payment-audit-notifications?page%3D2%26size%3D50"));
    }
    @org.junit.jupiter.params.ParameterizedTest @ValueSource(strings={"https://outside.invalid","//outside.invalid","/admin/orders"})
    void submittedExternalReturnUrlFallsBack(String url) throws Exception {
        mvc.perform(post(base+"/submit").with(user(admin)).with(csrf()).param("reason","理由").param("snapshotToken","signed-snapshot").param("returnUrl",url))
            .andExpect(redirectedUrl("/admin/payment-audit-notifications/42?returnUrl=/admin/payment-audit-notifications"));
    }
    @org.junit.jupiter.params.ParameterizedTest @ValueSource(strings={"", "\u200b"})
    void reasonErrorMessageIsRenderedAndNeverAccepted(String reason) throws Exception {
        String html=mvc.perform(post(base+"/confirm").with(user(admin)).with(csrf()).param("reason",reason)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("再送理由は空白だけにせず、1,000文字以内で入力してください。");verifyNoInteractions(acceptance);
        mvc.perform(post(base+"/submit").with(user(admin)).with(csrf()).param("reason",reason)).andExpect(flash().attribute("errorMessage","再送理由を確認してください。"));verifyNoInteractions(acceptance);
    }
    @Test void supplementaryReasonLengthIsValidatedInMvc() throws Exception {
        mvc.perform(post(base+"/confirm").with(user(admin)).with(csrf()).param("reason","😀".repeat(1000))).andExpect(view().name("admin/payment-audit-notifications/manual-resend-confirm"));
        mvc.perform(post(base+"/confirm").with(user(admin)).with(csrf()).param("reason","😀".repeat(1001))).andExpect(view().name("admin/payment-audit-notifications/manual-resend-input")).andExpect(content().string(org.hamcrest.Matchers.containsString("再送理由は空白だけにせず、1,000文字以内で入力してください。")));verifyNoInteractions(acceptance);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(PaymentAuditNotificationManualResendRejectedException.Reason.class)
    void directIneligibleUrlCannotReachConfirmationOrAccept(PaymentAuditNotificationManualResendRejectedException.Reason reason) throws Exception {
        var v=new AdminPaymentAuditNotificationManualResendViewService.View(42,new AdminPaymentAuditNotificationManualResendService.Eligibility(false,reason,true,false,null),1,List.of(),List.of());when(views.read(42,7,false)).thenReturn(v);when(views.read(42,7,true)).thenReturn(v);
        String input=mvc.perform(get(base).with(user(admin))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertThat(input).contains("disabled=\"disabled\"");
        String html=mvc.perform(post(base+"/confirm").with(user(admin)).with(csrf()).param("reason","理由")).andExpect(view().name("admin/payment-audit-notifications/manual-resend-input")).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("現在は再送できません。詳細を再読み込みしてください。").doesNotContain("/manual-resend/submit");verifyNoInteractions(acceptance);
        when(acceptance.request(anyLong(),anyLong(),anyString(),anyString(),anyString(),anyBoolean())).thenThrow(new PaymentAuditNotificationManualResendRejectedException(reason));
        mvc.perform(post(base+"/submit").with(user(admin)).with(csrf()).param("reason","理由").param("snapshotToken","old"))
          .andExpect(redirectedUrl("/admin/payment-audit-notifications/42?returnUrl=/admin/payment-audit-notifications")).andExpect(flash().attribute("errorMessage",new AdminPaymentAuditNotificationManualResendDisplay().rejection(reason)));
    }
    @org.junit.jupiter.params.ParameterizedTest @ValueSource(strings={"tampered","expired"})
    void invalidTokenHasExactSafeMessageAndLocation(String token) throws Exception {
        when(acceptance.request(anyLong(),anyLong(),anyString(),anyString(),anyString(),anyBoolean())).thenThrow(new IllegalArgumentException("secret-internal"));
        mvc.perform(post(base+"/submit").with(user(admin)).with(csrf()).param("reason","理由").param("snapshotToken",token).param("returnUrl","https://outside.invalid"))
          .andExpect(redirectedUrl("/admin/payment-audit-notifications/42?returnUrl=/admin/payment-audit-notifications")).andExpect(flash().attribute("errorMessage","署名が不正・期限切れ、または入力が不正です。詳細を再読み込みしてください。"));
    }
    @Test void missingKeyCannotConfirmOrSubmit() throws Exception {
        when(views.read(42,7,true)).thenReturn(new AdminPaymentAuditNotificationManualResendViewService.View(42,new AdminPaymentAuditNotificationManualResendService.Eligibility(true,null,false,false,null),1,List.of(),List.of()));
        mvc.perform(post(base+"/confirm").with(user(admin)).with(csrf()).param("reason","理由")).andExpect(view().name("admin/payment-audit-notifications/manual-resend-input"));verifyNoInteractions(acceptance);
        when(acceptance.request(anyLong(),anyLong(),anyString(),anyString(),anyString(),anyBoolean())).thenThrow(new IllegalStateException("secret-key"));
        mvc.perform(post(base+"/submit").with(user(admin)).with(csrf()).param("reason","理由").param("snapshotToken","old")).andExpect(flash().attribute("errorMessage","署名設定を確認してください。現在は操作できません。"));
    }
}
