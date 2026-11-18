package com.example.ecsite.support.isolation;

import java.lang.annotation.*;

/**
 * Database modes in which scripts/run-isolated-tests.sh may run this test class.
 *
 * <p>A class without this annotation runs only with {@code --testcontainers}. The annotation is
 * deliberately not {@link Inherited}: subclasses (for example the Customer* tests that extend
 * OrderEditReauthorizationIntegrationTest) declare their own mode. The launcher checks it before
 * connecting to any database or starting a container, and stops on a mismatch.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface IsolatedTestMode {
    Mode[] value();

    enum Mode {
        /** Shared 127.0.0.1:5432/ecsite_test ({@code --reset-test-db}, or no mode option). */
        SHARED,
        /** Disposable PostgreSQL 17 container started by the launcher ({@code --testcontainers}). */
        TESTCONTAINERS
    }
}
