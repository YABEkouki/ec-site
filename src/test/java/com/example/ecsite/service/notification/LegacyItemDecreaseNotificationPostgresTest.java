package com.example.ecsite.service.notification;

import static org.assertj.core.api.Assertions.*;

import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.support.LegacyItemDecreaseFixture;
import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.repository.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Feature133-A: the notification of an item decrease made before Feature141-A closed that route (schema V108). */
@SpringBootTest(properties={"logging.level.root=WARN", "spring.jpa.show-sql=false", "spring.flyway.target=108",
        "app.payment.reconciliation.enabled=false", "app.payment.discrepancy-audit.enabled=false"})
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@com.example.ecsite.support.CaptureConnectionTest
class LegacyItemDecreaseNotificationPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17")
            .withDatabaseName("legacy_item_notification").withUsername("notification_user").withPassword(UUID.randomUUID().toString());
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
        registry.add("spring.flyway.url",POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user",POSTGRES::getUsername);
        registry.add("spring.flyway.password",POSTGRES::getPassword);
    }
    @Autowired ApplicationContext context;
    @Autowired OrderRepository orders;
    @Autowired UserRepository users;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired TaxCategoryRepository taxes;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PaymentGateway gateway;

    @Test void decreaseRecordsOneItemsChangedNotificationWithTheSavedAmounts() {
        long[] ids=new TransactionTemplate(manager).execute(s->{
            String unique=UUID.randomUUID().toString();LocalDateTime now=LocalDateTime.now();
            User user=new User();user.setUsername("items-"+unique);user.setPassword("test");user.setEnabled(true);
            user.setCreatedAt(now);user.setUpdatedAt(now);users.saveAndFlush(user);
            var category=categories.saveAndFlush(new Category(unique));var tax=taxes.findAll().getFirst();
            Product product=new Product();product.setName("notification product");product.setPrice(1000);product.setStock(7);
            product.setCategory(category);product.setTaxCategory(tax);products.saveAndFlush(product);
            Order order=new Order(user.getId(),3550,now,now.plusDays(1));
            order.addItem(new OrderItem(product.getId(),product.getName(),category.getId(),category.getName(),
                    tax.getId(),tax.getCode(),tax.getName(),tax.getTaxRate(),1000,3));
            order.addCharge(new OrderCharge(OrderChargeType.SHIPPING,"送料・梱包料",550,tax.getId(),tax.getCode(),tax.getName(),tax.getTaxRate(),10));
            orders.saveAndFlush(order);
            return new long[]{order.getId(),user.getId(),order.getItems().getFirst().getId(),product.getId()};
        });
        jdbc.update("update orders set item_subtotal=3000,charge_total=550,tax_amount=322,total_amount=3550 where id=?",ids[0]);

        LegacyItemDecreaseFixture.decrease(context,ids[0],ids[1],"customer",Map.of(ids[2],2));

        var rows=jdbc.queryForList("select payload::text as payload,source_key,user_id,status from customer_order_notifications where order_id=? and event_type='ITEMS_CHANGED'",ids[0]);
        assertThat(rows).hasSize(1);
        long history=jdbc.queryForObject("select id from order_content_change_histories where order_id=?",Long.class,ids[0]);
        assertThat(rows.getFirst()).containsEntry("source_key",String.valueOf(history)).containsEntry("user_id",ids[1]).containsEntry("status","PENDING");
        var changed=new ObjectMapper().readTree((String)rows.getFirst().get("payload"));
        assertThat(changed.at("/change/oldTotalAmount").asInt()).isEqualTo(3550);
        assertThat(changed.at("/change/newTotalAmount").asInt()).isEqualTo(2550);
        assertThat(changed.at("/change/items/0/oldQuantity").asInt()).isEqualTo(3);
        assertThat(changed.at("/change/items/0/newQuantity").asInt()).isEqualTo(2);
        assertThat(changed.at("/order/totalAmount").asInt()).isEqualTo(2550);
        assertThat(changed.at("/order/items/0/quantity").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select stock from products where id=?",Integer.class,ids[3])).isEqualTo(8);
    }
}
