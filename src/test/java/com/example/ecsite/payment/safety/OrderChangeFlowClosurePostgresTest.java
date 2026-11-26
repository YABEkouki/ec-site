package com.example.ecsite.payment.safety;

import com.example.ecsite.support.isolation.IsolatedTestMode;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.example.ecsite.payment.*;
import com.example.ecsite.payment.capture.CaptureConnectionIdentity;
import com.example.ecsite.payment.orderchange.OrderChangeRequestSender;
import com.example.ecsite.payment.payjp.*;
import com.example.ecsite.service.order.evidence.*;
import com.example.ecsite.service.changeclosure.*;
import com.example.ecsite.service.changeclosure.ChangeFlowClosureService.*;
import com.example.ecsite.service.changeclosure.ChangeFlowClosureStore.*;
import com.example.ecsite.service.orderchange.OrderReauthorizationService;
import com.example.ecsite.support.CaptureConnectionTest;
import com.example.ecsite.support.isolation.IsolatedDatabaseGuard;
import static com.example.ecsite.service.order.evidence.OrderEditCandidate.*;
import java.time.Duration;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Feature143-A: closing an owned change whose re-authorization was never completed (V111). No trigger is ever disabled. */
@SpringBootTest(properties={"logging.level.root=WARN","spring.jpa.show-sql=false","logging.level.org.springframework=WARN","logging.level.org.hibernate=WARN"})
@CaptureConnectionTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@IsolatedTestMode(IsolatedTestMode.Mode.SHARED)
class OrderChangeFlowClosurePostgresTest {
    @DynamicPropertySource static void dedicatedCommitTrackingDatabase(DynamicPropertyRegistry properties){
        PaymentSafetyExpandPostgresTest.verifyContainerBeforeAnyDatabaseAccess();

    }
    @AfterAll static void stopDedicatedDatabase(){}
    static final CaptureConnectionIdentity CONNECTION=new CaptureConnectionIdentity("test-capture","test-account","TEST","https://api.pay.jp");
    @Autowired Environment environment;
    @Autowired JdbcTemplate applicationJdbc;
    @Autowired OrderReauthorizationService finance;
    @Autowired com.example.ecsite.service.orderchange.OrderReauthorizationTransactions financeTransactions;
    @Autowired com.example.ecsite.payment.orderchange.OrderChangeFinancialRequestStore requestStore;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @Autowired ChangeFlowClosureStore store;
    @Autowired ChangeFlowClosureService closures;
    @Autowired com.example.ecsite.service.payment.PaymentOperationSnapshotService snapshots;
    @Autowired com.example.ecsite.service.payment.PaymentOperationSyncService synchronization;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @MockitoBean PaymentGateway gateway;
    PaymentSafetyExpandPostgresTest db;
    String newStatus,oldCancelMode;
    final AtomicBoolean oldCancelled=new AtomicBoolean();
    String oldFlow,newFlow;
    UUID operation;
    int afterAmount;
    boolean preAuthorization=true;

