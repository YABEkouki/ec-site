package com.example.ecsite.payment.safety;

import com.example.ecsite.support.isolation.IsolatedTestMode;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.ecsite.payment.*;
import com.example.ecsite.payment.payjp.*;
import com.example.ecsite.payment.orderchange.OrderChangeRequestSender;
import com.example.ecsite.payment.savedcard.SavedCardGateway;
import com.example.ecsite.payment.savedcard.SavedCardGatewayException;
import com.example.ecsite.config.*;
import com.example.ecsite.service.order.evidence.*;
import com.example.ecsite.service.savedcard.OrderChangeSavedCardService;
import com.example.ecsite.service.savedcard.OrderChangeSavedCardService.NextStep;
import com.example.ecsite.service.savedcard.OrderChangeSavedCardService.Outcome;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Feature139-C: V107 guard and saved cards for the order edit reauthorization against an in-memory PAY.JP.
 * The Feature125 fixture is the one of {@link OrderEditCardPostgresTest}; the existing reauthorization code decides. */
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@SpringBootTest(properties={"logging.level.root=WARN","spring.jpa.show-sql=false","payjp.customer.id-prefix=t139c-"})
@com.example.ecsite.support.CaptureConnectionTest
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@IsolatedTestMode(IsolatedTestMode.Mode.SHARED)
class OrderChangeSavedCardPostgresTest {

    /** In-memory PAY.JP for the saved card calls only (customers, cards, Payment Flow update/confirm/read). */
    static final class FakeCards implements SavedCardGateway {
        final Map<String,String> customers=new LinkedHashMap<>();
        final Map<String,Card> cards=new LinkedHashMap<>();
        final Map<String,FlowState> flows=new LinkedHashMap<>();
        final Set<String> secured=new HashSet<>(),declining=new HashSet<>();
        SavedCardGatewayException failNext;String failOn;boolean applyDespiteFailure;
        int updateCalls,confirmCalls;
        private <T> T write(String kind,java.util.function.Supplier<T> action){
            if(failNext!=null&&kind.equals(failOn)){var f=failNext;failNext=null;failOn=null;if(applyDespiteFailure)action.get();applyDespiteFailure=false;throw f;}
            return action.get();
        }
        static SavedCardGatewayException rejected(int status,String code){return new SavedCardGatewayException(SavedCardGatewayException.Kind.REJECTED,status,code);}
        String card(String customer,String id){
            cards.put(id,new Card(id,customer,"Visa","4242",12,2030,"fp-"+id,OffsetDateTime.now(ZoneOffset.UTC),null));
            customers.putIfAbsent(customer,id);return id;
        }
        @Override public Optional<Customer> findCustomer(String id){return customers.containsKey(id)?Optional.of(new Customer(id,customers.get(id))):Optional.empty();}
        @Override public Customer createCustomer(String id,long userId,String key){customers.putIfAbsent(id,null);return new Customer(id,null);}
        @Override public List<Card> listCards(String customer){return cards.values().stream().filter(c->c.attachedTo(customer)).toList();}
        @Override public Optional<Card> findCard(String id){return Optional.ofNullable(cards.get(id));}
        @Override public SetupStart createSetupFlow(String c,long u,long o,String k){throw new UnsupportedOperationException();}
        @Override public Optional<SetupState> findSetupFlow(String id){throw new UnsupportedOperationException();}
        @Override public List<SetupState> findSetupFlowsByOperation(String c,long o){throw new UnsupportedOperationException();}
        @Override public Card detach(String id,String key){throw new UnsupportedOperationException();}
        @Override public Customer setDefaultCard(String c,String pm,String key){throw new UnsupportedOperationException();}
        @Override public Card attach(String id,String c,String key){throw new UnsupportedOperationException();}
        @Override public FlowState updateFlowCustomer(String flowId,String customerId,String pm,String key){
            return write("flowUpdate",()->{updateCalls++;var f=flows.get(flowId);
                if(!f.status().equals("requires_payment_method")&&!f.status().equals("requires_confirmation"))throw rejected(400,"fields_not_updatable_in_status");
                if(cards.get(pm)==null||!cards.get(pm).attachedTo(customerId))throw rejected(400,"payment_method_not_owned_by_customer");
                var next=new FlowState(flowId,"requires_confirmation",customerId,pm);flows.put(flowId,next);return next;});
        }
        @Override public FlowState confirmFlow(String flowId,String returnUrl,String key){
            return write("confirm",()->{confirmCalls++;var f=flows.get(flowId);
                if(!f.status().equals("requires_confirmation"))throw rejected(400,"invalid_status");
                if(declining.contains(f.paymentMethodId())){flows.put(flowId,new FlowState(flowId,"requires_payment_method",f.customerId(),f.paymentMethodId()));throw rejected(402,"card_declined");}
                var next=new FlowState(flowId,secured.contains(f.paymentMethodId())?"requires_capture":"requires_action",f.customerId(),f.paymentMethodId());
                flows.put(flowId,next);return next;});
        }
        @Override public Optional<FlowState> findFlow(String flowId){return Optional.ofNullable(flows.get(flowId));}
        void browserCompletes(String flowId,String status){var f=flows.get(flowId);flows.put(flowId,new FlowState(flowId,status,f.customerId(),f.paymentMethodId()));}
    }
    @TestConfiguration static class FakeProvider{@Bean @Primary FakeCards fakeCards(){return new FakeCards();}}

