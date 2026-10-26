package com.example.ecsite.service.payment.safety.lifecycle;

import java.time.Instant;
import java.util.UUID;

/** Historical save receipt plus a separate current database observation. Neither is a lease grant:
 * every subsequent write rechecks database time, owner, epoch and version. No financial permission. */
public record ObservationRunReceipt(Status status,RunView current,UUID eventUuid,String eventDigest,String sqlState) {
    public enum Status { APPLIED, REPLAYED, OBSERVED, NOT_APPLIED, UNKNOWN, CONTENT_CONFLICT, VERSION_CONFLICT,
        LEASE_LOST, STATE_CONFLICT, UNSUPPORTED_PHASE, STORAGE_FAILED, INTEGRITY_ERROR }
    public record RunView(UUID id,String status,UUID owner,long epoch,long version,Instant leaseUntil,long cursor,Instant observedAt) {}
    @Override public String toString(){return "ObservationRunReceipt[status="+status+"]";}
}
