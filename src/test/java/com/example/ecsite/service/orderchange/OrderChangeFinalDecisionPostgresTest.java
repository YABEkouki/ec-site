package com.example.ecsite.service.orderchange;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.payment.*;
import com.example.ecsite.payment.orderchange.*;
import com.example.ecsite.payment.payjp.PayJpPaymentFlowObservation;
import com.example.ecsite.repository.*;

/**
 * Feature128 A-2: 14:04 switch cutoff and irreversible 14:05 final decision.
 * The database guards use the real clock. LEGACY order and operation deadline columns are shifted back
 * together (keeping their CHECK relations) so that "now" lands just after deadline+4min or deadline+5min.
 */
@SpringBootTest(properties={"logging.level.root=WARN","spring.jpa.show-sql=false"})
@com.example.ecsite.support.CaptureConnectionTest
class OrderChangeFinalDecisionPostgresTest {
    @Autowired OrderReauthorizationService service;
    @Autowired OrderReauthorizationTransactions results;
    @Autowired OrderChangeFinalDecisionService decisions;
    @Autowired OrderIncreaseService increases;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrderRepository orders;
    @Autowired UserRepository users;
    @Autowired CategoryRepository categories;
    @Autowired TaxCategoryRepository taxes;
    @Autowired ProductRepository products;
    @Autowired PaymentRepository payments;
    @Autowired PaymentTransactionRepository transactions;
    @Autowired com.example.ecsite.service.OrderStatusHistoryService histories;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean PaymentGateway gateway;