    @Autowired com.example.ecsite.service.payment.PayJpWebhookSyncService webhooks;
    @Autowired OrderEditBrowserService browser;
    @Autowired JdbcTemplate jdbc;@Autowired OrderEditExecutionService execution;@Autowired OrderEditCardService cards;
    @Autowired CaptureConnectionProperties connection;@Autowired PayJpProperties provider;
    @Autowired FakeCards payjp;@Autowired OrderChangeSavedCardService saved;
    @MockitoBean PaymentGateway gateway;
    AtomicBoolean cancelled=new AtomicBoolean();String flow,customerId;
    OrderEditAdmissionServicePostgresTest.Fixture f;UUID receipt,operation;int amount;

    @BeforeEach void fixture(TestInfo info){
        var db=OrderEditAdmissionFoundationPostgresTest.open(null);db.schema="public";db.source.setCurrentSchema("public");
        OrderEditAdmissionServicePostgresTest.db=db;OrderEditAdmissionServicePostgresTest.jdbc=db.jdbc;
        LegacyOrderEvidenceEnrollmentPostgresTest.db=db;LegacyOrderEvidenceEnrollmentPostgresTest.jdbc=db.jdbc;
        OrderEditAdmissionFoundationPostgresTest.jdbc=jdbc;new OrderEditAdmissionFoundationPostgresTest().runtime(info);
        f=OrderEditAdmissionServicePostgresTest.fixture(false);var command=OrderEditAdmissionServicePostgresTest.command(f,3,UUID.randomUUID());
        receipt=new OrderEditAdmissionService(db.source).submit(command).admissionId();assertThat(receipt).isNotNull();
        amount=command.confirmed().previewSnapshot().totalAmount();cancelled.set(false);
        when(gateway.observeOrderAuthorization(any())).thenAnswer(a->{outside();return MockOrderAuthorizationTransport.observe(a.getArgument(0));});
        when(gateway.sendOrderChangeRequest(any())).thenAnswer(a->{
            outside();var request=((OrderChangeRequestSender.Dispatch)a.getArgument(0)).request().httpRequest();operation=request.operationId();flow="pfw_edit_"+operation.toString().replace("-","");
            if(request.kind().equals("CREATE")){payjp.flows.put(flow,new SavedCardGateway.FlowState(flow,"requires_payment_method",null,null));return observation(flow,"requires_payment_method",amount,Map.of());}
            if(request.kind().equals("OLD_CANCEL")){cancelled.set(true);return observation(request.flowId(),"canceled",6000,Map.of());}
            throw new AssertionError("Unexpected financial request");
        });
        // The existing code reads the new Payment Flow; its status is whatever the fake PAY.JP holds.
        when(gateway.observeOrderChangeFlow(anyString())).thenAnswer(a->{outside();String id=a.getArgument(0);
            if(id.equals(flow))return observation(id,payjp.flows.get(flow).status(),amount,Map.of("order_id",Long.toString(f.order()),"order_change_operation_id",operation.toString()));
            return observation(id,cancelled.get()?"canceled":"requires_capture",6000,Map.of());
        });
        assertThat(execution.executeInitial(receipt).status()).isEqualTo(OrderEditExecutionService.Status.HELD);
        when(gateway.retrieveAuthorization(flow)).thenAnswer(a->{outside();
            var status="requires_action".equals(payjp.flows.get(flow).status())?AuthorizationResultStatus.REQUIRES_ACTION:AuthorizationResultStatus.REQUIRES_PAYMENT_METHOD;
            return new AuthorizationRecovery(new AuthorizationResult(status,null,null,null),"secret_for_sdk_only");});
        customerId="t139c-"+f.customer();
        payjp.updateCalls=0;payjp.confirmCalls=0;payjp.failNext=null;payjp.failOn=null;
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
    String card(boolean secured){String pm=payjp.card(customerId,"pm_"+UUID.randomUUID().toString().replace("-",""));if(secured)payjp.secured.add(pm);return pm;}
    boolean marker(){return jdbc.queryForObject("select browser_confirmation_issued_at is not null from order_change_operations where id=?",Boolean.class,operation);}
    List<String> sends(){return jdbc.queryForList("select send_type||':'||status from order_change_card_sends where operation_id=? order by id",String.class,operation);}
    int revision(){return jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order());}
    void waitForBackoff(){jdbc.queryForObject("select pg_sleep(greatest(0,extract(epoch from next_attempt_at-clock_timestamp()))+0.02) from order_edit_execution_controls where admission_id=?",Object.class,receipt);}
    /** The existing continuation (as the card entry screen does after the SDK) until the change completes. */
    void completeByExistingContinuation(){
        execution.reconcile(receipt);waitForBackoff();browser.continueAdmission(f.order(),receipt);
        assertThat(browser.result(f.order(),receipt).label()).isEqualTo("変更完了");
    }
    void insert(String type,String pm,String key){
        new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource())).executeWithoutResult(s->
            jdbc.update("""
                insert into order_change_card_sends(user_id,order_id,operation_id,admission_id,payment_id,transaction_id,send_type,
                  provider_payment_flow_id,provider_customer_id,provider_payment_method_id,idempotency_key,status,created_at)
                select ?,?,op.id,?,op.new_payment_id,t.id,?,?,?,?,?,'SUCCEEDED',localtimestamp
                from order_change_operations op join payment_transactions t on t.order_change_operation_id=op.id
                  and t.purpose='ORDER_CHANGE_AUTHORIZATION' where op.id=?
                """,f.customer(),f.order(),receipt,type,flow,customerId,pm,key,operation));
    }

    @Test void guardNeedsTheConfirmationStartAndOrdersTheSends(){
        String pm=card(true);
        assertThatThrownBy(()->insert("FLOW_UPDATE",pm,"t139c-no-marker")).isInstanceOf(DataAccessException.class)
            .hasMessageContaining("confirmation-started");
        jdbc.update("update order_change_operations set browser_confirmation_issued_at=timezone('Asia/Tokyo',clock_timestamp()),version=version+1 where id=?",operation);
        assertThatThrownBy(()->insert("CONFIRM",pm,"t139c-confirm-first")).isInstanceOf(DataAccessException.class)
            .hasMessageContaining("succeeded saved card update");
        insert("FLOW_UPDATE",pm,"t139c-update-"+UUID.randomUUID());
        assertThat(sends()).containsExactly("FLOW_UPDATE:PENDING"); // inserted rows always start PENDING
        assertThatThrownBy(()->insert("CONFIRM",pm,"t139c-busy")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->insert("FLOW_UPDATE",pm,"t139c-second-update")).isInstanceOf(DataAccessException.class);
        long id=jdbc.queryForObject("select id from order_change_card_sends where operation_id=?",Long.class,operation);
        assertThatThrownBy(()->jdbc.update("update order_change_card_sends set status='SUCCEEDED',provider_payment_method_id='pm_other' where id=?",id))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("redefined");
        jdbc.update("update order_change_card_sends set status='SUCCEEDED' where id=?",id);
        assertThatThrownBy(()->jdbc.update("update order_change_card_sends set status='FAILED' where id=?",id))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("transition");
        assertThatThrownBy(()->insert("CONFIRM","pm_"+UUID.randomUUID().toString().replace("-",""),"t139c-other-card"))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("same card");
        insert("CONFIRM",pm,"t139c-confirm-"+UUID.randomUUID());
        assertThatThrownBy(()->jdbc.update("delete from order_change_card_sends where id=?",id)).isInstanceOf(DataAccessException.class);
    }

    @Test void securedSavedCardCompletesTheChangeThroughTheExistingReauthorization(){
        actor();String pm=card(true);
        var result=saved.pay(f.customer(),f.order(),receipt,pm);
        assertThat(result.outcome()).isEqualTo(Outcome.CONTINUE);
        assertThat(marker()).isTrue();assertThat(sends()).containsExactly("FLOW_UPDATE:SUCCEEDED","CONFIRM:SUCCEEDED");
        assertThat(payjp.flows.get(flow).customerId()).isEqualTo(customerId);
        // The card entry screen can no longer be issued: one confirmation chance per change.
        assertThat(cards.start(f.order(),f.customer(),receipt)).isEmpty();
        completeByExistingContinuation();
        assertThat(revision()).isEqualTo(1);assertThat(cancelled.get()).isTrue();
        assertThat(saved.pay(f.customer(),f.order(),receipt,pm).outcome()).isNotEqualTo(Outcome.REQUIRES_ACTION);
        assertThat(payjp.confirmCalls).isEqualTo(1);
    }

    @Test void threeDSecureOfTheServerConfirmContinuesInTheBrowserWithoutResending(){
        actor();String pm=card(false);
        var result=saved.pay(f.customer(),f.order(),receipt,pm);
        assertThat(result.outcome()).isEqualTo(Outcome.REQUIRES_ACTION);
        assertThat(result.card().clientSecret()).isEqualTo("secret_for_sdk_only");
        assertThat(result.card().returnUrl()).endsWith("/orders/"+f.order()+"/edit/"+receipt+"/payment/return");
        assertThat(result.toString()).doesNotContain("secret");
        browser.continueAdmission(f.order(),receipt);
        assertThat(saved.nextStep(f.customer(),f.order(),receipt)).isEqualTo(NextStep.AUTHENTICATE);
        assertThat(saved.authenticate(f.customer(),f.order(),receipt)).isPresent();
        assertThat(saved.authenticate(f.customer()+100000,f.order(),receipt)).isEmpty();
        assertThat(payjp.confirmCalls).isEqualTo(1);
        payjp.browserCompletes(flow,"requires_capture");
        completeByExistingContinuation();
        assertThat(revision()).isEqualTo(1);
        assertThat(saved.nextStep(f.customer(),f.order(),receipt)).isEqualTo(NextStep.NONE);
    }

    @Test void declinedCardIsDeliveredAndCannotBeReplacedInTheSameChange(){
        actor();String bad=card(true),good=card(true);payjp.declining.add(bad);
        assertThat(saved.pay(f.customer(),f.order(),receipt,bad).outcome()).isEqualTo(Outcome.CONTINUE);
        assertThat(sends()).containsExactly("FLOW_UPDATE:SUCCEEDED","CONFIRM:SUCCEEDED"); // the 402 reached PAY.JP
        browser.continueAdmission(f.order(),receipt);
        assertThat(browser.result(f.order(),receipt).label()).isNotEqualTo("変更完了");
        assertThat(saved.nextStep(f.customer(),f.order(),receipt)).isEqualTo(NextStep.NONE);
        assertThat(saved.pay(f.customer(),f.order(),receipt,good).outcome()).isEqualTo(Outcome.NOT_AVAILABLE);
        assertThat(saved.pay(f.customer(),f.order(),receipt,bad).outcome()).isEqualTo(Outcome.CONTINUE);
        assertThat(cards.start(f.order(),f.customer(),receipt)).isEmpty();
        assertThat(payjp.confirmCalls).isEqualTo(1);assertThat(revision()).isZero();
    }

    @Test void undeliveredConfirmIsResentOnceWithTheSameCard(){
        actor();String pm=card(true);
        payjp.failNext=FakeCards.rejected(400,"invalid_request_error");payjp.failOn="confirm";
        // The refused confirm leaves the Flow in requires_confirmation: it was not delivered.
        var first=saved.pay(f.customer(),f.order(),receipt,pm);
        assertThat(first.outcome()).isEqualTo(Outcome.CONTINUE);
        assertThat(sends()).containsExactly("FLOW_UPDATE:SUCCEEDED","CONFIRM:FAILED");
        assertThat(saved.nextStep(f.customer(),f.order(),receipt)).isEqualTo(NextStep.RESEND);
        assertThat(saved.selectedCard(f.customer(),f.order(),receipt)).contains(pm);
        payjp.failNext=FakeCards.rejected(400,"invalid_request_error");payjp.failOn="confirm";
        assertThat(saved.pay(f.customer(),f.order(),receipt,pm).outcome()).isEqualTo(Outcome.CONTINUE);
        assertThat(sends()).containsExactly("FLOW_UPDATE:SUCCEEDED","CONFIRM:FAILED","CONFIRM:FAILED");
        assertThat(saved.nextStep(f.customer(),f.order(),receipt)).isEqualTo(NextStep.NONE);
        assertThat(saved.pay(f.customer(),f.order(),receipt,pm).outcome()).isEqualTo(Outcome.NOT_AVAILABLE);
        // Both refused confirms were recorded (two CONFIRM rows); a third was never started.
        assertThat(sends()).hasSize(3);assertThat(payjp.confirmCalls).isZero();
    }

    @Test void resentConfirmCompletesTheChange(){
        actor();String pm=card(true);
        payjp.failNext=FakeCards.rejected(400,"invalid_request_error");payjp.failOn="confirm";
        saved.pay(f.customer(),f.order(),receipt,pm);
        assertThat(saved.pay(f.customer(),f.order(),receipt,pm).outcome()).isEqualTo(Outcome.CONTINUE);
        assertThat(sends()).containsExactly("FLOW_UPDATE:SUCCEEDED","CONFIRM:FAILED","CONFIRM:SUCCEEDED");
        completeByExistingContinuation();assertThat(revision()).isEqualTo(1);
    }

    @Test void unknownUpdateIsSettledByReadingTheFlowThenConfirmed(){
        actor();String pm=card(true);
        payjp.failNext=new SavedCardGatewayException(SavedCardGatewayException.Kind.UNKNOWN,null,"transport");
        payjp.failOn="flowUpdate";payjp.applyDespiteFailure=true;
        assertThat(saved.pay(f.customer(),f.order(),receipt,pm).outcome()).isEqualTo(Outcome.CONTINUE);
        assertThat(sends()).containsExactly("FLOW_UPDATE:UNCONFIRMED");
        assertThat(saved.nextStep(f.customer(),f.order(),receipt)).isEqualTo(NextStep.RESEND);
        assertThat(saved.pay(f.customer(),f.order(),receipt,pm).outcome()).isEqualTo(Outcome.CONTINUE);
        assertThat(sends()).containsExactly("FLOW_UPDATE:SUCCEEDED","CONFIRM:SUCCEEDED");
        assertThat(payjp.updateCalls).isEqualTo(1);
        completeByExistingContinuation();
    }

    @Test void refusedUpdateUsesTheChanceAndSendsNoConfirm(){
        actor();String pm=card(true);payjp.failNext=FakeCards.rejected(400,"payment_method_not_owned_by_customer");payjp.failOn="flowUpdate";
        assertThat(saved.pay(f.customer(),f.order(),receipt,pm).outcome()).isEqualTo(Outcome.UPDATE_REFUSED);
        assertThat(sends()).containsExactly("FLOW_UPDATE:FAILED");assertThat(marker()).isTrue();
        assertThat(saved.pay(f.customer(),f.order(),receipt,pm).outcome()).isEqualTo(Outcome.UPDATE_REFUSED);
        assertThat(saved.nextStep(f.customer(),f.order(),receipt)).isEqualTo(NextStep.NONE);
        assertThat(payjp.confirmCalls).isZero();assertThat(revision()).isZero();
    }

    @Test void otherPeoplesCardsForeignOrdersAndUsedChancesSendNothing(){
        actor();payjp.card("t139c-someone-else","pm_foreign_"+UUID.randomUUID().toString().replace("-",""));
        String foreign=payjp.cards.keySet().stream().filter(k->k.startsWith("pm_foreign_")).reduce((a,b)->b).orElseThrow();
        assertThat(saved.pay(f.customer(),f.order(),receipt,foreign).outcome()).isEqualTo(Outcome.CARD_NOT_USABLE);
        String pm=card(true);
        assertThat(saved.pay(f.customer(),f.order(),UUID.randomUUID(),pm).outcome()).isEqualTo(Outcome.NOT_AVAILABLE);
        assertThat(marker()).isFalse();assertThat(sends()).isEmpty();
        // The card entry screen used the chance first.
        assertThat(cards.start(f.order(),f.customer(),receipt)).isPresent();
        assertThat(saved.pay(f.customer(),f.order(),receipt,pm).outcome()).isEqualTo(Outcome.NOT_AVAILABLE);
        assertThat(sends()).isEmpty();assertThat(payjp.updateCalls).isZero();
        assertThat(saved.authenticate(f.customer(),f.order(),receipt)).isEmpty();
    }

    @Test void choiceIsOfferedBeforeTheChanceIsUsedAndRecordsNothing() throws Exception {
        actor();card(true);
        var principal=new com.example.ecsite.security.CustomUserDetails(f.customer(),"buyer","unused",true,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/orders/"+f.order()+"/edit/"+receipt+"/payment")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(principal))
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.view().name("orders/increase-payment-choice"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("/payment/saved-card")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret_for_sdk_only"))));
        assertThat(marker()).isFalse();verify(gateway,never()).retrieveAuthorization(anyString());
    }
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
}
