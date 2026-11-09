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
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@SpringBootTest(properties={"logging.level.root=WARN","spring.jpa.show-sql=false"})
@com.example.ecsite.support.CaptureConnectionTest
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class OrderEditCardPostgresTest {

    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired com.example.ecsite.service.payment.PayJpWebhookSyncService webhooks;
    @Autowired OrderEditBrowserService browser;
    @Autowired JdbcTemplate jdbc;@Autowired OrderEditExecutionService execution;@Autowired OrderEditCardService cards;
    @Autowired OrderReauthorizationTransactions transactions;@Autowired CaptureConnectionProperties connection;@Autowired PayJpProperties provider;
    @MockitoBean PaymentGateway gateway;
    AtomicReference<String> state=new AtomicReference<>("requires_payment_method");AtomicBoolean cancelled=new AtomicBoolean();String flow;
    OrderEditAdmissionServicePostgresTest.Fixture f;UUID receipt,operation;int amount;
    @BeforeEach void fixture(TestInfo info){
        var db=OrderEditAdmissionFoundationPostgresTest.open(null);db.schema="public";db.source.setCurrentSchema("public");
        OrderEditAdmissionServicePostgresTest.db=db;OrderEditAdmissionServicePostgresTest.jdbc=db.jdbc;
        LegacyOrderEvidenceEnrollmentPostgresTest.db=db;LegacyOrderEvidenceEnrollmentPostgresTest.jdbc=db.jdbc;
        OrderEditAdmissionFoundationPostgresTest.jdbc=jdbc;configureRuntime(info);
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
        if(Set.of("expiredAutomaticWindowNeverPostsCancellation","expiredAutomaticWindowNeverStartsCard").contains(info.getTestMethod().orElseThrow().getName()))
            new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()))
                .execute(s->jdbc.queryForObject("select f125_execution_initialize(?,interval '8 seconds')",Object.class,receipt));
        assertThat(execution.executeInitial(receipt).status()).isEqualTo(OrderEditExecutionService.Status.HELD);
        when(gateway.retrieveAuthorization(flow)).thenAnswer(a->{outside();return new AuthorizationRecovery(new AuthorizationResult(AuthorizationResultStatus.REQUIRES_PAYMENT_METHOD,null,null,null),"secret_for_widget_only");});
    }
    void configureRuntime(TestInfo info){new OrderEditAdmissionFoundationPostgresTest().runtime(info);}
    void outside(){assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();}
    PayJpPaymentFlowObservation observation(String id,String status,int total,Map<String,Object> metadata){
        var fields=new LinkedHashMap<String,Object>();fields.put("id",id);fields.put("status",status);fields.put("amount",total);fields.put("currency","jpy");fields.put("capture_method","manual");fields.put("livemode",false);fields.put("amount_received",0);fields.put("amount_capturable",status.equals("requires_capture")?total:0);fields.put("metadata",metadata);
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();var raw=mapper.readTree(mapper.writeValueAsString(fields));
        return mapper.treeToValue(raw,PayJpPaymentFlowObservation.class).withOldCancellationEvidence(OldAuthorizationCancellationEvidence.fromJson(raw,connection.requireIdentity(provider)));
    }
    @AfterEach void clearActor(){org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    void actor(){var p=new com.example.ecsite.security.CustomUserDetails(f.customer(),"buyer","unused",true,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(p,"unused",p.getAuthorities()));}
    @Test void authenticationStartHintIsReadOnlyAndOneTime() throws Exception {
        actor();clearInvocations(gateway);
        var principal=new com.example.ecsite.security.CustomUserDetails(f.customer(),"buyer","unused",true,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")));
        String before=jdbc.queryForObject("select md5(row_to_json(op)::text) from order_change_operations op where id=?",String.class,operation);
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/orders/"+f.order()+"/edit/result").param("admission",receipt.toString())
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(principal)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse();
        assertThat(response.getContentAsString()).contains("id=\"result-auth-start-script\"");
        assertThat(jdbc.queryForObject("select md5(row_to_json(op)::text) from order_change_operations op where id=?",String.class,operation)).isEqualTo(before);
        verifyNoInteractions(gateway);
        actor();assertThat(browser.startCard(f.order(),receipt)).isPresent();
        assertThat(browser.result(f.order(),receipt).canAuthenticate()).isFalse();
    }
    @Test void autoStartDoesNotExposeSecretsAndUsesExistingIssueCas(){
        actor();var hint=browser.result(f.order(),receipt);assertThat(hint.autoStartCardAllowed()).isTrue();
        assertThat(hint.automaticCardTarget()).contains(receipt.toString(),operation.toString()).doesNotContain(flow,"secret");
        assertThat(cards.start(f.order(),f.customer(),receipt)).isPresent();
        assertThat(browser.result(f.order(),receipt).autoStartCardAllowed()).isFalse();
        assertThat(cards.start(f.order(),f.customer(),receipt)).isEmpty();
    }
    @Test void stoppedRuntimeNeverAutomaticallyStartsAuthentication(){
        actor();jdbc.update("update order_edit_runtime_control set state='STOPPED',generation_id=null,version=version+1 where id=1");
        clearInvocations(gateway);assertThat(browser.result(f.order(),receipt).autoStartCardAllowed()).isFalse();verifyNoInteractions(gateway);
    }
    @Test void changedOwnerCannotReadAutomaticStartTarget(){
        actor();assertThatThrownBy(()->browser.result(f.order(),UUID.randomUUID())).isInstanceOf(com.example.ecsite.exception.OrderNotFoundException.class);
    }
    @Test void expiredAutomaticWindowNeverStartsCard(){
        actor();jdbc.queryForObject("select pg_sleep(greatest(0,extract(epoch from automatic_until-clock_timestamp()))+0.02) from order_edit_execution_controls where admission_id=?",Object.class,receipt);
        clearInvocations(gateway);assertThat(browser.result(f.order(),receipt).autoStartCardAllowed()).isFalse();verifyNoInteractions(gateway);
    }
    @Test void simultaneousTabsOnlyIssueOneCardScreen()throws Exception{
        var ready=new java.util.concurrent.CountDownLatch(2);
        when(gateway.observeOrderChangeFlow(flow)).thenAnswer(a->{outside();ready.countDown();assertThat(ready.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();return observation(flow,"requires_payment_method",amount,Map.of("order_id",Long.toString(f.order()),"order_change_operation_id",operation.toString()));});
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->cards.start(f.order(),f.customer(),receipt));var b=pool.submit(()->cards.start(f.order(),f.customer(),receipt));
            int issued=(a.get(20,java.util.concurrent.TimeUnit.SECONDS).isPresent()?1:0)+(b.get(20,java.util.concurrent.TimeUnit.SECONDS).isPresent()?1:0);
            assertThat(issued).isEqualTo(1);
        }
        actor();assertThat(browser.result(f.order(),receipt).autoStartCardAllowed()).isFalse();
    }
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
        assertThat(browser.result(f.order(),receipt).autoContinueAllowed()).isFalse();browser.continueAutomatically(f.order(),receipt);
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
        assertThat(browser.result(f.order(),receipt).autoContinueAllowed()).isFalse();browser.continueAutomatically(f.order(),receipt);
        assertThat(jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order())).isZero();
        verify(gateway,never()).sendOrderChangeRequest(any());verify(gateway,never()).retrieveAuthorization(anyString());
    }
    @Test void unknownGetLeavesReceiptAndFinancialRequestUnchanged(){
        actor();assertThat(browser.startCard(f.order(),receipt)).isPresent();clearInvocations(gateway);
        when(gateway.observeOrderChangeFlow(anyString())).thenThrow(new IllegalStateException("mock transport unknown"));
        browser.continueAdmission(f.order(),receipt);
        assertThat(browser.result(f.order(),receipt).label()).isNotEqualTo("変更完了");
        assertThat(browser.result(f.order(),receipt).autoContinueAllowed()).isFalse();browser.continueAutomatically(f.order(),receipt);
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

    void stopRuntime(){jdbc.queryForObject("select f125_runtime_stop((select version from order_edit_runtime_control where id=1),'isolated stop')",Object.class);}
    void resumeTestRuntime(){jdbc.update("update order_edit_runtime_control set state='ISOLATED_TEST',generation_id=(select db_generation_id from order_edit_admissions where id=?),version=version+1 where id=1",receipt);}
    void waitForBackoff(){jdbc.queryForObject("select pg_sleep(greatest(0,extract(epoch from next_attempt_at-clock_timestamp()))+0.02) from order_edit_execution_controls where admission_id=?",Object.class,receipt);}
    @Test void stoppedWebhookCannotApplyFinanceAndSavedReceiptResumesByGet(){
        actor();state.set("requires_capture");stopRuntime();clearInvocations(gateway);
        assertThatThrownBy(()->webhooks.synchronize("payment_flow.amount_capturable_updated",flow)).isInstanceOf(RuntimeException.class);
        browser.continueAdmission(f.order(),receipt);assertThat(browser.result(f.order(),receipt).label()).isNotEqualTo("変更完了");
        assertThat(browser.result(f.order(),receipt).autoContinueAllowed()).isFalse();browser.continueAutomatically(f.order(),receipt);
        verifyNoInteractions(gateway);assertThat(jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order())).isZero();
        resumeTestRuntime();execution.reconcile(receipt);waitForBackoff();assertThat(execution.reconcile(receipt).status()).isEqualTo(OrderEditExecutionService.Status.COMPLETED);
        verify(gateway,times(1)).sendOrderChangeRequest(any()); // Only OLD_CANCEL; CREATE happened before invocations were cleared.
    }
    @Test void stopAfterDispatchCommitDoesNotUndoPermitOrAllowDuplicatePost()throws Exception{
        state.set("requires_capture");execution.reconcile(receipt);waitForBackoff();
        var sending=new java.util.concurrent.CountDownLatch(1);var returned=new java.util.concurrent.CountDownLatch(1);
        doAnswer(a->{
            outside();var request=((OrderChangeRequestSender.Dispatch)a.getArgument(0)).request().httpRequest();assertThat(request.kind()).isEqualTo("OLD_CANCEL");
            sending.countDown();assertThat(returned.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();cancelled.set(true);
            return observation(request.flowId(),"canceled",6000,Map.of());
        }).when(gateway).sendOrderChangeRequest(any());
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{
            var worker=pool.submit(()->execution.execute(receipt));assertThat(sending.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            stopRuntime();assertThat(jdbc.queryForObject("select d.state from order_change_request_dispatches d join order_change_financial_requests q on q.id=d.request_id where q.operation_id=? and q.request_kind='OLD_CANCEL'",String.class,operation)).isEqualTo("STARTED");
            returned.countDown();assertThat(worker.get(10,java.util.concurrent.TimeUnit.SECONDS).status()).isNotEqualTo(OrderEditExecutionService.Status.COMPLETED);
            assertThat(jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order())).isZero();
            // Keep the real lease and automatic window; never rewrite protected control fields.
            jdbc.queryForObject("select pg_sleep(greatest(0,extract(epoch from lease_until-clock_timestamp()))+0.02) from order_edit_execution_controls where admission_id=?",Object.class,receipt);
            resumeTestRuntime();execution.reconcile(receipt);waitForBackoff();assertThat(execution.reconcile(receipt).status()).isEqualTo(OrderEditExecutionService.Status.COMPLETED);
            verify(gateway,times(2)).sendOrderChangeRequest(any());
        }finally{returned.countDown();pool.shutdownNow();}
    }

    String paymentUrl(){return "/orders/"+f.order()+"/edit/"+receipt+"/payment";}
    org.springframework.test.web.servlet.request.RequestPostProcessor customerRequest(){
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(
            new com.example.ecsite.security.CustomUserDetails(f.customer(),"buyer","unused",true,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))));
    }
    void authenticationReturnPost()throws Exception{
        actor();assertThat(browser.startCard(f.order(),receipt)).isPresent();state.set("requires_capture");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(paymentUrl()+"/return").with(customerRequest()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("window.orderEditPaymentContinue();")));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(paymentUrl()+"/continue").with(customerRequest())
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is3xxRedirection());
        actor(); // MockMvc clears its request SecurityContext; direct service checks need an explicit test actor.
    }
    @Test void authenticationReturnAndRequeueAutomaticallyCompleteWithoutManualContinuation()throws Exception{
        var digest=jdbc.queryForObject("select encode(financial_digest,'hex') from order_edit_admissions where id=?",String.class,receipt);
        authenticationReturnPost();
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/orders/"+f.order()+"/edit/result")
            .param("admission",receipt.toString()).with(customerRequest())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        var ready=(OrderEditBrowserService.BrowserResult)response.getModelAndView().getModel().get("result");
        assertThat(ready.nextStep().action()).isEqualTo(OrderEditBrowserService.NextAction.AUTO_RESUME_REAUTH_ALLOWED);
        assertThat(ready.nextStep().waitMillis()).isPositive().isLessThanOrEqualTo(5000);
        assertThat(response.getResponse().getContentAsString()).contains("data-stage=\"REAUTH:","setTimeout(send, wait)");
        actor();
        browser.continueAutomatically(f.order(),receipt,ready.nextStep().version()); // Early POST cannot claim or cancel.
        verify(gateway,times(1)).sendOrderChangeRequest(any());
        waitForBackoff();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(paymentUrl()+"/continue").with(customerRequest())
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
            .param("automatic","true").param("automaticVersion",ready.nextStep().version().toString()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is3xxRedirection());
        // Treat that response as lost: reread saved completion, then replay the same version.
        actor();
        assertThat(browser.result(f.order(),receipt).label()).isEqualTo("変更完了");
        browser.continueAutomatically(f.order(),receipt,ready.nextStep().version());
        verify(gateway,times(2)).sendOrderChangeRequest(any());assertThat(cancelled).isTrue();
        assertThat(jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select encode(financial_digest,'hex') from order_edit_admissions where id=?",String.class,receipt)).isEqualTo(digest);
    }
    @Test void requeuedVersionRaceAllowsOneApplicationAndNoDuplicateFinancialPost()throws Exception{
        authenticationReturnPost();waitForBackoff();long version=browser.result(f.order(),receipt).nextStep().version();
        assertThat(execution.executeRequeued(receipt,version-1).status()).isEqualTo(OrderEditExecutionService.Status.BLOCKED);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
        try{
            var one=pool.submit(()->{start.await();return execution.executeRequeued(receipt,version);});
            var two=pool.submit(()->{start.await();return execution.executeRequeued(receipt,version);});start.countDown();
            var outcomes=List.of(one.get(),two.get());assertThat(outcomes).anyMatch(x->x.status()==OrderEditExecutionService.Status.COMPLETED);
        }finally{pool.shutdownNow();}
        assertThat(browser.result(f.order(),receipt).label()).isEqualTo("変更完了");verify(gateway,times(2)).sendOrderChangeRequest(any());
        assertThat(jdbc.queryForObject("select count(*) from order_edit_execution_attempt_results where admission_id=? and disposition='APPLIED'",Integer.class,receipt)).isOne();
    }
    @Test void expiredAutomaticWindowNeverPostsCancellation()throws Exception{
        authenticationReturnPost();
        jdbc.queryForObject("select pg_sleep(greatest(0,extract(epoch from automatic_until-clock_timestamp()))+0.02) from order_edit_execution_controls where admission_id=?",Object.class,receipt);
        long version=jdbc.queryForObject("select version from order_edit_execution_controls where admission_id=?",Long.class,receipt);
        assertThat(browser.result(f.order(),receipt).autoContinueAllowed()).isFalse();
        browser.continueAutomatically(f.order(),receipt,version);
        assertThat(execution.executeRequeued(receipt,version).status()).isNotEqualTo(OrderEditExecutionService.Status.COMPLETED);
        verify(gateway,times(1)).sendOrderChangeRequest(any());assertThat(cancelled).isFalse();
    }

    @Test void lostRequeueClaimCommitResponseOnlyObservesSavedAttemptAndNeverReposts()throws Exception{
        authenticationReturnPost();waitForBackoff();long version=browser.result(f.order(),receipt).nextStep().version();
        var command=new OrderEditExecution.Claim(receipt,UUID.randomUUID(),version);
        var source=OrderEditAdmissionServicePostgresTest.db.source;
        var uncertain=new OrderEditExecutionCoordinator(source,OrderEditAdmissionServicePostgresTest.lostCommit(1,true)).claimRequeued(command);
        assertThat(uncertain.outcome()).isEqualTo(OrderEditExecution.Outcome.COMMIT_UNKNOWN);
        var coordinator=new OrderEditExecutionCoordinator(source);var saved=coordinator.recoverClaim(command);
        assertThat(saved.outcome()).isEqualTo(OrderEditExecution.Outcome.OBSERVED);
        assertThat(saved.view().activeAttemptId()).isEqualTo(command.attemptId());
        assertThat(browser.result(f.order(),receipt).autoContinueAllowed()).isFalse();browser.continueAutomatically(f.order(),receipt,version);
        verify(gateway,times(1)).sendOrderChangeRequest(any());
        // Resolve the isolated fixture's active owner into safe hold, not a retry.
        var owner=saved.view();assertThat(coordinator.resolve(new OrderEditExecution.Resolution(receipt,UUID.randomUUID(),command.attemptId(),
            owner.token(),owner.version(),OrderEditExecution.Disposition.UNKNOWN,"TEST_RESPONSE_LOSS")).outcome()).isEqualTo(OrderEditExecution.Outcome.RECORDED);
    }
}
