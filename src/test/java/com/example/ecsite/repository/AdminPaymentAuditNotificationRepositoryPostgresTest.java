package com.example.ecsite.repository;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.example.ecsite.entity.*;
import com.example.ecsite.support.PaymentAuditNotificationHistoryFixtures;

@DataJpaTest(properties={"spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false",
    "app.payment.discrepancy-audit.notification.enabled=false","app.payment.discrepancy-audit.enabled=false","app.payment.reconciliation.enabled=false"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Testcontainers
class AdminPaymentAuditNotificationRepositoryPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl); r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword); r.add("spring.flyway.url",POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",POSTGRES::getUsername); r.add("spring.flyway.password",POSTGRES::getPassword);
    }
    @Autowired PaymentAuditNotificationRepository notifications;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentAuditNotificationItemRepository items;
    @Autowired PaymentAuditNotificationAttemptRepository attempts;
    PaymentAuditNotificationHistoryFixtures f;
    @BeforeEach void reset() { f=new PaymentAuditNotificationHistoryFixtures(jdbc); f.clear(); }

    @Test void existsSearchIncludesRemovedItemsWithoutDuplicatingNotificationOrCount() throws Exception {
        long id=f.notification("SENT",PaymentAuditNotificationHistoryFixtures.NOW,3);
        f.item(id,"LATEST_FAILURE",true); f.item(id,"LONG_RUNNING",false);
        f.attempt(id,1,"UNKNOWN"); f.attempt(id,2,"FAILURE"); f.attempt(id,3,"SUCCESS");
        f.notification("PENDING",PaymentAuditNotificationHistoryFixtures.NOW);
        var page=notifications.searchForAdmin(null,PaymentAuditNotificationStatus.SENT,
            PaymentAuditNotificationWarningType.LATEST_FAILURE,null,null,PageRequest.of(0,20));
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).extracting(v -> v.getId()).containsExactly(id);

    }

    @Test void allFiltersAreAndedAndDatesAreInclusiveFromExclusiveTo() {
        var now=PaymentAuditNotificationHistoryFixtures.NOW;
        long id=f.notification("PENDING",now);
        f.item(id,"LATEST_FAILURE",false);
        f.notification("SENT",now);
        f.notification("PENDING",now.minusSeconds(1));
        assertThat(notifications.searchForAdmin(id,PaymentAuditNotificationStatus.PENDING,
            PaymentAuditNotificationWarningType.LATEST_FAILURE,now,now.plusSeconds(1),PageRequest.of(0,20)).getTotalElements()).isEqualTo(1);
        assertThat(notifications.searchForAdmin(id,PaymentAuditNotificationStatus.SENT,null,null,null,PageRequest.of(0,20))).isEmpty();
        assertThat(notifications.searchForAdmin(id,null,PaymentAuditNotificationWarningType.NO_HISTORY,null,null,PageRequest.of(0,20))).isEmpty();
        assertThat(notifications.searchForAdmin(id,null,null,null,now,PageRequest.of(0,20))).isEmpty();
        assertThat(notifications.searchForAdmin(null,null,null,now,null,PageRequest.of(0,20)).getTotalElements()).isEqualTo(2);
    }
    @Test void scalarDetailAndBatchItemsPreserveStoredEpisodeWithoutUsingCurrentState() {
        long first=f.notification("PENDING",PaymentAuditNotificationHistoryFixtures.NOW);
        long second=f.notification("PENDING",PaymentAuditNotificationHistoryFixtures.NOW);
        f.item(first,"LATEST_FAILURE",true); f.item(first,"LONG_RUNNING",false); f.item(second,"NO_HISTORY",false);
        jdbc.update("update payment_audit_notification_states set episode_no=999,active=true,first_observed_at=clock_timestamp(),last_observed_at=clock_timestamp() where warning_type='LATEST_FAILURE'");
        var header=notifications.findSummaryForAdmin(first).orElseThrow();
        assertThat(header.getRecipientCount()).isEqualTo(2);
        assertThat(header.getCreatedAt()).isEqualTo(PaymentAuditNotificationHistoryFixtures.NOW);
        var batch=items.findAllForAdminByNotificationIds(List.of(first,second));
        assertThat(batch).hasSize(3);
        assertThat(batch.getFirst().getEpisodeNo()).isEqualTo(first);
        assertThat(batch.getFirst().getItemStatus()).isEqualTo(PaymentAuditNotificationItemStatus.REMOVED_RESOLVED);
        assertThat(batch.getFirst().getWarningCount()).isEqualTo(2);
        assertThat(notifications.findSummaryForAdmin(Long.MAX_VALUE)).isEmpty();
    }
    @Test void attemptProjectionKeepsUnknownAndOrdersByAttemptNumber() {
        long id=f.notification("SENT",PaymentAuditNotificationHistoryFixtures.NOW,3);
        f.attempt(id,3,"SUCCESS"); f.attempt(id,1,"UNKNOWN"); f.attempt(id,2,"FAILURE");
        var rows=attempts.findForAdminByNotificationId(id);
        assertThat(rows).extracting(v -> v.getAttemptNo()).containsExactly(1,2,3);
        assertThat(rows).extracting(v -> v.getResult()).containsExactly(PaymentAuditNotificationAttemptResult.UNKNOWN,
            PaymentAuditNotificationAttemptResult.FAILURE,PaymentAuditNotificationAttemptResult.SUCCESS);
        assertThat(rows.getFirst().getFailureCode()).isEqualTo(PaymentAuditNotificationFailureCode.SEND_DEADLINE_EXCEEDED);
        assertThat(attempts.findForAdminByNotificationId(Long.MAX_VALUE)).isEmpty();
    }
}
