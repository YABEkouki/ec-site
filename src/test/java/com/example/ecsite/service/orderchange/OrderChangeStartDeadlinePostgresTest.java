package com.example.ecsite.service.orderchange;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
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
 * Feature128 A-1: no order-change reauthorization starts at or after the change deadline.
 * The database guards use the real database clock, so each test places the LEGACY order deadline a
 * moment ahead of clock_timestamp() and waits for it to pass instead of mocking time.
 */
@SpringBootTest(properties={"logging.level.root=WARN","spring.jpa.show-sql=false"})
@com.example.ecsite.support.CaptureConnectionTest
class OrderChangeStartDeadlinePostgresTest {
    @Autowired OrderReauthorizationService service;
    @Autowired OrderReauthorizationTransactions results;
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
    @MockitoSpyBean OrderChangeFinancialRequestStore requestStore;

    record Fixture(long order,long user,long item,long product,long oldPayment,String oldFlow){}
    <T>T tx(java.util.function.Supplier<T> body){return new TransactionTemplate(manager).execute(s->body.get());}

    /** LEGACY order whose deadline is {@code millis} after the database clock. */
    Fixture fixture(long millis){return tx(()->{
        var now=orders.findCurrentTimeInTokyo();String key=UUID.randomUUID().toString();
        var user=new User();user.setUsername("f128-"+key);user.setPassword("unused");user.setEnabled(true);user.setCreatedAt(now);user.setUpdatedAt(now);users.saveAndFlush(user);
        var category=categories.saveAndFlush(new Category(key));var tax=taxes.findAll().getFirst();
        var product=new Product();product.setName("item");product.setPrice(900);product.setStock(20);product.setCategory(category);product.setTaxCategory(tax);products.saveAndFlush(product);
        int price=100,quantity=2,total=price*quantity+550;var order=new Order(user.getId(),total,now,now.plusHours(1));
        order.addItem(new OrderItem(product.getId(),"ordered",category.getId(),category.getName(),tax.getId(),tax.getCode(),tax.getName(),tax.getTaxRate(),price,quantity));
        order.addCharge(new OrderCharge(OrderChargeType.SHIPPING,"送料・梱包料",550,tax.getId(),tax.getCode(),tax.getName(),tax.getTaxRate(),10));orders.saveAndFlush(order);
        histories.record(order,null,OrderStatus.ORDERED,OrderStatusHistoryActorType.USER,user.getId(),user.getUsername(),null);
        var payment=new Payment(order,PaymentProvider.PAYJP,PaymentMethod.CARD,total,now);payment.setProviderPaymentId("pfw_"+key,now);payment.markAuthorized(now);payments.saveAndFlush(payment);
        var auth=new PaymentTransaction(payment,PaymentTransactionType.AUTHORIZE,total,0,key,now,PaymentTransactionPurpose.INITIAL_ORDER_AUTHORIZATION);auth.markSucceeded(payment.getProviderPaymentId(),now);transactions.saveAndFlush(auth);
        jdbc.update("update orders set change_deadline_at=timezone('Asia/Tokyo',clock_timestamp())+make_interval(secs=>?) where id=?",millis/1000.0,order.getId());
        return new Fixture(order.getId(),user.getId(),order.getItems().getFirst().getId(),product.getId(),payment.getId(),payment.getProviderPaymentId());
    });}
    OrderChangeStore.Operation accept(Fixture f){
        int revision=jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order());
        return increases.accept(new OrderIncreaseService.Request(f.order(),f.user(),UUID.randomUUID(),revision,Map.of(f.item(),3)));
    }
    void awaitDeadline(Fixture f)throws InterruptedException{
        for(int i=0;i<200;i++){
            if(Boolean.TRUE.equals(jdbc.queryForObject("select timezone('Asia/Tokyo',clock_timestamp())>=change_deadline_at from orders where id=?",Boolean.class,f.order())))return;
            Thread.sleep(50);
        }
        throw new AssertionError("Deadline did not pass");
    }
    String newFlow(OrderChangeStore.Operation op){return "pfw_new_"+op.id();}
    PayJpPaymentFlowObservation observation(String flow,String status,int amount,Map<String,Object> metadata){
        var fields=new LinkedHashMap<String,Object>();
        fields.put("id",flow);fields.put("status",status);fields.put("amount",amount);fields.put("currency","jpy");
        fields.put("capture_method","manual");fields.put("livemode",false);fields.put("metadata",metadata);
        fields.put("amount_capturable",status.equals("requires_capture")?Long.valueOf(amount):null);fields.put("amount_received",0L);
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
        return mapper.treeToValue(mapper.readTree(mapper.writeValueAsString(fields)),PayJpPaymentFlowObservation.class);
    }
    Map<String,Object> metadata(Fixture f,OrderChangeStore.Operation op){return Map.of("order_id",Long.toString(f.order()),"order_change_operation_id",op.id().toString());}
    void mockCreate(Fixture f,OrderChangeStore.Operation op){
        when(gateway.sendOrderChangeRequest(any())).thenAnswer(call->{
            var saved=((OrderChangeRequestSender.Dispatch)call.getArgument(0)).request();
            assertThat(saved.httpRequest().kind()).isEqualTo("CREATE");
            return observation(newFlow(op),"requires_payment_method",op.afterAmount(),Map.of());
        });
        when(gateway.observeOrderChangeFlow(anyString())).thenAnswer(call->{
            String flow=call.getArgument(0);
            if(flow.equals(f.oldFlow()))return observation(flow,"requires_capture",op.beforeAmount(),Map.of());
            return observation(flow,"requires_payment_method",op.afterAmount(),metadata(f,op));
        });
    }
    String state(UUID op){return jdbc.queryForObject("select state from order_change_operations where id=?",String.class,op);}
    Map<String,Object> closure(UUID op){return jdbc.queryForMap("select * from order_change_deadline_closures where operation_id=?",op);}
    long createRequest(UUID op){return jdbc.queryForObject("select id from order_change_financial_requests where operation_id=? and request_kind='CREATE'",Long.class,op);}
    String dispatch(UUID op){return jdbc.queryForObject("select d.state from order_change_request_dispatches d join order_change_financial_requests r on r.id=d.request_id where r.operation_id=? and r.request_kind='CREATE'",String.class,op);}
    void assertDeadlineRejected(ThrowingConsumer body){
        assertThatThrownBy(body::run).satisfies(e->assertThat(OrderChangeFinancialRequestStore.isDeadlineViolation(e)).as("SQLSTATE EC128: %s",e).isTrue());
    }
    interface ThrowingConsumer{void run()throws Exception;}

    @AfterEach void clearMocks(){reset(gateway);}

    @Test void reservedChangeIsClosedBeforeAnyFinancialWorkOnceTheDeadlinePasses()throws Exception{
        var f=fixture(1500);var op=accept(f);mockCreate(f,op);
        int stockBefore=jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product());
        awaitDeadline(f);

        var view=service.advance(f.order(),op.id());

        assertThat(view.state()).isEqualTo("ABORTED");assertThat(view.createRequest()).isNull();
        verify(gateway,never()).sendOrderChangeRequest(any());
        assertThat(jdbc.queryForObject("select count(*) from payments where order_id=?",Integer.class,f.order())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from payment_transactions where order_change_operation_id=?",Integer.class,op.id())).isZero();
        assertThat(jdbc.queryForList("select state from inventory_reservations where operation_id=?",String.class,op.id())).containsOnly("RELEASED");
        assertThat(jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product())).isEqualTo(stockBefore+1);
        var closure=closure(op.id());
        assertThat(closure.get("phase")).isEqualTo("BEFORE_CREATE_REQUEST");assertThat(closure.get("operation_state")).isEqualTo("RESERVED");
        assertThat(((java.sql.Timestamp)closure.get("closed_at")).toLocalDateTime()).isAfterOrEqualTo(((java.sql.Timestamp)closure.get("deadline_at")).toLocalDateTime());
        assertThat(increases.status(f.order(),f.user(),op.id()).deadlineClosed()).isTrue();
        // Idempotent: a repeated customer, webhook or worker advance never reopens the change.
        assertThat(service.advance(f.order(),op.id()).state()).isEqualTo("ABORTED");
        assertThat(jdbc.queryForObject("select count(*) from order_change_deadline_closures where operation_id=?",Integer.class,op.id())).isEqualTo(1);
        verify(gateway,never()).sendOrderChangeRequest(any());
    }

    @Test void preparedCreateWhosePermitWasNeverConsumedIsClosedWithoutAProviderCall()throws Exception{
        var f=fixture(1500);var op=accept(f);mockCreate(f,op);
        assertThat(results.prepare(f.order(),op.id()).state()).isEqualTo("AUTHORIZING");
        assertThat(dispatch(op.id())).isEqualTo("READY");
        awaitDeadline(f);

        var view=service.advance(f.order(),op.id());

        assertThat(view.state()).isEqualTo("ABORTED");
        verify(gateway,never()).sendOrderChangeRequest(any());
        assertThat(dispatch(op.id())).isEqualTo("READY");
        long next=jdbc.queryForObject("select new_payment_id from order_change_operations where id=?",Long.class,op.id());
        assertThat(jdbc.queryForObject("select status from payments where id=?",String.class,next)).isEqualTo("FAILED");
        assertThat(jdbc.queryForMap("select status,failure_code,provider_transaction_id from payment_transactions where order_change_operation_id=? and purpose='ORDER_CHANGE_AUTHORIZATION'",op.id()))
            .containsEntry("status","FAILED").containsEntry("failure_code","NOT_SENT_AFTER_DEADLINE").containsEntry("provider_transaction_id",null);
        assertThat(jdbc.queryForObject("select status from payments where id=?",String.class,f.oldPayment())).isEqualTo("AUTHORIZED");
        assertThat(jdbc.queryForObject("select active_payment_id from orders where id=?",Long.class,f.order())).isEqualTo(f.oldPayment());
        assertThat(closure(op.id()).get("phase")).isEqualTo("CREATE_NOT_STARTED");
        // The permit can never be consumed afterwards, even by direct SQL.
        assertDeadlineRejected(()->jdbc.update("update order_change_request_dispatches set state='STARTED',started_at=clock_timestamp() where request_id=?",createRequest(op.id())));
    }

    @Test void commitAfterTheDeadlineRollsBackAStartedPermitAndNoPostIsSent()throws Exception{
        var f=fixture(1500);var op=accept(f);mockCreate(f,op);
        results.prepare(f.order(),op.id());
        // The row-level check passes before the deadline; the deferred check at commit runs after it.
        // Stubbing passes through the store's MANDATORY transaction boundary, so it runs inside one.
        tx(()->{doAnswer(call->{var saved=call.callRealMethod();awaitDeadline(f);return saved;}).when(requestStore).findForDispatch(anyLong());return null;});

        var view=service.advance(f.order(),op.id());

        assertThat(view.state()).isEqualTo("ABORTED");
        verify(gateway,never()).sendOrderChangeRequest(any());
        assertThat(dispatch(op.id())).isEqualTo("READY");
        assertThat(closure(op.id()).get("phase")).isEqualTo("CREATE_NOT_STARTED");
    }

    @Test void deferredGuardRejectsAStartCommittedAtOrAfterTheDeadline()throws Exception{
        var f=fixture(1500);var op=accept(f);mockCreate(f,op);
        results.prepare(f.order(),op.id());long request=createRequest(op.id());
        assertDeadlineRejected(()->new TransactionTemplate(manager).executeWithoutResult(s->{
            assertThat(jdbc.update("update order_change_request_dispatches set state='STARTED',started_at=clock_timestamp() where request_id=?",request)).isEqualTo(1);
            try{awaitDeadline(f);}catch(InterruptedException e){throw new IllegalStateException(e);}
        }));
        assertThat(dispatch(op.id())).isEqualTo("READY");
    }

    @Test void createStartedBeforeTheDeadlineMayStillReportItsResultButNoConfirmationIsIssuedAfterIt()throws Exception{
        var f=fixture(3000);var op=accept(f);mockCreate(f,op);
        var view=service.advance(f.order(),op.id());
        assertThat(view.state()).isEqualTo("AUTHORIZING");assertThat(view.newFlow()).isEqualTo(newFlow(op));
        assertThat(dispatch(op.id())).isIn("STARTED","OBSERVED");
        awaitDeadline(f);

        assertThat(results.issueBrowserConfirmation(f.order(),op.id(),observation(newFlow(op),"requires_payment_method",op.afterAmount(),metadata(f,op)))).isFalse();
        assertThat(jdbc.queryForObject("select browser_confirmation_issued_at from order_change_operations where id=?",java.time.LocalDateTime.class,op.id())).isNull();
        assertDeadlineRejected(()->jdbc.update("update order_change_operations set browser_confirmation_issued_at=timezone('Asia/Tokyo',clock_timestamp()) where id=?",op.id()));
        // A POST started before the deadline is awaited (the 14:05 decision is A-2): its result is still applied.
        var authorized=results.authorization(f.order(),op.id(),observation(newFlow(op),"requires_capture",op.afterAmount(),metadata(f,op)));
        assertThat(authorized.state()).isEqualTo("AUTHORIZED");
        assertThat(jdbc.queryForObject("select count(*) from order_change_deadline_closures where operation_id=?",Integer.class,op.id())).isZero();
    }

    @Test void confirmationIsIssuedBeforeTheDeadline()throws Exception{
        var f=fixture(60_000);var op=accept(f);mockCreate(f,op);
        service.advance(f.order(),op.id());
        assertThat(results.issueBrowserConfirmation(f.order(),op.id(),observation(newFlow(op),"requires_payment_method",op.afterAmount(),metadata(f,op)))).isTrue();
    }

    @Test void deadlineClosuresAreAppendOnlyAndRequireAPassedDeadline()throws Exception{
        var open=fixture(60_000);var pending=accept(open);
        assertThatThrownBy(()->tx(()->jdbc.update("""
            insert into order_change_deadline_closures(operation_id,order_id,phase,deadline_at,operation_state,operation_version,closed_at)
            select id,order_id,'BEFORE_CREATE_REQUEST',deadline_at,state,version,deadline_at from order_change_operations where id=?
            """,pending.id()))).hasMessageContaining("does not match");

        var f=fixture(1500);var op=accept(f);mockCreate(f,op);awaitDeadline(f);service.advance(f.order(),op.id());
        assertThatThrownBy(()->jdbc.update("update order_change_deadline_closures set phase='CREATE_NOT_STARTED' where operation_id=?",op.id())).hasMessageContaining("append-only");
        assertThatThrownBy(()->jdbc.update("delete from order_change_deadline_closures where operation_id=?",op.id())).hasMessageContaining("append-only");
    }
}
