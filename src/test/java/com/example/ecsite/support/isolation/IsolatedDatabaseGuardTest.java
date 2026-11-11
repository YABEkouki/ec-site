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
    @Test void sharedDatabaseAddressIsExplicitAndDoesNotNeedDocker() {
        var address=IsolatedDatabaseGuard.address("jdbc:postgresql://127.0.0.1:5432/ecsite_test");
        assertThat(address.host()).isEqualTo("127.0.0.1");
        assertThat(address.port()).isEqualTo(5432);
        assertThat(address.database()).isEqualTo("ecsite_test");
    }
    @ParameterizedTest @ValueSource(strings={
        "jdbc:postgresql://localhost:5432/ecsite_test","jdbc:postgresql://example.invalid:5432/ecsite_test",
        "jdbc:postgresql://127.0.0.1:55432/ecsite_test","jdbc:postgresql://127.0.0.1:5432/ecsite",
        "jdbc:postgresql://127.0.0.1:5432/another_database"})
    void rejectsUnapprovedSharedDatabaseAddresses(String url) {
        assertThatThrownBy(()->IsolatedDatabaseGuard.address(url)).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsDatasourceFlywayMismatch(){
        var ds=new IsolatedDatabaseGuard.Connection("jdbc:postgresql://localhost:55432/isolated_abc","user","secret");
        assertThatThrownBy(()->IsolatedDatabaseGuard.same(ds,new IsolatedDatabaseGuard.Connection("jdbc:postgresql://localhost:55433/isolated_abc","user","secret"))).isInstanceOf(IllegalStateException.class);
        assertThat(ds.toString()).doesNotContain("secret","55432");
    }
    @Test void acceptsSafeShapeButStillRequiresProvenance(){
        assertThat(IsolatedDatabaseGuard.address("jdbc:postgresql://localhost:55432/isolated_abc").database()).isEqualTo("isolated_abc");
        assertThat(IsolatedDatabaseGuard.address("jdbc:postgresql://localhost:55432/isolated_abc?loggerLevel=OFF").database()).isEqualTo("isolated_abc");
    }
    static org.springframework.mock.env.MockEnvironment facts(String url,String user,String mode) {
        var env=new org.springframework.mock.env.MockEnvironment();env.setActiveProfiles("test");
        env.setProperty("ecsite.test.database-mode",mode);
        for(String prefix:java.util.List.of("spring.datasource","spring.flyway")) {
            env.setProperty(prefix+".url",url);env.setProperty(prefix+(prefix.endsWith("flyway")?".user":".username"),user);
            env.setProperty(prefix+".password","unit-only-placeholder");
        }
        return env;
    }
    @Test void sharedConfigurationRequiresProfileApprovedUserAndMatchingFacts() {
        String url="jdbc:postgresql://127.0.0.1:5432/ecsite_test";
        assertThat(IsolatedDatabaseGuard.configuration(facts(url,"ecuser","shared")).user()).isEqualTo("ecuser");
        assertThatThrownBy(()->IsolatedDatabaseGuard.configuration(facts(url,"another_user","shared"))).isInstanceOf(IllegalStateException.class);
        var missingProfile=facts(url,"ecuser","shared");missingProfile.setActiveProfiles("production");
        assertThatThrownBy(()->IsolatedDatabaseGuard.verify(missingProfile)).isInstanceOf(IllegalStateException.class);
        for(String key:java.util.List.of("spring.flyway.url","spring.flyway.user","spring.flyway.password")) {
            var mismatch=facts(url,"ecuser","shared");mismatch.setProperty(key,"different");
            assertThatThrownBy(()->IsolatedDatabaseGuard.configuration(mismatch)).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void containerModeRemainsExplicitAndCannotApproveSharedOrForeignUrls() {
        String url="jdbc:postgresql://127.0.0.1:55432/isolated_unit";
        assertThat(IsolatedDatabaseGuard.configuration(facts(url,"container_user","testcontainers")).url()).isEqualTo(url);
        assertThatThrownBy(()->IsolatedDatabaseGuard.configuration(facts(url,"container_user","shared"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->IsolatedDatabaseGuard.configuration(facts("jdbc:postgresql://127.0.0.1:5432/ecsite_test","ecuser","testcontainers"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->IsolatedDatabaseGuard.configuration(facts(url,"container_user","unknown"))).isInstanceOf(IllegalStateException.class);
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
