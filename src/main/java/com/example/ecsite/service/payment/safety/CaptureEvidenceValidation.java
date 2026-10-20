package com.example.ecsite.service.payment.safety;

import java.util.List;
import java.util.Objects;
import com.example.ecsite.service.payment.PaymentSafetySnapshot.CaptureVerification;
import com.example.ecsite.service.payment.safety.PaymentSafetyReadFacts.*;

/** Immutable audit evidence only. Never contains a restored HTTP request or POST capability.
 * Raw rows remain available even for contradictions, missing targets and duplicates.
 */
public record CaptureEvidenceValidation(CaptureEvidenceProfiles profiles,List<CaptureAssessment> captures,List<Finding> globalFindings) {
    public CaptureEvidenceValidation { Objects.requireNonNull(profiles);captures=List.copyOf(captures);globalFindings=List.copyOf(globalFindings); }
    public record CaptureAssessment(TransactionRow transaction,List<CaptureRow> requests,
            CaptureVerification verification,List<Finding> findings) {
        public CaptureAssessment { Objects.requireNonNull(transaction);Objects.requireNonNull(verification);requests=List.copyOf(requests);findings=List.copyOf(findings); }
    }
    public enum Code {
        REQUEST_MISSING, REQUEST_MULTIPLE, DUPLICATE_ID, TARGET_MISMATCH, HTTP_INTEGRITY_MISMATCH,
        TIME_METADATA_MISMATCH, CONNECTION_APPROVAL_MISSING, CONNECTION_MISMATCH,
        TIME_PROFILE_MISSING, TIME_PROFILE_UNSUPPORTED, TIME_BINDING_UNPROVEN, TIME_BINDING_MISMATCH, ORPHAN_REQUEST
    }
    public record Finding(Code code,Long paymentId,Long transactionId,Long requestId,String detail) {
        public Finding {Objects.requireNonNull(code);Objects.requireNonNull(detail);}
    }
}
