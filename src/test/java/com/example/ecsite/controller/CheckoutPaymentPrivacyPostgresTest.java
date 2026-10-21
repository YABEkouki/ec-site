package com.example.ecsite.controller;

import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.payment.*;
import com.example.ecsite.repository.*;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.OrderStatusHistoryService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Guarded by IsolationContextCustomizerFactory before any DataSource/Flyway connection. */
@SpringBootTest(properties={"logging.level.root=WARN","spring.jpa.show-sql=false",
    "app.payment.discrepancy-audit.notification.manual-resend-scheduler-enabled=false"})
@AutoConfigureMockMvc
@com.example.ecsite.support.CaptureConnectionTest
class CheckoutPaymentPrivacyPostgresTest {
    @Autowired MockMvc mvc;
    @Autowired OrderRepository orders;
    @Autowired UserRepository users;
    @Autowired PaymentRepository payments;
    @Autowired PaymentTransactionRepository transactions;
    @Autowired OrderStatusHistoryService histories;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PaymentGateway gateway;
    static final String SECRET="synthetic_checkout_marker";
    record Fixture(long order,long user,long payment,String flow){}
    Fixture fixture(){return new TransactionTemplate(manager).execute(s->{
        var now=orders.findCurrentTimeInTokyo();String key=UUID.randomUUID().toString();
        var customer=new User();customer.setUsername("privacy-"+key);customer.setPassword("unused");customer.setEnabled(true);customer.setCreatedAt(now);customer.setUpdatedAt(now);users.saveAndFlush(customer);
        var order=new Order(customer.getId(),750,now,now.plusHours(1));orders.saveAndFlush(order);
        histories.record(order,null,OrderStatus.ORDERED,OrderStatusHistoryActorType.USER,customer.getId(),customer.getUsername(),null);
        var payment=new Payment(order,PaymentProvider.PAYJP,PaymentMethod.CARD,750,now);payment.setProviderPaymentId("pfw_"+key,now);payments.saveAndFlush(payment);
        var auth=new PaymentTransaction(payment,PaymentTransactionType.AUTHORIZE,750,0,key,now,PaymentTransactionPurpose.INITIAL_ORDER_AUTHORIZATION);transactions.saveAndFlush(auth);
        return new Fixture(order.getId(),customer.getId(),payment.getId(),payment.getProviderPaymentId());
    });}
    MockHttpSession session(Fixture f){var session=new MockHttpSession();session.setAttribute("checkoutOrderId",f.order());session.setAttribute("checkoutPaymentId",f.payment());return session;}
    org.springframework.test.web.servlet.request.RequestPostProcessor customer(long id){return user(new CustomUserDetails(id,"fixture","unused",true,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))));}
    void observe(Fixture f,AuthorizationResultStatus status){when(gateway.retrieveAuthorization(f.flow())).thenReturn(new AuthorizationRecovery(new AuthorizationResult(status,f.flow(),null,null),SECRET));}

    @Autowired org.springframework.context.ApplicationContext context;
    @Test void maintenanceFlagsStopEveryPaymentScheduler() {
        assertThat(context.getBeansOfType(com.example.ecsite.service.payment.PaymentReconciliationScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(com.example.ecsite.service.payment.PaymentDiscrepancyAuditScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(com.example.ecsite.service.payment.PaymentAuditNotificationScheduler.class)).isEmpty();
        assertThat(context.getBean(com.example.ecsite.service.payment.PaymentAuditNotificationManualResendScheduler.class).isRunning()).isFalse();
    }

    @Test void sameFlowGetHasNoSessionCredentialAndNoFinancialPost() throws Exception {
        var f=fixture();observe(f,AuthorizationResultStatus.REQUIRES_PAYMENT_METHOD);var session=session(f);
        session.setAttribute("checkoutPaymentClientSecret",SECRET); // Rolling upgrade removes old credentials.
        for(int i=0;i<2;i++){
            var result=mvc.perform(get("/checkout/payment").session(session).with(customer(f.user())))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().string("Referrer-Policy","no-referrer"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(SECRET))).andReturn();
            assertThat(result.getModelAndView().getModel().get("card").toString()).doesNotContain(SECRET);
            String html=result.getResponse().getContentAsString();
            assertThat(html).contains("widgets.confirmPayment","navigator.locks.request","localStorage","nonce=").doesNotContain("console.error","result.error.message");
        }
        assertThat(session.getAttribute("checkoutPaymentClientSecret")).isNull();
        verify(gateway,times(2)).retrieveAuthorization(f.flow());
        verify(gateway,never()).prepareAuthorization(any());
    }

    @Test void callbackScrubsBeforeSecurityAndNeverCallsGateway() throws Exception {
        var f=fixture();var session=session(f);
        String callback="/checkout/payment/return?payment_flow_client_secret="+SECRET+"&status=succeeded&payment_flow_id=evil";
        mvc.perform(get(callback).session(session).header("Referer","https://example.invalid/?"+SECRET))
            .andExpect(status().isFound());
        var saved=(org.springframework.security.web.savedrequest.SavedRequest)session.getAttribute("SPRING_SECURITY_SAVED_REQUEST");
        assertThat(saved).isNotNull();assertThat(saved.getRedirectUrl()).doesNotContain(SECRET,"payment_flow_client_secret");
        assertThat(saved.getParameterMap()).isEmpty();assertThat(saved.getHeaderValues("Referer")).isEmpty();
        var html=mvc.perform(get(callback).session(session).with(customer(f.user())))
            .andExpect(status().isOk()).andExpect(view().name("checkout/payment-return"))
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(header().string("Referrer-Policy","no-referrer"))
            .andExpect(header().string("Content-Security-Policy",org.hamcrest.Matchers.containsString("default-src 'none'")))
            .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("history.replaceState","/checkout/payment/continue","_csrf").doesNotContain(SECRET,"evil","https://","src=","<link");
        verifyNoInteractions(gateway);
    }

    @Test void ownerPaymentAndCsrfAreCheckedBeforeDisclosureOrApplication() throws Exception {
        var f=fixture();var session=session(f);
        mvc.perform(get("/checkout/payment").session(session).with(customer(f.user()+999))).andExpect(status().isNotFound());
        mvc.perform(get("/checkout/payment/return").session(session).with(customer(f.user()+999))).andExpect(status().isNotFound());
        mvc.perform(post("/checkout/payment/continue").session(session).with(customer(f.user()))).andExpect(status().isForbidden());
        var other=fixture();session.setAttribute("checkoutPaymentId",other.payment());
        mvc.perform(get("/checkout/payment").session(session).with(customer(f.user())))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(SECRET))));
        verifyNoInteractions(gateway);
    }

    @Test void actionProcessingAndUnknownRemainObservationOnly() throws Exception {
        for(var status:List.of(AuthorizationResultStatus.PENDING,AuthorizationResultStatus.REQUIRES_ACTION,AuthorizationResultStatus.REQUIRES_CONFIRMATION)){
            var f=fixture();observe(f,status);
            mvc.perform(get("/checkout/payment").session(session(f)).with(customer(f.user())))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(SECRET))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("widgets.confirmPayment"))));
            assertThat(jdbc.queryForObject("select status from orders where id=?",String.class,f.order())).isEqualTo("ORDERED");
        }
        var f=fixture();when(gateway.retrieveAuthorization(f.flow())).thenThrow(new PaymentGatewayException("synthetic-provider-failure"));
        mvc.perform(get("/checkout/payment").session(session(f)).with(customer(f.user()))).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("synthetic-provider-failure"))));
        verify(gateway,never()).prepareAuthorization(any());
    }

    @Test void serverObservationDeterminesSuccessNotForgedReturnParameters() throws Exception {
        var f=fixture();observe(f,AuthorizationResultStatus.PENDING);var session=session(f);
        mvc.perform(post("/checkout/payment/continue").session(session).with(customer(f.user())).with(csrf()).param("status","succeeded"))
            .andExpect(status().isFound()).andExpect(redirectedUrl("/checkout/payment"));
        assertThat(jdbc.queryForObject("select status from orders where id=?",String.class,f.order())).isEqualTo("ORDERED");
        observe(f,AuthorizationResultStatus.AUTHORIZED);
        mvc.perform(post("/checkout/payment/continue").session(session).with(customer(f.user())).with(csrf()))
            .andExpect(status().isFound()).andExpect(redirectedUrl("/checkout/complete"));
        assertThat(jdbc.queryForObject("select status from payments where id=?",String.class,f.payment())).isEqualTo("AUTHORIZED");
        mvc.perform(post("/checkout/payment/continue").session(session).with(customer(f.user())).with(csrf())).andExpect(redirectedUrl("/"));
        verify(gateway,times(2)).retrieveAuthorization(f.flow());
    }
}
