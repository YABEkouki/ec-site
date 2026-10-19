package com.example.ecsite.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.sql.DataSource;

import com.example.ecsite.cart.*;
import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.form.*;
import com.example.ecsite.payment.*;
import com.example.ecsite.repository.*;
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.payment.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real order creation/pricing/inventory and service TXs; only Gateway and the time boundary are controlled. */
@SpringBootTest(properties={"logging.level.root=WARN", "spring.jpa.show-sql=false"})
@ActiveProfiles("test")
@Import(OrderToCaptureJourneyPostgresTest.TimeConfiguration.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class OrderToCaptureJourneyPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17")
            .withDatabaseName("order_capture_journey").withUsername("journey_user").withPassword(UUID.randomUUID().toString());
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
        registry.add("spring.flyway.url",POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user",POSTGRES::getUsername);
        registry.add("spring.flyway.password",POSTGRES::getPassword);
    }
    private static final ZoneId TOKYO=ZoneId.of("Asia/Tokyo");
    @Autowired OrderService orderService;
    @Autowired OrderDeadlineCalculator deadlines;
    @Autowired OrderShippingPreparationService preparation;
    @Autowired PaymentAuthorizationService authorization;
    @Autowired PaymentCaptureService capture;
    @Autowired AdminCaptureRequeryService requery;
    @Autowired PaymentCancellationService cancellation;
    @Autowired AdminPaymentCancellationService adminCancellation;
    @Autowired UserRepository users;
    @Autowired AdminAccountRepository admins;
    @Autowired CategoryRepository categories;
    @Autowired ProductRepository products;
    @Autowired TaxCategoryService taxes;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired JourneyClock clock;
    @MockitoSpyBean OrderRepository orders;
    @MockitoBean PaymentGateway gateway;

    @AfterEach void cleanup() {
        try { reset(orders); clock.resume(); }
        finally { SecurityContextHolder.clearContext(); }
    }

    @Test void newOrderCanBeReducedPreparedShippedAndCapturedThroughRequery() {
        SecurityContextHolder.clearContext();
        Seed seed=tx(() -> {
            var now=orders.findCurrentTimeInTokyo();String unique=UUID.randomUUID().toString();
            User user=new User();user.setUsername("journey-"+unique);user.setPassword("test");user.setEnabled(true);
            user.setCreatedAt(now);user.setUpdatedAt(now);users.saveAndFlush(user);
            var category=categories.saveAndFlush(new Category(unique));
            Product product=new Product();product.setName("journey product");product.setPrice(1000);product.setStock(10);
            product.setCategory(category);product.setTaxCategory(taxes.findStandardTaxCategory());products.saveAndFlush(product);
            return new Seed(user.getId(),product.getId(),admin("prep-"+unique,now),admin("ship-"+unique,now));
        });
        Cart cart=new Cart();cart.addItem(new CartItem(seed.product(),"journey product",1000,3));
        Order created=orderService.createOrder(seed.user(),"customer",cart,address());
        long id=created.getId();
        var orderedAt=date(id,"ordered_at");var deadline=date(id,"change_deadline_at");
        assertThat(deadline).isEqualTo(deadlines.calculate(orderedAt));
        assertThat(row(id)).containsEntry("status","ORDERED").containsEntry("total_amount",3550).containsEntry("content_revision",0);
        assertThat(stock(seed)).isEqualTo(7);assertThat(quantity(id)).isEqualTo(3);
        assertThat(historyCount(id,"ORDERED")).isEqualTo(1);assertThat(historyCount(id,"SHIPPED")).isZero();

        String provider="flow_journey_"+UUID.randomUUID();
        when(gateway.prepareAuthorization(any())).thenReturn(new AuthorizationPreparation(provider,"test-secret"));
        // Match PayJpAuthorizationMapper: the verified response Flow ID is retained on success.
        when(gateway.retrieveAuthorization(provider)).thenReturn(new AuthorizationRecovery(
                new AuthorizationResult(AuthorizationResultStatus.AUTHORIZED,provider,null,null),null));
        var authorized=authorization.prepareAuthorization(created);
        assertThat(authorization.refreshAuthorization(id,authorized.paymentId()).status()).isEqualTo(AuthorizationResultStatus.AUTHORIZED);
        assertThat(payment(id)).containsEntry("status","AUTHORIZED").containsEntry("amount",3550);
        var authBefore=transactions(id);
        assertThat(authBefore).hasSize(1);
        assertThat(authBefore.getFirst()).containsEntry("transaction_type","AUTHORIZE").containsEntry("status","SUCCEEDED")
                .containsEntry("amount",3550).containsEntry("order_content_revision",0).containsEntry("provider_transaction_id",provider);

        // Initial order/authorization used real time. Advance only the time boundary from here.
        advance(deadline.minusNanos(1000));
        assertThat(orderService.changeItemsForUser(id,seed.user(),"customer",items(id,0,2))).isTrue();
        assertThat(row(id)).containsEntry("total_amount",2550).containsEntry("content_revision",1);
        assertThat(quantity(id)).isEqualTo(2);assertThat(stock(seed)).isEqualTo(8);
        assertThat(transactions(id)).isEqualTo(authBefore);
        assertThat(payment(id)).containsEntry("amount",3550).containsEntry("status","AUTHORIZED");

        advance(deadline);
        unchangedOnRejection(seed,id,()->orderService.changeItemsForUser(id,seed.user(),"customer",items(id,1,1)));
        authenticate(seed.preparer());
        var started=preparation.start(id);
        assertThat(started.status()).isEqualTo(ShippingPreparationStartResult.Status.STARTED);
        assertThat(date(id,"shipping_preparation_started_at")).isEqualTo(deadline);
        assertThat(row(id)).containsEntry("status","ORDERED")
                .containsEntry("shipping_preparation_started_by_account_id",seed.preparer().id())
                .containsEntry("shipping_preparation_started_by_username",seed.preparer().username());
        var prepared=snapshot(seed,id);
        OrderShippingAddressForm changed=new OrderShippingAddressForm();changed.setShippingName("changed");
        changed.setShippingPostalCode("100-0001");changed.setShippingPrefecture("東京都");changed.setShippingCity("千代田区");
        changed.setShippingAddressLine("changed address");changed.setShippingPhone("0312345678");
        unchangedOnRejection(seed,id,()->orderService.changeShippingAddressForUser(id,seed.user(),"customer",changed));
        unchangedOnRejection(seed,id,()->cancellation.cancelForUser(id,seed.user(),"customer"));
        unchangedOnRejection(seed,id,()->adminCancellation.cancel(id,seed.preparer().id(),seed.preparer().username(),null));
        assertThat(snapshot(seed,id)).isEqualTo(prepared);
        verify(gateway,never()).cancelAuthorization(any());

        advance(deadline.plusNanos(1000));authenticate(seed.shipper());
        when(gateway.capture(any())).thenAnswer(call -> {
            assertOutsideTx();
            CaptureRequest request=call.getArgument(0);
            assertThat(request.amount()).isEqualTo(2550);
            // A separate transaction/connection can acquire the lock and see all start-TX writes.
            tx(() -> {
                jdbc.execute("set local lock_timeout='2s'");
                var locked=orders.findByIdForUpdate(id).orElseThrow();
                assertThat(locked.getStatus()).isEqualTo(OrderStatus.SHIPPED);
                assertThat(locked.getShipmentCapturePaymentId()).isEqualTo(authorized.paymentId());
                assertThat(locked.getPaidAt()).isNull();
                assertThat(historyCount(id,"SHIPPED")).isEqualTo(1);
                assertThat(captureTransaction(id)).containsEntry("status","PENDING").containsEntry("amount",2550)
                        .containsEntry("order_content_revision",1).containsEntry("idempotency_key",request.idempotencyKey());
                return null;
            });
            return new CaptureResult(CaptureResultStatus.PENDING,provider,null,null);
        });
        var beforeShipment=unchangingContent(seed,id);
        assertThat(capture.captureForShipment(id,seed.shipper().id(),seed.shipper().username(),"journey memo").status())
                .isEqualTo(CaptureResultStatus.PENDING);
        assertThat(row(id)).containsEntry("status","SHIPPED").containsEntry("shipment_capture_payment_id",authorized.paymentId())
                .containsEntry("shipped_by_account_id",seed.shipper().id()).containsEntry("shipped_by_username",seed.shipper().username());
        assertThat(payment(id)).containsEntry("status","AUTHORIZED");assertThat(date(id,"paid_at")).isNull();
        assertThat(unchangingContent(seed,id)).isEqualTo(beforeShipment);
        var shipment=shipment(id);var transactionBefore=captureTransaction(id);

        advance(deadline.plusNanos(2000));
        when(gateway.retrievePaymentFlow(provider)).thenAnswer(call -> {
            assertOutsideTx();return new PaymentFlowState(provider,PaymentFlowStatus.SUCCEEDED,null,null);
        });
        assertThat(requery.requery(id)).isEqualTo(AdminCaptureRequeryService.Result.CONFIRMED);
        assertThat(payment(id)).containsEntry("status","CAPTURED").containsEntry("amount",3550);
        var applied=captureTransaction(id);
        assertThat(applied).containsEntry("status","SUCCEEDED");
        for(String key:List.of("id","amount","order_content_revision","idempotency_key","initiator_id","initiator_username","internal_note"))
            assertThat(applied.get(key)).isEqualTo(transactionBefore.get(key));
        assertThat(date(id,"paid_at")).isEqualTo(clock.localTime());
        assertThat(date(id,"paid_at").getNano()%1000).isZero();
        assertThat(date(id,"paid_at")).isAfterOrEqualTo(date(id,"shipped_at"));
        assertThat(shipment(id)).isEqualTo(shipment);
        assertThat(unchangingContent(seed,id)).isEqualTo(beforeShipment);
        assertThat(historyCount(id,"SHIPPED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from order_status_histories where order_id=?",Integer.class,id)).isEqualTo(2);
        var success=snapshot(seed,id);
        advance(deadline.plusNanos(3000));
        assertThat(requery.requery(id)).isEqualTo(AdminCaptureRequeryService.Result.CONFIRMED);
        assertThat(snapshot(seed,id)).isEqualTo(success);
        verify(gateway,times(1)).prepareAuthorization(any());verify(gateway,times(1)).retrieveAuthorization(provider);
        verify(gateway,times(1)).capture(any());verify(gateway,times(1)).retrievePaymentFlow(provider);verifyNoMoreInteractions(gateway);
    }

    private void advance(LocalDateTime time) {
        clock.advance(time);doReturn(clock.localTime()).when(orders).findCurrentTimeInTokyo();
    }
    private void unchangedOnRejection(Seed seed,long id,org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        var before=snapshot(seed,id);
        assertThatThrownBy(action).isInstanceOf(InvalidOrderStatusException.class);
        assertThat(snapshot(seed,id)).isEqualTo(before);
    }
    private Actor admin(String username,LocalDateTime now) {
        AdminAccount admin=new AdminAccount();admin.setUsername(username);admin.setPassword("test");admin.setEnabled(true);
        admin.setCreatedAt(now);admin.setUpdatedAt(now);admins.saveAndFlush(admin);return new Actor(admin.getId(),username);
    }
    private void authenticate(Actor actor) {
        var details=new AdminUserDetails(actor.id(),actor.username(),"test",true,List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(details,"",details.getAuthorities()));
    }
    private CheckoutForm address() {
        CheckoutForm f=new CheckoutForm();f.setShippingName("customer");f.setShippingPostalCode("100-0001");
        f.setShippingPrefecture("東京都");f.setShippingCity("千代田区");f.setShippingAddressLine("1-1");f.setShippingPhone("0312345678");return f;
    }
    private OrderItemChangeForm items(long id,int revision,int quantity) {
        var f=new OrderItemChangeForm();f.setContentRevision(revision);var item=new OrderItemChangeForm.Item();
        item.setOrderItemId(jdbc.queryForObject("select id from order_items where order_id=?",Long.class,id));
        item.setQuantity(quantity);f.getItems().add(item);return f;
    }
    private <T>T tx(Supplier<T> action) { return new TransactionTemplate(manager).execute(s->action.get()); }
    private void assertOutsideTx() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
    }
    private Map<String,Object> row(long id) { return jdbc.queryForMap("select * from orders where id=?",id); }
    private LocalDateTime date(long id,String column) { return jdbc.queryForObject("select "+column+" from orders where id=?",LocalDateTime.class,id); }
    private int stock(Seed s) { return jdbc.queryForObject("select stock from products where id=?",Integer.class,s.product()); }
    private int quantity(long id) { return jdbc.queryForObject("select quantity from order_items where order_id=?",Integer.class,id); }
    private int historyCount(long id,String to) { return jdbc.queryForObject("select count(*) from order_status_histories where order_id=? and to_status=?",Integer.class,id,to); }
    private Map<String,Object> payment(long id) { return jdbc.queryForMap("select * from payments where order_id=?",id); }
    private List<Map<String,Object>> transactions(long id) { return jdbc.queryForList("select t.* from payment_transactions t join payments p on p.id=t.payment_id where p.order_id=? order by t.id",id); }
    private Map<String,Object> captureTransaction(long id) { return transactions(id).stream().filter(t->"CAPTURE".equals(t.get("transaction_type"))).findFirst().orElseThrow(); }
    private Map<String,Object> shipment(long id) { return jdbc.queryForMap("select status,shipped_at,shipped_by_account_id,shipped_by_username,shipment_capture_payment_id,shipping_preparation_started_at,shipping_preparation_started_by_account_id,shipping_preparation_started_by_username from orders where id=?",id); }
    private Object unchangingContent(Seed s,long id) {
        return List.of(jdbc.queryForMap("select total_amount,content_revision from orders where id=?",id),
                jdbc.queryForList("select * from order_items where order_id=? order by id",id),
                jdbc.queryForList("select * from order_charges where order_id=? order by id",id),
                jdbc.queryForMap("select * from products where id=?",s.product()),
                jdbc.queryForList("select * from stock_movements where order_id=? order by id",id));
    }
    private Object snapshot(Seed s,long id) {
        return List.of(row(id),payment(id),transactions(id),unchangingContent(s,id),
                jdbc.queryForList("select * from order_status_histories where order_id=? order by id",id),
                jdbc.queryForList("select * from order_content_change_histories where order_id=? order by id",id),
                jdbc.queryForList("select * from order_shipping_address_histories where order_id=? order by id",id));
    }
    record Actor(Long id,String username) {}
    record Seed(Long user,Long product,Actor preparer,Actor shipper) {}

    @TestConfiguration(proxyBeanMethods=false)
    static class TimeConfiguration {
        @Bean @Primary JourneyClock journeyClock() { return new JourneyClock(); }
    }
    static final class JourneyClock extends Clock {
        private final AtomicReference<Instant> fixed=new AtomicReference<>();
        void advance(LocalDateTime time) { fixed.set(time.truncatedTo(ChronoUnit.MICROS).atZone(TOKYO).toInstant()); }
        void resume() { fixed.set(null); }
        LocalDateTime localTime() { return LocalDateTime.ofInstant(instant(),TOKYO); }
        @Override public ZoneId getZone() { return TOKYO; }
        @Override public Clock withZone(ZoneId zone) { return zone.equals(TOKYO)?this:Clock.fixed(instant(),zone); }
        @Override public Instant instant() { Instant value=fixed.get();return value==null?Instant.now():value; }
    }
}
