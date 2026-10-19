package com.example.ecsite.support;

import java.lang.annotation.*;
import org.springframework.test.context.TestPropertySource;

/** Explicit identity only for tests whose Gateway is mocked/blocked by the isolation guard. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@TestPropertySource(properties={"payjp.api-base-url=https://api.pay.jp", "payjp.capture-connection.connection-id=test-capture",
    "payjp.capture-connection.provider-account-ref=test-account", "payjp.capture-connection.mode=TEST",
    "payjp.capture-connection.api-origin=https://api.pay.jp"})
public @interface CaptureConnectionTest {}
