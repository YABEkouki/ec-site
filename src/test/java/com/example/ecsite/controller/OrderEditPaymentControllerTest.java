package com.example.ecsite.controller;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import com.example.ecsite.config.SecurityConfig;
import com.example.ecsite.security.*;
import com.example.ecsite.service.*;
import com.example.ecsite.service.order.evidence.*;
import com.example.ecsite.service.orderchange.OrderChangeBrowserService.Card;
import com.example.ecsite.exception.OrderNotFoundException;
import com.example.ecsite.web.OrderChangePaymentPrivacyFilter;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@WebMvcTest({OrderEditPaymentController.class,OrderChangePaymentController.class})
@Import({SecurityConfig.class,OrderChangePaymentPrivacyFilter.class})
class OrderEditPaymentControllerTest {
    @Autowired MockMvc mvc;@MockitoBean OrderEditBrowserService browser;
    @MockitoBean com.example.ecsite.service.orderchange.OrderChangeBrowserService legacyBrowser;
    @MockitoBean com.example.ecsite.service.orderchange.OrderIncreaseService increases;
    @MockitoBean AdminUserDetailsService adminUserDetailsService;@MockitoBean CustomUserDetailsService customUserDetailsService;
    @MockitoBean AdminAuthenticationSuccessHandler adminAuthenticationSuccessHandler;@MockitoBean CustomerAuthenticationSuccessHandler customerAuthenticationSuccessHandler;
    UUID receipt=UUID.randomUUID();String url(){return "/orders/1/edit/"+receipt+"/payment";}
    final CustomUserDetails customer=new CustomUserDetails(2L,"buyer","unused",true,List.of(new SimpleGrantedAuthority("ROLE_USER")));
    @Test void explicitPostUsesExistingWidgetAndTrustedContinuation()throws Exception{
        when(browser.startCard(1,receipt)).thenReturn(Optional.of(new Card("pk_test_dummy","widget_secret","http://localhost:8080"+url()+"/return")));
        mvc.perform(post(url()).with(user(customer)).with(csrf())).andExpect(status().isOk()).andExpect(view().name("orders/increase-payment"))
            .andExpect(header().string("Referrer-Policy","no-referrer")).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(content().string(containsString("PayjpPayments"))).andExpect(content().string(containsString(url()+"/continue")))
            .andExpect(content().string(not(containsString("/increase/"))))
            .andExpect(model().attribute("feature125AutoContinue",true))
            .andExpect(content().string(containsString("HTMLFormElement.prototype.submit.call(form)")))
            .andExpect(content().string(containsString("name=\"_csrf\"")));
    }
    @Test void missingCsrfCannotStartOrContinue()throws Exception{
        mvc.perform(post(url()).with(user(customer))).andExpect(status().isForbidden());
        mvc.perform(post(url()+"/continue").with(user(customer))).andExpect(status().isForbidden());verifyNoInteractions(browser);
    }
    @Test void returnGetNeverTrustsSdkSuccessOrStartsContinuation()throws Exception{
        mvc.perform(get(url()+"/return?client_secret=private_callback_value&status=success").with(user(customer)).header("Referer","https://example.test/private_callback_value"))
            .andExpect(status().isOk()).andExpect(content().string(not(containsString("private_callback_value"))))
            .andExpect(content().string(containsString("history.replaceState"))).andExpect(content().string(containsString(url()+"/continue")));
        verify(browser).admission(1,receipt);verify(browser,never()).continueAdmission(anyLong(),any());verify(browser,never()).startCard(anyLong(),any());
    }
    @Test void repeatedOrUnavailableStartReturnsToPersistedResult()throws Exception{
        when(browser.startCard(1,receipt)).thenReturn(Optional.empty());
        mvc.perform(post(url()).with(user(customer)).with(csrf())).andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/orders/1/edit/result?admission="+receipt));
    }
    @Test void sharedWidgetLinksIncludeContextPath()throws Exception{
        when(browser.startCard(1,receipt)).thenReturn(Optional.of(new Card("pk_test_dummy","widget_secret","http://localhost:8080"+url()+"/return")));
        mvc.perform(post("/shop"+url()).contextPath("/shop").with(user(customer)).with(csrf()))
            .andExpect(status().isOk()).andExpect(content().string(containsString("action=\"/shop"+url()+"/continue\"")))
            .andExpect(content().string(containsString("href=\"/shop/orders/1/edit/result?admission="+receipt)));
    }
    @Test void unauthenticatedCallbackCannotPersistSdkCredentialsInSavedRequest()throws Exception{
        var session=new org.springframework.mock.web.MockHttpSession();
        mvc.perform(get(url()+"/return?client_secret=callback_secret&status=success").session(session)
            .header("Referer","https://example.test/callback_secret")).andExpect(status().is3xxRedirection());
        var saved=(org.springframework.security.web.savedrequest.SavedRequest)session.getAttribute("SPRING_SECURITY_SAVED_REQUEST");
        org.assertj.core.api.Assertions.assertThat(saved).isNotNull();
        org.assertj.core.api.Assertions.assertThat(saved.getRedirectUrl()).doesNotContain("callback_secret","client_secret");
        org.assertj.core.api.Assertions.assertThat(saved.getParameterMap()).isEmpty();
        org.assertj.core.api.Assertions.assertThat(saved.getHeaderValues("Referer")).isEmpty();verifyNoInteractions(browser);
    }
    @Test void foreignReceiptIsNotFound()throws Exception{
        when(browser.startCard(1,receipt)).thenThrow(new OrderNotFoundException(1L));
        mvc.perform(post(url()).with(user(customer)).with(csrf())).andExpect(status().isNotFound());
    }
    @Test void returnEnablesNonceProtectedAutomaticPostButGetRemainsReadOnly()throws Exception{
        var response=mvc.perform(get(url()+"/return").with(user(customer)))
            .andExpect(status().isOk()).andExpect(model().attribute("feature125AutoContinue",true))
            .andExpect(content().string(containsString("window.orderEditPaymentContinue();")))
            .andExpect(content().string(containsString("name=\"_csrf\"")))
            .andReturn().getResponse();
        String nonce=response.getContentAsString().split("nonce=\"")[1].split("\"")[0];
        org.assertj.core.api.Assertions.assertThat(response.getHeader("Content-Security-Policy")).contains("'nonce-"+nonce+"'");
        mvc.perform(get(url()+"/return").with(user(customer))).andExpect(status().isOk());
        verify(browser,times(2)).admission(1,receipt);verify(browser,never()).continueAdmission(anyLong(),any());
    }
    @Test void feature124SharedScreensKeepManualContinuation()throws Exception{
        String legacy="/orders/1/increase/"+receipt+"/payment";
        when(legacyBrowser.start(1,2,receipt)).thenReturn(Optional.of(new Card("pk_test_dummy","dummy","http://localhost"+legacy+"/return")));
        mvc.perform(post(legacy).with(user(customer)).with(csrf())).andExpect(status().isOk())
            .andExpect(model().attributeDoesNotExist("feature125AutoContinue"))
            .andExpect(content().string(not(containsString("HTMLFormElement.prototype.submit.call(form)"))));
        mvc.perform(get(legacy+"/return").with(user(customer))).andExpect(status().isOk())
            .andExpect(content().string(not(containsString("window.orderEditPaymentContinue"))));
        verifyNoInteractions(browser);
    }
    @Test void repeatedContinuationUsesSameSavedAdmission()throws Exception{
        for(int i=0;i<2;i++)mvc.perform(post(url()+"/continue").with(user(customer)).with(csrf()))
            .andExpect(redirectedUrl("/orders/1/edit/result?admission="+receipt));
        verify(browser,times(2)).continueAdmission(1,receipt);verify(browser,never()).startCard(anyLong(),any());
    }
    @Test void foreignReturnAndContinuationAreRejected()throws Exception{
        when(browser.admission(1,receipt)).thenThrow(new OrderNotFoundException(1L));
        doThrow(new OrderNotFoundException(1L)).when(browser).continueAdmission(1,receipt);
        mvc.perform(get(url()+"/return").with(user(customer))).andExpect(status().isNotFound());
        mvc.perform(post(url()+"/continue").with(user(customer)).with(csrf())).andExpect(status().isNotFound());
    }

    @Test void automaticResultPostUsesSeparateFailClosedPathAndStillRequiresCsrf()throws Exception{
        mvc.perform(post(url()+"/continue").param("automatic","true").with(user(customer))).andExpect(status().isForbidden());verifyNoInteractions(browser);
        mvc.perform(post(url()+"/continue").param("automatic","true").with(user(customer)).with(csrf()))
            .andExpect(redirectedUrl("/orders/1/edit/result?admission="+receipt));
        verify(browser).continueAutomatically(1,receipt);verify(browser,never()).continueAdmission(anyLong(),any());
    }

    @Test void automaticRequeueVersionIsPreservedByPrivacyFilterAndBoundToExistingReceipt()throws Exception{
        mvc.perform(post(url()+"/continue").param("automatic","true").param("automaticVersion","3").with(user(customer)).with(csrf()))
            .andExpect(redirectedUrl("/orders/1/edit/result?admission="+receipt));
        verify(browser).continueAutomatically(1,receipt,3L);verify(browser,never()).continueAdmission(anyLong(),any());
    }
}
