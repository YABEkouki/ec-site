package com.example.ecsite.service.payment.safety;

import java.util.Map;
import java.util.Objects;
import com.example.ecsite.service.payment.PaymentSafetySnapshot.TimeReference;
import com.example.ecsite.service.payment.PaymentSafetySnapshot.TimeOrigin;

/** Explicit caller-supplied, trusted evidence only. No environment, clock or provider lookup.
 * Profile values should identify approved version/digest, never secrets. Limits are application
 * protection values, not PAY.JP guarantees. A failed read requires caller rollback.
 */
public record PaymentSafetyReadContext(Limits limits, Map<String,String> verificationProfiles,
        Map<TimeReference,TimeOrigin> timeOrigins) {
    public PaymentSafetyReadContext {
        Objects.requireNonNull(limits);
        verificationProfiles=Map.copyOf(verificationProfiles); timeOrigins=Map.copyOf(timeOrigins);
    }
    public static PaymentSafetyReadContext defaults() { return new PaymentSafetyReadContext(Limits.defaults(),Map.of(),Map.of()); }
    public record Limits(int payments, int transactions, int statusHistory, int captureRequests,
            long inputBytes, int fetchSize, int queryTimeoutSeconds, int lockTimeoutMillis) {
        public Limits {
            if (payments<1 || transactions<1 || statusHistory<1 || captureRequests<1
                    || Math.max(Math.max(payments,transactions),Math.max(statusHistory,captureRequests))==Integer.MAX_VALUE
                    || inputBytes<1 || inputBytes>Integer.MAX_VALUE-1L || fetchSize<1 || queryTimeoutSeconds<1 || lockTimeoutMillis<1)
                throw new IllegalArgumentException("Positive bounded read limits/timeouts are required");
        }
        public static Limits defaults() { return new Limits(1000,10000,10000,10000,16L*1024*1024,100,30,5000); }
    }
}
