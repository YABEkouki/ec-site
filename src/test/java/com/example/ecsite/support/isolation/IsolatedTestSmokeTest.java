package com.example.ecsite.support.isolation;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationContext;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class IsolatedTestSmokeTest {
    @Autowired DataSource datasource;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;
    @Test void commonContainerIsUsedAndFlywayIsValidated() throws Exception {
        assertThat(jdbc.queryForObject("select current_database()",String.class)).startsWith("isolated_");
        assertThat(jdbc.queryForObject("select max(version::int) from flyway_schema_history where version is not null and success",Integer.class)).isEqualTo(54);
        assertThat(context.getBeansOfType(com.example.ecsite.service.payment.PaymentAuditNotificationScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(com.example.ecsite.service.payment.PaymentDiscrepancyAuditScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(com.example.ecsite.service.payment.PaymentReconciliationScheduler.class)).isEmpty();
    }
    @Test void realExternalBeansAreBlocked(){
        assertThatThrownBy(()->context.getBean(org.springframework.mail.javamail.JavaMailSender.class).send(new org.springframework.mail.SimpleMailMessage())).isInstanceOf(IllegalStateException.class);
    }
}
