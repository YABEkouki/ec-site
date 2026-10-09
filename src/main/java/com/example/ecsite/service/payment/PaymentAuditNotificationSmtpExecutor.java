package com.example.ecsite.service.payment;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.example.ecsite.entity.*;

/** One SMTP worker, zero queue. A reservation occupies the actual worker before any DB attempt is reserved. */
@Component
@ConditionalOnProperty(prefix = "app.payment.discrepancy-audit.notification", name = "enabled", havingValue = "true")
public class PaymentAuditNotificationSmtpExecutor implements AutoCloseable {
    private final PaymentDiscrepancyAuditNotificationMailClient client;
    private final AtomicBoolean occupied = new AtomicBoolean();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
        new SynchronousQueue<>(), runnable -> {
            var thread = new Thread(runnable, "payment-audit-smtp"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());

    public record Outcome(PaymentAuditNotificationAttemptResult result, PaymentAuditNotificationFailureCode code) {}

    public PaymentAuditNotificationSmtpExecutor(PaymentDiscrepancyAuditNotificationMailClient client) { this.client = client; }

    public Optional<Slot> reserve() {
        if (!occupied.compareAndSet(false, true)) return Optional.empty();
        var slot = new Slot();
        try { executor.execute(slot::run); return Optional.of(slot); }
        catch (RejectedExecutionException failure) { occupied.set(false); return Optional.empty(); }
    }

    public final class Slot implements AutoCloseable {
        private final CompletableFuture<PaymentAuditNotificationMail> input = new CompletableFuture<>();
        private final CompletableFuture<Outcome> output = new CompletableFuture<>();
        private Thread worker;
        private boolean submitted;
        private enum DispatchState { PENDING, RUNNING, CANCELLED, FINISHED }
        private DispatchState state = DispatchState.PENDING;
        private long deadlineNanos;

        private void run() {
            synchronized (this) { worker = Thread.currentThread(); }
            try {
                var mail = input.get();
                boolean dispatch;
                synchronized (this) {
                    // Atomically fence pending dispatch after timeout/interruption, including late worker startup.
                    dispatch = mail != null && state == DispatchState.PENDING
                        && !Thread.currentThread().isInterrupted() && System.nanoTime() - deadlineNanos < 0;
                    if (dispatch) state = DispatchState.RUNNING;
                }
                if (dispatch) {
                    client.send(mail);
                    output.complete(new Outcome(PaymentAuditNotificationAttemptResult.SUCCESS, null));
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                output.complete(unknown());
            } catch (Exception failure) {
                output.complete(new Outcome(PaymentAuditNotificationAttemptResult.FAILURE, PaymentAuditNotificationFailureCode.SMTP_SEND_FAILED));
            } catch (Throwable failure) {
                // No uncaught SMTP Error may print message/credentials via the worker's exception handler.
                output.complete(new Outcome(PaymentAuditNotificationAttemptResult.UNKNOWN, PaymentAuditNotificationFailureCode.SMTP_SEND_FAILED));
            } finally {
                synchronized (this) {
                    // Synchronize cleanup with interrupt so a reused pool thread is never interrupted by an old slot.
                    state = DispatchState.FINISHED;
                    worker = null;
                    output.complete(unknown());
                    occupied.set(false);
                }
            }
        }
        public Outcome send(PaymentAuditNotificationMail mail, Duration deadline) {
            synchronized (this) {
                if (submitted || state != DispatchState.PENDING) throw new IllegalStateException("SMTP reservation already submitted or released");
                submitted = true;
                if (Thread.currentThread().isInterrupted() || deadline.isZero() || deadline.isNegative()) {
                    state = DispatchState.CANCELLED; input.complete(null); return unknown();
                }
                deadlineNanos = System.nanoTime() + deadline.toNanos();
                input.complete(mail);
            }
            try { return output.get(deadline.toMillis(), TimeUnit.MILLISECONDS); }
            catch (TimeoutException failure) { abandon(); return unknown(); }
            catch (InterruptedException failure) { abandon(); Thread.currentThread().interrupt(); return unknown(); }
            catch (ExecutionException failure) { abandon(); return unknown(); }
        }
        private synchronized void abandon() {
            if (state == DispatchState.PENDING) state = DispatchState.CANCELLED;
            // Interrupt inside the same monitor as cleanup; actual SMTP may ignore it and retains occupancy.
            if (worker != null) worker.interrupt();
        }
        @Override public synchronized void close() {
            // Releases unused reservations, but never releases a running SMTP operation early.
            if (!submitted) { state = DispatchState.CANCELLED; input.complete(null); }
        }
    }
    private static Outcome unknown() {
        return new Outcome(PaymentAuditNotificationAttemptResult.UNKNOWN, PaymentAuditNotificationFailureCode.SEND_DEADLINE_EXCEEDED);
    }
    @Override public void close() { executor.shutdownNow(); }
}
