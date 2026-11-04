package com.example.ecsite.payment.safety;

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
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.example.ecsite.payment.*;
import com.example.ecsite.payment.capture.CaptureConnectionIdentity;
import com.example.ecsite.payment.orderchange.OrderChangeRequestSender;
import com.example.ecsite.payment.payjp.*;
import com.example.ecsite.service.order.evidence.*;
import com.example.ecsite.service.orderchange.OrderReauthorizationService;
import com.example.ecsite.support.CaptureConnectionTest;
import com.example.ecsite.support.isolation.IsolatedDatabaseGuard;
import static com.example.ecsite.service.order.evidence.OrderEditCandidate.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real Feature124 Spring transactions and sender, with provider transport replaced by raw JSON observations. */
@SpringBootTest(properties={"logging.level.root=WARN","spring.jpa.show-sql=false","logging.level.org.springframework=WARN","logging.level.org.hibernate=WARN"})
@CaptureConnectionTest
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class OrderEditReauthorizationIntegrationTest {
    static PostgreSQLContainer container;
    @DynamicPropertySource static void dedicatedCommitTrackingDatabase(DynamicPropertyRegistry properties){
        PaymentSafetyExpandPostgresTest.verifyContainerBeforeAnyDatabaseAccess();
        container=new PostgreSQLContainer("postgres:17")
            .withDatabaseName("isolated_f125_admission_"+UUID.randomUUID().toString().replace("-",""))
            .withUsername("admission_test").withPassword(UUID.randomUUID().toString())
            .withCommand("postgres","-c","track_commit_timestamp=on","-c","synchronous_commit=on","-c","fsync=on");
        container.start();
        properties.add("spring.datasource.url",container::getJdbcUrl);
        properties.add("spring.datasource.username",container::getUsername);
        properties.add("spring.datasource.password",container::getPassword);
        properties.add("spring.flyway.url",container::getJdbcUrl);
        properties.add("spring.flyway.user",container::getUsername);
        properties.add("spring.flyway.password",container::getPassword);
    }
    @AfterAll static void stopDedicatedDatabase(){if(container!=null)container.stop();}
    static final CaptureConnectionIdentity CONNECTION=new CaptureConnectionIdentity("test-capture","test-account","TEST","https://api.pay.jp");
    @Autowired Environment environment;
    @Autowired JdbcTemplate applicationJdbc;
    @Autowired OrderReauthorizationService finance;
    @Autowired com.example.ecsite.service.orderchange.OrderReauthorizationTransactions financeTransactions;
    @Autowired com.example.ecsite.payment.orderchange.OrderChangeFinancialRequestStore requestStore;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @MockitoBean PaymentGateway gateway;
    PaymentSafetyExpandPostgresTest db;
    String newStatus,oldCancelMode;
    final AtomicBoolean oldCancelled=new AtomicBoolean();
    String oldFlow,newFlow;
    UUID operation;
    int afterAmount;

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
        reset(gateway);newStatus="requires_capture";oldCancelMode="success";oldCancelled.set(false);operation=null;
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
        fields.put("amount_received",0);Integer capturable=null;
        if(status.equals("requires_capture"))capturable=amount;else if(status.equals("canceled"))capturable=0;
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

    @Test void mixedDeletionAndNewItemCompleteThroughRealFinanceAndReplayWithoutPosts(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);long added=helper().product(f,7000);
        UUID id=accepted(f,List.of(new Line(null,added,1)),OrderChangeRoutePolicy.Route.REAUTHORIZE);mockTransport(f);
        byte[] baseline=db.jdbc.queryForObject("select financial_canonical from order_edit_admissions where id=?",byte[].class,id);
        var execution=service();var result=execution.execute(id);assertThat(result.status()).isEqualTo(OrderEditExecutionService.Status.COMPLETED);
        assertThat(db.jdbc.queryForObject("select total_amount from orders where id=?",Integer.class,f.order())).isEqualTo(7000);
        assertThat(db.jdbc.queryForObject("select count(*) from order_items where id=?",Integer.class,f.item())).isZero();
        assertThat(db.jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product())).isEqualTo(102);
        assertThat(db.jdbc.queryForObject("select stock from products where id=?",Integer.class,added)).isEqualTo(99);
        assertThat(db.jdbc.queryForObject("select origin_kind from order_payment_evidence_epochs where origin_operation_id=?",String.class,result.operationId())).isEqualTo("REAUTH_COMPLETED");
        assertThat(db.jdbc.queryForObject("select status from payment_transactions where order_change_operation_id=? and purpose='ORDER_CHANGE_OLD_AUTH_CANCEL'",String.class,result.operationId())).isEqualTo("SUCCEEDED");
        assertThat(db.jdbc.queryForObject("select financial_canonical from order_edit_admissions where id=?",byte[].class,id)).isEqualTo(baseline);
        var read=OrderEditAdmissionServicePostgresTest.tx(()->new JdbcOrderPaymentEvidenceReader(db.jdbc).readLocked(f.order()));assertThat(read.decision().locallyVerified()).isTrue();
        assertThat(execution.execute(id)).isEqualTo(result);assertThat(execution.recover(id)).isEqualTo(result);
        verify(gateway,times(2)).sendOrderChangeRequest(any());verify(gateway,times(2)).observeOrderAuthorization(any());
    }
    @Test void exactAuthorizationCeilingRetainsAuthorizationWithoutPost(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);long added=helper().product(f,6000);
        UUID id=accepted(f,List.of(new Line(null,added,1)),OrderChangeRoutePolicy.Route.RETAIN_AUTH);mockTransport(f);
        assertThat(service().execute(id).status()).isEqualTo(OrderEditExecutionService.Status.COMPLETED);
        verify(gateway,never()).sendOrderChangeRequest(any());
        assertThat(db.jdbc.queryForObject("select active_payment_id from orders where id=?",Long.class,f.order())).isEqualTo(f.payment());
    }
    @Test void reauthorizationAfterRetainedDecreaseCancelsOriginalAmountAtCurrentBaseRevision(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);mockTransport(f);
        UUID retained=accepted(f,List.of(new Line(f.item(),f.product(),1)),OrderChangeRoutePolicy.Route.RETAIN_AUTH);
        assertThat(service().execute(retained).status()).isEqualTo(OrderEditExecutionService.Status.COMPLETED);
        assertThat(db.jdbc.queryForObject("select total_amount from orders where id=?",Integer.class,f.order())).isEqualTo(3550);
        long added=helper().product(f,7000);
        UUID id=accepted(f,List.of(new Line(null,added,1)),OrderChangeRoutePolicy.Route.REAUTHORIZE);
        var applied=service().execute(id);assertThat(applied.status()).isEqualTo(OrderEditExecutionService.Status.COMPLETED);
        var cancel=db.jdbc.queryForMap("select amount,order_content_revision from payment_transactions where order_change_operation_id=? and purpose='ORDER_CHANGE_OLD_AUTH_CANCEL'",applied.operationId());
        assertThat(((Number)cancel.get("amount")).intValue()).isEqualTo(6000);
        assertThat(((Number)cancel.get("order_content_revision")).intValue()).isEqualTo(1);
        assertThat(db.jdbc.queryForObject("select order_content_revision from payment_transactions where id=?",Integer.class,f.auth())).isZero();
        assertThat(db.jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order())).isEqualTo(2);
        verify(gateway,times(2)).sendOrderChangeRequest(any());
    }
    @Test void insufficientStockCannotCreateFinancialRequestOrPost(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);long added=helper().product(f,7000);
        UUID id=accepted(f,List.of(new Line(null,added,1)),OrderChangeRoutePolicy.Route.REAUTHORIZE);db.jdbc.update("update products set stock=0 where id=?",added);mockTransport(f);
        assertThat(service().execute(id).status()).isNotEqualTo(OrderEditExecutionService.Status.COMPLETED);assertOriginalContent(f);
        verify(gateway,never()).sendOrderChangeRequest(any());
        assertThat(db.jdbc.queryForObject("select count(*) from order_change_financial_requests where order_id=?",Integer.class,f.order())).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"canceled","processing"})
    void failedOrUnsettledNewAuthorizationNeverCancelsOldAuthorization(String status){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);long added=helper().product(f,7000);
        UUID id=accepted(f,List.of(new Line(null,added,1)),OrderChangeRoutePolicy.Route.REAUTHORIZE);mockTransport(f);newStatus=status;
        var execution=service();assertThat(execution.execute(id).status()).isNotEqualTo(OrderEditExecutionService.Status.COMPLETED);assertOriginalContent(f);
        assertThat(db.jdbc.queryForObject("select count(*) from order_change_financial_requests where order_id=? and request_kind='OLD_CANCEL'",Integer.class,f.order())).isZero();
        if(status.equals("canceled")){assertThat(db.jdbc.queryForObject("select stock from products where id=?",Integer.class,added)).isEqualTo(100);
            assertThat(db.jdbc.queryForList("select reservation_state from order_edit_inventory_plans where admission_id=? and reserve_quantity>0",String.class,id)).containsOnly("RELEASED");}
        else assertHeld(id);
        execution.execute(id);verify(gateway,times(1)).sendOrderChangeRequest(any());
    }
    @ParameterizedTest @ValueSource(strings={"failed","unknown"})
    void failedOrUnknownOldCancelKeepsHoldAndNeverReposts(String mode){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);long added=helper().product(f,7000);
        UUID id=accepted(f,List.of(new Line(null,added,1)),OrderChangeRoutePolicy.Route.REAUTHORIZE);mockTransport(f);oldCancelMode=mode;
        var execution=service();assertThat(execution.execute(id).status()).isNotEqualTo(OrderEditExecutionService.Status.COMPLETED);assertOriginalContent(f);assertHeld(id);
        execution.execute(id);execution.reconcile(id);assertOriginalContent(f);assertHeld(id);verify(gateway,times(2)).sendOrderChangeRequest(any());
    }
    @Test void unknownCreateResponseKeepsHoldAndDoesNotRetryPost(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);long added=helper().product(f,7000);
        UUID id=accepted(f,List.of(new Line(null,added,1)),OrderChangeRoutePolicy.Route.REAUTHORIZE);mockTransport(f);
        doThrow(new PaymentGatewayException("lost create response")).when(gateway).sendOrderChangeRequest(any());
        var execution=service();assertThat(execution.execute(id).status()).isNotEqualTo(OrderEditExecutionService.Status.COMPLETED);assertHeld(id);assertOriginalContent(f);
        execution.execute(id);execution.reconcile(id);verify(gateway,times(1)).sendOrderChangeRequest(any());assertHeld(id);
    }
    @Test void retiredOwnerCannotPrepareOrDispatchFinancialPost(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);long added=helper().product(f,7000);
        UUID id=accepted(f,List.of(new Line(null,added,1)),OrderChangeRoutePolicy.Route.REAUTHORIZE);mockTransport(f);
        var controls=new OrderEditExecutionCoordinator(db.source);var claim=new OrderEditExecution.Claim(id,UUID.randomUUID(),0);
        var claimed=controls.claim(claim);assertThat(claimed.outcome()).isEqualTo(OrderEditExecution.Outcome.RECORDED);
        var owner=new OrderEditApplicationPlan.Ownership(id,claim.attemptId(),claimed.view().token(),claimed.view().version());
        var applications=new OrderEditApplicationTransactions(db.source,CONNECTION);var prepared=applications.prepare(owner);
        UUID op=applications.initializeReauthorization(prepared,MockOrderAuthorizationTransport.observe(prepared.expected()));
        var view=financeTransactions.prepareForOrderEdit(f.order(),op,owner);assertThat(view.createRequest()).isNotNull();
        assertThat(financeTransactions.issueBrowserConfirmation(f.order(),op,null)).isFalse();
        long version=db.jdbc.queryForObject("select version from order_change_operations where id=?",Long.class,op);
        var abort=new com.example.ecsite.service.orderchange.OrderReauthorizationTransactions.AbortRequest(f.order(),f.customer(),op,UUID.randomUUID(),version,
            com.example.ecsite.service.orderchange.OrderReauthorizationTransactions.AbortRequesterType.CUSTOMER,
            com.example.ecsite.service.orderchange.OrderReauthorizationTransactions.AbortReasonCode.CUSTOMER_REQUEST);
        assertThat(financeTransactions.requestAbort(abort).result()).isEqualTo(com.example.ecsite.service.orderchange.OrderReauthorizationTransactions.AbortResult.NOT_ALLOWED);
        assertThat(controls.resolve(new OrderEditExecution.Resolution(id,UUID.randomUUID(),owner.attemptId(),owner.token(),owner.controlVersion(),OrderEditExecution.Disposition.UNKNOWN,"TEST_OWNER_RETIRED")).outcome()).isEqualTo(OrderEditExecution.Outcome.RECORDED);
        assertThatThrownBy(()->financeTransactions.prepareForOrderEdit(f.order(),op,owner)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->new OrderChangeRequestSender(requestStore,gateway,manager).send(view.createRequest(),owner)).isInstanceOf(RuntimeException.class);
        assertThat(db.jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,view.createRequest())).isEqualTo("READY");
        verify(gateway,never()).sendOrderChangeRequest(any());assertHeld(id);assertOriginalContent(f);
        // The old owner remains fenced; a new explicit recovery may consume only READY first-send eligibility.
        var recovered=resumeAfterPolicyDelay(id);assertThat(recovered.status()).isEqualTo(OrderEditExecutionService.Status.COMPLETED);
        verify(gateway,times(2)).sendOrderChangeRequest(any());
    }
    @Test void applicationFailureAfterConfirmedFinanceRollsBackContentAndPreservesHold(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);long added=helper().product(f,7000);
        UUID id=accepted(f,List.of(new Line(null,added,1)),OrderChangeRoutePolicy.Route.REAUTHORIZE);mockTransport(f);
        db.jdbc.execute("create function test_f3_corrupt_epoch() returns trigger language plpgsql as $$ begin if NEW.origin_kind='REAUTH_COMPLETED' then NEW.evidence_digest=decode(repeat('00',32),'hex'); end if; return NEW; end; $$");
        db.jdbc.execute("create trigger zz_test_f3_corrupt_epoch before insert on order_payment_evidence_epochs for each row execute function test_f3_corrupt_epoch()");
        try{assertThat(service().execute(id).status()).isNotEqualTo(OrderEditExecutionService.Status.COMPLETED);assertOriginalContent(f);assertHeld(id);
            assertThat(db.jdbc.queryForObject("select state from order_change_operations where id=?",String.class,operation)).isEqualTo("COMMIT_PENDING");
            assertThat(db.jdbc.queryForObject("select status from payments where id=?",String.class,f.payment())).isEqualTo("CANCELLED");
            assertThat(db.jdbc.queryForObject("select count(*) from order_payment_evidence_epochs where origin_operation_id=?",Integer.class,operation)).isZero();
            verify(gateway,times(2)).sendOrderChangeRequest(any());
        }finally{db.jdbc.execute("drop trigger zz_test_f3_corrupt_epoch on order_payment_evidence_epochs");db.jdbc.execute("drop function test_f3_corrupt_epoch()");}
    }
    @Test void committedReauthorizationWithLostApplyResponseRecoversWithoutAnotherPost(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);long added=helper().product(f,7000);
        UUID id=accepted(f,List.of(new Line(null,added,1)),OrderChangeRoutePolicy.Route.REAUTHORIZE);mockTransport(f);
        var lost=new AtomicBoolean();
        var commitManager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.source){
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){
                boolean applied=Boolean.TRUE.equals(db.jdbc.queryForObject("select exists(select 1 from order_edit_execution_attempt_results where admission_id=? and disposition='APPLIED')",Boolean.class,id));
                super.doCommit(status);
                if(applied&&lost.compareAndSet(false,true))throw new org.springframework.transaction.TransactionSystemException("lost reauthorization apply commit response");
            }
        };
        var execution=new OrderEditExecutionService(db.source,gateway,CONNECTION,commitManager,finance);
        var result=execution.execute(id);assertThat(lost).isTrue();assertThat(result.status()).isEqualTo(OrderEditExecutionService.Status.COMPLETED);
        assertThat(execution.recover(id)).isEqualTo(result);assertThat(execution.execute(id)).isEqualTo(result);
        assertThat(db.jdbc.queryForObject("select count(*) from order_edit_execution_attempt_results where admission_id=? and disposition='APPLIED'",Integer.class,id)).isEqualTo(1);
        verify(gateway,times(2)).sendOrderChangeRequest(any());verify(gateway,times(2)).observeOrderAuthorization(any());
    }
    @Test void concurrentExecutionCannotClaimOrSendTwice() throws Exception {
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);long added=helper().product(f,7000);
        UUID id=accepted(f,List.of(new Line(null,added,1)),OrderChangeRoutePolicy.Route.REAUTHORIZE);mockTransport(f);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var first=new AtomicBoolean(true);
        doAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if(first.compareAndSet(true,false)){entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();}
            return MockOrderAuthorizationTransport.observe(call.getArgument(0));
        }).when(gateway).observeOrderAuthorization(any());
        var pool=Executors.newSingleThreadExecutor();
        try{var running=pool.submit(()->service().execute(id));assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(service().execute(id).status()).isNotEqualTo(OrderEditExecutionService.Status.COMPLETED);
            release.countDown();assertThat(running.get(20,TimeUnit.SECONDS).status()).isEqualTo(OrderEditExecutionService.Status.COMPLETED);
            assertThat(db.jdbc.queryForObject("select count(*) from order_edit_execution_attempt_results where admission_id=? and disposition='APPLIED'",Integer.class,id)).isEqualTo(1);
            verify(gateway,times(2)).sendOrderChangeRequest(any());
        }finally{release.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }
    @Test void revisionMutationDuringInitialGetPreventsEveryFinancialPost(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);long added=helper().product(f,7000);
        UUID id=accepted(f,List.of(new Line(null,added,1)),OrderChangeRoutePolicy.Route.REAUTHORIZE);mockTransport(f);
        doAnswer(call->{
            var observed=MockOrderAuthorizationTransport.observe(call.getArgument(0));
            db.jdbc.update("update orders set content_revision=content_revision+1 where id=?",f.order());return observed;
        }).when(gateway).observeOrderAuthorization(any());
        assertThat(service().execute(id).status()).isNotEqualTo(OrderEditExecutionService.Status.COMPLETED);
        assertThat(db.jdbc.queryForObject("select count(*) from order_change_financial_requests where order_id=?",Integer.class,f.order())).isZero();
        assertThat(db.jdbc.queryForObject("select count(*) from order_change_operations where order_id=?",Integer.class,f.order())).isZero();
        verify(gateway,never()).sendOrderChangeRequest(any());
    }
    @Test void duplicateAndReverseFinancialObservationsAfterCompletionCannotChangeBusiness(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);long added=helper().product(f,7000);
        UUID id=accepted(f,List.of(new Line(null,added,1)),OrderChangeRoutePolicy.Route.REAUTHORIZE);mockTransport(f);
        var result=service().execute(id);assertThat(result.status()).isEqualTo(OrderEditExecutionService.Status.COMPLETED);
        String before=OrderEditAdmissionServicePostgresTest.business(f);
        assertThat(finance.reconcile(f.order(),result.operationId()).state()).isEqualTo("COMPLETED");
        assertThat(financeTransactions.cancellation(f.order(),result.operationId(),false,observation(oldFlow,"canceled",6000,Map.of())).state()).isEqualTo("COMPLETED");
        assertThat(financeTransactions.authorization(f.order(),result.operationId(),observation(newFlow,"requires_capture",afterAmount,
            Map.of("order_id",Long.toString(f.order()),"order_change_operation_id",result.operationId().toString()))).state()).isEqualTo("COMPLETED");
        assertThat(finance.reconcile(f.order(),result.operationId()).state()).isEqualTo("COMPLETED");
        assertThat(OrderEditAdmissionServicePostgresTest.business(f)).isEqualTo(before);
        assertThat(service().recover(id)).isEqualTo(result);verify(gateway,times(2)).sendOrderChangeRequest(any());
    }
    @Test void mixedExistingIncreaseAndDeletionAggregateReservationsAndReleases(){
        var f=helper().twoLines();
        long second=db.jdbc.queryForObject("select product_id from order_items where order_id=? and id<>?",Long.class,f.order(),f.item());
        UUID id=accepted(f,List.of(new Line(f.item(),f.product(),3)),OrderChangeRoutePolicy.Route.REAUTHORIZE);mockTransport(f);
        var result=service().execute(id);assertThat(result.status()).isEqualTo(OrderEditExecutionService.Status.COMPLETED);
        assertThat(db.jdbc.queryForObject("select total_amount from orders where id=?",Integer.class,f.order())).isEqualTo(9000);
        assertThat(db.jdbc.queryForObject("select quantity from order_items where id=?",Integer.class,f.item())).isEqualTo(3);
        assertThat(db.jdbc.queryForObject("select count(*) from order_items where order_id=?",Integer.class,f.order())).isEqualTo(1);
        assertThat(db.jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product())).isEqualTo(98);
        assertThat(db.jdbc.queryForObject("select stock from products where id=?",Integer.class,second)).isEqualTo(101);
        assertThat(db.jdbc.queryForObject("select reserve_quantity from order_edit_inventory_plans where admission_id=? and product_id=?",Integer.class,id,f.product())).isEqualTo(2);
        assertThat(db.jdbc.queryForObject("select reservation_state from order_edit_inventory_plans where admission_id=? and product_id=?",String.class,id,f.product())).isEqualTo("CONSUMED");
        verify(gateway,times(2)).sendOrderChangeRequest(any());
    }
    @Test void higherPricedSameProductReplacementNeedsNoPositiveHoldAndUsesNewFormalId(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);db.jdbc.update("update products set price=7000 where id=?",f.product());
        UUID id=accepted(f,List.of(new Line(null,f.product(),1)),OrderChangeRoutePolicy.Route.REAUTHORIZE);mockTransport(f);
        var result=service().execute(id);assertThat(result.status()).isEqualTo(OrderEditExecutionService.Status.COMPLETED);
        assertThat(db.jdbc.queryForObject("select total_amount from orders where id=?",Integer.class,f.order())).isEqualTo(7000);
        assertThat(db.jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product())).isEqualTo(101);
        long formal=db.jdbc.queryForObject("select id from order_items where order_id=?",Long.class,f.order());assertThat(formal).isNotEqualTo(f.item());
        assertThat(db.jdbc.queryForObject("select price from order_items where id=?",Integer.class,formal)).isEqualTo(7000);
        assertThat(db.jdbc.queryForObject("select count(*) from order_edit_inventory_plans where admission_id=? and reserve_quantity>0",Integer.class,id)).isZero();
        assertThat(db.jdbc.queryForObject("select count(*) from stock_movements where order_id=? and movement_type='ORDER_CHANGE_RESERVATION'",Integer.class,f.order())).isZero();
        assertThat(db.jdbc.queryForObject("select count(*) from stock_movements where order_id=? and movement_type='ORDER_CHANGE_RELEASE' and quantity=1",Integer.class,f.order())).isEqualTo(1);
        verify(gateway,times(2)).sendOrderChangeRequest(any());
    }
    @ParameterizedTest @ValueSource(strings={"requires_capture","canceled"})
    void directFinancialAndApplicationTransactionsPreserveAtomicBoundary(String state){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);long added=helper().product(f,7000);
        UUID id=accepted(f,List.of(new Line(null,added,1)),OrderChangeRoutePolicy.Route.REAUTHORIZE);mockTransport(f);newStatus=state;
        var controls=new OrderEditExecutionCoordinator(db.source);var claim=new OrderEditExecution.Claim(id,UUID.randomUUID(),0);var claimed=controls.claim(claim);
        assertThat(claimed.outcome()).isEqualTo(OrderEditExecution.Outcome.RECORDED);
        var owner=new OrderEditApplicationPlan.Ownership(id,claim.attemptId(),claimed.view().token(),claimed.view().version());
        var application=new OrderEditApplicationTransactions(db.source,CONNECTION);var prepared=application.prepare(owner);
        UUID op=application.initializeReauthorization(prepared,MockOrderAuthorizationTransport.observe(prepared.expected()));
        var view=finance.advanceForOrderEdit(f.order(),op,owner);
        if(state.equals("canceled")){assertThat(view.state()).isEqualTo("ABORTED");assertThat(db.jdbc.queryForObject("select stock from products where id=?",Integer.class,added)).isEqualTo(100);return;}
        assertThat(view.state()).isEqualTo("COMMIT_PENDING");
        var ready=application.prepareReauthorized(owner,op);var result=application.apply(ready,MockOrderAuthorizationTransport.observe(ready.expected()));
        assertThat(result.snapshot().totalAmount()).isEqualTo(7000);
    }

    @Test void committedOldCancelRequestBeforeSendCanResumeWithNewOwnerWithoutRepostingCreate(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);long added=helper().product(f,7000);
        UUID id=accepted(f,List.of(new Line(null,added,1)),OrderChangeRoutePolicy.Route.REAUTHORIZE);mockTransport(f);
        var controls=new OrderEditExecutionCoordinator(db.source);var claim=new OrderEditExecution.Claim(id,UUID.randomUUID(),0);var claimed=controls.claim(claim);
        var owner=new OrderEditApplicationPlan.Ownership(id,claim.attemptId(),claimed.view().token(),claimed.view().version());
        var application=new OrderEditApplicationTransactions(db.source,CONNECTION);var prepared=application.prepare(owner);
        UUID op=application.initializeReauthorization(prepared,MockOrderAuthorizationTransport.observe(prepared.expected()));
        var ready=financeTransactions.prepareForOrderEdit(f.order(),op,owner);
        var created=new OrderChangeRequestSender(requestStore,gateway,manager).send(ready.createRequest(),owner);
        financeTransactions.attachFlow(f.order(),op,created.id());
        var authorized=observation(created.id(),"requires_capture",7000,Map.of("order_id",Long.toString(f.order()),"order_change_operation_id",op.toString()));
        financeTransactions.authorization(f.order(),op,authorized);
        var decided=financeTransactions.decideForOrderEdit(f.order(),op,authorized,observation(oldFlow,"requires_capture",6000,Map.of()),owner);
        assertThat(decided.state()).isEqualTo("OLD_CANCEL_PENDING");
        // A pre-send webhook/GET must not consume READY as an unknown cancellation.
        assertThat(financeTransactions.cancellation(f.order(),op,false,observation(oldFlow,"requires_capture",6000,Map.of())).state()).isEqualTo("OLD_CANCEL_PENDING");
        assertThat(db.jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,decided.oldCancelRequest())).isEqualTo("READY");
        assertThat(controls.resolve(new OrderEditExecution.Resolution(id,UUID.randomUUID(),owner.attemptId(),owner.token(),owner.controlVersion(),OrderEditExecution.Disposition.UNKNOWN,"LOST_DECISION_COMMIT_RESPONSE")).outcome()).isEqualTo(OrderEditExecution.Outcome.RECORDED);
        assertThat(resumeAfterPolicyDelay(id).status()).isEqualTo(OrderEditExecutionService.Status.COMPLETED);
        verify(gateway,times(2)).sendOrderChangeRequest(any());
    }

    OrderEditExecutionService.Result resumeAfterPolicyDelay(UUID id){
        var execution=service();var resumed=execution.reconcile(id);
        if(resumed.status()==OrderEditExecutionService.Status.HELD){
            assertThat(db.jdbc.queryForObject("select execution_state from order_edit_admissions where id=?",String.class,id)).isEqualTo("QUEUED");
            // Observe the real DB backoff; never change its clock or protected control fields.
            db.jdbc.queryForObject("select pg_sleep(greatest(0,extract(epoch from next_attempt_at-clock_timestamp()))+0.02) from order_edit_execution_controls where admission_id=?",Object.class,id);
            return execution.execute(id);
        }
        return resumed;
    }

}
