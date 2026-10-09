package com.example.ecsite.controller;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.example.ecsite.support.PaymentAuditNotificationHistoryFixtures;
import com.example.ecsite.service.payment.*;

@SpringBootTest(properties={"app.payment.discrepancy-audit.notification.enabled=false","app.payment.discrepancy-audit.enabled=false",
    "app.payment.reconciliation.enabled=false","spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
@Testcontainers
class AdminPaymentAuditNotificationControllerPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword);r.add("spring.flyway.url",POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",POSTGRES::getUsername);r.add("spring.flyway.password",POSTGRES::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;
    PaymentAuditNotificationHistoryFixtures fixtures;
    @BeforeEach void setup() { fixtures=new PaymentAuditNotificationHistoryFixtures(jdbc);fixtures.clear(); }
    private String html(String query) throws Exception {
        return mvc.perform(get("/admin/payment-audit-notifications"+query).with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
    @ParameterizedTest @ValueSource(strings={"-1","999","2147483647","99999999999999999999999"})
    void realServiceCorrectsPageAndKeepsNotificationTotals(String page) throws Exception {
        for(int i=0;i<21;i++) {
            long id=fixtures.notification("SENT",PaymentAuditNotificationHistoryFixtures.NOW);
            fixtures.item(id,"LATEST_FAILURE",false);
            fixtures.item(id,"LONG_UNHANDLED",true);
            fixtures.attempt(id,1,"SUCCESS");
        }
        String body=html("?page="+page+"&warningType=LONG_UNHANDLED");
        String plain=body.replaceAll("<[^>]*>","");
        assertThat(plain).contains("21 件",page.equals("-1") ? "1 / 2" : "2 / 2");
        assertThat(body).contains("長期未対応の決済不整合","2 件");
        assertThat(body).doesNotContain("hidden-first","hidden-second","hidden-from","hidden-actual","hidden-subject","hidden-body");
        assertThat(jdbc.queryForObject("select count(*) from payment_audit_notifications where status='SENT'",Long.class)).isEqualTo(21);
    }
    @Test void removedItemSearchAndJstDatesAreReadOnlyWithoutDeliveryBeans() throws Exception {
        long id=fixtures.notification("PENDING",PaymentAuditNotificationHistoryFixtures.NOW);
        fixtures.item(id,"LONG_UNHANDLED",true);
        String before=jdbc.queryForObject("select row_to_json(n)::text from payment_audit_notifications n where id=?",String.class,id);
        assertThat(html("?notificationId="+id+"&status=PENDING&warningType=LONG_UNHANDLED&from=2026-10-09&to=2026-10-09"))
            .contains("2026-10-09 10:00:00","初回送信予定","長期未対応の決済不整合");
        assertThat(jdbc.queryForObject("select row_to_json(n)::text from payment_audit_notifications n where id=?",String.class,id)).isEqualTo(before);
        assertThat(context.getBeansOfType(PaymentAuditNotificationDeliveryService.class)).isEmpty();
        assertThat(context.getBeansOfType(PaymentDiscrepancyAuditNotificationMailClient.class)).isEmpty();
        assertThat(context.getBeansOfType(PaymentAuditNotificationScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(PaymentAuditNotificationSmtpExecutor.class)).isEmpty();
    }
}
