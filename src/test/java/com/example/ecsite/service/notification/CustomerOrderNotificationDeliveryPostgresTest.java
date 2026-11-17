package com.example.ecsite.service.notification;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.example.ecsite.cart.*;
import com.example.ecsite.config.CustomerOrderNotificationProperties;
import com.example.ecsite.entity.*;
import com.example.ecsite.form.CheckoutForm;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.repository.*;
import com.example.ecsite.service.*;
import com.example.ecsite.service.notification.CustomerOrderNotificationMailClient.Outcome;
import com.example.ecsite.service.notification.CustomerOrderNotificationMailClient.Result;
import com.example.ecsite.service.warehouse.OrderShipmentTrackingReader;

/**
 * Feature133-B: delivery of recorded customer notifications. SMTP is replaced by a mock client; every state change
 * goes through the V91 guard on a real PostgreSQL 17.
 */
@SpringBootTest(properties={"logging.level.root=WARN", "spring.jpa.show-sql=false",
        "app.payment.reconciliation.enabled=false", "app.payment.discrepancy-audit.enabled=false",
        "app.customer-notification.enabled=true", "app.customer-notification.fixed-delay=1h"})
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@com.example.ecsite.support.CaptureConnectionTest
class CustomerOrderNotificationDeliveryPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17")
            .withDatabaseName("customer_delivery").withUsername("delivery_user").withPassword(UUID.randomUUID().toString());
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
        registry.add("spring.flyway.url",POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user",POSTGRES::getUsername);
        registry.add("spring.flyway.password",POSTGRES::getPassword);
    }
    @Autowired CustomerOrderNotificationDeliveryService delivery;
    @Autowired CustomerOrderNotificationScheduler scheduler;
    @Autowired CustomerOrderNotificationRenderer renderer;
    @Autowired OrderShipmentTrackingReader tracking;
    @Autowired OrderService orderService;
    @Autowired UserRepository users;
    @Autowired CategoryRepository categories;
    @Autowired ProductRepository products;
    @Autowired TaxCategoryService taxes;
    @Autowired OrderRepository orders;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CustomerOrderNotificationMailClient client;
    @MockitoBean PaymentGateway gateway;

    @BeforeEach void drainEarlierTests() {
        // Earlier tests in this class may leave due rows of other orders; close them so each test sees its own.
        when(client.send(any())).thenReturn(new Outcome(Result.SUCCESS, null));
        while (delivery.runPass() > 0) { }
        reset(client);
    }

    @Test void aVerifiedCustomerReceivesEachEventOnceInOrder() {
        long user=user(true);long order=order(user);
        status(order,"CANCELLED");status(order,"SHIPPED");
        var sent=new ArrayList<CustomerOrderNotificationMail>();
        when(client.send(any())).thenAnswer(call -> { sent.add(call.getArgument(0)); return new Outcome(Result.SUCCESS,null); });

        assertThat(delivery.runPass()).isEqualTo(2);

        assertThat(sent).extracting(CustomerOrderNotificationMail::recipient).containsOnly("customer-"+user+"@example.test");
        assertThat(sent.get(0).subject()).isEqualTo("【ECサイト】ご注文をキャンセルいたしました（注文番号 "+order+"）");
        assertThat(sent.get(1).subject()).contains("ご注文の商品を発送しました");
        assertThat(sent.get(1).body()).contains("http://localhost:8080/orders/"+order);
        assertThat(rows(order)).extracting(r -> r.get("status")).containsExactly("SENT","SENT");
        var attempts=attempts(order);
        assertThat(attempts).hasSize(2).allSatisfy(a -> assertThat(a).containsEntry("result","SUCCESS").containsEntry("failure_code",null)
                .containsEntry("attempt_no",1).containsEntry("recipient","customer-"+user+"@example.test"));
        assertThat(attempts.get(0).get("subject")).isEqualTo(sent.get(0).subject());
        assertThat(attempts.get(0).get("body")).isEqualTo(sent.get(0).body());
        assertThat(delivery.runPass()).isZero();
        verify(client,times(2)).send(any());
    }

    @Test void anUnverifiedOrMissingAddressIsSkippedWithoutSending() {
        long unverified=user(false);long a=order(unverified);status(a,"CANCELLED");
        long missing=user(true);jdbc.update("update users set email=null,email_verified_at=null where id=?",missing);
        long b=order(missing);status(b,"CANCELLED");

        delivery.runPass();

        for(long order:List.of(a,b)) assertThat(rows(order)).singleElement()
                .satisfies(r -> assertThat(r).containsEntry("status","SKIPPED").containsEntry("close_reason","NO_VERIFIED_EMAIL")
                        .containsEntry("attempt_count",0));
        assertThat(attempts(a)).isEmpty();
        verify(client,never()).send(any());
    }

    @Test void aFailedSendWaitsAndHoldsBackLaterEventsOfTheSameOrder() {
        long user=user(true);long order=order(user);status(order,"CANCELLED");status(order,"SHIPPED");
        when(client.send(any())).thenReturn(new Outcome(Result.FAILURE,"SMTP_CONNECT_FAILED"));

        assertThat(delivery.runPass()).isEqualTo(1);

        var first=rows(order).get(0);
        assertThat(first).containsEntry("status","RETRY_WAIT").containsEntry("attempt_count",1).containsEntry("delivery_uncertain",false);
        assertThat(jdbc.queryForObject("select next_attempt_at > clock_timestamp()+interval '4 minutes' from customer_order_notifications where id=?",
                Boolean.class,first.get("id"))).isTrue();
        assertThat(rows(order).get(1)).containsEntry("status","PENDING");
        assertThat(attempts(order)).singleElement().satisfies(a -> assertThat(a).containsEntry("result","FAILURE")
                .containsEntry("failure_code","SMTP_CONNECT_FAILED"));
        // Not due yet, and the shipment notice never overtakes the earlier cancellation notice.
        assertThat(delivery.runPass()).isZero();
        verify(client,times(1)).send(any());
    }

    @Test void retriesStopAtThreeAttempts() {
        long user=user(true);long order=order(user);status(order,"CANCELLED");
        var store=store(Duration.ZERO,Duration.ofMinutes(5),Duration.ofHours(24));
        for(int attempt=1;attempt<=3;attempt++){
            var claim=store.claim(UUID.randomUUID()).orElseThrow();
            assertThat(store.prepare(claim)).isPresent();
            assertThat(store.complete(claim,new Outcome(Result.FAILURE,"SMTP_CONNECT_FAILED"))).isTrue();
        }
        assertThat(rows(order)).singleElement().satisfies(r -> assertThat(r).containsEntry("status","EXHAUSTED")
                .containsEntry("close_reason","MAX_ATTEMPTS").containsEntry("attempt_count",3).containsEntry("delivery_uncertain",false));
        assertThat(attempts(order)).extracting(a -> a.get("attempt_no")).containsExactly(1,2,3);
        assertThat(store.claim(UUID.randomUUID())).isEmpty();
    }

    @Test void anUncertainSendIsClosedAndNeverResent() {
        long user=user(true);long order=order(user);status(order,"CANCELLED");
        when(client.send(any())).thenReturn(new Outcome(Result.UNKNOWN,"SMTP_SEND_UNCERTAIN"));

        delivery.runPass();delivery.runPass();

        assertThat(rows(order)).singleElement().satisfies(r -> assertThat(r).containsEntry("status","EXHAUSTED")
                .containsEntry("close_reason","DELIVERY_UNCERTAIN").containsEntry("delivery_uncertain",true).containsEntry("attempt_count",1));
        assertThat(attempts(order)).singleElement().satisfies(a -> assertThat(a).containsEntry("result","UNKNOWN"));
        verify(client,times(1)).send(any());
    }

    @Test void expiredLeasesAreRecoveredAndALostSendIsNeverRetried() {
        long user=user(true);long claimedOnly=order(user);status(claimedOnly,"CANCELLED");
        long sending=order(user);status(sending,"CANCELLED");
        var store=store(Duration.ofMinutes(5),Duration.ZERO,Duration.ofHours(24));
        var first=store.claim(UUID.randomUUID()).orElseThrow();
        var second=store.claim(UUID.randomUUID()).orElseThrow();
        assertThat(store.prepare(second)).isPresent();

        assertThat(store.recoverExpired()).isEqualTo(2);

        assertThat(rows(claimedOnly)).singleElement().satisfies(r -> assertThat(r).containsEntry("status","PENDING")
                .containsEntry("attempt_count",0).containsEntry("claim_token",null));
        assertThat(rows(sending)).singleElement().satisfies(r -> assertThat(r).containsEntry("status","EXHAUSTED")
                .containsEntry("close_reason","DELIVERY_UNCERTAIN").containsEntry("delivery_uncertain",true));
        assertThat(attempts(sending)).singleElement().satisfies(a -> assertThat(a).containsEntry("result","UNKNOWN")
                .containsEntry("failure_code","CLAIM_LOST"));
        // The recovered claims no longer belong to their old owners.
        assertThat(store.prepare(first)).isEmpty();
        assertThat(store.complete(second,new Outcome(Result.SUCCESS,null))).isFalse();
        assertThat(rows(sending).getFirst()).containsEntry("status","EXHAUSTED");
    }

    @Test void notificationsOlderThanTheMaximumAgeExpireInsteadOfBeingSent() {
        long user=user(true);long order=order(user);status(order,"CANCELLED");
        var store=store(Duration.ofMinutes(5),Duration.ofMinutes(5),Duration.ZERO);
        var claim=store.claim(UUID.randomUUID()).orElseThrow();
        assertThat(store.prepare(claim)).isEmpty();
        assertThat(rows(order)).singleElement().satisfies(r -> assertThat(r).containsEntry("status","EXPIRED")
                .containsEntry("close_reason","STALE").containsEntry("attempt_count",0));
        verify(client,never()).send(any());
    }

    @Test void theSchedulerRunsOnItsOwnThreadOnlyWhenEnabled() {
        assertThat(scheduler.isRunning()).isTrue();
        assertThatThrownBy(() -> new CustomerOrderNotificationProperties(true,Duration.ofSeconds(30),20,Duration.ofMinutes(5),
                Duration.ofSeconds(30),Duration.ofHours(24),new CustomerOrderNotificationProperties.Smtp(
                        Duration.ofSeconds(5),Duration.ofSeconds(10),Duration.ofSeconds(10))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("claim-lease");
    }

    // ---- fixtures ----

    private CustomerOrderNotificationStore store(Duration retryDelay,Duration claimLease,Duration maxAge) {
        var properties=mock(CustomerOrderNotificationProperties.class);
        when(properties.retryDelay()).thenReturn(retryDelay);
        when(properties.claimLease()).thenReturn(claimLease);
        when(properties.maxAge()).thenReturn(maxAge);
        var target=new CustomerOrderNotificationStore(jdbc,properties,renderer,tracking);
        // Run each call in its own transaction, like the proxied bean.
        return new CustomerOrderNotificationStore(jdbc,properties,renderer,tracking){
            private <T>T tx(java.util.function.Supplier<T> action){return new TransactionTemplate(manager).execute(s->action.get());}
            @Override public int recoverExpired(){return tx(target::recoverExpired);}
            @Override public Optional<Claim> claim(UUID owner){return tx(()->target.claim(owner));}
            @Override public Optional<CustomerOrderNotificationMail> prepare(Claim claim){return tx(()->target.prepare(claim));}
            @Override public boolean complete(Claim claim,Outcome outcome){return tx(()->target.complete(claim,outcome));}
        };
    }
    private long user(boolean verified) {
        return new TransactionTemplate(manager).execute(s -> {
            var now=orders.findCurrentTimeInTokyo();
            User user=new User();user.setUsername("delivery-"+UUID.randomUUID());user.setPassword("test");user.setEnabled(true);
            user.setCreatedAt(now);user.setUpdatedAt(now);users.saveAndFlush(user);
            jdbc.update("update users set email=?,email_verified_at=? where id=?","customer-"+user.getId()+"@example.test",
                    verified?now:null,user.getId());
            return user.getId();
        });
    }
    private long order(long user) {
        long product=new TransactionTemplate(manager).execute(s -> {
            var category=categories.saveAndFlush(new Category(UUID.randomUUID().toString()));
            Product p=new Product();p.setName("delivery product");p.setPrice(1000);p.setStock(100);
            p.setCategory(category);p.setTaxCategory(taxes.findStandardTaxCategory());return products.saveAndFlush(p).getId();
        });
        Cart cart=new Cart();cart.addItem(new CartItem(product,"delivery product",1000,1));
        CheckoutForm f=new CheckoutForm();f.setShippingName("customer");f.setShippingPostalCode("100-0001");
        f.setShippingPrefecture("東京都");f.setShippingCity("千代田区");f.setShippingAddressLine("1-1");f.setShippingPhone("0312345678");
        return orderService.createOrder(user,"customer",cart,f).getId();
    }
    private void status(long order,String to) {
        jdbc.update("insert into order_status_histories(order_id,from_status,to_status,changed_by_type,changed_by_account_id,changed_by_username,changed_at) "
                + "values (?,'ORDERED',?,'ADMIN',1,'fixture',localtimestamp)",order,to);
    }
    private List<Map<String,Object>> rows(long order) {
        return jdbc.queryForList("select * from customer_order_notifications where order_id=? order by id",order);
    }
    private List<Map<String,Object>> attempts(long order) {
        return jdbc.queryForList("select a.* from customer_order_notification_attempts a join customer_order_notifications n on n.id=a.notification_id "
                + "where n.order_id=? order by a.id",order);
    }
}
