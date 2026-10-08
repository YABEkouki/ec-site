package com.example.ecsite.service;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import com.example.ecsite.dto.AdminPaymentDiscrepancyAuditWarning.Type;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;
import com.example.ecsite.controller.AdminPaymentDiscrepancyAuditRunController;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.example.ecsite.entity.*;
import com.example.ecsite.form.AdminPaymentDiscrepancyAuditRunSearchForm;
import com.example.ecsite.payment.PaymentFlowStatus;
import com.example.ecsite.repository.*;
import com.example.ecsite.service.payment.PaymentDiscrepancyAuditRunSummary;

@DataJpaTest(properties={"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=never", "app.payment.discrepancy-audit.enabled=true"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({AdminPaymentDiscrepancyAuditRunService.class,AdminPaymentDiscrepancyAuditRunPostgresTest.Time.class})
@Testcontainers
class AdminPaymentDiscrepancyAuditRunPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
        .withDatabaseName("feature114_monitoring").withUsername("test").withPassword("test");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl); r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword); r.add("spring.flyway.url",POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",POSTGRES::getUsername); r.add("spring.flyway.password",POSTGRES::getPassword);
    }
    @org.springframework.boot.context.properties.EnableConfigurationProperties({
        com.example.ecsite.config.PaymentDiscrepancyAuditProperties.class,
        com.example.ecsite.config.PaymentDiscrepancyAuditMonitoringProperties.class})
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-08T00:00:00Z"),ZoneId.of("Asia/Tokyo")); }
    }
    @Autowired AdminPaymentDiscrepancyAuditRunService service;
    @Autowired PaymentDiscrepancyAuditRunRepository runs;
    @Autowired PaymentDiscrepancyRepository discrepancies;
    @Autowired UserRepository users;
    @Autowired OrderRepository orders;
    @Autowired PaymentRepository payments;
    @Autowired jakarta.persistence.EntityManager em;
    @BeforeEach void cleanHistory() { runs.deleteAll(); runs.flush(); }

    @Test void emptyHistoryShowsNoLatestOrSuccessAndCorrectedFirstPage() {
        assertThat(service.monitoringSummary().latestRun()).isNull();
        assertThat(service.monitoringSummary().lastSuccessFinishedAt()).isNull();
        var page = service.search(new AdminPaymentDiscrepancyAuditRunSearchForm(),999,20);
        assertThat(page.getNumber()).isZero(); assertThat(page.getTotalElements()).isZero();
    }
    @Test void sameStartUsesIdDescendingAndLatestIsIndependentOfLastSuccess() {
        var start = Instant.parse("2026-10-07T23:59:59Z");
        var success = run(start,PaymentDiscrepancyAuditRunStatus.SUCCESS);
        var failed = run(start,PaymentDiscrepancyAuditRunStatus.FAILED);
        var running = run(start,PaymentDiscrepancyAuditRunStatus.RUNNING);
        em.flush(); em.clear();
        var summary = service.monitoringSummary();
        assertThat(summary.latestRun().id()).isEqualTo(running.getId());
        assertThat(summary.latestRun().candidateCount()).isNull();
        assertThat(summary.latestRun().successCount()).isNull();
        assertThat(summary.lastSuccessFinishedAt()).isEqualTo(LocalDateTime.of(2026,10,8,9,0));
        assertThat(service.search(new AdminPaymentDiscrepancyAuditRunSearchForm(),0,20).getContent())
            .extracting(item->item.id()).containsExactly(running.getId(),failed.getId(),success.getId());
    }
    @Test void tokyoDateBoundariesAreInclusiveFromAndExclusiveNextDay() {
        run(Instant.parse("2026-10-07T14:59:59.999999Z"),PaymentDiscrepancyAuditRunStatus.SUCCESS);
        var first = run(Instant.parse("2026-10-07T15:00:00Z"),PaymentDiscrepancyAuditRunStatus.FAILED);
        var last = run(Instant.parse("2026-10-08T14:59:59.999999Z"),PaymentDiscrepancyAuditRunStatus.FAILED);
        run(Instant.parse("2026-10-08T15:00:00Z"),PaymentDiscrepancyAuditRunStatus.FAILED);
        var form = new AdminPaymentDiscrepancyAuditRunSearchForm();
        form.setFrom(LocalDate.of(2026,10,8)); form.setTo(LocalDate.of(2026,10,8)); form.setStatus(PaymentDiscrepancyAuditRunStatus.FAILED);
        em.flush(); em.clear();
        var result = service.search(form,0,20);
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).extracting(item->item.id()).containsExactly(last.getId(),first.getId());
        assertThat(result.getContent().getLast().startedAt()).isEqualTo(LocalDateTime.of(2026,10,8,0,0));
    }
    @Test void reversedDatesYieldEmptyResultAndPageSizeIsBounded() {
        run(Instant.parse("2026-10-08T00:00:00Z"),PaymentDiscrepancyAuditRunStatus.SUCCESS);
        var form = new AdminPaymentDiscrepancyAuditRunSearchForm();
        form.setFrom(LocalDate.of(2026,10,9)); form.setTo(LocalDate.of(2026,10,8));
        assertThat(service.search(form,-1,200).getTotalElements()).isZero();
        assertThat(service.search(form,-1,200).getSize()).isEqualTo(100);
    }
    @Test void databasePaginationCorrectsLastPageAndPreservesStatusFilter() {
        for(int i=0;i<23;i++) run(Instant.parse("2026-10-08T00:00:00Z").plusSeconds(i),PaymentDiscrepancyAuditRunStatus.SUCCESS);
        run(Instant.parse("2026-10-09T00:00:00Z"),PaymentDiscrepancyAuditRunStatus.FAILED);
        var form = new AdminPaymentDiscrepancyAuditRunSearchForm(); form.setStatus(PaymentDiscrepancyAuditRunStatus.SUCCESS);
        em.flush(); em.clear();
        var page = service.search(form,999,20);
        assertThat(page.getTotalElements()).isEqualTo(23); assertThat(page.getNumber()).isEqualTo(1);
        assertThat(page.getContent()).hasSize(3).allMatch(item->item.status()==PaymentDiscrepancyAuditRunStatus.SUCCESS);
    }
    @Test void abortedRunDerivesUnprocessedAndSuccessfulDetectionIsStillSuccess() {
        var aborted = PaymentDiscrepancyAuditRun.start("node",Instant.parse("2026-10-08T00:00:00Z"));
        aborted.finish(Instant.parse("2026-10-08T00:00:01Z"),new PaymentDiscrepancyAuditRunSummary(
            PaymentDiscrepancyAuditRunStatus.FAILED,5,1,1,0,1,1,1000,PaymentDiscrepancyAuditRunErrorCode.EXECUTION_ABORTED));
        runs.saveAndFlush(aborted); em.clear();
        var item=service.monitoringSummary().latestRun();
        assertThat(item.unprocessedCount()).isEqualTo(2);
        assertThat(item.errorSummary()).isEqualTo(PaymentDiscrepancyAuditRunErrorCode.EXECUTION_ABORTED.summary());
        var successful=PaymentDiscrepancyAuditRun.start("node",Instant.parse("2026-10-09T00:00:00Z"));
        successful.finish(Instant.parse("2026-10-09T00:00:01Z"),new PaymentDiscrepancyAuditRunSummary(
            PaymentDiscrepancyAuditRunStatus.SUCCESS,2,1,1,0,1,0,1000,null));
        runs.saveAndFlush(successful); em.clear();
        item=service.monitoringSummary().latestRun();
        assertThat(item.status()).isEqualTo(PaymentDiscrepancyAuditRunStatus.SUCCESS);
        assertThat(item.inconsistentCount()).isEqualTo(1); assertThat(item.unprocessedCount()).isZero();
    }
    @Test void openAndLongUnhandledCountsUseExact24HourBoundaryAndExcludeCompletedAndResolved() {
        var cutoff=LocalDateTime.of(2026,10,7,9,0);
        discrepancy(cutoff,PaymentDiscrepancyHandlingStatus.UNCONFIRMED,false);
        discrepancy(cutoff.minusSeconds(1),PaymentDiscrepancyHandlingStatus.IN_PROGRESS,false);
        discrepancy(cutoff.plusNanos(1000),PaymentDiscrepancyHandlingStatus.UNCONFIRMED,false);
        discrepancy(cutoff.minusDays(1),PaymentDiscrepancyHandlingStatus.COMPLETED,false);
        discrepancy(cutoff.minusDays(1),PaymentDiscrepancyHandlingStatus.CONFIRMED,true);
        em.flush(); em.clear();
        assertThat(service.monitoringSummary().openCount()).isEqualTo(4);
        assertThat(service.monitoringSummary().longUnhandledCount()).isEqualTo(2);
        assertThat(service.monitoringSummary().warnings()).anySatisfy(w -> {
            assertThat(w.type()).isEqualTo(Type.LONG_UNHANDLED);assertThat(w.count()).isEqualTo(2);
        });
    }
    @Test void finalSuccessUsesFinishedTimeAndLatestFailedUsesStartedTime() {
        var olderStart=run(Instant.parse("2026-10-07T00:00:00Z"),PaymentDiscrepancyAuditRunStatus.SUCCESS);
        var laterStart=run(Instant.parse("2026-10-08T00:00:00Z"),PaymentDiscrepancyAuditRunStatus.SUCCESS);
        em.createNativeQuery("update payment_discrepancy_audit_runs set finished_at = :finished where id = :id")
            .setParameter("finished",java.time.OffsetDateTime.parse("2026-10-09T01:00:00Z"))
            .setParameter("id",olderStart.getId()).executeUpdate();
        var failed=run(Instant.parse("2026-10-10T00:00:00Z"),PaymentDiscrepancyAuditRunStatus.FAILED);
        em.flush(); em.clear();
        var summary=service.monitoringSummary();
        assertThat(summary.latestRun().id()).isEqualTo(failed.getId());
        assertThat(summary.latestRun().status()).isEqualTo(PaymentDiscrepancyAuditRunStatus.FAILED);
        assertThat(summary.lastSuccessFinishedAt()).isEqualTo(LocalDateTime.of(2026,10,9,10,0));
    }
    @Test void oneSidedDateFiltersAndPartialFailureAreSupported() {
        var first=run(Instant.parse("2026-10-07T14:59:59Z"),PaymentDiscrepancyAuditRunStatus.SUCCESS);
        var partial=PaymentDiscrepancyAuditRun.start("node",Instant.parse("2026-10-07T15:00:00Z"));
        partial.finish(Instant.parse("2026-10-07T15:00:01Z"),new PaymentDiscrepancyAuditRunSummary(
            PaymentDiscrepancyAuditRunStatus.PARTIAL_FAILURE,2,1,0,0,0,1,1000,PaymentDiscrepancyAuditRunErrorCode.ITEM_FAILURE));
        runs.saveAndFlush(partial); em.clear();
        var from=new AdminPaymentDiscrepancyAuditRunSearchForm();from.setFrom(LocalDate.of(2026,10,8));
        assertThat(service.search(from,0,20).getContent()).extracting(item->item.id()).containsExactly(partial.getId());
        var to=new AdminPaymentDiscrepancyAuditRunSearchForm();to.setTo(LocalDate.of(2026,10,7));
        assertThat(service.search(to,0,20).getContent()).extracting(item->item.id()).containsExactly(first.getId());
        from.setStatus(PaymentDiscrepancyAuditRunStatus.PARTIAL_FAILURE);
        var item=service.search(from,0,20).getContent().getFirst();
        assertThat(item.failureCount()).isEqualTo(1);assertThat(item.unprocessedCount()).isZero();
    }
    @ParameterizedTest
    @ValueSource(ints = {20, 100})
    void extremePageUsesFilteredLastPageInDatabase(int size) {
        var lastIds = extremePageFixtures(size);
        var result = service.search(extremePageFilter(), Integer.MAX_VALUE, size);
        assertThat(result.getNumber()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(size);
        assertThat(result.getTotalElements()).isEqualTo(size + 3);
        assertThat(result.getContent()).extracting(item -> item.id()).containsExactlyElementsOf(lastIds);
        assertThat(result.getContent()).allMatch(item -> item.status() == PaymentDiscrepancyAuditRunStatus.SUCCESS);
    }

    @ParameterizedTest
    @ValueSource(ints = {20, 100})
    void extremePageWithNoHistoryReturnsFirstEmptyPage(int size) {
        var result = service.search(extremePageFilter(), Integer.MAX_VALUE, size);
        assertThat(result.getNumber()).isZero();
        assertThat(result.getSize()).isEqualTo(size);
        assertThat(result.getTotalElements()).isZero();
        assertThat(result.getContent()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {20, 100})
    void extremePageHttpRendersCorrectedPageAndPreservesSearch(int size) throws Exception {
        extremePageFixtures(size);
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setTemplateMode("HTML");
        var engine = new SpringTemplateEngine(); engine.setTemplateResolver(resolver);
        var views = new ThymeleafViewResolver(); views.setTemplateEngine(engine);
        var mvc = MockMvcBuilders.standaloneSetup(new AdminPaymentDiscrepancyAuditRunController(service))
            .setViewResolvers(views).build();
        mvc.perform(get("/admin/payment-discrepancy-audits")
            .param("page", "2147483647").param("size", Integer.toString(size))
            .param("status", "SUCCESS").param("from", "2026-10-08").param("to", "2026-10-08"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("status=SUCCESS")))
            .andExpect(content().string(containsString("from=2026-10-08")))
            .andExpect(content().string(containsString("to=2026-10-08")))
            .andExpect(content().string(containsString("page=0")))
            .andExpect(content().string(containsString("size=" + size)))
            .andExpect(model().attribute("runs", org.hamcrest.Matchers.hasSize(3)));
    }

    @ParameterizedTest
    @ValueSource(ints = {20, 100})
    void extremePageWithoutSearchConditionsUsesLastPage(int size) {
        extremePageFixtures(size);
        var result = service.search(new AdminPaymentDiscrepancyAuditRunSearchForm(), Integer.MAX_VALUE, size);
        assertThat(result.getNumber()).isEqualTo(1);
        assertThat(result.getTotalElements()).isEqualTo(size + 6);
        assertThat(result.getContent()).hasSize(6);
    }

    @Test void runningAggregateIncludesPastResidueAtBoundaryAndExcludesFuture() {
        var now=Instant.parse("2026-10-08T00:00:00Z");
        run(now.minusSeconds(899),PaymentDiscrepancyAuditRunStatus.RUNNING);
        run(now.minusSeconds(900),PaymentDiscrepancyAuditRunStatus.RUNNING);
        run(now.minusSeconds(7200),PaymentDiscrepancyAuditRunStatus.RUNNING);
        run(now.minusSeconds(2),PaymentDiscrepancyAuditRunStatus.SUCCESS);
        run(now.plusSeconds(3600),PaymentDiscrepancyAuditRunStatus.RUNNING);
        em.flush();em.clear();
        var aggregate=runs.summarizeLongRunning(PaymentDiscrepancyAuditExecutionType.SCHEDULED,now.minusSeconds(900));
        assertThat(aggregate.getCount()).isEqualTo(2);
        assertThat(aggregate.getOldestStartedAt()).isEqualTo(now.minusSeconds(7200));
        var warning=service.monitoringSummary().warnings().stream().filter(w->w.type()==Type.LONG_RUNNING).findFirst().orElseThrow();
        assertThat(warning.count()).isEqualTo(2);
        assertThat(warning.relatedAt()).isEqualTo(LocalDateTime.of(2026,10,8,7,0));
    }

    @Test void latestFinishedThreeExcludeRunningAndUseFinishTimeOrder() {
        var now=Instant.parse("2026-10-08T00:00:00Z");
        run(now.minusSeconds(200),PaymentDiscrepancyAuditRunStatus.SUCCESS);
        var first=run(now.minusSeconds(100),PaymentDiscrepancyAuditRunStatus.FAILED);
        var second=run(now.minusSeconds(50),PaymentDiscrepancyAuditRunStatus.FAILED);
        var third=run(now.minusSeconds(10),PaymentDiscrepancyAuditRunStatus.FAILED);
        run(now,PaymentDiscrepancyAuditRunStatus.RUNNING);
        em.flush();em.clear();
        var recent=runs.findRecentFinishedForMonitoring(PaymentDiscrepancyAuditExecutionType.SCHEDULED,
            org.springframework.data.domain.PageRequest.of(0,3));
        assertThat(recent).extracting(r->r.getId()).containsExactly(third.getId(),second.getId(),first.getId());
        assertThat(service.monitoringSummary().warnings()).extracting(w->w.type()).containsExactly(Type.CONSECUTIVE_FAILURES);
    }

    @ParameterizedTest @CsvSource({"899,false","900,false","901,true"})
    void delayBoundaryUsesRealHistoryFinishAndConfiguredDelay(long elapsed,boolean expected) {
        var finish=Instant.parse("2026-10-08T00:00:00Z").minusSeconds(elapsed);
        run(finish.minusSeconds(1),PaymentDiscrepancyAuditRunStatus.SUCCESS);
        em.flush();em.clear();
        assertThat(service.monitoringSummary().warnings().stream().anyMatch(w->w.type()==Type.DELAYED)).isEqualTo(expected);
    }

    @Test void failureStreakFollowsFinishOrderEvenWhenStartOrderDiffers() {
        var now=Instant.parse("2026-10-08T00:00:00Z");
        var olderSuccess=run(now.minusSeconds(100),PaymentDiscrepancyAuditRunStatus.SUCCESS);
        run(now.minusSeconds(50),PaymentDiscrepancyAuditRunStatus.FAILED);
        run(now.minusSeconds(20),PaymentDiscrepancyAuditRunStatus.FAILED);
        run(now.minusSeconds(10),PaymentDiscrepancyAuditRunStatus.FAILED);
        em.createNativeQuery("update payment_discrepancy_audit_runs set finished_at = :finished where id = :id")
            .setParameter("finished",java.time.OffsetDateTime.ofInstant(now,ZoneOffset.UTC))
            .setParameter("id",olderSuccess.getId()).executeUpdate();
        em.flush();em.clear();
        var recent=runs.findRecentFinishedForMonitoring(PaymentDiscrepancyAuditExecutionType.SCHEDULED,
            org.springframework.data.domain.PageRequest.of(0,3));
        assertThat(recent).hasSize(3);
        assertThat(recent.getFirst().getId()).isEqualTo(olderSuccess.getId());
        assertThat(service.monitoringSummary().warnings()).noneMatch(w->w.type()==Type.CONSECUTIVE_FAILURES);
    }

    private AdminPaymentDiscrepancyAuditRunSearchForm extremePageFilter() {
        var form = new AdminPaymentDiscrepancyAuditRunSearchForm();
        form.setStatus(PaymentDiscrepancyAuditRunStatus.SUCCESS);
        form.setFrom(LocalDate.of(2026, 10, 8)); form.setTo(LocalDate.of(2026, 10, 8));
        return form;
    }

    private List<Long> extremePageFixtures(int size) {
        var ids = new ArrayList<Long>();
        for (int i = 0; i < size + 3; i++) {
            ids.add(run(Instant.parse("2026-10-08T00:00:00Z").plusSeconds(i),
                PaymentDiscrepancyAuditRunStatus.SUCCESS).getId());
        }
        run(Instant.parse("2026-10-08T01:00:00Z"), PaymentDiscrepancyAuditRunStatus.FAILED);
        run(Instant.parse("2026-10-07T14:59:59Z"), PaymentDiscrepancyAuditRunStatus.SUCCESS);
        run(Instant.parse("2026-10-08T15:00:00Z"), PaymentDiscrepancyAuditRunStatus.SUCCESS);
        em.flush(); em.clear();
        return List.of(ids.get(2), ids.get(1), ids.get(0));
    }

    private PaymentDiscrepancyAuditRun run(Instant start,PaymentDiscrepancyAuditRunStatus status) {
        var run=PaymentDiscrepancyAuditRun.start("node",start);
        if(status!=PaymentDiscrepancyAuditRunStatus.RUNNING) run.finish(start.plusSeconds(1),new PaymentDiscrepancyAuditRunSummary(
            status,1,status==PaymentDiscrepancyAuditRunStatus.SUCCESS?1:0,0,0,0,
            status==PaymentDiscrepancyAuditRunStatus.SUCCESS?0:1,1000,
            status==PaymentDiscrepancyAuditRunStatus.SUCCESS?null:PaymentDiscrepancyAuditRunErrorCode.ALL_ITEMS_FAILED));
        return runs.saveAndFlush(run);
    }
    private void discrepancy(LocalDateTime first,PaymentDiscrepancyHandlingStatus handling,boolean resolved) {
        var user=new User(); user.setUsername("monitor-"+UUID.randomUUID());user.setPassword("test");user.setEnabled(true);
        user.setCreatedAt(first);user.setUpdatedAt(first);users.saveAndFlush(user);
        var order=orders.saveAndFlush(new com.example.ecsite.entity.Order(user.getId(),1000,first,first.plusHours(4)));
        var payment=payments.saveAndFlush(new Payment(order,PaymentProvider.PAYJP,PaymentMethod.CARD,1000,first));
        var d=new PaymentDiscrepancy(payment,PaymentStatus.AUTHORIZED,PaymentFlowStatus.SUCCEEDED,first);
        d.changeHandlingStatus(handling,first); if(resolved)d.resolve(first.plusHours(1));discrepancies.saveAndFlush(d);
    }
}
