package com.example.ecsite.service.payment;

import java.time.Instant;
import com.example.ecsite.payment.capture.CaptureHttpRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short write-capable validation TX. Completion releases all locks before HTTP; not an execution lease. */
@Service
public class CaptureInitialPostPreparationService {
    private final CaptureShipmentOperations operations;
    public CaptureInitialPostPreparationService(CaptureShipmentOperations operations) { this.operations = operations; }
    @Transactional
    public Prepared prepare(CaptureShipmentOperations.InitialPost initial) { return operations.prepareInitialPost(initial); }
    public sealed interface Prepared permits Ready, AlreadyApplied {}
    public record AlreadyApplied() implements Prepared {}
    public record Ready(CaptureHttpRequest request, Instant createdAt, Instant notAfter) implements Prepared {
        public void requireWithinWindow(Instant now) {
            if (now == null || createdAt == null || notAfter == null || now.isBefore(createdAt) || !now.isBefore(notAfter))
                throw new IllegalStateException("Capture initial POST window is invalid or expired");
        }
    }
}
