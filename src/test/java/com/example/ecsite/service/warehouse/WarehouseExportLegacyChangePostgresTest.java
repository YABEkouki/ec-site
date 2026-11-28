package com.example.ecsite.service.warehouse;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.repository.*;
import com.example.ecsite.service.OrderStatusHistoryService;
import com.example.ecsite.service.orderchange.LegacyOrderIncreaseFixture;
import com.example.ecsite.service.warehouse.WarehouseExportHoldException.Reason;

/** Feature131 C-1 with a legacy quantity increase accepted before Feature140-A (schema V107). */
@SpringBootTest(properties = {"logging.level.root=WARN", "spring.jpa.show-sql=false", "spring.flyway.target=107",
        "app.payment.reconciliation.enabled=false", "app.payment.discrepancy-audit.enabled=false"})
@com.example.ecsite.support.CaptureConnectionTest
class WarehouseExportLegacyChangePostgresTest {
    @Autowired WarehouseExportService service;
    @Autowired WarehouseExportBatchStore batches;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrderRepository orders;
    @Autowired UserRepository users;
    @Autowired CategoryRepository categories;
    @Autowired TaxCategoryRepository taxes;
    @Autowired ProductRepository products;
    @Autowired PaymentRepository payments;
    @Autowired PaymentTransactionRepository transactions;
    @Autowired OrderStatusHistoryService histories;
    @Autowired PlatformTransactionManager manager;
    @Autowired org.springframework.context.ApplicationContext context;
    @MockitoBean PaymentGateway gateway;

    record Fixture(long order, long user, long item) {}

    Fixture order() {
        return new TransactionTemplate(manager).execute(s -> {
            var now = orders.findCurrentTimeInTokyo(); String key = UUID.randomUUID().toString();
            var user = new User(); user.setUsername("f140-" + key); user.setPassword("unused"); user.setEnabled(true);
            user.setCreatedAt(now); user.setUpdatedAt(now); users.saveAndFlush(user);
            var category = categories.saveAndFlush(new Category(key)); var tax = taxes.findAll().getFirst();
            var product = new Product(); product.setName("item"); product.setPrice(900); product.setStock(20);
            product.setCategory(category); product.setTaxCategory(tax); products.saveAndFlush(product);
            var order = new Order(user.getId(), 750, now, now.plusHours(1));
            order.setShippingAddress("山田 太郎", "100-0001", "東京都", "千代田区", "千代田1-1", "0312345678");
            order.addItem(new OrderItem(product.getId(), "ordered", category.getId(), category.getName(), tax.getId(), tax.getCode(),
                    tax.getName(), tax.getTaxRate(), 100, 2));
            order.addCharge(new OrderCharge(OrderChargeType.SHIPPING, "送料・梱包料", 550, tax.getId(), tax.getCode(), tax.getName(),
                    tax.getTaxRate(), 10));
            orders.saveAndFlush(order);
            histories.record(order, null, OrderStatus.ORDERED, OrderStatusHistoryActorType.USER, user.getId(), user.getUsername(), null);
            // Feature147: on purpose, this legacy fixture leaves the order without an active payment (like orders before V55),
            // so the legacy increase also covers ActivePaymentService.initializeForOrderChange setting it.
            var payment = new Payment(order, PaymentProvider.PAYJP, PaymentMethod.CARD, 750, now);
            payment.setProviderPaymentId("pfw_" + key, now); payment.markAuthorized(now); payments.saveAndFlush(payment);
            var auth = new PaymentTransaction(payment, PaymentTransactionType.AUTHORIZE, 750, 0, key, now,
                    PaymentTransactionPurpose.INITIAL_ORDER_AUTHORIZATION);
            auth.markSucceeded(payment.getProviderPaymentId(), now); transactions.saveAndFlush(auth);
            return new Fixture(order.getId(), user.getId(), order.getItems().getFirst().getId());
        });
    }

    @Test void anUnresolvedOrderChangeHoldsTheOrder() {
        var f = order();
        // Accepted while the deadline was open; then the order and the operation move into the past.
        var op = LegacyOrderIncreaseFixture.accept(context, f.order(), f.user(), Map.of(f.item(), 3));
        var date = jdbc.queryForObject("select (timezone('Asia/Tokyo',clock_timestamp()))::date - 3000", java.time.LocalDate.class);
        var deadline = date.atTime(14, 0);
        jdbc.update("update orders set ordered_at=?, change_deadline_at=? where id=?", deadline.minusHours(2), deadline, f.order());
        jdbc.update("update order_change_operations set accepted_at=?,deadline_at=?,grace_until=? where id=?",
                deadline.minusMinutes(30), deadline, deadline.plusMinutes(10), op.id());
        var outcome = service.export(batches.openOrGet(date).id(), f.order());
        assertThat(outcome.isExported()).isFalse();
        assertThat(outcome.held()).isEqualTo(Reason.ORDER_CHANGE_UNRESOLVED);
        assertThat(jdbc.queryForObject("select shipping_preparation_started_at from orders where id=?",
                java.time.LocalDateTime.class, f.order())).isNull();
        assertThat(jdbc.queryForObject("select count(*) from warehouse_export_lines where order_id=?", Integer.class, f.order())).isZero();
    }
}
