package com.example.ecsite.config;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class PaymentDiscrepancyAuditMonitoringPropertiesTest {
    final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);
    @Configuration(proxyBeanMethods=false)
    @EnableConfigurationProperties(PaymentDiscrepancyAuditMonitoringProperties.class)
    static class Config {}
    @Test void omittedSettingsUseDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var p=context.getBean(PaymentDiscrepancyAuditMonitoringProperties.class);
            assertThat(p.runningThreshold()).isEqualTo(Duration.ofMinutes(15));
            assertThat(p.delayGrace()).isEqualTo(Duration.ofMinutes(10));
            assertThat(p.consecutiveFailures()).isEqualTo(3);
            assertThat(p.longUnhandledAge()).isEqualTo(Duration.ofHours(24));
        });
    }
    @Test void configuredSettingsBindAndZeroGraceIsAllowed() {
        runner.withPropertyValues("app.payment.discrepancy-audit.monitoring.running-threshold=20m",
            "app.payment.discrepancy-audit.monitoring.delay-grace=0s",
            "app.payment.discrepancy-audit.monitoring.consecutive-failures=4",
            "app.payment.discrepancy-audit.monitoring.long-unhandled-age=48h").run(context -> {
                assertThat(context).hasNotFailed();var p=context.getBean(PaymentDiscrepancyAuditMonitoringProperties.class);
                assertThat(p.runningThreshold()).isEqualTo(Duration.ofMinutes(20));assertThat(p.delayGrace()).isZero();
                assertThat(p.consecutiveFailures()).isEqualTo(4);assertThat(p.longUnhandledAge()).isEqualTo(Duration.ofHours(48));
            });
    }
    @ParameterizedTest @ValueSource(strings={"running-threshold=0s","running-threshold=-1s","delay-grace=-1s",
        "consecutive-failures=0","consecutive-failures=-1","long-unhandled-age=0s","long-unhandled-age=-1s","running-threshold=bad"})
    void invalidSettingsFailBinding(String setting) {
        runner.withPropertyValues("app.payment.discrepancy-audit.monitoring."+setting).run(context -> assertThat(context).hasFailed());
    }
    @ParameterizedTest
    @CsvSource({"running-threshold,7d", "delay-grace,7d", "delay-grace,0s",
        "long-unhandled-age,365d", "consecutive-failures,1", "consecutive-failures,100"})
    void boundarySettingsBindSuccessfully(String name, String value) {
        runner.withPropertyValues("app.payment.discrepancy-audit.monitoring." + name + "=" + value)
            .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @CsvSource({
        "running-threshold,PT168H0.000000001S,'0より大きく7日以下'",
        "delay-grace,PT168H0.000000001S,'0以上7日以下'",
        "long-unhandled-age,PT8760H0.000000001S,'0より大きく365日以下'",
        "consecutive-failures,101,'1以上100以下'",
        "consecutive-failures,2147483647,'1以上100以下'",
        "running-threshold,365000000000d,'0より大きく7日以下'",
        "delay-grace,365000000000d,'0以上7日以下'",
        "long-unhandled-age,365000000000d,'0より大きく365日以下'",
        "running-threshold,0s,'0より大きく7日以下'",
        "running-threshold,-1s,'0より大きく7日以下'",
        "delay-grace,-1s,'0以上7日以下'",
        "long-unhandled-age,0s,'0より大きく365日以下'",
        "long-unhandled-age,-1s,'0より大きく365日以下'",
        "consecutive-failures,0,'1以上100以下'",
        "consecutive-failures,-1,'1以上100以下'"})
    void outOfRangeSettingsFailBindingWithNameAndAllowedRange(String name, String value, String range) {
        runner.withPropertyValues("app.payment.discrepancy-audit.monitoring." + name + "=" + value)
            .run(context -> {
                assertThat(context).hasFailed();
                Throwable cause = context.getStartupFailure();
                while (cause.getCause() != null) cause = cause.getCause();
                assertThat(cause).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("app.payment.discrepancy-audit.monitoring." + name)
                    .hasMessageContaining(range);
            });
    }

}
