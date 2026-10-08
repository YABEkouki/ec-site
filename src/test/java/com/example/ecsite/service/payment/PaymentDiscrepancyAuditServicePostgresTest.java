package com.example.ecsite.service.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.example.ecsite.config.PaymentDiscrepancyAuditProperties;
import com.example.ecsite.entity.*;
import com.example.ecsite.payment.*;
import com.example.ecsite.repository.*;
import com.example.ecsite.service.payment.PaymentDiscrepancyConcurrencyPostgresTest.ReadSlot;
import com.example.ecsite.service.payment.PaymentDiscrepancyConcurrencyPostgresTest.SelectGate;

@DataJpaTest(properties = {
    "app.payment.reconciliation.enabled=false", "app.payment.discrepancy-audit.enabled=false",
    "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PaymentDiscrepancyAuditRunRecordingService.class, PaymentDiscrepancyAuditRetryFacade.class,
    PaymentDiscrepancyAuditItemService.class, PaymentDiscrepancyEvaluator.class,
    PaymentDiscrepancyConcurrencyPostgresTest.SynchronizationConfiguration.class,
    PaymentDiscrepancyAuditServicePostgresTest.BatchConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class PaymentDiscrepancyAuditServicePostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
        .withCommand("postgres","-c","lock_timeout=10000","-c","statement_timeout=15000")
        .withDatabaseName("feature114_batch").withUsername("feature114").withPassword("feature114-test");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
        registry.add("spring.flyway.url",POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user",POSTGRES::getUsername);
        registry.add("spring.flyway.password",POSTGRES::getPassword);
    }
    @Autowired PaymentDiscrepancyAuditService batch;
    @Autowired PaymentRepository payments;
    @Autowired UserRepository users;
    @Autowired OrderRepository orders;
    @Autowired PaymentTransactionRepository transactions;
    @Autowired PaymentDiscrepancyRepository discrepancies;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired SelectGate gate;
    @MockitoBean PaymentGateway gateway;
    static final LocalDateTime CREATED = LocalDateTime.of(2026,10,5,10,0);

    @TestConfiguration(proxyBeanMethods = false)
    static class BatchConfiguration {
        @Bean PaymentDiscrepancyAuditService batch(PaymentRepository payments, PaymentDiscrepancyAuditRetryFacade facade,
                PaymentDiscrepancyAuditRunRecordingService recording, Clock clock) {
            return new PaymentDiscrepancyAuditService(payments,facade,
                new PaymentDiscrepancyAuditProperties(true,Duration.ofMinutes(5),2),recording,clock);
        }
    }
    @BeforeEach void resetGate() { gate.reset(); }
    @AfterEach void cleanup() {
        gate.releaseAll(); gate.role.remove();
        verify(gateway,never()).prepareAuthorization(any());
        verify(gateway,never()).retrieveAuthorization(any());
        verify(gateway,never()).capture(any());
        verify(gateway,never()).cancelAuthorization(any());
    }

    @Test void runningCommitsBeforeAuditsAndBatchCountsSuccessfulCommits() {
        var first=fixture(); var second=fixture(); position(first);
        var before=protectedRows(first,second);
        List<Long> auditTxIds = new ArrayList<>();
        for (var f:List.of(first,second)) {
            when(gateway.retrievePaymentFlow(f.providerId())).thenAnswer(call -> {
                auditTxIds.add(jdbc.queryForObject("select txid_current()",Long.class));
                var committed=latestRun();
                assertThat(committed).containsEntry("status","RUNNING").containsEntry("candidate_count",null);
                assertThat(detectionCount(f)).isEqualTo(1);
                return provider(f);
            });
        }
        batch.auditPayments();
        assertThat(new HashSet<>(auditTxIds)).hasSize(2);
        assertThat(latestRun()).containsEntry("status","SUCCESS").containsEntry("candidate_count",2)
            .containsEntry("success_count",2).containsEntry("inconsistent_count",2)
            .containsEntry("failure_count",0);
        assertThat(detectionCount(first)).isEqualTo(2); assertThat(detectionCount(second)).isEqualTo(2);
        verifyGets(first,second); assertThat(protectedRows(first,second)).isEqualTo(before);
    }

    @Test void apiFailureRollsBackOneItemAndContinuesToNext() {
        var first=fixture(); var second=fixture(); position(first);
        var before=protectedRows(first,second);
        when(gateway.retrievePaymentFlow(first.providerId())).thenThrow(new PaymentGatewayException("private API failure"));
        when(gateway.retrievePaymentFlow(second.providerId())).thenReturn(provider(second));
        batch.auditPayments();
        assertThat(latestRun()).containsEntry("status","PARTIAL_FAILURE").containsEntry("candidate_count",2)
            .containsEntry("success_count",1).containsEntry("failure_count",1).containsEntry("error_code","ITEM_FAILURE");
        assertThat(detectionCount(first)).isEqualTo(1); assertThat(detectionCount(second)).isEqualTo(2);
        verifyGets(first,second); assertThat(protectedRows(first,second)).isEqualTo(before);
    }

    @Test void allApiFailuresProduceFailedRun() {
        var first=fixture(); var second=fixture(); position(first);
        var before=protectedRows(first,second);
        when(gateway.retrievePaymentFlow(anyString())).thenThrow(new PaymentGatewayException("API failed"));
        batch.auditPayments();
        assertThat(latestRun()).containsEntry("status","FAILED").containsEntry("failure_count",2)
            .containsEntry("success_count",0).containsEntry("error_code","ALL_ITEMS_FAILED");
        assertThat(detectionCount(first)).isEqualTo(1); assertThat(detectionCount(second)).isEqualTo(1);
        verifyGets(first,second); assertThat(protectedRows(first,second)).isEqualTo(before);
    }

    @Test void finishSaveFailureLeavesCommittedAuditAndRunningRecordWithoutExtraGet() {
        var f=fixture(); position(f); var before=protectedRows(f);
        when(gateway.retrievePaymentFlow(f.providerId())).thenReturn(provider(f));
        rejectingRecordingWrites("UPDATE", () -> {
            assertThatThrownBy(batch::auditPayments).isInstanceOf(RuntimeException.class);
            assertThat(latestRun()).containsEntry("status","RUNNING").containsEntry("finished_at",null);
            assertThat(detectionCount(f)).isEqualTo(2);
            verifyGets(f); assertThat(protectedRows(f)).isEqualTo(before);
        });
    }

    @Test void startSaveFailureDoesNotAudit() {
        var f=fixture(); position(f); var before=protectedRows(f);
        long oldRuns=tx(() -> jdbc.queryForObject("select count(*) from payment_discrepancy_audit_runs",Long.class));
        rejectingRecordingWrites("INSERT", () -> {
            assertThatThrownBy(batch::auditPayments).isInstanceOf(RuntimeException.class);
            assertThat(tx(() -> jdbc.queryForObject("select count(*) from payment_discrepancy_audit_runs",Long.class))).isEqualTo(oldRuns);
            verifyNoInteractions(gateway);
            assertThat(detectionCount(f)).isEqualTo(1); assertThat(protectedRows(f)).isEqualTo(before);
        });
    }

    @Test void wholeRunErrorPreservesEarlierCommitAndCountsUnprocessedItem() {
        var first=fixture(); var second=fixture(); position(first); var before=protectedRows(first,second);
        when(gateway.retrievePaymentFlow(first.providerId())).thenReturn(provider(first));
        var failure=new AssertionError("whole run interrupted");
        when(gateway.retrievePaymentFlow(second.providerId())).thenThrow(failure);
        assertThatThrownBy(batch::auditPayments).isSameAs(failure);
        assertThat(latestRun()).containsEntry("status","FAILED").containsEntry("candidate_count",2)
            .containsEntry("success_count",1).containsEntry("failure_count",0).containsEntry("error_code","EXECUTION_ABORTED");
        assertThat(detectionCount(first)).isEqualTo(2); assertThat(detectionCount(second)).isEqualTo(1);
        verifyGets(first,second); assertThat(protectedRows(first,second)).isEqualTo(before);
    }

    @Test void realOptimisticRetryRecordsOnlyOneSuccessfulItem() throws Exception {
        var f=fixture(); position(f); var before=protectedRows(f);
        when(gateway.retrievePaymentFlow(f.providerId())).thenReturn(provider(f));
        var workers=Executors.newSingleThreadExecutor();
        var slot=new ReadSlot("findByPaymentIdAndLocalStatusAndProviderStatusAndStatus"); slot.release();
        slot.onRead = ignored -> {
            int auditPid=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
            try {
                workers.submit(() -> tx(() -> {
                    assertThat(jdbc.queryForObject("select pg_backend_pid()",Integer.class)).isNotEqualTo(auditPid);
                    discrepancies.findById(f.discrepancyId()).orElseThrow().detectAgain(CREATED);
                    return null;
                })).get(10,TimeUnit.SECONDS);
            } catch (Exception failure) { throw new AssertionError("Competing Tx failed",failure); }
        };
        gate.slots.put("batch",slot); gate.role.set("batch");
        try { batch.auditPayments(); }
        finally { gate.role.remove(); gate.releaseAll(); workers.shutdownNow(); assertThat(workers.awaitTermination(20,TimeUnit.SECONDS)).isTrue(); }
        assertThat(latestRun()).containsEntry("status","SUCCESS").containsEntry("candidate_count",1)
            .containsEntry("success_count",1).containsEntry("failure_count",0).containsEntry("inconsistent_count",1);
        assertThat(detectionCount(f)).isEqualTo(3); // initial + competing commit + recovered audit commit
        verify(gateway,times(2)).retrievePaymentFlow(f.providerId());
        assertThat(protectedRows(f)).isEqualTo(before);
    }

    private void rejectingRecordingWrites(String operation,Runnable check) {
        jdbc.execute("create function feature114_batch_reject() returns trigger language plpgsql as $$ begin raise exception 'test recording rejection'; end $$");
        try {
            jdbc.execute("create trigger feature114_batch_reject before " + operation + " on payment_discrepancy_audit_runs for each row execute function feature114_batch_reject()");
            check.run();
        } finally {
            jdbc.execute("drop trigger if exists feature114_batch_reject on payment_discrepancy_audit_runs");
            jdbc.execute("drop function feature114_batch_reject()");
        }
    }
    private void position(Fixture f) { org.springframework.test.util.ReflectionTestUtils.setField(batch,"lastPaymentId",f.paymentId()-1); }
    private Fixture fixture() {
        return tx(() -> {
            String unique=UUID.randomUUID().toString(); var u=new User();
            u.setUsername("f114-"+unique); u.setPassword("test"); u.setEnabled(true); u.setCreatedAt(CREATED); u.setUpdatedAt(CREATED); users.saveAndFlush(u);
            var o=orders.saveAndFlush(new Order(u.getId(),1000,CREATED,CREATED.plusHours(4)));
            var p=new Payment(o,PaymentProvider.PAYJP,PaymentMethod.CARD,1000,CREATED);
            p.setProviderPaymentId("pfw_"+unique,CREATED); p.markAuthorized(CREATED); payments.saveAndFlush(p);
            var t=new PaymentTransaction(p,PaymentTransactionType.AUTHORIZE,1000,0,unique,CREATED);
            t.markSucceeded("txn_"+unique,CREATED); transactions.saveAndFlush(t);
            var d=discrepancies.saveAndFlush(new PaymentDiscrepancy(p,PaymentStatus.AUTHORIZED,PaymentFlowStatus.SUCCEEDED,CREATED));
            return new Fixture(o.getId(),p.getId(),p.getProviderPaymentId(),d.getId());
        });
    }
    private PaymentFlowState provider(Fixture f) { return new PaymentFlowState(f.providerId(),PaymentFlowStatus.SUCCEEDED,null,null); }
    private Map<String,Object> latestRun() { return tx(() -> jdbc.queryForMap("select * from payment_discrepancy_audit_runs order by id desc limit 1")); }
    private int detectionCount(Fixture f) { return tx(() -> jdbc.queryForObject("select detection_count from payment_discrepancies where id = ?",Integer.class,f.discrepancyId())); }
    private void verifyGets(Fixture... fixtures) { for (var f:fixtures) verify(gateway,times(1)).retrievePaymentFlow(f.providerId()); }
    private List<Map<String,Object>> protectedRows(Fixture... fixtures) {
        return tx(() -> {
            List<Map<String,Object>> result=new ArrayList<>();
            for (var f:fixtures) {
                result.add(jdbc.queryForMap("select * from payments where id = ?",f.paymentId()));
                result.add(jdbc.queryForMap("select * from orders where id = ?",f.orderId()));
                result.addAll(jdbc.queryForList("select * from payment_transactions where payment_id = ? order by id",f.paymentId()));
            }
            return result;
        });
    }
    private <T> T tx(Supplier<T> action) {
        var template=new TransactionTemplate(manager);
        template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> action.get());
    }
    private record Fixture(Long orderId,Long paymentId,String providerId,Long discrepancyId) {}
}
