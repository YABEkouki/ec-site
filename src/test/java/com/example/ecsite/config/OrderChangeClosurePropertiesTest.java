package com.example.ecsite.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.example.ecsite.service.changeclosure.ChangeFlowClosureScheduler;
import com.example.ecsite.service.changeclosure.ChangeFlowClosureService;

/** Feature143-B: disabled by default, and the application does not start with a grace below 10 minutes. */
class OrderChangeClosurePropertiesTest {
    @Configuration
    @EnableConfigurationProperties(OrderChangeClosureProperties.class)
    @Import(ChangeFlowClosureScheduler.class)
    static class Config {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class)
            .withBean(ChangeFlowClosureService.class, () -> org.mockito.Mockito.mock(ChangeFlowClosureService.class));

    @Test void disabledWithSixtyMinutesByDefault() {
        runner.run(c -> {
            var p = c.getBean(OrderChangeClosureProperties.class);
            assertThat(p.enabled()).isFalse();
            assertThat(p.after()).isEqualTo(Duration.ofMinutes(60));
            assertThat(p.fixedDelay()).isEqualTo(Duration.ofMinutes(5));
            assertThat(p.retryAfter()).isEqualTo(Duration.ofHours(6));
            assertThat(p.batchSize()).isEqualTo(20);
            assertThat(c).doesNotHaveBean(ChangeFlowClosureScheduler.class);
        });
    }

    @Test void theSchedulerPassesTheConfiguredValuesAndSurvivesAFailure() {
        runner.withPropertyValues("app.order-change-closure.enabled=true", "app.order-change-closure.after=10m",
                "app.order-change-closure.batch-size=3").run(c -> {
            var service = c.getBean(ChangeFlowClosureService.class);
            org.mockito.Mockito.doThrow(new IllegalStateException("down")).when(service).closeDue(Duration.ofMinutes(10), Duration.ofHours(6), 3);
            c.getBean(ChangeFlowClosureScheduler.class).closeDue();
            org.mockito.Mockito.verify(service).closeDue(Duration.ofMinutes(10), Duration.ofHours(6), 3);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = { "after=9m", "after=0s", "after=8d", "fixed-delay=9s", "fixed-delay=25h", "retry-after=9m",
            "retry-after=8d", "batch-size=0", "batch-size=101" })
    void unsafeValuesAreRefusedAtStartup(String value) {
        runner.withPropertyValues("app.order-change-closure." + value)
                .run(c -> assertThat(c).getFailure().rootCause().hasMessageContaining("app.order-change-closure."));
    }
}
