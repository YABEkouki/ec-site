package com.example.ecsite.payment.safety;

import com.example.ecsite.form.OrderItemChangeForm;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.service.OrderService;
import com.example.ecsite.service.order.evidence.LegacyOrderEvidenceEnrollment;
import com.example.ecsite.service.order.evidence.OrderChangeRoutePolicy;
import com.example.ecsite.service.order.evidence.OrderEditExecutionService;
import org.junit.jupiter.api.TestInfo;
import org.springframework.context.ApplicationContext;
import java.util.List;
import java.util.UUID;
import static com.example.ecsite.service.order.evidence.OrderEditCandidate.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Composes existing writer fixtures; does not inherit their JUnit test methods. */
final class CustomerOrderChangeSavedHistoryFixture {
    final OrderEditReauthorizationIntegrationTest writer = new OrderEditReauthorizationIntegrationTest();
    private final ApplicationContext context;

    CustomerOrderChangeSavedHistoryFixture(ApplicationContext context, PaymentGateway gateway, TestInfo info) {
        this.context = context;
        writer.environment = context.getEnvironment();
        writer.applicationJdbc = context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        writer.finance = context.getBean(com.example.ecsite.service.orderchange.OrderReauthorizationService.class);
        writer.financeTransactions = context.getBean(com.example.ecsite.service.orderchange.OrderReauthorizationTransactions.class);
        writer.requestStore = context.getBean(com.example.ecsite.payment.orderchange.OrderChangeFinancialRequestStore.class);
        writer.manager = context.getBean(org.springframework.transaction.PlatformTransactionManager.class);
        writer.gateway = gateway;
        writer.fixtureDatabase(info);
    }

    UUID apply(OrderEditAdmissionServicePostgresTest.Fixture order, List<Line> lines, OrderChangeRoutePolicy.Route route) {
        UUID admission = writer.accepted(order, lines, route);
        writer.mockTransport(order);
        assertThat(writer.service().execute(admission).status()).isEqualTo(OrderEditExecutionService.Status.COMPLETED);
        return admission;
    }

    record Legacy(long order, long owner, long item, long removed, long product) {}

    Legacy legacyWithTwoItems(String name) {
        var base = new LegacyOrderEvidenceEnrollmentPostgresTest().fixture();
        var jdbc = writer.db.jdbc;
        long owner = jdbc.queryForObject("select user_id from orders where id=?", Long.class, base.order());
        jdbc.update("update order_items set product_name=?,price=3000,quantity=3,subtotal=9000 where order_id=?", name, base.order());
        long item = jdbc.queryForObject("select id from order_items where order_id=?", Long.class, base.order());
        long product = jdbc.queryForObject("select product_id from order_items where id=?", Long.class, item);
        long removed = jdbc.queryForObject("""
                insert into order_items(order_id,product_id,product_name,category_id,category_name,price,quantity,subtotal,
                    tax_category_id,tax_category_code,tax_category_name,tax_rate)
                select order_id,product_id,'削除商品',category_id,category_name,price,1,3000,
                    tax_category_id,tax_category_code,tax_category_name,tax_rate from order_items where id=? returning id
                """, Long.class, item);
        jdbc.update("update orders set item_subtotal=12000,total_amount=12000,tax_amount=1090,ordered_at=date_trunc('day',timezone('Asia/Tokyo',clock_timestamp()))+interval '2 days 10 hours',change_deadline_at=date_trunc('day',timezone('Asia/Tokyo',clock_timestamp()))+interval '2 days 14 hours' where id=?", base.order());
        jdbc.update("update payments set amount=12000 where id=?", base.payment());
        jdbc.update("update payment_transactions set amount=12000 where id=?", base.auth());
        return new Legacy(base.order(), owner, item, removed, product);
    }

    void decrease(Legacy order, int revision, int quantity, boolean remove) {
        var form = new OrderItemChangeForm();
        form.setContentRevision(revision);
        var item = new OrderItemChangeForm.Item(); item.setOrderItemId(order.item()); item.setQuantity(quantity);
        form.getItems().add(item);
        if (remove) {
            var deleted = new OrderItemChangeForm.Item(); deleted.setOrderItemId(order.removed()); deleted.setQuantity(0);
            form.getItems().add(deleted);
        }
        assertThat(context.getBean(OrderService.class).changeItemsForUser(order.order(), order.owner(), "saved-history-test", form)).isTrue();
    }

    OrderEditAdmissionServicePostgresTest.Fixture enroll(Legacy order) {
        var enrolled = new LegacyOrderEvidenceEnrollment(writer.db.source, writer.gateway,
                OrderEditReauthorizationIntegrationTest.CONNECTION).enrollForCustomer(order.order(), order.owner());
        assertThat(enrolled.status()).isEqualTo(LegacyOrderEvidenceEnrollment.Status.ENROLLED);
        var jdbc = writer.db.jdbc;
        long payment = jdbc.queryForObject("select active_payment_id from orders where id=?", Long.class, order.order());
        long auth = jdbc.queryForObject("select id from payment_transactions where payment_id=? and transaction_type='AUTHORIZE'", Long.class, payment);
        return new OrderEditAdmissionServicePostgresTest.Fixture(order.order(), order.owner(), payment, auth,
                order.item(), order.product(), enrolled.epochId());
    }

    void close() { writer.stopRuntime(); }
}
