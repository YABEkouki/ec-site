package com.example.ecsite.controller;

import java.time.LocalDateTime;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.dao.DataAccessResourceFailureException;
import com.example.ecsite.config.SecurityConfig;
import com.example.ecsite.security.*;
import com.example.ecsite.service.*;
import com.example.ecsite.service.payment.*;
import com.example.ecsite.service.order.evidence.OrderEditBrowserService;
import com.example.ecsite.service.order.history.*;
import com.example.ecsite.exception.OrderNotFoundException;
import com.example.ecsite.entity.Order;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.example.ecsite.service.order.history.CustomerOrderChangeHistoryWebFixture.*;

@WebMvcTest(OrderController.class)
@Import({SecurityConfig.class,CustomerOrderChangeHistoryViewService.class,CustomerOrderChangeHistoryReadService.class})
class CustomerOrderChangeHistoryWebTest {
    @Autowired MockMvc mvc;
    @MockitoBean CustomerOrderChangeEvidenceReader reader;
    @MockitoBean OrderService orders;
    @MockitoBean OrderStatusHistoryService statuses;
    @MockitoBean ShippingAddressService addresses;
    @MockitoBean OrderShippingAddressHistoryService addressHistory;
    @MockitoBean OrderContentChangeHistoryService legacyHistory;
    @MockitoBean PaymentCancellationService cancellation;
    @MockitoBean PaymentService payments;
    @MockitoBean PaymentAuthorizationService authorization;
    @MockitoBean OrderEditBrowserService browser;
    @MockitoBean AdminUserDetailsService admins;
    @MockitoBean CustomUserDetailsService customers;
    @MockitoBean AdminAuthenticationSuccessHandler adminSuccess;
    @MockitoBean CustomerAuthenticationSuccessHandler customerSuccess;
    static final String URL="/orders/1/content-changes";
    final CustomUserDetails customer=new CustomUserDetails(2L,"buyer","unused",true,List.of(new SimpleGrantedAuthority("ROLE_USER")));
    String show(String scenario,String query,int status)throws Exception {
        when(reader.readForCustomer(1,2)).thenReturn(bundle(scenario));
        var result=mvc.perform(get(URL+query).with(user(customer))).andExpect(status().is(status)).andReturn();
        assertThat(result.getModelAndView().getModel()).containsOnlyKeys("historyPage", org.springframework.validation.BindingResult.MODEL_KEY_PREFIX + "historyPage");
        var binding = (org.springframework.validation.BindingResult) result.getModelAndView().getModel().get(org.springframework.validation.BindingResult.MODEL_KEY_PREFIX + "historyPage");
        assertThat(binding.getTarget()).isSameAs(result.getModelAndView().getModel().get("historyPage"));
        assertThat(result.getModelAndView().getModel().get("historyPage")).isInstanceOf(CustomerOrderChangeHistoryPage.class);
        verifyNoInteractions(orders,legacyHistory,payments,authorization,cancellation,browser);
        return result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }
    @ParameterizedTest @ValueSource(strings={"retained","reauthorized","decrease","removed","oldReauthorization","mixed","replacement"})
    void realSavedEvidenceRendersCustomerPage(String scenario)throws Exception {
        String html=show(scenario,"",200);
        assertThat(html).contains("注文番号","日本時間","変更前数量","変更後数量","変更前単価","変更後単価","送料","うち消費税","注文合計","/orders/1").doesNotContain("確定した注文変更履歴はありません","admission_id","operation_id","evidence_digest","pfw_");
        assertThat(html).contains("class=\"change-entry\"");verify(reader).readForCustomer(1,2);
    }
    @Test void normalEmptyDoesNotShowUnavailableWarning()throws Exception {
        assertThat(show("empty","",200)).contains("確定した注文変更履歴はありません").doesNotContain("注文変更履歴を表示できません","class=\"change-entry\"");
    }
    @Test void partialWarningPersistsAcrossPages()throws Exception {
        for(String query:List.of("?page=1","?page=2","?page=999")) assertThat(show("partial",query,200)).contains("一部の注文変更履歴を表示できません","class=\"change-entry\"").doesNotContain("確定した注文変更履歴はありません");
    }
    @ParameterizedTest @ValueSource(strings={"none","limit"}) void unavailableEvidenceIsNotAnEmptyHistory(String scenario)throws Exception {
        String html=show(scenario,"",200);assertThat(html).contains(scenario.equals("none")?"注文変更履歴を表示できません":"注文変更履歴の件数またはデータ量が多いため、現在表示できません").doesNotContain("確定した注文変更履歴はありません","class=\"change-entry\"");
    }
    @Test void outageReturns503WithoutPrivateExceptionText()throws Exception {
        when(reader.readForCustomer(1,2)).thenThrow(new DataAccessResourceFailureException("SQL private-payload-47"));
        var response=mvc.perform(get(URL).with(user(customer))).andExpect(status().isServiceUnavailable()).andReturn().getResponse();
        assertThat(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("しばらくしてから再度お試しください").doesNotContain("private-payload-47","SQL","確定した注文変更履歴はありません");
    }
    @Test void internalContractRemains500()throws Exception {
        when(reader.readForCustomer(1,2)).thenThrow(new IllegalStateException("SQL private-contract-47"));
        var response=mvc.perform(get(URL).with(user(customer))).andExpect(status().isInternalServerError()).andReturn().getResponse();
        assertThat(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("private-contract-47","SQL");
    }
    @Test void foreignAndMissingOrderHaveSameSafe404()throws Exception {
        when(reader.readForCustomer(1,2)).thenThrow(new OrderNotFoundException(123456789L));
        when(reader.readForCustomer(2,2)).thenThrow(new OrderNotFoundException(987654321L));
        var missing=mvc.perform(get(URL).with(user(customer))).andExpect(status().isNotFound()).andReturn();
        assertThat(missing.getModelAndView().getModel().values()).allSatisfy(value -> {
            if (value instanceof org.springframework.validation.BindingResult binding) assertThat(binding.getTarget()).isInstanceOf(OrderController.HistoryRequestError.class);
            else assertThat(value).isInstanceOf(OrderController.HistoryRequestError.class);
        });
        String first=missing.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String second=mvc.perform(get("/orders/2/content-changes").with(user(customer))).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(first).isEqualTo(second).doesNotContain("123456789","987654321");verifyNoInteractions(legacyHistory,orders);
    }
    @Test void anonymousUsesExistingLoginRedirect()throws Exception {mvc.perform(get(URL)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));verifyNoInteractions(reader,legacyHistory,orders);}
    @Test void adminRoleCannotReadCustomerHistory()throws Exception {mvc.perform(get(URL).with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());verifyNoInteractions(reader,legacyHistory,orders);}
    @Test void invalidPrincipalCannotAcquireEvidence()throws Exception {mvc.perform(get(URL).with(user("not-custom-principal").roles("USER"))).andExpect(status().isForbidden());verifyNoInteractions(reader);}
    @Test void authorizationFailureIsNever503()throws Exception {when(reader.readForCustomer(1,2)).thenThrow(new AccessDeniedException("private-denial"));var r=mvc.perform(get(URL).with(user(customer))).andExpect(status().isForbidden()).andReturn();assertThat(r.getResponse().getContentAsString()).doesNotContain("private-denial");}
    @ParameterizedTest @ValueSource(strings={"0","-1","2147483648","no-number","1.5"}) void invalidPageIs400(String page)throws Exception {mvc.perform(get(URL).param("page",page).with(user(customer))).andExpect(status().isBadRequest());verifyNoInteractions(reader);}
    @Test void twentyPerPageAndStableLinks()throws Exception {
        String first=show("many","?page=1",200);assertThat(first.split("class=\"change-entry\"",-1)).hasSize(21);assertThat(first).contains("href=\"/orders/1/content-changes?page=2\"","1 / 3").doesNotContain("rel=\"prev\"");
        String middle=show("many","?page=2",200);assertThat(middle).contains("href=\"/orders/1/content-changes?page=1\"","href=\"/orders/1/content-changes?page=3\"","2 / 3");
        String last=show("many","?page=999",200);assertThat(last.split("class=\"change-entry\"",-1)).hasSize(2);assertThat(last).contains("3 / 3").doesNotContain("rel=\"next\"");
    }
    @Test void deletionSideIsAbsentNotStoredZero()throws Exception {String html=show("removed","",200);assertThat(html).contains("削除","—").doesNotContain("0（削除）","0円（削除）");}
    @Test void japanTimeComesFromDisplayDto()throws Exception {var page=show("decrease","",200);assertThat(page).contains("2026/01/01 09:00","日本時間");}
    @Test void productAndChargeNamesCannotBecomeHtml()throws Exception {
        String html=show("xss","",200);assertThat(html).contains("&lt;script&gt;").doesNotContain("<script>","old-charge","new-charge");
    }
    @Test void detailRetainsHistoryLink()throws Exception {
        var order=new Order(2L,3000,LocalDateTime.of(2026,1,1,9,0),LocalDateTime.of(2026,1,1,14,0));
        org.springframework.test.util.ReflectionTestUtils.setField(order,"id",1L);
        when(orders.findOrderByIdAndUserId(1L,2L)).thenReturn(order);
        var html=mvc.perform(get("/orders/1").with(user(customer))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(html).contains("href=\"/orders/1/content-changes\"","注文内容変更履歴を見る");verifyNoInteractions(reader);
    }
}
