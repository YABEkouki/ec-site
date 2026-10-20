package com.example.ecsite.service.payment.safety;

import com.example.ecsite.payment.capture.CaptureConnectionIdentity;
import java.time.ZoneId;
import java.util.Map;

/** Trusted, non-secret historical approval inputs. These records do not authenticate an operator
 * or prove API-key ownership. No current configuration/key/zone is consulted. Approvals are scoped
 * to explicit Capture transaction IDs; absent approvals never inherit a deployment default.
 */
public record CaptureEvidenceProfiles(ConnectionManifest connectionManifest, TimestampBindingProfile timestampBinding) {
    public static CaptureEvidenceProfiles none() { return new CaptureEvidenceProfiles(null,null); }
    public record ConnectionManifest(String id,String approvalReference,Map<Long,CaptureConnectionIdentity> identitiesByTransaction) {
        public ConnectionManifest {
            requireApproval(id,approvalReference);identitiesByTransaction=Map.copyOf(identitiesByTransaction);
            identitiesByTransaction.keySet().forEach(CaptureEvidenceProfiles::positive);
        }
    }
    /** V1 encoding assertion from Feature123: Capture.created_at and Order.shipped_at share
     * the saved local value; interpreting Capture.created_at in the approved zone yields the
     * immutable request created_at. This proves an encoding binding, NOT event chronology or a
     * common clock domain. Ambiguous/nonexistent local times cannot prove this binding.
     */
    public record TimestampBindingProfile(int encodingVersion,String id,String approvalReference,Map<Long,String> localZonesByTransaction) {
        public TimestampBindingProfile {
            requireApproval(id,approvalReference);localZonesByTransaction=Map.copyOf(localZonesByTransaction);
            localZonesByTransaction.forEach((transaction,zone)->{positive(transaction);if(encodingVersion==1)ZoneId.of(zone);});
        }
        /** Explicit assertion of the documented Feature123 v1 encoding, scoped by transaction. */
        public TimestampBindingProfile(String id,String approvalReference,Map<Long,String> localZonesByTransaction) {
            this(1,id,approvalReference,localZonesByTransaction);
        }
    }
    private static void positive(Long id) { if(id==null || id<=0)throw new IllegalArgumentException("Positive approval target required"); }
    private static void requireApproval(String id,String reference) {
        if(id==null || id.isBlank() || reference==null || reference.isBlank())throw new IllegalArgumentException("Explicit historical approval identification required");
    }
}
