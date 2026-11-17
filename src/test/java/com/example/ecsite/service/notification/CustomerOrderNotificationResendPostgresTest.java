package com.example.ecsite.service.notification;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.sql.SQLException;
import java.util.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.example.ecsite.cart.*;
import com.example.ecsite.entity.*;
import com.example.ecsite.form.CheckoutForm;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.repository.*;
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.*;

/** Feature133-C: administrator screens and manual resend (V92) of customer order notifications. */
@SpringBootTest(properties={"logging.level.root=WARN", "spring.jpa.show-sql=false",
        "app.payment.reconciliation.enabled=false", "app.payment.discrepancy-audit.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@com.example.ecsite.support.CaptureConnectionTest
class CustomerOrderNotificationResendPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17")
            .withDatabaseName("customer_resend").withUsername("resend_user").withPassword(UUID.randomUUID().toString());
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
        registry.add("spring.flyway.url",POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user",POSTGRES::getUsername);
        registry.add("spring.flyway.password",POSTGRES::getPassword);
    }
    private static final String BASE="/admin/customer-notifications";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrderService orderService;
    @Autowired UserRepository users;
    @Autowired AdminAccountRepository admins;
    @Autowired CategoryRepository categories;
    @Autowired ProductRepository products;
    @Autowired TaxCategoryService taxes;
    @Autowired OrderRepository orders;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean PaymentGateway gateway;

    record Admin(long id,String username){}

    // ---- database guard ----

    @Test void onlyAClosedUndeliveredLatestNotificationCanBeResentThreeTimes() {
        var admin=admin();long order=order();history(order,"CANCELLED");long original=only(order);
        // Still pending: not resendable.
        rejects("23514",() -> resend(original,admin,"pending",false));
        skip(original);

        UUID nonce=resend(original,admin,"お客様がメールアドレスを確認したため",false);
        var copy=jdbc.queryForMap("select * from customer_order_notifications where resend_of=?",original);
        var source=jdbc.queryForMap("select * from customer_order_notifications where id=?",original);
        assertThat(copy).containsEntry("status","PENDING").containsEntry("event_type",source.get("event_type"))
                .containsEntry("order_id",order).containsEntry("user_id",source.get("user_id"))
                .containsEntry("source_key",nonce.toString()).containsEntry("attempt_count",0);
        assertThat(copy.get("payload").toString()).isEqualTo(source.get("payload").toString());
        assertThat(jdbc.queryForMap("select request_no,actor_username,reason from customer_order_notification_resends where request_nonce=?",nonce))
                .containsEntry("request_no",1).containsEntry("actor_username",admin.username());
        // The original never changes; its copy cannot be resent directly and the chain waits for the copy.
        assertThat(jdbc.queryForObject("select status from customer_order_notifications where id=?",String.class,original)).isEqualTo("SKIPPED");
        long copyId=((Number)copy.get("id")).longValue();
        rejects("23514",() -> resend(copyId,admin,"copy",false));
        rejects("23514",() -> resend(original,admin,"while open",false));

        skip(copyId);
        resend(original,admin,"2回目",false);skip(latest(original));
        resend(original,admin,"3回目",false);skip(latest(original));
        rejects("23514",() -> resend(original,admin,"4回目",false));
        assertThat(jdbc.queryForList("select request_no from customer_order_notification_resends where notification_id=? order by id",Integer.class,original))
                .containsExactly(1,2,3);

        // Requests and links are append-only.
        rejects("55000",() -> jdbc.update("update customer_order_notification_resends set reason='x' where notification_id=?",original));
        rejects("55000",() -> jdbc.update("delete from customer_order_notification_resends where notification_id=?",original));
        rejects("55000",() -> jdbc.update("update customer_order_notifications set resend_of=null,resend_request_id=null,version=version+1 where id=?",copyId));
    }

    @Test void aPossiblyDeliveredNotificationNeedsAnAcknowledgedRiskAndAKnownAdministrator() {
        var admin=admin();long order=order();history(order,"SHIPPED");long original=only(order);
        uncertain(original);
        rejects("23514",() -> resend(original,admin,"no ack",false));
        rejects("23514",() -> resend(original,new Admin(admin.id(),"someone-else"),"wrong name",true));
        resend(original,admin,"お客様から未着の連絡",true);
        assertThat(jdbc.queryForObject("select count(*) from customer_order_notifications where resend_of=?",Integer.class,original)).isOne();
    }

    // ---- screens ----

    @Test void screensRequireAnAdministrator() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().is3xxRedirection());
        mvc.perform(get(BASE).with(user("customer").roles("USER"))).andExpect(status().isForbidden());
    }

    @Test void listDetailOrderPageAndDashboardShowNotificationsAndActions() throws Exception {
        var admin=admin();long order=order();history(order,"CANCELLED");long id=only(order);
        claim(id);
        jdbc.update("update customer_order_notifications set status='SENDING',attempt_count=1,version=version+1 where id=?",id);
        jdbc.update("insert into customer_order_notification_attempts(notification_id,attempt_no,claim_token,result,recipient,subject,body,started_at) "
                + "select id,1,claim_token,'IN_PROGRESS','shown@example.test','件名です','本文です',now() from customer_order_notifications where id=?",id);
        jdbc.update("update customer_order_notification_attempts set result='UNKNOWN',failure_code='SMTP_SEND_UNCERTAIN' where notification_id=?",id);
        jdbc.update("update customer_order_notifications set status='EXHAUSTED',close_reason='DELIVERY_UNCERTAIN',delivery_uncertain=true,"
                + "claim_token=null,claimed_by=null,lease_until=null,version=version+1 where id=?",id);

        mvc.perform(get(BASE).with(as(admin))).andExpect(status().isOk())
                .andExpect(content().string(containsString("注文取消"))).andExpect(content().string(containsString("送信結果不明")))
                .andExpect(content().string(containsString("送信（バックグラウンド）が無効")));
        mvc.perform(get(BASE).param("filter","ACTION_REQUIRED").param("orderId",String.valueOf(order)).with(as(admin)))
                .andExpect(status().isOk()).andExpect(content().string(containsString("（要対応）")));
        mvc.perform(get(BASE).param("filter","SENT").param("orderId",String.valueOf(order)).with(as(admin)))
                .andExpect(content().string(containsString("該当する通知はありません。")));
        mvc.perform(get(BASE).param("filter","NOPE").with(as(admin))).andExpect(status().isBadRequest());
        mvc.perform(get(BASE+"/"+id).with(as(admin))).andExpect(status().isOk())
                .andExpect(content().string(containsString("shown@example.test"))).andExpect(content().string(containsString("本文です")))
                .andExpect(content().string(containsString("このメールを再送する")));
        mvc.perform(get("/admin/orders/"+order).with(as(admin))).andExpect(status().isOk())
                .andExpect(content().string(containsString("お客様へのメール")))
                .andExpect(content().string(containsString(BASE+"/"+id)));
        mvc.perform(get("/admin").with(as(admin))).andExpect(status().isOk())
                .andExpect(content().string(containsString("送信失敗・結果不明")));
    }

    @Test void resendGoesThroughConfirmationAndIsRecordedOnce() throws Exception {
        var admin=admin();long order=order();history(order,"CANCELLED");long id=only(order);
        skip(id);
        String nonce=UUID.randomUUID().toString();

        mvc.perform(get(BASE+"/"+id+"/resend").with(as(admin))).andExpect(status().isOk())
                .andExpect(content().string(containsString("確認画面へ")));
        mvc.perform(post(BASE+"/"+id+"/resend/confirm").with(as(admin)).with(csrf())
                .param("nonce",nonce).param("expectedResendCount","0").param("reason","  "))
                .andExpect(status().isOk()).andExpect(content().string(containsString("再送理由を入力してください。")));
        mvc.perform(post(BASE+"/"+id+"/resend/confirm").with(as(admin)).with(csrf())
                .param("nonce",nonce).param("expectedResendCount","0").param("reason","メール確認後に再送"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("再送する")));
        assertThat(resends(id)).isZero();

        for(int i=0;i<2;i++)
            mvc.perform(post(BASE+"/"+id+"/resend").with(as(admin)).with(csrf())
                    .param("nonce",nonce).param("expectedResendCount","0").param("reason","メール確認後に再送"))
                    .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl(BASE+"/"+id))
                    .andExpect(flash().attribute("message",i==0?containsString("再送を受け付けました"):is("この再送はすでに受け付けています。")));
        assertThat(resends(id)).isOne();
        assertThat(jdbc.queryForMap("select actor_admin_id,actor_username,reason,risk_acknowledged from customer_order_notification_resends where notification_id=?",id))
                .containsEntry("actor_admin_id",admin.id()).containsEntry("actor_username",admin.username())
                .containsEntry("reason","メール確認後に再送").containsEntry("risk_acknowledged",false);

        // A second administrator confirming against the old count is refused once the first copy closed.
        skip(latest(id));
        mvc.perform(post(BASE+"/"+id+"/resend").with(as(admin)).with(csrf())
                .param("nonce",UUID.randomUUID().toString()).param("expectedResendCount","0").param("reason","古い画面から"))
                .andExpect(flash().attribute("errorMessage",containsString("状態が変わった")));
        assertThat(resends(id)).isOne();
    }

    @Test void anUncertainNotificationRequiresTheRiskCheckbox() throws Exception {
        var admin=admin();long order=order();history(order,"SHIPPED");long id=only(order);
        uncertain(id);
        String nonce=UUID.randomUUID().toString();
        mvc.perform(post(BASE+"/"+id+"/resend/confirm").with(as(admin)).with(csrf())
                .param("nonce",nonce).param("expectedResendCount","0").param("reason","未着の連絡"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("2通届く可能性があることを確認してください。")));
        mvc.perform(post(BASE+"/"+id+"/resend").with(as(admin)).with(csrf())
                .param("nonce",nonce).param("expectedResendCount","0").param("reason","未着の連絡"))
                .andExpect(flash().attribute("errorMessage",containsString("確認が必要です")));
        assertThat(resends(id)).isZero();
        mvc.perform(post(BASE+"/"+id+"/resend").with(as(admin)).with(csrf())
                .param("nonce",nonce).param("expectedResendCount","0").param("reason","未着の連絡").param("riskAcknowledged","true"))
                .andExpect(flash().attribute("message",containsString("再送を受け付けました")));
        assertThat(resends(id)).isOne();
    }

    // ---- fixtures ----

    private RequestPostProcessor as(Admin admin) {
        return user(new AdminUserDetails(admin.id(),admin.username(),"password",true,List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
    private Admin admin() {
        return new TransactionTemplate(manager).execute(s -> {
            var now=orders.findCurrentTimeInTokyo();
            AdminAccount a=new AdminAccount();a.setUsername("resend-admin-"+UUID.randomUUID().toString().substring(0,8));
            a.setPassword("test");a.setEnabled(true);a.setCreatedAt(now);a.setUpdatedAt(now);admins.saveAndFlush(a);
            return new Admin(a.getId(),a.getUsername());
        });
    }
    private long order() {
        return new TransactionTemplate(manager).execute(s -> {
            var now=orders.findCurrentTimeInTokyo();
            User user=new User();user.setUsername("resend-"+UUID.randomUUID());user.setPassword("test");user.setEnabled(true);
            user.setCreatedAt(now);user.setUpdatedAt(now);users.saveAndFlush(user);
            var category=categories.saveAndFlush(new Category(UUID.randomUUID().toString()));
            Product p=new Product();p.setName("resend product");p.setPrice(1000);p.setStock(100);
            p.setCategory(category);p.setTaxCategory(taxes.findStandardTaxCategory());products.saveAndFlush(p);
            return new long[]{user.getId(),p.getId()};
        }) instanceof long[] ids ? place(ids[0],ids[1]) : 0;
    }
    private long place(long user,long product) {
        Cart cart=new Cart();cart.addItem(new CartItem(product,"resend product",1000,1));
        CheckoutForm f=new CheckoutForm();f.setShippingName("customer");f.setShippingPostalCode("100-0001");
        f.setShippingPrefecture("東京都");f.setShippingCity("千代田区");f.setShippingAddressLine("1-1");f.setShippingPhone("0312345678");
        return orderService.createOrder(user,"customer",cart,f).getId();
    }
    private void history(long order,String to) {
        jdbc.update("insert into order_status_histories(order_id,from_status,to_status,changed_by_type,changed_by_account_id,changed_by_username,changed_at) "
                + "values (?,'ORDERED',?,'ADMIN',1,'fixture',localtimestamp)",order,to);
    }
    private long only(long order) {
        return jdbc.queryForObject("select id from customer_order_notifications where order_id=? and resend_of is null",Long.class,order);
    }
    private long latest(long original) {
        return jdbc.queryForObject("select max(id) from customer_order_notifications where id=? or resend_of=?",Long.class,original,original);
    }
    private int resends(long original) {
        return jdbc.queryForObject("select count(*) from customer_order_notification_resends where notification_id=?",Integer.class,original);
    }
    private void claim(long id) {
        jdbc.update("update customer_order_notifications set status='CLAIMED',next_attempt_at=null,claim_token=gen_random_uuid(),"
                + "claimed_by=gen_random_uuid(),lease_until=clock_timestamp()+interval '5 minutes',version=version+1 where id=?",id);
    }
    private void skip(long id) {
        claim(id);
        jdbc.update("update customer_order_notifications set status='SKIPPED',close_reason='NO_VERIFIED_EMAIL',claim_token=null,"
                + "claimed_by=null,lease_until=null,version=version+1 where id=?",id);
    }
    private void uncertain(long id) {
        claim(id);
        jdbc.update("update customer_order_notifications set status='SENDING',attempt_count=1,version=version+1 where id=?",id);
        jdbc.update("update customer_order_notifications set status='EXHAUSTED',close_reason='DELIVERY_UNCERTAIN',delivery_uncertain=true,"
                + "claim_token=null,claimed_by=null,lease_until=null,version=version+1 where id=?",id);
    }
    private UUID resend(long original,Admin admin,String reason,boolean ack) {
        UUID nonce=UUID.randomUUID();
        jdbc.update("insert into customer_order_notification_resends(notification_id,request_no,request_nonce,actor_admin_id,actor_username,"
                + "reason,risk_acknowledged,requested_at) values (?,1,?,?,?,?,?,now())",original,nonce,admin.id(),admin.username(),reason,ack);
        return nonce;
    }
    private static void rejects(String state,Call call) {
        assertThatThrownBy(call::run).satisfies(e -> {
            Throwable t=e;while(t!=null&&!(t instanceof SQLException))t=t.getCause();
            assertThat(t).as("SQL failure").isNotNull();
            assertThat(((SQLException)t).getSQLState()).isEqualTo(state);
        });
    }
    @FunctionalInterface interface Call { void run() throws Throwable; }
}
