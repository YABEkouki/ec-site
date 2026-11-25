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

import com.example.ecsite.service.unpaidclosure.UnpaidOrderClosureScheduler;
import com.example.ecsite.service.unpaidclosure.UnpaidOrderClosureService;

/** Feature142-B: disabled by default, and the application does not start with a wait below 60 minutes. */
class UnpaidOrderClosurePropertiesTest {
    @Configuration
    @EnableConfigurationProperties(UnpaidOrderClosureProperties.class)
    @Import(UnpaidOrderClosureScheduler.class)
    static class Config {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class)
            .withBean(UnpaidOrderClosureService.class, () -> org.mockito.Mockito.mock(UnpaidOrderClosureService.class));

    @Test void disabledWithTwentyFourHoursByDefault() {
        runner.run(c -> {
            var p = c.getBean(UnpaidOrderClosureProperties.class);
            assertThat(p.enabled()).isFalse();
            assertThat(p.after()).isEqualTo(Duration.ofHours(24));
            assertThat(p.retryAfter()).isEqualTo(Duration.ofHours(6));
            assertThat(c).doesNotHaveBean(UnpaidOrderClosureScheduler.class);
        });
    }

    @Test void theSchedulerPassesTheConfiguredValuesAndSurvivesAFailure() {
        runner.withPropertyValues("app.unpaid-order-closure.enabled=true", "app.unpaid-order-closure.after=60m",
                "app.unpaid-order-closure.batch-size=3").run(c -> {
            var service = c.getBean(UnpaidOrderClosureService.class);
            org.mockito.Mockito.doThrow(new IllegalStateException("down")).when(service).closeDue(Duration.ofMinutes(60), Duration.ofHours(6), 3);
            c.getBean(UnpaidOrderClosureScheduler.class).closeDue();
            org.mockito.Mockito.verify(service).closeDue(Duration.ofMinutes(60), Duration.ofHours(6), 3);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = { "after=59m", "after=0s", "after=31d", "fixed-delay=9s", "retry-after=9m", "batch-size=0", "batch-size=101" })
    void unsafeValuesAreRefusedAtStartup(String value) {
        runner.withPropertyValues("app.unpaid-order-closure." + value)
                .run(c -> assertThat(c).getFailure().rootCause().hasMessageContaining("app.unpaid-order-closure."));
    }
}
