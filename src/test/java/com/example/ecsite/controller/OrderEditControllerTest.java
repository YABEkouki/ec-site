package com.example.ecsite.controller;

import java.util.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import com.example.ecsite.config.SecurityConfig;
import com.example.ecsite.security.*;
import com.example.ecsite.service.*;
import com.example.ecsite.service.order.evidence.*;
import com.example.ecsite.form.OrderEditForm;
import com.example.ecsite.exception.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrderEditController.class)
@Import(SecurityConfig.class)
class OrderEditControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean OrderEditPreviewService service;
    @MockitoBean OrderEditBrowserService browser;
    @MockitoBean AdminUserDetailsService adminUserDetailsService;
    @MockitoBean CustomUserDetailsService customUserDetailsService;
    @MockitoBean AdminAuthenticationSuccessHandler adminAuthenticationSuccessHandler;
    @MockitoBean CustomerAuthenticationSuccessHandler customerAuthenticationSuccessHandler;
    static final String URL="/orders/1/edit";
    final CustomUserDetails customer=new CustomUserDetails(2L,"buyer","unused",true,List.of(new SimpleGrantedAuthority("ROLE_USER")));
    OrderContentSnapshot before,after;
    OrderEditPreviewService.PageContext page;
    LocalDateTime now=LocalDateTime.of(2026,11,4,13,0);
    @BeforeEach void data(){
        when(browser.load(anyLong())).thenAnswer(a->service.load(a.getArgument(0),2L));
        when(browser.preview(anyLong(),any())).thenAnswer(a->service.preview(a.getArgument(0),2L,a.getArgument(1)));
        when(browser.offer(anyLong())).thenAnswer(a->service.offer(a.getArgument(0)));
        when(browser.search(any(),anyInt())).thenAnswer(a->service.search(a.getArgument(0),a.getArgument(1)));
        before=snapshot(1);after=snapshot(2);
        var facts=new OrderPaymentEvidence.Facts(before,10L,List.of(),List.of(),true,null,List.of(),null,false,false);
        page=new OrderEditPreviewService.PageContext(new OrderEditCandidate.Context(facts,2,"ORDERED",now.minusDays(1),now.plusHours(1),null,null,null,now),null);
        when(service.load(1,2)).thenReturn(page);
        when(service.search(any(),anyInt())).thenReturn(Page.empty(PageRequest.of(0,10)));
        when(service.preview(eq(1L),eq(2L),any())).thenReturn(new OrderEditPreviewService.Preview(before,after,now.plusHours(1),OrderChangeRoutePolicy.Route.REAUTHORIZE));
        when(service.offer(8)).thenReturn(new OrderEditCandidate.Offer(8,"追加商品",4,"分類",1000,5,"STANDARD","標準",1000,true));
    }
    @ParameterizedTest @ValueSource(strings={"ENROLLED","ALREADY_ENROLLED"})
    void startEditingRedirectsWithoutSubmittingAnEdit(String statusName)throws Exception{
        when(browser.startEditing(1)).thenReturn(new LegacyOrderEvidenceEnrollment.Result(LegacyOrderEvidenceEnrollment.Status.valueOf(statusName),UUID.randomUUID()));
        for(int i=0;i<2;i++)mvc.perform(post(URL+"/start").with(user(customer)).with(csrf())).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl(URL));
        verify(browser,never()).submit(anyLong(),any(),any(),any());
    }
    @Test void editStartRequiresCsrfAndGetIsReadOnly()throws Exception{
        mvc.perform(post(URL+"/start").with(user(customer))).andExpect(status().isForbidden());
        mvc.perform(get(URL).with(user(customer))).andExpect(status().isOk());verify(browser,never()).startEditing(anyLong());verify(browser,never()).enroll(anyLong());
    }
    @ParameterizedTest @ValueSource(strings={"OBSERVATION_UNAVAILABLE","COMMIT_UNKNOWN","REVIEW_REQUIRED"})
    void editStartFailurePreservesManualFallback(String statusName)throws Exception{
        when(browser.startEditing(1)).thenReturn(new LegacyOrderEvidenceEnrollment.Result(LegacyOrderEvidenceEnrollment.Status.valueOf(statusName),null));
        mvc.perform(post(URL+"/start").with(user(customer)).with(csrf())).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl(URL)).andExpect(flash().attributeExists("errorMessage"));
        verify(browser,times(1)).startEditing(1);verify(browser,never()).enroll(anyLong());
    }
    @Test void foreignEditStartIsNotFound()throws Exception{
        when(browser.startEditing(1)).thenThrow(new OrderNotFoundException(1L));
        mvc.perform(post(URL+"/start").with(user(customer)).with(csrf())).andExpect(status().isNotFound());
    }
    @Test void submitReusesServerQuoteKeyAndRedirectsToOwnedResult()throws Exception{
        var candidate=mock(OrderEditCandidate.Candidate.class);
        when(service.preview(eq(1L),eq(2L),any())).thenReturn(new OrderEditPreviewService.Preview(before,after,now.plusHours(1),OrderChangeRoutePolicy.Route.RETAIN_AUTH,candidate));
        var confirmation=mvc.perform(form("/confirm")).andExpect(status().is3xxRedirection()).andReturn();
        var session=(MockHttpSession)confirmation.getRequest().getSession(false);String location=confirmation.getResponse().getRedirectedUrl();
        UUID key=UUID.fromString(location.substring(location.indexOf("preview=")+8));UUID id=UUID.randomUUID();
        when(browser.submit(eq(1L),eq(key),any(),eq(candidate))).thenReturn(new OrderEditAdmissionService.Result(OrderEditAdmissionService.Outcome.ACCEPTED,id,"ACCEPTED","QUEUED","test"));
        for(int i=0;i<2;i++)mvc.perform(post(URL+"/submit").with(user(customer)).with(csrf()).session(session).param("preview",key.toString()).param("totalAmount","1"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl(URL+"/result?admission="+id));
        verify(browser,times(2)).submit(eq(1L),eq(key),any(),eq(candidate));
    }
    @Test void confirmationOnlyEnablesSubmissionWhenRuntimeAllowsIt()throws Exception{
        var candidate=mock(OrderEditCandidate.Candidate.class);
        when(service.preview(eq(1L),eq(2L),any())).thenReturn(new OrderEditPreviewService.Preview(before,after,now.plusHours(1),OrderChangeRoutePolicy.Route.RETAIN_AUTH,candidate));
        var r=mvc.perform(form("/confirm")).andReturn();var session=(MockHttpSession)r.getRequest().getSession(false);var location=r.getResponse().getRedirectedUrl();
        mvc.perform(get(location).with(user(customer)).session(session)).andExpect(status().isOk()).andExpect(model().attribute("submissionAvailable",false))
            .andExpect(content().string(containsString("disabled")));
        when(browser.submissionAvailable()).thenReturn(true);
        mvc.perform(get(location).with(user(customer)).session(session)).andExpect(status().isOk()).andExpect(model().attribute("submissionAvailable",true));
    }
    @Test void unknownCommitWithoutReceiptPreservesOriginalKeyForExplicitRetry()throws Exception{
        var candidate=mock(OrderEditCandidate.Candidate.class);
        when(service.preview(eq(1L),eq(2L),any())).thenReturn(new OrderEditPreviewService.Preview(before,after,now.plusHours(1),OrderChangeRoutePolicy.Route.RETAIN_AUTH,candidate));
        var confirmation=mvc.perform(form("/confirm")).andReturn();var session=(MockHttpSession)confirmation.getRequest().getSession(false);
        String location=confirmation.getResponse().getRedirectedUrl();UUID key=UUID.fromString(location.substring(location.indexOf("preview=")+8));
        when(browser.submit(eq(1L),eq(key),any(),eq(candidate))).thenReturn(new OrderEditAdmissionService.Result(OrderEditAdmissionService.Outcome.COMMIT_UNKNOWN,null,null,null,"unknown"));
        mvc.perform(post(URL+"/submit").with(user(customer)).with(csrf()).session(session).param("preview",key.toString()))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl(URL+"/result?preview="+key));
        when(browser.submissionAvailable()).thenReturn(true);
        mvc.perform(get(URL+"/result").with(user(customer)).session(session).param("preview",key.toString())).andExpect(status().isOk())
            .andExpect(content().string(containsString("同じ確認内容で再送する"))).andExpect(content().string(containsString(key.toString())));
        mvc.perform(post(URL+"/submit").with(user(customer)).with(csrf()).session(session).param("preview",key.toString())).andExpect(status().is3xxRedirection());
        verify(browser,times(2)).submit(eq(1L),eq(key),any(),eq(candidate));
        verify(browser,never()).continueAdmission(anyLong(),any());
    }
    @Test void resultGetOnlyReadsAndCanRenderCardWait()throws Exception{
        UUID id=UUID.randomUUID();when(browser.result(1,id)).thenReturn(new OrderEditBrowserService.BrowserResult(id,"カード認証待ち",false));
        mvc.perform(get(URL+"/result").param("admission",id.toString()).with(user(customer))).andExpect(status().isOk())
            .andExpect(content().string(containsString("カード認証待ち")));
        verify(browser).result(1,id);verify(browser,never()).continueAdmission(anyLong(),any());verify(browser,never()).submit(anyLong(),any(),any(),any());
    }
    @Test void submitAndContinueNeedCsrfAndQuoteCannotBeInvented()throws Exception{
        mvc.perform(post(URL+"/submit").with(user(customer)).param("preview",UUID.randomUUID().toString())).andExpect(status().isForbidden());
        mvc.perform(post(URL+"/continue").with(user(customer)).param("admission",UUID.randomUUID().toString())).andExpect(status().isForbidden());
        mvc.perform(post(URL+"/submit").with(user(customer)).with(csrf()).param("preview",UUID.randomUUID().toString()))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl(URL));
        verify(browser,never()).submit(anyLong(),any(),any(),any());
    }
    @Test void legacyPageOffersExplicitPostAndDoesNotEnrollOnGet()throws Exception{
        var facts=new OrderPaymentEvidence.Facts(before,10L,List.of(),List.of(),false,null,List.of(),null,false,false);
        when(service.load(1,2)).thenReturn(new OrderEditPreviewService.PageContext(new OrderEditCandidate.Context(facts,2,"ORDERED",now.minusDays(1),now.plusHours(1),null,null,null,now),"確認が必要です"));
        mvc.perform(get(URL).with(user(customer))).andExpect(status().isOk())
            .andExpect(content().string(containsString("action=\"/orders/1/edit/enroll\"")))
            .andExpect(content().string(containsString("注文情報を確認して編集を開始する")));
        verify(browser,never()).enroll(anyLong());
    }
    @Test void explicitEnrollmentRedirectsToEditing()throws Exception{
        when(browser.enroll(1)).thenReturn(new LegacyOrderEvidenceEnrollment.Result(LegacyOrderEvidenceEnrollment.Status.ENROLLED,null));
        mvc.perform(post(URL+"/enroll").with(user(customer)).with(csrf())).andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl(URL)).andExpect(flash().attributeExists("successMessage"));
        verify(browser).enroll(1);
    }
    @Test void enrollmentNeedsCsrfAndGetNeverEnrolls()throws Exception{
        mvc.perform(post(URL+"/enroll").with(user(customer))).andExpect(status().isForbidden());
        mvc.perform(get(URL).with(user(customer))).andExpect(status().isOk());
        verify(browser,never()).enroll(anyLong());
    }
    @Test void foreignEnrollmentIsNotFound()throws Exception{
        when(browser.enroll(1)).thenThrow(new OrderNotFoundException(1L));
        mvc.perform(post(URL+"/enroll").with(user(customer)).with(csrf())).andExpect(status().isNotFound());
    }
    @Test void unknownEnrollmentIsNotReportedAsSuccess()throws Exception{
        when(browser.enroll(1)).thenReturn(new LegacyOrderEvidenceEnrollment.Result(LegacyOrderEvidenceEnrollment.Status.COMMIT_UNKNOWN,null));
        mvc.perform(post(URL+"/enroll").with(user(customer)).with(csrf())).andExpect(status().is3xxRedirection())
            .andExpect(flash().attributeExists("errorMessage")).andExpect(flash().attributeCount(1));
    }
    OrderContentSnapshot snapshot(int quantity){
        int subtotal=6000*quantity;
        return new OrderContentSnapshot(1,1,2,0,List.of(new OrderContentSnapshot.Item(3,7,"保存商品",4,"保存分類",6000,quantity,subtotal,5,"STANDARD","標準",1000)),
            List.of(new OrderContentSnapshot.Charge(6,"SHIPPING","送料",0,5,"STANDARD","標準",1000,10)),subtotal,0,subtotal*10/110,subtotal);
    }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder form(String suffix){
        return post(URL+suffix).with(user(customer)).with(csrf()).param("revision","0")
            .param("lines[0].existingItemId","3").param("lines[0].productId","7").param("lines[0].quantity","2");
    }
    @Test void editRendersSavedPricesAndSearchInTheExistingLayout()throws Exception{
        mvc.perform(get(URL).with(user(customer))).andExpect(status().isOk()).andExpect(view().name("orders/edit"))
            .andExpect(content().string(containsString("注文内容の一括編集"))).andExpect(content().string(containsString("保存商品")))
            .andExpect(content().string(containsString("注文時単価"))).andExpect(content().string(containsString("_csrf")));
        verify(service).load(1,2);verify(service,never()).preview(anyLong(),anyLong(),any());
    }
    @Test void searchPreservesTheDraftQuantityAndUsesEscapedProductNames()throws Exception{
        var product=new com.example.ecsite.entity.Product();product.setName("<script>bad()</script>");product.setPrice(1000);product.setStock(5);
        org.springframework.test.util.ReflectionTestUtils.setField(product,"id",8L);
        when(service.search("camp",0)).thenReturn(new PageImpl<>(List.of(product),PageRequest.of(0,10),1));
        var result=mvc.perform(form("/search").param("keyword","camp")).andExpect(status().isOk()).andExpect(view().name("orders/edit")).andReturn();
        var f=(OrderEditForm)result.getModelAndView().getModel().get("editForm");assertThat(f.getLines().getFirst().getQuantity()).isEqualTo(2);
        assertThat(result.getResponse().getContentAsString()).contains("&lt;script&gt;").doesNotContain("<script>bad()");verify(service).search("camp",0);
    }
    @Test void addingAProductPreservesDeletionAndDoesNotMergeSavedRows()throws Exception{
        var r=mvc.perform(post(URL+"/add").with(user(customer)).with(csrf()).param("revision","0").param("lines[0].existingItemId","3").param("lines[0].productId","7").param("lines[0].quantity","0").param("addProductId","8"))
            .andExpect(status().isOk()).andReturn();
        var f=(OrderEditForm)r.getModelAndView().getModel().get("editForm");
        assertThat(f.getLines()).hasSize(2);assertThat(f.getLines().getFirst().getExistingItemId()).isEqualTo(3);assertThat(f.getLines().getFirst().getQuantity()).isZero();
        assertThat(f.getLines().get(1).getExistingItemId()).isNull();assertThat(f.getLines().get(1).getProductId()).isEqualTo(8);
    }
    @Test void confirmationIsPrgAndCanOnlyReturnToEditing()throws Exception{
        var r=mvc.perform(form("/confirm").param("totalAmount","1").param("taxRate","0")).andExpect(status().is3xxRedirection()).andReturn();
        String location=r.getResponse().getRedirectedUrl();var session=(MockHttpSession)r.getRequest().getSession(false);
        var html=mvc.perform(get(location).with(user(customer)).session(session)).andExpect(status().isOk()).andExpect(view().name("orders/edit-confirm"))
            .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("変更前","変更後","差額","内税","disabled");
        var capture=org.mockito.ArgumentCaptor.forClass(OrderEditCandidate.Request.class);verify(service,times(2)).preview(eq(1L),eq(2L),capture.capture());
        assertThat(capture.getValue().lines().getFirst().quantity()).isEqualTo(2);
        String token=location.substring(location.indexOf("preview="));
        var back=mvc.perform(get(URL+"?"+token).with(user(customer)).session(session)).andExpect(status().isOk()).andReturn();
        assertThat(((OrderEditForm)back.getModelAndView().getModel().get("editForm")).getLines().getFirst().getQuantity()).isEqualTo(2);
        mvc.perform(post(URL).with(user(customer)).with(csrf())).andExpect(status().isMethodNotAllowed());
    }
    @ParameterizedTest @ValueSource(strings={"-1","2147483648","abc"})
    void invalidQuantitiesRedisplayWithoutCalculating(String quantity)throws Exception{
        mvc.perform(post(URL+"/confirm").with(user(customer)).with(csrf()).param("revision","0")
            .param("lines[0].existingItemId","3").param("lines[0].productId","7").param("lines[0].quantity",quantity))
            .andExpect(status().isOk()).andExpect(view().name("orders/edit"));
        verify(service,never()).preview(anyLong(),anyLong(),any());
    }
    @Test void emptyOrderAndMissingProductDisplayActionableErrors()throws Exception{
        when(service.preview(anyLong(),anyLong(),any())).thenThrow(new IllegalArgumentException("商品をすべて削除する場合は、注文キャンセルをご利用ください。"));
        mvc.perform(form("/confirm")).andExpect(status().isOk()).andExpect(content().string(containsString("注文キャンセル")));
        when(service.offer(999)).thenThrow(new IllegalArgumentException("選択した商品は追加できません。"));
        mvc.perform(form("/add").param("addProductId","999")).andExpect(status().isOk()).andExpect(content().string(containsString("追加できません")));
    }
    @Test void staleConfirmationIsRejectedInsteadOfShowingOldPrices()throws Exception{
        var r=mvc.perform(form("/confirm")).andReturn();String location=r.getResponse().getRedirectedUrl();
        when(service.preview(anyLong(),anyLong(),any())).thenReturn(new OrderEditPreviewService.Preview(before,snapshot(3),now.plusHours(1),OrderChangeRoutePolicy.Route.REAUTHORIZE));
        mvc.perform(get(location).with(user(customer)).session((MockHttpSession)r.getRequest().getSession(false)))
            .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("errorMessage"));
    }
    @Test void unavailableReasonIsVisibleWithoutEditableControls()throws Exception{
        when(service.load(1,2)).thenReturn(new OrderEditPreviewService.PageContext(page.context(),"発送準備が始まっているため、編集できません。"));
        var html=mvc.perform(get(URL).with(user(customer))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("発送準備").doesNotContain("name=\"lines[0].quantity\"");
    }
    @Test void authenticationOwnershipAndCsrfUseExistingSecurity()throws Exception{
        mvc.perform(get(URL)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
        mvc.perform(get(URL).with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
        when(service.load(99,2)).thenThrow(new OrderNotFoundException(99L));
        mvc.perform(get("/orders/99/edit").with(user(customer))).andExpect(status().isNotFound());
        for(String path:List.of("/search","/add","/confirm"))mvc.perform(post(URL+path).with(user(customer))).andExpect(status().isForbidden());
    }
    @Test void repeatedNewProductAddsMergeOnlyTheNewRow()throws Exception {
        var result=mvc.perform(form("/add").param("lines[1].productId","8").param("lines[1].quantity","1").param("addProductId","8"))
            .andExpect(status().isOk()).andReturn();
        var f=(OrderEditForm)result.getModelAndView().getModel().get("editForm");
        assertThat(f.getLines()).hasSize(2);assertThat(f.getLines().get(1).getQuantity()).isEqualTo(2);
        assertThat(f.getLines().getFirst().getExistingItemId()).isEqualTo(3);
    }
    @Test void noOpAndExpiredPreviewDoNotExposeAConfirmation()throws Exception {
        when(service.preview(anyLong(),anyLong(),any())).thenReturn(new OrderEditPreviewService.Preview(before,before,now.plusHours(1),OrderChangeRoutePolicy.Route.NO_CHANGE));
        mvc.perform(form("/confirm")).andExpect(status().isOk()).andExpect(view().name("orders/edit"))
            .andExpect(content().string(containsString("変更内容がありません")));
        when(service.load(1,2)).thenReturn(new OrderEditPreviewService.PageContext(page.context(),"注文変更期限を過ぎています。"));
        mvc.perform(form("/confirm")).andExpect(status().isOk()).andExpect(view().name("orders/edit"))
            .andExpect(content().string(containsString("注文変更期限を過ぎています")));
    }
    @Test void foreignOrderPostsAndForgedIndicesAreRejected()throws Exception {
        when(service.load(99,2)).thenThrow(new OrderNotFoundException(99L));
        for(String action:List.of("search","add","confirm"))mvc.perform(post("/orders/99/edit/"+action).with(user(customer)).with(csrf())
            .param("revision","0").param("addProductId","8")).andExpect(status().isNotFound());
        mvc.perform(post(URL+"/confirm").with(user(customer)).with(csrf()).param("revision","0").param("lines[1000].quantity","1"))
            .andExpect(status().isBadRequest());
    }

    @Test void invalidatedProductAtConfirmationReturnsToThePreservedDraft()throws Exception {
        var submitted=mvc.perform(form("/confirm")).andReturn();
        String location=submitted.getResponse().getRedirectedUrl();var session=(MockHttpSession)submitted.getRequest().getSession(false);
        when(service.preview(anyLong(),anyLong(),any())).thenThrow(new IllegalArgumentException("選択した商品は追加できません。"));
        var result=mvc.perform(get(location).with(user(customer)).session(session))
            .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("errorMessage")).andReturn();
        var back=mvc.perform(get(result.getResponse().getRedirectedUrl()).with(user(customer)).session(session)).andExpect(status().isOk()).andReturn();
        assertThat(((OrderEditForm)back.getModelAndView().getModel().get("editForm")).getLines().getFirst().getQuantity()).isEqualTo(2);
    }


    @Test void eligibleResultRendersNonceProtectedAutomaticFormWithoutExecutingOnGet()throws Exception{
        UUID id=UUID.randomUUID();when(browser.result(1,id)).thenReturn(new OrderEditBrowserService.BrowserResult(id,"受付済み",false,false,
            new OrderEditBrowserService.NextStep(OrderEditBrowserService.NextAction.AUTO_CONTINUE_ALLOWED,"VERIFIED_INITIAL_QUEUE")));
        var response=mvc.perform(get(URL+"/result").param("admission",id.toString()).with(user(customer)))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(content().string(containsString("sessionStorage")))
            .andExpect(content().string(containsString("name=\"_csrf\"")))
            .andExpect(content().string(containsString("name=\"automatic\" value=\"false\"")))
            .andExpect(content().string(containsString("受付済み処理を継続する"))).andReturn().getResponse();
        String nonce=response.getContentAsString().split("nonce=\"")[1].split("\"")[0];
        assertThat(response.getHeader("Content-Security-Policy")).contains("'nonce-"+nonce+"'");
        verify(browser,never()).continueAutomatically(anyLong(),any());verify(browser,never()).continueAdmission(anyLong(),any());
    }
    @ParameterizedTest @ValueSource(strings={"DISPLAY_ONLY","OBSERVE_ONLY","MANUAL_REVIEW_REQUIRED"})
    void nonAutomaticActionsNeverRenderAutoScriptEvenWhenManualContinuationIsAllowed(String action)throws Exception{
        UUID id=UUID.randomUUID();when(browser.result(1,id)).thenReturn(new OrderEditBrowserService.BrowserResult(id,"確認待ち",true,false,
            new OrderEditBrowserService.NextStep(OrderEditBrowserService.NextAction.valueOf(action),"SAVED_STATE")));
        var response=mvc.perform(get(URL+"/result").param("admission",id.toString()).with(user(customer))).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("sessionStorage").contains("受付済み処理を継続する");
    }

    @Test void requeueResultIncludesBoundedBackoffAndVersionWithoutGetSideEffects()throws Exception{
        UUID id=UUID.randomUUID();when(browser.result(1,id)).thenReturn(new OrderEditBrowserService.BrowserResult(id,"受付済み",true,false,
            new OrderEditBrowserService.NextStep(OrderEditBrowserService.NextAction.AUTO_RESUME_REAUTH_ALLOWED,"OWNED",3L,5000,60000,"REAUTH:3:4")));
        mvc.perform(get(URL+"/result").param("admission",id.toString()).with(user(customer))).andExpect(status().isOk())
            .andExpect(content().string(containsString("data-wait-ms=\"5000\"")))
            .andExpect(content().string(containsString("data-stage=\"REAUTH:3:4\"")))
            .andExpect(content().string(containsString("name=\"automaticVersion\" value=\"3\"")));
        verify(browser,never()).continueAutomatically(anyLong(),any(),any());
    }
}
