package com.example.ecsite.service.payment.safety.payload;

/** Recomputed ORDER ledger projection. REVIEW is a subset of classified; no mutable increments. */
public record ObservationLedgerCounters(long processedOrderCount,long classifiedOrderCount,long reviewOrderCount,
        long acquisitionFailureAttemptCount,long assemblyFailureAttemptCount,long persistenceFailureAttemptCount,
        long interruptedAttemptCount,long retryAttemptCount,long unresolvedFailureOrderCount,long notFoundOrderCount,
        long skippedOrderCount) {
    public ObservationLedgerCounters {
        for(long n:new long[]{processedOrderCount,classifiedOrderCount,reviewOrderCount,acquisitionFailureAttemptCount,
                assemblyFailureAttemptCount,persistenceFailureAttemptCount,interruptedAttemptCount,retryAttemptCount,
                unresolvedFailureOrderCount,notFoundOrderCount,skippedOrderCount})if(n<0)throw PayloadEncodingV1.invalid();
        try {
            long total=Math.addExact(Math.addExact(classifiedOrderCount,notFoundOrderCount),Math.addExact(skippedOrderCount,unresolvedFailureOrderCount));
            if(total!=processedOrderCount || reviewOrderCount>classifiedOrderCount || skippedOrderCount!=0)throw PayloadEncodingV1.invalid();
        }catch(ArithmeticException invalid){throw PayloadEncodingV1.invalid();}
    }
}
