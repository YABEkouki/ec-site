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
class OrderReauthorizationPostgresTest {
    @Autowired OrderReauthorizationService service;
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
    PayJpPaymentFlowObservation observation(String flow,String status,int amount,Map<String,Object> metadata){return new PayJpPaymentFlowObservation(flow,status,(long)amount,"jpy",0L,"requires_capture".equals(status)?(long)amount:0L,"manual",false,null,null,metadata);}
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
    String state(UUID op){return jdbc.queryForObject("select state from order_change_operations where id=?",String.class,op);}
    PayJpPaymentFlowObservation jsonObservation(String flow,String status,int amount,Map<String,Object> metadata,String received){
        var fields=new LinkedHashMap<String,Object>();
        fields.put("id",flow);fields.put("status",status);fields.put("amount",amount);fields.put("currency","jpy");
        fields.put("capture_method","manual");fields.put("livemode",false);fields.put("metadata",metadata);
        fields.put("amount_capturable",status.equals("requires_capture")?Long.valueOf(amount):status.equals("canceled")?Long.valueOf(0):null);
        if(!received.equals("MISSING"))fields.put("amount_received",received.equals("NULL")?null:Long.valueOf(received));
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
        return mapper.readValue(mapper.writeValueAsString(fields),PayJpPaymentFlowObservation.class);
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
    @org.junit.jupiter.params.provider.CsvSource({"NEW_AUTHORIZATION,NEW_FLOW_EVIDENCE_MISMATCH",
        "OLD_AUTHORIZATION,OLD_AUTH_NOT_READY", "COMMIT,COMMIT_AUTHORIZATION_UNAVAILABLE"})
    void missingReceivedNeverProvesAuthorizationCancelEligibilityOrSwitch(String stage,String reason){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);
        var metadata=Map.<String,Object>of("order_id",Long.toString(f.order()),"order_change_operation_id",op.id().toString());
        var missing=jsonObservation(newFlow,"requires_capture",op.afterAmount(),metadata,"MISSING");
        if(stage.equals("OLD_AUTHORIZATION"))doReturn(jsonObservation(f.oldFlow(),"requires_capture",op.beforeAmount(),Map.of(),"MISSING"))
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
    void createTimeoutAnd409ReuseSameRequestAfterRestart(PaymentGatewayErrorCategory category){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);
        doThrow(new PaymentGatewayException(category,null,null,true)).doAnswer(transport).when(gateway).sendOrderChangeRequest(any());
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("AUTH_UNKNOWN");
        var before=jdbc.queryForMap("select id,idempotency_key,request_body from order_change_financial_requests where operation_id=?",op.id());
        var restarted=new OrderReauthorizationService(resultTransactions,gateway,requestStore,manager);
        assertThat(restarted.advance(f.order(),op.id()).state()).isEqualTo("COMPLETED");
        var after=jdbc.queryForMap("select id,idempotency_key,request_body from order_change_financial_requests where operation_id=? and request_kind='CREATE'",op.id());
        assertThat(after.get("id")).isEqualTo(before.get("id"));assertThat(after.get("idempotency_key")).isEqualTo(before.get("idempotency_key"));assertThat(after.get("request_body")).isEqualTo(before.get("request_body"));
    }
    @Test void cancelTimeoutPreservesDecisionAndRetriesSameKeyAfterGrace(){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);var first=new AtomicBoolean(true);
        doAnswer(call->{var saved=((OrderChangeRequestSender.Dispatch)call.getArgument(0)).request();
            if(saved.httpRequest().kind().equals("OLD_CANCEL")&&first.getAndSet(false))throw new PaymentGatewayException(PaymentGatewayErrorCategory.TRANSPORT_RESULT_UNKNOWN,null,null,true);
            return transport.answer(call);
        }).when(gateway).sendOrderChangeRequest(any());
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("OLD_CANCEL_UNKNOWN");assertThat(oldCancelled).isFalse();
        var key=jdbc.queryForObject("select idempotency_key from order_change_financial_requests where operation_id=? and request_kind='OLD_CANCEL'",String.class,op.id());
        doReturn(op.graceUntil().plusHours(1)).when(orders).findCurrentTimeInTokyo();
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select idempotency_key from order_change_financial_requests where operation_id=? and request_kind='OLD_CANCEL'",String.class,op.id())).isEqualTo(key);
    }
    @Test void webhookDuplicateAndReverseEventsDoNotRewindCompletion(){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);newState.set("requires_action");
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("REQUIRES_ACTION");
        newState.set("requires_capture");
        reconciliation.reconcile(jdbc.queryForObject("select id from payment_transactions where order_change_operation_id=? and purpose='ORDER_CHANGE_AUTHORIZATION'",Long.class,op.id()));
        webhook.synchronize("payment_flow.requires_action",newFlow);assertThat(state(op.id())).isEqualTo("AUTHORIZED");
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("COMPLETED");
        webhook.synchronize("payment_flow.payment_failed",newFlow);webhook.synchronize("payment_flow.canceled",f.oldFlow());
        assertThat(state(op.id())).isEqualTo("COMPLETED");assertThat(jdbc.queryForObject("select status from orders where id=?",String.class,f.order())).isEqualTo("ORDERED");
    }
    @Test void switchRollbackRemainsCommitPendingAndStaleCancelCannotRewindIt(){
        var f=fixture();var op=accept(f,3);mockFlow(f,op);String name="fail_switch_"+f.order();
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
}
