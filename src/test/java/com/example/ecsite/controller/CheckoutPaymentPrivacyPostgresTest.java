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
@org.testcontainers.junit.jupiter.Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class CheckoutPaymentPrivacyPostgresTest {
    // These fixtures commit across controller/service transaction boundaries. Keep them
    // out of the shared launcher database used by transactional repository tests.
    @org.testcontainers.junit.jupiter.Container
    static final org.testcontainers.postgresql.PostgreSQLContainer POSTGRES = new org.testcontainers.postgresql.PostgreSQLContainer("postgres:17")
            .withDatabaseName("checkout_privacy").withUsername("checkout_privacy").withPassword(UUID.randomUUID().toString());
    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl); r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword); r.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user", POSTGRES::getUsername); r.add("spring.flyway.password", POSTGRES::getPassword);
    }
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
        var payment=new Payment(order,PaymentProvider.PAYJP,PaymentMethod.CARD,750,now);payment.setProviderPaymentId("pfw_"+key,now);payments.saveAndFlush(payment);order.bindActivePayment(payment);orders.flush();
        var auth=new PaymentTransaction(payment,PaymentTransactionType.AUTHORIZE,750,0,key,now,PaymentTransactionPurpose.INITIAL_ORDER_AUTHORIZATION);transactions.saveAndFlush(auth);
        return new Fixture(order.getId(),customer.getId(),payment.getId(),payment.getProviderPaymentId());
    });}
    MockHttpSession session(Fixture f){var session=new MockHttpSession();session.setAttribute("checkoutOrderId",f.order());session.setAttribute("checkoutPaymentId",f.payment());return session;}
    org.springframework.test.web.servlet.request.RequestPostProcessor customer(long id){return user(new CustomUserDetails(id,"fixture","unused",true,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))));}
    void observe(Fixture f,AuthorizationResultStatus status){doReturn(new AuthorizationRecovery(new AuthorizationResult(status,f.flow(),null,null),SECRET)).when(gateway).retrieveAuthorization(f.flow());}

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
        assertThat(html).contains("history.replaceState","/checkout/payment/continue","_csrf","<link rel=\"stylesheet\" href=\"/css/style.css\">");
        assertThat(html.replace("<link rel=\"stylesheet\" href=\"/css/style.css\">", "")).doesNotContain(SECRET,"evil","https://","src=","<link");
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
        fixedPost(f,session)
            .andExpect(status().isFound()).andExpect(redirectedUrl("/checkout/payment/wait"));
        assertThat(jdbc.queryForObject("select status from orders where id=?",String.class,f.order())).isEqualTo("ORDERED");
        observe(f,AuthorizationResultStatus.AUTHORIZED);
        fixedPost(f,session)
            .andExpect(status().isFound()).andExpect(redirectedUrl("/checkout/complete"));
        assertThat(jdbc.queryForObject("select status from payments where id=?",String.class,f.payment())).isEqualTo("AUTHORIZED");
        fixedPost(f,session).andExpect(redirectedUrl("/"));
        verify(gateway,times(2)).retrieveAuthorization(f.flow());
    }
    @Test void returnPinsTargetAndAutomaticPostCompletesWithoutGetSideEffects() throws Exception {
        var f=fixture();var session=session(f);
        var page=mvc.perform(get("/checkout/payment/return").session(session).with(customer(f.user())))
            .andExpect(status().isOk()).andReturn();
        assertThat(page.getModelAndView().getModel().get("autoCheckAllowed")).isEqualTo(true);
        assertThat(page.getResponse().getContentAsString()).contains("expectedOrderId","expectedPaymentId","expectedTransactionId","data-auto=\"true\"","nonce=","_csrf");
        verifyNoInteractions(gateway);
        observe(f,AuthorizationResultStatus.AUTHORIZED);
        fixedPost(f,session).andExpect(redirectedUrl("/checkout/complete"));
        assertThat(jdbc.queryForObject("select status from payments where id=?",String.class,f.payment())).isEqualTo("AUTHORIZED");
        verify(gateway,times(1)).retrieveAuthorization(f.flow());verify(gateway,never()).prepareAuthorization(any());
        fixedPost(f,session).andExpect(redirectedUrl("/"));
    }
    @Test void waitingGetAndUnknownPostNeverAutomaticallyReobserve() throws Exception {
        var f=fixture();var session=session(f);observe(f,AuthorizationResultStatus.PENDING);
        fixedPost(f,session).andExpect(redirectedUrl("/checkout/payment/wait"));
        clearInvocations(gateway);
        for(int i=0;i<2;i++)mvc.perform(get("/checkout/payment/wait").session(session).with(customer(f.user())))
            .andExpect(status().isOk()).andExpect(model().attribute("autoCheckAllowed",false));
        verifyNoInteractions(gateway);
    }
    @Test void replacedTargetAndMissingTargetNeverReachGateway() throws Exception {
        var first=fixture();var second=fixture();var session=session(second);
        fixedPost(first,session).andExpect(redirectedUrl("/checkout/payment/wait"));
        mvc.perform(post("/checkout/payment/continue").session(session).with(customer(second.user())).with(csrf()))
            .andExpect(redirectedUrl("/checkout/payment/wait"));
        verifyNoInteractions(gateway);
    }

    @Test void failedObservationKeepsUnknownAndWaitGetNeverCallsProvider()throws Exception {
        var f=fixture();var session=session(f);when(gateway.retrieveAuthorization(f.flow())).thenThrow(new PaymentGatewayException("private-error"));
        fixedPost(f,session).andExpect(redirectedUrl("/checkout/payment/wait"));
        assertThat(jdbc.queryForObject("select status from payments where id=?",String.class,f.payment())).isEqualTo("PENDING");
        clearInvocations(gateway);
        mvc.perform(get("/checkout/payment/wait").session(session).with(customer(f.user())))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("data-auto=\"false\"")))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-error"))));
        verifyNoInteractions(gateway);
    }
    @Test void actionNotFinishedNeverCompletesOrReissuesAuthorization()throws Exception {
        var f=fixture();observe(f,AuthorizationResultStatus.REQUIRES_ACTION);fixedPost(f,session(f)).andExpect(redirectedUrl("/checkout/payment/wait"));
        assertThat(jdbc.queryForObject("select status from payments where id=?",String.class,f.payment())).isEqualTo("REQUIRES_ACTION");
        verify(gateway,never()).prepareAuthorization(any());
    }
    @Test void concurrentTabsOnlyApplyAuthorizationOnce()throws Exception {
        var f=fixture();observe(f,AuthorizationResultStatus.AUTHORIZED);var one=session(f);var two=session(f);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{var a=pool.submit(()->fixedPost(f,one));var b=pool.submit(()->fixedPost(f,two));
            a.get().andExpect(redirectedUrl("/checkout/complete"));b.get().andExpect(redirectedUrl("/checkout/complete"));
        }finally{pool.shutdownNow();}
        assertThat(jdbc.queryForObject("select count(*) from payment_transactions where payment_id=?",Integer.class,f.payment())).isOne();
        assertThat(jdbc.queryForObject("select count(*) from stock_movements where order_id=?",Integer.class,f.order())).isZero();
        verify(gateway,never()).prepareAuthorization(any());
    }
    @Test void staleTransactionCsrfAndForeignOwnerCannotRefresh()throws Exception {
        var f=fixture();var session=session(f);
        mvc.perform(post("/checkout/payment/continue").session(session).with(customer(f.user())).with(csrf())
            .param("expectedOrderId",Long.toString(f.order())).param("expectedPaymentId",Long.toString(f.payment())).param("expectedTransactionId","-1"))
            .andExpect(redirectedUrl("/checkout/payment/wait"));
        mvc.perform(get("/checkout/payment/wait").session(session).with(customer(f.user()+999))).andExpect(status().isNotFound());
        mvc.perform(get("/checkout/payment/return").session(session).with(anonymous())).andExpect(status().is3xxRedirection());
        mvc.perform(get("/checkout/payment/return").session(new MockHttpSession()).with(customer(f.user()))).andExpect(redirectedUrl("/"));
        mvc.perform(post("/checkout/payment/continue").session(session).with(customer(f.user()))).andExpect(status().isForbidden());
        verifyNoInteractions(gateway);
    }

    @Test void responseLossAfterSavedSuccessWaitsAndExplicitRetryIsIdempotent()throws Exception {
        var f=fixture();var session=session(f);
        when(gateway.retrieveAuthorization(f.flow())).thenAnswer(call->{
            new TransactionTemplate(manager).executeWithoutResult(tx->{var p=payments.findById(f.payment()).orElseThrow();p.markAuthorized(java.time.LocalDateTime.now());
                var auth=transactions.findByPaymentIdOrderByCreatedAtAscIdAsc(f.payment()).getFirst();auth.markSucceeded(f.flow(),java.time.LocalDateTime.now());});
            throw new PaymentGatewayException("synthetic-response-loss");
        });
        fixedPost(f,session).andExpect(redirectedUrl("/checkout/payment/wait"));
        clearInvocations(gateway);
        mvc.perform(get("/checkout/payment/wait").session(session).with(customer(f.user()))).andExpect(model().attribute("autoCheckAllowed",false));
        verifyNoInteractions(gateway);observe(f,AuthorizationResultStatus.AUTHORIZED);
        fixedPost(f,session).andExpect(redirectedUrl("/checkout/complete"));
        assertThat(jdbc.queryForObject("select count(*) from payment_transactions where payment_id=?",Integer.class,f.payment())).isOne();
        verify(gateway,never()).prepareAuthorization(any());
    }
    @Test void changedCheckoutDuringProviderObservationIsNotCleared()throws Exception {
        var f=fixture();var next=fixture();var session=session(f);
        when(gateway.retrieveAuthorization(f.flow())).thenAnswer(call->{session.setAttribute("checkoutOrderId",next.order());session.setAttribute("checkoutPaymentId",next.payment());
            return new AuthorizationRecovery(new AuthorizationResult(AuthorizationResultStatus.AUTHORIZED,f.flow(),null,null),SECRET);});
        fixedPost(f,session).andExpect(redirectedUrl("/checkout/complete"));
        assertThat(session.getAttribute("checkoutOrderId")).isEqualTo(next.order());assertThat(session.getAttribute("checkoutPaymentId")).isEqualTo(next.payment());
        assertThat(jdbc.queryForObject("select status from payments where id=?",String.class,next.payment())).isEqualTo("PENDING");
    }
    org.springframework.test.web.servlet.ResultActions fixedPost(Fixture f,MockHttpSession session)throws Exception {
        long tx=jdbc.queryForObject("select id from payment_transactions where payment_id=? and transaction_type='AUTHORIZE'",Long.class,f.payment());
        return mvc.perform(post("/checkout/payment/continue").session(session).with(customer(f.user())).with(csrf())
            .param("expectedOrderId",Long.toString(f.order())).param("expectedPaymentId",Long.toString(f.payment())).param("expectedTransactionId",Long.toString(tx)));
    }

}
