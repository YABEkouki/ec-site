package com.example.ecsite.support.isolation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.assertj.core.api.Assertions.*;

class IsolatedDatabaseGuardTest {
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={
        "jdbc:postgresql://localhost:5432/ecsite","jdbc:postgresql://localhost:55432/ecsite",
        "jdbc:postgresql://localhost:55432/ecsite_test","jdbc:postgresql://localhost:5432/isolated_abc",
        "jdbc:postgresql://localhost:55432/isolated_abc?password=x","jdbc:h2:mem:test"})
    void rejectsMissingOrUnsafeAddress(String url){assertThatThrownBy(()->IsolatedDatabaseGuard.address(url)).isInstanceOf(IllegalStateException.class);}
    @Test void rejectsDatasourceFlywayMismatch(){
        var ds=new IsolatedDatabaseGuard.Connection("jdbc:postgresql://localhost:55432/isolated_abc","user","secret");
        assertThatThrownBy(()->IsolatedDatabaseGuard.same(ds,new IsolatedDatabaseGuard.Connection("jdbc:postgresql://localhost:55433/isolated_abc","user","secret"))).isInstanceOf(IllegalStateException.class);
        assertThat(ds.toString()).doesNotContain("secret","55432");
    }
    @Test void acceptsSafeShapeButStillRequiresProvenance(){
        assertThat(IsolatedDatabaseGuard.address("jdbc:postgresql://localhost:55432/isolated_abc").database()).isEqualTo("isolated_abc");
        assertThat(IsolatedDatabaseGuard.address("jdbc:postgresql://localhost:55432/isolated_abc?loggerLevel=OFF").database()).isEqualTo("isolated_abc");
    }
    @Test void missingConfigurationStopsBeforeDatasourceAndFlywayCreation(){
        var created=new java.util.concurrent.atomic.AtomicBoolean();
        try(var context=new org.springframework.context.support.GenericApplicationContext()) {
            context.setEnvironment(new org.springframework.mock.env.MockEnvironment());
            context.registerBean("dataSource",Object.class,()->{created.set(true);return new Object();});
            context.registerBean("flyway",Object.class,()->{created.set(true);return new Object();});
            new IsolationContextCustomizerFactory().createContextCustomizer(IsolatedTestSmokeTest.class,java.util.List.of()).customizeContext(context,null);
            assertThatThrownBy(context::refresh).isInstanceOf(IllegalStateException.class);
            assertThat(created).isFalse();
        }
    }
}
