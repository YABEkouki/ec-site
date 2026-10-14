package com.example.ecsite.config;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class PayJpSafetyPropertiesTest {
    @Configuration(proxyBeanMethods=false)
    @EnableConfigurationProperties(PayJpSafetyProperties.class)
    static class Config {}
    ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(Config.class);
    @Test void defaultsAreBound() {
        context.run(c -> {
            assertThat(c).hasNotFailed();
            var properties=c.getBean(PayJpSafetyProperties.class);
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(15));
            assertThat(properties.postMaxAge()).isEqualTo(Duration.ofHours(23));
        });
    }
    @ParameterizedTest @ValueSource(strings={"connect-timeout=0s","read-timeout=-1s","post-max-age=24h","post-max-age=0s","connect-timeout=0.000001s"})
    void invalidValuesFailAtStartup(String value) {
        context.withPropertyValues("payjp.safety."+value).run(c -> assertThat(c).hasFailed());
    }
}
