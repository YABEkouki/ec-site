package com.example.ecsite.service.payment.safety;

import java.util.Objects;
import com.example.ecsite.service.payment.PaymentSafetySnapshot;

/** Retains raw evidence omitted by the narrower classifier model (notes, bytes, active pointer).
 * A Snapshot/reference assessment is never permission to resume a financial operation.
 */
public record PaymentSafetySnapshotAssembly(PaymentSafetyReadFacts facts,PaymentSafetySnapshot snapshot,
        CaptureEvidenceValidation evidence,String inputFingerprint) {
    public PaymentSafetySnapshotAssembly {
        Objects.requireNonNull(facts);Objects.requireNonNull(snapshot);Objects.requireNonNull(evidence);Objects.requireNonNull(inputFingerprint);
    }
}
