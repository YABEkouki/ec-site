package com.example.ecsite.service.payment;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.example.ecsite.entity.PaymentDiscrepancyAuditRun;
import com.example.ecsite.repository.PaymentDiscrepancyAuditRunRepository;

/** Records runs only. Does not execute or retry payment audits. */
@Service
public class PaymentDiscrepancyAuditRunRecordingService {
    private final PaymentDiscrepancyAuditRunRepository repository;
    private final Clock clock;
    // Singleton bean lifetime is the application process lifetime; never generated per run.
    private final String instanceId = UUID.randomUUID().toString();

    public PaymentDiscrepancyAuditRunRecordingService(PaymentDiscrepancyAuditRunRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long startRun() {
        return repository.save(PaymentDiscrepancyAuditRun.start(instanceId, clock.instant())).getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishRun(Long runId, Instant finishedAt, PaymentDiscrepancyAuditRunSummary summary) {
        if (runId == null || finishedAt == null || summary == null) {
            throw new IllegalArgumentException("Run ID, finish time and summary are required");
        }
        PaymentDiscrepancyAuditRun run = repository.findForFinish(runId)
                .orElseThrow(() -> new IllegalArgumentException("Audit run not found"));
        run.finish(finishedAt, summary);
    }
}
