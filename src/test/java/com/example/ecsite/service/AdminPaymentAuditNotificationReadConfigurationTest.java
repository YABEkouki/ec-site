package com.example.ecsite.service;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.example.ecsite.form.AdminPaymentAuditNotificationSearchForm;
import com.example.ecsite.service.payment.*;
import com.example.ecsite.support.PaymentAuditNotificationHistoryFixtures;

@SpringBootTest(properties={"app.payment.discrepancy-audit.notification.enabled=false","app.payment.discrepancy-audit.enabled=false",
    "app.payment.reconciliation.enabled=false","spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@Testcontainers
class AdminPaymentAuditNotificationReadConfigurationTest {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword);r.add("spring.flyway.url",POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",POSTGRES::getUsername);r.add("spring.flyway.password",POSTGRES::getPassword);
    }
    @Autowired AdminPaymentAuditNotificationService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;
    @Test void bootRegistersHistoryReaderWhenNotificationsAreDisabledWithoutStartingSmtpOrScheduler() {
        var f=new PaymentAuditNotificationHistoryFixtures(jdbc);f.clear();
        long id=f.notification("EXHAUSTED",PaymentAuditNotificationHistoryFixtures.NOW);
        assertThat(service.findById(id).attemptCount()).isEqualTo(3);
        assertThat(service.search(new AdminPaymentAuditNotificationSearchForm(),0,20).getTotalElements()).isEqualTo(1);
        assertThat(context.getBeansOfType(PaymentAuditNotificationDeliveryService.class)).isEmpty();
        assertThat(context.getBeansOfType(PaymentDiscrepancyAuditNotificationMailClient.class)).isEmpty();
        assertThat(context.getBeansOfType(PaymentAuditNotificationSmtpExecutor.class)).isEmpty();
        assertThat(context.getBeansOfType(PaymentAuditNotificationScheduler.class)).isEmpty();
        assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).isEmpty();
    }
}
