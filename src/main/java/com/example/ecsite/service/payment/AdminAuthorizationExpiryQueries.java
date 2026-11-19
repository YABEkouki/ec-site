package com.example.ecsite.service.payment;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.form.AdminAuthorizationExpiryFilter;

/**
 * Feature135-A2: read-only views of the card authorization expiry (V93) for the administrator order screens.
 * Only AUTHORIZED payments of ORDERED orders are shown: those are the authorizations a shipment would capture.
 * "Now" is the database clock, as for the V93 shipping gates.
 */
@Service
@Transactional(readOnly = true)
public class AdminAuthorizationExpiryQueries {

    /** The shipping preparation margin of V93. */
    static final Duration MARGIN = Duration.ofHours(24);

    public enum Level { EXPIRED, SOON, VALID }

    public record Badge(Level level, LocalDateTime expiresAt, boolean estimated, long remainingHours) {
        public String label() {
            return switch (level) {
                case EXPIRED -> "与信期限切れ";
                case SOON -> "与信期限まで" + remainingHours + "時間";
                case VALID -> "与信期限まで残り" + (remainingHours / 24) + "日";
            };
        }
        public boolean attention() { return level != Level.VALID; }
    }

    public record PaymentExpiry(long paymentId, boolean active, Badge badge) {
        public String sourceLabel() { return badge.estimated() ? "推定（作成時刻＋5日）" : "PAY.JP"; }
    }

    public record Discrepancy(long id, long paymentId, String localStatus, String providerStatus,
            LocalDateTime firstDetectedAt, String handlingStatus) {}

    /** Feature135-C1: the shipment capture failed because PAY.JP had canceled the Flow (V95). */
    public record CaptureCancellation(long paymentId, String cancellationReason, LocalDateTime observedAt) {}

    /** Feature135-C1: the order was cancelled after its authorization expired, without a cancel POST (V94). */
    public record ExpiredCancellation(long paymentId, LocalDateTime authorizationExpiresAt, LocalDateTime observedAt) {}

    public record OrderExpiry(List<PaymentExpiry> payments, List<Discrepancy> openDiscrepancies,
            CaptureCancellation captureCancellation, ExpiredCancellation expiredCancellation) {
        public OrderExpiry(List<PaymentExpiry> payments, List<Discrepancy> openDiscrepancies) {
            this(payments, openDiscrepancies, null, null);
        }
        public boolean isEmpty() {
            return payments.isEmpty() && openDiscrepancies.isEmpty() && captureCancellation == null && expiredCancellation == null;
        }
        public boolean hasExpired() { return payments.stream().anyMatch(p -> p.badge().level() == Level.EXPIRED); }
    }

    /** Feature135-C1: dashboard counts; each links to the order list filter of the same name. */
    public record Summary(long expired, long attention, long captureCanceled) {}

    private final NamedParameterJdbcTemplate jdbc;

    public AdminAuthorizationExpiryQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Order IDs matching the filter; the caller restricts its own search to them. */
    public List<Long> orderIds(AdminAuthorizationExpiryFilter filter) {
        if (filter == AdminAuthorizationExpiryFilter.CAPTURE_CANCELED) {
            return jdbc.queryForList("""
                    select distinct c.order_id from payment_capture_cancellation_observations c
                    join orders o on o.id = c.order_id
                    where o.status = 'SHIPPED' and o.paid_at is null
                    order by c.order_id
                    """, new MapSqlParameterSource(), Long.class);
        }
        LocalDateTime now = now();
        LocalDateTime limit = filter == AdminAuthorizationExpiryFilter.EXPIRED ? now : now.plus(MARGIN);
        return jdbc.queryForList("""
                select distinct p.order_id from payments p
                join orders o on o.id = p.order_id
                join payment_authorization_expiries e on e.payment_id = p.id
                where o.status = 'ORDERED' and p.status = 'AUTHORIZED' and e.expires_at <= :limit
                order by p.order_id
                """, new MapSqlParameterSource("limit", limit), Long.class);
    }

    /** Feature135-C1: the same sets as {@link #orderIds}, counted. */
    public Summary summary() {
        LocalDateTime now = now();
        var params = new MapSqlParameterSource("now", now).addValue("limit", now.plus(MARGIN));
        return jdbc.queryForObject("""
                select
                  (select count(distinct p.order_id) from payments p join orders o on o.id = p.order_id
                     join payment_authorization_expiries e on e.payment_id = p.id
                   where o.status = 'ORDERED' and p.status = 'AUTHORIZED' and e.expires_at <= :now),
                  (select count(distinct p.order_id) from payments p join orders o on o.id = p.order_id
                     join payment_authorization_expiries e on e.payment_id = p.id
                   where o.status = 'ORDERED' and p.status = 'AUTHORIZED' and e.expires_at <= :limit),
                  (select count(distinct c.order_id) from payment_capture_cancellation_observations c
                     join orders o on o.id = c.order_id where o.status = 'SHIPPED' and o.paid_at is null)
                """, params, (rs, n) -> new Summary(rs.getLong(1), rs.getLong(2), rs.getLong(3)));
    }

