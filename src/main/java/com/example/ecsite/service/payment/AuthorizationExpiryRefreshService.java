package com.example.ecsite.service.payment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.entity.PaymentStatus;
import com.example.ecsite.payment.AuthorizationResult;
import com.example.ecsite.payment.AuthorizationResultStatus;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.repository.OrderRepository;
import com.example.ecsite.repository.PaymentRepository;

/**
 * Feature135-B3: replaces the conservative ESTIMATED authorization expiry (V93) with PAY.JP's expired_at.
 *
 * Each candidate is read by GET outside any database transaction. Only a Flow still in requires_capture with an
 * expired_at is recorded, in a short transaction that locks the order first and then its payments in ID order and
 * re-checks that the payment is still the same AUTHORIZED PAY.JP payment with an ESTIMATED expiry. Canceled or other
 * Flows are left to Feature135-B1/B2 and are not asked again for a while in this process. The replacement itself
 * happens at most once per payment (V93 trigger).
 */
@Service
public class AuthorizationExpiryRefreshService {
    private static final Logger log = LoggerFactory.getLogger(AuthorizationExpiryRefreshService.class);

    public enum Outcome { RECORDED, NOT_RECORDABLE, STALE, FAILED }
    public record Candidate(long orderId, long paymentId, String providerPaymentId) {}
    public record Summary(int candidates, int recorded, int notRecordable, int stale, int failed) {}

    private final Transactions transactions;
    private final PaymentGateway gateway;
    private final Clock clock;
    private final int batchSize;
    private final Duration retryAfter;
    /** Payments whose last GET gave nothing to record, and when they may be asked again (lost on restart). */
    private final Map<Long, Instant> notBefore = new ConcurrentHashMap<>();

    public AuthorizationExpiryRefreshService(Transactions transactions, PaymentGateway gateway, Clock clock,
            @Value("${app.payment.authorization-expiry-refresh.batch-size:20}") int batchSize,
            @Value("${app.payment.authorization-expiry-refresh.retry-after:6h}") Duration retryAfter) {
        if (batchSize < 1 || batchSize > 200) throw new IllegalArgumentException("batch-size must be 1..200");
        if (retryAfter.isNegative()) throw new IllegalArgumentException("retry-after must not be negative");
        this.transactions = transactions;
        this.gateway = gateway;
        this.clock = clock;
        this.batchSize = batchSize;
        this.retryAfter = retryAfter;
    }

    @Transactional(propagation = Propagation.NEVER)
    public synchronized Summary runPass() {
        Instant now = clock.instant();
        notBefore.values().removeIf(until -> !until.isAfter(now));
        List<Candidate> candidates = transactions.candidates(batchSize + notBefore.size()).stream()
                .filter(c -> !notBefore.containsKey(c.paymentId())).limit(batchSize).toList();
        int recorded = 0, notRecordable = 0, stale = 0, failed = 0;
        for (Candidate candidate : candidates) {
            Outcome outcome = refresh(candidate);
            switch (outcome) {
                case RECORDED -> recorded++;
                case NOT_RECORDABLE -> { notRecordable++; notBefore.put(candidate.paymentId(), now.plus(retryAfter)); }
                case STALE -> stale++;
                case FAILED -> failed++;
            }
        }
        Summary summary = new Summary(candidates.size(), recorded, notRecordable, stale, failed);
        if (!candidates.isEmpty()) log.info("Authorization expiry refresh: {}", summary);
        return summary;
    }

    private Outcome refresh(Candidate candidate) {
        AuthorizationResult result;
        try {
            result = gateway.retrieveAuthorization(candidate.providerPaymentId()).result();
        } catch (RuntimeException failure) {
            log.warn("Authorization expiry refresh GET failed: paymentId={}, exceptionType={}",
                    candidate.paymentId(), failure.getClass().getName());
            return Outcome.FAILED;
        }
        if (result == null || result.status() != AuthorizationResultStatus.AUTHORIZED || result.expiresAt() == null
                || !Objects.equals(result.providerTransactionId(), candidate.providerPaymentId()))
            return Outcome.NOT_RECORDABLE;
        try {
            return transactions.record(candidate, result.expiresAt());
        } catch (RuntimeException failure) {
            log.warn("Authorization expiry refresh failed: paymentId={}, exceptionType={}",
                    candidate.paymentId(), failure.getClass().getName());
            return Outcome.FAILED;
        }
    }

    @Service
    public static class Transactions {
        private final JdbcTemplate jdbc;
        private final OrderRepository orders;
        private final PaymentRepository payments;
        private final PaymentAuthorizationExpiryStore expiries;

        public Transactions(JdbcTemplate jdbc, OrderRepository orders, PaymentRepository payments,
                PaymentAuthorizationExpiryStore expiries) {
            this.jdbc = jdbc;
            this.orders = orders;
            this.payments = payments;
            this.expiries = expiries;
        }

        /** ESTIMATED expiries of AUTHORIZED PAY.JP card payments of live orders, at most 25 days after the base. */
        @Transactional(readOnly = true)
        public List<Candidate> candidates(int limit) {
            return jdbc.query("""
                    select o.id, p.id, p.provider_payment_id
                    from payment_authorization_expiries e
                    join payments p on p.id = e.payment_id
                    join orders o on o.id = p.order_id
                    where e.source = 'ESTIMATED' and p.status = 'AUTHORIZED' and p.provider = 'PAYJP'
                      and p.payment_method = 'CARD' and p.provider_payment_id is not null and o.status <> 'CANCELLED'
                      and e.authorization_base_at + interval '25 days' > timezone('Asia/Tokyo', clock_timestamp())
                    order by e.expires_at, p.id
                    limit ?
                    """, (r, n) -> new Candidate(r.getLong(1), r.getLong(2), r.getString(3)), limit);
        }

        /** Lock order: the order first, then its payments in ID order. */
        @Transactional
        public Outcome record(Candidate candidate, Instant providerExpiresAt) {
            var order = orders.findByIdForUpdate(candidate.orderId()).orElse(null);
            if (order == null || order.getStatus() == OrderStatus.CANCELLED) return Outcome.STALE;
            var payment = payments.findByOrderIdForUpdate(candidate.orderId()).stream()
                    .filter(p -> p.getId() == candidate.paymentId()).findFirst().orElse(null);
            if (payment == null || payment.getStatus() != PaymentStatus.AUTHORIZED
                    || !Objects.equals(payment.getProviderPaymentId(), candidate.providerPaymentId()))
                return Outcome.STALE;
            var before = expiries.find(candidate.paymentId()).orElse(null);
            if (before == null || before.source() != PaymentAuthorizationExpiryStore.Source.ESTIMATED) return Outcome.STALE;
            expiries.recordProvider(candidate.paymentId(), providerExpiresAt);
            var after = expiries.find(candidate.paymentId()).orElseThrow();
            return after.source() == PaymentAuthorizationExpiryStore.Source.PROVIDER ? Outcome.RECORDED : Outcome.NOT_RECORDABLE;
        }
    }
}
