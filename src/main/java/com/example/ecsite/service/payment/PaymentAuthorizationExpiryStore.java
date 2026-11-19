package com.example.ecsite.service.payment;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

/**
 * Feature135-A1: PAY.JP authorization expiry per AUTHORIZED payment (V93).
 *
 * The database creates a conservative ESTIMATED row whenever a payment becomes AUTHORIZED; this store
 * only replaces it once with the provider's expired_at, and reads it for the shipping gates. Times are
 * Asia/Tokyo wall-clock values, as elsewhere in the schema.
 */
@Component
public class PaymentAuthorizationExpiryStore {
    private static final Logger log = LoggerFactory.getLogger(PaymentAuthorizationExpiryStore.class);
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    public enum Source { ESTIMATED, PROVIDER }

    public record Expiry(long paymentId, LocalDateTime expiresAt, Source source, LocalDateTime estimatedExpiresAt) {}

    private final JdbcTemplate jdbc;
    private final EntityManager em;

    public PaymentAuthorizationExpiryStore(JdbcTemplate jdbc, EntityManager em) {
        this.jdbc = jdbc;
        this.em = em;
    }

    /**
     * Records the provider's expiry in the caller's transaction, right after the payment was marked AUTHORIZED.
     * Never throws for an unusable value: the conservative estimate is kept, so the authorization result still applies.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordProvider(long paymentId, Instant providerExpiresAt) {
        if (providerExpiresAt == null) return;
        em.flush(); // the AUTHORIZED update (and the V93 estimate) must be in the database first
        LocalDateTime value = LocalDateTime.ofInstant(providerExpiresAt, JST);
        int updated = jdbc.update("""
                update payment_authorization_expiries set source='PROVIDER', expires_at=?
                where payment_id=? and source='ESTIMATED'
                  and ? > authorization_base_at and ? <= authorization_base_at + interval '61 days'
                """, value, paymentId, value, value);
        if (updated != 1) {
            log.warn("Authorization expiry kept as recorded: paymentId={}, reason=NOT_ESTIMATED_OR_OUT_OF_RANGE", paymentId);
        }
    }

    @Transactional(propagation = Propagation.SUPPORTS)
    public Optional<Expiry> find(long paymentId) {
        return jdbc.query("""
                select payment_id, expires_at, source, estimated_expires_at
                from payment_authorization_expiries where payment_id=?
                """, (r, n) -> new Expiry(r.getLong(1), r.getObject(2, LocalDateTime.class),
                        Source.valueOf(r.getString(3)), r.getObject(4, LocalDateTime.class)), paymentId)
                .stream().findFirst();
    }

    /** Feature135-B1: true only when the payment has a recorded expiry that has passed at the database clock. */
    @Transactional(propagation = Propagation.SUPPORTS)
    public boolean hasExpired(long paymentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from payment_authorization_expiries
                              where payment_id = ? and expires_at <= timezone('Asia/Tokyo', clock_timestamp()))
                """, Boolean.class, paymentId));
    }

    /** Feature135-C2: the order was cancelled by the expired-authorization cancellation (V94 audit row). */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public boolean cancelledForExpiredAuthorization(long orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from payment_expired_cancellation_observations x
                              join payments p on p.id = x.payment_id join orders o on o.id = p.order_id
                              where o.id = ? and o.status = 'CANCELLED')
                """, Boolean.class, orderId));
    }

    /** The database's Asia/Tokyo wall clock, as used by every V93/V94 rule. */
    @Transactional(propagation = Propagation.SUPPORTS)
    public LocalDateTime databaseNow() {
        return jdbc.queryForObject("select timezone('Asia/Tokyo', clock_timestamp())", LocalDateTime.class);
    }

    /**
     * Feature135-B1: audit record of an expired authorization cancelled from a GET without a cancel POST (V94),
     * written in the caller's transaction after the CANCEL transaction has been flushed.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordExpiredCancellation(long transactionId, long paymentId, String providerPaymentId) {
        em.flush();
        jdbc.update("""
                insert into payment_expired_cancellation_observations
                    (transaction_id, payment_id, provider_payment_id, authorization_expires_at, expiry_source, observed_status, observed_at)
                values (?, ?, ?, timezone('Asia/Tokyo', clock_timestamp()), 'ESTIMATED', 'canceled', timezone('Asia/Tokyo', clock_timestamp()))
                """, transactionId, paymentId, providerPaymentId);
    }

    /**
     * Feature135-B2: audit row of a shipment capture settled as FAILED because PAY.JP canceled the Flow (V95).
     * Written in the same transaction as the FAILED result; the observation time and order are taken by the trigger.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCaptureCancellation(long transactionId, long paymentId, String providerPaymentId, String cancellationReason) {
        em.flush();
        jdbc.update("""
                insert into payment_capture_cancellation_observations
                    (transaction_id, payment_id, order_id, provider_payment_id, cancellation_reason, observed_status, observed_at)
                values (?, ?, 0, ?, ?, 'canceled', timezone('Asia/Tokyo', clock_timestamp()))
                """, transactionId, paymentId, providerPaymentId, cancellationReason);
    }

    /** Shipping preparation: valid for more than 24 hours after the database clock (same rule as V93). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireValidForPreparation(long paymentId) {
        requireValidBeyond(paymentId, "24 hours",
                "カード与信の有効期限まで24時間未満、または期限切れのため、発送準備を開始できません。");
    }

    /** Shipment capture start: still valid at the database clock (same rule as V93). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireValidForCapture(long paymentId) {
        requireValidBeyond(paymentId, "0 seconds",
                "カード与信の有効期限が切れているため、発送（売上確定）を開始できません。");
    }

    private void requireValidBeyond(long paymentId, String margin, String message) {
        em.flush();
        Boolean valid = jdbc.queryForObject("select f135_authorization_valid_beyond(?, ?::interval)",
                Boolean.class, paymentId, margin);
        if (!Boolean.TRUE.equals(valid)) throw new AuthorizationExpiryException(message);
    }
}
