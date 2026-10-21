package com.example.ecsite.service.orderchange;

import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.repository.*;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.service.OrderStatusHistoryService;
import com.example.ecsite.service.payment.OrderPaymentGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"logging.level.root=WARN","spring.jpa.show-sql=false"})
@ActiveProfiles("test")
@com.example.ecsite.support.CaptureConnectionTest
class OrderIncreasePostgresTest {
    @Autowired OrderIncreaseService service;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean OrderRepository orders;
    @Autowired UserRepository users;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired TaxCategoryRepository taxes;
    @Autowired PaymentRepository payments;
    @Autowired PaymentTransactionRepository transactions;
    @Autowired OrderStatusHistoryService histories;
    @Autowired OrderPaymentGuard guard;
    @Autowired com.example.ecsite.service.payment.PaymentService paymentService;
    @Autowired com.example.ecsite.service.payment.PaymentCancellationStartService cancellation;
    @Autowired com.example.ecsite.service.OrderShippingPreparationService preparation;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.example.ecsite.service.InventoryService inventory;
    @Autowired com.example.ecsite.service.OrderService orderService;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PaymentGateway gateway;
    <T>T tx(Supplier<T> work){return new TransactionTemplate(manager).execute(s->work.get());}
    record Fixture(long order,long user,long item,long product){}
    Fixture fixture(){return tx(()->{
        var now=orders.findCurrentTimeInTokyo();String key=UUID.randomUUID().toString();
        var user=new User();user.setUsername("increase-"+key);user.setPassword("password");user.setEnabled(true);user.setCreatedAt(now);user.setUpdatedAt(now);users.saveAndFlush(user);
        var category=categories.saveAndFlush(new Category(key));var tax=taxes.findAll().getFirst();
        var product=new Product();product.setName("item");product.setPrice(900);product.setStock(10);product.setCategory(category);product.setTaxCategory(tax);products.saveAndFlush(product);
        var order=new Order(user.getId(),750,now,now.plusHours(1));
        order.addItem(new OrderItem(product.getId(),"ordered item",category.getId(),category.getName(),tax.getId(),tax.getCode(),tax.getName(),tax.getTaxRate(),100,2));
        order.addCharge(new OrderCharge(OrderChargeType.SHIPPING,"送料・梱包料",550,tax.getId(),tax.getCode(),tax.getName(),tax.getTaxRate(),10));orders.saveAndFlush(order);
        histories.record(order,null,OrderStatus.ORDERED,OrderStatusHistoryActorType.USER,user.getId(),user.getUsername(),null);
        var payment=new Payment(order,PaymentProvider.PAYJP,PaymentMethod.CARD,750,now);payment.setProviderPaymentId("pfw_"+key,now);payment.markAuthorized(now);payments.saveAndFlush(payment);
        var auth=new PaymentTransaction(payment,PaymentTransactionType.AUTHORIZE,750,0,key,now);auth.markSucceeded(payment.getProviderPaymentId(),now);transactions.saveAndFlush(auth);
        return new Fixture(order.getId(),user.getId(),order.getItems().getFirst().getId(),product.getId());
    });}
    OrderIncreaseService.Request request(Fixture f,UUID key,int quantity){return new OrderIncreaseService.Request(f.order(),f.user(),key,0,Map.of(f.item(),quantity));}
    int stock(Fixture f){return jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product());}
    @Test void reservesOnlyIncreaseAndKeepsOriginalOrder(){
        var f=fixture();var before=jdbc.queryForMap("select total_amount,item_subtotal,charge_total,tax_amount,content_revision from orders where id=?",f.order());
        var r=service.accept(request(f,UUID.randomUUID(),5));
        assertThat(r.state()).isEqualTo("RESERVED");assertThat(r.afterAmount()).isEqualTo(1050);
        assertThat(stock(f)).isEqualTo(7);
        assertThat(jdbc.queryForMap("select total_amount,item_subtotal,charge_total,tax_amount,content_revision from orders where id=?",f.order())).isEqualTo(before);
        assertThat(jdbc.queryForObject("select quantity from order_items where id=?",Integer.class,f.item())).isEqualTo(2);
        verifyNoInteractions(gateway);
    }
    @Test void replayDoesNotReserveAgainAndChangedRequestConflicts(){
        var f=fixture();var key=UUID.randomUUID();var req=request(f,key,4);
        var first=service.accept(req);assertThat(service.accept(req)).isEqualTo(first);assertThat(stock(f)).isEqualTo(8);
        assertThatThrownBy(()->service.accept(request(f,key,5))).isInstanceOf(IllegalStateException.class);
    }
    @Test void stockFailureRollsBackAllWrites(){
        var f=fixture();assertThatThrownBy(()->service.accept(request(f,UUID.randomUUID(),50))).isInstanceOf(RuntimeException.class);
        assertThat(stock(f)).isEqualTo(10);
        assertThat(jdbc.queryForObject("select count(*) from order_change_operations where order_id=?",Long.class,f.order())).isZero();
        assertThat(jdbc.queryForObject("select active_payment_id from orders where id=?",Long.class,f.order())).isNull();
    }
    @Test void releaseIsIdempotentAndAllowsNewSubmission(){
        var f=fixture();var req=request(f,UUID.randomUUID(),5);var accepted=service.accept(req);
        service.abort(f.order(),f.user(),accepted.id());service.abort(f.order(),f.user(),accepted.id());
        assertThat(stock(f)).isEqualTo(10);assertThat(service.accept(req).state()).isEqualTo("ABORTED");
        assertThat(service.accept(request(f,UUID.randomUUID(),4)).state()).isEqualTo("RESERVED");
    }
    @Test void unresolvedCandidateBlocksCompetingMutations(){
        var f=fixture();service.accept(request(f,UUID.randomUUID(),3));
        assertThatThrownBy(()->service.accept(request(f,UUID.randomUUID(),4))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->tx(()->{guard.validateItemChange(orders.findByIdForUpdate(f.order()).orElseThrow());return null;})).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->tx(()->{guard.validateLocalCancellation(orders.findByIdForUpdate(f.order()).orElseThrow());return null;})).isInstanceOf(RuntimeException.class);
    }
    @Test void unsupportedChangesAndOtherCustomersAreRejected(){
        var f=fixture();
        assertThatThrownBy(()->service.accept(request(f,UUID.randomUUID(),2))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.accept(new OrderIncreaseService.Request(f.order(),f.user(),UUID.randomUUID(),0,Map.of(Long.MAX_VALUE,3)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.accept(new OrderIncreaseService.Request(f.order(),f.user()+10000,UUID.randomUUID(),0,Map.of(f.item(),3)))).isInstanceOf(RuntimeException.class);
        assertThat(stock(f)).isEqualTo(10);
    }
    @Test void concurrentReplayHasOneReservation() throws Exception {
        var f=fixture();var req=request(f,UUID.randomUUID(),4);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var go=new java.util.concurrent.CountDownLatch(1);
        try{var a=pool.submit(()->{go.await();return service.accept(req);});var b=pool.submit(()->{go.await();return service.accept(req);});go.countDown();
            assertThat(a.get(20,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(b.get(20,java.util.concurrent.TimeUnit.SECONDS));assertThat(stock(f)).isEqualTo(8);
            assertThat(jdbc.queryForObject("select count(*) from inventory_reservations where product_id=?",Long.class,f.product())).isEqualTo(1);
        }finally{go.countDown();pool.shutdownNow();}
    }
    @Test void reservationExpiryUsesGraceBoundaryAndCannotUndoDecision(){
        var f=fixture();var operation=service.accept(request(f,UUID.randomUUID(),4));
        doReturn(operation.graceUntil().minusNanos(1)).when(orders).findCurrentTimeInTokyo();
        assertThat(service.expireReserved(f.order(),operation.id())).isFalse();
        doReturn(operation.graceUntil()).when(orders).findCurrentTimeInTokyo();
        assertThat(service.expireReserved(f.order(),operation.id())).isTrue();assertThat(stock(f)).isEqualTo(10);
    }
    @Test void decidedOperationCannotExpireOrAbort(){
        var f=fixture();var operation=service.accept(request(f,UUID.randomUUID(),4));
        jdbc.update("update order_change_operations set state='OLD_CANCEL_PENDING',switch_decided_at=accepted_at where id=?",operation.id());
        doReturn(operation.graceUntil().plusHours(1)).when(orders).findCurrentTimeInTokyo();
        assertThat(service.expireReserved(f.order(),operation.id())).isFalse();
        assertThatThrownBy(()->service.abort(f.order(),f.user(),operation.id())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->jdbc.update("update order_change_operations set switch_decided_at=null where id=?",operation.id())).isInstanceOf(RuntimeException.class);
        assertThat(stock(f)).isEqualTo(8);
    }
    @Test void refreshesStaleProductBeforeReservationRelease(){
        var f=fixture();var operation=service.accept(request(f,UUID.randomUUID(),4));
        tx(()->{var stale=products.findById(f.product()).orElseThrow();assertThat(stale.getStock()).isEqualTo(8);
            // A different transaction has committed an independent inventory adjustment.
            var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
            try{pool.submit(()->jdbc.update("update products set stock=stock+5 where id=?",f.product())).get(10,java.util.concurrent.TimeUnit.SECONDS);}
            catch(Exception e){throw new RuntimeException(e);}finally{pool.shutdownNow();}
            service.abort(f.order(),f.user(),operation.id());return null;});
        assertThat(stock(f)).isEqualTo(15);
    }
    @org.junit.jupiter.api.AfterEach void clearSecurity(){org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    @Test void realStartPathsAndAddressChangeAreGuarded(){
        var f=fixture();service.accept(request(f,UUID.randomUUID(),3));
        assertThatThrownBy(()->cancellation.start(f.order(),f.user(),"customer")).hasMessageContaining("未解決");
        assertThatThrownBy(()->tx(()->paymentService.startAuthorization(orders.findByIdForUpdate(f.order()).orElseThrow()))).hasMessageContaining("未解決");
        assertThatThrownBy(()->orderService.changeShippingAddressForUser(f.order(),f.user(),"customer",new com.example.ecsite.form.OrderShippingAddressForm())).hasMessageContaining("未解決");
        var actor=new com.example.ecsite.security.AdminUserDetails(9L,"admin","password",true,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(actor,"",actor.getAuthorities()));
        assertThatThrownBy(()->preparation.start(f.order())).hasMessageContaining("未解決");
        verifyNoInteractions(gateway);
    }
    @Test void twoDifferentSubmissionsCannotBothReserve() throws Exception {
        var f=fixture();var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var go=new java.util.concurrent.CountDownLatch(1);
        try{var jobs=new ArrayList<java.util.concurrent.Future<Boolean>>();for(int i=0;i<2;i++)jobs.add(pool.submit(()->{go.await();try{service.accept(request(f,UUID.randomUUID(),4));return true;}catch(com.example.ecsite.exception.InvalidOrderStatusException e){return false;}}));
            go.countDown();int successes=0;for(var job:jobs)if(job.get(20,java.util.concurrent.TimeUnit.SECONDS))successes++;
            assertThat(successes).isEqualTo(1);assertThat(stock(f)).isEqualTo(8);
        }finally{go.countDown();pool.shutdownNow();}
    }
    @Test void cancellationWinningOrderLockPreventsCandidate() throws Exception {
        var f=fixture();var pool=java.util.concurrent.Executors.newSingleThreadExecutor();var started=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Future<?>[] pending=new java.util.concurrent.Future<?>[1];
        try{tx(()->{orders.findByIdForUpdate(f.order()).orElseThrow();cancellation.start(f.order(),f.user(),"customer");
            pending[0]=pool.submit(()->{started.countDown();return service.accept(request(f,UUID.randomUUID(),3));});
            try{assertThat(started.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new RuntimeException(e);}return null;});
            assertThatThrownBy(()->pending[0].get(20,java.util.concurrent.TimeUnit.SECONDS)).isInstanceOf(java.util.concurrent.ExecutionException.class);
            assertThat(stock(f)).isEqualTo(10);assertThat(jdbc.queryForObject("select count(*) from order_change_operations where order_id=?",Long.class,f.order())).isZero();
        }finally{pool.shutdownNow();}
    }
    long[] secondItem(Fixture f,int stock){return tx(()->{
        var order=orders.findByIdForUpdate(f.order()).orElseThrow();var first=order.getItems().getFirst();var p0=products.findById(f.product()).orElseThrow();
        var p=new Product();p.setName("second");p.setPrice(999);p.setStock(stock);p.setCategory(p0.getCategory());p.setTaxCategory(p0.getTaxCategory());products.saveAndFlush(p);
        var item=new OrderItem(p.getId(),"second",first.getCategoryId(),first.getCategoryName(),first.getTaxCategoryId(),first.getTaxCategoryCode(),first.getTaxCategoryName(),first.getTaxRate(),100,1);order.addItem(item);orders.saveAndFlush(order);
        jdbc.update("update orders set total_amount=total_amount+100 where id=?",f.order());
        jdbc.update("update payments set amount=amount+100 where order_id=?",f.order());
        jdbc.update("update payment_transactions set amount=amount+100 where payment_id in(select id from payments where order_id=?)",f.order());
        return new long[]{jdbc.queryForObject("select id from order_items where order_id=? and product_id=?",Long.class,f.order(),p.getId()),p.getId()};
    });}
    @Test void productsLockAscendingAndLaterShortageRollsBackEarlierReservation(){
        var f=fixture();var second=secondItem(f,0);clearInvocations(products);
        assertThatThrownBy(()->service.accept(new OrderIncreaseService.Request(f.order(),f.user(),UUID.randomUUID(),0,Map.of(second[0],2,f.item(),3)))).isInstanceOf(RuntimeException.class);
        var ordered=inOrder(products);ordered.verify(products).findByIdForUpdate(f.product());ordered.verify(products).findByIdForUpdate(second[1]);
        assertThat(stock(f)).isEqualTo(10);assertThat(jdbc.queryForObject("select count(*) from stock_movements where order_id=?",Long.class,f.order())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from order_change_operations where order_id=?",Long.class,f.order())).isZero();
    }
    @Test void finalAdmissionCheckRejectsDeadlineReachedWhileWaitingForProductLocks(){
        var f=fixture();var deadline=jdbc.queryForObject("select change_deadline_at from orders where id=?",java.time.LocalDateTime.class,f.order());
        doReturn(deadline.minusNanos(1),deadline).when(orders).findCurrentTimeInTokyo();
        assertThatThrownBy(()->service.accept(request(f,UUID.randomUUID(),3))).isInstanceOf(RuntimeException.class);
        verify(products).findByIdForUpdate(f.product());
        assertThat(stock(f)).isEqualTo(10);assertThat(jdbc.queryForObject("select count(*) from order_change_operations where order_id=?",Long.class,f.order())).isZero();
    }
    @Test void quantityIncreaseCanLowerTotalByCrossingFreeShippingThreshold(){
        var f=fixture();
        jdbc.update("update order_items set quantity=49,subtotal=4900 where id=?",f.item());
        jdbc.update("update orders set total_amount=5450,item_subtotal=4900,charge_total=550 where id=?",f.order());
        jdbc.update("update payments set amount=5450 where order_id=?",f.order());
        jdbc.update("update payment_transactions set amount=5450 where payment_id in(select id from payments where order_id=?)",f.order());
        var result=service.accept(request(f,UUID.randomUUID(),50));
        assertThat(result.beforeAmount()).isEqualTo(5450);assertThat(result.afterAmount()).isEqualTo(5000);assertThat(stock(f)).isEqualTo(9);
        assertThat(jdbc.queryForObject("select shipping_amount from order_change_operations where id=?",Integer.class,result.id())).isZero();
    }
    @Test void databaseRejectsSecondUnresolvedOperationAndForeignCustomer(){
        var f=fixture();var other=fixture();var op=service.accept(request(f,UUID.randomUUID(),3));
        assertThatThrownBy(()->jdbc.update("update order_change_operations set customer_id=? where id=?",other.user(),op.id())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.execute("insert into order_change_operations select gen_random_uuid(),order_id,customer_id,gen_random_uuid(),request_digest,base_content_revision,old_payment_id,before_amount,after_amount,item_subtotal,charge_total,tax_amount,shipping_name,shipping_amount,shipping_tax_category_id,shipping_tax_category_code,shipping_tax_category_name,shipping_tax_rate,shipping_display_order,accepted_at,deadline_at,grace_until,switch_decided_at,state,version,created_at,updated_at from order_change_operations where id='"+op.id()+"'" )).isInstanceOf(RuntimeException.class);
    }
    @Test void shippingPreparationWaitsForCandidateAndThenIsRejected() throws Exception {
        var f=fixture();var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        doAnswer(invocation->{var result=invocation.callRealMethod();entered.countDown();
            if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("test synchronization timeout");return result;
        }).when(org.springframework.test.util.AopTestUtils.<com.example.ecsite.service.InventoryService>getUltimateTargetObject(inventory)).reserveForOrderChange(eq(f.product()),anyInt(),eq(f.order()),any(UUID.class),eq(f.item()));
        try{var candidate=pool.submit(()->service.accept(request(f,UUID.randomUUID(),3)));
            assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var prepEntered=new java.util.concurrent.CountDownLatch(1);
            var prep=pool.submit(()->{var actor=new com.example.ecsite.security.AdminUserDetails(9L,"admin","password",true,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
                org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(actor,"",actor.getAuthorities()));
                prepEntered.countDown();try{return preparation.start(f.order());}finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}});
            assertThat(prepEntered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();release.countDown();candidate.get(20,java.util.concurrent.TimeUnit.SECONDS);
            assertThatThrownBy(()->prep.get(20,java.util.concurrent.TimeUnit.SECONDS)).hasCauseInstanceOf(com.example.ecsite.exception.InvalidOrderStatusException.class);
            assertThat(jdbc.queryForObject("select shipping_preparation_started_at from orders where id=?",java.time.LocalDateTime.class,f.order())).isNull();
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void abortedCandidateDoesNotPreventLaterOrdinaryItemRemoval(){
        var f=fixture();var second=secondItem(f,10);var operation=service.accept(request(f,UUID.randomUUID(),4));
        service.abort(f.order(),f.user(),operation.id());
        var form=new com.example.ecsite.form.OrderItemChangeForm();form.setContentRevision(0);
        var first=new com.example.ecsite.form.OrderItemChangeForm.Item();first.setOrderItemId(f.item());first.setQuantity(0);
        var remaining=new com.example.ecsite.form.OrderItemChangeForm.Item();remaining.setOrderItemId(second[0]);remaining.setQuantity(1);
        form.setItems(List.of(first,remaining));
        assertThat(orderService.changeItemsForUser(f.order(),f.user(),"customer",form)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from order_items where id=?",Long.class,f.item())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from order_change_items where operation_id=? and order_item_id=?",Long.class,operation.id(),f.item())).isEqualTo(1);
        assertThat(service.accept(request(f,findSubmission(operation.id()),4)).state()).isEqualTo("ABORTED");
    }
    UUID findSubmission(UUID operationId){return jdbc.queryForObject("select submission_key from order_change_operations where id=?",UUID.class,operationId);}
}
