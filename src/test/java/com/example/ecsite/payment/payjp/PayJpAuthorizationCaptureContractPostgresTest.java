package com.example.ecsite.payment.payjp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.util.*;
import java.util.function.Supplier;
import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.payment.*;
import com.example.ecsite.repository.*;
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.OrderShippingPreparationService;
import com.example.ecsite.service.payment.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Uses the real HTTP response conversion and real persistence; HTTP is intercepted, never sent. */
@SpringBootTest(properties={"logging.level.root=WARN", "spring.jpa.show-sql=false"})
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PayJpAuthorizationCaptureContractPostgresTest {
    // Service calls commit intentionally. Keep their rows away from repository tests' global counts.
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17")
            .withDatabaseName("authorization_contract").withUsername("contract_user").withPassword(UUID.randomUUID().toString());
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
        registry.add("spring.flyway.url",POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user",POSTGRES::getUsername);
        registry.add("spring.flyway.password",POSTGRES::getPassword);
    }
    @Autowired PaymentAuthorizationService authorization;
    @Autowired PayJpWebhookAuthorizationSyncService webhook;
    @Autowired PaymentReconciliationItemService reconciliation;
    @Autowired OrderShippingPreparationService preparation;
    @Autowired CaptureShipmentPolicy policy;
    @Autowired OrderRepository orders;
    @Autowired PaymentRepository payments;
    @Autowired PaymentTransactionRepository transactions;
    @Autowired UserRepository users;
    @Autowired AdminAccountRepository admins;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PaymentGateway gateway;

    @BeforeEach void setup() { SecurityContextHolder.clearContext(); }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    enum Entry { SYNCHRONOUS, WEBHOOK, RECONCILIATION }

    @ParameterizedTest @EnumSource(Entry.class)
    void verifiedHttpFlowIdSurvivesEveryAuthorizationEntryAndAllowsCapture(Entry entry) {
        Fixture f=fixture();
        var builder=RestClient.builder().baseUrl("http://localhost:1");
        var server=MockRestServiceServer.bindTo(builder).build();
        var adapter=new PayJpPaymentGateway(builder.build(),new PayJpAuthorizationMapper());
        int gets=entry==Entry.SYNCHRONOUS?2:1;
        server.expect(ExpectedCount.times(gets),requestTo("http://localhost:1/v2/payment_flows/"+f.provider()))
                .andRespond(withSuccess("{\"id\":\""+f.provider()+"\",\"status\":\"requires_capture\"}",MediaType.APPLICATION_JSON));
        when(gateway.retrieveAuthorization(f.provider())).thenAnswer(invocation -> {
            var recovery=adapter.retrieveAuthorization(invocation.getArgument(0));
            assertThat(recovery.result().providerTransactionId()).isEqualTo(f.provider());
            return recovery;
        });
        apply(entry,f,false);
        assertThat(paymentRow(f)).containsEntry("status","AUTHORIZED").containsEntry("provider_payment_id",f.provider());
        assertThat(transactionRow(f)).containsEntry("status","SUCCEEDED").containsEntry("provider_transaction_id",f.provider());
        var first=state(f);
        apply(entry,f,true);
        assertThat(state(f)).isEqualTo(first); // Same operation, initial completion timestamp and Flow ID.
        assertThat(jdbc.queryForObject("select count(*) from payment_transactions where payment_id=?",Integer.class,f.payment())).isEqualTo(1);
        var actor=new AdminUserDetails(f.admin(),f.username(),"test",true,List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,"",actor.getAuthorities()));
        preparation.start(f.order());
        var decision=tx(() -> {
            Order order=orders.findById(f.order()).orElseThrow();
            var current=payments.findByOrderIdOrderByCreatedAtAscIdAsc(f.order());
            var history=transactions.findByPaymentIdOrderByCreatedAtAscIdAsc(f.payment());
            return policy.classify(order,current,history,null);
        });
        assertThat(decision.disposition()).isEqualTo(CaptureShipmentPolicy.Disposition.NEW);
        assertThat(decision.paymentId()).isEqualTo(f.payment());
        verify(gateway,times(gets)).retrieveAuthorization(f.provider());verify(gateway,never()).capture(any());
        server.verify();
    }

    @Test void foreignHttpFlowIdCannotReachAuthorizationPersistence() {
        Fixture f=fixture();var before=state(f);
        var builder=RestClient.builder().baseUrl("http://localhost:1");var server=MockRestServiceServer.bindTo(builder).build();
        var adapter=new PayJpPaymentGateway(builder.build(),new PayJpAuthorizationMapper());
        server.expect(requestTo("http://localhost:1/v2/payment_flows/"+f.provider()))
                .andRespond(withSuccess("{\"id\":\"pfw_other\",\"status\":\"requires_capture\"}",MediaType.APPLICATION_JSON));
        when(gateway.retrieveAuthorization(f.provider())).thenAnswer(invocation->adapter.retrieveAuthorization(invocation.getArgument(0)));
        assertThatThrownBy(()->authorization.refreshAuthorization(f.order(),f.payment())).isInstanceOf(PaymentGatewayException.class);
        assertThat(state(f)).isEqualTo(before);
        assertThat(transactionRow(f)).containsEntry("status","PENDING").containsEntry("provider_transaction_id",null);
        verify(gateway,never()).capture(any());server.verify();
    }

    private void apply(Entry entry,Fixture f,boolean repeated) {
        switch(entry) {
            case SYNCHRONOUS -> assertThat(authorization.refreshAuthorization(f.order(),f.payment()).status()).isEqualTo(AuthorizationResultStatus.AUTHORIZED);
            case WEBHOOK -> assertThat(webhook.synchronize("payment_flow.amount_capturable_updated",f.provider()))
                    .isEqualTo(repeated?PaymentSynchronizationOutcome.SKIPPED:PaymentSynchronizationOutcome.APPLIED);
            case RECONCILIATION -> assertThat(reconciliation.reconcile(f.transaction()))
                    .isEqualTo(repeated?PaymentSynchronizationOutcome.SKIPPED:PaymentSynchronizationOutcome.APPLIED);
        }
    }
    private Fixture fixture() {
        var seed=tx(() -> {
            String unique=UUID.randomUUID().toString();var now=orders.findCurrentTimeInTokyo();
            User user=new User();user.setUsername("http-contract-"+unique);user.setPassword("test");user.setEnabled(true);
            user.setCreatedAt(now);user.setUpdatedAt(now);users.saveAndFlush(user);
            AdminAccount admin=new AdminAccount();admin.setUsername("contract-admin-"+unique);admin.setPassword("test");admin.setEnabled(true);
            admin.setCreatedAt(now);admin.setUpdatedAt(now);admins.saveAndFlush(admin);
            var ordered=now.minusDays(1).toLocalDate().atTime(10,0);
            Order order=orders.saveAndFlush(new Order(user.getId(),200,ordered,ordered.withHour(14)));
            return new Seed(order.getId(),admin.getId(),admin.getUsername(),"pfw_contract_"+unique);
        });
        when(gateway.prepareAuthorization(any())).thenReturn(new AuthorizationPreparation(seed.provider(),"test-secret"));
        var start=authorization.prepareAuthorization(order(seed.order()));
        Long transaction=jdbc.queryForObject("select id from payment_transactions where payment_id=?",Long.class,start.paymentId());
        return new Fixture(seed.order(),start.paymentId(),transaction,seed.admin(),seed.username(),seed.provider());
    }
    private Order order(long id) { return tx(()->orders.findById(id).orElseThrow()); }
    private <T>T tx(Supplier<T> action) { return new TransactionTemplate(manager).execute(s->action.get()); }
    private Map<String,Object> paymentRow(Fixture f) { return jdbc.queryForMap("select * from payments where id=?",f.payment()); }
    private Map<String,Object> transactionRow(Fixture f) { return jdbc.queryForMap("select * from payment_transactions where id=?",f.transaction()); }
    private Object state(Fixture f) { return List.of(jdbc.queryForMap("select * from orders where id=?",f.order()),paymentRow(f),transactionRow(f)); }
    record Seed(Long order,Long admin,String username,String provider) {}
    record Fixture(Long order,Long payment,Long transaction,Long admin,String username,String provider) {}
}
