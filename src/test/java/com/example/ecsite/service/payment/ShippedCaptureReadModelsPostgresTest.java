package com.example.ecsite.service.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;

import com.example.ecsite.controller.OrderPaymentViewFactory;
import com.example.ecsite.controller.OrderPaymentViewReadService;
import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.form.*;
import com.example.ecsite.payment.*;
import com.example.ecsite.repository.*;
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Characterizes existing monitoring/revenue behavior; it does not introduce settlement-based accounting. */
@SpringBootTest(properties={"logging.level.root=WARN", "spring.jpa.show-sql=false"})
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@com.example.ecsite.support.CaptureConnectionTest
class ShippedCaptureReadModelsPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17")
            .withDatabaseName("shipped_capture_read_models").withUsername("read_model_user").withPassword(UUID.randomUUID().toString());
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
        registry.add("spring.flyway.url",POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user",POSTGRES::getUsername);
        registry.add("spring.flyway.password",POSTGRES::getPassword);
    }
    @Autowired OrderService orderService;
    @Autowired OrderShippingPreparationService preparation;
    @Autowired PaymentCaptureService capture;
    @Autowired OrderPaymentViewReadService views;
    @Autowired PaymentDiscrepancyAuditRetryFacade audit;
    @Autowired AdminPaymentDiscrepancyService discrepancyHandling;
    @Autowired SalesDashboardService sales;
    @Autowired OrderRepository orders;
    @Autowired UserRepository users;
    @Autowired AdminAccountRepository admins;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired TaxCategoryService taxes;
    @Autowired PaymentRepository payments;
    @Autowired PaymentTransactionRepository transactions;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PaymentGateway gateway;

    @BeforeEach void setup() { SecurityContextHolder.clearContext(); }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest
    @EnumSource(value=OrderHandlingStatus.class,names={"NONE","NEEDS_ACTION","IN_PROGRESS"})
    void listingUsesHandlingStatusRatherThanPendingCapture(OrderHandlingStatus handling) {
        Fixture f=fixture();ship(f,CaptureResultStatus.PENDING);
        if(handling!=OrderHandlingStatus.NONE)
            assertThat(orderService.changeHandlingStatus(f.order(),handling,null,f.admin(),f.username())).isTrue();
        var before=state(f);clearInvocations(gateway);
        AdminOrderSearchForm all=new AdminOrderSearchForm();all.setOrderId(f.order());
        assertThat(orderService.searchOrders(all,f.admin(),0,20).getContent()).extracting(Order::getId).containsExactly(f.order());
        AdminActionRequiredOrderSearchForm action=new AdminActionRequiredOrderSearchForm();action.setOrderId(f.order());
        var required=orderService.searchActionRequiredOrderDetails(action,f.admin(),0,20).getContent();
        if(handling==OrderHandlingStatus.NONE)assertThat(required).isEmpty();
        else assertThat(required).hasSize(1);
        assertReadOnly(f,before,OrderPaymentViewFactory.State.CAPTURE_PENDING);
    }

    @Test void pendingShipmentAuditSkipsProviderAndKeepsDiscrepancyAndHandlingHistory() {
        Fixture f=fixture();ship(f,CaptureResultStatus.PENDING);
        // Nonempty records make preservation stronger than asserting that empty histories remain empty.
        Long discrepancyId=tx(() -> {
            var payment=payments.findById(f.payment()).orElseThrow();
            var discrepancy=new PaymentDiscrepancy(payment,PaymentStatus.AUTHORIZED,PaymentFlowStatus.SUCCEEDED,orders.findCurrentTimeInTokyo());
            discrepancyRepository.saveAndFlush(discrepancy);
            return discrepancy.getId();
        });
        discrepancyHandling.changeHandlingStatus(discrepancyId,0L,PaymentDiscrepancyHandlingStatus.CONFIRMED,f.admin(),f.username());
        orderService.changeHandlingStatus(f.order(),OrderHandlingStatus.NEEDS_ACTION,null,f.admin(),f.username());
        var before=state(f);clearInvocations(gateway);
        var result=audit.auditWithResult(f.payment());
        assertThat(result.status()).isEqualTo(PaymentDiscrepancyAuditResult.Status.SKIPPED);
        assertThat(result.skipReason()).isEqualTo(PaymentDiscrepancyAuditResult.SkipReason.PENDING_TRANSACTION);
        assertReadOnly(f,before,OrderPaymentViewFactory.State.CAPTURE_PENDING);
    }

    @Autowired PaymentDiscrepancyRepository discrepancyRepository;

    @ParameterizedTest
    @EnumSource(value=CaptureResultStatus.class,names={"PENDING","FAILED","CAPTURED"})
    void salesCountShippedOrdersRegardlessOfCaptureSettlement(CaptureResultStatus result) {
        Fixture f=fixture();
        SalesDashboardForm period=new SalesDashboardForm();period.setFrom(f.orderedAt().toLocalDate());period.setTo(f.orderedAt().toLocalDate());
        var baseline=sales.getSummary(period);
        var committed=new java.util.concurrent.atomic.AtomicReference<com.example.ecsite.dto.SalesSummary>();
        when(gateway.capture(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(row(f)).containsEntry("status","SHIPPED").containsEntry("shipment_capture_payment_id",f.payment());
            assertThat(date(f,"paid_at")).isNull();
            committed.set(sales.getSummary(period));
            assertThat(committed.get().salesOrderCount()).isEqualTo(baseline.salesOrderCount()+1);
            assertThat(committed.get().salesAmount()).isEqualTo(baseline.salesAmount()+200);
            return new CaptureResult(result,f.provider(),"test_failure","synthetic result");
        });
        preparation.start(f.order());
        assertThat(capture.captureForShipment(f.order(),f.admin(),f.username(),"read-model shipment").status()).isEqualTo(result);
        assertThat(row(f)).containsEntry("status","SHIPPED");
        assertThat(date(f,"paid_at")!=null).isEqualTo(result==CaptureResultStatus.CAPTURED);
        assertThat(paymentRow(f)).containsEntry("status",result==CaptureResultStatus.CAPTURED?"CAPTURED":"AUTHORIZED");
        assertThat(captureRow(f)).containsEntry("status",result==CaptureResultStatus.CAPTURED?"SUCCEEDED":result.name());
        verify(gateway,times(1)).capture(any());
        var before=state(f);clearInvocations(gateway);
        var after=sales.getSummary(period);
        assertThat(after).isEqualTo(committed.get()); // Result application must not add the sale a second time.
        assertThat(after.salesOrderCount()).isEqualTo(baseline.salesOrderCount()+1);
        assertThat(after.salesAmount()).isEqualTo(baseline.salesAmount()+200);
        var expected=switch(result) {
            case CAPTURED -> OrderPaymentViewFactory.State.CAPTURED;
            case FAILED -> OrderPaymentViewFactory.State.CAPTURE_FAILED;
            default -> OrderPaymentViewFactory.State.CAPTURE_PENDING;
        };
        assertReadOnly(f,before,expected);
    }

    private Fixture fixture() {
        Fixture f=tx(() -> {
            String unique=UUID.randomUUID().toString();var now=orders.findCurrentTimeInTokyo();
            User user=new User();user.setUsername("read-model-"+unique);user.setPassword("test");user.setEnabled(true);
            user.setCreatedAt(now);user.setUpdatedAt(now);users.saveAndFlush(user);
            AdminAccount admin=new AdminAccount();admin.setUsername("read-admin-"+unique);admin.setPassword("test");admin.setEnabled(true);
            admin.setCreatedAt(now);admin.setUpdatedAt(now);admins.saveAndFlush(admin);
            var category=categories.saveAndFlush(new Category(unique));var tax=taxes.findStandardTaxCategory();
            Product product=new Product();product.setName("read product");product.setPrice(100);product.setStock(10);
            product.setCategory(category);product.setTaxCategory(tax);products.saveAndFlush(product);
            var orderedAt=now.minusDays(1).toLocalDate().atTime(10,0);
            Order order=new Order(user.getId(),200,orderedAt,orderedAt.withHour(14));
            order.addItem(new OrderItem(product.getId(),product.getName(),category.getId(),category.getName(),tax.getId(),tax.getCode(),tax.getName(),tax.getTaxRate(),100,2));
            orders.saveAndFlush(order);
            Payment payment=new Payment(order,PaymentProvider.PAYJP,PaymentMethod.CARD,200,now);
            String provider="pfw_read_"+unique;payment.setProviderPaymentId(provider,now);payment.markAuthorized(now);payments.saveAndFlush(payment);
            var auth=new PaymentTransaction(payment,PaymentTransactionType.AUTHORIZE,200,0,"read_auth_"+unique,now);
            auth.markSucceeded(provider,now);transactions.saveAndFlush(auth);
            return new Fixture(order.getId(),payment.getId(),product.getId(),admin.getId(),admin.getUsername(),provider,orderedAt);
        });
        var actor=new AdminUserDetails(f.admin(),f.username(),"test",true,List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,"",actor.getAuthorities()));
        return f;
    }
    private void ship(Fixture f,CaptureResultStatus result) {
        when(gateway.capture(any())).thenReturn(new CaptureResult(result,f.provider(),null,null));
        assertThat(preparation.start(f.order()).status()).isEqualTo(ShippingPreparationStartResult.Status.STARTED);
        assertThat(capture.captureForShipment(f.order(),f.admin(),f.username(),"read-model shipment").status()).isEqualTo(result);
        assertThat(row(f)).containsEntry("status","SHIPPED").containsEntry("shipment_capture_payment_id",f.payment());
        assertThat(date(f,"paid_at")).isNull();
        assertThat(captureRow(f)).containsEntry("status","PENDING");
        assertThat(stock(f)).isEqualTo(10);assertThat(shipmentHistory(f)).hasSize(1);
        verify(gateway,times(1)).capture(any());
    }
    private void assertReadOnly(Fixture f,Object before,OrderPaymentViewFactory.State expected) {
        var view=views.read(orderService.findOrderWithItems(f.order()));
        assertThat(view.state()).isEqualTo(expected);
        assertThat(view.canRequery()).isEqualTo(expected==OrderPaymentViewFactory.State.CAPTURE_PENDING);
        assertThat(view.canCapture()).isFalse();assertThat(view.canManualShip()).isFalse();
        assertThat(state(f)).isEqualTo(before);
        assertThat(stock(f)).isEqualTo(10);assertThat(shipmentHistory(f)).hasSize(1);
        verifyNoInteractions(gateway);
    }
    private <T>T tx(Supplier<T> action) { return new TransactionTemplate(manager).execute(s->action.get()); }
    private Map<String,Object> row(Fixture f) { return jdbc.queryForMap("select * from orders where id=?",f.order()); }
    private Map<String,Object> paymentRow(Fixture f) { return jdbc.queryForMap("select * from payments where id=?",f.payment()); }
    private Map<String,Object> captureRow(Fixture f) { return jdbc.queryForMap("select * from payment_transactions where payment_id=? and transaction_type='CAPTURE'",f.payment()); }
    private LocalDateTime date(Fixture f,String column) { return jdbc.queryForObject("select "+column+" from orders where id=?",LocalDateTime.class,f.order()); }
    private int stock(Fixture f) { return jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product()); }
    private List<Map<String,Object>> shipmentHistory(Fixture f) { return jdbc.queryForList("select * from order_status_histories where order_id=? and to_status='SHIPPED' order by id",f.order()); }
    private Object state(Fixture f) {
        return List.of(row(f),paymentRow(f),
                jdbc.queryForList("select * from payment_transactions where payment_id=? order by id",f.payment()),
                jdbc.queryForList("select * from order_items where order_id=? order by id",f.order()),
                jdbc.queryForList("select * from order_charges where order_id=? order by id",f.order()),
                jdbc.queryForMap("select * from products where id=?",f.product()),
                jdbc.queryForList("select * from stock_movements where order_id=? order by id",f.order()),
                jdbc.queryForList("select * from order_status_histories where order_id=? order by id",f.order()),
                jdbc.queryForList("select * from order_handling_status_histories where order_id=? order by id",f.order()),
                jdbc.queryForList("select * from payment_discrepancies where payment_id=? order by id",f.payment()),
                jdbc.queryForList("select h.* from payment_discrepancy_handling_status_histories h join payment_discrepancies d on d.id=h.payment_discrepancy_id where d.payment_id=? order by h.id",f.payment()));
    }
    record Fixture(Long order,Long payment,Long product,Long admin,String username,String provider,LocalDateTime orderedAt) {}
}
