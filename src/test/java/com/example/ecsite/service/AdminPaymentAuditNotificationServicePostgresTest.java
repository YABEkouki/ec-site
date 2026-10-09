package com.example.ecsite.service;

import static org.assertj.core.api.Assertions.*;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.example.ecsite.dto.*;
import com.example.ecsite.entity.*;
import com.example.ecsite.exception.PaymentAuditNotificationNotFoundException;
import com.example.ecsite.form.AdminPaymentAuditNotificationSearchForm;
import com.example.ecsite.service.payment.*;
import com.example.ecsite.support.*;

@DataJpaTest(properties={"spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false",
    "app.payment.discrepancy-audit.notification.enabled=false","app.payment.discrepancy-audit.enabled=false","app.payment.reconciliation.enabled=false",
    "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.example.ecsite.support.PaymentAuditNotificationHistorySqlInspector"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({AdminPaymentAuditNotificationService.class,AdminPaymentAuditNotificationServicePostgresTest.Config.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Testcontainers
class AdminPaymentAuditNotificationServicePostgresTest {
    @TestConfiguration static class Config {
        @Bean LocalValidatorFactoryBean validator() { return new LocalValidatorFactoryBean(); }
    }
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl); r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword); r.add("spring.flyway.url",POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",POSTGRES::getUsername); r.add("spring.flyway.password",POSTGRES::getPassword);
    }
    private static final Instant NOW=PaymentAuditNotificationHistoryFixtures.NOW;
    @Autowired AdminPaymentAuditNotificationService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ApplicationContext context;
    PaymentAuditNotificationHistoryFixtures f;
    @BeforeEach void reset() {
        PaymentAuditNotificationHistorySqlInspector.clear();
        f=new PaymentAuditNotificationHistoryFixtures(jdbc); f.clear();
    }
    @AfterEach void clearInspection() { PaymentAuditNotificationHistorySqlInspector.clear(); }
    AdminPaymentAuditNotificationSearchForm form() { return new AdminPaymentAuditNotificationSearchForm(); }

    @Test void listKeepsJstSuccessAndNextAttemptDatesDistinctAndCollectsAllWarnings() {
        long id=f.notification("SENT",NOW,3); f.item(id,"LATEST_FAILURE",false); f.item(id,"LONG_RUNNING",true);
        f.attempt(id,1,"UNKNOWN"); f.attempt(id,2,"FAILURE"); f.attempt(id,3,"SUCCESS");
        jdbc.update("update payment_audit_notifications set delivery_uncertain=true where id=?",id);
        var page=service.search(form(),0,20);
        assertThat(page.getTotalElements()).isEqualTo(1);
        var item=page.getContent().getFirst();
        assertThat(item.id()).isEqualTo(id);
        assertThat(item.createdAt()).isEqualTo(LocalDateTime.of(2026,10,9,10,0));
        assertThat(item.status()).isEqualTo(PaymentAuditNotificationStatus.SENT);
        assertThat(item.warningTypes()).containsExactly(PaymentAuditNotificationWarningType.LATEST_FAILURE,PaymentAuditNotificationWarningType.LONG_RUNNING);
        assertThat(item.warningItemCount()).isEqualTo(2);
        assertThat(item.attemptCount()).isEqualTo(3); assertThat(item.maxAttempts()).isEqualTo(3);
        assertThat(item.sentAt()).isEqualTo(LocalDateTime.of(2026,10,9,10,0,10));
        assertThat(item.nextAttemptAt()).isNull(); assertThat(item.deliveryUncertain()).isTrue();
        assertThatThrownBy(()->item.warningTypes().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void allFiltersAreAndedIncludingRemovedItems() {
        long wanted=f.notification("RETRY_WAIT",NOW); f.item(wanted,"LATEST_FAILURE",true);
        long other=f.notification("PENDING",NOW); f.item(other,"LATEST_FAILURE",false);
        var form=form();form.setNotificationId(wanted);form.setStatus(PaymentAuditNotificationStatus.RETRY_WAIT);
        form.setWarningType(PaymentAuditNotificationWarningType.LATEST_FAILURE);
        form.setFrom(LocalDate.of(2026,10,9));form.setTo(LocalDate.of(2026,10,9));
        assertThat(service.search(form,0,20).getContent()).extracting(AdminPaymentAuditNotificationListItem::id).containsExactly(wanted);
        form.setStatus(PaymentAuditNotificationStatus.PENDING); assertThat(service.search(form,0,20)).isEmpty();
    }
    @Test void notificationIdAndStatusAndWarningFiltersWorkIndependently() {
        long wanted=f.notification("EXHAUSTED",NOW);f.item(wanted,"LONG_RUNNING",false);
        f.notification("PENDING",NOW);
        var byId=form();byId.setNotificationId(wanted);
        var byStatus=form();byStatus.setStatus(PaymentAuditNotificationStatus.EXHAUSTED);
        var byWarning=form();byWarning.setWarningType(PaymentAuditNotificationWarningType.LONG_RUNNING);
        for(var search:List.of(byId,byStatus,byWarning))
            assertThat(service.search(search,0,20).getContent()).extracting(AdminPaymentAuditNotificationListItem::id).containsExactly(wanted);
    }
    @Test void tokyoDatesIncludeStartAndExcludeFollowingMidnight() {
        Instant midnight=Instant.parse("2026-10-08T15:00:00Z");
        long before=f.notification("PENDING",midnight.minusNanos(1000));
        long start=f.notification("PENDING",midnight);
        long end=f.notification("PENDING",midnight.plusSeconds(86400).minusNanos(1000));
        long next=f.notification("PENDING",midnight.plusSeconds(86400));
        var both=form(); both.setFrom(LocalDate.of(2026,10,9));both.setTo(LocalDate.of(2026,10,9));
        assertThat(service.search(both,0,20).getContent()).extracting(AdminPaymentAuditNotificationListItem::id).containsExactly(end,start);
        var from=form();from.setFrom(LocalDate.of(2026,10,9));
        assertThat(service.search(from,0,20).getContent()).extracting(AdminPaymentAuditNotificationListItem::id).containsExactly(next,end,start);
        var to=form();to.setTo(LocalDate.of(2026,10,9));
        assertThat(service.search(to,0,20).getContent()).extracting(AdminPaymentAuditNotificationListItem::id).containsExactly(end,start,before);
    }
    @ParameterizedTest @ValueSource(ints={20,50,100})
    void pagingHasNotificationTotalsFixedSortAndCorrectsExtremeOffsets(int size) {
        List<Long> ids=new ArrayList<>();
        for(int i=0;i<121;i++) {
            long id=f.notification("PENDING",NOW);ids.add(id);
            f.item(id,"LATEST_FAILURE",false); f.item(id,"LONG_RUNNING",true);
        }
        long newest=f.notification("SENT",NOW.plusSeconds(1),3);
        f.attempt(newest,1,"UNKNOWN"); f.attempt(newest,2,"FAILURE"); f.attempt(newest,3,"SUCCESS");
        var first=service.search(form(),0,size);
        assertThat(first.getTotalElements()).isEqualTo(122);assertThat(first.getSize()).isEqualTo(size);
        assertThat(first.getContent()).hasSize(size);
        assertThat(first.getContent().getFirst().id()).isEqualTo(newest);
        assertThat(first.getContent().get(1).id()).isEqualTo(ids.getLast());
        assertThat(first.getContent()).extracting(AdminPaymentAuditNotificationListItem::id).doesNotHaveDuplicates();
        for(int page:new int[]{999,Integer.MAX_VALUE}) {
            var last=service.search(form(),page,size);
            assertThat(last.getNumber()).isEqualTo(121/size);
            assertThat(last.getTotalElements()).isEqualTo(122);
            assertThat(last.getContent()).hasSize(122%size);
        }
        var filtered=form();filtered.setStatus(PaymentAuditNotificationStatus.SENT);
        assertThat(service.search(filtered,Integer.MAX_VALUE,size).getContent()).extracting(AdminPaymentAuditNotificationListItem::id).containsExactly(newest);
        filtered.setStatus(PaymentAuditNotificationStatus.EXHAUSTED);
        var empty=service.search(filtered,Integer.MAX_VALUE,size);
        assertThat(empty.getNumber()).isZero(); assertThat(empty.getTotalElements()).isZero();
    }
    @ParameterizedTest @CsvSource({"-1,20","0,20","21,20","50,50","75,20","101,100","2147483647,100"})
    void pageSizeUsesOnlySupportedChoicesAndNegativePageIsFirst(int requested,int expected) {
        f.notification("PENDING",NOW);
        var result=service.search(form(),-1,requested);
        assertThat(result.getNumber()).isZero();assertThat(result.getSize()).isEqualTo(expected);
    }
    @Test void emptySearchSkipsItemBatchAndNormalizesOutOfRangePage() {
        PaymentAuditNotificationHistorySqlInspector.start();
        var empty=service.search(form(),Integer.MAX_VALUE,20);
        assertThat(empty.getNumber()).isZero();assertThat(empty).isEmpty();
        assertThat(PaymentAuditNotificationHistorySqlInspector.statements()).noneMatch(s->s.contains("payment_audit_notification_items") && !s.contains("payment_audit_notifications"));
    }
    @Test void pageUsesOneItemBatchAndNeverReadsBodiesOrClaimFields() {
        for(int i=0;i<25;i++){long id=f.notification("PENDING",NOW);f.item(id,"LATEST_FAILURE",false);f.item(id,"LONG_RUNNING",false);}
        PaymentAuditNotificationHistorySqlInspector.start(); service.search(form(),0,20);
        var sql=PaymentAuditNotificationHistorySqlInspector.statements();
        assertThat(sql).hasSize(3);
        assertThat(sql.stream().filter(s->s.contains("from payment_audit_notification_items") && !s.contains("from payment_audit_notifications")).count()).isEqualTo(1);
        assertThat(String.join(" ",sql)).doesNotContain("for update","claim_token","claimed_by","recipient_set_hash","from_address",".subject",".body");
    }
    @ParameterizedTest @EnumSource(PaymentAuditNotificationStatus.class)
    void detailSupportsAllStatusesAndAttemptFreeHistories(PaymentAuditNotificationStatus status) {
        long id=f.notification(status.name(),NOW);
        var detail=service.findById(id);
        assertThat(detail.id()).isEqualTo(id);assertThat(detail.status()).isEqualTo(status);
        assertThat(detail.createdAt()).isEqualTo(LocalDateTime.of(2026,10,9,10,0));
        assertThat(detail.updatedAt()).isEqualTo(LocalDateTime.of(2026,10,9,10,0,10));
        assertThat(detail.evaluatedAt()).isEqualTo(LocalDateTime.of(2026,10,9,9,59,59));
        assertThat(detail.recipientCount()).isEqualTo(2);assertThat(detail.maxAttempts()).isEqualTo(3);
        assertThat(detail.warnings()).isEmpty();assertThat(detail.attempts()).isEmpty();
        assertThat(detail.sentAt()!=null).isEqualTo(status==PaymentAuditNotificationStatus.SENT);
        assertThat(detail.nextAttemptAt()!=null).isEqualTo(status==PaymentAuditNotificationStatus.PENDING || status==PaymentAuditNotificationStatus.RETRY_WAIT);
        assertThat(detail.closedAt()!=null).isEqualTo(status==PaymentAuditNotificationStatus.SENT || status==PaymentAuditNotificationStatus.EXHAUSTED || status==PaymentAuditNotificationStatus.CANCELLED);
        if(status==PaymentAuditNotificationStatus.EXHAUSTED) {assertThat(detail.closeReason()).isEqualTo(PaymentAuditNotificationCloseReason.MAX_ATTEMPTS);assertThat(detail.attemptCount()).isEqualTo(3);}
        else if(status==PaymentAuditNotificationStatus.CANCELLED) assertThat(detail.closeReason()).isEqualTo(PaymentAuditNotificationCloseReason.RESOLVED);
        else assertThat(detail.closeReason()).isNull();
    }
    @ParameterizedTest @EnumSource(PaymentAuditNotificationAttemptResult.class)
    void detailPreservesAllAttemptResultsAndNullUnfinishedTime(PaymentAuditNotificationAttemptResult result) {
        long id=f.notification("SENDING",NOW); f.attempt(id,1,result.name());
        var attempt=service.findById(id).attempts().getFirst();
        assertThat(attempt.attemptNo()).isEqualTo(1);assertThat(attempt.result()).isEqualTo(result);
        assertThat(attempt.startedAt()).isEqualTo(LocalDateTime.of(2026,10,9,10,0,1));
        assertThat(attempt.finishedAt()==null).isEqualTo(result==PaymentAuditNotificationAttemptResult.IN_PROGRESS);
        if(result==PaymentAuditNotificationAttemptResult.UNKNOWN) assertThat(attempt.failureCode()).isEqualTo(PaymentAuditNotificationFailureCode.SEND_DEADLINE_EXCEEDED);
    }
    @Test void detailReadsHistoricalItemsAndUnknownSuccessHistoryWithoutSecrets() {
        long id=f.notification("SENT",NOW,3);f.item(id,"LATEST_FAILURE",true);f.item(id,"LONG_RUNNING",false);
        f.attempt(id,3,"SUCCESS");f.attempt(id,1,"UNKNOWN");f.attempt(id,2,"FAILURE");
        jdbc.update("update payment_audit_notifications set delivery_uncertain=true where id=?",id);
        var d=service.findById(id);
        assertThat(d.deliveryUncertain()).isTrue();assertThat(d.warnings()).hasSize(2);
        var warning=d.warnings().getFirst();
        assertThat(warning.warningType()).isEqualTo(PaymentAuditNotificationWarningType.LATEST_FAILURE);
        assertThat(warning.episodeNo()).isEqualTo(id);assertThat(warning.itemStatus()).isEqualTo(PaymentAuditNotificationItemStatus.REMOVED_RESOLVED);
        assertThat(warning.firstObservedAt()).isEqualTo(LocalDateTime.of(2026,10,9,9,59));
        assertThat(warning.relatedAt()).isEqualTo(LocalDateTime.of(2026,10,9,9,58));assertThat(warning.warningCount()).isEqualTo(2);
        assertThat(warning.errorCode()).isEqualTo(PaymentDiscrepancyAuditRunErrorCode.ITEM_FAILURE);
        assertThat(warning.resolvedAt()).isEqualTo(LocalDateTime.of(2026,10,9,10,0));assertThat(warning.removedAt()).isEqualTo(LocalDateTime.of(2026,10,9,10,0,1));
        assertThat(d.warnings().get(1).itemStatus()).isEqualTo(PaymentAuditNotificationItemStatus.INCLUDED);
        assertThat(d.attempts()).extracting(AdminPaymentAuditNotificationAttemptItem::attemptNo).containsExactly(1,2,3);
        assertThat(d.attempts()).extracting(AdminPaymentAuditNotificationAttemptItem::result).containsExactly(PaymentAuditNotificationAttemptResult.UNKNOWN,PaymentAuditNotificationAttemptResult.FAILURE,PaymentAuditNotificationAttemptResult.SUCCESS);
        assertThat(d.toString()).doesNotContain("hidden-","@","claimToken","recipientSetHash","body=","subject=");
        for(var type:List.of(AdminPaymentAuditNotificationDetail.class,AdminPaymentAuditNotificationListItem.class,AdminPaymentAuditNotificationWarningItem.class,AdminPaymentAuditNotificationAttemptItem.class))
            assertThat(Arrays.stream(type.getRecordComponents()).map(c->c.getName())).noneMatch(n->Set.of("recipients","fromAddress","subject","body","recipientSetHash","claimToken","claimedBy","leaseOwner").contains(n));
        assertThatThrownBy(()->d.warnings().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->d.attempts().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void cancelledWithoutRecipientsAndAbsentIdsHaveSafeTypedResults() {
        long id=f.notification("CANCELLED",NOW);
        jdbc.update("update payment_audit_notifications set close_reason='NO_VALID_RECIPIENTS' where id=?",id);
        assertThat(service.findById(id).closeReason()).isEqualTo(PaymentAuditNotificationCloseReason.NO_VALID_RECIPIENTS);
        assertThatThrownBy(()->service.findById(Long.MAX_VALUE)).isInstanceOf(PaymentAuditNotificationNotFoundException.class);
        assertThatThrownBy(()->service.findById(0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.findById(null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void invalidInputIsRejectedBeforeDatabaseQueries() {
        PaymentAuditNotificationHistorySqlInspector.start();
        var reversed=form();reversed.setFrom(LocalDate.of(2026,10,10));reversed.setTo(LocalDate.of(2026,10,9));
        assertThatThrownBy(()->service.search(reversed,0,20)).isInstanceOf(ConstraintViolationException.class);
        var invalidId=form();invalidId.setNotificationId(-1L);
        assertThatThrownBy(()->service.search(invalidId,0,20)).isInstanceOf(ConstraintViolationException.class);
        var extreme=form();extreme.setTo(LocalDate.MAX);
        assertThatThrownBy(()->service.search(extreme,0,20)).isInstanceOf(ConstraintViolationException.class);
        assertThat(PaymentAuditNotificationHistorySqlInspector.statements()).isEmpty();
    }
    String fingerprint(String table,String order) {
        return jdbc.queryForObject("select md5(coalesce(string_agg(row_to_json(t)::text,'|' order by "+order+"),'empty')) from "+table+" t",String.class);
    }
    @Test void readingWhileNotificationsOffHasNoStateChangesOrSmtpComponents() {
        long id=f.notification("SENDING",NOW);f.item(id,"LATEST_FAILURE",false);f.attempt(id,1,"IN_PROGRESS");
        var tables=List.of("payment_audit_notifications","payment_audit_notification_items","payment_audit_notification_attempts","payment_audit_notification_states");
        var before=tables.stream().map(t->fingerprint(t,t.endsWith("states")?"warning_type":"id")).toList();
        service.search(form(),0,20);service.findById(id);
        assertThat(tables.stream().map(t->fingerprint(t,t.endsWith("states")?"warning_type":"id")).toList()).isEqualTo(before);
        assertThat(context.getBeansOfType(PaymentDiscrepancyAuditNotificationMailClient.class)).isEmpty();
        assertThat(context.getBeansOfType(PaymentAuditNotificationScheduler.class)).isEmpty();
    }
    @Test void detailSnapshotSurvivesConcurrentCommitAndDoesNotLockDeliveryRows() {
        long id=f.notification("SENDING",NOW);f.item(id,"LATEST_FAILURE",false);f.attempt(id,1,"IN_PROGRESS");
        AtomicBoolean committed=new AtomicBoolean();
        PaymentAuditNotificationHistorySqlInspector.start();
        PaymentAuditNotificationHistorySqlInspector.before(sql->{
            if(!sql.contains("from payment_audit_notification_items") || committed.getAndSet(true)) return;
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
            try(var c=dataSource.getConnection();var s=c.createStatement()) {
                c.setAutoCommit(false);s.execute("set local lock_timeout='1s'");
                for(var table:List.of("payment_audit_notifications","payment_audit_notification_items","payment_audit_notification_attempts","payment_audit_notification_states"))
                    try(var rows=s.executeQuery("select * from "+table+" for update nowait")){while(rows.next()){} }
                s.executeUpdate("update payment_audit_notifications set status='SENT',claim_token=null,claimed_by=null,lease_until=null,sent_at=created_at+interval '1 minute',closed_at=created_at+interval '1 minute',updated_at=created_at+interval '1 minute' where id="+id);
                s.executeUpdate("update payment_audit_notification_attempts set result='SUCCESS',finished_at=started_at+interval '1 second' where notification_id="+id);
                s.executeUpdate("update payment_audit_notification_items set item_status='REMOVED_RESOLVED',resolved_at=first_observed_at+interval '1 minute',removed_at=first_observed_at+interval '1 minute' where notification_id="+id);
                c.commit();
            } catch(java.sql.SQLException e){throw new AssertionError("Concurrent delivery must not be blocked by history reads",e);}
        });
        var snapshot=service.findById(id);assertThat(committed).isTrue();
        assertThat(snapshot.status()).isEqualTo(PaymentAuditNotificationStatus.SENDING);
        assertThat(snapshot.warnings().getFirst().itemStatus()).isEqualTo(PaymentAuditNotificationItemStatus.INCLUDED);
        assertThat(snapshot.attempts().getFirst().result()).isEqualTo(PaymentAuditNotificationAttemptResult.IN_PROGRESS);
        assertThat(String.join(" ",PaymentAuditNotificationHistorySqlInspector.statements())).doesNotContain("for update");
        PaymentAuditNotificationHistorySqlInspector.clear();
        var fresh=service.findById(id);
        assertThat(fresh.status()).isEqualTo(PaymentAuditNotificationStatus.SENT);
        assertThat(fresh.attempts().getFirst().result()).isEqualTo(PaymentAuditNotificationAttemptResult.SUCCESS);
    }
}
