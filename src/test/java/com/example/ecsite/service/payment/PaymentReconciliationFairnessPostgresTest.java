package com.example.ecsite.service.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import com.example.ecsite.config.*;
import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest(showSql=false,properties={"spring.jpa.show-sql=false","spring.sql.init.mode=never"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({PaymentPostGuardService.class,TimeConfig.class,PaymentService.class,PaymentConsistencyEvaluator.class})
@EnableConfigurationProperties(PayJpSafetyProperties.class)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentReconciliationFairnessPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17")
            .withDatabaseName("feature120_fairness").withUsername("feature120").withPassword("feature120-test")
            .withCommand("postgres","-c","lock_timeout=10000","-c","statement_timeout=15000");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
        r.add("spring.flyway.url",POSTGRES::getJdbcUrl);r.add("spring.flyway.user",POSTGRES::getUsername);r.add("spring.flyway.password",POSTGRES::getPassword);
    }
    @Autowired PaymentTransactionRepository transactions;
    @Autowired PaymentRepository payments;
    @Autowired OrderRepository orders;
    @Autowired UserRepository users;
    @Autowired PaymentPostGuardService diagnostics;
    @Autowired PaymentService paymentService;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;
    PaymentReconciliationItemService item;
    PaymentReconciliationService service;
    Clock clock;
    Queue<Long> selected;
    @BeforeEach void setup() {
        clock=Clock.fixed(Instant.now(),ZoneId.of("Asia/Tokyo"));selected=new ConcurrentLinkedQueue<>();
        item=mock(PaymentReconciliationItemService.class);
        service=new PaymentReconciliationService(transactions,item,
                new PaymentReconciliationProperties(true,Duration.ofMinutes(1),Duration.ofMinutes(5),100),clock,diagnostics);
        when(item.reconcile(anyLong())).thenAnswer(inv->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
            selected.add(inv.getArgument(0));return PaymentSynchronizationOutcome.WAITING;
        });
    }
    @AfterEach void clear() {
        tx(()->{transactions.deleteAllInBatch();payments.deleteAllInBatch();orders.deleteAllInBatch();return null;});
    }
    @Test void providerlessBacklogDoesNotOccupyGetQuotaAndKeepsWarning() {
        List<Long> stopped=create(105,false,Duration.ofDays(2));List<Long> eligible=create(101,true,Duration.ofMinutes(10));
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(PaymentPostGuardService.class);
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();appender.start();logger.addAppender(appender);
        try {
            service.reconcilePendingTransactions();assertThat(selected).containsExactlyElementsOf(eligible.subList(0,100));
            service.reconcilePendingTransactions();assertThat(selected).containsExactlyElementsOf(eligible);
            assertThat(selected).doesNotContainAnyElementsOf(stopped);
            assertThat(appender.list).isNotEmpty().allSatisfy(e->assertThat(e.getFormattedMessage()).contains("LONG_PENDING_WITHOUT_PROVIDER_ID","orderId=","transactionId="));
            // Diagnostic rotation also reaches the final providerless record without using GET slots.
            for(int i=0;i<9;i++)service.reconcilePendingTransactions();
            assertThat(appender.list).hasSize(105).anySatisfy(e -> assertThat(e.getFormattedMessage())
                    .contains("transactionId="+stopped.getLast()+","));
            assertThat(jdbc.queryForObject("select count(*) from payment_transactions where status='PENDING'",Integer.class)).isEqualTo(206);
            assertThat(jdbc.queryForObject("select count(*) from orders where status='CANCELLED'",Integer.class)).isZero();
        } finally {logger.detachAppender(appender);appender.stop();}
    }
    @Test void oldProviderKnownBacklogRotatesWithinLimit() {
        var ids=create(251,true,Duration.ofDays(2));
        service.reconcilePendingTransactions();assertThat(selected).hasSize(100);
        service.reconcilePendingTransactions();assertThat(selected).hasSize(200);
        service.reconcilePendingTransactions();assertThat(selected).containsExactlyElementsOf(ids);
        service.reconcilePendingTransactions();assertThat(new ArrayList<>(selected).subList(251,351)).containsExactlyElementsOf(ids.subList(0,100));
    }
    @Test void additionsDoNotMoveCurrentSweepWatermark() {
        var original=create(101,true,Duration.ofDays(2));service.reconcilePendingTransactions();
        var added=create(100,true,Duration.ofDays(1));service.reconcilePendingTransactions();
        assertThat(selected).containsExactlyElementsOf(original);
        service.reconcilePendingTransactions();service.reconcilePendingTransactions();service.reconcilePendingTransactions();
        assertThat(selected).containsAll(added);
    }
    @Test void recentlyCreatedIsExcludedUntilALaterSweep() {
        var recent=create(1,true,Duration.ofMinutes(1));create(1,true,Duration.ofMinutes(10));
        service.reconcilePendingTransactions();assertThat(selected).hasSize(1).doesNotContainAnyElementsOf(recent);
        jdbc.update("update payment_transactions set created_at=created_at - interval '10 minutes' where id=?",recent.getFirst());
        service.reconcilePendingTransactions();assertThat(selected).contains(recent.getFirst());
    }
    @Test void failedItemsStillAdvanceToLaterCandidates() {
        var ids=create(101,true,Duration.ofDays(2));
        doAnswer(inv->{selected.add(inv.getArgument(0));throw new RuntimeException("isolated item failure");}).when(item).reconcile(ids.getFirst());
        service.reconcilePendingTransactions();service.reconcilePendingTransactions();assertThat(selected).containsExactlyElementsOf(ids);
    }
    @Test void concurrentBatchesReserveDifferentRangesWithoutHoldingTransactionDuringItem() throws Exception {
        var ids=create(201,true,Duration.ofDays(2));CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        doAnswer(inv->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();selected.add(inv.getArgument(0));entered.countDown();
            assertThat(release.await(20,TimeUnit.SECONDS)).isTrue();return PaymentSynchronizationOutcome.WAITING;
        }).when(item).reconcile(ids.getFirst());
        Future<?> first=worker.submit(service::reconcilePendingTransactions);
        try {
            assertThat(entered.await(20,TimeUnit.SECONDS)).isTrue();service.reconcilePendingTransactions();
            release.countDown();first.get(20,TimeUnit.SECONDS);assertThat(selected).hasSize(200).doesNotHaveDuplicates();
            service.reconcilePendingTransactions();assertThat(selected).containsExactlyInAnyOrderElementsOf(ids);
        } finally {release.countDown();worker.shutdownNow();assertThat(worker.awaitTermination(20,TimeUnit.SECONDS)).isTrue();}
    }
    @Test void providerIdBecomingAvailableIsPickedUpOnLaterSweep() {
        var missing=create(1,false,Duration.ofDays(2));service.reconcilePendingTransactions();assertThat(selected).isEmpty();
        jdbc.update("update payments set provider_payment_id='pf_recovered' where id=(select payment_id from payment_transactions where id=?)",missing.getFirst());
        service.reconcilePendingTransactions();assertThat(selected).containsExactlyElementsOf(missing);
    }
    @Test void persistedResumeEligibilityRequiresProviderAndPreservesThreeDSecure() {
        var missing=create(1,false,Duration.ofDays(2));var known=create(1,true,Duration.ofDays(2));
        Order stopped=order(missing.getFirst());Order recovering=order(known.getFirst());
        assertThat(paymentService.canResumeAuthorization(stopped)).isFalse();assertThat(paymentService.requiresAuthorizationConfirmation(stopped)).isTrue();
        assertThat(paymentService.canResumeAuthorization(recovering)).isTrue();assertThat(paymentService.canRecoverAuthorization(recovering)).isTrue();
        jdbc.update("update payments set status='REQUIRES_ACTION' where order_id=?",recovering.getId());
        assertThat(paymentService.canResumeAuthorization(recovering)).isTrue();assertThat(paymentService.canRecoverAuthorization(recovering)).isTrue();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"\t","\n","\u2003\u3000","\u001C"})
    void nonSpaceWhitespaceProviderIdsNeverConsumeGetQuota(String blank) {
        var stopped=create(105,false,Duration.ofDays(2));
        for(int i=0;i<stopped.size();i++)
            jdbc.update("update payments set provider_payment_id=? where id=(select payment_id from payment_transactions where id=?)",blank.repeat(i+1),stopped.get(i));
        var eligible=create(1,true,Duration.ofDays(2));
        service.reconcilePendingTransactions();
        assertThat(selected).containsExactlyElementsOf(eligible).doesNotContainAnyElementsOf(stopped);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"\u00A0","\u2007","\u202F"})
    void nonBreakingSpacesFollowJavaNonblankClassification(String value) {
        assertThat(value.isBlank()).isFalse();
        var ids=create(1,false,Duration.ofDays(2));jdbc.update("update payments set provider_payment_id=?",value);
        service.reconcilePendingTransactions();assertThat(selected).containsExactlyElementsOf(ids);
    }
    private Order order(Long transactionId) {
        Long id=jdbc.queryForObject("select p.order_id from payments p join payment_transactions t on t.payment_id=p.id where t.id=?",Long.class,transactionId);
        return orders.findById(id).orElseThrow();
    }
    private List<Long> create(int count,boolean providerKnown,Duration age) {
        return tx(()->{
            LocalDateTime now=LocalDateTime.now(clock);User u=new User();u.setUsername("fair-"+UUID.randomUUID());u.setPassword("dummy");u.setEnabled(true);u.setCreatedAt(now);u.setUpdatedAt(now);users.saveAndFlush(u);
            List<Long> ids=new ArrayList<>();
            for(int i=0;i<count;i++) {
                Order o=orders.saveAndFlush(new Order(u.getId(),3000,now.minus(age),now.plusDays(1)));
                Payment p=new Payment(o,PaymentProvider.PAYJP,PaymentMethod.CARD,3000,now.minus(age));
                if(providerKnown)p.setProviderPaymentId("pf_"+UUID.randomUUID(),now);payments.saveAndFlush(p);
                ids.add(transactions.saveAndFlush(new PaymentTransaction(p,PaymentTransactionType.AUTHORIZE,3000,0,UUID.randomUUID().toString(),now.minus(age))).getId());
            }
            return ids;
        });
    }
    private <T>T tx(Supplier<T> action){return new TransactionTemplate(manager).execute(status->action.get());}
}
