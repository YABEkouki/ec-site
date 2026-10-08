package com.example.ecsite.service.payment;

import java.util.List;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import com.example.ecsite.entity.PaymentDiscrepancyAuditRunStatus;
import com.example.ecsite.entity.PaymentDiscrepancyAuditRunErrorCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.example.ecsite.config.PaymentDiscrepancyAuditProperties;
import com.example.ecsite.entity.Payment;
import com.example.ecsite.entity.PaymentMethod;
import com.example.ecsite.entity.PaymentProvider;
import com.example.ecsite.repository.PaymentRepository;

@Service
public class PaymentDiscrepancyAuditService {

    private static final Logger log = LoggerFactory.getLogger(PaymentDiscrepancyAuditService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentDiscrepancyAuditRetryFacade itemService;
    private final PaymentDiscrepancyAuditProperties properties;

    private final PaymentDiscrepancyAuditRunRecordingService recordingService;
    private final Clock clock;

    private long lastPaymentId;

    public PaymentDiscrepancyAuditService(
            PaymentRepository paymentRepository,
            PaymentDiscrepancyAuditRetryFacade itemService,
            PaymentDiscrepancyAuditProperties properties,
            PaymentDiscrepancyAuditRunRecordingService recordingService,
            Clock clock) {
        this.paymentRepository = paymentRepository;
        this.itemService = itemService;
        this.properties = properties;
        this.recordingService = recordingService;
        this.clock = clock;
    }

    public synchronized void auditPayments() {
        long startedNanos = System.nanoTime();
        Long runId;
        try {
            runId = recordingService.startRun();
        } catch (RuntimeException | Error failure) {
            log.error("Failed to start payment discrepancy audit run: phase=START_RECORDING, exceptionType={}",
                    failure.getClass().getName());
            throw failure;
        }

        RunCounts counts = new RunCounts();
        Throwable executionFailure = null;
        try {
            List<Long> paymentIds = findPaymentIdsAfter(lastPaymentId);
            if (paymentIds.isEmpty() && lastPaymentId > 0L) {
                lastPaymentId = 0L;
                paymentIds = findPaymentIdsAfter(lastPaymentId);
            }
            counts.candidates = paymentIds.size();

            for (Long paymentId : paymentIds) {
                PaymentDiscrepancyAuditResult result;
                try {
                    // Proxy returns only after the successful attempt commits.
                    result = itemService.auditWithResult(paymentId);
                } catch (Exception failure) {
                    counts.failures++;
                    log.error("Failed to audit payment discrepancy: runId={}, paymentId={}, exceptionType={}",
                            runId, paymentId, failure.getClass().getName());
                    continue;
                }
                counts.record(result);
            }

            if (!paymentIds.isEmpty()) {
                lastPaymentId = paymentIds.get(paymentIds.size() - 1);
            }
        } catch (RuntimeException | Error failure) {
            executionFailure = failure;
            log.error("Payment discrepancy audit run failed: runId={}, phase={}, exceptionType={}",
                    runId, counts.candidates == null ? "CANDIDATE_FETCH" : "EXECUTION",
                    failure.getClass().getName());
        }

        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        PaymentDiscrepancyAuditRunSummary summary = counts.summary(durationMs, executionFailure);
        try {
            recordingService.finishRun(runId, clock.instant(), summary);
        } catch (RuntimeException | Error recordingFailure) {
            // Never run the payment audit again to recover a recording failure.
            log.error("Failed to finish payment discrepancy audit run: runId={}, phase=FINISH_RECORDING, "
                    + "summary={}, exceptionType={}", runId, summary, recordingFailure.getClass().getName());
            if (executionFailure == null) {
                throw recordingFailure;
            }
            if (executionFailure != recordingFailure) {
                executionFailure.addSuppressed(recordingFailure);
            }
        }
        if (executionFailure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (executionFailure instanceof Error error) {
            throw error;
        }
    }

    private static final class RunCounts {
        private Integer candidates;
        private int successes;
        private int inconsistent;
        private int inProgress;
        private int skipped;
        private int failures;

        private void record(PaymentDiscrepancyAuditResult result) {
            switch (result.status()) {
                case CONSISTENT -> successes++;
                case INCONSISTENT -> { successes++; inconsistent++; }
                case IN_PROGRESS -> { successes++; inProgress++; }
                case SKIPPED -> skipped++;
            }
        }

        private PaymentDiscrepancyAuditRunSummary summary(long durationMs, Throwable executionFailure) {
            PaymentDiscrepancyAuditRunStatus status;
            PaymentDiscrepancyAuditRunErrorCode errorCode;
            if (executionFailure != null) {
                status = PaymentDiscrepancyAuditRunStatus.FAILED;
                errorCode = candidates == null ? PaymentDiscrepancyAuditRunErrorCode.CANDIDATE_FETCH_FAILED
                        : PaymentDiscrepancyAuditRunErrorCode.EXECUTION_ABORTED;
            } else if (failures == 0) {
                status = PaymentDiscrepancyAuditRunStatus.SUCCESS;
                errorCode = null;
            } else if (successes == 0 && skipped == 0) {
                status = PaymentDiscrepancyAuditRunStatus.FAILED;
                errorCode = PaymentDiscrepancyAuditRunErrorCode.ALL_ITEMS_FAILED;
            } else {
                status = PaymentDiscrepancyAuditRunStatus.PARTIAL_FAILURE;
                errorCode = PaymentDiscrepancyAuditRunErrorCode.ITEM_FAILURE;
            }
            return new PaymentDiscrepancyAuditRunSummary(status, candidates, successes, inconsistent,
                    inProgress, skipped, failures, durationMs, errorCode);
        }
    }

    private List<Long> findPaymentIdsAfter(long paymentId) {
        return paymentRepository
                .findByProviderAndPaymentMethodAndProviderPaymentIdIsNotNullAndIdGreaterThanOrderByIdAsc(
                        PaymentProvider.PAYJP,
                        PaymentMethod.CARD,
                        paymentId,
                        PageRequest.of(
                                0,
                                properties.batchSize()))
                .stream()
                .map(Payment::getId)
                .toList();
    }
}