    record Fixture(long order,long user,long item,long product,long oldPayment,String oldFlow){}
    <T>T tx(java.util.function.Supplier<T> body){return new TransactionTemplate(manager).execute(s->body.get());}
    /** LEGACY order whose deadline is one hour ahead. */
    Fixture fixture(){return tx(()->{
        var now=orders.findCurrentTimeInTokyo();String key=UUID.randomUUID().toString();
        var user=new User();user.setUsername("f128b-"+key);user.setPassword("unused");user.setEnabled(true);user.setCreatedAt(now);user.setUpdatedAt(now);users.saveAndFlush(user);
        var category=categories.saveAndFlush(new Category(key));var tax=taxes.findAll().getFirst();
        var product=new Product();product.setName("item");product.setPrice(900);product.setStock(20);product.setCategory(category);product.setTaxCategory(tax);products.saveAndFlush(product);
        int price=100,quantity=2,total=price*quantity+550;var order=new Order(user.getId(),total,now,now.plusHours(1));
        order.addItem(new OrderItem(product.getId(),"ordered",category.getId(),category.getName(),tax.getId(),tax.getCode(),tax.getName(),tax.getTaxRate(),price,quantity));
        order.addCharge(new OrderCharge(OrderChargeType.SHIPPING,"送料・梱包料",550,tax.getId(),tax.getCode(),tax.getName(),tax.getTaxRate(),10));orders.saveAndFlush(order);
        histories.record(order,null,OrderStatus.ORDERED,OrderStatusHistoryActorType.USER,user.getId(),user.getUsername(),null);
        var payment=new Payment(order,PaymentProvider.PAYJP,PaymentMethod.CARD,total,now);payment.setProviderPaymentId("pfw_"+key,now);payment.markAuthorized(now);payments.saveAndFlush(payment);
        var auth=new PaymentTransaction(payment,PaymentTransactionType.AUTHORIZE,total,0,key,now,PaymentTransactionPurpose.INITIAL_ORDER_AUTHORIZATION);auth.markSucceeded(payment.getProviderPaymentId(),now);transactions.saveAndFlush(auth);
        return new Fixture(order.getId(),user.getId(),order.getItems().getFirst().getId(),product.getId(),payment.getId(),payment.getProviderPaymentId());
    });}
    OrderChangeStore.Operation accept(Fixture f){
        int revision=jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order());
        return increases.accept(new OrderIncreaseService.Request(f.order(),f.user(),UUID.randomUUID(),revision,Map.of(f.item(),3)));
    }
    /** Places the database clock {@code seconds} after the change deadline (deadline was one hour ahead). */
    void atDeadlinePlus(Fixture f,OrderChangeStore.Operation op,int seconds){tx(()->{
        jdbc.update("""
            update orders set change_deadline_at=timezone('Asia/Tokyo',clock_timestamp())-make_interval(secs=>?),
              ordered_at=least(ordered_at,timezone('Asia/Tokyo',clock_timestamp())-make_interval(secs=>?)-interval '1 hour') where id=?
            """,seconds,seconds,f.order());
        var deadline=jdbc.queryForObject("select change_deadline_at from orders where id=?",java.time.LocalDateTime.class,f.order());
        jdbc.update("update order_change_operations set accepted_at=?,deadline_at=?,grace_until=? where id=?",
            deadline.minusMinutes(30),deadline,deadline.plusMinutes(10),op.id());
        return null;
    });}

    final AtomicReference<String> newState=new AtomicReference<>("requires_payment_method");
    final AtomicBoolean oldCancelled=new AtomicBoolean();
    String newFlow(OrderChangeStore.Operation op){return "pfw_new_"+op.id();}
    PayJpPaymentFlowObservation observation(String flow,String status,int amount,Map<String,Object> metadata){
        var fields=new LinkedHashMap<String,Object>();
        fields.put("id",flow);fields.put("status",status);fields.put("amount",amount);fields.put("currency","jpy");
        fields.put("capture_method","manual");fields.put("livemode",false);fields.put("metadata",metadata);
        fields.put("amount_capturable",status.equals("requires_capture")?Long.valueOf(amount):status.equals("canceled")?0L:null);fields.put("amount_received",0L);
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
        var raw=mapper.readTree(mapper.writeValueAsString(fields));
        return mapper.treeToValue(raw,PayJpPaymentFlowObservation.class).withOldCancellationEvidence(
            com.example.ecsite.payment.payjp.OldAuthorizationCancellationEvidence.fromJson(raw,
                new com.example.ecsite.payment.capture.CaptureConnectionIdentity("test-capture","test-account","TEST","https://api.pay.jp")));
    }
    Map<String,Object> metadata(Fixture f,OrderChangeStore.Operation op){return Map.of("order_id",Long.toString(f.order()),"order_change_operation_id",op.id().toString());}
    PayJpPaymentFlowObservation newObserved(Fixture f,OrderChangeStore.Operation op,String status){return observation(newFlow(op),status,op.afterAmount(),metadata(f,op));}
    PayJpPaymentFlowObservation oldObserved(Fixture f,OrderChangeStore.Operation op){return observation(f.oldFlow(),oldCancelled.get()?"canceled":"requires_capture",op.beforeAmount(),Map.of());}
    void mockFlow(Fixture f,OrderChangeStore.Operation op){
        reset(gateway);newState.set("requires_payment_method");oldCancelled.set(false);
        when(gateway.sendOrderChangeRequest(any())).thenAnswer(call->{
            var saved=((OrderChangeRequestSender.Dispatch)call.getArgument(0)).request();
            return switch(saved.httpRequest().kind()){
                case "CREATE"->observation(newFlow(op),"requires_payment_method",op.afterAmount(),Map.of());
                case "OLD_CANCEL"->{oldCancelled.set(true);yield oldObserved(f,op);}
                case "NEW_ABORT"->{newState.set("canceled");yield newObserved(f,op,"canceled");}
                default->throw new AssertionError();
            };
        });
        when(gateway.observeOrderChangeFlow(anyString())).thenAnswer(call->{
            String flow=call.getArgument(0);
            if(flow.equals(f.oldFlow()))return oldObserved(f,op);
            return newObserved(f,op,newState.get());
        });
    }
    /** CREATE started before the deadline; the browser authorization has not completed. */
    void createStarted(Fixture f,OrderChangeStore.Operation op){
        mockFlow(f,op);assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("AUTHORIZING");
    }
    void authorized(Fixture f,OrderChangeStore.Operation op){
        createStarted(f,op);
        assertThat(results.authorization(f.order(),op.id(),newObserved(f,op,"requires_capture")).state()).isEqualTo("AUTHORIZED");
        newState.set("requires_capture");
    }
    String state(UUID op){return jdbc.queryForObject("select state from order_change_operations where id=?",String.class,op);}
    Map<String,Object> decision(UUID op){return jdbc.queryForMap("select * from order_change_final_decisions where operation_id=?",op);}
    int decisionCount(UUID op){return jdbc.queryForObject("select count(*) from order_change_final_decisions where operation_id=?",Integer.class,op);}
    int orderTotal(Fixture f){return jdbc.queryForObject("select total_amount from orders where id=?",Integer.class,f.order());}

    @Test void reservedChangeWithoutPaymentWorkIsNotAdoptedAndReleasedAt1405()throws Exception{
        var f=fixture();var op=accept(f);mockFlow(f,op);atDeadlinePlus(f,op,330);

        assertThat(decisions.finalizeDue(1000)).isPositive();

        assertThat(decision(op.id())).containsEntry("outcome","NOT_ADOPTED").containsEntry("shipping_disposition","ORIGINAL")
            .containsEntry("reason_code","NO_REAUTHORIZATION_STARTED").containsEntry("operation_state","RESERVED");
        var decided=decision(op.id());
        assertThat(((java.sql.Timestamp)decided.get("decided_at")).toLocalDateTime()).isAfterOrEqualTo(((java.sql.Timestamp)decided.get("final_at")).toLocalDateTime());
        assertThat(state(op.id())).isEqualTo("ABORTED");
        assertThat(jdbc.queryForList("select state from inventory_reservations where operation_id=?",String.class,op.id())).containsOnly("RELEASED");
        verify(gateway,never()).sendOrderChangeRequest(any());
        assertThat(results.finalizeAtDeadline(f.order(),op.id())).isEqualTo(OrderReauthorizationTransactions.FinalDecisionResult.SETTLED);
        assertThat(decisionCount(op.id())).isEqualTo(1);
        assertThat(increases.status(f.order(),f.user(),op.id()).finalOutcome()).isEqualTo("NOT_ADOPTED");
    }

    @Test void incompleteAuthorizationIsNotAdoptedAndALateSuccessIsCancelledInsteadOfSwitched()throws Exception{
        var f=fixture();var op=accept(f);createStarted(f,op);atDeadlinePlus(f,op,330);

        assertThat(results.finalizeAtDeadline(f.order(),op.id())).isEqualTo(OrderReauthorizationTransactions.FinalDecisionResult.DECIDED);
        assertThat(results.finalizeAtDeadline(f.order(),op.id())).isEqualTo(OrderReauthorizationTransactions.FinalDecisionResult.ALREADY_DECIDED);
        assertThat(decision(op.id())).containsEntry("outcome","NOT_ADOPTED").containsEntry("shipping_disposition","HOLD")
            .containsEntry("reason_code","AUTHORIZATION_INCOMPLETE");
        assertThat(state(op.id())).isEqualTo("AUTHORIZING");

        // The browser authorization succeeds after 14:05: it is recorded but the change is never switched.
        assertThat(results.authorization(f.order(),op.id(),newObserved(f,op,"requires_capture")).state()).isEqualTo("AUTHORIZED");
        newState.set("requires_capture");
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("ABORTED");

        assertThat(oldCancelled).isFalse();
        assertThat(jdbc.queryForObject("select switch_decided_at from order_change_operations where id=?",java.time.LocalDateTime.class,op.id())).isNull();
        assertThat(jdbc.queryForObject("select status from payments where id=?",String.class,f.oldPayment())).isEqualTo("AUTHORIZED");
        assertThat(jdbc.queryForObject("select active_payment_id from orders where id=?",Long.class,f.order())).isEqualTo(f.oldPayment());
        assertThat(orderTotal(f)).isEqualTo(op.beforeAmount());
        assertThat(jdbc.queryForObject("select count(*) from order_change_financial_requests where operation_id=? and request_kind='NEW_ABORT'",Integer.class,op.id())).isEqualTo(1);
        assertThat(decisionCount(op.id())).isEqualTo(1);
    }

    @Test void switchIsNotDecidedAfter1404EvenBeforeTheFinalDecision()throws Exception{
        var f=fixture();var op=accept(f);authorized(f,op);atDeadlinePlus(f,op,270);

        assertThat(results.finalizeAtDeadline(f.order(),op.id())).isEqualTo(OrderReauthorizationTransactions.FinalDecisionResult.NOT_DUE);
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("ABORTED");

        assertThat(oldCancelled).isFalse();
        assertThat(orderTotal(f)).isEqualTo(op.beforeAmount());
        assertThat(jdbc.queryForObject("select switch_decided_at from order_change_operations where id=?",java.time.LocalDateTime.class,op.id())).isNull();
        assertThat(decisionCount(op.id())).isZero();
    }

    @Test void databaseRejectsASwitchDecisionAfter1404()throws Exception{
        var f=fixture();var op=accept(f);authorized(f,op);atDeadlinePlus(f,op,270);
        assertThatThrownBy(()->jdbc.update("update order_change_operations set switch_decided_at=timezone('Asia/Tokyo',clock_timestamp()) where id=?",op.id()))
            .satisfies(e->assertThat(OrderChangeFinancialRequestStore.isDeadlineViolation(e)).as("SQLSTATE EC128: %s",e).isTrue());
    }

    @Test void decidedButIncompleteSwitchIsALateAdoptionThatCanOnlyMoveForward()throws Exception{
        var f=fixture();var op=accept(f);authorized(f,op);
        assertThat(results.decide(f.order(),op.id(),newObserved(f,op,"requires_capture"),oldObserved(f,op)).state()).isEqualTo("OLD_CANCEL_PENDING");
        atDeadlinePlus(f,op,330);

        assertThat(results.finalizeAtDeadline(f.order(),op.id())).isEqualTo(OrderReauthorizationTransactions.FinalDecisionResult.DECIDED);
        assertThat(decision(op.id())).containsEntry("outcome","LATE_ADOPTION").containsEntry("shipping_disposition","HOLD")
            .containsEntry("reason_code","SWITCH_INCOMPLETE").containsEntry("operation_state","OLD_CANCEL_PENDING");
        assertThat(increases.status(f.order(),f.user(),op.id()).finalOutcome()).isEqualTo("LATE_ADOPTION");

        // Reconciliation/webhook/customer progress completes the change after 14:05.
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("COMPLETED");
        assertThat(orderTotal(f)).isEqualTo(op.afterAmount());
        assertThat(jdbc.queryForObject("select status from payments where id=?",String.class,f.oldPayment())).isEqualTo("CANCELLED");
        assertThat(decisionCount(op.id())).isEqualTo(1);
    }

    @Test void unresolvedReviewIsHeldAndNotAdopted()throws Exception{
        var f=fixture();var op=accept(f);createStarted(f,op);
        assertThat(results.authorization(f.order(),op.id(),observation(newFlow(op),"requires_capture",op.afterAmount(),Map.of())).state()).isEqualTo("MANUAL_REVIEW");
        atDeadlinePlus(f,op,330);

        assertThat(results.finalizeAtDeadline(f.order(),op.id())).isEqualTo(OrderReauthorizationTransactions.FinalDecisionResult.DECIDED);
        assertThat(decision(op.id())).containsEntry("outcome","NOT_ADOPTED").containsEntry("shipping_disposition","HOLD")
            .containsEntry("reason_code","PAYMENT_STATE_UNRESOLVED").containsEntry("operation_state","MANUAL_REVIEW");
        assertThat(state(op.id())).isEqualTo("MANUAL_REVIEW");
    }

    @Test void finalDecisionsAreDueOnlyAfter1405AndAppendOnly()throws Exception{
        var f=fixture();var op=accept(f);createStarted(f,op);
        String insert="""
            insert into order_change_final_decisions(operation_id,order_id,outcome,shipping_disposition,reason_code,deadline_at,final_at,
              operation_state,operation_version,switch_decided_at,decided_at)
            select o.id,o.order_id,'NOT_ADOPTED',?,'TEST',o.deadline_at,o.deadline_at+interval '5 minutes',o.state,o.version,null,o.deadline_at+interval '5 minutes'
            from order_change_operations o where o.id=?
            """;
        atDeadlinePlus(f,op,270);
        assertThatThrownBy(()->jdbc.update(insert,"HOLD",op.id())).hasMessageContaining("not due");
        atDeadlinePlus(f,op,330);
        assertThatThrownBy(()->jdbc.update(insert,"ORIGINAL",op.id())).hasMessageContaining("shipping hold");

        results.finalizeAtDeadline(f.order(),op.id());
        assertThatThrownBy(()->jdbc.update("update order_change_final_decisions set shipping_disposition='ORIGINAL' where operation_id=?",op.id())).hasMessageContaining("append-only");
        assertThatThrownBy(()->jdbc.update("delete from order_change_final_decisions where operation_id=?",op.id())).hasMessageContaining("append-only");
    }
}
