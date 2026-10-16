package com.example.ecsite.service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {"logging.level.root=WARN", "spring.jpa.show-sql=false"})
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentShippingPreparationValidationPostgresTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("shipping_preparation_validation")
            .withUsername("shipping_validation").withPassword("shipping-validation-test")
            .withCommand("postgres", "-c", "lock_timeout=10000", "-c", "statement_timeout=15000");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired PaymentService paymentService;
    @Autowired OrderRepository orders;
    @Autowired UserRepository users;
    @Autowired PaymentRepository payments;
    @Autowired PaymentTransactionRepository transactions;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PaymentGateway gateway;

    @ParameterizedTest
    @ValueSource(ints = {1000, 800})
    void validAuthorizationWithInactiveHistoryDoesNotChangeDatabase(int orderAmount) {
        Fixture fixture = fixture(orderAmount);
        Map<String, Object> before = snapshot(fixture.orderId());

        assertThatCode(() -> validateUnderOrderLock(fixture.orderId())).doesNotThrowAnyException();

        assertThat(snapshot(fixture.orderId())).isEqualTo(before);
        verifyNoInteractions(gateway);
    }

    @Test
    void pendingRefundOnPastPaymentRejectsWithoutChangingDatabase() {
        Fixture fixture = fixture(1000);
        tx(() -> {
            Payment past = payments.findById(fixture.failedPaymentId()).orElseThrow();
            transactions.saveAndFlush(new PaymentTransaction(past, PaymentTransactionType.REFUND,
                    1000, 0, UUID.randomUUID().toString(), LocalDateTime.now()));
            return null;
        });
        Map<String, Object> before = snapshot(fixture.orderId());

        assertThatThrownBy(() -> validateUnderOrderLock(fixture.orderId()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(snapshot(fixture.orderId())).isEqualTo(before);
        verifyNoInteractions(gateway);
    }

    @Test
    void proxiedValidationRequiresAnExistingTransaction() {
        Fixture fixture = fixture(1000);
        Order order = orders.findById(fixture.orderId()).orElseThrow();
        Map<String, Object> before = snapshot(fixture.orderId());

        assertThatThrownBy(() -> paymentService.validateCardPaymentForShippingPreparation(order))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(snapshot(fixture.orderId())).isEqualTo(before);
        verifyNoInteractions(gateway);
    }

    private void validateUnderOrderLock(Long orderId) {
        tx(() -> {
            Order order = orders.findByIdForUpdate(orderId).orElseThrow();
            paymentService.validateCardPaymentForShippingPreparation(order);
            return null;
        });
    }

    private Fixture fixture(int orderAmount) {
        return tx(() -> {
            LocalDateTime now = LocalDateTime.now();
            User user = new User();
            user.setUsername("shipping-validation-" + UUID.randomUUID());
            user.setPassword("password");
            user.setEnabled(true);
            user.setCreatedAt(now);
            user.setUpdatedAt(now);
            users.saveAndFlush(user);
            Order order = new Order(user.getId(), orderAmount, now, now.plusDays(1));
            if (orderAmount < 1000) order.incrementContentRevision();
            orders.saveAndFlush(order);

            Payment failed = new Payment(order, PaymentProvider.PAYJP, PaymentMethod.CARD, 1000, now);
            failed.markFailed(now);
            payments.saveAndFlush(failed);
            Payment cancelled = new Payment(order, PaymentProvider.PAYJP, PaymentMethod.CARD, 1000, now);
            cancelled.markAuthorized(now);
            cancelled.markCancelled(now);
            payments.saveAndFlush(cancelled);
            Payment active = new Payment(order, PaymentProvider.PAYJP, PaymentMethod.CARD, 1000, now);
            String providerId = "pf_" + UUID.randomUUID();
            active.setProviderPaymentId(providerId, now);
            active.markAuthorized(now);
            payments.saveAndFlush(active);
            PaymentTransaction authorization = new PaymentTransaction(active, PaymentTransactionType.AUTHORIZE,
                    1000, 0, UUID.randomUUID().toString(), now);
            authorization.markSucceeded(providerId, now);
            transactions.saveAndFlush(authorization);
            return new Fixture(order.getId(), failed.getId());
        });
    }

    private Map<String, Object> snapshot(Long orderId) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("order", jdbc.queryForMap("select * from orders where id=?", orderId));
        for (String table : List.of("order_items", "order_charges", "payments", "stock_movements",
                "order_status_histories", "order_content_change_histories", "order_shipping_address_histories")) {
            state.put(table, jdbc.queryForList("select * from " + table + " where order_id=? order by id", orderId));
        }
        state.put("transactions", jdbc.queryForList("select t.* from payment_transactions t "
                + "join payments p on p.id=t.payment_id where p.order_id=? order by t.id", orderId));
        state.put("stock", jdbc.queryForList("select id, stock from products order by id"));
        return state;
    }

    private <T> T tx(Supplier<T> action) {
        return new TransactionTemplate(manager).execute(status -> action.get());
    }

    private record Fixture(Long orderId, Long failedPaymentId) {}
}