    /** The earliest expiry among each order's AUTHORIZED payments; orders without one are absent. */
    public Map<Long, Badge> badges(Collection<Long> orderIds) {
        var result = new HashMap<Long, Badge>();
        if (orderIds == null || orderIds.isEmpty()) return result;
        LocalDateTime now = now();
        jdbc.query("""
                select distinct on (p.order_id) p.order_id, e.expires_at, e.source
                from payments p
                join orders o on o.id = p.order_id
                join payment_authorization_expiries e on e.payment_id = p.id
                where p.order_id in (:ids) and o.status = 'ORDERED' and p.status = 'AUTHORIZED'
                order by p.order_id, e.expires_at, p.id
                """, new MapSqlParameterSource("ids", orderIds), rs -> {
            result.put(rs.getLong(1), badge(rs.getObject(2, LocalDateTime.class), "ESTIMATED".equals(rs.getString(3)), now));
        });
        return result;
    }

    /** Every AUTHORIZED payment of an ORDERED order with its expiry, and the order's open payment discrepancies. */
    public OrderExpiry forOrder(long orderId) {
        LocalDateTime now = now();
        var params = new MapSqlParameterSource("order", orderId);
        List<PaymentExpiry> payments = jdbc.query("""
                select p.id, (o.active_payment_id = p.id) as active, e.expires_at, e.source
                from payments p
                join orders o on o.id = p.order_id
                join payment_authorization_expiries e on e.payment_id = p.id
                where p.order_id = :order and o.status = 'ORDERED' and p.status = 'AUTHORIZED'
                order by p.id
                """, params, (rs, n) -> new PaymentExpiry(rs.getLong(1), rs.getBoolean(2),
                        badge(rs.getObject(3, LocalDateTime.class), "ESTIMATED".equals(rs.getString(4)), now)));
        List<Discrepancy> discrepancies = jdbc.query("""
                select d.id, d.payment_id, d.local_status, d.provider_status, d.first_detected_at, d.handling_status
                from payment_discrepancies d join payments p on p.id = d.payment_id
                where p.order_id = :order and d.status = 'OPEN'
                order by d.id
                """, params, (rs, n) -> new Discrepancy(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5, LocalDateTime.class), rs.getString(6)));
        CaptureCancellation capture = jdbc.query("""
                select c.payment_id, c.cancellation_reason, c.observed_at
                from payment_capture_cancellation_observations c join orders o on o.id = c.order_id
                where c.order_id = :order and o.status = 'SHIPPED' and o.paid_at is null
                order by c.transaction_id desc limit 1
                """, params, (rs, n) -> new CaptureCancellation(rs.getLong(1), rs.getString(2),
                        rs.getObject(3, LocalDateTime.class))).stream().findFirst().orElse(null);
        ExpiredCancellation expired = jdbc.query("""
                select x.payment_id, x.authorization_expires_at, x.observed_at
                from payment_expired_cancellation_observations x join payments p on p.id = x.payment_id
                join orders o on o.id = p.order_id
                where p.order_id = :order and o.status = 'CANCELLED'
                order by x.transaction_id desc limit 1
                """, params, (rs, n) -> new ExpiredCancellation(rs.getLong(1), rs.getObject(2, LocalDateTime.class),
                        rs.getObject(3, LocalDateTime.class))).stream().findFirst().orElse(null);
        return new OrderExpiry(payments, discrepancies, capture, expired);
    }

    static Badge badge(LocalDateTime expiresAt, boolean estimated, LocalDateTime now) {
        long hours = Math.max(0, Duration.between(now, expiresAt).toHours());
        Level level = !expiresAt.isAfter(now) ? Level.EXPIRED
                : !expiresAt.isAfter(now.plus(MARGIN)) ? Level.SOON : Level.VALID;
        return new Badge(level, expiresAt, estimated, hours);
    }

    private LocalDateTime now() {
        return jdbc.getJdbcTemplate().queryForObject("select timezone('Asia/Tokyo', clock_timestamp())", LocalDateTime.class);
    }
}