    @BeforeEach void fixtureDatabase(TestInfo info){
        IsolatedDatabaseGuard.verify(environment);
        db=new PaymentSafetyExpandPostgresTest();db.schema=applicationJdbc.queryForObject("select current_schema()",String.class);
        db.source=new PGSimpleDataSource();db.source.setURL(environment.getRequiredProperty("spring.datasource.url"));
        db.source.setUser(environment.getRequiredProperty("spring.datasource.username"));
        db.source.setPassword(environment.getRequiredProperty("spring.datasource.password"));db.source.setCurrentSchema(db.schema);
        db.jdbc=new JdbcTemplate(db.source);
        assertThat(db.jdbc.queryForObject("select to_regprocedure('f125_reauth_initialize(uuid,uuid,bigint,bigint,uuid,bytea)') is not null",Boolean.class)).isTrue();
        OrderEditAdmissionFoundationPostgresTest.db=db;OrderEditAdmissionFoundationPostgresTest.jdbc=db.jdbc;
        OrderEditAdmissionServicePostgresTest.db=db;OrderEditAdmissionServicePostgresTest.jdbc=db.jdbc;
        LegacyOrderEvidenceEnrollmentPostgresTest.db=db;LegacyOrderEvidenceEnrollmentPostgresTest.jdbc=db.jdbc;
        OrderPaymentEvidenceFoundationPostgresTest.db=db;OrderPaymentEvidenceFoundationPostgresTest.jdbc=db.jdbc;
        OrderEditApplicationPlanPostgresTest.db=db;
        new OrderEditAdmissionFoundationPostgresTest().runtime(info);
        reset(gateway);newStatus="requires_payment_method";oldCancelMode="success";oldCancelled.set(false);operation=null;
    }
    @AfterEach void stopRuntime(){
        if(db!=null)db.jdbc.update("update order_edit_runtime_control set state='STOPPED',generation_id=null,version=version+1 where id=1");
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
    OrderEditApplicationPlanPostgresTest helper(){return new OrderEditApplicationPlanPostgresTest();}
    OrderEditExecutionService service(){return new OrderEditExecutionService(db.source,gateway,CONNECTION,finance);}
    UUID accepted(OrderEditAdmissionServicePostgresTest.Fixture f,List<Line> lines,OrderChangeRoutePolicy.Route route){
        var command=OrderEditAdmissionServicePostgresTest.tx(()->{
            var read=new JdbcOrderPaymentEvidenceReader(db.jdbc).readLocked(f.order());
            var request=new Request(f.order(),read.facts().current().contentRevision(),lines);
            var now=db.jdbc.queryForObject("select timezone('Asia/Tokyo',clock_timestamp())",java.time.LocalDateTime.class);
            var offers=db.jdbc.query("select p.id,p.name,c.id,c.name,p.price,t.id,t.code,t.name,t.tax_rate,(p.active and c.active and t.active) from products p join categories c on c.id=p.category_id join tax_categories t on t.id=p.tax_category_id order by p.id",
                (r,n)->new Offer(r.getLong(1),r.getString(2),r.getLong(3),r.getString(4),r.getInt(5),r.getLong(6),r.getString(7),r.getString(8),OrderContentSnapshotCodec.taxRateBasisPoints(r.getBigDecimal(9)),r.getBoolean(10)));
            var calculated=new OrderEditCandidateCalculator().calculateForAdmission(OrderEditCandidateSources.context(read,f.customer(),now),request,offers);
            assertThat(calculated.route()).isEqualTo(route);
            return new OrderEditAdmissionService.Command(f.customer(),UUID.randomUUID(),request,calculated.candidate());
        });
        var result=new OrderEditAdmissionService(db.source).submit(command);
        assertThat(result.outcome()).isEqualTo(OrderEditAdmissionService.Outcome.ACCEPTED);return result.admissionId();
    }
    void mockTransport(OrderEditAdmissionServicePostgresTest.Fixture f){
        oldFlow=db.jdbc.queryForObject("select provider_payment_id from payments where id=?",String.class,f.payment());
        when(gateway.observeOrderAuthorization(any())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return MockOrderAuthorizationTransport.observe(call.getArgument(0));
        });
        when(gateway.sendOrderChangeRequest(any())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var saved=((OrderChangeRequestSender.Dispatch)call.getArgument(0)).request();var request=saved.httpRequest();
            assertThat(db.jdbc.queryForObject("select request_body from order_change_financial_requests where id=?",byte[].class,saved.id())).isEqualTo(request.body());
            int positivePlans=db.jdbc.queryForObject("select count(*) from order_edit_inventory_plans p join order_edit_admissions a on a.id=p.admission_id join order_change_operations o on o.order_id=a.order_id and o.submission_key=a.submission_key where o.id=? and p.reserve_quantity>0",Integer.class,request.operationId());
            assertThat(db.jdbc.queryForObject("select count(*) from order_edit_inventory_plans where operation_id=? and reserve_quantity>0 and reservation_state='HELD'",Integer.class,request.operationId())).isEqualTo(positivePlans);
            operation=request.operationId();newFlow="pfw_f125_"+operation;
            if("CREATE".equals(request.kind())){afterAmount=request.amount();return observation(newFlow,"requires_payment_method",afterAmount,Map.of());}
            if("NEW_ABORT".equals(request.kind())){newStatus="canceled";return observation(newFlow,"canceled",afterAmount,Map.of());}
            assertThat(request.kind()).isEqualTo("OLD_CANCEL");assertThat(request.amount()).isEqualTo(6000);
            if("unknown".equals(oldCancelMode))throw new PaymentGatewayException("lost cancel response");
            if("failed".equals(oldCancelMode))throw new PaymentGatewayException(PaymentGatewayErrorCategory.OTHER_HTTP_ERROR,400,"rejected",false);
            oldCancelled.set(true);return observation(oldFlow,"canceled",6000,Map.of());
        });
        when(gateway.observeOrderChangeFlow(anyString())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();String flow=call.getArgument(0);
            if(flow.equals(oldFlow))return observation(flow,oldCancelled.get()?"canceled":"requires_capture",6000,Map.of());
            assertThat(flow).isEqualTo(newFlow);
            return observation(flow,newStatus,afterAmount,Map.of("order_id",Long.toString(f.order()),"order_change_operation_id",operation.toString()));
        });
    }
    PayJpPaymentFlowObservation observation(String flow,String status,int amount,Map<String,Object> metadata){
        var fields=new LinkedHashMap<String,Object>();fields.put("id",flow);fields.put("status",status);fields.put("amount",amount);
        fields.put("currency","jpy");fields.put("capture_method","manual");fields.put("livemode",false);fields.put("metadata",metadata);
        fields.put("amount_received",status.equals("requires_capture")||status.equals("canceled")&&!preAuthorization?0:null);Integer capturable=null;
        if(status.equals("requires_capture"))capturable=amount;else if(status.equals("canceled")&&!preAuthorization)capturable=0;
        fields.put("amount_capturable",capturable);
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();var raw=mapper.readTree(mapper.writeValueAsString(fields));
        return mapper.treeToValue(raw,PayJpPaymentFlowObservation.class).withOldCancellationEvidence(OldAuthorizationCancellationEvidence.fromJson(raw,CONNECTION));
    }
    void assertOriginalContent(OrderEditAdmissionServicePostgresTest.Fixture f){
        assertThat(db.jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order())).isZero();
        assertThat(db.jdbc.queryForObject("select active_payment_id from orders where id=?",Long.class,f.order())).isEqualTo(f.payment());
        assertThat(db.jdbc.queryForObject("select count(*) from order_content_change_histories where order_id=?",Integer.class,f.order())).isZero();
    }
    void assertHeld(UUID id){assertThat(db.jdbc.queryForList("select reservation_state from order_edit_inventory_plans where admission_id=? and reserve_quantity>0",String.class,id)).isNotEmpty().containsOnly("HELD");}

    static final Actor ADMIN=new Actor(1L,"closer","お客様の操作が止まっているため");
    OrderEditAdmissionServicePostgresTest.Fixture f;
    UUID admission;
    long added,payment,authorization;
    int stockBefore;

    /** Through the production path: accepted, reserved, created at PAY.JP and left there by the customer. */
    void stuck(){stuck(Duration.ofSeconds(4));}
    void stuck(Duration window){
        f=OrderEditAdmissionServicePostgresTest.fixture(false);added=helper().product(f,7000);stockBefore=stock();
        admission=accepted(f,List.of(new Line(null,added,1)),OrderChangeRoutePolicy.Route.REAUTHORIZE);mockTransport(f);
        if(window!=null)OrderEditAdmissionServicePostgresTest.tx(()->db.jdbc.queryForObject("select f125_execution_initialize(?,?::interval)",Object.class,admission,window.toMillis()+" milliseconds"));
        assertThat(service().execute(admission).status()).isEqualTo(OrderEditExecutionService.Status.HELD);
        assertHeld(admission);assertThat(stock()).isEqualTo(stockBefore-1);
        payment=db.jdbc.queryForObject("select new_payment_id from order_change_operations where id=?",Long.class,operation);
        authorization=db.jdbc.queryForObject("select id from payment_transactions where payment_id=?",Long.class,payment);
        when(gateway.cancelUnpaidCheckout(any())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(((CancellationRequest)call.getArgument(0)).providerPaymentId()).isEqualTo(newFlow);
            assertThat(status()).isEqualTo("SENDING");
            newStatus="canceled";return new CancellationResult(CancellationResultStatus.CANCELLED,newFlow);
        });
        if(window!=null)db.jdbc.queryForObject("select pg_sleep(greatest(0,extract(epoch from automatic_until-clock_timestamp()))+0.05) from order_edit_execution_controls where admission_id=?",Object.class,admission);
        clearInvocations(gateway);
    }
    void review(){
        assertThat(synchronization.synchronize(snapshots.find(authorization).orElseThrow())).isNotNull();
        assertThat(one("select state||':'||financial_review_reason from order_change_operations where id=?",operation)).isEqualTo("MANUAL_REVIEW:UNSETTLED_AUTH_AFTER_GRACE");
    }
    Outcome close(){return closures.close(f.order(),ADMIN);}
    ChangeFlowClosureService impatient(){return new ChangeFlowClosureService(store,gateway,new Timing(Duration.ZERO,Duration.ZERO,Duration.ofHours(23),Duration.ZERO));}
    int stock(){return db.jdbc.queryForObject("select stock from products where id=?",Integer.class,added);}
    String one(String sql,Object id){return db.jdbc.queryForObject(sql,String.class,id);}
    int count(String sql,Object id){return db.jdbc.queryForObject("select count(*) from "+sql,Integer.class,id);}
    String status(){return one("select status from order_change_flow_closures where operation_id=? order by id desc limit 1",operation);}
    List<String> sends(){return store.sends(store.forOrder(f.order()).getFirst().id()).stream().map(s->s.status().name()).toList();}
    void assertClosed(){
        assertThat(status()).isEqualTo("CLOSED");assertThat(operationState()).isEqualTo("ABORTED");
        assertThat(one("select status from payments where id=?",payment)).isEqualTo("FAILED");
        assertThat(one("select status||':'||failure_code||':'||provider_transaction_id from payment_transactions where id=?",authorization)).isEqualTo("FAILED:FLOW_CANCELED:"+newFlow);
        assertThat(one("select execution_state||':'||reason_code from order_edit_admissions where id=?",admission)).isEqualTo("CLOSED_UNAPPLIED:REAUTH_UNFINISHED");
        assertThat(db.jdbc.queryForList("select reservation_state from order_edit_inventory_plans where admission_id=? and reserve_quantity>0",String.class,admission)).containsOnly("RELEASED");
        assertThat(stock()).isEqualTo(stockBefore);
        assertThat(count("stock_movements where movement_type='ORDER_CHANGE_RELEASE' and order_id=?",f.order())).isOne();
        assertThat(one("select status from payments where id=?",f.payment())).isEqualTo("AUTHORIZED");
        assertOriginalContent(f);
        assertThat(db.jdbc.queryForObject("select f131_order_has_blocking_admission(?)",Boolean.class,f.order())).isFalse();
        assertThat(new com.example.ecsite.service.orderchange.AdminOrderHoldViewService(db.jdbc,Duration.ofMinutes(30)).listForOrder(f.order())).isEmpty();
    }
    String operationState(){return one("select state from order_change_operations where id=?",operation);}
    void assertOpen(String expected){assertThat(status()).isEqualTo(expected);assertUntouched();}
    void assertUntouched(){
        assertThat(operationState()).isIn("AUTHORIZING","REQUIRES_ACTION","MANUAL_REVIEW");
        assertThat(one("select status from payments where id=?",payment)).isIn("PENDING","REQUIRES_ACTION");
        assertThat(one("select status from payment_transactions where id=?",authorization)).isEqualTo("PENDING");
        assertThat(one("select execution_state from order_edit_admissions where id=?",admission)).isEqualTo("RECOVERY_PENDING");
        assertHeld(admission);assertThat(stock()).isEqualTo(stockBefore-1);
    }
    static PaymentGatewayException failure(Integer http,String code){
        return new PaymentGatewayException(http==null?PaymentGatewayErrorCategory.TRANSPORT_RESULT_UNKNOWN:http>=500?PaymentGatewayErrorCategory.SERVER_RESULT_UNKNOWN:PaymentGatewayErrorCategory.OTHER_HTTP_ERROR,http,code,true);
    }
    /** Nothing may stay open: the next test's runtime refuses other generations' work. */
    void finish(){newStatus="canceled";impatient().recover(100);assertClosed();}

    @ParameterizedTest @ValueSource(strings={"requires_payment_method","requires_confirmation","requires_action"})
    void closesAfterTheFlowIsReadAsCanceled(String provider){
        stuck();newStatus=provider;
        assertThat(close()).isEqualTo(Outcome.CLOSED);assertClosed();
        verify(gateway,times(1)).cancelUnpaidCheckout(any());verify(gateway,never()).sendOrderChangeRequest(any());
        assertThat(sends()).containsExactly("ACCEPTED");
        assertThatThrownBy(this::close).isInstanceOf(ChangeFlowClosureException.class);
        verify(gateway,times(1)).cancelUnpaidCheckout(any());
    }
    @Test void aManualReviewIsClosedTheSameWay(){stuck();review();assertThat(close()).isEqualTo(Outcome.CLOSED);assertClosed();}
    @Test void aRequiredActionObservedLocallyIsClosed(){
        stuck();newStatus="requires_action";synchronization.synchronize(snapshots.find(authorization).orElseThrow());
        assertThat(operationState()).isEqualTo("REQUIRES_ACTION");
        assertThat(close()).isEqualTo(Outcome.CLOSED);assertClosed();
    }
    @Test void anAlreadyCanceledFlowNeedsNoSend(){
        stuck();newStatus="canceled";assertThat(close()).isEqualTo(Outcome.CLOSED);assertClosed();verify(gateway,never()).cancelUnpaidCheckout(any());
    }
    @Test void theAutomaticWindowIsKept(){
        stuck(null);
        assertThat(store.closableOperation(f.order())).isEmpty();
        assertThat(store.request(f.order(),operation,ADMIN)).isEmpty();
        assertThatThrownBy(this::insert).hasMessageContaining("not completed in time");
        assertThatThrownBy(this::close).isInstanceOf(ChangeFlowClosureException.class);assertUntouched();
        verify(gateway,never()).cancelUnpaidCheckout(any());
    }
    @ParameterizedTest @CsvSource({"requires_capture,AUTHORIZED","processing,PROCESSING"})
    void otherProviderStatesAreLeftAlone(String provider,String expected){
        stuck();newStatus=provider;assertThat(close().name()).isEqualTo(expected);
        assertThat(status()).isEqualTo("RELEASED");assertUntouched();verify(gateway,never()).cancelUnpaidCheckout(any());
        newStatus="requires_payment_method";assertThat(close()).isEqualTo(Outcome.CLOSED);assertClosed();
    }
    @Test void anUnreadableFlowIsLeftAlone(){
        stuck();when(gateway.observeOrderChangeFlow(newFlow)).thenThrow(failure(null,null));
        assertThat(close()).isEqualTo(Outcome.UNREADABLE);assertOpen("RELEASED");
    }
    @Test void aFlowOfAnotherChangeNeverCloses(){
        stuck();newStatus="canceled";
        when(gateway.observeOrderChangeFlow(newFlow)).thenAnswer(c->observation(newFlow,"canceled",afterAmount,Map.of("order_id",Long.toString(f.order()),"order_change_operation_id",UUID.randomUUID().toString())));
        assertThat(close()).isEqualTo(Outcome.CHANGED);assertOpen("RELEASED");
        when(gateway.observeOrderChangeFlow(newFlow)).thenAnswer(c->observation(newFlow,"canceled",afterAmount+1,Map.of("order_id",Long.toString(f.order()),"order_change_operation_id",operation.toString())));
        assertThat(close()).isEqualTo(Outcome.CHANGED);assertOpen("RELEASED");
    }
    /** The success arrives between our GET and the cancel: it is not applied, and the cancel wins. */
    @Test void anAuthorizationBetweenTheReadAndTheCancelIsNotApplied(){
        stuck();
        doAnswer(call->{
            newStatus="requires_capture";
            assertThat(synchronization.synchronize(snapshots.find(authorization).orElseThrow())).isEqualTo(com.example.ecsite.service.payment.PaymentSynchronizationOutcome.WAITING);
            assertOpen("SENDING");newStatus="canceled";preAuthorization=false;
            return new CancellationResult(CancellationResultStatus.CANCELLED,newFlow);
        }).when(gateway).cancelUnpaidCheckout(any());
        assertThat(close()).isEqualTo(Outcome.CLOSED);assertClosed();
        newStatus="requires_capture";synchronization.synchronize(snapshots.find(authorization).orElseThrow());assertClosed();
    }
    @Test void anUnknownAnswerKeepsTheFenceAndIsResolvedWithTheSameKey(){
        stuck();doThrow(failure(null,null)).when(gateway).cancelUnpaidCheckout(any());
        assertThat(close()).isEqualTo(Outcome.UNCONFIRMED);assertOpen("SENDING");
        newStatus="requires_capture";
        assertThat(synchronization.synchronize(snapshots.find(authorization).orElseThrow())).isEqualTo(com.example.ecsite.service.payment.PaymentSynchronizationOutcome.WAITING);
        assertOpen("SENDING");newStatus="requires_payment_method";
        impatient().recover(100);assertThat(sends()).containsExactly("UNCONFIRMED");
        var keys=org.mockito.ArgumentCaptor.forClass(CancellationRequest.class);verify(gateway,times(2)).cancelUnpaidCheckout(keys.capture());
        assertThat(keys.getAllValues().stream().map(CancellationRequest::idempotencyKey).distinct()).hasSize(1);
        finish();
    }
    @Test void aRejectedCancelLeavesTheChangeAndAllowsAnotherClosure(){
        stuck();doThrow(failure(400,"invalid_status")).when(gateway).cancelUnpaidCheckout(any());
        assertThat(close()).isEqualTo(Outcome.REJECTED);assertOpen("REJECTED");
        newStatus="canceled";assertThat(close()).isEqualTo(Outcome.CLOSED);assertClosed();
    }
    @Test void anErrorIsRetriedWithANewKeyFiveTimesAtMost(){
        stuck();doThrow(failure(500,"internal_server_error")).when(gateway).cancelUnpaidCheckout(any());
        close();closures.recover(100);assertThat(sends()).hasSize(1);
        for(int i=0;i<6;i++)impatient().recover(100);
        assertThat(sends()).hasSize(5);finish();
    }
    @Test void aCrashAfterTheRequestIsReleasedAndNeverSent(){
        stuck();var closure=store.request(f.order(),operation,ADMIN).orElseThrow();
        assertThatThrownBy(this::close).isInstanceOf(ChangeFlowClosureException.class);
        impatient().recover(100);assertOpen("RELEASED");assertThat(store.startSending(closure)).isEmpty();
        verify(gateway,never()).cancelUnpaidCheckout(any());
    }
    @Test void aCrashWhileSendingIsResolved(){
        stuck();store.startSending(store.request(f.order(),operation,ADMIN).orElseThrow());
        closures.recover(100);assertOpen("SENDING");impatient().recover(100);assertClosed();
        verify(gateway,times(1)).cancelUnpaidCheckout(any());
    }
    @Test void aStoppedRuntimeWritesAndSendsNothing(){
        stuck();stopRuntime();
        assertThatThrownBy(this::close).isInstanceOf(ChangeFlowClosureException.class);
        assertThat(count("order_change_flow_closures where operation_id=?",operation)).isZero();
        verifyNoInteractions(gateway);
    }
    @Test void twoAdministratorsSendOnce() throws Exception {
        stuck();var pool=Executors.newFixedThreadPool(2);
        try{
            var tasks=new ArrayList<Future<?>>();
            for(int i=0;i<2;i++)tasks.add(pool.submit(()->{try{return close();}catch(ChangeFlowClosureException|org.springframework.dao.DataAccessException refused){return null;}}));
            for(var t:tasks)t.get(30,TimeUnit.SECONDS);
        }finally{pool.shutdownNow();}
        assertClosed();verify(gateway,times(1)).cancelUnpaidCheckout(any());
    }
    @Test void theDatabaseFencesAnOpenClosure(){
        stuck();
        db.jdbc.update("update order_change_operations set browser_confirmation_issued_at=timezone('Asia/Tokyo',clock_timestamp()) where id=?",operation);
        store.request(f.order(),operation,ADMIN).orElseThrow();String fence="being closed as unfinished";
        assertThatThrownBy(()->db.jdbc.update("update payments set status='AUTHORIZED' where id=?",payment)).hasMessageContaining(fence);
        assertThatThrownBy(()->db.jdbc.update("update payment_transactions set status='SUCCEEDED',completed_at=localtimestamp where id=?",authorization)).hasMessageContaining(fence);
        assertThatThrownBy(()->db.jdbc.update("update order_change_operations set state='AUTHORIZED',version=version+1 where id=?",operation)).hasMessageContaining(fence);
        assertThatThrownBy(()->db.jdbc.update("update order_change_operations set state='ABORTED',version=version+1 where id=?",operation)).hasMessageContaining(fence);
        assertThatThrownBy(()->db.jdbc.update("insert into order_change_card_sends(user_id,order_id,operation_id,admission_id,payment_id,transaction_id,send_type,provider_payment_flow_id,provider_customer_id,provider_payment_method_id,idempotency_key,status,created_at) values (?,?,?,?,?,?,'FLOW_UPDATE',?,'cus_x','pm_x',?,'PENDING',localtimestamp)",f.customer(),f.order(),operation,admission,payment,authorization,newFlow,UUID.randomUUID().toString())).hasMessageContaining(fence);
        assertThatThrownBy(()->db.jdbc.update("insert into order_change_financial_requests overriding system value select (jsonb_populate_record(q,'{\"id\":-1}')).* from order_change_financial_requests q where operation_id=?",operation)).hasMessageContaining(fence);
        assertThatThrownBy(()->db.jdbc.update("""
            insert into order_edit_failure_closure_controls(id,admission_id,operation_id,order_id,generation_id,old_payment_id,new_payment_id,
             authorization_id,create_request_id,flow_id,requester_type,actor_name,reason,execution_version,history_digest)
            select gen_random_uuid(),r.id,op.id,r.order_id,r.db_generation_id,r.base_payment_id,op.new_payment_id,q.transaction_id,
             q.id,p.provider_payment_id,'CUSTOMER',r.actor_username,'THREE_DS_FAILED',x.version,f125_failure_history(r.id)
            from order_edit_admissions r join order_change_operations op on op.order_id=r.order_id and op.submission_key=r.submission_key
            join payments p on p.id=op.new_payment_id join order_change_financial_requests q on q.operation_id=op.id and q.request_kind='CREATE'
            join order_edit_execution_controls x on x.admission_id=r.id where r.id=?""",admission)).hasMessageContaining(fence);
        // The application stops before the database has to refuse.
        assertThatCode(()->financeTransactions.cancellation(f.order(),operation,false,null)).doesNotThrowAnyException();
        assertOpen("REQUESTED");store.release(store.open(f.order()).orElseThrow().id(),ReleaseReason.STALE);
        assertThat(close()).isEqualTo(Outcome.CLOSED);
        assertThatThrownBy(()->db.jdbc.update("update payments set status='AUTHORIZED' where id=?",payment)).hasMessageContaining("closed as unfinished");
        assertThatThrownBy(()->db.jdbc.update("update payment_transactions set status='PENDING',completed_at=null where id=?",authorization)).hasMessageContaining("closed as unfinished");
        assertThatThrownBy(()->db.jdbc.update("update order_change_operations set state='AUTHORIZING',version=version+1 where id=?",operation)).hasMessageContaining("closed as unfinished");
        assertClosed();
    }
    @Test void theDatabaseRefusesFalseHistories(){
        stuck();var closure=store.request(f.order(),operation,ADMIN).orElseThrow();
        assertThatThrownBy(()->db.jdbc.update("update order_change_flow_closures set status='CLOSED' where id=?",closure.id())).hasMessageContaining("together with its proof");
        assertThatThrownBy(()->db.jdbc.update("update order_change_flow_closures set internal_note='x' where id=?",closure.id())).hasMessageContaining("cannot be redefined");
        assertThatThrownBy(()->db.jdbc.update("delete from order_change_flow_closures where id=?",closure.id())).hasMessageContaining("cannot be removed");
        assertThatThrownBy(()->db.jdbc.execute("truncate order_change_flow_closures cascade")).hasMessageContaining("Order change flow closures cannot be removed");
        assertThatThrownBy(()->db.jdbc.execute("truncate order_change_flow_closure_sends")).hasMessageContaining("Order change flow closure sends cannot be removed");
        assertThatThrownBy(()->db.jdbc.update("insert into order_change_flow_closure_sends(closure_id,idempotency_key,status,created_at) values (?,'early','PENDING',localtimestamp)",closure.id())).hasMessageContaining("only for a sending closure");
        assertThatThrownBy(this::insert).hasMessageContaining("uq_order_change_flow_closure_open");
        String good=evidence("canceled",afterAmount);
        for(String bad:List.of("{}","null",evidence("requires_payment_method",afterAmount),evidence("canceled",afterAmount+1),good.replace("\"jpy\"","\"usd\""),
                good.replace("\"livemode\":false","\"livemode\":true"),good.replace("\"amountReceived\":null","\"amountReceived\":1"),good.replace(operation.toString(),UUID.randomUUID().toString())))
            assertThatThrownBy(()->store.closeUnfinished(closure,bad)).as(bad).isInstanceOf(org.springframework.dao.DataAccessException.class);
        // A proof without the whole closure cannot commit.
        assertThatThrownBy(()->db.jdbc.update("insert into order_edit_unfinished_closures(admission_id,closure_id,order_id,operation_id,new_payment_id,observation,document,canonical,digest) values (?,?,?,?,?,?::jsonb,'{}','\\x00',sha256('\\x00'))",admission,closure.id(),f.order(),operation,payment,good))
            .rootCause().hasMessageContaining("Incomplete atomic");
        assertOpen("REQUESTED");
        store.closeUnfinished(closure,good);assertClosed();store.closeUnfinished(closure,good);assertClosed();
        assertThatThrownBy(()->db.jdbc.update("update order_edit_unfinished_closures set observation='{}' where admission_id=?",admission)).hasMessageContaining("append only");
        assertThatThrownBy(()->db.jdbc.execute("truncate order_edit_unfinished_closures")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    /** The proof authorizes exactly its own closure, checked where the evidence is written. */
    @Test void theProofPermitsOnlyItsOwnClosure(){
        stuck();var closure=store.request(f.order(),operation,ADMIN).orElseThrow();
        assertThatThrownBy(()->OrderEditAdmissionServicePostgresTest.tx(()->{
            db.jdbc.update("insert into order_edit_unfinished_closures(admission_id,closure_id,order_id,operation_id,new_payment_id,observation) values (?,?,?,?,?,?::jsonb)",admission,closure.id(),f.order(),operation,payment,evidence("canceled",afterAmount));
            return db.jdbc.update("insert into order_edit_unapplied_evidences(admission_id,order_id,db_generation_id,invalidated_token,control_version,terminal_state,reason_code,closure_request_id) select admission_id,order_id,db_generation_id,fencing_token+1,version+1,'CLOSED_UNAPPLIED','CUSTOMER_REQUESTED',? from order_edit_execution_controls where admission_id=?",operation,admission);
        })).hasMessageContaining("permits only its own closure");
        assertOpen("REQUESTED");store.closeUnfinished(closure,evidence("canceled",afterAmount));assertClosed();
    }
    @Test void aSettledNewPaymentIsNotClosable(){
        stuck();assertThat(closable()).isTrue();
        OrderEditAdmissionServicePostgresTest.tx(()->{
            for(String settled:List.of("FAILED","CANCELLED")){
                db.jdbc.update("update payments set status=? where id=?",settled,payment);
                assertThat(closable()).as(settled).isFalse();
            }
            return db.jdbc.update("update payments set status='PENDING' where id=?",payment);
        });
        assertThat(close()).isEqualTo(Outcome.CLOSED);assertClosed();
    }
    /** A change that no longer matches its admission is not offered: the closure could not be written. */
    @Test void aChangedOrderIsNotOffered(){
        stuck();assertThat(closable()).isTrue();
        assertThatThrownBy(()->OrderEditAdmissionServicePostgresTest.tx(()->{
            assertThat(db.jdbc.update("update orders set shipping_city=coalesce(shipping_city,'')||'x' where id=?",f.order())).isOne();
            // The application uses its own connection; this transaction sees the change.
            assertThat(closable()).isFalse();
            throw new IllegalStateException("rolled back");
        })).hasMessage("rolled back");
        assertThat(close()).isEqualTo(Outcome.CLOSED);assertClosed();
    }
    boolean closable(){return Boolean.TRUE.equals(db.jdbc.queryForObject("select f143_change_closable(?)",Boolean.class,operation));}
    String evidence(String status,int amount){
        return "{\"flow\":\""+newFlow+"\",\"status\":\""+status+"\",\"amount\":"+amount+",\"amountReceived\":null,\"amountCapturable\":null,\"currency\":\"jpy\",\"captureMethod\":\"manual\",\"livemode\":false,\"order\":\""+f.order()+"\",\"operation\":\""+operation+"\"}";
    }
    void insert(){db.jdbc.update("insert into order_change_flow_closures(order_id,operation_id,admission_id,payment_id,transaction_id,provider_payment_flow_id,requested_by_type,requested_by_account_id,requested_by_username,status,created_at) values (?,?,?,?,?,?,'ADMIN',1,'closer','REQUESTED',localtimestamp)",f.order(),operation,admission,payment,authorization,newFlow);}
    /** The customer may edit the original order again before its deadline. */
    @Test void theOrderCanBeEditedAgainAfterwards(){
        stuck();assertThat(close()).isEqualTo(Outcome.CLOSED);
        preAuthorization=false;newStatus="requires_capture";reset(gateway);mockTransport(f);
        UUID again=accepted(f,List.of(new Line(null,added,1)),OrderChangeRoutePolicy.Route.REAUTHORIZE);
        assertThat(service().execute(again).status()).isEqualTo(OrderEditExecutionService.Status.COMPLETED);
    }
    @Test void theAdministratorClosesFromTheOrderDetail() throws Exception {
        stuck();
        long id=db.jdbc.queryForObject("insert into admin_accounts(username,password,enabled,created_at,updated_at) values (?,'unused',true,localtimestamp,localtimestamp) returning id",Long.class,"f143-"+f.order());
        var account=user(new com.example.ecsite.security.AdminUserDetails(id,"f143-"+f.order(),"unused",true,List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        var page=mvc.perform(get("/admin/orders/{id}",f.order()).with(account)).andExpect(status200()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(page).contains("完了しなかった注文変更を終了する");
        mvc.perform(post("/admin/orders/{id}/close-unfinished-change",f.order()).with(account)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(post("/admin/orders/{id}/close-unfinished-change",f.order()).with(account).with(csrf()).param("internalNote"," 確認 ")).andExpect(flash().attributeExists("successMessage"));
        assertClosed();
        page=mvc.perform(get("/admin/orders/{id}",f.order()).with(account)).andExpect(status200()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(page).contains("確認").doesNotContain("完了しなかった注文変更を終了する</button>");
        mvc.perform(post("/admin/orders/{id}/close-unfinished-change",f.order()).with(account).with(csrf())).andExpect(flash().attributeExists("errorMessage"));
    }
    static org.springframework.test.web.servlet.ResultMatcher status200(){return org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk();}
}
