package com.example.ecsite.service.payment;

/** Result of background/webhook synchronization, distinct from API command success. */
public enum PaymentSynchronizationOutcome {
    APPLIED, ALREADY_APPLIED, IGNORED_STALE, CONFLICT, WAITING, SKIPPED;

    static PaymentSynchronizationOutcome from(PaymentResultApplication application) {
        return valueOf(application.name());
    }
}
