package com.example.ecsite.service.orderchange;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.repository.*;
import com.example.ecsite.payment.*;
import com.example.ecsite.payment.orderchange.*;
import com.example.ecsite.payment.payjp.PayJpPaymentFlowObservation;

@SpringBootTest(properties={"logging.level.root=WARN","spring.jpa.show-sql=false"})
@com.example.ecsite.support.CaptureConnectionTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class OrderReauthorizationPostgresTest {
    @Autowired org.springframework.test.web.servlet.MockMvc adminMvc;
    @Autowired OrderReauthorizationService service;
    @Autowired OldCancelRecoveryService recovery;
    @Autowired OldCancelRecoveryAuditRepository recoveryAudits;
    @Autowired OrderReauthorizationTransactions resultTransactions;
    @Autowired OrderIncreaseService increases;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean OrderRepository orders;
    @Autowired UserRepository users;
    @Autowired CategoryRepository categories;
    @Autowired TaxCategoryRepository taxes;
    @Autowired ProductRepository products;
    @Autowired PaymentRepository payments;
    @Autowired PaymentTransactionRepository transactions;
    @Autowired com.example.ecsite.service.OrderStatusHistoryService histories;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean PaymentGateway gateway;
    @Autowired com.example.ecsite.service.payment.PayJpWebhookSyncService webhook;
    @Autowired com.example.ecsite.service.payment.PaymentReconciliationItemService reconciliation;
    @Autowired com.example.ecsite.service.payment.PaymentCancellationStartService cancellation;
    @Autowired com.example.ecsite.service.payment.PaymentCancellationResultService cancellationResults;
    @Autowired com.example.ecsite.service.OrderShippingPreparationService shippingPreparation;
    @Autowired com.example.ecsite.service.payment.PaymentCaptureService capture;
    @MockitoSpyBean java.time.Clock clock;
    @MockitoSpyBean OrderChangeFinancialRequestStore requestStore;
    org.mockito.stubbing.Answer<PayJpPaymentFlowObservation> transport;
    record Fixture(long order,long user,long item,long product,long oldPayment,String oldFlow){}
    <T>T tx(java.util.function.Supplier<T> body){return new TransactionTemplate(manager).execute(s->body.get());}
    Fixture fixture(){return tx(()->{
        var now=orders.findCurrentTimeInTokyo();String key=UUID.randomUUID().toString();
        var user=new User();user.setUsername("reauth-"+key);user.setPassword("unused");user.setEnabled(true);user.setCreatedAt(now);user.setUpdatedAt(now);users.saveAndFlush(user);
        var category=categories.saveAndFlush(new Category(key));var tax=taxes.findAll().getFirst();
        var product=new Product();product.setName("item");product.setPrice(900);product.setStock(20);product.setCategory(category);product.setTaxCategory(tax);products.saveAndFlush(product);
        var order=new Order(user.getId(),750,now,now.plusHours(1));
        order.addItem(new OrderItem(product.getId(),"ordered",category.getId(),category.getName(),tax.getId(),tax.getCode(),tax.getName(),tax.getTaxRate(),100,2));
        order.addCharge(new OrderCharge(OrderChargeType.SHIPPING,"送料・梱包料",550,tax.getId(),tax.getCode(),tax.getName(),tax.getTaxRate(),10));orders.saveAndFlush(order);
        histories.record(order,null,OrderStatus.ORDERED,OrderStatusHistoryActorType.USER,user.getId(),user.getUsername(),null);
        var payment=new Payment(order,PaymentProvider.PAYJP,PaymentMethod.CARD,750,now);payment.setProviderPaymentId("pfw_"+key,now);payment.markAuthorized(now);payments.saveAndFlush(payment);
        var auth=new PaymentTransaction(payment,PaymentTransactionType.AUTHORIZE,750,0,key,now,PaymentTransactionPurpose.INITIAL_ORDER_AUTHORIZATION);auth.markSucceeded(payment.getProviderPaymentId(),now);transactions.saveAndFlush(auth);
        return new Fixture(order.getId(),user.getId(),order.getItems().getFirst().getId(),product.getId(),payment.getId(),payment.getProviderPaymentId());
    });}
    OrderChangeStore.Operation accept(Fixture f,int quantity){int revision=jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order());return increases.accept(new OrderIncreaseService.Request(f.order(),f.user(),UUID.randomUUID(),revision,Map.of(f.item(),quantity)));}
    final AtomicReference<String> newState=new AtomicReference<>("requires_capture");
    final AtomicBoolean oldCancelled=new AtomicBoolean();
    String newFlow;
    PayJpPaymentFlowObservation observation(String flow,String status,int amount,Map<String,Object> metadata){return jsonObservation(flow,status,amount,metadata,"0");}
    void mockFlow(Fixture f,OrderChangeStore.Operation op){
        reset(gateway);newState.set("requires_capture");oldCancelled.set(false);newFlow="pfw_new_"+op.id();
        transport=call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var saved=((OrderChangeRequestSender.Dispatch)call.getArgument(0)).request();
            assertThat(jdbc.queryForObject("select request_body from order_change_financial_requests where id=?",byte[].class,saved.id())).isEqualTo(saved.httpRequest().body());
            return switch(saved.httpRequest().kind()){
                case "CREATE"->observation(newFlow,"requires_payment_method",op.afterAmount(),Map.of());
                case "OLD_CANCEL"->{oldCancelled.set(true);yield observation(f.oldFlow(),"canceled",op.beforeAmount(),Map.of());}
                case "NEW_ABORT"->{newState.set("canceled");yield observation(newFlow,"canceled",op.afterAmount(),Map.of());}
                default->throw new AssertionError();
            };
        };when(gateway.sendOrderChangeRequest(any())).thenAnswer(transport);
        when(gateway.observeOrderChangeFlow(anyString())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();String flow=call.getArgument(0);
            if(flow.equals(f.oldFlow()))return observation(flow,oldCancelled.get()?"canceled":"requires_capture",op.beforeAmount(),Map.of());
            assertThat(flow).isEqualTo(newFlow);
            return observation(flow,newState.get(),op.afterAmount(),Map.of("order_id",Long.toString(f.order()),"order_change_operation_id",op.id().toString()));
        });
    }
    long version(UUID op){return jdbc.queryForObject("select version from order_change_operations where id=?",Long.class,op);}
    OrderReauthorizationTransactions.AbortRequest abortCommand(Fixture f,OrderChangeStore.Operation op,UUID request,long expected){
        return new OrderReauthorizationTransactions.AbortRequest(f.order(),f.user(),op.id(),request,expected,
            OrderReauthorizationTransactions.AbortRequesterType.CUSTOMER,OrderReauthorizationTransactions.AbortReasonCode.CUSTOMER_REQUEST);
    }
    Map<String,List<String>> preservedBusiness(Fixture f,OrderChangeStore.Operation op){
        var snapshot=new LinkedHashMap<String,List<String>>();
        snapshot.put("order",jdbc.queryForList("select to_jsonb(t)::text from orders t where id=?",String.class,f.order()));
        snapshot.put("items",jdbc.queryForList("select to_jsonb(t)::text from order_items t where order_id=? order by id",String.class,f.order()));
        snapshot.put("oldPayment",jdbc.queryForList("select to_jsonb(t)::text from payments t where id=?",String.class,f.oldPayment()));
        snapshot.put("oldTransactions",jdbc.queryForList("select to_jsonb(t)::text from payment_transactions t where payment_id=? order by id",String.class,f.oldPayment()));
        snapshot.put("reservation",jdbc.queryForList("select to_jsonb(t)::text from inventory_reservations t where operation_id=? order by order_item_id",String.class,op.id()));
        snapshot.put("stockMovements",jdbc.queryForList("select to_jsonb(t)::text from stock_movements t where order_id=? order by id",String.class,f.order()));
        snapshot.put("product",jdbc.queryForList("select to_jsonb(t)::text from products t where id=?",String.class,f.product()));
        return snapshot;
    }
    void assertAbortHeld(Fixture f,OrderChangeStore.Operation op,Map<String,List<String>> before){
        assertThat(preservedBusiness(f,op)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from order_change_financial_requests where operation_id=? and request_kind in ('OLD_CANCEL','NEW_ABORT')",Integer.class,op.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from order_content_change_histories where order_id=?",Integer.class,f.order())).isZero();
        assertThat(state(op.id())).isNotIn("COMPLETED","ABORTED");
    }
    PayJpPaymentFlowObservation newAuthorized(Fixture f,OrderChangeStore.Operation op){return observation(newFlow,"requires_capture",op.afterAmount(),Map.of("order_id",Long.toString(f.order()),"order_change_operation_id",op.id().toString()));}
    void authorizeWithoutDecision(Fixture f,OrderChangeStore.Operation op){
        mockFlow(f,op);newState.set("requires_payment_method");service.advance(f.order(),op.id());
        resultTransactions.authorization(f.order(),op.id(),newAuthorized(f,op));newState.set("requires_capture");
    }
    OrderReauthorizationTransactions.RequestedAbortEvidence abortEvidence(Fixture f,OrderChangeStore.Operation op){
        var body=tools.jackson.databind.json.JsonMapper.builder().build().readTree("""
            {"id":"%s","status":"requires_capture","amount":%d,"currency":"jpy","capture_method":"manual",
             "livemode":false,"amount_received":null,"amount_capturable":%d,
             "metadata":{"order_id":"%s","order_change_operation_id":"%s"}}
            """.formatted(newFlow,op.afterAmount(),op.afterAmount(),f.order(),op.id()));
        long next=jdbc.queryForObject("select new_payment_id from order_change_operations where id=?",Long.class,op.id());
        return OrderReauthorizationTransactions.RequestedAbortEvidence.verify(f.order(),op.id(),next,newFlow,op.afterAmount(),
            new com.example.ecsite.payment.capture.CaptureConnectionIdentity("test-capture","test-account","TEST","https://api.pay.jp"),body);
    }
    OrderChangeStore.Operation requestedAuthorized(Fixture f){
        var op=accept(f,3);authorizeWithoutDecision(f,op);
        resultTransactions.requestAbort(abortCommand(f,op,UUID.randomUUID(),version(op.id())));return op;
    }
    Map<String,List<String>> closureDatabaseSnapshot(){
        var result=new TreeMap<String,List<String>>();
        for(var table:jdbc.queryForList("select tablename from pg_tables where schemaname='public' order by tablename",String.class))
            result.put(table,jdbc.queryForList("select to_jsonb(t)::text from public.\""+table+"\" t order by to_jsonb(t)::text",String.class));
        for(var sequence:jdbc.queryForList("select sequencename from pg_sequences where schemaname='public' order by sequencename",String.class))
            result.put("sequence:"+sequence,jdbc.queryForList("select jsonb_build_array(last_value,is_called)::text from public.\""+sequence+"\"",String.class));
        return result;
    }
    void mockClosureGet(){
        doAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return com.example.ecsite.payment.payjp.MockNewFlowClosureTransport.observe(call.getArgument(0));
        }).when(gateway).observeNewFlowClosure(any());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void closureGetWithOrWithoutAbortRequestChangesNoRowsOrSequences(boolean present){
        var f=fixture();var op=requestedAuthorized(f);
        if(present)resultTransactions.prepareRequestedAbort(f.order(),op.id(),version(op.id()),abortEvidence(f,op));
        mockClosureGet();clearInvocations(gateway);var before=closureDatabaseSnapshot();
        var control=new NewFlowClosureControl(resultTransactions,manager);var observed=control.observe(gateway,f.order(),op.id());
        var expected=tx(()->resultTransactions.loadNewFlowClosureExpected(f.order(),op.id()));
        assertThat(expected.abort().isPresent()).isEqualTo(present);observed.requireMatches(expected);observed.requireFresh();
        assertThat(closureDatabaseSnapshot()).isEqualTo(before);verify(gateway,times(1)).observeNewFlowClosure(any());verifyNoMoreInteractions(gateway);
    }
    @Test void closureGetAllowsUnconfirmedNewPaymentWithoutInventingSuccess(){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);newState.set("requires_payment_method");service.advance(f.order(),op.id());
        resultTransactions.requestAbort(abortCommand(f,op,UUID.randomUUID(),version(op.id())));mockClosureGet();
        var before=closureDatabaseSnapshot();new NewFlowClosureControl(resultTransactions,manager).observe(gateway,f.order(),op.id());
        assertThat(closureDatabaseSnapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select status from payments where id=(select new_payment_id from order_change_operations where id=?)",String.class,op.id())).isEqualTo("PENDING");
    }
    @Test void closureRejectsCancelTransactionWithoutRequest(){
        var f=fixture();var op=requestedAuthorized(f);
        tx(()->{var next=payments.findById(jdbc.queryForObject("select new_payment_id from order_change_operations where id=?",Long.class,op.id())).orElseThrow();
            transactions.saveAndFlush(PaymentTransaction.forOrderChange(next,PaymentTransactionType.CANCEL,op.afterAmount(),0,
                UUID.randomUUID().toString(),orders.findCurrentTimeInTokyo(),PaymentTransactionPurpose.ORDER_CHANGE_NEW_AUTH_ABORT,op.id()));return null;});
        clearInvocations(gateway);
        assertThatThrownBy(()->new NewFlowClosureControl(resultTransactions,manager).observe(gateway,f.order(),op.id())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(gateway);
    }
    @Test void closureGetRejectsChildFinancialChangeWithoutOperationVersionChange(){
        var f=fixture();var op=requestedAuthorized(f);long version=version(op.id());
        doAnswer(call->{var value=com.example.ecsite.payment.payjp.MockNewFlowClosureTransport.observe(call.getArgument(0));
            jdbc.update("update payment_transactions set completed_at=completed_at+interval '1 second' where order_change_operation_id=?",op.id());return value;
        }).when(gateway).observeNewFlowClosure(any());
        assertThatThrownBy(()->new NewFlowClosureControl(resultTransactions,manager).observe(gateway,f.order(),op.id())).isInstanceOf(IllegalStateException.class);
        assertThat(version(op.id())).isEqualTo(version);
    }
    @Test void closureGetAllowsStartedUnknownWithoutChangingDispatch(){
        var f=dispatchFixture();
        f.control().start(f.context(),f.observation());
        jdbc.update("update order_change_operations set state='ABORT_UNKNOWN',version=version+1 where id=?",f.operation().id());
        mockClosureGet();var before=closureDatabaseSnapshot();
        new NewFlowClosureControl(resultTransactions,manager).observe(gateway,f.base().order(),f.operation().id());
        assertThat(closureDatabaseSnapshot()).isEqualTo(before);
    }
    @Test void closureGetManualReviewAndMissingIntentAreRejectedBeforeTransport(){
        var f=fixture();var op=accept(f,3);authorizeWithoutDecision(f,op);clearInvocations(gateway);
        assertThatThrownBy(()->new NewFlowClosureControl(resultTransactions,manager).observe(gateway,f.order(),op.id())).isInstanceOf(IllegalStateException.class);
        resultTransactions.requestAbort(abortCommand(f,op,UUID.randomUUID(),version(op.id())));
        jdbc.update("update order_change_operations set state='MANUAL_REVIEW',version=version+1 where id=?",op.id());
        assertThatThrownBy(()->new NewFlowClosureControl(resultTransactions,manager).observe(gateway,f.order(),op.id())).isInstanceOf(IllegalStateException.class);verifyNoInteractions(gateway);
    }
    @Test void closureGetFailureLeavesAllRowsAndSequencesUntouched(){
        var f=dispatchFixture();var before=closureDatabaseSnapshot();
        doThrow(new PaymentGatewayException(PaymentGatewayErrorCategory.TRANSPORT_RESULT_UNKNOWN,null,null,true)).when(gateway).observeNewFlowClosure(any());
        assertThatThrownBy(()->new NewFlowClosureControl(resultTransactions,manager).observe(gateway,f.base().order(),f.operation().id())).isInstanceOf(PaymentGatewayException.class);
        assertThat(closureDatabaseSnapshot()).isEqualTo(before);
    }
    @Test void closureRejectsOperationVersionChangedDuringGet(){
        var f=fixture();var op=requestedAuthorized(f);
        doAnswer(call->{var value=com.example.ecsite.payment.payjp.MockNewFlowClosureTransport.observe(call.getArgument(0));
            jdbc.update("update order_change_operations set version=version+1 where id=?",op.id());return value;
        }).when(gateway).observeNewFlowClosure(any());
        assertThatThrownBy(()->new NewFlowClosureControl(resultTransactions,manager).observe(gateway,f.order(),op.id())).isInstanceOf(IllegalStateException.class);
    }
    @Test void closureCanObservePastPostDeadlineAndAlreadyObservedDispatch(){
        var f=dispatchFixture();var saved=f.request();
        var expired=new StoredOrderChangeRequest(saved.id(),saved.paymentId(),saved.transactionId(),saved.httpRequest(),
            saved.createdAt().minus(java.time.Duration.ofDays(2)),saved.replayNotAfter().minus(java.time.Duration.ofDays(2)));
        tx(()->{doReturn(expired).when(requestStore).find(saved.id());return null;});
        jdbc.update("update order_change_request_dispatches set state='STARTED',started_at=clock_timestamp() where request_id=?",saved.id());
        jdbc.update("update order_change_request_dispatches set state='OBSERVED',observed_at=clock_timestamp() where request_id=?",saved.id());
        mockClosureGet();var before=closureDatabaseSnapshot();new NewFlowClosureControl(resultTransactions,manager).observe(gateway,f.base().order(),f.operation().id());
        assertThat(closureDatabaseSnapshot()).isEqualTo(before);
    }
    @Test void closureConsumptionAtTransactionStartCannotBeReusedAfterRollback(){
        var f=fixture();var op=requestedAuthorized(f);mockClosureGet();var before=closureDatabaseSnapshot();
        var value=new NewFlowClosureControl(resultTransactions,manager).observe(gateway,f.order(),op.id());
        assertThatThrownBy(()->tx(()->{value.consumeBound();throw new IllegalStateException("Synthetic writer rollback; no writes");})).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(value::consumeBound).isInstanceOf(IllegalStateException.class);
        assertThat(closureDatabaseSnapshot()).isEqualTo(before);
    }
    @Test void closureRequestWithoutTransactionIsBlockedByDatabaseForeignKey(){
        var f=dispatchFixture();var before=closureDatabaseSnapshot();
        assertThatThrownBy(()->tx(()->jdbc.update("delete from payment_transactions where id=?",f.request().transactionId())))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(closureDatabaseSnapshot()).isEqualTo(before);
    }
    void seedClosureForGuardTest(OrderChangeStore.Operation op){
        jdbc.update("update order_change_operations set state='ABORTING',abort_flow_closed_at=clock_timestamp(),abort_flow_close_digest=decode(repeat('ab',32),'hex'),abort_flow_close_version=1,version=version+1 where id=?",op.id());
    }
    @Test void closureRecordedFlowRejectsLateAuthorizeBeforeCreateObserved(){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);newState.set("requires_payment_method");service.advance(f.order(),op.id());
        resultTransactions.requestAbort(abortCommand(f,op,UUID.randomUUID(),version(op.id())));seedClosureForGuardTest(op);
        var before=closureDatabaseSnapshot();resultTransactions.authorization(f.order(),op.id(),newAuthorized(f,op));
        assertThat(closureDatabaseSnapshot()).isEqualTo(before);
    }
    @Test void closureRecordedFlowDatabaseRejectsFinancialRollback(){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);newState.set("requires_payment_method");service.advance(f.order(),op.id());
        resultTransactions.requestAbort(abortCommand(f,op,UUID.randomUUID(),version(op.id())));seedClosureForGuardTest(op);
        assertThatThrownBy(()->jdbc.update("update payments set status='AUTHORIZED' where id=(select new_payment_id from order_change_operations where id=?)",op.id()))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    NewFlowClosureReconciliationService closureAutomation(){return new NewFlowClosureReconciliationService(jdbc,manager,resultTransactions,gateway);}
    void isolateClosureScan(){
        jdbc.update("update order_change_operations set state='MANUAL_REVIEW',version=version+1 where abort_requested_at is not null and abort_flow_closed_at is null and state<>'MANUAL_REVIEW'");
        jdbc.update("update order_change_closure_scan_cursor set after_id=null,upper_id=null where id=1");
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"ABSENT","READY","STARTED","ABORT_UNKNOWN","OBSERVED"})
    void automaticClosureScanSettlesOnlyKnownFinancialHistories(String phase){
        isolateClosureScan();var f=fixture();var op=phase.equals("ABSENT")?requestedUnconfirmed(f):requestedAuthorized(f);
        Long request=null;if(!phase.equals("ABSENT"))request=resultTransactions.prepareRequestedAbort(f.order(),op.id(),version(op.id()),abortEvidence(f,op)).id();
        if(List.of("STARTED","ABORT_UNKNOWN","OBSERVED").contains(phase))jdbc.update("update order_change_request_dispatches set state='STARTED',started_at=clock_timestamp() where request_id=?",request);
        if(List.of("ABORT_UNKNOWN","OBSERVED").contains(phase))jdbc.update("update order_change_operations set state='ABORT_UNKNOWN',version=version+1 where id=?",op.id());
        if(phase.equals("OBSERVED"))jdbc.update("update order_change_request_dispatches set state='OBSERVED',observed_at=clock_timestamp() where request_id=?",request);
        mockClosureAssessment("canceled",200);clearInvocations(gateway);var before=closureDatabaseSnapshot();var business=preservedBusiness(f,op);
        var report=closureAutomation().scan();assertThat(report.attempted()).isEqualTo(1);
        if(List.of("ABSENT","READY").contains(phase)){
            assertThat(report.outcomes().get("CONFLICT")).isEqualTo(1);
            assertThat(jdbc.queryForObject("select abort_flow_closed_at is null from order_change_operations where id=?",Boolean.class,op.id())).isTrue();
        }else{
            assertThat(report.outcomes().get("CLOSED_RECORDED")).isEqualTo(1);
            assertThat(closureAutomation().scan().attempted()).isZero();
        }
        assertThat(preservedBusiness(f,op)).isEqualTo(business);verify(gateway).assessNewFlowClosure(any());verifyNoMoreInteractions(gateway);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={200,404,500})
    void automaticClosureScanReportsFailuresAndKeepsAllFinancialData(int httpStatus){
        isolateClosureScan();var f=fixture();var op=requestedUnconfirmed(f);mockClosureAssessment("requires_capture",httpStatus);clearInvocations(gateway);
        var before=preservedBusiness(f,op);var row=jdbc.queryForMap("select * from order_change_operations where id=?",op.id());
        var report=closureAutomation().scan();assertThat(report.attempted()).isEqualTo(1);
        assertThat(report.outcomes().get(httpStatus==200?"NOT_CLOSED":"RETRY_LATER")).isEqualTo(1);
        assertThat(report.getFailures()).isEqualTo(httpStatus==200?0:1);
        assertThat(preservedBusiness(f,op)).isEqualTo(before);assertThat(jdbc.queryForMap("select * from order_change_operations where id=?",op.id())).isEqualTo(row);
        verify(gateway).assessNewFlowClosure(any());verifyNoMoreInteractions(gateway);
    }
    @Test void automaticClosureCursorSurvivesRestartAndFailuresCannotStarveLaterCandidates() throws Exception{
        isolateClosureScan();
        for(int i=0;i<105;i++){
            var f=fixture();var op=accept(f,3);resultTransactions.requestAbort(abortCommand(f,op,UUID.randomUUID(),version(op.id())));
        }
        clearInvocations(gateway);var report=closureAutomation().scan();assertThat(report.attempted()).isEqualTo(100);
        assertThat(report.outcomes().get("CONFLICT")).isEqualTo(100);
        assertThat(jdbc.queryForObject("select after_id is not null and upper_id is not null from order_change_closure_scan_cursor where id=1",Boolean.class)).isTrue();
        // A new service instance must continue from the persisted cursor rather than retrying the first hundred.
        var restarted=closureAutomation();assertThat(restarted.scan().attempted()).isEqualTo(5);
        assertThat(jdbc.queryForObject("select after_id is null and upper_id is null from order_change_closure_scan_cursor where id=1",Boolean.class)).isTrue();
        assertThat(restarted.scan().attempted()).isEqualTo(100);assertThat(restarted.scan().attempted()).isEqualTo(5);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var gate=new java.util.concurrent.CountDownLatch(1);
        try{var a=pool.submit(()->{gate.await();return closureAutomation().reserve();});var b=pool.submit(()->{gate.await();return closureAutomation().reserve();});gate.countDown();
            var first=a.get(15,java.util.concurrent.TimeUnit.SECONDS);var second=b.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(List.of(first.size(),second.size())).containsExactlyInAnyOrder(100,5);
            assertThat(first).doesNotContainAnyElementsOf(second);var union=new HashSet<>(first);union.addAll(second);assertThat(union).hasSize(105);
        }finally{pool.shutdownNow();}
        verifyNoInteractions(gateway);
    }
    @Test void automaticClosureRejectsInvalidEvidenceAndNeverResumesManualReview(){
        isolateClosureScan();var f=fixture();var op=requestedUnconfirmed(f);
        doThrow(new PaymentGatewayException(PaymentGatewayErrorCategory.INVALID_RESPONSE,200,null,true)).when(gateway).assessNewFlowClosure(any());clearInvocations(gateway);
        var before=preservedBusiness(f,op);var report=closureAutomation().scan();
        assertThat(report.outcomes().get("CONFLICT")).isEqualTo(1);assertThat(report.getFailures()).isEqualTo(1);
        assertThat(preservedBusiness(f,op)).isEqualTo(before);
        jdbc.update("update order_change_operations set state='MANUAL_REVIEW',version=version+1 where id=?",op.id());clearInvocations(gateway);
        assertThat(closureAutomation().scan().attempted()).isZero();closureAutomation().afterWebhook(newFlow);
        assertThat(state(op.id())).isEqualTo("MANUAL_REVIEW");verifyNoInteractions(gateway);
    }
    @Test void differentConcurrentWebhookEventsOnlyGetAndNeverDuplicateClosure() throws Exception{
        isolateClosureScan();var f=fixture();var op=requestedFailed(f);var flow=newFlow;var gate=new java.util.concurrent.CyclicBarrier(2);
        doAnswer(call->{var observation=com.example.ecsite.payment.payjp.MockNewFlowClosureTransport.assess(call.getArgument(0),"canceled",200,java.time.Clock.systemUTC(),System::nanoTime);
            gate.await(10,java.util.concurrent.TimeUnit.SECONDS);return observation;
        }).when(gateway).assessNewFlowClosure(any());clearInvocations(gateway);long version=version(op.id());
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{var a=pool.submit(()->webhook.synchronize("payment_flow.canceled",flow));var b=pool.submit(()->webhook.synchronize("payment_flow.requires_action",flow));
            a.get(15,java.util.concurrent.TimeUnit.SECONDS);b.get(15,java.util.concurrent.TimeUnit.SECONDS);
        }finally{pool.shutdownNow();}
        assertThat(version(op.id())).isEqualTo(version+1);verify(gateway,times(2)).assessNewFlowClosure(any());
        verify(gateway,never()).observeOrderChangeFlow(anyString());verifyNoMoreInteractions(gateway);
    }
    @Test void authenticatedWebhookUsesOnlySavedNewFlowAndDuplicateDeliveryIsIdempotent(){
        isolateClosureScan();var f=fixture();var op=requestedFailed(f);mockClosureAssessment("canceled",200);clearInvocations(gateway);
        var flow=jdbc.queryForObject("select p.provider_payment_id from payments p join order_change_operations o on o.new_payment_id=p.id where o.id=?",String.class,op.id());
        webhook.synchronize("payment_flow.canceled",flow);var recorded=closureDatabaseSnapshot();
        assertThat(jdbc.queryForObject("select abort_flow_closed_at is not null from order_change_operations where id=?",Boolean.class,op.id())).isTrue();
        webhook.synchronize("payment_flow.canceled",flow);assertThat(closureDatabaseSnapshot()).isEqualTo(recorded);
        closureAutomation().afterWebhook(f.oldFlow());closureAutomation().afterWebhook("pfw_unknown");
        verify(gateway,times(1)).assessNewFlowClosure(any());verify(gateway,never()).observeOrderChangeFlow(anyString());verifyNoMoreInteractions(gateway);
    }
    @Test void invalidWebhookAuthenticationCannotTriggerClosureGet(){
        isolateClosureScan();var f=fixture();var op=requestedUnconfirmed(f);clearInvocations(gateway);
        var verifier=mock(com.example.ecsite.payment.payjp.PayJpWebhookTokenVerifier.class);
        var controller=new com.example.ecsite.controller.PayJpWebhookController(verifier,webhook);
        var event=new com.example.ecsite.payment.payjp.PayJpWebhookEvent("evt_synthetic","payment_flow.canceled",
            new com.example.ecsite.payment.payjp.PayJpWebhookPaymentFlow(newFlow));
        assertThat(controller.receive("invalid-synthetic-token",event).getStatusCode().value()).isEqualTo(401);verifyNoInteractions(gateway);
    }
    @Test void webhookGetFailureDoesNotFailExistingWebhookAndPeriodicScanRecovers(){
        isolateClosureScan();var f=fixture();var op=requestedFailed(f);mockClosureAssessment("canceled",500);clearInvocations(gateway);
        assertThatCode(()->webhook.synchronize("payment_flow.canceled",newFlow)).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("select abort_flow_closed_at is null from order_change_operations where id=?",Boolean.class,op.id())).isTrue();
        mockClosureAssessment("canceled",200);assertThat(closureAutomation().scan().outcomes().get("CLOSED_RECORDED")).isEqualTo(1);
        verify(gateway,never()).sendRequestedAbort(any());verify(gateway,never()).sendOrderChangeRequest(any());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void automaticClosureCursorCommitUncertaintyDoesNotIssueGet(boolean committed){
        isolateClosureScan();var f=fixture();var op=requestedFailed(f);mockClosureAssessment("canceled",200);clearInvocations(gateway);
        var factory=((org.springframework.orm.jpa.JpaTransactionManager)manager).getEntityManagerFactory();
        var ambiguous=new org.springframework.orm.jpa.JpaTransactionManager(factory){
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){
                if(committed)super.doCommit(status);else super.doRollback(status);
                throw new org.springframework.transaction.TransactionSystemException("Synthetic scan commit uncertainty");
            }
        };
        assertThatThrownBy(()->new NewFlowClosureReconciliationService(jdbc,ambiguous,resultTransactions,gateway).scan())
            .isInstanceOf(org.springframework.transaction.TransactionSystemException.class);verifyNoInteractions(gateway);
        assertThat(closureAutomation().scan().outcomes().get("CLOSED_RECORDED")).isEqualTo(1);
    }
    @Test void simultaneousSchedulersOnlyGetAndOneWriterWins() throws Exception{
        isolateClosureScan();var f=fixture();var op=requestedFailed(f);var gate=new java.util.concurrent.CyclicBarrier(2);
        doAnswer(call->{var observation=com.example.ecsite.payment.payjp.MockNewFlowClosureTransport.assess(call.getArgument(0),"canceled",200,java.time.Clock.systemUTC(),System::nanoTime);
            gate.await(10,java.util.concurrent.TimeUnit.SECONDS);return observation;
        }).when(gateway).assessNewFlowClosure(any());clearInvocations(gateway);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{var a=pool.submit(()->closureAutomation().scan());var b=pool.submit(()->closureAutomation().scan());
            var results=List.of(a.get(15,java.util.concurrent.TimeUnit.SECONDS),b.get(15,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(results.stream().mapToLong(r->r.outcomes().get("CLOSED_RECORDED")).sum()).isEqualTo(1);
            assertThat(results.stream().mapToInt(NewFlowClosureReconciliationService.ScanReport::attempted).sum()).isEqualTo(2);
        }finally{pool.shutdownNow();}
        verify(gateway,times(2)).assessNewFlowClosure(any());verifyNoMoreInteractions(gateway);
    }
    @Test void webhookAndSchedulerConcurrentGetUseExistingSingleCasWriter() throws Exception{
        isolateClosureScan();var f=fixture();var op=requestedFailed(f);var flow=newFlow;var gate=new java.util.concurrent.CyclicBarrier(2);
        doAnswer(call->{var observation=com.example.ecsite.payment.payjp.MockNewFlowClosureTransport.assess(call.getArgument(0),"canceled",200,java.time.Clock.systemUTC(),System::nanoTime);
            gate.await(10,java.util.concurrent.TimeUnit.SECONDS);return observation;
        }).when(gateway).assessNewFlowClosure(any());clearInvocations(gateway);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{var a=pool.submit(()->closureAutomation().scan());var b=pool.submit(()->{webhook.synchronize("payment_flow.canceled",flow);return true;});
            assertThat(a.get(15,java.util.concurrent.TimeUnit.SECONDS).attempted()).isEqualTo(1);assertThat(b.get(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }finally{pool.shutdownNow();}
        assertThat(jdbc.queryForObject("select abort_flow_closed_at is not null from order_change_operations where id=?",Boolean.class,op.id())).isTrue();
        verify(gateway,times(2)).assessNewFlowClosure(any());verify(gateway,never()).observeOrderChangeFlow(anyString());verifyNoMoreInteractions(gateway);
    }
    void mockClosureAssessment(String status,int httpStatus){
        doAnswer(call->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return com.example.ecsite.payment.payjp.MockNewFlowClosureTransport.assess(call.getArgument(0),status,httpStatus,java.time.Clock.systemUTC(),System::nanoTime);
        }).when(gateway).assessNewFlowClosure(any());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"ABSENT","READY","STARTED","ABORT_UNKNOWN","OBSERVED","UNKNOWN_OBSERVED"})
    void operationClosureEntrySettlesKnownHistoriesAndRegetsWithoutPost(String phase){
        var f=fixture();var op=phase.equals("ABSENT")?requestedUnconfirmed(f):requestedAuthorized(f);
        Long request=null;
        if(!phase.equals("ABSENT"))request=resultTransactions.prepareRequestedAbort(f.order(),op.id(),version(op.id()),abortEvidence(f,op)).id();
        if(List.of("STARTED","ABORT_UNKNOWN","OBSERVED","UNKNOWN_OBSERVED").contains(phase))jdbc.update("update order_change_request_dispatches set state='STARTED',started_at=clock_timestamp() where request_id=?",request);
        if(List.of("ABORT_UNKNOWN","UNKNOWN_OBSERVED").contains(phase))jdbc.update("update order_change_operations set state='ABORT_UNKNOWN',version=version+1 where id=?",op.id());
        if(List.of("OBSERVED","UNKNOWN_OBSERVED").contains(phase))jdbc.update("update order_change_request_dispatches set state='OBSERVED',observed_at=clock_timestamp() where request_id=?",request);
        mockClosureAssessment("canceled",200);clearInvocations(gateway);var before=closureDatabaseSnapshot();var business=preservedBusiness(f,op);
        if(List.of("ABSENT","READY").contains(phase)){
            assertThat(closureControl().reconcile(op.id(),gateway)).isEqualTo(NewFlowClosureControl.ReconcileResult.CONFLICT);
            assertThat(closureDatabaseSnapshot()).isEqualTo(before);return;
        }
        assertThat(closureControl().reconcile(op.id(),gateway)).isEqualTo(NewFlowClosureControl.ReconcileResult.CLOSED_RECORDED);
        assertThat(preservedBusiness(f,op)).isEqualTo(business);var recorded=closureDatabaseSnapshot();
        // New controller instance simulates restart: only saved DB context and a fresh GET are used.
        assertThat(new NewFlowClosureControl(resultTransactions,manager).reconcile(op.id(),gateway)).isEqualTo(NewFlowClosureControl.ReconcileResult.ALREADY_CLOSED);
        assertThat(closureDatabaseSnapshot()).isEqualTo(recorded);
        verify(gateway,times(2)).assessNewFlowClosure(any());verifyNoMoreInteractions(gateway);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"requires_capture,200,NOT_CLOSED","processing,200,RETRY_LATER",
        "requires_payment_method,200,RETRY_LATER","requires_confirmation,200,RETRY_LATER","requires_action,200,RETRY_LATER",
        "succeeded,200,MANUAL_REVIEW_REQUIRED","future_status,200,RETRY_LATER","canceled,404,RETRY_LATER","canceled,500,RETRY_LATER"})
    void operationClosureEntryClassifiesWithoutUpdatingDatabase(String status,int httpStatus,String outcome){
        var f=fixture();var op=requestedUnconfirmed(f);mockClosureAssessment(status,httpStatus);clearInvocations(gateway);var before=closureDatabaseSnapshot();
        assertThat(closureControl().reconcile(op.id(),gateway).name()).isEqualTo(outcome);
        assertThat(closureDatabaseSnapshot()).isEqualTo(before);verify(gateway).assessNewFlowClosure(any());verifyNoMoreInteractions(gateway);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"NO_INTENT","MANUAL_REVIEW","MISSING_DISPATCH","MISSING_CREATE_DISPATCH","PARTIAL_ABORT","VERSION_RACE","PAYMENT_RACE","MANUAL_RACE","GET_TIMEOUT","INVALID_JSON","CONNECTION","STALE","OLD_CANCEL","OLD_CANCEL_RACE"})
    void operationClosureEntrySafelyHoldsConflictsAndNeverReposts(String scenario){
        var f=fixture();var op=scenario.equals("NO_INTENT")?accept(f,3):requestedFailed(f);
        mockClosureAssessment("canceled",200);String expected=scenario.equals("STALE")?"RETRY_LATER":"CONFLICT";
        if(scenario.equals("NO_INTENT"))expected="NOT_APPLICABLE";
        if(scenario.equals("MANUAL_REVIEW")){
            jdbc.update("update order_change_operations set state='MANUAL_REVIEW',version=version+1 where id=?",op.id());expected="MANUAL_REVIEW_REQUIRED";
        }
        if(scenario.equals("MISSING_DISPATCH")){
            var df=dispatchFixture();f=df.base();op=df.operation();
            requestedDispatchFixtureMutation("order_change_request_dispatches","trg_change_dispatch_progress","delete from order_change_request_dispatches where request_id=?",df.request().id());
        }
        if(scenario.equals("MISSING_CREATE_DISPATCH")){
            Long createId=jdbc.queryForObject("select id from order_change_financial_requests where operation_id=? and request_kind='CREATE'",Long.class,op.id());
            requestedDispatchFixtureMutation("order_change_request_dispatches","trg_change_dispatch_progress","delete from order_change_request_dispatches where request_id=?",createId);
        }
        var target=op;var base=f;
        if(scenario.equals("PARTIAL_ABORT"))tx(()->{
            var payment=payments.findById(jdbc.queryForObject("select new_payment_id from order_change_operations where id=?",Long.class,target.id())).orElseThrow();
            transactions.saveAndFlush(PaymentTransaction.forOrderChange(payment,PaymentTransactionType.CANCEL,payment.getAmount(),0,UUID.randomUUID().toString(),
                orders.findCurrentTimeInTokyo(),PaymentTransactionPurpose.ORDER_CHANGE_NEW_AUTH_ABORT,target.id()));return null;
        });
        if(scenario.equals("OLD_CANCEL"))tx(()->{
            var old=payments.findById(base.oldPayment()).orElseThrow();transactions.saveAndFlush(PaymentTransaction.forOrderChange(old,PaymentTransactionType.CANCEL,old.getAmount(),0,
                UUID.randomUUID().toString(),orders.findCurrentTimeInTokyo(),PaymentTransactionPurpose.ORDER_CHANGE_OLD_AUTH_CANCEL,target.id()));return null;
        });
        if(scenario.equals("GET_TIMEOUT")){
            doThrow(new com.example.ecsite.payment.PaymentGatewayException("synthetic timeout")).when(gateway).assessNewFlowClosure(any());expected="RETRY_LATER";
        }else if(scenario.equals("INVALID_JSON")){
            doThrow(new com.example.ecsite.payment.PaymentGatewayException(com.example.ecsite.payment.PaymentGatewayErrorCategory.INVALID_RESPONSE,null,null,true)).when(gateway).assessNewFlowClosure(any());
        }else if(scenario.equals("CONNECTION")){
            doThrow(new IllegalStateException("Synthetic connection mismatch")).when(gateway).assessNewFlowClosure(any());
        }else if(List.of("VERSION_RACE","PAYMENT_RACE","MANUAL_RACE","STALE","OLD_CANCEL_RACE").contains(scenario)){
            doAnswer(call->{
                var expectedContext=(NewFlowClosureControl.Expected)call.getArgument(0);
                var observation=com.example.ecsite.payment.payjp.MockNewFlowClosureTransport.assess(expectedContext,"canceled",200,java.time.Clock.systemUTC(),System::nanoTime);
                if(scenario.equals("VERSION_RACE"))jdbc.update("update order_change_operations set version=version+1 where id=?",target.id());
                if(scenario.equals("PAYMENT_RACE"))jdbc.update("update payments set status='REQUIRES_ACTION' where id=?",expectedContext.paymentId());
                if(scenario.equals("MANUAL_RACE"))jdbc.update("update order_change_operations set state='MANUAL_REVIEW',version=version+1 where id=?",target.id());
                if(scenario.equals("OLD_CANCEL_RACE"))tx(()->{
                    var old=payments.findById(base.oldPayment()).orElseThrow();transactions.saveAndFlush(PaymentTransaction.forOrderChange(old,PaymentTransactionType.CANCEL,old.getAmount(),0,
                        UUID.randomUUID().toString(),orders.findCurrentTimeInTokyo(),PaymentTransactionPurpose.ORDER_CHANGE_OLD_AUTH_CANCEL,target.id()));return null;
                });
                if(scenario.equals("STALE")){
                    var ticks=new java.util.concurrent.atomic.AtomicLong();
                    var stale=com.example.ecsite.payment.payjp.MockNewFlowClosureTransport.assess(expectedContext,"canceled",200,java.time.Clock.systemUTC(),ticks::get);
                    ticks.set(java.time.Duration.ofSeconds(6).toNanos());return stale;
                }
                return observation;
            }).when(gateway).assessNewFlowClosure(any());
        }
        clearInvocations(gateway);var before=closureDatabaseSnapshot();
        assertThat(closureControl().reconcile(op.id(),gateway).name()).isEqualTo(expected);
        if(!List.of("VERSION_RACE","PAYMENT_RACE","MANUAL_RACE","OLD_CANCEL_RACE").contains(scenario))assertThat(closureDatabaseSnapshot()).isEqualTo(before);
        verify(gateway,never()).sendRequestedAbort(any());verify(gateway,never()).sendOrderChangeRequest(any());
    }
    @Test void operationClosureEntryConcurrentGetsHaveOneWriterAndNoDispatch() throws Exception{
        var f=fixture();var op=requestedFailed(f);var gate=new java.util.concurrent.CyclicBarrier(2);
        doAnswer(call->{
            var observed=com.example.ecsite.payment.payjp.MockNewFlowClosureTransport.assess(call.getArgument(0),"canceled",200,java.time.Clock.systemUTC(),System::nanoTime);
            gate.await(10,java.util.concurrent.TimeUnit.SECONDS);return observed;
        }).when(gateway).assessNewFlowClosure(any());clearInvocations(gateway);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var before=closureDatabaseSnapshot();
        try{var one=pool.submit(()->closureControl().reconcile(op.id(),gateway));var two=pool.submit(()->closureControl().reconcile(op.id(),gateway));
            assertThat(List.of(one.get(15,java.util.concurrent.TimeUnit.SECONDS),two.get(15,java.util.concurrent.TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(NewFlowClosureControl.ReconcileResult.CLOSED_RECORDED,NewFlowClosureControl.ReconcileResult.CONFLICT);
        }finally{pool.shutdownNow();}
        assertOnlyClosureOperationChanged(before);verify(gateway,times(2)).assessNewFlowClosure(any());verifyNoMoreInteractions(gateway);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void operationClosureEntryCommitUncertaintyNeverReturnsSuccess(boolean committed){
        var f=fixture();var op=requestedFailed(f);mockClosureAssessment("canceled",200);
        var factory=((org.springframework.orm.jpa.JpaTransactionManager)manager).getEntityManagerFactory();var commits=new AtomicInteger();
        var ambiguous=new org.springframework.orm.jpa.JpaTransactionManager(factory){
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){
                if(commits.incrementAndGet()==2){
                    if(committed)super.doCommit(status);else super.doRollback(status);
                    throw new org.springframework.transaction.TransactionSystemException("Synthetic commit uncertainty");
                }
                super.doCommit(status);
            }
        };
        var before=closureDatabaseSnapshot();
        assertThat(new NewFlowClosureControl(resultTransactions,ambiguous).reconcile(op.id(),gateway)).isEqualTo(NewFlowClosureControl.ReconcileResult.RETRY_LATER);
        if(!committed)assertThat(closureDatabaseSnapshot()).isEqualTo(before);else assertOnlyClosureOperationChanged(before);
        assertThat(closureControl().reconcile(op.id(),gateway)).isEqualTo(committed?NewFlowClosureControl.ReconcileResult.ALREADY_CLOSED:NewFlowClosureControl.ReconcileResult.CLOSED_RECORDED);
    }
    @Test void operationClosureEntryNeverTrustsUnknownProvenanceDigestOrErasesIt(){
        var f=fixture();var op=requestedFailed(f);seedClosureForGuardTest(op);mockClosureAssessment("canceled",200);
        var before=closureDatabaseSnapshot();assertThat(closureControl().reconcile(op.id(),gateway)).isEqualTo(NewFlowClosureControl.ReconcileResult.CONFLICT);
        assertThat(closureDatabaseSnapshot()).isEqualTo(before);
        mockClosureAssessment("requires_capture",200);
        assertThat(closureControl().reconcile(op.id(),gateway)).isEqualTo(NewFlowClosureControl.ReconcileResult.NOT_CLOSED);
        assertThat(closureDatabaseSnapshot()).isEqualTo(before);
    }
    NewFlowClosureControl closureControl(){return new NewFlowClosureControl(resultTransactions,manager);}
    com.example.ecsite.payment.payjp.NewFlowClosureObservation closureObservation(Fixture f,OrderChangeStore.Operation op){
        mockClosureGet();return closureControl().observe(gateway,f.order(),op.id());
    }
    OrderChangeStore.Operation requestedUnconfirmed(Fixture f){
        var op=accept(f,3);mockFlow(f,op);newState.set("requires_payment_method");service.advance(f.order(),op.id());
        resultTransactions.requestAbort(abortCommand(f,op,UUID.randomUUID(),version(op.id())));return op;
    }
    @Test void financialClosureNeverFreezesPendingAuthorization(){
        var f=fixture();var op=requestedUnconfirmed(f);var before=closureDatabaseSnapshot();
        assertThat(closureControl().record(f.order(),op.id(),closureObservation(f,op)))
            .isEqualTo(NewFlowClosureControl.RecordResult.CONFLICT);
        assertThat(closureDatabaseSnapshot()).isEqualTo(before);
    }
    @Test void financialClosureSettlesKnownAuthorizationAndStartedCancelAtomically(){
        var f=fixture();var op=requestedAuthorized(f);var saved=resultTransactions.prepareRequestedAbort(f.order(),op.id(),version(op.id()),abortEvidence(f,op));
        jdbc.update("update order_change_request_dispatches set state='STARTED',started_at=clock_timestamp() where request_id=?",saved.id());
        var before=preservedBusiness(f,op);
        assertThat(closureControl().record(f.order(),op.id(),closureObservation(f,op))).isEqualTo(NewFlowClosureControl.RecordResult.RECORDED);
        assertThat(jdbc.queryForObject("select status from payments where id=?",String.class,saved.paymentId())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select status from payment_transactions where id=?",String.class,saved.transactionId())).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,saved.id())).isEqualTo("OBSERVED");
        assertThat(preservedBusiness(f,op)).isEqualTo(before);
        assertThat(state(op.id())).isEqualTo("ABORTING");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"STARTED","OBSERVED","SUCCEEDED"})
    void financialClosureKnownCancelHistoriesConvergeAndFreeze(String phase){
        var f=fixture();var op=requestedAuthorized(f);var r=resultTransactions.prepareRequestedAbort(f.order(),op.id(),version(op.id()),abortEvidence(f,op));
        jdbc.update("update order_change_request_dispatches set state='STARTED',started_at=clock_timestamp() where request_id=?",r.id());
        if(!phase.equals("STARTED"))jdbc.update("update order_change_request_dispatches set state='OBSERVED',observed_at=clock_timestamp() where request_id=?",r.id());
        if(phase.equals("SUCCEEDED"))tx(()->{transactions.findById(r.transactionId()).orElseThrow().markSucceeded(newFlow,orders.findCurrentTimeInTokyo());return null;});
        clearInvocations(gateway);var before=preservedBusiness(f,op);var bytes=jdbc.queryForMap("select encode(request_body,'hex') body,idempotency_key,encode(request_fingerprint,'hex') fingerprint from order_change_financial_requests where id=?",r.id());
        var proof=closureObservation(f,op);
        assertThat(closureControl().record(f.order(),op.id(),proof)).isEqualTo(NewFlowClosureControl.RecordResult.RECORDED);
        assertThat(jdbc.queryForObject("select status from payments where id=?",String.class,r.paymentId())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select status from payment_transactions where id=?",String.class,r.transactionId())).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,r.id())).isEqualTo("OBSERVED");
        assertThat(preservedBusiness(f,op)).isEqualTo(before);
        assertThat(jdbc.queryForMap("select encode(request_body,'hex') body,idempotency_key,encode(request_fingerprint,'hex') fingerprint from order_change_financial_requests where id=?",r.id())).isEqualTo(bytes);
        assertThatThrownBy(()->closureControl().record(f.order(),op.id(),proof)).isInstanceOf(IllegalStateException.class);
        var settled=closureDatabaseSnapshot();
        assertThat(closureControl().record(f.order(),op.id(),closureObservation(f,op))).isEqualTo(NewFlowClosureControl.RecordResult.REPLAYED);
        assertThat(closureDatabaseSnapshot()).isEqualTo(settled);
        verify(gateway,never()).sendRequestedAbort(any());verify(gateway,never()).sendOrderChangeRequest(any());
    }
    @Test void financialClosurePreservesConfirmedFailedAuthorizationWithoutInventingCancel(){
        var f=fixture();var op=requestedFailed(f);var before=preservedBusiness(f,op);
        assertThat(closureControl().record(f.order(),op.id(),closureObservation(f,op))).isEqualTo(NewFlowClosureControl.RecordResult.RECORDED);
        long next=jdbc.queryForObject("select new_payment_id from order_change_operations where id=?",Long.class,op.id());
        assertThat(jdbc.queryForObject("select status from payments where id=?",String.class,next)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select status from payment_transactions where payment_id=?",String.class,next)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select count(*) from order_change_financial_requests where operation_id=? and request_kind='NEW_ABORT'",Integer.class,op.id())).isZero();
        assertThat(preservedBusiness(f,op)).isEqualTo(before);assertThat(state(op.id())).isEqualTo("ABORTING");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"READY","FAILED_CANCEL","BAD_SUCCEEDED_FLOW","UNKNOWN_GET"})
    void financialClosureRejectsUnresolvedOrContradictoryCancelHistory(String phase){
        var f=fixture();var op=requestedAuthorized(f);var r=resultTransactions.prepareRequestedAbort(f.order(),op.id(),version(op.id()),abortEvidence(f,op));
        if(!phase.equals("READY"))jdbc.update("update order_change_request_dispatches set state='STARTED',started_at=clock_timestamp() where request_id=?",r.id());
        if(phase.equals("FAILED_CANCEL"))tx(()->{transactions.findById(r.transactionId()).orElseThrow().markFailed(newFlow,"CONFIRMED_FAILURE","synthetic",orders.findCurrentTimeInTokyo());return null;});
        if(phase.equals("BAD_SUCCEEDED_FLOW"))tx(()->{transactions.findById(r.transactionId()).orElseThrow().markSucceeded("pfw_wrong",orders.findCurrentTimeInTokyo());return null;});
        clearInvocations(gateway);var before=closureDatabaseSnapshot();
        if(phase.equals("UNKNOWN_GET")){
            mockClosureAssessment("processing",200);
            assertThat(closureControl().reconcile(op.id(),gateway)).isEqualTo(NewFlowClosureControl.ReconcileResult.RETRY_LATER);
        }else assertThat(closureControl().record(f.order(),op.id(),closureObservation(f,op))).isEqualTo(NewFlowClosureControl.RecordResult.CONFLICT);
        assertThat(closureDatabaseSnapshot()).isEqualTo(before);
        verify(gateway,never()).sendRequestedAbort(any());verify(gateway,never()).sendOrderChangeRequest(any());
    }
    @Test void financialClosureNeverRepairsAlreadyFrozenInconsistentHistoryEvenWithMatchingDigest(){
        var f=fixture();var op=requestedUnconfirmed(f);
        var expected=tx(()->resultTransactions.loadNewFlowClosureExpected(f.order(),op.id()));
        jdbc.update("update order_change_operations set state='ABORTING',abort_flow_closed_at=clock_timestamp(),abort_flow_close_digest=?,abort_flow_close_version=1,version=version+1 where id=?",NewFlowClosureDigest.compute(expected),op.id());
        var before=closureDatabaseSnapshot();
        assertThat(closureControl().record(f.order(),op.id(),closureObservation(f,op))).isEqualTo(NewFlowClosureControl.RecordResult.CONFLICT);
        assertThat(closureDatabaseSnapshot()).isEqualTo(before);
    }
    @Test void financialClosureFailureAfterFinancialFlushRollsBackEveryRow(){
        var f=fixture();var op=requestedAuthorized(f);var r=resultTransactions.prepareRequestedAbort(f.order(),op.id(),version(op.id()),abortEvidence(f,op));
        jdbc.update("update order_change_request_dispatches set state='STARTED',started_at=clock_timestamp() where request_id=?",r.id());
        var proof=closureObservation(f,op);var before=closureDatabaseSnapshot();
        String name="fail_financial_closure_"+op.id().toString().replace("-","");
        jdbc.execute("create function "+name+"() returns trigger language plpgsql as $$ begin raise exception 'synthetic closure write failure'; end $$");
        jdbc.execute("create trigger "+name+" before update of abort_flow_closed_at on order_change_operations for each row when(new.id='"+op.id()+"') execute function "+name+"()");
        try{
            assertThatThrownBy(()->closureControl().record(f.order(),op.id(),proof)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(closureDatabaseSnapshot()).isEqualTo(before);
            assertThatThrownBy(proof::consumeBound).isInstanceOf(IllegalStateException.class);
        }finally{jdbc.execute("drop trigger "+name+" on order_change_operations");jdbc.execute("drop function "+name+"()");}
        assertThat(closureControl().record(f.order(),op.id(),closureObservation(f,op))).isEqualTo(NewFlowClosureControl.RecordResult.RECORDED);
    }
    OrderChangeStore.Operation requestedFailed(Fixture f){
        var op=requestedUnconfirmed(f);
        tx(()->{
            var auth=transactions.findById(jdbc.queryForObject("select id from payment_transactions where order_change_operation_id=? and purpose='ORDER_CHANGE_AUTHORIZATION'",Long.class,op.id())).orElseThrow();
            auth.markFailed(newFlow,"CONFIRMED_FAILURE","synthetic confirmed history",orders.findCurrentTimeInTokyo());
            auth.getPayment().markFailed(orders.findCurrentTimeInTokyo());return null;
        });
        return op;
    }
    void assertOnlyClosureOperationChanged(Map<String,List<String>> before){
        var after=closureDatabaseSnapshot();var previous=new TreeMap<>(before);previous.remove("order_change_operations");after.remove("order_change_operations");
        assertThat(after).isEqualTo(previous);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"ABSENT_PENDING","ABSENT_AUTHORIZED","READY","STARTED","ABORT_UNKNOWN","OBSERVED"})
    void closureWriterRequiresKnownAuthorizationAndStartedCancellation(String phase){
        var f=fixture();var op=phase.equals("ABSENT_PENDING")?requestedUnconfirmed(f):requestedAuthorized(f);
        Long request=null;
        if(!phase.startsWith("ABSENT"))request=resultTransactions.prepareRequestedAbort(f.order(),op.id(),version(op.id()),abortEvidence(f,op)).id();
        if(List.of("STARTED","ABORT_UNKNOWN","OBSERVED").contains(phase))jdbc.update("update order_change_request_dispatches set state='STARTED',started_at=clock_timestamp() where request_id=?",request);
        if(phase.equals("ABORT_UNKNOWN"))jdbc.update("update order_change_operations set state='ABORT_UNKNOWN',version=version+1 where id=?",op.id());
        if(phase.equals("OBSERVED"))jdbc.update("update order_change_request_dispatches set state='OBSERVED',observed_at=clock_timestamp() where request_id=?",request);
        var observed=closureObservation(f,op);var before=closureDatabaseSnapshot();var business=preservedBusiness(f,op);long version=version(op.id());
        var started=jdbc.queryForObject("select clock_timestamp()",java.time.OffsetDateTime.class);
        clearInvocations(gateway);
        if(List.of("ABSENT_PENDING","ABSENT_AUTHORIZED","READY").contains(phase)){
            assertThat(closureControl().record(f.order(),op.id(),observed)).isEqualTo(NewFlowClosureControl.RecordResult.CONFLICT);
            assertThat(closureDatabaseSnapshot()).isEqualTo(before);return;
        }
        assertThat(closureControl().record(f.order(),op.id(),observed)).isEqualTo(NewFlowClosureControl.RecordResult.RECORDED);
        var completed=jdbc.queryForObject("select clock_timestamp()",java.time.OffsetDateTime.class);
        var row=jdbc.queryForMap("select * from order_change_operations where id=?",op.id());
        assertThat((byte[])row.get("abort_flow_close_digest")).hasSize(32);
        assertThat(((Number)row.get("abort_flow_close_version")).intValue()).isEqualTo(1);
        var recorded=jdbc.queryForObject("select abort_flow_closed_at from order_change_operations where id=?",java.time.OffsetDateTime.class,op.id());
        assertThat(recorded.toInstant()).isBetween(started.toInstant(),completed.toInstant());
        assertThat(version(op.id())).isEqualTo(version+1);assertThat(state(op.id())).isEqualTo(phase.equals("ABORT_UNKNOWN")?"ABORT_UNKNOWN":"ABORTING");
        assertThat(preservedBusiness(f,op)).isEqualTo(business);
        assertThat(jdbc.queryForObject("select status from payment_transactions where id=(select transaction_id from order_change_financial_requests where id=?)",String.class,request)).isEqualTo("SUCCEEDED");
        verifyNoInteractions(gateway);
    }
    @Test void closureWriterFreshRegetIsIdempotentAndCannotReuseConsumedObservation(){
        var f=fixture();var op=requestedFailed(f);var first=closureObservation(f,op);
        assertThat(closureControl().record(f.order(),op.id(),first)).isEqualTo(NewFlowClosureControl.RecordResult.RECORDED);
        assertThatThrownBy(()->closureControl().record(f.order(),op.id(),first)).isInstanceOf(IllegalStateException.class);
        var before=closureDatabaseSnapshot();var second=closureObservation(f,op);
        assertThat(closureControl().record(f.order(),op.id(),second)).isEqualTo(NewFlowClosureControl.RecordResult.REPLAYED);
        assertThat(closureDatabaseSnapshot()).isEqualTo(before);
    }
    @Test void closureWriterDifferentPersistedDigestIsConflictAndImmutable(){
        var f=fixture();var op=requestedFailed(f);seedClosureForGuardTest(op);var before=closureDatabaseSnapshot();
        assertThat(closureControl().record(f.order(),op.id(),closureObservation(f,op))).isEqualTo(NewFlowClosureControl.RecordResult.CONFLICT);
        assertThat(closureDatabaseSnapshot()).isEqualTo(before);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"VERSION","PAYMENT","PAYMENT_CREATED","TRANSACTION","OLD_CANCEL","MANUAL_REVIEW"})
    void closureWriterRejectsChangedTargetAndFinancialSnapshot(String changed){
        var f=fixture();var op=requestedFailed(f);var observed=closureObservation(f,op);
        switch(changed){
            case "VERSION"->jdbc.update("update order_change_operations set version=version+1 where id=?",op.id());
            case "PAYMENT"->jdbc.update("update payments set status='REQUIRES_ACTION' where id=(select new_payment_id from order_change_operations where id=?)",op.id());
            case "PAYMENT_CREATED"->jdbc.update("update payments set created_at=created_at+interval '1 second' where id=(select new_payment_id from order_change_operations where id=?)",op.id());
            case "TRANSACTION"->jdbc.update("update payment_transactions set completed_at=localtimestamp where order_change_operation_id=?",op.id());
            case "OLD_CANCEL"->tx(()->{var old=payments.findById(f.oldPayment()).orElseThrow();transactions.saveAndFlush(PaymentTransaction.forOrderChange(old,PaymentTransactionType.CANCEL,old.getAmount(),0,UUID.randomUUID().toString(),orders.findCurrentTimeInTokyo(),PaymentTransactionPurpose.ORDER_CHANGE_OLD_AUTH_CANCEL,op.id()));return null;});
            case "MANUAL_REVIEW"->jdbc.update("update order_change_operations set state='MANUAL_REVIEW',version=version+1 where id=?",op.id());
        }
        var before=closureDatabaseSnapshot();assertThat(closureControl().record(f.order(),op.id(),observed)).isEqualTo(NewFlowClosureControl.RecordResult.CONFLICT);
        assertThat(closureDatabaseSnapshot()).isEqualTo(before);assertThatThrownBy(observed::consumeBound).isInstanceOf(IllegalStateException.class);
    }
    @Test void closureWriterWrongTargetIsConflictWithoutDatabaseMutation(){
        var f=fixture();var op=requestedFailed(f);var observed=closureObservation(f,op);var before=closureDatabaseSnapshot();
        assertThat(closureControl().record(f.order()+999,op.id(),observed)).isEqualTo(NewFlowClosureControl.RecordResult.CONFLICT);
        assertThat(closureDatabaseSnapshot()).isEqualTo(before);
    }
    @Test void closureWriterRollbackConsumesObservationAndRollsBackEveryColumn(){
        var f=fixture();var op=requestedFailed(f);var observed=closureObservation(f,op);var before=closureDatabaseSnapshot();
        PlatformTransactionManager rollbackOnly=new PlatformTransactionManager(){
            public TransactionStatus getTransaction(TransactionDefinition definition){return manager.getTransaction(definition);}
            public void commit(TransactionStatus status){status.setRollbackOnly();manager.commit(status);}
            public void rollback(TransactionStatus status){manager.rollback(status);}
        };
        var control=new NewFlowClosureControl(resultTransactions,rollbackOnly);
        assertThatThrownBy(()->control.record(f.order(),op.id(),observed)).isInstanceOf(IllegalStateException.class);
        assertThat(closureDatabaseSnapshot()).isEqualTo(before);assertThatThrownBy(observed::consumeBound).isInstanceOf(IllegalStateException.class);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void closureWriterAmbiguousCommitDoesNotReturnSuccessOrRestoreObservation(boolean committed){
        var f=fixture();var op=requestedFailed(f);var observed=closureObservation(f,op);long version=version(op.id());
        var factory=((org.springframework.orm.jpa.JpaTransactionManager)manager).getEntityManagerFactory();
        var ambiguous=new org.springframework.orm.jpa.JpaTransactionManager(factory){
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){
                if(committed)super.doCommit(status);else super.doRollback(status);
                throw new org.springframework.transaction.TransactionSystemException("Synthetic closure commit uncertainty");
            }
        };
        assertThatThrownBy(()->new NewFlowClosureControl(resultTransactions,ambiguous).record(f.order(),op.id(),observed))
            .isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
        assertThat(version(op.id())).isEqualTo(version+(committed?1:0));assertThatThrownBy(observed::consumeBound).isInstanceOf(IllegalStateException.class);
        if(committed)assertThat(closureControl().record(f.order(),op.id(),closureObservation(f,op))).isEqualTo(NewFlowClosureControl.RecordResult.REPLAYED);
    }
    @Test void closureWriterConcurrentFreshObservationsHaveOneCasWinner() throws Exception{
        var f=fixture();var op=requestedFailed(f);var a=closureObservation(f,op);var b=closureObservation(f,op);
        var gate=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{var one=pool.submit(()->{gate.await();return closureControl().record(f.order(),op.id(),a);});
            var two=pool.submit(()->{gate.await();return closureControl().record(f.order(),op.id(),b);});gate.countDown();
            assertThat(List.of(one.get(),two.get())).containsExactlyInAnyOrder(NewFlowClosureControl.RecordResult.RECORDED,NewFlowClosureControl.RecordResult.CONFLICT);
        }finally{pool.shutdownNow();}
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"DIRECT","WEBHOOK","RECONCILIATION","SYNCHRONIZE"})
    void closureGuardsLateAuthorizeAcrossEveryResultEntry(String route){
        var f=fixture();var op=requestedFailed(f);closureControl().record(f.order(),op.id(),closureObservation(f,op));
        var before=closureDatabaseSnapshot();clearInvocations(gateway);newState.set("requires_capture");
        OrderChangeFinancialRequestStore observedStore=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(requestStore);clearInvocations(observedStore);
        long auth=jdbc.queryForObject("select id from payment_transactions where order_change_operation_id=? and purpose='ORDER_CHANGE_AUTHORIZATION'",Long.class,op.id());
        if(route.equals("DIRECT"))resultTransactions.authorization(f.order(),op.id(),newAuthorized(f,op));
        else if(route.equals("WEBHOOK"))webhook.synchronize("payment_flow.requires_action",newFlow);
        else if(route.equals("RECONCILIATION"))assertThat(reconciliation.reconcile(auth)).isEqualTo(com.example.ecsite.service.payment.PaymentSynchronizationOutcome.SKIPPED);
        else{service.reconcile(f.order(),op.id());service.advance(f.order(),op.id());}
        assertThat(closureDatabaseSnapshot()).isEqualTo(before);verifyNoInteractions(gateway);verify(observedStore,never()).observed(op.id(),"CREATE");
    }
    @Test void closureLateAuthorizationIdentityMismatchIsConflictNotStale(){
        var f=fixture();var op=requestedFailed(f);closureControl().record(f.order(),op.id(),closureObservation(f,op));var before=closureDatabaseSnapshot();
        assertThatThrownBy(()->resultTransactions.authorization(f.order(),op.id(),observation(f.oldFlow(),"requires_capture",op.beforeAmount(),Map.of())))
            .isInstanceOf(IllegalArgumentException.class);assertThat(closureDatabaseSnapshot()).isEqualTo(before);
        var row=jdbc.queryForMap("select * from payment_transactions where order_change_operation_id=?",op.id());
        var invalid=new com.example.ecsite.service.payment.PaymentOperationSnapshot(f.order(),f.oldPayment(),((Number)row.get("id")).longValue(),PaymentTransactionType.AUTHORIZE,
            (String)row.get("idempotency_key"),op.afterAmount(),1,newFlow,PaymentProvider.PAYJP,PaymentTransactionStatus.PENDING,null,null,null,null,PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION);
        assertThat(service.synchronize(invalid)).isEqualTo(com.example.ecsite.service.payment.PaymentSynchronizationOutcome.CONFLICT);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"status='AUTHORIZED'","amount=amount+1","provider_payment_id='pfw_other'","provider='OTHER'","payment_method='OTHER'","created_at=created_at+interval '1 second'"})
    void closureV64ProtectsNewPaymentFinancialColumns(String assignment){
        var f=fixture();var op=requestedFailed(f);closureControl().record(f.order(),op.id(),closureObservation(f,op));
        assertThatThrownBy(()->jdbc.update("update payments set "+assignment+" where id=(select new_payment_id from order_change_operations where id=?)",op.id()))
            .isInstanceOf(org.springframework.dao.DataAccessException.class).hasStackTraceContaining("Closed new Payment");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"status='SUCCEEDED'","amount=amount+1","completed_at=localtimestamp","provider_transaction_id='pfw_other'","idempotency_key='another-key'","order_content_revision=99"})
    void closureV64ProtectsNewTransactionFinancialColumns(String assignment){
        var f=fixture();var op=requestedFailed(f);closureControl().record(f.order(),op.id(),closureObservation(f,op));
        assertThatThrownBy(()->jdbc.update("update payment_transactions set "+assignment+" where order_change_operation_id=?",op.id()))
            .isInstanceOf(org.springframework.dao.DataAccessException.class).hasStackTraceContaining("Closed new Flow transaction");
    }
    @Test void closureV64AllowsAuditAndOtherPaymentsButProtectsOldAssociation(){
        var f=fixture();var op=requestedFailed(f);closureControl().record(f.order(),op.id(),closureObservation(f,op));
        assertThat(jdbc.update("update payment_transactions set internal_note='audit',initiator_username='reviewer',failure_message='reviewed' where order_change_operation_id=?",op.id())).isEqualTo(1);
        assertThat(jdbc.update("update payments set updated_at=localtimestamp where id=(select new_payment_id from order_change_operations where id=?)",op.id())).isEqualTo(1);
        assertThat(closureControl().record(f.order(),op.id(),closureObservation(f,op))).isEqualTo(NewFlowClosureControl.RecordResult.REPLAYED);
        assertThatThrownBy(()->jdbc.update("update payment_transactions set payment_id=? where order_change_operation_id=?",f.oldPayment(),op.id()))
            .isInstanceOf(org.springframework.dao.DataAccessException.class).hasStackTraceContaining("Closed new Flow transaction");
        assertThat(jdbc.update("update payments set status='CANCELLED' where id=?",f.oldPayment())).isEqualTo(1);
        var other=fixture();assertThat(jdbc.update("update payments set status='CANCELLED' where id=?",other.oldPayment())).isEqualTo(1);
    }
    @Test void closureV64RejectsLateDispatchAndCreateObservedAndNewAbortTransaction(){
        var f=dispatchFixture();
        jdbc.update("update order_change_request_dispatches set state='STARTED',started_at=clock_timestamp() where request_id=?",f.request().id());
        closureControl().record(f.base().order(),f.operation().id(),closureObservation(f.base(),f.operation()));
        assertThatThrownBy(()->f.control().start(f.context(),f.observation())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->jdbc.update("update order_change_request_dispatches set state='STARTED',started_at=clock_timestamp() where request_id=?",f.request().id()))
            .isInstanceOf(org.springframework.dao.DataAccessException.class).hasStackTraceContaining("Closed new Flow");
        var creation=jdbc.queryForObject("select id from order_change_financial_requests where operation_id=? and request_kind='CREATE'",Long.class,f.operation().id());
        // CREATE is already OBSERVED in this authorized fixture. Its existing dispatch cannot change either.
        assertThatThrownBy(()->jdbc.update("update order_change_request_dispatches set state='OBSERVED',observed_at=clock_timestamp() where request_id=?",creation))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
        var plain=fixture();var op=requestedFailed(plain);closureControl().record(plain.order(),op.id(),closureObservation(plain,op));
        long payment=jdbc.queryForObject("select new_payment_id from order_change_operations where id=?",Long.class,op.id());
        assertThatThrownBy(()->insertClosureRaceCancel(plain,op,payment)).isInstanceOf(org.springframework.dao.DataAccessException.class).hasStackTraceContaining("Closed new Flow");
    }
    int insertClosureRaceCancel(Fixture f,OrderChangeStore.Operation op,long payment){
        return jdbc.update("""
            insert into payment_transactions(id,payment_id,transaction_type,status,amount,order_content_revision,idempotency_key,purpose,order_change_operation_id,order_change_order_id)
            values (-999,?,'CANCEL','PENDING',?,0,?,'ORDER_CHANGE_NEW_AUTH_ABORT',?,?)
            """,payment,op.afterAmount(),UUID.randomUUID().toString(),op.id(),f.order());
    }
    @Test void closureLateUnknownOutcomeCannotOverwriteClosureOrCreateSuccess(){
        var f=dispatchFixture();var observed=trustedAbortObservation(f);var dispatch=f.control().startTrusted(observed);
        closureControl().record(f.base().order(),f.operation().id(),closureObservation(f.base(),f.operation()));var before=closureDatabaseSnapshot();
        assertThat(tx(()->resultTransactions.recordRequestedAbortPostUnknown(dispatch,false))).isFalse();
        assertThat(tx(()->resultTransactions.recordRequestedAbortPostUnknown(dispatch,true))).isFalse();
        assertThat(closureDatabaseSnapshot()).isEqualTo(before);
        // Already-issued transport permits cannot be recalled by a DB write. This test never sends it.
    }
    @Test void closureWriterRejectsExpiredObservationAndIrreversiblyConsumesIt(){
        var f=fixture();var op=requestedUnconfirmed(f);var ticks=new AtomicLong(1);
        doAnswer(call->com.example.ecsite.payment.payjp.MockNewFlowClosureTransport.observe(call.getArgument(0),java.time.Clock.systemUTC(),ticks::get))
            .when(gateway).observeNewFlowClosure(any());
        var observed=closureControl().observe(gateway,f.order(),op.id());var before=closureDatabaseSnapshot();ticks.addAndGet(5_000_000_001L);
        assertThatThrownBy(()->closureControl().record(f.order(),op.id(),observed)).isInstanceOf(IllegalStateException.class);
        assertThat(closureDatabaseSnapshot()).isEqualTo(before);ticks.set(1);
        assertThatThrownBy(observed::consumeBound).isInstanceOf(IllegalStateException.class);
    }
    @Test void closureV64RejectsInsertThatWaitedOnParentBeforeClosure() throws Exception{closureParentInsertRace(true);}
    @Test void closureV64RejectsUnscopedInsertThatWaitedOnPaymentBeforeClosure() throws Exception{closureParentInsertRace(false);}
    void closureParentInsertRace(boolean scoped) throws Exception{
        var f=fixture();var op=requestedFailed(f);var observed=closureObservation(f,op);
        long next=jdbc.queryForObject("select new_payment_id from order_change_operations where id=?",Long.class,op.id());
        var locked=new java.util.concurrent.CountDownLatch(1);var inserting=new java.util.concurrent.CountDownLatch(1);
        var insertPid=new java.util.concurrent.atomic.AtomicInteger();var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            var writer=pool.submit(()->tx(()->{
                jdbc.queryForObject("select id from orders where id=? for update",Long.class,f.order());
                jdbc.queryForObject("select id from order_change_operations where id=? for update",UUID.class,op.id());
                jdbc.queryForList("select id from payments where order_id=? order by id for update",f.order());locked.countDown();
                try{if(!inserting.await(2,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Insert not started");
                    long limit=System.nanoTime()+2_000_000_000L;
                    while(jdbc.queryForObject("select count(*) from pg_locks where pid=? and not granted",Integer.class,insertPid.get())==0){
                        if(System.nanoTime()>limit)throw new AssertionError("Insert did not wait on FK");Thread.sleep(5);
                    }
                }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                return resultTransactions.recordNewFlowClosure(observed);
            }));
            assertThat(locked.await(2,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var insert=pool.submit(()->{
                try{return tx(()->{insertPid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));inserting.countDown();
                    if(scoped)insertClosureRaceCancel(f,op,next);
                    else jdbc.update("""
                        insert into payment_transactions(id,payment_id,transaction_type,status,amount,order_content_revision,idempotency_key,purpose)
                        values (-999,?,'CANCEL','PENDING',?,0,?,'ORDER_CANCELLATION')
                        """,next,op.afterAmount(),UUID.randomUUID().toString());
                    return false;});}
                catch(org.springframework.dao.DataAccessException expected){assertThat(expected).hasStackTraceContaining("Closed new Flow");return true;}
            });
            assertThat(writer.get(5,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(NewFlowClosureControl.RecordResult.RECORDED);
            assertThat(insert.get(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from payment_transactions where payment_id=?",Integer.class,next)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from payment_transactions where order_change_operation_id=? and purpose='ORDER_CHANGE_NEW_AUTH_ABORT'",Integer.class,op.id())).isZero();
        }finally{inserting.countDown();pool.shutdownNow();}
    }
    @Test void closureSynchronizeRaceReturnsConflictInsteadOfThrowing(){
        var f=fixture();var op=requestedUnconfirmed(f);var before=preservedBusiness(f,op);
        var row=jdbc.queryForMap("select * from payment_transactions where order_change_operation_id=?",op.id());
        long payment=((Number)row.get("payment_id")).longValue();
        var snapshot=new com.example.ecsite.service.payment.PaymentOperationSnapshot(f.order(),payment,((Number)row.get("id")).longValue(),PaymentTransactionType.AUTHORIZE,
            (String)row.get("idempotency_key"),op.afterAmount(),1,newFlow,PaymentProvider.PAYJP,PaymentTransactionStatus.PENDING,null,null,null,null,PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION);
        doAnswer(call->{seedClosureForGuardTest(op);return observation(f.oldFlow(),"requires_capture",op.beforeAmount(),Map.of());})
            .when(gateway).observeOrderChangeFlow(newFlow);
        assertThat(service.synchronize(snapshot)).isEqualTo(com.example.ecsite.service.payment.PaymentSynchronizationOutcome.CONFLICT);
        assertThat(preservedBusiness(f,op)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select status from payments where id=?",String.class,payment)).isEqualTo("PENDING");
    }
    @Test void closureWriterDirectTransactionEntryAlsoBurnsTokenAcrossRollback(){
        var f=fixture();var op=requestedFailed(f);var observed=closureObservation(f,op);var before=closureDatabaseSnapshot();
        assertThatThrownBy(()->tx(()->{assertThat(resultTransactions.recordNewFlowClosure(observed)).isEqualTo(NewFlowClosureControl.RecordResult.RECORDED);
            throw new IllegalStateException("Synthetic rollback after writer CAS");})).isInstanceOf(IllegalStateException.class);
        assertThat(closureDatabaseSnapshot()).isEqualTo(before);
        assertThatThrownBy(()->tx(()->resultTransactions.recordNewFlowClosure(observed))).isInstanceOf(IllegalStateException.class);
    }
    record DispatchFixture(Fixture base,OrderChangeStore.Operation operation,StoredOrderChangeRequest request,
            RequestedAbortDispatchControl control,RequestedAbortDispatchControl.Context context,
            RequestedAbortDispatchControl.Observation observation){}
    DispatchFixture dispatchFixture(){
        var f=fixture();var op=requestedAuthorized(f);
        var saved=resultTransactions.prepareRequestedAbort(f.order(),op.id(),version(op.id()),abortEvidence(f,op));
        var control=new RequestedAbortDispatchControl(resultTransactions,manager,java.time.Clock.systemUTC());
        var context=control.load(saved.id());var now=java.time.Instant.now();
        var observed=RequestedAbortDispatchControl.observe(context,now,now,dispatchBody(saved));
        clearInvocations(gateway);
        return new DispatchFixture(f,op,saved,control,context,observed);
    }
    tools.jackson.databind.node.ObjectNode dispatchBody(StoredOrderChangeRequest saved){
        var request=saved.httpRequest();
        return (tools.jackson.databind.node.ObjectNode)tools.jackson.databind.json.JsonMapper.builder().build().readTree("""
            {"id":"%s","status":"requires_capture","amount":%d,"currency":"jpy","capture_method":"manual",
             "livemode":false,"amount_received":null,"amount_capturable":%d,
             "metadata":{"order_id":"%s","order_change_operation_id":"%s"}}
            """.formatted(request.flowId(),request.amount(),request.amount(),request.orderId(),request.operationId()));
    }
    void mockDedicatedSend(DispatchFixture f,int response,AtomicInteger posts){
        doAnswer(call->com.example.ecsite.payment.payjp.MockRequestedAbortTransport.observe(call.getArgument(0))).when(gateway).observeRequestedAbort(any());
        doAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,f.request().id())).isEqualTo("STARTED");
            com.example.ecsite.payment.payjp.MockRequestedAbortTransport.send(call.getArgument(0),f.request(),response,posts,()->{});return null;
        }).when(gateway).sendRequestedAbort(any());
    }
    @Test void dedicatedAbortSendCommitsThenPostsFixedRequestOnceWithoutApplyingResult(){
        var f=dispatchFixture();var before=preservedBusiness(f.base(),f.operation());long initial=version(f.operation().id());var posts=new AtomicInteger();
        mockDedicatedSend(f,200,posts);
        assertThat(f.control().send(gateway,f.request().id()).result()).isEqualTo(RequestedAbortDispatchControl.SendResult.SENT_UNCONFIRMED);
        assertThat(posts).hasValue(1);assertThat(version(f.operation().id())).isEqualTo(initial+1);
        assertThat(state(f.operation().id())).isEqualTo("ABORTING");assertThat(preservedBusiness(f.base(),f.operation())).isEqualTo(before);
        assertThat(jdbc.queryForObject("select abort_flow_closed_at is null and abort_flow_close_digest is null and abort_flow_close_version is null from order_change_operations where id=?",Boolean.class,f.operation().id())).isTrue();
        assertThatThrownBy(()->f.control().send(gateway,f.request().id())).isInstanceOf(IllegalStateException.class);assertThat(posts).hasValue(1);
    }
    @Test void dedicatedAbortTimeoutRecordsUnknownWithoutSecondPost(){
        var f=dispatchFixture();var before=preservedBusiness(f.base(),f.operation());var posts=new AtomicInteger();mockDedicatedSend(f,0,posts);
        var result=f.control().send(gateway,f.request().id());assertThat(result.result()).isEqualTo(RequestedAbortDispatchControl.SendResult.RESULT_UNKNOWN);
        assertThat(result.recorded()).isTrue();assertThat(state(f.operation().id())).isEqualTo("ABORT_UNKNOWN");assertThat(posts).hasValue(1);
        assertThatThrownBy(()->f.control().send(gateway,f.request().id())).isInstanceOf(IllegalStateException.class);assertThat(posts).hasValue(1);
        assertThat(preservedBusiness(f.base(),f.operation())).isEqualTo(before);
    }
    com.example.ecsite.payment.payjp.RequestedAbortObservation trustedAbortObservation(DispatchFixture f){
        doAnswer(call->com.example.ecsite.payment.payjp.MockRequestedAbortTransport.observe(call.getArgument(0))).when(gateway).observeRequestedAbort(any());
        return f.control().observe(gateway,f.request().id());
    }
    void assertDedicatedAbortUnfinished(DispatchFixture f,Map<String,List<String>> before){
        assertThat(preservedBusiness(f.base(),f.operation())).isEqualTo(before);
        assertThat(state(f.operation().id())).isNotIn("ABORTED","COMPLETED","COMMIT_PENDING");
        assertThat(jdbc.queryForObject("select abort_flow_closed_at is null and abort_flow_close_digest is null and abort_flow_close_version is null from order_change_operations where id=?",Boolean.class,f.operation().id())).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from order_change_request_dispatches where request_id=? and state='OBSERVED'",Integer.class,f.request().id())).isZero();
        assertThat(jdbc.queryForObject("select status from payment_transactions where id=?",String.class,f.request().transactionId())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select count(*) from order_change_financial_requests where operation_id=? and request_kind='OLD_CANCEL'",Integer.class,f.operation().id())).isZero();
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={-1,400,404,422,500})
    void dedicatedAbortHttpErrorsNeverRetryOrApplyClosure(int status){
        var f=dispatchFixture();var before=preservedBusiness(f.base(),f.operation());var posts=new AtomicInteger();mockDedicatedSend(f,status,posts);
        var result=f.control().send(gateway,f.request().id());
        boolean manual=status==404||status==422;
        assertThat(result.result()).isEqualTo(manual?RequestedAbortDispatchControl.SendResult.MANUAL_REVIEW_REQUIRED:RequestedAbortDispatchControl.SendResult.RESULT_UNKNOWN);
        assertThat(result.recorded()).isTrue();assertThat(state(f.operation().id())).isEqualTo(manual?"MANUAL_REVIEW":"ABORT_UNKNOWN");
        assertThatThrownBy(()->f.control().send(gateway,f.request().id())).isInstanceOf(IllegalStateException.class);
        assertThat(posts).hasValue(1);assertDedicatedAbortUnfinished(f,before);
    }
    @Test void dedicatedAbortGetFailureDoesNotClaimReady(){
        var f=dispatchFixture();var before=preservedBusiness(f.base(),f.operation());long v=version(f.operation().id());var posts=new AtomicInteger();mockDedicatedSend(f,200,posts);
        doThrow(new PaymentGatewayException("Mock invalid GET")).when(gateway).observeRequestedAbort(any());
        assertThatThrownBy(()->f.control().send(gateway,f.request().id())).isInstanceOf(PaymentGatewayException.class);
        assertThat(posts).hasValue(0);assertThat(version(f.operation().id())).isEqualTo(v);
        assertThat(jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,f.request().id())).isEqualTo("READY");
        verify(gateway,never()).sendRequestedAbort(any());assertDedicatedAbortUnfinished(f,before);
    }
    @Test void dedicatedAbortVersionConflictBurnsObservationAndRollsBackReady(){
        var f=dispatchFixture();var captured=new AtomicReference<com.example.ecsite.payment.payjp.RequestedAbortObservation>();
        when(gateway.observeRequestedAbort(any())).thenAnswer(call->{var observed=com.example.ecsite.payment.payjp.MockRequestedAbortTransport.observe(call.getArgument(0));captured.set(observed);
            jdbc.update("update order_change_operations set version=version+1 where id=?",f.operation().id());return observed;});
        assertThatThrownBy(()->f.control().send(gateway,f.request().id())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(captured.get()::requireFresh).isInstanceOf(IllegalStateException.class);
        verify(gateway,never()).sendRequestedAbort(any());
        assertThat(jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,f.request().id())).isEqualTo("READY");
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void dedicatedAbortFreshnessBeforeClaimAndBeforePost(boolean beforeClaim){
        var f=dispatchFixture();var before=preservedBusiness(f.base(),f.operation());var ticks=new AtomicLong(1);var posts=new AtomicInteger();
        when(gateway.observeRequestedAbort(any())).thenAnswer(call->{var observed=com.example.ecsite.payment.payjp.MockRequestedAbortTransport.observe(call.getArgument(0),java.time.Clock.systemUTC(),ticks::get);
            if(beforeClaim)ticks.addAndGet(6_000_000_000L);return observed;});
        doAnswer(call->{ticks.addAndGet(6_000_000_000L);com.example.ecsite.payment.payjp.MockRequestedAbortTransport.send(call.getArgument(0),f.request(),200,posts,()->{});return null;}).when(gateway).sendRequestedAbort(any());
        if(beforeClaim)assertThatThrownBy(()->f.control().send(gateway,f.request().id())).isInstanceOf(IllegalStateException.class);
        else assertThat(f.control().send(gateway,f.request().id()).result()).isEqualTo(RequestedAbortDispatchControl.SendResult.RESULT_UNKNOWN);
        assertThat(posts).hasValue(0);
        assertThat(jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,f.request().id())).isEqualTo(beforeClaim?"READY":"STARTED");
        assertDedicatedAbortUnfinished(f,before);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void dedicatedAbortAmbiguousCommitNeverCreatesTrustedDispatch(boolean committed){
        var f=dispatchFixture();var observed=trustedAbortObservation(f);long v=version(f.operation().id());
        var factory=((org.springframework.orm.jpa.JpaTransactionManager)manager).getEntityManagerFactory();
        var ambiguous=new org.springframework.orm.jpa.JpaTransactionManager(factory){
            @Override protected void doCommit(DefaultTransactionStatus status){if(committed)super.doCommit(status);else super.doRollback(status);
                throw new TransactionSystemException("Mock ambiguous trusted commit");}
        };
        var control=new RequestedAbortDispatchControl(resultTransactions,ambiguous,java.time.Clock.systemUTC());var returned=new AtomicReference<RequestedAbortDispatchControl.Dispatch>();
        assertThatThrownBy(()->returned.set(control.startTrusted(observed))).isInstanceOf(TransactionSystemException.class);
        assertThat(returned.get()).isNull();assertThatThrownBy(observed::requireFresh).isInstanceOf(IllegalStateException.class);
        assertThat(version(f.operation().id())).isEqualTo(committed?v+1:v);
        assertThat(jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,f.request().id())).isEqualTo(committed?"STARTED":"READY");
        verify(gateway,never()).sendRequestedAbort(any());
    }
    @Test void dedicatedAbortRollbackOnlyCannotMintOrReuseTrustedObservation(){
        var f=dispatchFixture();var observed=trustedAbortObservation(f);long v=version(f.operation().id());
        PlatformTransactionManager rollbackOnly=new PlatformTransactionManager(){
            public TransactionStatus getTransaction(TransactionDefinition d){return manager.getTransaction(d);}
            public void commit(TransactionStatus status){status.setRollbackOnly();manager.commit(status);}
            public void rollback(TransactionStatus status){manager.rollback(status);}
        };
        var control=new RequestedAbortDispatchControl(resultTransactions,rollbackOnly,java.time.Clock.systemUTC());
        assertThatThrownBy(()->control.startTrusted(observed)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->f.control().startTrusted(observed)).isInstanceOf(IllegalStateException.class);
        assertThat(version(f.operation().id())).isEqualTo(v);
        assertThat(jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,f.request().id())).isEqualTo("READY");
        verify(gateway,never()).sendRequestedAbort(any());
    }
    @Test void dedicatedAbortSyntheticPermitCannotReachPost(){
        var f=dispatchFixture();var dispatch=f.control().start(f.context(),f.observation());var posts=new AtomicInteger();
        assertThatThrownBy(()->com.example.ecsite.payment.payjp.MockRequestedAbortTransport.send(dispatch,f.request(),200,posts,()->{})).isInstanceOf(IllegalStateException.class);
        assertThat(posts).hasValue(0);assertThatThrownBy(dispatch::consumeForGateway).isInstanceOf(IllegalStateException.class);
    }
    @Test void dedicatedAbortCrashAfterStartedCannotRestoreOrResend(){
        var f=dispatchFixture();var before=preservedBusiness(f.base(),f.operation());var observed=trustedAbortObservation(f);f.control().startTrusted(observed);
        var restarted=new RequestedAbortDispatchControl(resultTransactions,manager,java.time.Clock.systemUTC());
        assertThatThrownBy(()->restarted.send(gateway,f.request().id())).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,f.request().id())).isEqualTo("STARTED");
        verify(gateway,never()).sendRequestedAbort(any());assertDedicatedAbortUnfinished(f,before);
    }
    @Test void dedicatedAbortParallelClaimsAndGatewayConsumptionPostAtMostOnce() throws Exception{
        var f=dispatchFixture();var before=preservedBusiness(f.base(),f.operation());var first=trustedAbortObservation(f);var second=trustedAbortObservation(f);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var gate=new java.util.concurrent.CountDownLatch(1);
        try{
            java.util.function.Function<com.example.ecsite.payment.payjp.RequestedAbortObservation,RequestedAbortDispatchControl.Dispatch> claim=obs->{try{gate.await();return f.control().startTrusted(obs);}catch(IllegalStateException lost){return null;}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}};
            var a=pool.submit(()->claim.apply(first));var b=pool.submit(()->claim.apply(second));gate.countDown();
            var left=a.get(10,java.util.concurrent.TimeUnit.SECONDS);var right=b.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertThat((left==null?0:1)+(right==null?0:1)).isEqualTo(1);var dispatch=left!=null?left:right;
            var posts=new AtomicInteger();var consumeGate=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> consume=()->{consumeGate.await();try{com.example.ecsite.payment.payjp.MockRequestedAbortTransport.send(dispatch,f.request(),200,posts,()->{});return true;}catch(IllegalStateException consumed){return false;}};
            var c=pool.submit(consume);var d=pool.submit(consume);consumeGate.countDown();assertThat((c.get()?1:0)+(d.get()?1:0)).isEqualTo(1);
            assertThat(posts).hasValue(1);assertDedicatedAbortUnfinished(f,before);
        }finally{pool.shutdownNow();}
    }
    @Test void dedicatedAbortUnknownRecordingIsIdempotent(){
        var f=dispatchFixture();var observed=trustedAbortObservation(f);var dispatch=f.control().startTrusted(observed);var posts=new AtomicInteger();
        assertThatThrownBy(()->com.example.ecsite.payment.payjp.MockRequestedAbortTransport.send(dispatch,f.request(),500,posts,()->{})).isInstanceOf(PaymentGatewayException.class);
        assertThat(tx(()->resultTransactions.recordRequestedAbortPostUnknown(dispatch,false))).isTrue();long v=version(f.operation().id());
        assertThat(tx(()->resultTransactions.recordRequestedAbortPostUnknown(dispatch,false))).isTrue();assertThat(version(f.operation().id())).isEqualTo(v);assertThat(posts).hasValue(1);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"MANUAL_REVIEW","version"})
    void dedicatedAbortUnknownRecordingPreservesConcurrentReviewOrVersion(String change){
        var f=dispatchFixture();var posts=new AtomicInteger();mockDedicatedSend(f,200,posts);
        doAnswer(call->{com.example.ecsite.payment.payjp.MockRequestedAbortTransport.send(call.getArgument(0),f.request(),500,posts,()->{
            if(change.equals("MANUAL_REVIEW"))jdbc.update("update order_change_operations set state='MANUAL_REVIEW',financial_review_reason='MOCK_REVIEW',version=version+1 where id=?",f.operation().id());
            else jdbc.update("update order_change_operations set version=version+1 where id=?",f.operation().id());
        });return null;}).when(gateway).sendRequestedAbort(any());
        var result=f.control().send(gateway,f.request().id());assertThat(result.recorded()).isFalse();assertThat(posts).hasValue(1);
        assertThat(state(f.operation().id())).isEqualTo(change.equals("MANUAL_REVIEW")?"MANUAL_REVIEW":"ABORTING");
        assertThatThrownBy(()->f.control().send(gateway,f.request().id())).isInstanceOf(IllegalStateException.class);assertThat(posts).hasValue(1);
    }
    @Test void dedicatedAbortTxBRollbackBurnsObservationAndNeedsFreshGet(){
        var f=dispatchFixture();var observed=trustedAbortObservation(f);long v=version(f.operation().id());String name="fail_trusted_start_"+f.request().id();
        jdbc.execute("create function "+name+"() returns trigger language plpgsql as $$ begin raise exception 'Mock trusted rollback'; end $$");
        jdbc.execute("create trigger "+name+" after update on order_change_request_dispatches for each row when (new.request_id="+f.request().id()+") execute function "+name+"()");
        try{assertThatThrownBy(()->f.control().startTrusted(observed)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(version(f.operation().id())).isEqualTo(v);assertThatThrownBy(observed::requireFresh).isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,f.request().id())).isEqualTo("READY");
        }finally{jdbc.execute("drop trigger "+name+" on order_change_request_dispatches");jdbc.execute("drop function "+name+"()");}
        assertThatThrownBy(()->f.control().startTrusted(observed)).isInstanceOf(IllegalStateException.class);
        var fresh=trustedAbortObservation(f);var dispatch=f.control().startTrusted(fresh);var posts=new AtomicInteger();
        com.example.ecsite.payment.payjp.MockRequestedAbortTransport.send(dispatch,f.request(),200,posts,()->{});assertThat(posts).hasValue(1);
    }
    @Test void dedicatedAbortUnknownDatabaseFailureNeverRetriesTransport(){
        var f=dispatchFixture();var before=preservedBusiness(f.base(),f.operation());var posts=new AtomicInteger();mockDedicatedSend(f,500,posts);String name="fail_abort_unknown_"+f.request().id();
        jdbc.execute("create function "+name+"() returns trigger language plpgsql as $$ begin raise exception 'Mock outcome record failure'; end $$");
        jdbc.execute("create trigger "+name+" before update on order_change_operations for each row when (new.id='"+f.operation().id()+"'::uuid and new.state='ABORT_UNKNOWN') execute function "+name+"()");
        try{var result=f.control().send(gateway,f.request().id());assertThat(result.recorded()).isFalse();assertThat(posts).hasValue(1);
            assertThat(state(f.operation().id())).isEqualTo("ABORTING");
            assertThat(jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,f.request().id())).isEqualTo("STARTED");
            assertThatThrownBy(()->f.control().send(gateway,f.request().id())).isInstanceOf(IllegalStateException.class);assertDedicatedAbortUnfinished(f,before);
        }finally{jdbc.execute("drop trigger "+name+" on order_change_operations");jdbc.execute("drop function "+name+"()");}
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"STARTED","OBSERVED","missing","MANUAL_REVIEW","expired","old_cancel","flow","authorization","reservation"})
    void dedicatedAbortClaimRejectsChangedSavedStateWithoutPost(String change){
        var f=dispatchFixture();var observed=trustedAbortObservation(f);
        switch(change){
            case "STARTED","OBSERVED" -> {jdbc.update("update order_change_request_dispatches set state='STARTED',started_at=clock_timestamp() where request_id=?",f.request().id());
                if(change.equals("OBSERVED"))jdbc.update("update order_change_request_dispatches set state='OBSERVED',observed_at=clock_timestamp() where request_id=?",f.request().id());}
            case "missing" -> requestedDispatchFixtureMutation("order_change_request_dispatches","trg_change_dispatch_progress","delete from order_change_request_dispatches where request_id=?",f.request().id());
            case "MANUAL_REVIEW" -> jdbc.update("update order_change_operations set state='MANUAL_REVIEW',version=version+1 where id=?",f.operation().id());
            case "expired" -> requestedDispatchFixtureMutation("order_change_financial_requests","change_requests_no_mutation","update order_change_financial_requests set created_at=statement_timestamp()-interval '2 days',replay_not_after=statement_timestamp()-interval '1 day 1 hour' where id=?",f.request().id());
            case "old_cancel" -> tx(()->{var old=payments.findById(f.base().oldPayment()).orElseThrow();transactions.saveAndFlush(PaymentTransaction.forOrderChange(old,PaymentTransactionType.CANCEL,old.getAmount(),0,UUID.randomUUID().toString(),orders.findCurrentTimeInTokyo(),PaymentTransactionPurpose.ORDER_CHANGE_OLD_AUTH_CANCEL,f.operation().id()));return null;});
            case "flow" -> jdbc.update("update payments set provider_payment_id=? where id=?","pfw_dedicated_other_"+UUID.randomUUID(),f.request().paymentId());
            case "authorization" -> jdbc.update("update payment_transactions set order_content_revision=order_content_revision+1 where order_change_operation_id=? and purpose='ORDER_CHANGE_AUTHORIZATION'",f.operation().id());
            case "reservation" -> jdbc.update("update inventory_reservations set state='CONSUMED',consumed_at=localtimestamp where operation_id=?",f.operation().id());
        }
        assertThatThrownBy(()->f.control().startTrusted(observed)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(observed::requireFresh).isInstanceOf(IllegalStateException.class);verify(gateway,never()).sendRequestedAbort(any());
    }
    @Test void dedicatedAbortWrongRequestObservationNeverClaimsEitherReady(){
        var first=dispatchFixture();var second=dispatchFixture();var other=trustedAbortObservation(second);
        doReturn(other).when(gateway).observeRequestedAbort(any());
        assertThatThrownBy(()->first.control().send(gateway,first.request().id())).isInstanceOf(IllegalStateException.class);
        for(var f:List.of(first,second))assertThat(jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,f.request().id())).isEqualTo("READY");
        verify(gateway,never()).sendRequestedAbort(any());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"WEBHOOK","RECONCILIATION"})
    void dedicatedAbortUnknownCannotBeResentByWebhookOrReconciliation(String route){
        var f=dispatchFixture();var before=preservedBusiness(f.base(),f.operation());var posts=new AtomicInteger();mockDedicatedSend(f,0,posts);
        f.control().send(gateway,f.request().id());long v=version(f.operation().id());
        if(route.equals("WEBHOOK"))webhook.synchronize("payment_flow.requires_action",newFlow);
        else reconciliation.reconcile(f.request().transactionId());
        service.advance(f.base().order(),f.operation().id());
        assertThat(state(f.operation().id())).isEqualTo("ABORT_UNKNOWN");assertThat(version(f.operation().id())).isEqualTo(v);
        assertThat(posts).hasValue(1);verify(gateway,never()).sendOrderChangeRequest(any());assertDedicatedAbortUnfinished(f,before);
    }
    @Test void dedicatedAbortPostWindowExpiryBurnsPermitWithoutSending(){
        var f=dispatchFixture();var before=preservedBusiness(f.base(),f.operation());var posts=new AtomicInteger();mockDedicatedSend(f,200,posts);
        var afterWindow=java.time.Clock.fixed(java.time.Instant.now().plusSeconds(172800),java.time.ZoneOffset.UTC);
        var control=new RequestedAbortDispatchControl(resultTransactions,manager,afterWindow);
        assertThat(control.send(gateway,f.request().id()).result()).isEqualTo(RequestedAbortDispatchControl.SendResult.RESULT_UNKNOWN);
        assertThat(posts).hasValue(0);assertThat(jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,f.request().id())).isEqualTo("STARTED");
        assertDedicatedAbortUnfinished(f,before);
    }
    @Test void dedicatedAbortExternalChangeAfterGetDoesNotProveCancelOrClosure(){
        var f=dispatchFixture();var before=preservedBusiness(f.base(),f.operation());var posts=new AtomicInteger();var changed=new AtomicBoolean();
        when(gateway.observeRequestedAbort(any())).thenAnswer(call->{var observed=com.example.ecsite.payment.payjp.MockRequestedAbortTransport.observe(call.getArgument(0));changed.set(true);return observed;});
        doAnswer(call->{assertThat(changed).isTrue();com.example.ecsite.payment.payjp.MockRequestedAbortTransport.send(call.getArgument(0),f.request(),400,posts,()->{});return null;}).when(gateway).sendRequestedAbort(any());
        var result=f.control().send(gateway,f.request().id());assertThat(result.result()).isEqualTo(RequestedAbortDispatchControl.SendResult.RESULT_UNKNOWN);
        assertThat(state(f.operation().id())).isEqualTo("ABORT_UNKNOWN");assertThat(posts).hasValue(1);assertDedicatedAbortUnfinished(f,before);
    }
    @Test void dedicatedAbortParallelEndToEndSendHasOnePost() throws Exception{
        var f=dispatchFixture();var posts=new AtomicInteger();mockDedicatedSend(f,200,posts);var observedBoth=new java.util.concurrent.CountDownLatch(2);
        doAnswer(call->{var observed=com.example.ecsite.payment.payjp.MockRequestedAbortTransport.observe(call.getArgument(0));
            observedBoth.countDown();if(!observedBoth.await(3,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Both GET observations expected");return observed;
        }).when(gateway).observeRequestedAbort(any());
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.Callable<Boolean> send=()->{try{f.control().send(gateway,f.request().id());return true;}catch(IllegalStateException lost){return false;}};
        try{var a=pool.submit(send);var b=pool.submit(send);assertThat((a.get()?1:0)+(b.get()?1:0)).isEqualTo(1);assertThat(posts).hasValue(1);}
        finally{pool.shutdownNow();}
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"WEBHOOK","RECONCILIATION"})
    void dedicatedAbortEventsDuringPostCannotReleaseOrSendAgain(String route){
        var f=dispatchFixture();var before=preservedBusiness(f.base(),f.operation());var posts=new AtomicInteger();mockDedicatedSend(f,200,posts);
        doAnswer(call->{com.example.ecsite.payment.payjp.MockRequestedAbortTransport.send(call.getArgument(0),f.request(),0,posts,()->{
            if(route.equals("WEBHOOK"))webhook.synchronize("payment_flow.requires_action",newFlow);
            else reconciliation.reconcile(f.request().transactionId());
        });return null;}).when(gateway).sendRequestedAbort(any());
        assertThat(f.control().send(gateway,f.request().id()).result()).isEqualTo(RequestedAbortDispatchControl.SendResult.RESULT_UNKNOWN);
        assertThat(state(f.operation().id())).isEqualTo("ABORT_UNKNOWN");assertThat(posts).hasValue(1);
        verify(gateway,never()).sendOrderChangeRequest(any());assertDedicatedAbortUnfinished(f,before);
    }
    @Test void requestedAbortGetOnlyKeepsAllPersistedValuesUnchanged(){
        var f=dispatchFixture();var before=preservedBusiness(f.base(),f.operation());long version=version(f.operation().id());
        when(gateway.observeRequestedAbort(any())).thenAnswer(call->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return com.example.ecsite.payment.payjp.MockRequestedAbortTransport.observe(call.getArgument(0));
        });
        var observation=f.control().observe(gateway,f.request().id());observation.requireFresh();
        assertThat(preservedBusiness(f.base(),f.operation())).isEqualTo(before);
        assertThat(version(f.operation().id())).isEqualTo(version);
        assertThat(jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,f.request().id())).isEqualTo("READY");
        assertThat(jdbc.queryForObject("select count(*) from order_change_operations where id=? and abort_flow_closed_at is null and abort_flow_close_digest is null and abort_flow_close_version is null",Integer.class,f.operation().id())).isEqualTo(1);
        verify(gateway).observeRequestedAbort(any());verifyNoMoreInteractions(gateway);
    }
    @Test void requestedAbortGetFailureKeepsReadyAndVersion(){
        var f=dispatchFixture();var before=preservedBusiness(f.base(),f.operation());long version=version(f.operation().id());
        when(gateway.observeRequestedAbort(any())).thenThrow(new IllegalStateException("Mock GET failed"));
        assertThatThrownBy(()->f.control().observe(gateway,f.request().id())).isInstanceOf(IllegalStateException.class);
        assertThat(version(f.operation().id())).isEqualTo(version);assertThat(preservedBusiness(f.base(),f.operation())).isEqualTo(before);
        assertThat(jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,f.request().id())).isEqualTo("READY");
    }
    @Test void requestedAbortGetRejectsVersionChangedDuringTransportWithoutClaim(){
        var f=dispatchFixture();
        when(gateway.observeRequestedAbort(any())).thenAnswer(call->{
            var observation=com.example.ecsite.payment.payjp.MockRequestedAbortTransport.observe(call.getArgument(0));
            jdbc.update("update order_change_operations set version=version+1 where id=?",f.operation().id());return observation;
        });
        assertThatThrownBy(()->f.control().observe(gateway,f.request().id())).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,f.request().id())).isEqualTo("READY");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"versionless-state","new-flow","authorization","original-revision","held","old-cancel"})
    void requestedAbortGetRejectsInvalidSavedContextBeforeHttp(String mutation){
        var f=dispatchFixture();
        switch(mutation){
            case "versionless-state" -> jdbc.update("update order_change_operations set state='MANUAL_REVIEW',version=version+1 where id=?",f.operation().id());
            case "new-flow" -> jdbc.update("update payments set provider_payment_id=? where id=?","pfw_get_other_"+UUID.randomUUID(),f.request().paymentId());
            case "authorization" -> jdbc.update("update payment_transactions set status='FAILED' where payment_id=? and transaction_type='AUTHORIZE'",f.request().paymentId());
            case "original-revision" -> jdbc.update("update orders set content_revision=content_revision+1 where id=?",f.base().order());
            case "held" -> jdbc.update("update inventory_reservations set state='CONSUMED',consumed_at=localtimestamp where operation_id=?",f.operation().id());
            case "old-cancel" -> jdbc.update("update payments set status='CANCELLED' where id=?",f.base().oldPayment());
        }
        assertThatThrownBy(()->f.control().observe(gateway,f.request().id())).isInstanceOf(RuntimeException.class);verifyNoInteractions(gateway);
        assertThat(jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,f.request().id())).isEqualTo("READY");
    }
    @Test void requestedDispatchCommitsBeforeReturningOneUsePermitWithoutGateway(){
        var f=dispatchFixture();var before=preservedBusiness(f.base(),f.operation());long expected=version(f.operation().id());
        var dispatch=f.control().start(f.context(),f.observation());
        assertThat(jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,f.request().id())).isEqualTo("STARTED");
        assertThat(version(f.operation().id())).isEqualTo(expected+1);
        assertThat(dispatch.claimedVersion()).isEqualTo(expected+1);
        assertThatThrownBy(()->tx(()->{requestStore.findForDispatch(f.request().id());return null;})).isInstanceOf(OrderChangeFinancialRequestStore.DispatchUnavailable.class);
        assertThat(dispatch.consume().id()).isEqualTo(f.request().id());
        assertThatThrownBy(dispatch::consume).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->f.control().start(f.context(),f.observation())).isInstanceOf(IllegalStateException.class);
        assertThat(preservedBusiness(f.base(),f.operation())).isEqualTo(before);
        assertThat(jdbc.queryForObject("select abort_flow_closed_at is null and abort_flow_close_digest is null and abort_flow_close_version is null from order_change_operations where id=?",Boolean.class,f.operation().id())).isTrue();
        verifyNoInteractions(gateway);
    }
    @Test void requestedDispatchRequiresObservationBeforeConsumingReady(){
        var f=dispatchFixture();long expected=version(f.operation().id());
        assertThatThrownBy(()->f.control().start(f.context(),null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,f.request().id())).isEqualTo("READY");
        assertThat(version(f.operation().id())).isEqualTo(expected);verifyNoInteractions(gateway);
    }
    @Test void requestedDispatchParallelClaimsAndParallelConsumptionHaveOneWinner() throws Exception{
        var f=dispatchFixture();var gate=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.Callable<RequestedAbortDispatchControl.Dispatch> claim=()->{gate.await();try{return f.control().start(f.context(),f.observation());}catch(IllegalStateException lost){return null;}};
        try{
            var a=pool.submit(claim);var b=pool.submit(claim);gate.countDown();
            var permits=new ArrayList<RequestedAbortDispatchControl.Dispatch>();
            var left=a.get(15,java.util.concurrent.TimeUnit.SECONDS);var right=b.get(15,java.util.concurrent.TimeUnit.SECONDS);
            if(left!=null)permits.add(left);if(right!=null)permits.add(right);assertThat(permits).hasSize(1);
            var dispatch=permits.getFirst();var consumeGate=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> consume=()->{consumeGate.await();try{dispatch.consume();return true;}catch(IllegalStateException used){return false;}};
            var c=pool.submit(consume);var d=pool.submit(consume);consumeGate.countDown();
            assertThat((c.get(10,java.util.concurrent.TimeUnit.SECONDS)?1:0)+(d.get(10,java.util.concurrent.TimeUnit.SECONDS)?1:0)).isEqualTo(1);
            verifyNoInteractions(gateway);
        }finally{gate.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"STARTED","OBSERVED"})
    void requestedDispatchCannotRestoreConsumedDatabasePermission(String state){
        var f=dispatchFixture();
        jdbc.update("update order_change_request_dispatches set state='STARTED',started_at=clock_timestamp() where request_id=?",f.request().id());
        if(state.equals("OBSERVED"))jdbc.update("update order_change_request_dispatches set state='OBSERVED',observed_at=clock_timestamp() where request_id=?",f.request().id());
        assertThatThrownBy(()->f.control().load(f.request().id())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->f.control().start(f.context(),f.observation())).isInstanceOf(IllegalStateException.class);verifyNoInteractions(gateway);
    }
    /** Fault injection in the guarded disposable DB only, restoring the immutable trigger before the subject runs. */
    void requestedDispatchFixtureMutation(String table,String trigger,String sql,Object... args){
        tx(()->{jdbc.execute("alter table "+table+" disable trigger "+trigger);
            jdbc.update(sql,args);jdbc.execute("alter table "+table+" enable trigger "+trigger);return null;});
    }
    @Test void requestedDispatchMissingLegacyRowNeverGetsRepaired(){
        var f=dispatchFixture();long expected=version(f.operation().id());
        requestedDispatchFixtureMutation("order_change_request_dispatches","trg_change_dispatch_progress","delete from order_change_request_dispatches where request_id=?",f.request().id());
        assertThatThrownBy(()->f.control().start(f.context(),f.observation())).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from order_change_request_dispatches where request_id=?",Integer.class,f.request().id())).isZero();
        assertThat(version(f.operation().id())).isEqualTo(expected);verifyNoInteractions(gateway);
    }
    @Test void requestedDispatchExpiredWindowDoesNotConsumeReady(){
        var f=dispatchFixture();
        requestedDispatchFixtureMutation("order_change_financial_requests","change_requests_no_mutation","update order_change_financial_requests set created_at=statement_timestamp()-interval '2 days',replay_not_after=statement_timestamp()-interval '1 day 1 hour' where id=?",f.request().id());
        var context=f.control().load(f.request().id());var now=java.time.Instant.now();
        var observation=RequestedAbortDispatchControl.observe(context,now,now,dispatchBody(context.request()));
        assertThatThrownBy(()->f.control().start(context,observation)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,f.request().id())).isEqualTo("READY");verifyNoInteractions(gateway);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"version","MANUAL_REVIEW","ABORT_UNKNOWN","revision","flow","authorization_revision","authorization_status","authorization_provider","old_status","new_status","cancel_status","reservation","old_cancel"})
    void requestedDispatchRevalidatesStateAndHistoryUnderLocks(String mutation){
        var f=dispatchFixture();var op=f.operation();
        switch(mutation){
            case "version"->jdbc.update("update order_change_operations set version=version+1 where id=?",op.id());
            case "MANUAL_REVIEW","ABORT_UNKNOWN"->jdbc.update("update order_change_operations set state=?,version=version+1 where id=?",mutation,op.id());
            case "revision"->jdbc.update("update orders set content_revision=content_revision+1 where id=?",f.base().order());
            case "flow"->jdbc.update("update payments set provider_payment_id='pfw_other' where id=?",f.request().paymentId());
            case "authorization_revision"->jdbc.update("update payment_transactions set order_content_revision=order_content_revision+1 where order_change_operation_id=? and purpose='ORDER_CHANGE_AUTHORIZATION'",op.id());
            case "authorization_status"->jdbc.update("update payment_transactions set status='FAILED' where order_change_operation_id=? and purpose='ORDER_CHANGE_AUTHORIZATION'",op.id());
            case "authorization_provider"->jdbc.update("update payment_transactions set provider_transaction_id='pfw_other' where order_change_operation_id=? and purpose='ORDER_CHANGE_AUTHORIZATION'",op.id());
            case "old_status"->jdbc.update("update payments set status='CANCELLED' where id=?",f.base().oldPayment());
            case "new_status"->jdbc.update("update payments set status='PENDING' where id=?",f.request().paymentId());
            case "cancel_status"->jdbc.update("update payment_transactions set status='SUCCEEDED' where id=?",f.request().transactionId());
            case "reservation"->jdbc.update("update inventory_reservations set state='CONSUMED',consumed_at=localtimestamp where operation_id=?",op.id());
            case "old_cancel"->tx(()->{var old=payments.findById(f.base().oldPayment()).orElseThrow();transactions.saveAndFlush(PaymentTransaction.forOrderChange(old,PaymentTransactionType.CANCEL,old.getAmount(),0,UUID.randomUUID().toString(),orders.findCurrentTimeInTokyo(),PaymentTransactionPurpose.ORDER_CHANGE_OLD_AUTH_CANCEL,op.id()));return null;});
            default->throw new AssertionError();
        }
        long version=version(op.id());
        assertThatThrownBy(()->f.control().start(f.context(),f.observation())).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,f.request().id())).isEqualTo("READY");
        assertThat(version(op.id())).isEqualTo(version);verifyNoInteractions(gateway);
    }
    @Test void requestedDispatchBindsRequestFingerprintPaymentAndConnection(){
        var f=dispatchFixture();var stored=f.request();var original=stored.httpRequest();
        for(String change:List.of("payment","flow","connection","amount","transaction","operation")){
            var identity=change.equals("connection")?new com.example.ecsite.payment.capture.CaptureConnectionIdentity("other","test-account","TEST","https://api.pay.jp"):original.connection();
            var request=OrderChangeHttpRequest.cancel(identity,change.equals("operation")?UUID.randomUUID():original.operationId(),original.orderId(),
                original.amount()+(change.equals("amount")?1:0),original.idempotencyKey(),change.equals("flow")?"pfw_other":original.flowId(),true);
            var forged=new StoredOrderChangeRequest(stored.id(),change.equals("payment")?f.base().oldPayment():stored.paymentId(),
                change.equals("transaction")?Long.MAX_VALUE:stored.transactionId(),request,stored.createdAt(),stored.replayNotAfter());
            var context=new RequestedAbortDispatchControl.Context(forged,f.context().version());var now=java.time.Instant.now();
            var observed=RequestedAbortDispatchControl.observe(context,now,now,dispatchBody(forged));
            assertThatThrownBy(()->f.control().start(context,observed)).isInstanceOf(IllegalStateException.class);
        }
        assertThat(jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,stored.id())).isEqualTo("READY");verifyNoInteractions(gateway);
    }
    @Test void requestedDispatchRejectsStaleFutureAndUnconfirmedObservations(){
        var f=dispatchFixture();var now=java.time.Instant.now();
        for(var at:List.of(now.minusSeconds(10),now.plusSeconds(60))){
            var observed=RequestedAbortDispatchControl.observe(f.context(),at,at,dispatchBody(f.request()));
            assertThatThrownBy(()->f.control().start(f.context(),observed)).isInstanceOf(IllegalStateException.class);
        }
        var staleAt=now.minusSeconds(10);
        var stale=RequestedAbortDispatchControl.observe(f.context(),staleAt,staleAt,dispatchBody(f.request()));
        var staleClock=new RequestedAbortDispatchControl(resultTransactions,manager,java.time.Clock.fixed(staleAt,java.time.ZoneOffset.UTC));
        assertThatThrownBy(()->staleClock.start(f.context(),stale)).isInstanceOf(IllegalStateException.class); // DB-time check, not only caller clock.
        var body=dispatchBody(f.request());body.put("status","requires_payment_method");
        assertThatThrownBy(()->RequestedAbortDispatchControl.observe(f.context(),now,now,body)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->RequestedAbortDispatchControl.observe(f.context(),now,now.minusSeconds(1),dispatchBody(f.request()))).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,f.request().id())).isEqualTo("READY");verifyNoInteractions(gateway);
    }
    @Test void requestedDispatchExpiredConsumptionBurnsPermitAndCannotSerialize() throws Exception{
        var f=dispatchFixture();var clock=mock(java.time.Clock.class);var now=java.time.Instant.now();when(clock.instant()).thenReturn(now);
        var control=new RequestedAbortDispatchControl(resultTransactions,manager,clock);var dispatch=control.start(f.context(),f.observation());
        assertThatThrownBy(()->new java.io.ObjectOutputStream(new java.io.ByteArrayOutputStream()).writeObject(dispatch)).isInstanceOf(java.io.NotSerializableException.class);
        when(clock.instant()).thenReturn(now.plusSeconds(60));
        assertThatThrownBy(dispatch::consume).isInstanceOf(IllegalStateException.class);
        when(clock.instant()).thenReturn(now);
        assertThatThrownBy(dispatch::consume).isInstanceOf(IllegalStateException.class);verifyNoInteractions(gateway);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void requestedDispatchAmbiguousCommitNeverReturnsPermit(boolean committed){
        var f=dispatchFixture();long expected=version(f.operation().id());
        var factory=((org.springframework.orm.jpa.JpaTransactionManager)manager).getEntityManagerFactory();
        var ambiguous=new org.springframework.orm.jpa.JpaTransactionManager(factory){
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){
                if(committed)super.doCommit(status);else super.doRollback(status);
                throw new org.springframework.transaction.TransactionSystemException("Synthetic ambiguous dispatch commit");
            }
        };
        var control=new RequestedAbortDispatchControl(resultTransactions,ambiguous,java.time.Clock.systemUTC());
        var returned=new AtomicReference<RequestedAbortDispatchControl.Dispatch>();
        assertThatThrownBy(()->returned.set(control.start(f.context(),f.observation()))).isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
        assertThat(returned.get()).isNull();
        assertThat(jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,f.request().id())).isEqualTo(committed?"STARTED":"READY");
        assertThat(version(f.operation().id())).isEqualTo(committed?expected+1:expected);verifyNoInteractions(gateway);
    }
    @Test void requestedDispatchRollbackOnlyNeverReturnsPermit(){
        var f=dispatchFixture();long expected=version(f.operation().id());
        PlatformTransactionManager rollbackOnly=new PlatformTransactionManager(){
            public TransactionStatus getTransaction(TransactionDefinition definition){return manager.getTransaction(definition);}
            public void commit(TransactionStatus status){status.setRollbackOnly();manager.commit(status);}
            public void rollback(TransactionStatus status){manager.rollback(status);}
        };
        var control=new RequestedAbortDispatchControl(resultTransactions,rollbackOnly,java.time.Clock.systemUTC());
        var returned=new AtomicReference<RequestedAbortDispatchControl.Dispatch>();
        assertThatThrownBy(()->returned.set(control.start(f.context(),f.observation()))).isInstanceOf(IllegalStateException.class);
        assertThat(returned.get()).isNull();assertThat(version(f.operation().id())).isEqualTo(expected);
        assertThat(jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,f.request().id())).isEqualTo("READY");
        verifyNoInteractions(gateway);
    }
    @Test void requestedDispatchStartFailureRollsBackBothWrites(){
        var f=dispatchFixture();long expected=version(f.operation().id());
        String name="fail_abort_start_"+f.request().id();
        jdbc.execute("create function "+name+"() returns trigger language plpgsql as $$ begin raise exception 'Synthetic start rollback'; end $$");
        jdbc.execute("create trigger "+name+" after update on order_change_request_dispatches for each row when (new.request_id="+f.request().id()+") execute function "+name+"()");
        try{
            assertThatThrownBy(()->f.control().start(f.context(),f.observation())).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(version(f.operation().id())).isEqualTo(expected);
            assertThat(jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,f.request().id())).isEqualTo("READY");
            verifyNoInteractions(gateway);
        }finally{jdbc.execute("drop trigger "+name+" on order_change_request_dispatches");jdbc.execute("drop function "+name+"()");}
    }
    @Test void requestedAbortPreparationIsAtomicIdempotentAndCannotDispatch(){
        var f=fixture();var op=requestedAuthorized(f);var before=preservedBusiness(f,op);long expected=version(op.id());
        long movements=jdbc.queryForObject("select count(*) from stock_movements where order_id=?",Long.class,f.order());
        var evidence=abortEvidence(f,op);
        var saved=resultTransactions.prepareRequestedAbort(f.order(),op.id(),expected,evidence);
        assertThat(resultTransactions.prepareRequestedAbort(f.order(),op.id(),version(op.id()),evidence).id()).isEqualTo(saved.id());
        assertThat(saved.paymentId()).isNotEqualTo(f.oldPayment());assertThat(saved.httpRequest().kind()).isEqualTo("NEW_ABORT");
        assertThat(jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,saved.id())).isEqualTo("READY");
        assertThat(jdbc.queryForObject("select count(*) from payment_transactions where order_change_operation_id=? and purpose='ORDER_CHANGE_NEW_AUTH_ABORT'",Integer.class,op.id())).isEqualTo(1);
        assertThatThrownBy(()->tx(()->{requestStore.findForDispatch(saved.id());return null;})).isInstanceOf(OrderChangeFinancialRequestStore.DispatchUnavailable.class);
        assertThat(preservedBusiness(f,op)).isEqualTo(before);assertThat(state(op.id())).isEqualTo("ABORTING");
        assertThat(jdbc.queryForObject("select count(*) from stock_movements where order_id=?",Long.class,f.order())).isEqualTo(movements);
        verify(gateway,times(1)).sendOrderChangeRequest(any()); // Fixture CREATE only; preparation never sends.
    }
    @Test void requestedAbortRejectsStaleVersionAndMissingIntent(){
        var f=fixture();var op=accept(f,3);authorizeWithoutDecision(f,op);var evidence=abortEvidence(f,op);
        assertThatThrownBy(()->resultTransactions.prepareRequestedAbort(f.order(),op.id(),version(op.id()),evidence)).isInstanceOf(IllegalStateException.class);
        resultTransactions.requestAbort(abortCommand(f,op,UUID.randomUUID(),version(op.id())));
        assertThatThrownBy(()->resultTransactions.prepareRequestedAbort(f.order(),op.id(),version(op.id())-1,evidence)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from payment_transactions where order_change_operation_id=? and purpose='ORDER_CHANGE_NEW_AUTH_ABORT'",Integer.class,op.id())).isZero();
    }
    @Test void requestedAbortRejectsDifferentContentOnReplay(){
        var f=fixture();var op=requestedAuthorized(f);var evidence=abortEvidence(f,op);
        var saved=resultTransactions.prepareRequestedAbort(f.order(),op.id(),version(op.id()),evidence);
        var body=tools.jackson.databind.json.JsonMapper.builder().build().readTree("""
            {"id":"pfw_other","status":"requires_capture","amount":%d,"currency":"jpy","capture_method":"manual",
             "livemode":false,"amount_received":0,"amount_capturable":%d,
             "metadata":{"order_id":"%s","order_change_operation_id":"%s"}}
            """.formatted(op.afterAmount(),op.afterAmount(),f.order(),op.id()));
        var conflicting=OrderReauthorizationTransactions.RequestedAbortEvidence.verify(f.order(),op.id(),saved.paymentId(),"pfw_other",op.afterAmount(),
            new com.example.ecsite.payment.capture.CaptureConnectionIdentity("test-capture","test-account","TEST","https://api.pay.jp"),body);
        assertThatThrownBy(()->resultTransactions.prepareRequestedAbort(f.order(),op.id(),version(op.id()),conflicting)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from order_change_financial_requests where operation_id=? and request_kind='NEW_ABORT'",Integer.class,op.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,saved.id())).isEqualTo("READY");
    }
    @Test void requestedAbortStopsForManualReviewAndUnknownWithoutPreparingAgain(){
        for(String blocked:List.of("MANUAL_REVIEW","ABORT_UNKNOWN")){
            var f=fixture();var op=requestedAuthorized(f);
            jdbc.update("update order_change_operations set state=?,version=version+1 where id=?",blocked,op.id());
            assertThatThrownBy(()->resultTransactions.prepareRequestedAbort(f.order(),op.id(),version(op.id()),abortEvidence(f,op))).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->jdbc.update("update order_change_operations set state='ABORTING',version=version+1 where id=?",op.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
    }
    @Test void requestedAbortRollsBackTransactionRequestAndReadyOnInsertFailure(){
        var f=fixture();var op=requestedAuthorized(f);var before=preservedBusiness(f,op);long expected=version(op.id());
        long dispatches=jdbc.queryForObject("select count(*) from order_change_request_dispatches",Long.class);
        tx(()->{doAnswer(call->{call.callRealMethod();throw new IllegalStateException("Synthetic preparation rollback");}).when(requestStore).savePreparedAbort(any());return null;});
        assertThatThrownBy(()->resultTransactions.prepareRequestedAbort(f.order(),op.id(),expected,abortEvidence(f,op)))
            .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class).hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(version(op.id())).isEqualTo(expected);assertThat(state(op.id())).isEqualTo("AUTHORIZED");
        assertThat(jdbc.queryForObject("select count(*) from payment_transactions where order_change_operation_id=? and purpose='ORDER_CHANGE_NEW_AUTH_ABORT'",Integer.class,op.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from order_change_financial_requests where operation_id=? and request_kind='NEW_ABORT'",Integer.class,op.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from order_change_request_dispatches",Long.class)).isEqualTo(dispatches);
        assertThat(preservedBusiness(f,op)).isEqualTo(before);
    }
    @Test void requestedAbortRejectsOriginalOrderChangesAndCompetingOldCancel(){
        var f=fixture();var op=requestedAuthorized(f);var evidence=abortEvidence(f,op);
        jdbc.update("update orders set content_revision=content_revision+1 where id=?",f.order());
        assertThatThrownBy(()->resultTransactions.prepareRequestedAbort(f.order(),op.id(),version(op.id()),evidence)).isInstanceOf(IllegalStateException.class);
        jdbc.update("update orders set content_revision=content_revision-1 where id=?",f.order());
        tx(()->{var old=payments.findById(f.oldPayment()).orElseThrow();transactions.saveAndFlush(PaymentTransaction.forOrderChange(old,PaymentTransactionType.CANCEL,old.getAmount(),0,UUID.randomUUID().toString(),orders.findCurrentTimeInTokyo(),PaymentTransactionPurpose.ORDER_CHANGE_OLD_AUTH_CANCEL,op.id()));return null;});
        assertThatThrownBy(()->resultTransactions.prepareRequestedAbort(f.order(),op.id(),version(op.id()),evidence)).isInstanceOf(IllegalStateException.class);
    }
    @Test void requestedAbortConcurrentPreparationHasOneWinner() throws Exception{
        var f=fixture();var op=requestedAuthorized(f);long expected=version(op.id());var evidence=abortEvidence(f,op);
        var gate=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.Callable<Boolean> prepare=()->{gate.await();try{resultTransactions.prepareRequestedAbort(f.order(),op.id(),expected,evidence);return true;}catch(IllegalStateException conflict){return false;}};
        try{
            var a=pool.submit(prepare);var b=pool.submit(prepare);gate.countDown();
            assertThat((a.get(15,java.util.concurrent.TimeUnit.SECONDS)?1:0)+(b.get(15,java.util.concurrent.TimeUnit.SECONDS)?1:0)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from order_change_financial_requests where operation_id=? and request_kind='NEW_ABORT'",Integer.class,op.id())).isEqualTo(1);
            service.advance(f.order(),op.id());assertThat(jdbc.queryForObject("select count(*) from order_change_financial_requests where operation_id=? and request_kind='OLD_CANCEL'",Integer.class,op.id())).isZero();
        }finally{gate.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
    }
    @Test void abortIntentIsImmutableIdempotentAndVersioned(){
        var f=fixture();var op=accept(f,3);var before=preservedBusiness(f,op);long expected=version(op.id());
        var command=abortCommand(f,op,UUID.randomUUID(),expected);
        assertThat(resultTransactions.requestAbort(abortCommand(f,op,UUID.randomUUID(),expected+1)).result()).isEqualTo(OrderReauthorizationTransactions.AbortResult.VERSION_CONFLICT);
        assertThat(resultTransactions.requestAbort(command).result()).isEqualTo(OrderReauthorizationTransactions.AbortResult.ACCEPTED);
        var saved=jdbc.queryForMap("select * from order_change_operations where id=?",op.id());
        assertThat(resultTransactions.requestAbort(command).result()).isEqualTo(OrderReauthorizationTransactions.AbortResult.REPLAYED);
        assertThat(jdbc.queryForMap("select * from order_change_operations where id=?",op.id())).isEqualTo(saved);
        assertThat(resultTransactions.requestAbort(abortCommand(f,op,UUID.randomUUID(),expected)).result()).isEqualTo(OrderReauthorizationTransactions.AbortResult.CONTENT_CONFLICT);
        var changed=new OrderReauthorizationTransactions.AbortRequest(f.order(),f.user(),op.id(),command.requestId(),expected,
            command.requesterType(),OrderReauthorizationTransactions.AbortReasonCode.COMMUNICATION_FAILURE);
        assertThat(resultTransactions.requestAbort(changed).result()).isEqualTo(OrderReauthorizationTransactions.AbortResult.CONTENT_CONFLICT);
        assertThat(resultTransactions.requestAbort(abortCommand(f,op,command.requestId(),expected+1)).result()).isEqualTo(OrderReauthorizationTransactions.AbortResult.CONTENT_CONFLICT);
        assertThat(version(op.id())).isEqualTo(expected+1);assertAbortHeld(f,op,before);
        assertThatThrownBy(()->jdbc.update("update order_change_operations set abort_reason_code='PAYMENT_FAILURE' where id=?",op.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("update order_change_operations set switch_decided_at=localtimestamp,state='OLD_CANCEL_PENDING' where id=?",op.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void abortRejectsWrongCustomerAndGlobalRequestUuidReuse(){
        var f=fixture();var op=accept(f,3);UUID request=UUID.randomUUID();
        var wrong=new OrderReauthorizationTransactions.AbortRequest(f.order(),f.user()+10000,op.id(),request,version(op.id()),
            OrderReauthorizationTransactions.AbortRequesterType.CUSTOMER,OrderReauthorizationTransactions.AbortReasonCode.CUSTOMER_REQUEST);
        assertThatThrownBy(()->resultTransactions.requestAbort(wrong)).isInstanceOf(com.example.ecsite.exception.OrderNotFoundException.class);
        assertThat(resultTransactions.requestAbort(abortCommand(f,op,request,version(op.id()))).result()).isEqualTo(OrderReauthorizationTransactions.AbortResult.ACCEPTED);
        var other=fixture();var candidate=accept(other,3);
        assertThat(resultTransactions.requestAbort(abortCommand(other,candidate,request,version(candidate.id()))).result()).isEqualTo(OrderReauthorizationTransactions.AbortResult.CONTENT_CONFLICT);
        assertThat(jdbc.queryForObject("select abort_request_id from order_change_operations where id=?",UUID.class,candidate.id())).isNull();
    }
    @Test void reservedAbortIntentBlocksCreationWithdrawalAndExpiry(){
        var f=fixture();var op=accept(f,3);var before=preservedBusiness(f,op);mockFlow(f,op);
        resultTransactions.requestAbort(abortCommand(f,op,UUID.randomUUID(),version(op.id())));
        assertThat(service.advance(f.order(),op.id()).abortRequested()).isTrue();verifyNoInteractions(gateway);
        doReturn(op.graceUntil().plusMinutes(1)).when(orders).findCurrentTimeInTokyo();
        assertThat(increases.expireReserved(f.order(),op.id())).isFalse();
        assertThatThrownBy(()->increases.abort(f.order(),f.user(),op.id())).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from order_change_financial_requests where operation_id=?",Integer.class,op.id())).isZero();assertAbortHeld(f,op,before);
    }
    @Test void preparedCreateCannotStartAfterAbortIntent(){
        var f=fixture();var op=accept(f,3);var before=preservedBusiness(f,op);mockFlow(f,op);
        var prepared=resultTransactions.prepare(f.order(),op.id());
        resultTransactions.requestAbort(abortCommand(f,op,UUID.randomUUID(),version(op.id())));
        var sender=new OrderChangeRequestSender(requestStore,gateway,manager);
        assertThatThrownBy(()->sender.send(prepared.createRequest())).isInstanceOf(OrderChangeFinancialRequestStore.DispatchUnavailable.class);
        assertThat(jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,prepared.createRequest())).isEqualTo("READY");
        service.advance(f.order(),op.id());verifyNoInteractions(gateway);assertAbortHeld(f,op,before);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"DIRECT","WEBHOOK","RECONCILIATION"})
    void lateAuthorizationIsRecordedWithoutProgressAfterAbort(String route){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);newState.set("requires_payment_method");service.advance(f.order(),op.id());
        var before=preservedBusiness(f,op);resultTransactions.requestAbort(abortCommand(f,op,UUID.randomUUID(),version(op.id())));newState.set("requires_capture");
        if(route.equals("DIRECT"))resultTransactions.authorization(f.order(),op.id(),newAuthorized(f,op));
        else if(route.equals("WEBHOOK"))webhook.synchronize("payment_flow.requires_action",newFlow);
        else reconciliation.reconcile(jdbc.queryForObject("select id from payment_transactions where order_change_operation_id=? and purpose='ORDER_CHANGE_AUTHORIZATION'",Long.class,op.id()));
        assertThat(jdbc.queryForObject("select status from payment_transactions where order_change_operation_id=? and purpose='ORDER_CHANGE_AUTHORIZATION'",String.class,op.id())).isEqualTo("SUCCEEDED");
        assertThat(state(op.id())).isEqualTo("AUTHORIZING");
        service.advance(f.order(),op.id());service.reconcile(f.order(),op.id());
        resultTransactions.decide(f.order(),op.id(),newAuthorized(f,op),observation(f.oldFlow(),"requires_capture",op.beforeAmount(),Map.of()));
        resultTransactions.complete(f.order(),op.id(),newAuthorized(f,op));
        resultTransactions.cancellation(f.order(),op.id(),true,observation(newFlow,"canceled",op.afterAmount(),Map.of()));
        verify(gateway,times(1)).sendOrderChangeRequest(any());assertAbortHeld(f,op,before);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"WEBHOOK","RECONCILIATION"})
    void abortDuringExternalGetFencesConcurrentResultApplication(String route) throws Exception{
        var f=fixture();var op=accept(f,3);mockFlow(f,op);newState.set("requires_payment_method");service.advance(f.order(),op.id());
        var before=preservedBusiness(f,op);var observed=newAuthorized(f,op);
        long auth=jdbc.queryForObject("select id from payment_transactions where order_change_operation_id=? and purpose='ORDER_CHANGE_AUTHORIZATION'",Long.class,op.id());
        var entered=new java.util.concurrent.CountDownLatch(1);var finish=new java.util.concurrent.CountDownLatch(1);
        doAnswer(call->{entered.countDown();assertThat(finish.await(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();return observed;}).when(gateway).observeOrderChangeFlow(newFlow);
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{
            var task=pool.submit(()->{if(route.equals("WEBHOOK"))webhook.synchronize("payment_flow.requires_action",newFlow);else reconciliation.reconcile(auth);});
            assertThat(entered.await(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(resultTransactions.requestAbort(abortCommand(f,op,UUID.randomUUID(),version(op.id()))).result()).isEqualTo(OrderReauthorizationTransactions.AbortResult.ACCEPTED);
            finish.countDown();task.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("select status from payment_transactions where id=?",String.class,auth)).isEqualTo("SUCCEEDED");
            verify(gateway,times(1)).sendOrderChangeRequest(any());assertAbortHeld(f,op,before);
        }finally{finish.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
    }
    @Test void canceledObservationDoesNotReleaseAnAbortIntent(){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);newState.set("requires_payment_method");service.advance(f.order(),op.id());
        var before=preservedBusiness(f,op);resultTransactions.requestAbort(abortCommand(f,op,UUID.randomUUID(),version(op.id())));
        newState.set("canceled");service.reconcile(f.order(),op.id());assertAbortHeld(f,op,before);
    }
    @Test void manualReviewAbortDoesNotClearReviewOrApplyResults(){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);newState.set("requires_payment_method");service.advance(f.order(),op.id());
        jdbc.update("update order_change_operations set state='MANUAL_REVIEW',financial_review_reason='TEST_REVIEW' where id=?",op.id());
        var before=preservedBusiness(f,op);resultTransactions.requestAbort(abortCommand(f,op,UUID.randomUUID(),version(op.id())));
        clearInvocations(gateway);service.advance(f.order(),op.id());service.reconcile(f.order(),op.id());resultTransactions.authorization(f.order(),op.id(),newAuthorized(f,op));
        assertThat(state(op.id())).isEqualTo("MANUAL_REVIEW");verifyNoInteractions(gateway);assertAbortHeld(f,op,before);
    }
    @Test void abortWinnerBlocksDecisionAndDecisionWinnerRejectsAbort(){
        var f=fixture();var op=accept(f,3);authorizeWithoutDecision(f,op);var before=preservedBusiness(f,op);
        resultTransactions.requestAbort(abortCommand(f,op,UUID.randomUUID(),version(op.id())));
        assertThat(resultTransactions.decide(f.order(),op.id(),newAuthorized(f,op),observation(f.oldFlow(),"requires_capture",op.beforeAmount(),Map.of())).abortRequested()).isTrue();assertAbortHeld(f,op,before);
        var other=fixture();var next=accept(other,3);authorizeWithoutDecision(other,next);long expected=version(next.id());
        assertThat(resultTransactions.decide(other.order(),next.id(),newAuthorized(other,next),observation(other.oldFlow(),"requires_capture",next.beforeAmount(),Map.of())).state()).isEqualTo("OLD_CANCEL_PENDING");
        assertThat(resultTransactions.requestAbort(abortCommand(other,next,UUID.randomUUID(),expected)).result()).isEqualTo(OrderReauthorizationTransactions.AbortResult.NOT_ALLOWED);
    }
    @Test void concurrentAbortRequestsHaveOneAcceptance() throws Exception{
        var f=fixture();var op=accept(f,3);var command=abortCommand(f,op,UUID.randomUUID(),version(op.id()));
        var gate=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            var a=pool.submit(()->{gate.await();return resultTransactions.requestAbort(command).result();});
            var b=pool.submit(()->{gate.await();return resultTransactions.requestAbort(command).result();});gate.countDown();
            assertThat(List.of(a.get(15,java.util.concurrent.TimeUnit.SECONDS),b.get(15,java.util.concurrent.TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(OrderReauthorizationTransactions.AbortResult.ACCEPTED,OrderReauthorizationTransactions.AbortResult.REPLAYED);
            assertThat(version(op.id())).isEqualTo(command.expectedVersion()+1);
        }finally{gate.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
    }
    @Test void concurrentDecisionAndAbortCannotBothWin() throws Exception{
        var f=fixture();var op=accept(f,3);authorizeWithoutDecision(f,op);var command=abortCommand(f,op,UUID.randomUUID(),version(op.id()));
        var observed=newAuthorized(f,op);var old=observation(f.oldFlow(),"requires_capture",op.beforeAmount(),Map.of());
        var gate=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            var a=pool.submit(()->{gate.await();return resultTransactions.requestAbort(command);});
            var b=pool.submit(()->{gate.await();return resultTransactions.decide(f.order(),op.id(),observed,old);});gate.countDown();
            var abort=a.get(15,java.util.concurrent.TimeUnit.SECONDS);b.get(15,java.util.concurrent.TimeUnit.SECONDS);
            int decisions=jdbc.queryForObject("select count(*) from order_change_operations where id=? and switch_decided_at is not null",Integer.class,op.id());
            assertThat((abort.result()==OrderReauthorizationTransactions.AbortResult.ACCEPTED?1:0)+decisions).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from order_change_financial_requests where operation_id=? and request_kind='OLD_CANCEL'",Integer.class,op.id())).isEqualTo(decisions);
            assertHeldOriginal(f,op);
        }finally{gate.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
    }
    @Test void alreadyStartedCreateResponseIsPreservedButCannotAdvance() throws Exception{
        var f=fixture();var op=accept(f,3);mockFlow(f,op);var before=preservedBusiness(f,op);
        var entered=new java.util.concurrent.CountDownLatch(1);var finish=new java.util.concurrent.CountDownLatch(1);
        doAnswer(call->{entered.countDown();assertThat(finish.await(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();return transport.answer(call);}).when(gateway).sendOrderChangeRequest(any());
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{
            var task=pool.submit(()->service.advance(f.order(),op.id()));assertThat(entered.await(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            resultTransactions.requestAbort(abortCommand(f,op,UUID.randomUUID(),version(op.id())));finish.countDown();
            var result=task.get(15,java.util.concurrent.TimeUnit.SECONDS);assertThat(result.abortRequested()).isTrue();assertThat(result.newFlow()).isEqualTo(newFlow);
            service.advance(f.order(),op.id());verify(gateway,times(1)).sendOrderChangeRequest(any());verify(gateway,never()).observeOrderChangeFlow(anyString());assertAbortHeld(f,op,before);
        }finally{finish.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
    }
    @Test void abortIntentSchemaStartsNullableWithoutBackfill(){
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=current_schema() and table_name='order_change_operations' and column_name='abort_requested_at' and is_nullable='YES'",Integer.class)).isEqualTo(1);
    }
    String state(UUID op){return jdbc.queryForObject("select state from order_change_operations where id=?",String.class,op);}
    PayJpPaymentFlowObservation jsonObservation(String flow,String status,int amount,Map<String,Object> metadata,String received){
        String capturable=status.equals("requires_capture")?Integer.toString(amount):status.equals("canceled")?"0":"NULL";
        return jsonObservation(flow,status,amount,metadata,received,capturable);
    }
    PayJpPaymentFlowObservation jsonObservation(String flow,String status,int amount,Map<String,Object> metadata,String received,String capturable){
        var fields=new LinkedHashMap<String,Object>();
        fields.put("id",flow);fields.put("status",status);fields.put("amount",amount);fields.put("currency","jpy");
        fields.put("capture_method","manual");fields.put("livemode",false);fields.put("metadata",metadata);
        if(!capturable.equals("MISSING"))fields.put("amount_capturable",capturable.equals("NULL")?null:Long.valueOf(capturable));
        if(!received.equals("MISSING"))fields.put("amount_received",received.equals("NULL")?null:Long.valueOf(received));
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
        var raw=mapper.readTree(mapper.writeValueAsString(fields));
        var result=mapper.treeToValue(raw,PayJpPaymentFlowObservation.class);
        return result.withOldCancellationEvidence(com.example.ecsite.payment.payjp.OldAuthorizationCancellationEvidence.fromJson(raw,
            new com.example.ecsite.payment.capture.CaptureConnectionIdentity("test-capture","test-account","TEST","https://api.pay.jp")));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "requires_payment_method,NULL", "requires_payment_method,0", "requires_payment_method,MISSING",
        "requires_confirmation,NULL", "requires_confirmation,0", "requires_confirmation,MISSING",
        "requires_action,NULL", "requires_action,0", "requires_action,MISSING",
        "processing,NULL", "processing,0", "processing,MISSING"})
    void preAuthorizationNullableReceivedWaitsWithoutCancel(String status,String received){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);
        var observed=jsonObservation(newFlow,status,op.afterAmount(),
            Map.of("order_id",Long.toString(f.order()),"order_change_operation_id",op.id().toString()),received);
        doReturn(observed).when(gateway).observeOrderChangeFlow(newFlow);
        assertThat(service.advance(f.order(),op.id()).state())
            .isEqualTo(status.equals("requires_action")?"REQUIRES_ACTION":"AUTHORIZING");
        assertThat(oldCancelled).isFalse();verify(gateway,times(1)).sendOrderChangeRequest(any());
        assertThat(jdbc.queryForObject("select count(*) from order_change_financial_requests where operation_id=? and request_kind<>'CREATE'",Integer.class,op.id())).isZero();
        assertThat(jdbc.queryForObject("select status from payments where id=?",String.class,f.oldPayment())).isEqualTo("AUTHORIZED");
        assertThat(jdbc.queryForObject("select active_payment_id from orders where id=?",Long.class,f.order())).isEqualTo(f.oldPayment());
        assertThat(jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order())).isZero();
        assertThat(jdbc.queryForObject("select state from inventory_reservations where operation_id=?",String.class,op.id())).isEqualTo("HELD");
        if(status.equals("requires_payment_method"))assertThat(resultTransactions.issueBrowserConfirmation(f.order(),op.id(),observed)).isTrue();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"unknown_state,NULL,NEW_FLOW_EVIDENCE_MISMATCH",
        "unknown_state,0,UNCONFIRMED_AUTH_STATE", "'',NULL,NEW_FLOW_EVIDENCE_MISMATCH", "'',0,UNCONFIRMED_AUTH_STATE"})
    void unknownAndEmptyStatusNeverAuthorizeOrCancel(String status,String received,String reason){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);
        doReturn(jsonObservation(newFlow,status,op.afterAmount(),
            Map.of("order_id",Long.toString(f.order()),"order_change_operation_id",op.id().toString()),received))
            .when(gateway).observeOrderChangeFlow(newFlow);
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("MANUAL_REVIEW");
        assertThat(jdbc.queryForObject("select financial_review_reason from order_change_operations where id=?",String.class,op.id())).isEqualTo(reason);
        assertThat(oldCancelled).isFalse();verify(gateway,times(1)).sendOrderChangeRequest(any());
        assertThat(jdbc.queryForObject("select status from payments where id=(select new_payment_id from order_change_operations where id=?)",String.class,op.id())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select switch_decided_at from order_change_operations where id=?",java.time.LocalDateTime.class,op.id())).isNull();
        assertThat(jdbc.queryForObject("select count(*) from order_change_financial_requests where operation_id=? and request_kind<>'CREATE'",Integer.class,op.id())).isZero();
        assertThat(jdbc.queryForObject("select active_payment_id from orders where id=?",Long.class,f.order())).isEqualTo(f.oldPayment());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"NEW_AUTHORIZATION,MISSING,NEW_FLOW_EVIDENCE_MISMATCH",
        "OLD_AUTHORIZATION,MISSING,OLD_AUTH_NOT_READY", "COMMIT,MISSING,COMMIT_AUTHORIZATION_UNAVAILABLE",
        "OLD_AUTHORIZATION,1,OLD_AUTH_NOT_READY", "OLD_AUTHORIZATION,-1,OLD_AUTH_NOT_READY",
        "COMMIT,1,COMMIT_AUTHORIZATION_UNAVAILABLE", "COMMIT,-1,COMMIT_AUTHORIZATION_UNAVAILABLE"})
    void missingOrNonzeroReceivedNeverProvesAuthorizationCancelEligibilityOrSwitch(String stage,String received,String reason){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);
        var metadata=Map.<String,Object>of("order_id",Long.toString(f.order()),"order_change_operation_id",op.id().toString());
        var missing=jsonObservation(newFlow,"requires_capture",op.afterAmount(),metadata,received);
        if(stage.equals("OLD_AUTHORIZATION"))doReturn(jsonObservation(f.oldFlow(),"requires_capture",op.beforeAmount(),Map.of(),received))
            .when(gateway).observeOrderChangeFlow(f.oldFlow());
        else if(stage.equals("COMMIT"))doReturn(observation(newFlow,"requires_capture",op.afterAmount(),metadata),missing)
            .when(gateway).observeOrderChangeFlow(newFlow);
        else doReturn(missing).when(gateway).observeOrderChangeFlow(newFlow);
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("MANUAL_REVIEW");
        assertThat(jdbc.queryForObject("select financial_review_reason from order_change_operations where id=?",String.class,op.id())).isEqualTo(reason);
        assertThat(oldCancelled.get()).isEqualTo(stage.equals("COMMIT"));
        verify(gateway,times(stage.equals("COMMIT")?2:1)).sendOrderChangeRequest(any());
        assertThat(jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order())).isZero();
        assertThat(jdbc.queryForObject("select active_payment_id from orders where id=?",Long.class,f.order())).isEqualTo(f.oldPayment());
        assertThat(jdbc.queryForObject("select state from inventory_reservations where operation_id=?",String.class,op.id())).isEqualTo("HELD");
        assertThat(jdbc.queryForObject("select count(*) from order_content_change_histories where order_id=?",Integer.class,f.order())).isZero();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"flow","amount","currency","mode","capture",
        "metadata_missing","metadata_order_type","metadata_operation_type","received_positive","received_negative",
        "confirmation_nonzero","action_nonzero","processing_nonzero","authorized_null","canceled_null","canceled_capturable_null"})
    void invalidNewFlowEvidenceStillStopsBeforeOldCancel(String invalid){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);
        String status=switch(invalid){case "authorized_null"->"requires_capture";case "canceled_null","canceled_capturable_null"->"canceled";
            case "confirmation_nonzero"->"requires_confirmation";case "action_nonzero"->"requires_action";case "processing_nonzero"->"processing";default->"requires_payment_method";};
        Long received=switch(invalid){case "received_positive","confirmation_nonzero","action_nonzero","processing_nonzero"->1L;case "received_negative"->-1L;case "canceled_capturable_null"->0L;default->null;};
        Map<String,Object> metadata=new HashMap<>(Map.of("order_id",Long.toString(f.order()),"order_change_operation_id",op.id().toString()));
        if(invalid.equals("metadata_missing"))metadata=null;
        if(invalid.equals("metadata_order_type"))metadata.put("order_id",f.order());
        if(invalid.equals("metadata_operation_type"))metadata.put("order_change_operation_id",123);
        var observed=new PayJpPaymentFlowObservation(invalid.equals("flow")?"pfw_other":newFlow,status,
            invalid.equals("amount")?(long)op.afterAmount()+1:(long)op.afterAmount(),invalid.equals("currency")?"JPY":"jpy",
            received,null,invalid.equals("capture")?"automatic":"manual",invalid.equals("mode"),null,null,metadata);
        doReturn(observed).when(gateway).observeOrderChangeFlow(newFlow);
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("MANUAL_REVIEW");
        assertThat(jdbc.queryForObject("select financial_review_reason from order_change_operations where id=?",String.class,op.id())).isEqualTo("NEW_FLOW_EVIDENCE_MISMATCH");
        assertThat(oldCancelled).isFalse();
        assertThat(jdbc.queryForObject("select switch_decided_at from order_change_operations where id=?",java.time.LocalDateTime.class,op.id())).isNull();
        assertThat(jdbc.queryForObject("select count(*) from order_change_financial_requests where operation_id=? and request_kind<>'CREATE'",Integer.class,op.id())).isZero();
        service.advance(f.order(),op.id());verify(gateway,times(1)).sendOrderChangeRequest(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"NEW,NULL","OLD,NULL","BOTH,NULL","BOTH,0"})
    void fullUncapturedAuthorizationWithExplicitReceivedEvidenceSwitchesOnce(String role,String received){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);
        var metadata=Map.<String,Object>of("order_id",Long.toString(f.order()),"order_change_operation_id",op.id().toString());
        if(!role.equals("OLD"))doReturn(jsonObservation(newFlow,"requires_capture",op.afterAmount(),metadata,received))
            .when(gateway).observeOrderChangeFlow(newFlow);
        if(!role.equals("NEW"))doAnswer(call->jsonObservation(f.oldFlow(),oldCancelled.get()?"canceled":"requires_capture",op.beforeAmount(),Map.of(),oldCancelled.get()?"0":received))
            .when(gateway).observeOrderChangeFlow(f.oldFlow());
        int reservedStock=jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product());
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select total_amount from orders where id=?",Integer.class,f.order())).isEqualTo(op.afterAmount());
        assertThat(jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select active_payment_id from orders where id=?",Long.class,f.order())).isEqualTo(jdbc.queryForObject("select new_payment_id from order_change_operations where id=?",Long.class,op.id()));
        assertThat(jdbc.queryForObject("select state from inventory_reservations where operation_id=?",String.class,op.id())).isEqualTo("CONSUMED");
        assertThat(service.reconcile(f.order(),op.id()).state()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product())).isEqualTo(reservedStock);
        assertThat(jdbc.queryForObject("select count(*) from order_content_change_histories where order_id=?",Integer.class,f.order())).isEqualTo(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"NEW,NULL","NEW,MISSING","NEW,849","NEW,851","OLD,NULL","OLD,MISSING","OLD,749","OLD,751","COMMIT,NULL","COMMIT,MISSING","COMMIT,849","COMMIT,851"})
    void incompleteCapturableEvidenceNeverPermitsDecisionOrSwitch(String stage,String capturable){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);
        var metadata=Map.<String,Object>of("order_id",Long.toString(f.order()),"order_change_operation_id",op.id().toString());
        var invalid=jsonObservation(stage.equals("OLD")?f.oldFlow():newFlow,"requires_capture",stage.equals("OLD")?op.beforeAmount():op.afterAmount(),stage.equals("OLD")?Map.of():metadata,"0",capturable);
        if(stage.equals("OLD"))doReturn(invalid).when(gateway).observeOrderChangeFlow(f.oldFlow());
        else if(stage.equals("COMMIT"))doReturn(observation(newFlow,"requires_capture",op.afterAmount(),metadata),invalid).when(gateway).observeOrderChangeFlow(newFlow);
        else doReturn(invalid).when(gateway).observeOrderChangeFlow(newFlow);
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("MANUAL_REVIEW");
        assertThat(oldCancelled.get()).isEqualTo(stage.equals("COMMIT"));
        assertThat(jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order())).isZero();
        assertThat(jdbc.queryForObject("select active_payment_id from orders where id=?",Long.class,f.order())).isEqualTo(f.oldPayment());
        assertThat(jdbc.queryForObject("select state from inventory_reservations where operation_id=?",String.class,op.id())).isEqualTo("HELD");
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"NEW","OLD"})
    void swappedOldAndNewFlowIdentityNeverPermitsCancel(String role){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);
        var metadata=Map.<String,Object>of("order_id",Long.toString(f.order()),"order_change_operation_id",op.id().toString());
        if(role.equals("NEW"))doReturn(observation(f.oldFlow(),"requires_capture",op.afterAmount(),metadata)).when(gateway).observeOrderChangeFlow(newFlow);
        else doReturn(observation(newFlow,"requires_capture",op.beforeAmount(),Map.of())).when(gateway).observeOrderChangeFlow(f.oldFlow());
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("MANUAL_REVIEW");
        assertThat(oldCancelled).isFalse();verify(gateway,times(1)).sendOrderChangeRequest(any());
        assertThat(jdbc.queryForObject("select switch_decided_at from order_change_operations where id=?",java.time.LocalDateTime.class,op.id())).isNull();
    }
    @Test void canceledMissingReceivedAfterUnknownCancelNeverSwitchesOrAutomaticallyReposts(){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);var cancelPosts=new AtomicInteger();
        doAnswer(call->{var saved=((OrderChangeRequestSender.Dispatch)call.getArgument(0)).request();
            if(saved.httpRequest().kind().equals("OLD_CANCEL")){cancelPosts.incrementAndGet();oldCancelled.set(true);throw new PaymentGatewayException(PaymentGatewayErrorCategory.TRANSPORT_RESULT_UNKNOWN,null,null,true);}
            return transport.answer(call);
        }).when(gateway).sendOrderChangeRequest(any());
        doAnswer(call->oldCancelled.get()?jsonObservation(f.oldFlow(),"canceled",op.beforeAmount(),Map.of(),"MISSING"):observation(f.oldFlow(),"requires_capture",op.beforeAmount(),Map.of()))
            .when(gateway).observeOrderChangeFlow(f.oldFlow());
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("MANUAL_REVIEW");
        assertThat(cancelPosts).hasValue(1);
        assertThat(jdbc.queryForObject("select switch_decided_at from order_change_operations where id=?",java.time.LocalDateTime.class,op.id())).isNotNull();
        assertThat(jdbc.queryForObject("select status from payments where id=?",String.class,f.oldPayment())).isEqualTo("AUTHORIZED");
        assertThat(jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order())).isZero();
        assertThat(jdbc.queryForObject("select state from inventory_reservations where operation_id=?",String.class,op.id())).isEqualTo("HELD");
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("MANUAL_REVIEW");
        assertThat(cancelPosts).hasValue(1);
        verify(gateway,times(2)).sendOrderChangeRequest(any()); // CREATE + one Cancel, no automatic or stopped-state retry.
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"0,0","NULL,FULL","NULL,0","0,FULL"})
    void oldCancellationObservedShapesConvergeWithoutReposting(String received,String capturable){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);var posts=new AtomicInteger();
        doAnswer(call->{var saved=((OrderChangeRequestSender.Dispatch)call.getArgument(0)).request();
            if(saved.httpRequest().kind().equals("OLD_CANCEL")){posts.incrementAndGet();oldCancelled.set(true);
                throw new PaymentGatewayException(PaymentGatewayErrorCategory.TRANSPORT_RESULT_UNKNOWN,null,null,true);}
            return transport.answer(call);
        }).when(gateway).sendOrderChangeRequest(any());
        doAnswer(call->oldCancelled.get()?jsonObservation(f.oldFlow(),"canceled",op.beforeAmount(),Map.of(),received,
            capturable.equals("FULL")?Integer.toString(op.beforeAmount()):capturable):observation(f.oldFlow(),"requires_capture",op.beforeAmount(),Map.of()))
            .when(gateway).observeOrderChangeFlow(f.oldFlow());
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("COMPLETED");
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("COMPLETED");
        assertThat(posts).hasValue(1);
        assertThat(jdbc.queryForObject("select status from payments where id=?",String.class,f.oldPayment())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product())).isEqualTo(19);
        assertThat(jdbc.queryForObject("select state from inventory_reservations where operation_id=?",String.class,op.id())).isEqualTo("CONSUMED");
        assertThat(jdbc.queryForObject("select count(*) from order_content_change_histories where order_id=?",Integer.class,f.order())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select active_payment_id from orders where id=?",Long.class,f.order())).isNotEqualTo(f.oldPayment());
    }
    record RecoveryFixture(Fixture base,OrderChangeStore.Operation op){}
    RecoveryFixture recoveryFixture(){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);
        doAnswer(call->oldCancelled.get()?jsonObservation(f.oldFlow(),"canceled",op.beforeAmount(),Map.of(),"MISSING")
            :observation(f.oldFlow(),"requires_capture",op.beforeAmount(),Map.of())).when(gateway).observeOrderChangeFlow(f.oldFlow());
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("MANUAL_REVIEW");
        doReturn(jsonObservation(f.oldFlow(),"canceled",op.beforeAmount(),Map.of(),"NULL",Integer.toString(op.beforeAmount())))
            .when(gateway).observeOrderChangeFlow(f.oldFlow());
        clearInvocations(gateway);admin();return new RecoveryFixture(f,op);
    }
    OldCancelRecovery.Command recoveryCommand(RecoveryFixture f){return new OldCancelRecovery.Command(f.base().order(),f.op().id(),UUID.randomUUID(),version(f.op().id()),"VERIFY_OLD_CANCEL");}
    @Test void explicitRecoveryCompletesWithoutAnyFinancialPostAndIsIdempotent(){
        var f=recoveryFixture();var command=recoveryCommand(f);
        assertThat(recovery.recover(command)).isEqualTo(OldCancelRecovery.Result.COMPLETED);
        assertThat(recovery.recover(command)).isEqualTo(OldCancelRecovery.Result.COMPLETED);
        assertThat(state(f.op().id())).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select stock from products where id=?",Integer.class,f.base().product())).isEqualTo(19);
        assertThat(jdbc.queryForObject("select count(*) from order_content_change_histories where order_id=?",Integer.class,f.base().order())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from order_change_recovery_audits where operation_id=?",Integer.class,f.op().id())).isEqualTo(1);
        verify(gateway,never()).sendOrderChangeRequest(any());verify(gateway,never()).capture(any());verify(gateway,never()).cancelAuthorization(any());
    }
    @Test void explicitRecoveryRejectsWrongVersionAndUnauthorizedActor(){
        var f=recoveryFixture();var c=recoveryCommand(f);
        assertThatThrownBy(()->recovery.recover(new OldCancelRecovery.Command(c.orderId(),c.operationId(),c.requestId(),c.expectedVersion()+1,c.reason()))).isInstanceOf(IllegalStateException.class);
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        assertThatThrownBy(()->recovery.recover(c)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(gateway);assertHeldOriginal(f.base(),f.op());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"reason","state","oldPayment","newPayment","oldAuth","newAuth","cancel","dispatch","missingDispatch","reservation","revision","active","shipping","capture","newAbort"})
    void explicitRecoveryRejectsIneligibleSavedTargets(String field){
        var f=recoveryFixture();var c=recoveryCommand(f);long next=jdbc.queryForObject("select new_payment_id from order_change_operations where id=?",Long.class,c.operationId());
        switch(field){
            case "reason"->jdbc.update("update order_change_operations set financial_review_reason='OTHER_REASON' where id=?",c.operationId());
            case "state"->jdbc.update("update order_change_operations set state='OLD_CANCEL_UNKNOWN' where id=?",c.operationId());
            case "oldPayment"->jdbc.update("update payments set status='CANCELLED' where id=?",f.base().oldPayment());
            case "newPayment"->jdbc.update("update payments set status='REQUIRES_ACTION' where id=?",next);
            case "oldAuth"->jdbc.update("update payment_transactions set status='FAILED' where payment_id=? and transaction_type='AUTHORIZE'",f.base().oldPayment());
            case "newAuth"->jdbc.update("update payment_transactions set status='PENDING' where payment_id=? and transaction_type='AUTHORIZE'",next);
            case "cancel"->jdbc.update("update payment_transactions set status='FAILED' where order_change_operation_id=? and transaction_type='CANCEL'",c.operationId());
            case "dispatch"->jdbc.update("update order_change_request_dispatches set state='OBSERVED',observed_at=clock_timestamp() where request_id=(select id from order_change_financial_requests where operation_id=? and request_kind='OLD_CANCEL')",c.operationId());
            case "missingDispatch"->{long id=jdbc.queryForObject("select id from order_change_financial_requests where operation_id=? and request_kind='OLD_CANCEL'",Long.class,c.operationId());requestedDispatchFixtureMutation("order_change_request_dispatches","trg_change_dispatch_progress","delete from order_change_request_dispatches where request_id=?",id);}
            case "reservation"->jdbc.update("update inventory_reservations set state='CONSUMED',consumed_at=localtimestamp where operation_id=?",c.operationId());
            case "revision"->jdbc.update("update orders set content_revision=content_revision+1 where id=?",c.orderId());
            case "active"->jdbc.update("update orders set active_payment_id=? where id=?",next,c.orderId());
            case "shipping"->jdbc.update("update orders set shipping_preparation_started_at=localtimestamp,shipping_preparation_started_by_account_id=9,shipping_preparation_started_by_username='shipper' where id=?",c.orderId());
            case "capture"->tx(()->{transactions.saveAndFlush(new PaymentTransaction(payments.findById(f.base().oldPayment()).orElseThrow(),PaymentTransactionType.CAPTURE,f.op().beforeAmount(),0,UUID.randomUUID().toString(),orders.findCurrentTimeInTokyo(),PaymentTransactionPurpose.SHIPMENT_CAPTURE));return null;});
            case "newAbort"->tx(()->{transactions.saveAndFlush(PaymentTransaction.forOrderChange(payments.findById(next).orElseThrow(),PaymentTransactionType.CANCEL,f.op().afterAmount(),0,UUID.randomUUID().toString(),orders.findCurrentTimeInTokyo(),PaymentTransactionPurpose.ORDER_CHANGE_NEW_AUTH_ABORT,c.operationId()));return null;});
        }
        assertThatThrownBy(()->recovery.recover(c)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(gateway);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"timeout","500","disconnect","oldEvidence","newExpired"})
    void explicitRecoveryStopsOnUnconfirmedEvidence(String failure){
        var f=recoveryFixture();var c=recoveryCommand(f);
        if(List.of("timeout","500","disconnect").contains(failure))doThrow(new PaymentGatewayException(
            failure.equals("500")?PaymentGatewayErrorCategory.SERVER_RESULT_UNKNOWN:PaymentGatewayErrorCategory.TRANSPORT_RESULT_UNKNOWN,
            failure.equals("500")?500:null,null,true)).when(gateway).observeOrderChangeFlow(f.base().oldFlow());
        else if(failure.equals("oldEvidence"))doReturn(jsonObservation(f.base().oldFlow(),"canceled",f.op().beforeAmount(),Map.of(),"1")).when(gateway).observeOrderChangeFlow(f.base().oldFlow());
        else newState.set("canceled");
        assertThat(recovery.recover(c)).isEqualTo(OldCancelRecovery.Result.HOLD);assertHeldOriginal(f.base(),f.op());
        assertThat(state(c.operationId())).isEqualTo("MANUAL_REVIEW");verify(gateway,never()).sendOrderChangeRequest(any());
    }
    void installRecoveryFailure(String name,UUID operation,String status){
        jdbc.execute("create function "+name+"() returns trigger language plpgsql as $$ begin if NEW.operation_id='"+operation+"'::uuid and NEW.result_code='"+status+"' then raise exception 'Synthetic recovery audit failure'; end if; return NEW; end; $$");
        jdbc.execute("create trigger "+name+" before update on order_change_recovery_audits for each row execute function "+name+"()");
    }
    void removeRecoveryFailure(String name){jdbc.execute("drop trigger "+name+" on order_change_recovery_audits");jdbc.execute("drop function "+name+"()");}
    @Test void recoveryAuditFailureRollsBackEveryCancellationMutation(){
        var f=recoveryFixture();var c=recoveryCommand(f);var before=preservedBusiness(f.base(),f.op());var name="recovery_apply_"+c.orderId();
        installRecoveryFailure(name,c.operationId(),"COMMIT_PENDING");
        try{
            assertThat(recovery.recover(c)).isEqualTo(OldCancelRecovery.Result.HOLD);
            assertThat(preservedBusiness(f.base(),f.op())).isEqualTo(before);assertThat(state(c.operationId())).isEqualTo("MANUAL_REVIEW");
        }finally{removeRecoveryFailure(name);}
    }
    @Test void recoveryCompletionFailureKeepsCommitPendingAndExplicitRetryCompletes(){
        var f=recoveryFixture();var c=recoveryCommand(f);var name="recovery_complete_"+c.orderId();installRecoveryFailure(name,c.operationId(),"COMPLETED");
        try{
            assertThat(recovery.recover(c)).isEqualTo(OldCancelRecovery.Result.COMMIT_PENDING);
            assertThat(state(c.operationId())).isEqualTo("COMMIT_PENDING");
            assertThat(service.reconcile(c.orderId(),c.operationId()).state()).isEqualTo("COMMIT_PENDING");
            assertThat(service.advance(c.orderId(),c.operationId()).state()).isEqualTo("COMMIT_PENDING");
            webhook.synchronize("payment_flow.canceled",f.base().oldFlow());
            assertThat(state(c.operationId())).isEqualTo("COMMIT_PENDING");
            assertThat(jdbc.queryForObject("select count(*) from order_content_change_histories where order_id=?",Integer.class,c.orderId())).isZero();
            assertThat(jdbc.queryForObject("select state from inventory_reservations where operation_id=?",String.class,c.operationId())).isEqualTo("HELD");
            assertThatThrownBy(()->shippingPreparation.start(c.orderId())).isInstanceOf(RuntimeException.class);
        }finally{removeRecoveryFailure(name);}
        assertThat(recovery.recover(c)).isEqualTo(OldCancelRecovery.Result.COMPLETED);verify(gateway,never()).sendOrderChangeRequest(any());
    }
    @Test void lostCancellationCommitAcknowledgementIsReadBackWithoutReplaying(){
        var f=recoveryFixture();var c=recoveryCommand(f);
        var delegate=mock(OrderReauthorizationTransactions.class,org.mockito.AdditionalAnswers.delegatesTo(resultTransactions));
        doAnswer(call->{resultTransactions.applyRecoveredOldCancellation(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3));throw new IllegalStateException("Synthetic lost commit acknowledgement");})
            .when(delegate).applyRecoveredOldCancellation(any(),any(),any(),any());
        var uncertain=new OldCancelRecoveryService(delegate,recoveryAudits,gateway,clock);
        assertThat(uncertain.recover(c)).isEqualTo(OldCancelRecovery.Result.COMMIT_PENDING);
        assertThat(recovery.recover(c)).isEqualTo(OldCancelRecovery.Result.COMPLETED);verify(gateway,never()).sendOrderChangeRequest(any());
    }
    @Test void recoveredOperationCanConvergeAfterDeadlineAndOnlyNewPaymentCanCapture(){
        var f=recoveryFixture();var c=recoveryCommand(f);doReturn(f.op().graceUntil().plusHours(1)).when(orders).findCurrentTimeInTokyo();
        doReturn(f.op().graceUntil().plusHours(1).atZone(java.time.ZoneId.of("Asia/Tokyo")).toInstant()).when(clock).instant();
        assertThatThrownBy(()->shippingPreparation.start(c.orderId())).isInstanceOf(RuntimeException.class);
        assertThat(recovery.recover(c)).isEqualTo(OldCancelRecovery.Result.COMPLETED);
        shippingPreparation.start(c.orderId());when(gateway.capture(any())).thenAnswer(call->{var request=(com.example.ecsite.payment.capture.CaptureHttpRequest)call.getArgument(0);assertThat(request.flowId()).isEqualTo(newFlow);return new CaptureResult(CaptureResultStatus.PENDING,newFlow,null,null);});
        assertThat(capture.captureForShipment(c.orderId(),9L,"shipper",null).status()).isEqualTo(CaptureResultStatus.PENDING);
        verify(gateway,never()).sendOrderChangeRequest(any());
    }
    @Test void reusedRecoveryUuidWithDifferentContentIsRejected(){
        var f=recoveryFixture();var c=recoveryCommand(f);assertThat(recovery.recover(c)).isEqualTo(OldCancelRecovery.Result.COMPLETED);
        clearInvocations(gateway);
        assertThatThrownBy(()->recovery.recover(new OldCancelRecovery.Command(c.orderId(),c.operationId(),c.requestId(),c.expectedVersion()+1,c.reason()))).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(gateway);
    }
    @Test void concurrentExplicitRecoveriesNeverDuplicateCompletion()throws Exception{
        var f=recoveryFixture();var c=recoveryCommand(f);var entered=new java.util.concurrent.CountDownLatch(2);
        doAnswer(call->{entered.countDown();if(!entered.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Recovery GET barrier timed out");return jsonObservation(f.base().oldFlow(),"canceled",f.op().beforeAmount(),Map.of(),"NULL",Integer.toString(f.op().beforeAmount()));}).when(gateway).observeOrderChangeFlow(f.base().oldFlow());
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            java.util.concurrent.Callable<OldCancelRecovery.Result> task=()->{admin();try{return recovery.recover(c);}finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}};
            var first=pool.submit(task);var second=pool.submit(task);
            assertThat(List.of(first.get(30,java.util.concurrent.TimeUnit.SECONDS),second.get(30,java.util.concurrent.TimeUnit.SECONDS))).contains(OldCancelRecovery.Result.COMPLETED);
            assertThat(jdbc.queryForObject("select count(*) from order_content_change_histories where order_id=?",Integer.class,c.orderId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("select stock from products where id=?",Integer.class,f.base().product())).isEqualTo(19);
            verify(gateway,never()).sendOrderChangeRequest(any());
        }finally{pool.shutdownNow();}
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"oldFlow","newFlow","newAmount","newMetadata","newExpiry","connection"})
    void explicitRecoveryRejectsMismatchedFlowBindings(String field){
        var f=recoveryFixture();var c=recoveryCommand(f);var metadata=Map.<String,Object>of("order_id",Long.toString(c.orderId()),"order_change_operation_id",c.operationId().toString());
        switch(field){
            case "oldFlow"->doReturn(jsonObservation("pfw_other","canceled",f.op().beforeAmount(),Map.of(),"NULL",Integer.toString(f.op().beforeAmount()))).when(gateway).observeOrderChangeFlow(f.base().oldFlow());
            case "newFlow"->doReturn(observation("pfw_other","requires_capture",f.op().afterAmount(),metadata)).when(gateway).observeOrderChangeFlow(newFlow);
            case "newAmount"->doReturn(observation(newFlow,"requires_capture",f.op().afterAmount()+1,metadata)).when(gateway).observeOrderChangeFlow(newFlow);
            case "newMetadata"->doReturn(observation(newFlow,"requires_capture",f.op().afterAmount(),Map.of())).when(gateway).observeOrderChangeFlow(newFlow);
            case "newExpiry"->doReturn(new PayJpPaymentFlowObservation(newFlow,"requires_capture",(long)f.op().afterAmount(),"jpy",0L,(long)f.op().afterAmount(),"manual",false,java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1),null,metadata)).when(gateway).observeOrderChangeFlow(newFlow);
            case "connection"->{var mapper=tools.jackson.databind.json.JsonMapper.builder().build();var raw=mapper.readTree("{\"id\":\""+f.base().oldFlow()+"\",\"status\":\"canceled\",\"amount\":"+f.op().beforeAmount()+",\"currency\":\"jpy\",\"capture_method\":\"manual\",\"livemode\":false,\"amount_received\":null,\"amount_capturable\":0}");
                var wrong=jsonObservation(f.base().oldFlow(),"canceled",f.op().beforeAmount(),Map.of(),"NULL").withOldCancellationEvidence(com.example.ecsite.payment.payjp.OldAuthorizationCancellationEvidence.fromJson(raw,new com.example.ecsite.payment.capture.CaptureConnectionIdentity("other","test-account","TEST","https://api.pay.jp")));
                doReturn(wrong).when(gateway).observeOrderChangeFlow(f.base().oldFlow());}
        }
        assertThat(recovery.recover(c)).isEqualTo(OldCancelRecovery.Result.HOLD);assertHeldOriginal(f.base(),f.op());verify(gateway,never()).sendOrderChangeRequest(any());
    }
    @Test void recoveryRejectsVersionChangedDuringGet(){
        var f=recoveryFixture();var c=recoveryCommand(f);
        doAnswer(call->{jdbc.update("update order_change_operations set version=version+1 where id=?",c.operationId());return jsonObservation(f.base().oldFlow(),"canceled",f.op().beforeAmount(),Map.of(),"NULL");}).when(gateway).observeOrderChangeFlow(f.base().oldFlow());
        assertThat(recovery.recover(c)).isEqualTo(OldCancelRecovery.Result.CONFLICT);assertHeldOriginal(f.base(),f.op());verify(gateway,never()).sendOrderChangeRequest(any());
    }
    @Test void recoveryRejectsMonotonicallyStaleGetEvidence(){
        var f=recoveryFixture();var c=recoveryCommand(f);var nanos=new AtomicLong();
        var slow=new OldCancelRecoveryService(resultTransactions,recoveryAudits,gateway,clock,()->nanos.addAndGet(3_000_000_000L));
        assertThat(slow.recover(c)).isEqualTo(OldCancelRecovery.Result.HOLD);assertHeldOriginal(f.base(),f.op());verify(gateway,never()).sendOrderChangeRequest(any());
    }
    @Test void recoveryStartAuditRollbackPreventsAllGets(){
        var f=recoveryFixture();var c=recoveryCommand(f);var name="recovery_start_"+c.orderId();installRecoveryFailure(name,c.operationId(),"STARTED");
        jdbc.execute("drop trigger "+name+" on order_change_recovery_audits");jdbc.execute("create trigger "+name+" before insert on order_change_recovery_audits for each row execute function "+name+"()");
        try{
            assertThatThrownBy(()->recovery.recover(c)).isInstanceOf(RuntimeException.class);verifyNoInteractions(gateway);assertHeldOriginal(f.base(),f.op());
            assertThat(jdbc.queryForObject("select count(*) from order_change_recovery_audits where operation_id=?",Integer.class,c.operationId())).isZero();
        }finally{removeRecoveryFailure(name);}
    }
    @Test void lostAuditStartCommitAcknowledgementNeverStartsGetUntilExplicitRetry(){
        var f=recoveryFixture();var c=recoveryCommand(f);var delegate=mock(OldCancelRecoveryAuditRepository.class,org.mockito.AdditionalAnswers.delegatesTo(recoveryAudits));
        doAnswer(call->{recoveryAudits.start(call.getArgument(0),call.getArgument(1));throw new IllegalStateException("Synthetic audit commit acknowledgement loss");}).when(delegate).start(any(),any());
        var uncertain=new OldCancelRecoveryService(resultTransactions,delegate,gateway,clock);
        assertThatThrownBy(()->uncertain.recover(c)).isInstanceOf(IllegalStateException.class);verifyNoInteractions(gateway);
        assertThat(recovery.recover(c)).isEqualTo(OldCancelRecovery.Result.COMPLETED);
        assertThat(jdbc.queryForObject("select count(*) from order_change_recovery_audits where operation_id=?",Integer.class,c.operationId())).isEqualTo(1);
    }
    @Test void ordinaryPathsDoNotReopenTheRecoveryTarget(){
        var f=recoveryFixture();var c=recoveryCommand(f);
        assertThat(service.advance(c.orderId(),c.operationId()).state()).isEqualTo("MANUAL_REVIEW");
        assertThat(service.reconcile(c.orderId(),c.operationId()).state()).isEqualTo("MANUAL_REVIEW");
        webhook.synchronize("payment_flow.canceled",f.base().oldFlow());verifyNoInteractions(gateway);assertHeldOriginal(f.base(),f.op());
        assertThatThrownBy(()->capture.captureForShipment(c.orderId(),9L,"shipper",null)).isInstanceOf(RuntimeException.class);verify(gateway,never()).capture(any());
    }
    @Test void v68AuditIdentityAndTerminalOutcomesCannotBeRewrittenOrDeleted(){
        var f=recoveryFixture();var c=recoveryCommand(f);assertThat(recovery.recover(c)).isEqualTo(OldCancelRecovery.Result.COMPLETED);
        assertThatThrownBy(()->jdbc.update("update order_change_recovery_audits set actor_username='other' where request_id=?",c.requestId())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("delete from order_change_recovery_audits where request_id=?",c.requestId())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("update order_change_recovery_audits set result_code='COMMIT_PENDING',completed_version=null,content_change_history_id=null where request_id=?",c.requestId())).isInstanceOf(RuntimeException.class);
        assertThat(recovery.recover(c)).isEqualTo(OldCancelRecovery.Result.COMPLETED);
    }

    record AuditFixture(RecoveryFixture recovery, OldCancelRecovery.Command command){}
    AuditFixture v68StartedAudit(){
        var f=recoveryFixture();var c=recoveryCommand(f);var actor=new OldCancelRecovery.Actor(9L,"shipper");
        recoveryAudits.start(resultTransactions.recoverySnapshot(c,false,actor),actor);
        return new AuditFixture(f,c);
    }
    long v68History(){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("COMPLETED");
        long history=jdbc.queryForObject("select content_change_history_id from order_change_operations where id=?",Long.class,op.id());
        clearInvocations(gateway);return history;
    }
    void v68State(UUID id,String state,Long history){
        boolean applied=List.of("COMMIT_PENDING","COMPLETED").contains(state);
        jdbc.update("""
            update order_change_recovery_audits set result_code=?,
              applied_version=case when ? then expected_version+1 else null end,
              completed_version=case when ? then expected_version+2 else null end,
              evidence_digest=?,content_change_history_id=?,updated_at=clock_timestamp(),
              finished_at=case when ? then null else clock_timestamp() end where request_id=?
            """,state,applied,state.equals("COMPLETED"),applied?new byte[32]:null,
            state.equals("COMPLETED")?history:null,state.equals("STARTED"),id);
    }
    void v68SqlRejected(Runnable action,String sqlState){
        assertThatThrownBy(action::run).isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class,
            e->assertThat(((java.sql.SQLException)e.getMostSpecificCause()).getSQLState()).isEqualTo(sqlState));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"HOLD","CONFLICT","COMMIT_PENDING","COMPLETED"})
    void v68OnlyStartedMayBeInserted(String target){
        var f=v68StartedAudit();long history=target.equals("COMPLETED")?v68History():0;
        v68SqlRejected(()->jdbc.update("""
            insert into order_change_recovery_audits
              (request_id,order_id,operation_id,old_cancel_request_id,actor_id,actor_username,reason_code,original_reason,
               expected_version,old_flow_id,new_flow_id,context_digest,result_code,applied_version,completed_version,
               evidence_digest,content_change_history_id,started_at,updated_at,finished_at)
            select ?,order_id,operation_id,old_cancel_request_id,actor_id,actor_username,reason_code,original_reason,
               expected_version,old_flow_id,new_flow_id,context_digest,?,
               case when ? then expected_version+1 else null end,
               case when ? then expected_version+2 else null end,?,?,started_at,clock_timestamp(),clock_timestamp()
            from order_change_recovery_audits where request_id=?
            """,UUID.randomUUID(),target,List.of("COMMIT_PENDING","COMPLETED").contains(target),target.equals("COMPLETED"),
            List.of("COMMIT_PENDING","COMPLETED").contains(target)?new byte[32]:null,target.equals("COMPLETED")?history:null,
            f.command().requestId()),"P0001");
        verifyNoInteractions(gateway);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "STARTED,STARTED,true","STARTED,HOLD,true","STARTED,CONFLICT,true","STARTED,COMMIT_PENDING,true","STARTED,COMPLETED,false",
        "HOLD,STARTED,false","HOLD,HOLD,true","HOLD,CONFLICT,true","HOLD,COMMIT_PENDING,true","HOLD,COMPLETED,false",
        "CONFLICT,STARTED,false","CONFLICT,HOLD,false","CONFLICT,CONFLICT,true","CONFLICT,COMMIT_PENDING,false","CONFLICT,COMPLETED,false",
        "COMMIT_PENDING,STARTED,false","COMMIT_PENDING,HOLD,false","COMMIT_PENDING,CONFLICT,false","COMMIT_PENDING,COMMIT_PENDING,true","COMMIT_PENDING,COMPLETED,true",
        "COMPLETED,STARTED,false","COMPLETED,HOLD,false","COMPLETED,CONFLICT,false","COMPLETED,COMMIT_PENDING,false","COMPLETED,COMPLETED,true"})
    void v68AuditTransitionTable(String from,String to,boolean allowed){
        var f=v68StartedAudit();var before=preservedBusiness(f.recovery().base(),f.recovery().op());
        Long history=from.equals("COMPLETED")||to.equals("COMPLETED")?v68History():null;
        if(!from.equals("STARTED")){
            if(from.equals("COMPLETED"))v68State(f.command().requestId(),"COMMIT_PENDING",null);
            v68State(f.command().requestId(),from,history);
        }
        Runnable update=from.equals(to)?()->jdbc.update("update order_change_recovery_audits set result_code=result_code where request_id=?",f.command().requestId())
            :()->v68State(f.command().requestId(),to,history);
        if(allowed){update.run();assertThat(recoveryAudits.find(f.command().requestId()).orElseThrow().get("result_code")).isEqualTo(to);}
        else v68SqlRejected(update,"P0001");
        assertThat(preservedBusiness(f.recovery().base(),f.recovery().op())).isEqualTo(before);
        clearInvocations(gateway);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"commit-null","completed-null","completed-both-null","completed-applied-null","expected-null"})
    void v68RejectsNullVersionsOnUpdate(String kind){
        var f=v68StartedAudit();Long history=kind.startsWith("completed")?v68History():null;
        if(kind.startsWith("completed"))v68State(f.command().requestId(),"COMMIT_PENDING",null);
        String assignments=switch(kind){
            case "commit-null"->"result_code='COMMIT_PENDING',applied_version=null,evidence_digest=decode(repeat('00',32),'hex'),finished_at=clock_timestamp()";
            case "completed-null"->"result_code='COMPLETED',completed_version=null,content_change_history_id="+history;
            case "completed-both-null"->"result_code='COMPLETED',applied_version=null,completed_version=null,content_change_history_id="+history;
            case "completed-applied-null"->"result_code='COMPLETED',applied_version=null,completed_version=expected_version+2,content_change_history_id="+history;
            default->"expected_version=null";
        };
        String state=List.of("commit-null","completed-null").contains(kind)?"23514":"P0001";
        v68SqlRejected(()->jdbc.update("update order_change_recovery_audits set "+assignments+" where request_id=?",f.command().requestId()),state);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"COMMIT_PENDING,false,false","COMPLETED,false,true","COMPLETED,true,false","COMPLETED,false,false"})
    void v68RejectsNullVersionsOnInsert(String target,boolean applied,boolean completed){
        var f=v68StartedAudit();Long history=target.equals("COMPLETED")?v68History():null;
        v68SqlRejected(()->jdbc.update("""
            insert into order_change_recovery_audits
              (request_id,order_id,operation_id,old_cancel_request_id,actor_id,actor_username,reason_code,original_reason,
               expected_version,old_flow_id,new_flow_id,context_digest,result_code,applied_version,completed_version,
               evidence_digest,content_change_history_id,started_at,updated_at,finished_at)
            select ?,order_id,operation_id,old_cancel_request_id,actor_id,actor_username,reason_code,original_reason,
               expected_version,old_flow_id,new_flow_id,context_digest,?,
               case when ? then expected_version+1 else null end,
               case when ? then expected_version+2 else null end,?,?,started_at,clock_timestamp(),clock_timestamp()
            from order_change_recovery_audits where request_id=?
            """,UUID.randomUUID(),target,applied,completed,new byte[32],history,f.command().requestId()),"P0001");
    }
    @Test void v68RepositoryPermittedRetriesAndConflictFreeze(){
        var f=v68StartedAudit();var c=f.command();var actor=new OldCancelRecovery.Actor(9L,"shipper");
        var snapshot=resultTransactions.recoverySnapshot(c,false,actor);
        recoveryAudits.start(snapshot,actor);recoveryAudits.hold(c.requestId());recoveryAudits.hold(c.requestId());
        recoveryAudits.start(snapshot,actor);recoveryAudits.conflict(c.requestId());var frozen=jdbc.queryForObject("select to_jsonb(a)::text from order_change_recovery_audits a where request_id=?",String.class,c.requestId());
        recoveryAudits.hold(c.requestId());recoveryAudits.conflict(c.requestId());recoveryAudits.start(snapshot,actor);
        assertThat(jdbc.queryForObject("select to_jsonb(a)::text from order_change_recovery_audits a where request_id=?",String.class,c.requestId())).isEqualTo(frozen);
        v68SqlRejected(()->jdbc.update("update order_change_recovery_audits set finished_at=finished_at+interval '1 second' where request_id=?",c.requestId()),"P0001");
        verifyNoInteractions(gateway);
    }


    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"request_id","order_id","operation_id","old_cancel_request_id","actor_id","actor_username","expected_version","started_at"})
    void v68AuditIdentifiersRemainImmutableBeforeOutcome(String field){
        var f=v68StartedAudit();
        String value=switch(field){
            case "request_id","operation_id"->"'"+UUID.randomUUID()+"'::uuid";
            case "actor_username"->"'different-admin'";
            case "started_at"->"started_at+interval '1 second'";
            default->field+"+1";
        };
        v68SqlRejected(()->jdbc.update("update order_change_recovery_audits set "+field+"="+value+" where request_id=?",f.command().requestId()),"P0001");
        v68SqlRejected(()->jdbc.update("delete from order_change_recovery_audits where request_id=?",f.command().requestId()),"P0001");
    }

    @Test void recoveryReadbackToleratesConcurrentCompletionAfterAuditWasRead(){
        var f=recoveryFixture();var c=recoveryCommand(f);
        var uncertainResults=mock(OrderReauthorizationTransactions.class,org.mockito.AdditionalAnswers.delegatesTo(resultTransactions));
        doAnswer(call->{resultTransactions.applyRecoveredOldCancellation(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3));throw new IllegalStateException("Synthetic commit acknowledgement loss");})
            .when(uncertainResults).applyRecoveredOldCancellation(any(),any(),any(),any());
        var uncertainAudits=mock(OldCancelRecoveryAuditRepository.class,org.mockito.AdditionalAnswers.delegatesTo(recoveryAudits));var raced=new AtomicBoolean();
        doAnswer(call->{var row=recoveryAudits.find(c.requestId());
            if(row.isPresent()&&"COMMIT_PENDING".equals(row.get().get("result_code"))&&raced.compareAndSet(false,true))
                assertThat(recovery.recover(c)).isEqualTo(OldCancelRecovery.Result.COMPLETED);
            return row;
        }).when(uncertainAudits).find(c.requestId());
        var uncertain=new OldCancelRecoveryService(uncertainResults,uncertainAudits,gateway,clock);
        assertThat(uncertain.recover(c)).isEqualTo(OldCancelRecovery.Result.COMPLETED);assertThat(raced).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from order_content_change_histories where order_id=?",Integer.class,c.orderId())).isEqualTo(1);
        verify(gateway,never()).sendOrderChangeRequest(any());
    }
    String recoveryUrl(RecoveryFixture f){return "/admin/orders/"+f.base().order()+"/changes/"+f.op().id()+"/old-cancel-recovery";}
    org.springframework.test.web.servlet.request.RequestPostProcessor recoveryAdmin(){
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(
            new com.example.ecsite.security.AdminUserDetails(9L,"shipper","unused",true,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
    @Test void adminRecoveryPageIsReadOnlyAndPostIsIdempotent()throws Exception{
        var f=recoveryFixture();var session=new org.springframework.mock.web.MockHttpSession();var before=preservedBusiness(f.base(),f.op());
        var response=adminMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(recoveryUrl(f)).with(recoveryAdmin()).session(session))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.view().name("admin/orders/old-cancel-recovery")).andReturn();
        var command=(OldCancelRecovery.Command)response.getModelAndView().getModel().get("recoveryCommand");
        assertThat(command).isNotNull();verifyNoInteractions(gateway);assertThat(preservedBusiness(f.base(),f.op())).isEqualTo(before);
        for(int i=0;i<2;i++)adminMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(recoveryUrl(f)).with(recoveryAdmin())
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).session(session)
            .param("requestId",command.requestId().toString()).param("expectedVersion",Long.toString(command.expectedVersion())).param("reason",command.reason())
            .param("actorId","99999").param("actorUsername","forged"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isFound());
        assertThat(state(f.op().id())).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select actor_username from order_change_recovery_audits where request_id=?",String.class,command.requestId())).isEqualTo("shipper");
        assertThat(jdbc.queryForObject("select count(*) from order_content_change_histories where order_id=?",Integer.class,f.base().order())).isEqualTo(1);
        verify(gateway,never()).sendOrderChangeRequest(any());
    }
    @Test void adminRecoveryLinkAndAuditDisplayUseSavedDataOnly()throws Exception{
        var f=recoveryFixture();var url=recoveryUrl(f);var session=new org.springframework.mock.web.MockHttpSession();
        var detail=adminMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/admin/orders/"+f.base().order()).with(recoveryAdmin())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        assertThat(detail.getResponse().getContentAsString()).contains(url);
        var page=adminMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).with(recoveryAdmin()).session(session)).andReturn();
        var command=(OldCancelRecovery.Command)page.getModelAndView().getModel().get("recoveryCommand");
        assertThat(page.getResponse().getContentAsString()).contains("750","850","金融POST","name=\"_csrf\"");
        assertThat(version(f.op().id())).isEqualTo(command.expectedVersion());
        assertThat(jdbc.queryForObject("select count(*) from order_change_recovery_audits where operation_id=?",Integer.class,f.op().id())).isZero();verifyNoInteractions(gateway);
        adminMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url).with(recoveryAdmin()).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).session(session)
            .param("requestId",command.requestId().toString()).param("expectedVersion",Long.toString(command.expectedVersion())).param("reason",command.reason())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isFound());
        clearInvocations(gateway);
        var audited=adminMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).with(recoveryAdmin()).session(session)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        assertThat(audited.getResponse().getContentAsString()).contains(command.requestId().toString(),"shipper","COMPLETED").doesNotContain("client_secret","request_body","request_fingerprint");verifyNoInteractions(gateway);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"version","reason"})
    void adminRecoveryRejectsTargetChangedAfterConfirmation(String change)throws Exception{
        var f=recoveryFixture();var session=new org.springframework.mock.web.MockHttpSession();
        var page=adminMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(recoveryUrl(f)).with(recoveryAdmin()).session(session)).andReturn();
        var c=(OldCancelRecovery.Command)page.getModelAndView().getModel().get("recoveryCommand");
        if(change.equals("version"))jdbc.update("update order_change_operations set version=version+1 where id=?",f.op().id());
        else jdbc.update("update order_change_operations set financial_review_reason='OTHER' where id=?",f.op().id());
        adminMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(recoveryUrl(f)).with(recoveryAdmin()).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).session(session)
            .param("requestId",c.requestId().toString()).param("expectedVersion",Long.toString(c.expectedVersion())).param("reason",c.reason()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash().attribute("recoveryResult",change.equals("version")?"VERSION_CONFLICT":"NOT_ELIGIBLE"));
        verifyNoInteractions(gateway);assertHeldOriginal(f.base(),f.op());
        adminMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/admin/orders/"+f.base().order()+"/changes/"+UUID.randomUUID()+"/old-cancel-recovery").with(recoveryAdmin()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"old","new","get"})
    void detailedRecoveryOutcomesDistinguishUnconfirmedEvidence(String field){
        var f=recoveryFixture();var c=recoveryCommand(f);
        if(field.equals("old"))doReturn(jsonObservation(f.base().oldFlow(),"canceled",f.op().beforeAmount(),Map.of(),"1")).when(gateway).observeOrderChangeFlow(f.base().oldFlow());
        if(field.equals("new"))newState.set("canceled");
        if(field.equals("get"))doThrow(new PaymentGatewayException(PaymentGatewayErrorCategory.TRANSPORT_RESULT_UNKNOWN,null,null,true)).when(gateway).observeOrderChangeFlow(f.base().oldFlow());
        assertThat(recovery.recoverDetailed(c).detail()).isEqualTo(field.equals("old")?OldCancelRecovery.Detail.OLD_EVIDENCE_UNCONFIRMED:field.equals("new")?OldCancelRecovery.Detail.NEW_AUTH_UNAVAILABLE:OldCancelRecovery.Detail.GET_UNKNOWN);
        assertHeldOriginal(f.base(),f.op());verify(gateway,never()).sendOrderChangeRequest(any());
    }
    @Test void switchesOnceWithoutSecondStockDecrementAndCanAcceptSecondIncrease(){
        var f=fixture();var op=accept(f,5);mockFlow(f,op);
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("COMPLETED");
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select quantity from order_items where id=?",Integer.class,f.item())).isEqualTo(5);
        assertThat(jdbc.queryForObject("select total_amount from orders where id=?",Integer.class,f.order())).isEqualTo(1050);
        assertThat(jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product())).isEqualTo(17);
        assertThat(jdbc.queryForObject("select state from inventory_reservations where operation_id=?",String.class,op.id())).isEqualTo("CONSUMED");
        assertThat(jdbc.queryForObject("select count(*) from order_content_change_histories where order_id=?",Integer.class,f.order())).isEqualTo(1);
        var next=new Fixture(f.order(),f.user(),f.item(),f.product(),jdbc.queryForObject("select active_payment_id from orders where id=?",Long.class,f.order()),newFlow);
        var second=accept(next,6);mockFlow(next,second);
        assertThat(service.advance(f.order(),second.id()).state()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product())).isEqualTo(16);
    }
    @Test void actionWaitAndCanceledNewFlowNeverCancelOriginalOrder(){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);newState.set("requires_action");
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("REQUIRES_ACTION");assertThat(oldCancelled).isFalse();
        newState.set("canceled");webhook.synchronize("payment_flow.canceled",newFlow);assertThat(state(op.id())).isEqualTo("ABORTED");
        assertThat(jdbc.queryForObject("select status from orders where id=?",String.class,f.order())).isEqualTo("ORDERED");
        assertThat(jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product())).isEqualTo(20);
    }
    @Test void unknownCreateNeverAllocatesAnotherPaymentOrKey(){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);
        doThrow(new PaymentGatewayException(PaymentGatewayErrorCategory.SERVER_RESULT_UNKNOWN,500,null,true)).when(gateway).sendOrderChangeRequest(any());
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("MANUAL_REVIEW");
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("MANUAL_REVIEW");
        assertThat(jdbc.queryForObject("select count(*) from order_change_financial_requests where operation_id=?",Integer.class,op.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from payments where order_id=?",Integer.class,f.order())).isEqualTo(2);
        assertThat(oldCancelled).isFalse();verify(gateway,times(1)).sendOrderChangeRequest(any());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=PaymentGatewayErrorCategory.class,names={"TRANSPORT_RESULT_UNKNOWN","IDEMPOTENCY_WAIT_TIMEOUT"})
    void createTimeoutAnd409NeverRepostAfterRestart(PaymentGatewayErrorCategory category){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);
        doThrow(new PaymentGatewayException(category,null,null,true)).doAnswer(transport).when(gateway).sendOrderChangeRequest(any());
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("AUTH_UNKNOWN");
        var before=jdbc.queryForMap("select id,idempotency_key,request_body from order_change_financial_requests where operation_id=?",op.id());
        var restarted=new OrderReauthorizationService(resultTransactions,gateway,requestStore,manager);
        assertThat(restarted.advance(f.order(),op.id()).state()).isEqualTo("AUTH_UNKNOWN");
        verify(gateway,times(1)).sendOrderChangeRequest(any());
        assertHeldOriginal(f,op);
        assertThat(jdbc.queryForList("select d.state from order_change_request_dispatches d join order_change_financial_requests r on r.id=d.request_id where r.operation_id=?",String.class,op.id())).containsExactly("STARTED");
        var after=jdbc.queryForMap("select id,idempotency_key,request_body from order_change_financial_requests where operation_id=? and request_kind='CREATE'",op.id());
        assertThat(after.get("id")).isEqualTo(before.get("id"));assertThat(after.get("idempotency_key")).isEqualTo(before.get("idempotency_key"));assertThat(after.get("request_body")).isEqualTo(before.get("request_body"));
    }
    @Test void cancelTimeoutPreservesDecisionAndOnlyObservesAfterGrace(){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);var first=new AtomicBoolean(true);
        doAnswer(call->{var saved=((OrderChangeRequestSender.Dispatch)call.getArgument(0)).request();
            if(saved.httpRequest().kind().equals("OLD_CANCEL")&&first.getAndSet(false))throw new PaymentGatewayException(PaymentGatewayErrorCategory.TRANSPORT_RESULT_UNKNOWN,null,null,true);
            return transport.answer(call);
        }).when(gateway).sendOrderChangeRequest(any());
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("OLD_CANCEL_UNKNOWN");assertThat(oldCancelled).isFalse();
        var key=jdbc.queryForObject("select idempotency_key from order_change_financial_requests where operation_id=? and request_kind='OLD_CANCEL'",String.class,op.id());
        doReturn(op.graceUntil().plusHours(1)).when(orders).findCurrentTimeInTokyo();
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("OLD_CANCEL_UNKNOWN");
        verify(gateway,times(2)).sendOrderChangeRequest(any()); // CREATE + exactly one OLD_CANCEL
        assertHeldOriginal(f,op);
        oldCancelled.set(true); // A later external GET establishes the original Cancel succeeded.
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("COMPLETED");
        verify(gateway,times(2)).sendOrderChangeRequest(any());
        assertThat(jdbc.queryForList("select d.state from order_change_request_dispatches d join order_change_financial_requests r on r.id=d.request_id where r.operation_id=?",String.class,op.id())).containsOnly("OBSERVED");
        assertThat(jdbc.queryForObject("select idempotency_key from order_change_financial_requests where operation_id=? and request_kind='OLD_CANCEL'",String.class,op.id())).isEqualTo(key);
    }
    void assertHeldOriginal(Fixture f,OrderChangeStore.Operation op){
        assertThat(jdbc.queryForObject("select total_amount from orders where id=?",Integer.class,f.order())).isEqualTo(op.beforeAmount());
        assertThat(jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order())).isZero();
        assertThat(jdbc.queryForObject("select status from payments where id=?",String.class,f.oldPayment())).isEqualTo("AUTHORIZED");
        assertThat(jdbc.queryForObject("select state from inventory_reservations where operation_id=?",String.class,op.id())).isEqualTo("HELD");
    }
    @Test void abortUnknownOnlyObservesAndNeverPostsAgain(){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);
        // An expired candidate with a confirmed new authorization uses the existing abort path.
        doReturn(op.graceUntil().plusSeconds(1)).when(orders).findCurrentTimeInTokyo();
        doAnswer(call->{var saved=((OrderChangeRequestSender.Dispatch)call.getArgument(0)).request();
            if(saved.httpRequest().kind().equals("NEW_ABORT"))throw new PaymentGatewayException(PaymentGatewayErrorCategory.TRANSPORT_RESULT_UNKNOWN,null,null,true);
            return transport.answer(call);
        }).when(gateway).sendOrderChangeRequest(any());
        // Prepare before expiry; otherwise prepare() locally expires the RESERVED candidate.
        doReturn(op.graceUntil().minusMinutes(11)).when(orders).findCurrentTimeInTokyo();
        resultTransactions.prepare(f.order(),op.id());
        doReturn(op.graceUntil().plusSeconds(1)).when(orders).findCurrentTimeInTokyo();
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("ABORT_UNKNOWN");
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("ABORT_UNKNOWN");
        verify(gateway,times(2)).sendOrderChangeRequest(any());assertHeldOriginal(f,op);
    }
    @Test void manualReviewCannotBeAutomaticallyReopenedByGetEvidence(){
        var waiting=fixture();var candidate=accept(waiting,3);mockFlow(waiting,candidate);newState.set("requires_payment_method");
        service.advance(waiting.order(),candidate.id());
        jdbc.update("update order_change_operations set state='MANUAL_REVIEW',financial_review_reason='TEST_REVIEW' where id=?",candidate.id());
        newState.set("requires_capture");clearInvocations(gateway);
        assertThat(service.reconcile(waiting.order(),candidate.id()).state()).isEqualTo("MANUAL_REVIEW");
        assertThat(resultTransactions.authorization(waiting.order(),candidate.id(),observation(newFlow,"requires_capture",candidate.afterAmount(),Map.of("order_id",Long.toString(waiting.order()),"order_change_operation_id",candidate.id().toString()))).state()).isEqualTo("MANUAL_REVIEW");
        verifyNoInteractions(gateway);assertHeldOriginal(waiting,candidate);
    }
    @Test void webhookDuplicateAndReverseEventsDoNotRewindCompletion(){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);newState.set("requires_action");
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("REQUIRES_ACTION");
        newState.set("requires_capture");
        doReturn(jsonObservation(newFlow,"requires_capture",op.afterAmount(),Map.of("order_id",Long.toString(f.order()),"order_change_operation_id",op.id().toString()),"NULL"))
            .when(gateway).observeOrderChangeFlow(newFlow);
        reconciliation.reconcile(jdbc.queryForObject("select id from payment_transactions where order_change_operation_id=? and purpose='ORDER_CHANGE_AUTHORIZATION'",Long.class,op.id()));
        webhook.synchronize("payment_flow.requires_action",newFlow);assertThat(state(op.id())).isEqualTo("AUTHORIZED");
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("COMPLETED");
        webhook.synchronize("payment_flow.payment_failed",newFlow);webhook.synchronize("payment_flow.canceled",f.oldFlow());
        assertThat(state(op.id())).isEqualTo("COMPLETED");assertThat(jdbc.queryForObject("select status from orders where id=?",String.class,f.order())).isEqualTo("ORDERED");
    }
    @Test void switchRollbackRemainsCommitPendingAndStaleCancelCannotRewindIt(){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);String name="fail_switch_"+f.order();
        doReturn(jsonObservation(newFlow,"requires_capture",op.afterAmount(),Map.of("order_id",Long.toString(f.order()),"order_change_operation_id",op.id().toString()),"NULL"))
            .when(gateway).observeOrderChangeFlow(newFlow);
        jdbc.execute("create function "+name+"() returns trigger language plpgsql as $$begin if NEW.order_id="+f.order()+" then raise exception 'injected switch failure'; end if; return NEW; end;$$");
        jdbc.execute("create trigger "+name+" before update on order_items for each row execute function "+name+"()");
        try{
            assertThatThrownBy(()->service.advance(f.order(),op.id())).isInstanceOf(RuntimeException.class).hasStackTraceContaining("injected switch failure");
            assertThat(state(op.id())).isEqualTo("COMMIT_PENDING");
            assertThat(jdbc.queryForObject("select quantity from order_items where id=?",Integer.class,f.item())).isEqualTo(2);
            assertThat(jdbc.queryForObject("select state from inventory_reservations where operation_id=?",String.class,op.id())).isEqualTo("HELD");
            admin();assertThatThrownBy(()->shippingPreparation.start(f.order())).hasMessageContaining("未解決");
            assertThatThrownBy(()->cancellation.start(f.order(),f.user(),"customer")).hasMessageContaining("未解決");
            assertThat(resultTransactions.cancellation(f.order(),op.id(),false,observation(f.oldFlow(),"requires_capture",750,Map.of())).state()).isEqualTo("COMMIT_PENDING");
            assertThat(resultTransactions.failed(f.order(),op.id(),false,PaymentGatewayErrorCategory.TRANSPORT_RESULT_UNKNOWN).state()).isEqualTo("COMMIT_PENDING");
        }finally{jdbc.execute("drop trigger "+name+" on order_items");jdbc.execute("drop function "+name+"()");}
        assertThat(service.reconcile(f.order(),op.id()).state()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from order_content_change_histories where order_id=?",Integer.class,f.order())).isEqualTo(1);
    }
    @Test void graceExpiryAbortsOnlyConfirmedNewAuthorization(){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);newState.set("requires_action");service.advance(f.order(),op.id());
        newState.set("requires_capture");doReturn(op.graceUntil()).when(orders).findCurrentTimeInTokyo();
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("ABORTED");assertThat(oldCancelled).isFalse();
        assertThat(jdbc.queryForObject("select active_payment_id from orders where id=?",Long.class,f.order())).isEqualTo(f.oldPayment());
        assertThat(jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product())).isEqualTo(20);
    }
    @Test void freeShippingCanReduceTotalDespiteQuantityIncrease(){
        var f=fixture();jdbc.update("update order_items set quantity=49,subtotal=4900 where id=?",f.item());
        jdbc.update("update orders set total_amount=5450,item_subtotal=4900,charge_total=550 where id=?",f.order());
        jdbc.update("update payments set amount=5450 where id=?",f.oldPayment());jdbc.update("update payment_transactions set amount=5450 where payment_id=?",f.oldPayment());
        var op=accept(f,50);mockFlow(f,op);assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select total_amount from orders where id=?",Integer.class,f.order())).isEqualTo(5000);
        assertThat(jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product())).isEqualTo(19);
    }
    RequestedAbortCompletionControl completionControl(){return new RequestedAbortCompletionControl(resultTransactions,manager);}
    OrderChangeStore.Operation completionReady(Fixture f,boolean authorized){
        var op=authorized?requestedAuthorized(f):requestedFailed(f);
        if(authorized){
            var request=resultTransactions.prepareRequestedAbort(f.order(),op.id(),version(op.id()),abortEvidence(f,op));
            jdbc.update("update order_change_request_dispatches set state='STARTED',started_at=clock_timestamp() where request_id=?",request.id());
        }
        assertThat(closureControl().record(f.order(),op.id(),closureObservation(f,op))).isEqualTo(NewFlowClosureControl.RecordResult.RECORDED);
        return op;
    }
    void mockOldAuthorizationGet(){
        doAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return com.example.ecsite.payment.payjp.MockOldAuthorizationTransport.observe(call.getArgument(0));
        }).when(gateway).observeOldAuthorization(any());
    }
    Map<String,List<String>> completionRows(){
        var rows=closureDatabaseSnapshot();rows.keySet().removeIf(key->key.startsWith("sequence:"));return rows;
    }
    Map<String,List<String>> completionFinance(Fixture f,OrderChangeStore.Operation op){
        var rows=preservedBusiness(f,op);rows.keySet().retainAll(Set.of("order","items","oldPayment","oldTransactions"));
        rows.put("newPayment",jdbc.queryForList("select to_jsonb(t)::text from payments t where id=(select new_payment_id from order_change_operations where id=?)",String.class,op.id()));
        rows.put("newTransactions",jdbc.queryForList("select to_jsonb(t)::text from payment_transactions t where order_change_operation_id=? order by id",String.class,op.id()));
        rows.put("requests",jdbc.queryForList("select to_jsonb(t)::text from order_change_financial_requests t where operation_id=? order by id",String.class,op.id()));
        return rows;
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void requestedAbortCompletionReleasesOnlyExtraQuantityAndReplaysWithoutWrites(boolean authorized){
        var f=fixture();var op=completionReady(f,authorized);var finance=completionFinance(f,op);long expected=version(op.id());
        int movements=jdbc.queryForObject("select count(*) from stock_movements where order_id=?",Integer.class,f.order());
        assertThat(jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product())).isEqualTo(19);
        mockOldAuthorizationGet();clearInvocations(gateway);
        assertThat(completionControl().complete(gateway,f.order(),op.id())).isEqualTo(RequestedAbortCompletionControl.Result.COMPLETED);
        assertThat(state(op.id())).isEqualTo("ABORTED");assertThat(version(op.id())).isEqualTo(expected+1);
        assertThat(completionFinance(f,op)).isEqualTo(finance);
        assertThat(jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product())).isEqualTo(20);
        assertThat(jdbc.queryForObject("select state from inventory_reservations where operation_id=?",String.class,op.id())).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("select release_movement_id from inventory_reservations where operation_id=?",Long.class,op.id())).isPositive();
        assertThat(jdbc.queryForObject("select count(*) from stock_movements where order_id=?",Integer.class,f.order())).isEqualTo(movements+1);
        var complete=closureDatabaseSnapshot();assertThat(completionControl().complete(gateway,f.order(),op.id())).isEqualTo(RequestedAbortCompletionControl.Result.REPLAYED);
        assertThat(closureDatabaseSnapshot()).isEqualTo(complete);
        verify(gateway,atLeastOnce()).observeOldAuthorization(any());verifyNoMoreInteractions(gateway);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"WRONG_FLOW","WRONG_CAPTURABLE"})
    void requestedAbortCompletionRejectsInvalidOldAuthorizationWithoutWrites(String invalid){
        var f=fixture();var op=completionReady(f,false);clearInvocations(gateway);var before=closureDatabaseSnapshot();
        doAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var body="""
                {"id":"%s","status":"requires_capture","amount":%d,"currency":"jpy","capture_method":"manual",
                 "livemode":false,"amount_received":0,"amount_capturable":%d}
                """.formatted(invalid.equals("WRONG_FLOW")?"pfw_wrong":f.oldFlow(),op.beforeAmount(),invalid.equals("WRONG_CAPTURABLE")?op.beforeAmount()-1:op.beforeAmount());
            return com.example.ecsite.payment.payjp.MockOldAuthorizationTransport.observeRaw(call.getArgument(0),body,java.time.Clock.systemUTC(),System::nanoTime);
        }).when(gateway).observeOldAuthorization(any());
        assertThatThrownBy(()->completionControl().complete(gateway,f.order(),op.id())).isInstanceOf(RuntimeException.class);
        assertThat(closureDatabaseSnapshot()).isEqualTo(before);verify(gateway).observeOldAuthorization(any());verifyNoMoreInteractions(gateway);
    }
    @Test void requestedAbortCompletionOldGetTimeoutLeavesEveryRowAndSequenceUntouched(){
        var f=fixture();var op=completionReady(f,false);var before=closureDatabaseSnapshot();clearInvocations(gateway);
        doAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();throw new IllegalStateException("old GET timeout");}).when(gateway).observeOldAuthorization(any());
        assertThatThrownBy(()->completionControl().complete(gateway,f.order(),op.id())).isInstanceOf(RuntimeException.class);
        assertThat(closureDatabaseSnapshot()).isEqualTo(before);verify(gateway).observeOldAuthorization(any());verifyNoMoreInteractions(gateway);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"VERSION","OLD_PAYMENT","OLD_HISTORY","ORDER","RESERVATION","MANUAL_REVIEW"})
    void requestedAbortCompletionRevalidatesEverySnapshotAfterOldGet(String race){
        var f=fixture();var op=completionReady(f,false);var raced=new AtomicReference<Map<String,List<String>>>();clearInvocations(gateway);
        doAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var proof=com.example.ecsite.payment.payjp.MockOldAuthorizationTransport.observe(call.getArgument(0));
            switch(race){
                case "VERSION"->jdbc.update("update order_change_operations set version=version+1 where id=?",op.id());
                case "OLD_PAYMENT"->jdbc.update("update payments set updated_at=updated_at+interval '1 second' where id=?",f.oldPayment());
                case "OLD_HISTORY"->jdbc.update("update payment_transactions set completed_at=completed_at+interval '1 second' where payment_id=?",f.oldPayment());
                case "ORDER"->jdbc.update("update orders set ordered_at=ordered_at+interval '1 second' where id=?",f.order());
                case "RESERVATION"->jdbc.update("update inventory_reservations set held_at=held_at+interval '1 second' where operation_id=?",op.id());
                case "MANUAL_REVIEW"->jdbc.update("update order_change_operations set state='MANUAL_REVIEW',version=version+1 where id=?",op.id());
            }
            raced.set(closureDatabaseSnapshot());return proof;
        }).when(gateway).observeOldAuthorization(any());
        assertThatThrownBy(()->completionControl().complete(gateway,f.order(),op.id())).isInstanceOf(RuntimeException.class);
        assertThat(raced.get()).as("race injection completed").isNotNull();
        assertThat(closureDatabaseSnapshot()).isEqualTo(raced.get());verify(gateway).observeOldAuthorization(any());verifyNoMoreInteractions(gateway);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"PENDING_AUTH","NO_CLOSURE","MANUAL_REVIEW"})
    void requestedAbortCompletionRejectsUnsafeLocalStateWithoutWrites(String unsafe){
        var f=fixture();var op=unsafe.equals("PENDING_AUTH")?requestedUnconfirmed(f):unsafe.equals("NO_CLOSURE")?requestedFailed(f):completionReady(f,false);
        if(unsafe.equals("MANUAL_REVIEW"))jdbc.update("update order_change_operations set state='MANUAL_REVIEW',version=version+1 where id=?",op.id());
        mockOldAuthorizationGet();clearInvocations(gateway);var before=closureDatabaseSnapshot();
        assertThatThrownBy(()->completionControl().complete(gateway,f.order(),op.id())).isInstanceOf(RuntimeException.class);
        assertThat(closureDatabaseSnapshot()).isEqualTo(before);verifyNoInteractions(gateway);
    }
    @Test void requestedAbortCompletionFailureAtOperationUpdateRollsBackReleaseAndStock(){
        var f=fixture();var op=completionReady(f,true);mockOldAuthorizationGet();var before=completionRows();
        String name="fail_abort_completion_"+op.id().toString().replace("-","");
        jdbc.execute("create function "+name+"() returns trigger language plpgsql as $$ begin raise exception 'synthetic completion rollback'; end $$");
        jdbc.execute("create trigger "+name+" before update on order_change_operations for each row when(new.id='"+op.id()+"') execute function "+name+"()");
        try{assertThatThrownBy(()->completionControl().complete(gateway,f.order(),op.id())).isInstanceOf(RuntimeException.class);
            assertThat(completionRows()).isEqualTo(before);
        }finally{jdbc.execute("drop trigger "+name+" on order_change_operations");jdbc.execute("drop function "+name+"()");}
        assertThat(completionControl().complete(gateway,f.order(),op.id())).isEqualTo(RequestedAbortCompletionControl.Result.COMPLETED);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void requestedAbortCompletionCommitUncertaintyNeverReturnsSuccess(boolean committed){
        var f=fixture();var op=completionReady(f,false);mockOldAuthorizationGet();var before=completionRows();
        var factory=((org.springframework.orm.jpa.JpaTransactionManager)manager).getEntityManagerFactory();var commits=new AtomicInteger();
        var ambiguous=new org.springframework.orm.jpa.JpaTransactionManager(factory){
            @Override protected void doCommit(DefaultTransactionStatus status){
                if(commits.incrementAndGet()!=2){super.doCommit(status);return;}
                if(committed)super.doCommit(status);else super.doRollback(status);
                throw new TransactionSystemException("synthetic completion commit uncertainty");
            }
        };
        assertThatThrownBy(()->new RequestedAbortCompletionControl(resultTransactions,ambiguous).complete(gateway,f.order(),op.id())).isInstanceOf(TransactionSystemException.class);
        if(committed){assertThat(state(op.id())).isEqualTo("ABORTED");assertThat(completionControl().complete(gateway,f.order(),op.id())).isEqualTo(RequestedAbortCompletionControl.Result.REPLAYED);}
        else{assertThat(completionRows()).isEqualTo(before);assertThat(completionControl().complete(gateway,f.order(),op.id())).isEqualTo(RequestedAbortCompletionControl.Result.COMPLETED);}
    }
    @Test void requestedAbortCompletionConcurrentDuplicatesRestoreStockOnlyOnce()throws Exception{
        var f=fixture();var op=completionReady(f,false);var gate=new java.util.concurrent.CyclicBarrier(2);
        doAnswer(call->{var proof=com.example.ecsite.payment.payjp.MockOldAuthorizationTransport.observe(call.getArgument(0));gate.await(20,java.util.concurrent.TimeUnit.SECONDS);return proof;}).when(gateway).observeOldAuthorization(any());
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{java.util.concurrent.Callable<Object> task=()->{try{return completionControl().complete(gateway,f.order(),op.id());}catch(RuntimeException conflict){return conflict;}};
            var a=pool.submit(task);var b=pool.submit(task);var outcomes=List.of(a.get(30,java.util.concurrent.TimeUnit.SECONDS),b.get(30,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(RequestedAbortCompletionControl.Result.COMPLETED::equals).count()).isEqualTo(1);
            assertThat(state(op.id())).isEqualTo("ABORTED");assertThat(jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product())).isEqualTo(20);
        }finally{pool.shutdownNow();}
    }
    void assertCompletionReplayAfterLaterWork(Fixture f,OrderChangeStore.Operation op){
        var before=closureDatabaseSnapshot();clearInvocations(gateway);
        assertThat(completionControl().complete(gateway,f.order(),op.id())).isEqualTo(RequestedAbortCompletionControl.Result.REPLAYED);
        assertThat(closureDatabaseSnapshot()).isEqualTo(before);verifyNoInteractions(gateway);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"DIRECT_ABORTED","PARTIAL_PROOF","FORGED_COMPLETE_PROOF"})
    void requestedAbortCompletionDatabaseGuardRejectsUnprovedTerminalState(String mutation){
        var f=fixture();var op=completionReady(f,false);var before=closureDatabaseSnapshot();clearInvocations(gateway);
        String assignment=switch(mutation){
            case "DIRECT_ABORTED"->"state='ABORTED'";
            case "PARTIAL_PROOF"->"abort_completed_at=clock_timestamp()";
            default->"state='ABORTED',abort_completed_at=clock_timestamp(),abort_completion_digest=decode(repeat('ab',32),'hex'),abort_completion_version=1,abort_old_authorization_observed_at=clock_timestamp()";
        };
        assertThatThrownBy(()->jdbc.update("update order_change_operations set "+assignment+",version=version+1 where id=?",op.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(closureDatabaseSnapshot()).isEqualTo(before);verifyNoInteractions(gateway);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"DIGEST","TIMESTAMP","VERSION","OBSERVED_AT","DELETE"})
    void requestedAbortCompletionDatabaseGuardProtectsCompletedProof(String mutation){
        var f=fixture();var op=completionReady(f,false);mockOldAuthorizationGet();completionControl().complete(gateway,f.order(),op.id());
        var before=closureDatabaseSnapshot();clearInvocations(gateway);
        String assignment=switch(mutation){
            case "DIGEST"->"abort_completion_digest=decode(repeat('ab',32),'hex')";
            case "TIMESTAMP"->"abort_completed_at=abort_completed_at+interval '1 second'";
            case "VERSION"->"abort_completion_version=2";
            default->"abort_old_authorization_observed_at=abort_old_authorization_observed_at+interval '1 second'";
        };
        assertThatThrownBy(()->{
            if(mutation.equals("DELETE"))jdbc.update("delete from order_change_operations where id=?",op.id());
            else jdbc.update("update order_change_operations set "+assignment+",version=version+1 where id=?",op.id());
        }).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(closureDatabaseSnapshot()).isEqualTo(before);verifyNoInteractions(gateway);
    }
    @Test void requestedAbortCompletionAllowsExpiredDeadlineAndPreservesOldPaymentForShipping(){
        var f=fixture();var op=completionReady(f,true);mockOldAuthorizationGet();admin();
        var deadline=jdbc.queryForObject("select change_deadline_at from orders where id=?",java.time.LocalDateTime.class,f.order());
        doReturn(deadline.plusSeconds(1)).when(orders).findCurrentTimeInTokyo();doReturn(deadline.plusSeconds(1).atZone(java.time.ZoneId.of("Asia/Tokyo")).toInstant()).when(clock).instant();
        assertThat(completionControl().complete(gateway,f.order(),op.id())).isEqualTo(RequestedAbortCompletionControl.Result.COMPLETED);
        shippingPreparation.start(f.order());
        when(gateway.capture(any())).thenAnswer(call->{var request=(com.example.ecsite.payment.capture.CaptureHttpRequest)call.getArgument(0);assertThat(request.flowId()).isEqualTo(f.oldFlow());return new CaptureResult(CaptureResultStatus.PENDING,f.oldFlow(),null,null);});
        assertThat(capture.captureForShipment(f.order(),9L,"shipper",null).status()).isEqualTo(CaptureResultStatus.PENDING);
        assertCompletionReplayAfterLaterWork(f,op);
        when(gateway.retrievePaymentFlow(f.oldFlow())).thenReturn(new PaymentFlowState(f.oldFlow(),PaymentFlowStatus.SUCCEEDED,null,null));
        capture.captureForShipment(f.order(),9L,"shipper",null);
        assertThat(jdbc.queryForObject("select status from payments where id=?",String.class,f.oldPayment())).isEqualTo("CAPTURED");
        assertCompletionReplayAfterLaterWork(f,op);
    }
    @Test void requestedAbortCompletionRestoresNormalCancellationOnOldPayment(){
        var f=fixture();var op=completionReady(f,false);mockOldAuthorizationGet();completionControl().complete(gateway,f.order(),op.id());
        var start=cancellation.start(f.order(),f.user(),"customer");assertThat(start.providerPaymentId()).isEqualTo(f.oldFlow());
        assertThat(cancellationResults.apply(start.snapshot(),new CancellationResult(CancellationResultStatus.CANCELLED,f.oldFlow()))).isEqualTo(com.example.ecsite.service.payment.PaymentResultApplication.APPLIED);
        assertCompletionReplayAfterLaterWork(f,op);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"WEBHOOK","RECONCILIATION"})
    void requestedAbortCompletionKeepsNewFinanceFrozenDuringOldGetResultEntry(String route){
        var f=fixture();var op=completionReady(f,false);var finance=completionFinance(f,op);
        long auth=jdbc.queryForObject("select id from payment_transactions where order_change_operation_id=? and purpose='ORDER_CHANGE_AUTHORIZATION'",Long.class,op.id());
        newState.set("requires_capture");clearInvocations(gateway);
        doAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var before=closureDatabaseSnapshot();
            if(route.equals("WEBHOOK"))webhook.synchronize("payment_flow.requires_action",newFlow);
            else assertThat(reconciliation.reconcile(auth)).isEqualTo(com.example.ecsite.service.payment.PaymentSynchronizationOutcome.SKIPPED);
            assertThat(closureDatabaseSnapshot()).isEqualTo(before);assertThat(completionFinance(f,op)).isEqualTo(finance);
            return com.example.ecsite.payment.payjp.MockOldAuthorizationTransport.observe(call.getArgument(0));
        }).when(gateway).observeOldAuthorization(any());
        assertThat(completionControl().complete(gateway,f.order(),op.id())).isEqualTo(RequestedAbortCompletionControl.Result.COMPLETED);
        assertThat(completionFinance(f,op)).isEqualTo(finance);assertThat(state(op.id())).isEqualTo("ABORTED");
        assertThat(jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product())).isEqualTo(20);
        verify(gateway).observeOldAuthorization(any());verifyNoMoreInteractions(gateway);
        assertCompletionReplayAfterLaterWork(f,op);
    }
    @Test void requestedAbortCompletionPermitsAnotherIncreaseAgainstOriginalOrder(){
        var f=fixture();var op=completionReady(f,false);mockOldAuthorizationGet();completionControl().complete(gateway,f.order(),op.id());
        var next=accept(f,4);assertThat(next.id()).isNotEqualTo(op.id());assertThat(next.beforeAmount()).isEqualTo(op.beforeAmount());
        assertThat(jdbc.queryForObject("select quantity from order_items where id=?",Integer.class,f.item())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product())).isEqualTo(18);
        assertCompletionReplayAfterLaterWork(f,op);
        int revision=jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order());
        mockFlow(f,next);assertThat(service.advance(f.order(),next.id()).state()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order())).isEqualTo(revision+1);
        assertThat(jdbc.queryForObject("select active_payment_id from orders where id=?",Long.class,f.order())).isNotEqualTo(f.oldPayment());
        assertThat(jdbc.queryForObject("select quantity from order_items where id=?",Integer.class,f.item())).isEqualTo(4);
        assertCompletionReplayAfterLaterWork(f,op);
    }
    @Test void ordinaryCancellationUsesNewPaymentAfterSwitch(){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);service.advance(f.order(),op.id());
        var start=cancellation.start(f.order(),f.user(),"customer");
        assertThat(start.providerPaymentId()).isEqualTo(newFlow);
        assertThat(cancellationResults.apply(start.snapshot(),new CancellationResult(CancellationResultStatus.CANCELLED,newFlow)))
            .isEqualTo(com.example.ecsite.service.payment.PaymentResultApplication.APPLIED);
        assertThat(jdbc.queryForObject("select status from orders where id=?",String.class,f.order())).isEqualTo("CANCELLED");
        assertThat(cancellationResults.apply(start.snapshot(),new CancellationResult(CancellationResultStatus.CANCELLED,newFlow)))
            .isEqualTo(com.example.ecsite.service.payment.PaymentResultApplication.ALREADY_APPLIED);
    }
    void admin(){var actor=new com.example.ecsite.security.AdminUserDetails(9L,"shipper","unused",true,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(actor,"",actor.getAuthorities()));}
    @AfterEach void cleanup(){org.springframework.security.core.context.SecurityContextHolder.clearContext();reset(orders,clock);}
    @Test void newActivePaymentShipsWithV54FixedTargetAndPendingCaptureOnlyGets(){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);service.advance(f.order(),op.id());admin();
        var deadline=jdbc.queryForObject("select change_deadline_at from orders where id=?",java.time.LocalDateTime.class,f.order());
        doReturn(deadline).when(orders).findCurrentTimeInTokyo();doReturn(deadline.atZone(java.time.ZoneId.of("Asia/Tokyo")).toInstant()).when(clock).instant();
        shippingPreparation.start(f.order());
        when(gateway.capture(any())).thenAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("select status from orders where id=?",String.class,f.order())).isEqualTo("SHIPPED");
            var request=(com.example.ecsite.payment.capture.CaptureHttpRequest)call.getArgument(0);assertThat(request.flowId()).isEqualTo(newFlow);
            return new CaptureResult(CaptureResultStatus.PENDING,newFlow,null,null);});
        assertThat(capture.captureForShipment(f.order(),9L,"shipper",null).status()).isEqualTo(CaptureResultStatus.PENDING);
        var bytes=jdbc.queryForObject("select request_body from capture_requests where payment_id=(select active_payment_id from orders where id=?)",byte[].class,f.order());
        when(gateway.retrievePaymentFlow(newFlow)).thenReturn(new PaymentFlowState(newFlow,PaymentFlowStatus.SUCCEEDED,null,null));
        capture.captureForShipment(f.order(),9L,"shipper",null);verify(gateway,times(1)).capture(any());
        assertThat(jdbc.queryForObject("select request_body from capture_requests where payment_id=(select active_payment_id from orders where id=?)",byte[].class,f.order())).isEqualTo(bytes);
        assertThat(jdbc.queryForObject("select status from orders where id=?",String.class,f.order())).isEqualTo("SHIPPED");
    }
    @Test void concurrentStaleCreateCannotOverwriteCompletedProgress()throws Exception{
        var f=fixture();var op=accept(f,3);mockFlow(f,op);
        var entered=new java.util.concurrent.CountDownLatch(1);var resume=new java.util.concurrent.CountDownLatch(1);var first=new AtomicBoolean(true);
        OrderChangeFinancialRequestStore target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(requestStore);
        doAnswer(call->{if(first.getAndSet(false)){entered.countDown();if(!resume.await(20,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Dispatch test coordination timeout");}return call.callRealMethod();})
            .when(target).findForDispatch(anyLong());
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{
            var stale=pool.submit(()->service.advance(f.order(),op.id()));
            assertThat(entered.await(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("COMPLETED");
            resume.countDown();assertThat(stale.get(20,java.util.concurrent.TimeUnit.SECONDS).state()).isEqualTo("COMPLETED");
            assertThat(jdbc.queryForObject("select count(*) from order_content_change_histories where order_id=?",Integer.class,f.order())).isEqualTo(1);
        }finally{resume.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
    }
    @Test void lostNewAuthorizationAfterOldCancelHoldsOrderForReview(){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);
        doAnswer(call->{var saved=((OrderChangeRequestSender.Dispatch)call.getArgument(0)).request();var response=transport.answer(call);
            if(saved.httpRequest().kind().equals("OLD_CANCEL"))newState.set("canceled");return response;
        }).when(gateway).sendOrderChangeRequest(any());
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("MANUAL_REVIEW");
        assertThat(jdbc.queryForObject("select total_amount from orders where id=?",Integer.class,f.order())).isEqualTo(750);
        assertThat(jdbc.queryForObject("select status from payments where id=?",String.class,f.oldPayment())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select state from inventory_reservations where operation_id=?",String.class,op.id())).isEqualTo("HELD");
    }
    @Test void mismatchedNewFlowMetadataNeverReachesOldCancel(){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);
        doReturn(observation(newFlow,"requires_capture",op.afterAmount(),Map.of("order_id",Long.toString(f.order()),"order_change_operation_id",UUID.randomUUID().toString())))
            .when(gateway).observeOrderChangeFlow(newFlow);
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("MANUAL_REVIEW");assertThat(oldCancelled).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from order_change_financial_requests where operation_id=? and request_kind='OLD_CANCEL'",Integer.class,op.id())).isZero();
    }

    // Customer withdrawal workflow: all provider boundaries below use isolated Mock HTTP evidence.
    @Autowired OrderChangeWithdrawalService withdrawals;
    void mockWithdrawalTransport(AtomicInteger posts,boolean timeout){
        doAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return com.example.ecsite.payment.payjp.MockAbortPreparationTransport.observe(call.getArgument(0));
        }).when(gateway).observeAbortPreparation(any());
        doAnswer(call->com.example.ecsite.payment.payjp.MockRequestedAbortTransport.observe(call.getArgument(0)))
            .when(gateway).observeRequestedAbort(any());
        doAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            RequestedAbortDispatchControl.Dispatch dispatch=call.getArgument(0);
            var saved=dispatch.consumeForGateway();
            assertThat(saved.httpRequest().kind()).isEqualTo("NEW_ABORT");
            assertThat(jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,saved.id())).isEqualTo("STARTED");
            posts.incrementAndGet();
            if(timeout)throw new PaymentGatewayException("Mock cancellation timeout");
            newState.set("canceled");return null;
        }).when(gateway).sendRequestedAbort(any());
        mockClosureGet();mockOldAuthorizationGet();
    }
    Map<String,List<String>> withdrawalOriginal(Fixture f,OrderChangeStore.Operation op){
        var rows=preservedBusiness(f,op);rows.keySet().retainAll(Set.of("order","items","oldPayment","oldTransactions"));return rows;
    }
    void assertWithdrawalReleasedOnce(Fixture f,OrderChangeStore.Operation op,int movements){
        assertThat(state(op.id())).isEqualTo("ABORTED");
        assertThat(jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product())).isEqualTo(20);
        assertThat(jdbc.queryForObject("select state from inventory_reservations where operation_id=?",String.class,op.id())).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("select count(*) from stock_movements where order_id=?",Integer.class,f.order())).isEqualTo(movements+1);
        assertThat(jdbc.queryForObject("select count(*) from order_content_change_histories where order_id=?",Integer.class,f.order())).isZero();
    }
    @Test void withdrawalReservedUsesLocalReleaseWithoutFinancialWorkAndReleasesOnce(){
        var f=fixture();var op=accept(f,3);var original=withdrawalOriginal(f,op);reset(gateway);
        int movements=jdbc.queryForObject("select count(*) from stock_movements where order_id=?",Integer.class,f.order());
        UUID command=UUID.randomUUID();long expected=version(op.id());
        assertThat(withdrawals.status(f.order(),f.user(),op.id()).canRequest()).isTrue();
        withdrawals.withdraw(f.order(),f.user(),op.id(),command,expected);
        assertWithdrawalReleasedOnce(f,op,movements);assertThat(withdrawalOriginal(f,op)).isEqualTo(original);
        assertThat(jdbc.queryForObject("select count(*) from order_change_financial_requests where operation_id=?",Integer.class,op.id())).isZero();
        var complete=closureDatabaseSnapshot();withdrawals.withdraw(f.order(),f.user(),op.id(),command,expected);
        assertThat(closureDatabaseSnapshot()).isEqualTo(complete);verifyNoInteractions(gateway);
    }
    @Test void withdrawalKnownSuccessfulAuthorizationCancelsNewOnlyAndSameCommandReplays(){
        var f=fixture();var op=accept(f,3);authorizeWithoutDecision(f,op);var original=withdrawalOriginal(f,op);
        assertThat(jdbc.queryForObject("select status from payment_transactions where order_change_operation_id=? and transaction_type='AUTHORIZE'",String.class,op.id())).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("select status from payments where id=(select new_payment_id from order_change_operations where id=?)",String.class,op.id())).isEqualTo("AUTHORIZED");
        int movements=jdbc.queryForObject("select count(*) from stock_movements where order_id=?",Integer.class,f.order());
        var posts=new AtomicInteger();mockWithdrawalTransport(posts,false);clearInvocations(gateway);
        UUID command=UUID.randomUUID();long expected=version(op.id());withdrawals.withdraw(f.order(),f.user(),op.id(),command,expected);
        assertThat(posts).hasValue(1);assertThat(newState).hasValue("canceled");assertThat(oldCancelled).isFalse();
        assertWithdrawalReleasedOnce(f,op,movements);assertThat(withdrawalOriginal(f,op)).isEqualTo(original);
        assertThat(jdbc.queryForObject("select abort_request_id from order_change_operations where id=?",UUID.class,op.id())).isEqualTo(command);
        assertThat(jdbc.queryForObject("select count(*) from order_change_financial_requests where operation_id=? and request_kind='NEW_ABORT'",Integer.class,op.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from order_change_financial_requests where operation_id=? and request_kind='OLD_CANCEL'",Integer.class,op.id())).isZero();
        verify(gateway,times(1)).observeAbortPreparation(any());verify(gateway,times(1)).sendRequestedAbort(any());
        verify(gateway,atLeastOnce()).observeNewFlowClosure(any());verify(gateway,atLeastOnce()).observeOldAuthorization(any());
        verify(gateway,never()).sendOrderChangeRequest(any());
        var complete=closureDatabaseSnapshot();clearInvocations(gateway);
        withdrawals.withdraw(f.order(),f.user(),op.id(),command,expected);
        assertThat(posts).hasValue(1);assertThat(closureDatabaseSnapshot()).isEqualTo(complete);
        var status=withdrawals.status(f.order(),f.user(),op.id());assertThat(status.requested()).isTrue();assertThat(status.canRequest()).isFalse();
        assertThatThrownBy(()->withdrawals.withdraw(f.order(),f.user(),op.id(),UUID.randomUUID(),expected)).isInstanceOf(IllegalStateException.class);
        assertThat(closureDatabaseSnapshot()).isEqualTo(complete);verify(gateway,never()).sendRequestedAbort(any());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"PREAUTH","PENDING","CREATE_UNKNOWN","MANUAL_REVIEW"})
    void withdrawalUncertainFinancialWorkPersistsIntentAndDisplaysManualWithoutRelease(String phase){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);newState.set("requires_payment_method");
        if(phase.equals("CREATE_UNKNOWN"))doThrow(new PaymentGatewayException("Mock unknown create")).when(gateway).sendOrderChangeRequest(any());
        service.advance(f.order(),op.id());
        if(phase.equals("PENDING"))newState.set("requires_action");
        if(phase.equals("MANUAL_REVIEW"))jdbc.update("update order_change_operations set state='MANUAL_REVIEW',financial_review_reason='TEST_REVIEW',version=version+1 where id=?",op.id());
        var before=preservedBusiness(f,op);var posts=new AtomicInteger();mockWithdrawalTransport(posts,false);clearInvocations(gateway);
        UUID command=UUID.randomUUID();withdrawals.withdraw(f.order(),f.user(),op.id(),command,version(op.id()));
        assertThat(jdbc.queryForObject("select abort_request_id from order_change_operations where id=?",UUID.class,op.id())).isEqualTo(command);
        var status=withdrawals.status(f.order(),f.user(),op.id());assertThat(status.requested()).isTrue();
        assertThat(status.canRequest()).isFalse();assertThat(status.manualReviewRequired()).isTrue();
        assertThat(posts).hasValue(0);assertAbortHeld(f,op,before);
        verify(gateway,never()).sendRequestedAbort(any());verify(gateway,never()).sendOrderChangeRequest(any());
    }
    @Test void withdrawalTimeoutKeepsReservationAndRepeatNeverPostsAgainBeforeStrictClosure(){
        var f=fixture();var op=accept(f,3);authorizeWithoutDecision(f,op);var before=preservedBusiness(f,op);
        var posts=new AtomicInteger();mockWithdrawalTransport(posts,true);
        // The strict closure transport normally always returns canceled; here GET must stay inconclusive.
        doThrow(new PaymentGatewayException("Mock closure not confirmed")).when(gateway).observeNewFlowClosure(any());
        UUID command=UUID.randomUUID();long expected=version(op.id());withdrawals.withdraw(f.order(),f.user(),op.id(),command,expected);
        assertThat(posts).hasValue(1);assertThat(state(op.id())).isEqualTo("ABORT_UNKNOWN");
        assertThat(preservedBusiness(f,op)).isEqualTo(before);
        withdrawals.withdraw(f.order(),f.user(),op.id(),command,expected);
        assertThat(posts).hasValue(1);assertThat(preservedBusiness(f,op)).isEqualTo(before);
        int movements=jdbc.queryForObject("select count(*) from stock_movements where order_id=?",Integer.class,f.order());
        newState.set("canceled");mockClosureGet();withdrawals.withdraw(f.order(),f.user(),op.id(),command,expected);
        assertThat(posts).hasValue(1);assertWithdrawalReleasedOnce(f,op,movements);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"FLOW","METADATA"})
    void withdrawalInvalidPreparationNeverCreatesNewAbort(String mismatch){
        var f=fixture();var op=accept(f,3);authorizeWithoutDecision(f,op);var before=preservedBusiness(f,op);
        var posts=new AtomicInteger();mockWithdrawalTransport(posts,false);
        doAnswer(call->{NewFlowClosureControl.Expected expected=call.getArgument(0);var flow=expected.flow();
            String body="""
                {"id":"%s","status":"requires_capture","amount":%d,"currency":"jpy","capture_method":"manual",
                 "livemode":false,"amount_received":null,"amount_capturable":%d,
                 "metadata":{"order_id":"%s","order_change_operation_id":"%s"}}
                """.formatted(mismatch.equals("FLOW")?"pfw_wrong":flow.newFlowId(),flow.amount(),flow.amount(),flow.orderId(),mismatch.equals("METADATA")?UUID.randomUUID():flow.operationId());
            return com.example.ecsite.payment.payjp.MockAbortPreparationTransport.observeRaw(expected,body,java.time.Clock.systemUTC(),System::nanoTime);
        }).when(gateway).observeAbortPreparation(any());
        withdrawals.withdraw(f.order(),f.user(),op.id(),UUID.randomUUID(),version(op.id()));
        assertThat(posts).hasValue(0);assertAbortHeld(f,op,before);verify(gateway,never()).sendRequestedAbort(any());
    }
    @Test void withdrawalAfterOldCancelDecisionIsRefusedWithoutGatewayWork(){
        var f=fixture();var op=accept(f,3);authorizeWithoutDecision(f,op);resultTransactions.decide(f.order(),op.id(),newAuthorized(f,op),observation(f.oldFlow(),"requires_capture",op.beforeAmount(),Map.of()));
        var before=closureDatabaseSnapshot();clearInvocations(gateway);
        assertThat(withdrawals.status(f.order(),f.user(),op.id()).canRequest()).isFalse();
        assertThatThrownBy(()->withdrawals.withdraw(f.order(),f.user(),op.id(),UUID.randomUUID(),version(op.id()))).isInstanceOf(IllegalStateException.class);
        assertThat(closureDatabaseSnapshot()).isEqualTo(before);verifyNoInteractions(gateway);
    }
    @Test void withdrawalWrongOwnerCannotReadStatusOrPersistIntent(){
        var f=fixture();var op=accept(f,3);authorizeWithoutDecision(f,op);var stranger=fixture();
        var before=closureDatabaseSnapshot();clearInvocations(gateway);
        assertThatThrownBy(()->withdrawals.status(f.order(),stranger.user(),op.id())).isInstanceOf(com.example.ecsite.exception.OrderNotFoundException.class);
        assertThatThrownBy(()->withdrawals.withdraw(f.order(),stranger.user(),op.id(),UUID.randomUUID(),version(op.id()))).isInstanceOf(com.example.ecsite.exception.OrderNotFoundException.class);
        assertThat(closureDatabaseSnapshot()).isEqualTo(before);verifyNoInteractions(gateway);
    }
}
