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

@SpringBootTest(properties={"logging.level.root=WARN","spring.jpa.show-sql=false",
    "spring.flyway.target=107"})
@ActiveProfiles("test")
@com.example.ecsite.support.CaptureConnectionTest
class OrderIncreasePostgresTest {
    @Autowired OrderIncreaseService service;
    @Autowired org.springframework.context.ApplicationContext context;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean OrderRepository orders;
    @Autowired UserRepository users;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired TaxCategoryRepository taxes;
    @Autowired PaymentRepository payments;
    @Autowired PaymentTransactionRepository transactions;
    @Autowired OrderStatusHistoryService histories;
    @Autowired OrderPaymentGuard guard;
    @Autowired com.example.ecsite.service.payment.PaymentService paymentService;
    @Autowired com.example.ecsite.service.payment.PaymentCancellationStartService cancellation;
    @Autowired com.example.ecsite.service.OrderShippingPreparationService preparation;
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
        // Feature147: on purpose, this legacy fixture leaves the order without an active payment (like orders before V55),
        // so the legacy increase also covers ActivePaymentService.initializeForOrderChange setting it.
        var payment=new Payment(order,PaymentProvider.PAYJP,PaymentMethod.CARD,750,now);payment.setProviderPaymentId("pfw_"+key,now);payment.markAuthorized(now);payments.saveAndFlush(payment);
        var auth=new PaymentTransaction(payment,PaymentTransactionType.AUTHORIZE,750,0,key,now);auth.markSucceeded(payment.getProviderPaymentId(),now);transactions.saveAndFlush(auth);
        return new Fixture(order.getId(),user.getId(),order.getItems().getFirst().getId(),product.getId());
    });}
    OrderChangeStore.Operation accept(Fixture f,int quantity){return LegacyOrderIncreaseFixture.accept(context,f.order(),f.user(),Map.of(f.item(),quantity));}
    int stock(Fixture f){return jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product());}
    @Test void releaseIsIdempotentAndClearsTheUnresolvedChange(){
        var f=fixture();var accepted=accept(f,5);
        service.abort(f.order(),f.user(),accepted.id());service.abort(f.order(),f.user(),accepted.id());
        assertThat(stock(f)).isEqualTo(10);assertThat(jdbc.queryForObject("select state from order_change_operations where id=?",String.class,accepted.id())).isEqualTo("ABORTED");
        assertThat(accept(f,4).state()).isEqualTo("RESERVED");
    }
    @Test void unresolvedCandidateBlocksCompetingMutations(){
        var f=fixture();accept(f,3);
        assertThatThrownBy(()->tx(()->{guard.requireNoOrderChange(orders.findByIdForUpdate(f.order()).orElseThrow());return null;})).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->tx(()->{guard.validateLocalCancellation(orders.findByIdForUpdate(f.order()).orElseThrow());return null;})).isInstanceOf(RuntimeException.class);
    }
    @Test void reservationExpiryUsesGraceBoundaryAndCannotUndoDecision(){
        var f=fixture();var operation=accept(f,4);
        doReturn(operation.graceUntil().minusNanos(1)).when(orders).findCurrentTimeInTokyo();
        assertThat(service.expireReserved(f.order(),operation.id())).isFalse();
        doReturn(operation.graceUntil()).when(orders).findCurrentTimeInTokyo();
        assertThat(service.expireReserved(f.order(),operation.id())).isTrue();assertThat(stock(f)).isEqualTo(10);
    }
    @Test void decidedOperationCannotExpireOrAbort(){
        var f=fixture();var operation=accept(f,4);
        jdbc.update("update order_change_operations set state='OLD_CANCEL_PENDING',switch_decided_at=accepted_at where id=?",operation.id());
        doReturn(operation.graceUntil().plusHours(1)).when(orders).findCurrentTimeInTokyo();
        assertThat(service.expireReserved(f.order(),operation.id())).isFalse();
        assertThatThrownBy(()->service.abort(f.order(),f.user(),operation.id())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->jdbc.update("update order_change_operations set switch_decided_at=null where id=?",operation.id())).isInstanceOf(RuntimeException.class);
        assertThat(stock(f)).isEqualTo(8);
    }
    @Test void refreshesStaleProductBeforeReservationRelease(){
        var f=fixture();var operation=accept(f,4);
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
        var f=fixture();accept(f,3);
        assertThatThrownBy(()->cancellation.start(f.order(),f.user(),"customer")).hasMessageContaining("未解決");
        assertThatThrownBy(()->tx(()->paymentService.startAuthorization(orders.findByIdForUpdate(f.order()).orElseThrow()))).hasMessageContaining("未解決");
        assertThatThrownBy(()->orderService.changeShippingAddressForUser(f.order(),f.user(),"customer",new com.example.ecsite.form.OrderShippingAddressForm())).hasMessageContaining("未解決");
        com.example.ecsite.support.EmergencyShippingFixtures.ensurePermittedAdmin(jdbc,9L,"admin");var actor=new com.example.ecsite.security.AdminUserDetails(9L,"admin","password",true,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(actor,"",actor.getAuthorities()));
        assertThatThrownBy(()->com.example.ecsite.support.EmergencyShippingFixtures.start(preparation,jdbc,f.order())).hasMessageContaining("未解決");
        verifyNoInteractions(gateway);
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
    @Test void databaseRejectsSecondUnresolvedOperationAndForeignCustomer(){
        var f=fixture();var other=fixture();var op=accept(f,3);
        assertThatThrownBy(()->jdbc.update("update order_change_operations set customer_id=? where id=?",other.user(),op.id())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.execute("insert into order_change_operations select gen_random_uuid(),order_id,customer_id,gen_random_uuid(),request_digest,base_content_revision,old_payment_id,before_amount,after_amount,item_subtotal,charge_total,tax_amount,shipping_name,shipping_amount,shipping_tax_category_id,shipping_tax_category_code,shipping_tax_category_name,shipping_tax_rate,shipping_display_order,accepted_at,deadline_at,grace_until,switch_decided_at,state,version,created_at,updated_at from order_change_operations where id='"+op.id()+"'" )).isInstanceOf(RuntimeException.class);
    }
    @Test void abortedCandidateDoesNotPreventLaterOrdinaryItemRemoval(){
        var f=fixture();var second=secondItem(f,10);var operation=accept(f,4);
        service.abort(f.order(),f.user(),operation.id());
        com.example.ecsite.support.LegacyItemDecreaseFixture.decrease(context,f.order(),f.user(),"customer",Map.of(f.item(),0));
        assertThat(jdbc.queryForObject("select count(*) from order_items where id=?",Long.class,f.item())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from order_change_items where operation_id=? and order_item_id=?",Long.class,operation.id(),f.item())).isEqualTo(1);
    }
}
