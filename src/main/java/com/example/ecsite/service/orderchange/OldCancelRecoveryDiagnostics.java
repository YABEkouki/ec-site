package com.example.ecsite.service.orderchange;

import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;

/** Allowlisted metadata only. Never receives provider JSON, credentials, actor names or exception text. */
final class OldCancelRecoveryDiagnostics {
    enum Stage { SNAPSHOT, AUDIT_START, GET, LOCK, REVALIDATE, CONSUME, NEW_EVIDENCE, OLD_EVIDENCE, APPLY, COMPLETE, READBACK, AUDIT_RESULT }
    enum Reason { OBSERVATION_STALE, OBSERVATION_CLOCK_INVALID, OBSERVATION_CONSUMED, SNAPSHOT_CONFLICT,
        CONTEXT_DIGEST_CONFLICT, VERSION_CONFLICT, PAYMENT_STATE_CONFLICT, FINANCIAL_COMPETITION,
        TARGET_CONFLICT, OLD_EVIDENCE_UNCONFIRMED, NEW_AUTH_UNAVAILABLE, GET_UNKNOWN, DB_FAILURE,
        READBACK_FAILED, AUDIT_FAILED, UNEXPECTED_FAILURE }
    record Entry(UUID requestId, Stage stage, Reason reason, long observationElapsedMs, long lockRevalidationMs) {}
    static final class Failure extends IllegalStateException {
        final Reason reason;
        Failure(Reason reason) { super(reason.name()); this.reason=reason; }
    }
    static final Consumer<Entry> LOG = entry -> LoggerFactory.getLogger(OldCancelRecoveryDiagnostics.class).warn(
        "old_cancel_recovery request={} stage={} reason={} observation_elapsed_ms={} lock_revalidation_ms={}",
        entry.requestId(),entry.stage(),entry.reason(),entry.observationElapsedMs(),entry.lockRevalidationMs());
    static void emit(Consumer<Entry> sink, Entry entry) {
        // Diagnostics are observational: a broken sink must neither authorize success nor mask the original outcome.
        try { sink.accept(entry); } catch (RuntimeException unavailable) { /* Original recovery handling continues. */ }
    }
    private OldCancelRecoveryDiagnostics() {}
}
