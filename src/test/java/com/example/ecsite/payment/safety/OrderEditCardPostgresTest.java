package com.example.ecsite.payment.safety;

import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.example.ecsite.payment.*;
import com.example.ecsite.payment.payjp.*;
import com.example.ecsite.payment.orderchange.OrderChangeRequestSender;
import com.example.ecsite.config.*;
import com.example.ecsite.service.order.evidence.*;
import com.example.ecsite.service.orderchange.OrderReauthorizationTransactions;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Explicit guarded commit-tracking PG17 runtime. Every provider operation is mocked. */
@SpringBootTest(properties={"logging.level.root=WARN","spring.jpa.show-sql=false"})
@com.example.ecsite.support.CaptureConnectionTest
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class OrderEditCardPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17")
        .withDatabaseName("isolated_f125_admission_"+UUID.randomUUID().toString().replace("-",""))
        .withUsername("admission_test").withPassword(UUID.randomUUID().toString())
        .withCommand("postgres","-c","track_commit_timestamp=on","-c","synchronous_commit=on","-c","fsync=on");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
        r.add("spring.flyway.url",POSTGRES::getJdbcUrl);r.add("spring.flyway.user",POSTGRES::getUsername);r.add("spring.flyway.password",POSTGRES::getPassword);
    }
    @Autowired OrderEditBrowserService browser;
    @Autowired JdbcTemplate jdbc;@Autowired OrderEditExecutionService execution;@Autowired OrderEditCardService cards;
    @Autowired OrderReauthorizationTransactions transactions;@Autowired CaptureConnectionProperties connection;@Autowired PayJpProperties provider;
    @MockitoBean PaymentGateway gateway;
    AtomicReference<String> state=new AtomicReference<>("requires_payment_method");AtomicBoolean cancelled=new AtomicBoolean();String flow;
    OrderEditAdmissionServicePostgresTest.Fixture f;UUID receipt,operation;int amount;
    @BeforeEach void fixture(TestInfo info){
        var db=OrderEditAdmissionFoundationPostgresTest.open(POSTGRES);db.schema="public";db.source.setCurrentSchema("public");
        OrderEditAdmissionServicePostgresTest.db=db;OrderEditAdmissionServicePostgresTest.jdbc=db.jdbc;
        LegacyOrderEvidenceEnrollmentPostgresTest.db=db;LegacyOrderEvidenceEnrollmentPostgresTest.jdbc=db.jdbc;
        OrderEditAdmissionFoundationPostgresTest.jdbc=jdbc;new OrderEditAdmissionFoundationPostgresTest().runtime(info);
        f=OrderEditAdmissionServicePostgresTest.fixture(false);var command=OrderEditAdmissionServicePostgresTest.command(f,3,UUID.randomUUID());
        receipt=new OrderEditAdmissionService(db.source).submit(command).admissionId();assertThat(receipt).isNotNull();
        amount=command.confirmed().previewSnapshot().totalAmount();state.set("requires_payment_method");cancelled.set(false);
        when(gateway.observeOrderAuthorization(any())).thenAnswer(a->{outside();return MockOrderAuthorizationTransport.observe(a.getArgument(0));});
        when(gateway.sendOrderChangeRequest(any())).thenAnswer(a->{
            outside();var request=((OrderChangeRequestSender.Dispatch)a.getArgument(0)).request().httpRequest();operation=request.operationId();flow="pfw_edit_"+operation.toString().replace("-","");
            if(request.kind().equals("CREATE"))return observation(flow,"requires_payment_method",amount,Map.of());
            if(request.kind().equals("OLD_CANCEL")){cancelled.set(true);return observation(request.flowId(),"canceled",6000,Map.of());}
            throw new AssertionError("Unexpected financial request");
        });
        when(gateway.observeOrderChangeFlow(anyString())).thenAnswer(a->{outside();String id=a.getArgument(0);
            if(id.equals(flow))return observation(id,state.get(),amount,Map.of("order_id",Long.toString(f.order()),"order_change_operation_id",operation.toString()));
            return observation(id,cancelled.get()?"canceled":"requires_capture",6000,Map.of());
        });
        assertThat(execution.execute(receipt).status()).isEqualTo(OrderEditExecutionService.Status.HELD);
        when(gateway.retrieveAuthorization(flow)).thenAnswer(a->{outside();return new AuthorizationRecovery(new AuthorizationResult(AuthorizationResultStatus.REQUIRES_PAYMENT_METHOD,null,null,null),"secret_for_widget_only");});
    }
    void outside(){assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();}
    PayJpPaymentFlowObservation observation(String id,String status,int total,Map<String,Object> metadata){
        var fields=new LinkedHashMap<String,Object>();fields.put("id",id);fields.put("status",status);fields.put("amount",total);fields.put("currency","jpy");fields.put("capture_method","manual");fields.put("livemode",false);fields.put("amount_received",0);fields.put("amount_capturable",status.equals("requires_capture")?total:0);fields.put("metadata",metadata);
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();var raw=mapper.readTree(mapper.writeValueAsString(fields));
        return mapper.treeToValue(raw,PayJpPaymentFlowObservation.class).withOldCancellationEvidence(OldAuthorizationCancellationEvidence.fromJson(raw,connection.requireIdentity(provider)));
    }
    @AfterEach void clearActor(){org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    void actor(){var p=new com.example.ecsite.security.CustomUserDetails(f.customer(),"buyer","unused",true,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(p,"unused",p.getAuthorities()));}
    @Test void firstCardIssueIsOwnedOneTimeAndLegacyEntryStillRejects(){
        assertThat(transactions.issueBrowserConfirmation(f.order(),operation,observation(flow,"requires_payment_method",amount,Map.of()))).isFalse();
        var card=cards.start(f.order(),f.customer(),receipt);assertThat(card).isPresent();assertThat(card.get().toString()).doesNotContain("secret_for_widget_only");
        assertThat(card.get().returnUrl()).endsWith("/orders/"+f.order()+"/edit/"+receipt+"/payment/return");
        assertThat(cards.start(f.order(),f.customer(),receipt)).isEmpty();verify(gateway,times(1)).retrieveAuthorization(flow);
        assertThat(jdbc.queryForObject("select browser_confirmation_issued_at is not null from order_change_operations where id=?",Boolean.class,operation)).isTrue();
        assertThat(jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order())).isZero();
    }
    @Test void foreignIdentityCannotRetrieveSecret(){
        assertThat(cards.start(f.order(),f.customer()+100000,receipt)).isEmpty();verify(gateway,never()).retrieveAuthorization(anyString());
        assertThat(cards.start(f.order(),f.customer(),UUID.randomUUID())).isEmpty();
    }
    @Test void serverGetAfterAuthenticationCompletesWithoutDuplicatePosts(){
        actor();assertThat(browser.result(f.order(),receipt).canAuthenticate()).isTrue();
        assertThat(browser.startCard(f.order(),receipt)).isPresent();state.set("requires_capture");
        var first=execution.reconcile(receipt);
        assertThat(first.status()).isIn(OrderEditExecutionService.Status.HELD,OrderEditExecutionService.Status.COMPLETED);
        // Honor the existing retry backoff instead of treating an early explicit continuation as failure.
        jdbc.queryForObject("select pg_sleep(greatest(0,extract(epoch from (next_attempt_at-clock_timestamp())))+0.02) from order_edit_execution_controls where admission_id=?",Object.class,receipt);
        browser.continueAdmission(f.order(),receipt);assertThat(browser.result(f.order(),receipt).label()).isEqualTo("変更完了");
        browser.continueAdmission(f.order(),receipt);assertThat(browser.result(f.order(),receipt).label()).isEqualTo("変更完了");
        verify(gateway,times(2)).sendOrderChangeRequest(any());
        assertThat(jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order())).isEqualTo(1);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"requires_payment_method","requires_action","processing","payment_failed"})
    void interruptedFailedOrPendingAuthenticationNeverAppliesOrReposts(String status){
        actor();assertThat(browser.startCard(f.order(),receipt)).isPresent();state.set(status);clearInvocations(gateway);
        browser.continueAdmission(f.order(),receipt);
        assertThat(browser.result(f.order(),receipt).label()).isNotEqualTo("変更完了");
        assertThat(jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order())).isZero();
        verify(gateway,never()).sendOrderChangeRequest(any());verify(gateway,never()).retrieveAuthorization(anyString());
    }
    @Test void unknownGetLeavesReceiptAndFinancialRequestUnchanged(){
        actor();assertThat(browser.startCard(f.order(),receipt)).isPresent();clearInvocations(gateway);
        when(gateway.observeOrderChangeFlow(anyString())).thenThrow(new IllegalStateException("mock transport unknown"));
        browser.continueAdmission(f.order(),receipt);
        assertThat(browser.result(f.order(),receipt).label()).isNotEqualTo("変更完了");
        assertThat(jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order())).isZero();
        verify(gateway,never()).sendOrderChangeRequest(any());verify(gateway,never()).retrieveAuthorization(anyString());
    }
    @Test void stoppedRuntimeCannotIssueBrowserSecret(){
        jdbc.update("update order_edit_runtime_control set state='STOPPED',generation_id=null,version=version+1 where id=1");
        clearInvocations(gateway);assertThat(cards.start(f.order(),f.customer(),receipt)).isEmpty();verifyNoInteractions(gateway);
    }
    @Test void staleOrWrongMetadataObservationCannotIssueMarker(){
        var target=transactions.prepareOrderEditBrowser(f.order(),f.customer(),receipt).orElseThrow();
        var wrong=observation(flow,"requires_payment_method",amount,Map.of("order_id",Long.toString(f.order()+1),"order_change_operation_id",operation.toString()));
        assertThat(transactions.issueOrderEditBrowser(target,wrong,System.nanoTime())).isFalse();
        var correct=observation(flow,"requires_payment_method",amount,Map.of("order_id",Long.toString(f.order()),"order_change_operation_id",operation.toString()));
        assertThat(transactions.issueOrderEditBrowser(target,correct,System.nanoTime()-6_000_000_000L)).isFalse();
        assertThat(jdbc.queryForObject("select browser_confirmation_issued_at is null from order_change_operations where id=?",Boolean.class,operation)).isTrue();
    }

    @Test void concurrentBrowserStartsExposeAtMostOneWidget()throws Exception{
        var start=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            var first=pool.submit(()->{start.await();return cards.start(f.order(),f.customer(),receipt).isPresent();});
            var second=pool.submit(()->{start.await();return cards.start(f.order(),f.customer(),receipt).isPresent();});start.countDown();
            assertThat(List.of(first.get(20,java.util.concurrent.TimeUnit.SECONDS),second.get(20,java.util.concurrent.TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true,false);
            assertThat(jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order())).isZero();
            verify(gateway,times(1)).sendOrderChangeRequest(any());
        }finally{pool.shutdownNow();}
    }

}
