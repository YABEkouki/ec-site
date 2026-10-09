package com.example.ecsite.config;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class PaymentDiscrepancyAuditNotificationPropertiesTest {
    private static final String PREFIX = "app.payment.discrepancy-audit.notification.";
    final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PaymentDiscrepancyAuditNotificationProperties.class)
    static class Config {}

    @Test void defaultsDisableNotificationAndUseReviewedTimings() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var p = context.getBean(PaymentDiscrepancyAuditNotificationProperties.class);
            assertThat(p.enabled()).isFalse();
            assertThat(p.recipients()).isEmpty();
            assertThat(p.fixedDelay()).isEqualTo(Duration.ofMinutes(1));
            assertThat(p.noHistoryGracePeriod()).isEqualTo(Duration.ofMinutes(15));
            assertThat(p.retryDelay()).isEqualTo(Duration.ofMinutes(5));
            assertThat(p.maxAttempts()).isEqualTo(3);
            assertThat(p.claimLease()).isEqualTo(Duration.ofMinutes(3));
            assertThat(p.smtp().connectionTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(p.smtp().readTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(p.smtp().writeTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(p.smtp().sendDeadline()).isEqualTo(Duration.ofMinutes(2));
        });
    }

    @Test void trimsNormalizesDomainsAndDeduplicatesRecipientsWithoutDisclosingThem() {
        runner.withPropertyValues(PREFIX + "enabled=true", PREFIX + "recipients= Admin@EXAMPLE.com ,Admin@example.com,other@example.com")
            .run(context -> {
                assertThat(context).hasNotFailed();
                var p = context.getBean(PaymentDiscrepancyAuditNotificationProperties.class);
                assertThat(p.recipients()).containsExactly("Admin@example.com", "other@example.com");
                assertThat(p.toString()).doesNotContain("Admin@", "other@");
                assertThatThrownBy(() -> p.recipients().add("third@example.com"))
                    .isInstanceOf(UnsupportedOperationException.class);
            });
    }

    @Test void enablingWithoutRecipientsFails() {
        runner.withPropertyValues(PREFIX + "enabled=true").run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "Name <admin@example.com>", "admin@example.com\r\nBcc: victim@example.com",
        "group:admin@example.com;", "admin@", "", "   "})
    void invalidNonemptyConfigurationIsRejected(String recipient) {
        runner.withPropertyValues(PREFIX + "enabled=true", PREFIX + "recipients=" + recipient)
            .run(context -> assertThat(context).hasFailed());
    }

    @Test void recipientLimitIsTwenty() {
        String twenty = IntStream.range(0, 20).mapToObj(i -> "admin" + i + "@example.com").collect(Collectors.joining(","));
        runner.withPropertyValues(PREFIX + "enabled=true", PREFIX + "recipients=" + twenty)
            .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues(PREFIX + "enabled=true", PREFIX + "recipients=" + twenty + ",extra@example.com")
            .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"fixed-delay=0s", "fixed-delay=9s", "fixed-delay=61m", "fixed-delay=bad",
        "no-history-grace-period=0s", "no-history-grace-period=25h", "retry-delay=59s", "retry-delay=25h",
        "max-attempts=0", "max-attempts=11", "claim-lease=2m", "claim-lease=31m",
        "smtp.connection-timeout=0s", "smtp.connection-timeout=31s", "smtp.read-timeout=61s",
        "smtp.write-timeout=-1s", "smtp.send-deadline=29s", "smtp.send-deadline=11m", "smtp.send-deadline=3m"})
    void invalidSettingsFailEvenWhenDisabled(String setting) {
        runner.withPropertyValues(PREFIX + setting).run(context -> assertThat(context).hasFailed());
    }

    @Test void boundarySettingsAndNestedOverridesBind() {
        runner.withPropertyValues(PREFIX + "fixed-delay=10s", PREFIX + "no-history-grace-period=24h",
            PREFIX + "retry-delay=1m", PREFIX + "max-attempts=10", PREFIX + "claim-lease=90s",
            PREFIX + "smtp.send-deadline=30s", PREFIX + "smtp.read-timeout=30s")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(PaymentDiscrepancyAuditNotificationProperties.class).smtp().readTimeout())
                    .isEqualTo(Duration.ofSeconds(30));
            });
    }
}
