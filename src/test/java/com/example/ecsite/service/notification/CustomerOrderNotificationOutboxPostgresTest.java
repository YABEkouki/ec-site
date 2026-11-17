package com.example.ecsite.service.notification;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.SQLException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.example.ecsite.cart.*;
import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.form.*;
import com.example.ecsite.payment.*;
import com.example.ecsite.repository.*;
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.*;
import com.example.ecsite.service.payment.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Feature133-A: every customer-visible order event records exactly one notification in the
 * business transaction (V91 deferred triggers), whatever service path wrote it. Nothing is sent.
 */
@SpringBootTest(properties={"logging.level.root=WARN", "spring.jpa.show-sql=false",
        "app.payment.reconciliation.enabled=false", "app.payment.discrepancy-audit.enabled=false"})
@ActiveProfiles("test")
@Import(CustomerOrderNotificationOutboxPostgresTest.TimeConfiguration.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@com.example.ecsite.support.CaptureConnectionTest
class CustomerOrderNotificationOutboxPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17")
            .withDatabaseName("customer_notification").withUsername("notification_user").withPassword(UUID.randomUUID().toString());
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
        registry.add("spring.flyway.url",POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user",POSTGRES::getUsername);
        registry.add("spring.flyway.password",POSTGRES::getPassword);
    }
    private static final ZoneId TOKYO=ZoneId.of("Asia/Tokyo");
    private static final ObjectMapper JSON=new ObjectMapper();
    @Autowired OrderService orderService;
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
    @Autowired NotificationClock clock;
    @MockitoSpyBean OrderRepository orders;
    @MockitoBean PaymentGateway gateway;

    @AfterEach void cleanup() {
        try { reset(orders); clock.resume(); }
        finally { SecurityContextHolder.clearContext(); }
    }

    @Test void acceptanceItemAndAddressChangesAndShipmentEachRecordOneNotification() throws Exception {
        Seed seed=seed();
        long id=order(seed);
        assertThat(notifications(id)).isEmpty();

        String provider=authorize(id,AuthorizationResultStatus.AUTHORIZED);
        assertThat(types(id)).containsExactly("ORDER_ACCEPTED");
        // A repeated result application is idempotent and records nothing more.
        assertThat(authorization.refreshAuthorization(id,paymentId(id)).status()).isEqualTo(AuthorizationResultStatus.AUTHORIZED);
        assertThat(types(id)).containsExactly("ORDER_ACCEPTED");
        JsonNode accepted=payload(id,"ORDER_ACCEPTED");
        assertThat(accepted.path("version").asInt()).isEqualTo(1);
        assertThat(accepted.path("acceptedAt").asString()).isNotBlank();
        assertThat(accepted.at("/order/orderId").asLong()).isEqualTo(id);
        assertThat(accepted.at("/order/totalAmount").asInt()).isEqualTo(3550);
        assertThat(accepted.at("/order/items/0/productName").asString()).isEqualTo("notification product");
        assertThat(accepted.at("/order/items/0/quantity").asInt()).isEqualTo(3);
        assertThat(accepted.at("/order/charges/0/amount").asInt()).isPositive();
        assertThat(accepted.at("/order/shipping/name").asString()).isEqualTo("customer");
        assertThat(accepted.at("/order/shipping").has("phone")).isFalse();
        assertThat(accepted.toString()).doesNotContain("0312345678").doesNotContain(provider);
        assertThat(notification(id,"ORDER_ACCEPTED")).containsEntry("source_key",String.valueOf(id))
                .containsEntry("user_id",seed.user()).containsEntry("status","PENDING").containsEntry("attempt_count",0);

        var deadline=date(id,"change_deadline_at");
        advance(deadline.minusNanos(1000));
        assertThat(orderService.changeItemsForUser(id,seed.user(),"customer",items(id,0,2))).isTrue();
        JsonNode changed=payload(id,"ITEMS_CHANGED");
        assertThat(changed.at("/change/oldTotalAmount").asInt()).isEqualTo(3550);
        assertThat(changed.at("/change/newTotalAmount").asInt()).isEqualTo(2550);
        assertThat(changed.at("/change/items/0/oldQuantity").asInt()).isEqualTo(3);
        assertThat(changed.at("/change/items/0/newQuantity").asInt()).isEqualTo(2);
        assertThat(changed.at("/order/totalAmount").asInt()).isEqualTo(2550);
        assertThat(changed.at("/order/items/0/quantity").asInt()).isEqualTo(2);
        long history=jdbc.queryForObject("select id from order_content_change_histories where order_id=?",Long.class,id);
        assertThat(notification(id,"ITEMS_CHANGED")).containsEntry("source_key",String.valueOf(history));
        // The acceptance snapshot is not rewritten by the later change.
        assertThat(payload(id,"ORDER_ACCEPTED")).isEqualTo(accepted);

        OrderShippingAddressForm address=new OrderShippingAddressForm();address.setShippingName("changed name");
        address.setShippingPostalCode("150-0001");address.setShippingPrefecture("東京都");address.setShippingCity("渋谷区");
        address.setShippingAddressLine("2-2");address.setShippingPhone("0398765432");
        assertThat(orderService.changeShippingAddressForUser(id,seed.user(),"customer",address)).isTrue();
        JsonNode moved=payload(id,"ADDRESS_CHANGED");
        assertThat(moved.at("/oldShipping/name").asString()).isEqualTo("customer");
        assertThat(moved.at("/newShipping/name").asString()).isEqualTo("changed name");
        assertThat(moved.at("/newShipping/city").asString()).isEqualTo("渋谷区");
        assertThat(moved.at("/order/shipping/addressLine").asString()).isEqualTo("2-2");
        assertThat(moved.toString()).doesNotContain("0398765432").doesNotContain("0312345678");
        // An unchanged address writes no history and records nothing.
        assertThat(orderService.changeShippingAddressForUser(id,seed.user(),"customer",address)).isFalse();

        advance(deadline);authenticate(seed.preparer());
        preparation.start(id);
        assertThat(types(id)).containsExactly("ORDER_ACCEPTED","ITEMS_CHANGED","ADDRESS_CHANGED");

        advance(deadline.plusNanos(1000));authenticate(seed.shipper());
        when(gateway.capture(any())).thenReturn(new CaptureResult(CaptureResultStatus.PENDING,provider,null,null));
        capture.captureForShipment(id,seed.shipper().id(),seed.shipper().username(),"internal memo");
        JsonNode shipped=payload(id,"ORDER_SHIPPED");
        assertThat(shipped.path("changedByType").asString()).isEqualTo("ADMIN");
        assertThat(shipped.path("warehouseImport").asBoolean()).isFalse();
        assertThat(shipped.path("shippedAt").asString()).isNotBlank();
        assertThat(shipped.toString()).doesNotContain("internal memo").doesNotContain(seed.shipper().username());

        advance(deadline.plusNanos(2000));
        when(gateway.retrievePaymentFlow(provider)).thenReturn(new PaymentFlowState(provider,PaymentFlowStatus.SUCCEEDED,null,null));
        assertThat(requery.requery(id)).isEqualTo(AdminCaptureRequeryService.Result.CONFIRMED);
        assertThat(types(id)).containsExactly("ORDER_ACCEPTED","ITEMS_CHANGED","ADDRESS_CHANGED","ORDER_SHIPPED");
        assertThat(notifications(id)).allSatisfy(n -> assertThat(n).containsEntry("status","PENDING")
                .containsEntry("user_id",seed.user()).containsEntry("attempt_count",0).containsEntry("max_attempts",3));
    }

    @Test void customerAndAdministratorCancellationsRecordTheirActor() throws Exception {
        Seed seed=seed();
        long byCustomer=order(seed);String provider=authorize(byCustomer,AuthorizationResultStatus.AUTHORIZED);
        when(gateway.cancelAuthorization(any())).thenReturn(new CancellationResult(CancellationResultStatus.CANCELLED,provider));
        assertThat(cancellation.cancelForUser(byCustomer,seed.user(),"customer").status()).isEqualTo(CancellationResultStatus.CANCELLED);
        assertThat(types(byCustomer)).containsExactly("ORDER_ACCEPTED","ORDER_CANCELLED");
        JsonNode customer=payload(byCustomer,"ORDER_CANCELLED");
        assertThat(customer.path("changedByType").asString()).isEqualTo("USER");
        assertThat(customer.path("accepted").asBoolean()).isTrue();
        assertThat(customer.at("/order/status").asString()).isEqualTo("CANCELLED");

        long byAdmin=order(seed);String adminProvider=authorize(byAdmin,AuthorizationResultStatus.AUTHORIZED);
        when(gateway.cancelAuthorization(any())).thenReturn(new CancellationResult(CancellationResultStatus.CANCELLED,adminProvider));
        authenticate(seed.preparer());
        assertThat(adminCancellation.cancel(byAdmin,seed.preparer().id(),seed.preparer().username(),"admin note").status())
                .isEqualTo(CancellationResultStatus.CANCELLED);
        JsonNode admin=payload(byAdmin,"ORDER_CANCELLED");
        assertThat(admin.path("changedByType").asString()).isEqualTo("ADMIN");
        assertThat(admin.toString()).doesNotContain("admin note").doesNotContain(seed.preparer().username());
    }

    @Test void aFailedInitialAuthorizationRecordsOnlyASystemCancellationOfAnUnacceptedOrder() throws Exception {
        Seed seed=seed();
        long id=order(seed);
        authorize(id,AuthorizationResultStatus.FAILED);
        assertThat(types(id)).containsExactly("ORDER_CANCELLED");
        JsonNode cancelled=payload(id,"ORDER_CANCELLED");
        assertThat(cancelled.path("changedByType").asString()).isEqualTo("SYSTEM");
        assertThat(cancelled.path("accepted").asBoolean()).isFalse();
        assertThat(cancelled.at("/order/acceptedAt").isNull()).isTrue();
    }

    @Test void aRolledBackEventRecordsNothingAndOtherStatusesAreIgnored() {
        Seed seed=seed();
        long id=order(seed);
        new TransactionTemplate(manager).executeWithoutResult(s -> {
            insertStatus(id,"CANCELLED");
            s.setRollbackOnly();
        });
        assertThat(notifications(id)).isEmpty();
        insertStatus(id,"PAID");
        assertThat(notifications(id)).isEmpty();
    }

    @Test void theOutboxGuardKeepsRowsImmutableOrderedAndFinal() throws Exception {
        Seed seed=seed();
        long id=order(seed);
        insertStatus(id,"CANCELLED");insertStatus(id,"SHIPPED");
        var rows=notifications(id);
        assertThat(rows).extracting(r -> r.get("event_type")).containsExactly("ORDER_CANCELLED","ORDER_SHIPPED");
        long first=((Number)rows.get(0).get("id")).longValue(),second=((Number)rows.get(1).get("id")).longValue();

        // Only the recording triggers create rows; nothing is ever deleted.
        rejects("55000",() -> jdbc.update("insert into customer_order_notifications(event_type,order_id,user_id,source_key,payload,status,"
                + "created_at,updated_at,recorded_xid) values ('ORDER_SHIPPED',?,?,'999999',?::jsonb,'PENDING',now(),now(),pg_current_xact_id())",
                id,seed.user(),"{\"version\":1,\"eventType\":\"ORDER_SHIPPED\",\"order\":{}}"));
        rejects("55000",() -> jdbc.update("delete from customer_order_notifications where id=?",first));
        rejects("55000",() -> jdbc.execute("truncate customer_order_notification_attempts, customer_order_notifications"));
        rejects("55000",() -> jdbc.update("update customer_order_notifications set payload=payload||'{\"x\":1}'::jsonb,version=version+1 where id=?",first));
        rejects("55000",() -> jdbc.update("update customer_order_notifications set source_key='1',version=version+1 where id=?",first));
        rejects("23514",() -> jdbc.update("update customer_order_notifications set status='SENT',next_attempt_at=null,version=version+1 where id=?",first));
        rejects("40001",() -> claim(first,0));

        // Per-order FIFO: the later event cannot be claimed while the earlier one is open.
        rejects("23514",() -> claim(second,1));
        UUID token=claim(first,1);
        rejects("23514",() -> jdbc.update("update customer_order_notifications set status='SENDING',version=version+1 where id=?",first));
        jdbc.update("update customer_order_notifications set status='SENDING',attempt_count=1,version=version+1 where id=?",first);
        rejects("23514",() -> attempt(first,UUID.randomUUID(),1));
        rejects("23514",() -> attempt(first,token,2));
        attempt(first,token,1);
        rejects("55000",() -> jdbc.update("update customer_order_notification_attempts set body='changed' where notification_id=?",first));
        jdbc.update("update customer_order_notification_attempts set result='SUCCESS' where notification_id=?",first);
        rejects("55000",() -> jdbc.update("update customer_order_notification_attempts set result='FAILURE',failure_code='X' where notification_id=?",first));
        rejects("55000",() -> jdbc.update("delete from customer_order_notification_attempts where notification_id=?",first));
        jdbc.update("update customer_order_notifications set status='SENT',claim_token=null,claimed_by=null,lease_until=null,"
                + "version=version+1 where id=?",first);
        var sent=jdbc.queryForMap("select * from customer_order_notifications where id=?",first);
        assertThat(sent).containsEntry("status","SENT").containsEntry("attempt_count",1).containsEntry("version",3L);
        assertThat(sent.get("sent_at")).isNotNull().isEqualTo(sent.get("closed_at"));
        assertThat(jdbc.queryForMap("select result,finished_at from customer_order_notification_attempts where notification_id=?",first).get("finished_at")).isNotNull();
        rejects("55000",() -> jdbc.update("update customer_order_notifications set status='EXPIRED',close_reason='STALE',version=version+1 where id=?",first));

        // With the earlier event closed the later one may now be claimed, skipped and is then final.
        claim(second,1);
        rejects("23514",() -> jdbc.update("update customer_order_notifications set status='SKIPPED',claim_token=null,claimed_by=null,"
                + "lease_until=null,version=version+1 where id=?",second));
        jdbc.update("update customer_order_notifications set status='SKIPPED',close_reason='NO_VERIFIED_EMAIL',claim_token=null,"
                + "claimed_by=null,lease_until=null,version=version+1 where id=?",second);
        assertThat(jdbc.queryForObject("select attempt_count from customer_order_notifications where id=?",Integer.class,second)).isZero();
        rejects("55000",() -> jdbc.update("update customer_order_notifications set status='EXPIRED',close_reason='STALE',version=version+1 where id=?",second));
    }

    @Test void retriesAreBoundedAndAnUncertainDeliveryIsNeverRetried() {
        Seed seed=seed();
        long id=order(seed);
        insertStatus(id,"CANCELLED");
        long n=((Number)notifications(id).getFirst().get("id")).longValue();
        for(int attempt=1;attempt<=2;attempt++){
            claim(n,1);
            jdbc.update("update customer_order_notifications set status='SENDING',attempt_count=attempt_count+1,version=version+1 where id=?",n);
            jdbc.update("update customer_order_notifications set status='RETRY_WAIT',next_attempt_at=clock_timestamp(),claim_token=null,"
                    + "claimed_by=null,lease_until=null,version=version+1 where id=?",n);
        }
        claim(n,1);
        jdbc.update("update customer_order_notifications set status='SENDING',attempt_count=3,version=version+1 where id=?",n);
        String unclaim=",claim_token=null,claimed_by=null,lease_until=null,version=version+1 where id=?";
        rejects("23514",() -> jdbc.update("update customer_order_notifications set status='RETRY_WAIT',next_attempt_at=clock_timestamp()"+unclaim,n));
        rejects("23514",() -> jdbc.update("update customer_order_notifications set status='EXHAUSTED',close_reason='DELIVERY_UNCERTAIN'"+unclaim,n));
        rejects("23514",() -> jdbc.update("update customer_order_notifications set status='RETRY_WAIT',delivery_uncertain=true,"
                + "next_attempt_at=clock_timestamp()"+unclaim,n));
        jdbc.update("update customer_order_notifications set status='EXHAUSTED',close_reason='DELIVERY_UNCERTAIN',delivery_uncertain=true"+unclaim,n);
        assertThat(jdbc.queryForMap("select status,attempt_count,delivery_uncertain from customer_order_notifications where id=?",n))
                .containsEntry("status","EXHAUSTED").containsEntry("attempt_count",3).containsEntry("delivery_uncertain",true);
    }

    // ---- fixtures ----

    private UUID claim(long id,int versionStep) {
        UUID token=UUID.randomUUID();
        jdbc.update("update customer_order_notifications set status='CLAIMED',next_attempt_at=null,claim_token=?,claimed_by=?,"
                + "lease_until=clock_timestamp()+interval '5 minutes',version=version+? where id=?",token,UUID.randomUUID(),versionStep,id);
        return token;
    }
    private void attempt(long id,UUID token,int no) {
        jdbc.update("insert into customer_order_notification_attempts(notification_id,attempt_no,claim_token,result,recipient,subject,body,started_at) "
                + "values (?,?,?,'IN_PROGRESS','customer@example.test','subject','body',now())",id,no,token);
    }
    private void insertStatus(long id,String to) {
        jdbc.update("insert into order_status_histories(order_id,from_status,to_status,changed_by_type,changed_by_account_id,changed_by_username,changed_at) "
                + "values (?,'ORDERED',?,'ADMIN',1,'fixture',localtimestamp)",id,to);
    }
    private static void rejects(String state,ThrowableAssertCall call) {
        assertThatThrownBy(call::run).satisfies(e -> {
            Throwable t=e;while(t!=null&&!(t instanceof SQLException))t=t.getCause();
            assertThat(t).as("SQL failure").isNotNull();
            assertThat(((SQLException)t).getSQLState()).isEqualTo(state);
        });
    }
    @FunctionalInterface interface ThrowableAssertCall { void run() throws Throwable; }

    private Seed seed() {
        return tx(() -> {
            var now=orders.findCurrentTimeInTokyo();String unique=UUID.randomUUID().toString();
            User user=new User();user.setUsername("notify-"+unique);user.setPassword("test");user.setEnabled(true);
            user.setCreatedAt(now);user.setUpdatedAt(now);users.saveAndFlush(user);
            var category=categories.saveAndFlush(new Category(unique));
            Product product=new Product();product.setName("notification product");product.setPrice(1000);product.setStock(100);
            product.setCategory(category);product.setTaxCategory(taxes.findStandardTaxCategory());products.saveAndFlush(product);
            return new Seed(user.getId(),product.getId(),admin("prep-"+unique,now),admin("ship-"+unique,now));
        });
    }
    private long order(Seed seed) {
        Cart cart=new Cart();cart.addItem(new CartItem(seed.product(),"notification product",1000,3));
        CheckoutForm f=new CheckoutForm();f.setShippingName("customer");f.setShippingPostalCode("100-0001");
        f.setShippingPrefecture("東京都");f.setShippingCity("千代田区");f.setShippingAddressLine("1-1");f.setShippingPhone("0312345678");
        return orderService.createOrder(seed.user(),"customer",cart,f).getId();
    }
    private String authorize(long id,AuthorizationResultStatus status) {
        String provider="pfw_flow_notify_"+UUID.randomUUID();
        when(gateway.prepareAuthorization(any())).thenReturn(new AuthorizationPreparation(provider,"test-secret"));
        when(gateway.retrieveAuthorization(provider)).thenReturn(new AuthorizationRecovery(
                new AuthorizationResult(status,provider,status==AuthorizationResultStatus.FAILED?"card_declined":null,null),null));
        Order order=tx(() -> orders.findById(id).orElseThrow());
        var prepared=authorization.prepareAuthorization(order);
        assertThat(authorization.refreshAuthorization(id,prepared.paymentId()).status()).isEqualTo(status);
        return provider;
    }
    private long paymentId(long id) { return jdbc.queryForObject("select id from payments where order_id=?",Long.class,id); }
    private Actor admin(String username,LocalDateTime now) {
        AdminAccount admin=new AdminAccount();admin.setUsername(username);admin.setPassword("test");admin.setEnabled(true);
        admin.setCreatedAt(now);admin.setUpdatedAt(now);admins.saveAndFlush(admin);return new Actor(admin.getId(),username);
    }
    private void authenticate(Actor actor) {
        var details=new AdminUserDetails(actor.id(),actor.username(),"test",true,List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(details,"",details.getAuthorities()));
    }
    private OrderItemChangeForm items(long id,int revision,int quantity) {
        var f=new OrderItemChangeForm();f.setContentRevision(revision);var item=new OrderItemChangeForm.Item();
        item.setOrderItemId(jdbc.queryForObject("select id from order_items where order_id=?",Long.class,id));
        item.setQuantity(quantity);f.getItems().add(item);return f;
    }
    private void advance(LocalDateTime time) {
        clock.advance(time);doReturn(clock.localTime()).when(orders).findCurrentTimeInTokyo();
    }
    private <T>T tx(Supplier<T> action) { return new TransactionTemplate(manager).execute(s->action.get()); }
    private LocalDateTime date(long id,String column) { return jdbc.queryForObject("select "+column+" from orders where id=?",LocalDateTime.class,id); }
    private List<Map<String,Object>> notifications(long id) {
        return jdbc.queryForList("select * from customer_order_notifications where order_id=? order by id",id);
    }
    private List<Object> types(long id) { return notifications(id).stream().map(r -> r.get("event_type")).toList(); }
    private Map<String,Object> notification(long id,String type) {
        return jdbc.queryForMap("select * from customer_order_notifications where order_id=? and event_type=?",id,type);
    }
    private JsonNode payload(long id,String type) throws Exception {
        return JSON.readTree(jdbc.queryForObject("select payload::text from customer_order_notifications where order_id=? and event_type=?",
                String.class,id,type));
    }
    record Actor(Long id,String username) {}
    record Seed(Long user,Long product,Actor preparer,Actor shipper) {}

    @TestConfiguration(proxyBeanMethods=false)
    static class TimeConfiguration {
        @Bean @Primary NotificationClock notificationClock() { return new NotificationClock(); }
    }
    static final class NotificationClock extends Clock {
        private final AtomicReference<Instant> fixed=new AtomicReference<>();
        void advance(LocalDateTime time) { fixed.set(time.truncatedTo(ChronoUnit.MICROS).atZone(TOKYO).toInstant()); }
        void resume() { fixed.set(null); }
        LocalDateTime localTime() { return LocalDateTime.ofInstant(instant(),TOKYO); }
        @Override public ZoneId getZone() { return TOKYO; }
        @Override public Clock withZone(ZoneId zone) { return zone.equals(TOKYO)?this:Clock.fixed(instant(),zone); }
        @Override public Instant instant() { Instant value=fixed.get();return value==null?Instant.now():value; }
    }
}
