package com.example.ecsite.service.notification;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feature133-C: accepts an administrator's manual resend request. The request row is the audit record; the V92
 * trigger re-validates it under the original's row lock and records the copy that the worker then delivers.
 */
@Service
public class AdminCustomerNotificationResendService {

    public enum Result { ACCEPTED, ALREADY_ACCEPTED, NOT_FOUND, STALE, NOT_ALLOWED, RISK_NOT_ACKNOWLEDGED }

    private final JdbcTemplate jdbc;
    private final AdminCustomerNotificationQueries queries;

    public AdminCustomerNotificationResendService(JdbcTemplate jdbc, AdminCustomerNotificationQueries queries) {
        this.jdbc = jdbc; this.queries = queries;
    }

    /**
     * @param expectedResendCount the number of resends the administrator saw when confirming; a different current
     *        count means someone else acted meanwhile, so nothing is recorded.
     */
    @Transactional
    public Result request(long originalId, long adminId, String adminUsername, UUID nonce, String reason,
            boolean riskAcknowledged, int expectedResendCount) {
        if (nonce == null || reason == null || reason.isBlank() || reason.length() > 500)
            throw new IllegalArgumentException("A nonce and a reason of at most 500 characters are required");
        // Lock the original first (the V92 trigger locks it again), so the checks below see a settled chain.
        var locked = jdbc.queryForList("select id from customer_order_notifications where id=? and resend_of is null for update",
                Long.class, originalId);
        if (locked.isEmpty()) return Result.NOT_FOUND;
        Long earlier = jdbc.query("select notification_id from customer_order_notification_resends where request_nonce=?",
                rs -> rs.next() ? rs.getLong(1) : null, nonce);
        if (earlier != null) return earlier == originalId ? Result.ALREADY_ACCEPTED : Result.STALE;
        var chain = queries.detail(originalId).orElseThrow().chain();
        var eligibility = AdminCustomerNotificationQueries.eligibility(chain);
        if (eligibility.resendCount() != expectedResendCount) return Result.STALE;
        if (!eligibility.allowed()) return Result.NOT_ALLOWED;
        if (eligibility.riskAcknowledgementRequired() && !riskAcknowledged) return Result.RISK_NOT_ACKNOWLEDGED;
        jdbc.update("""
            insert into customer_order_notification_resends(notification_id,request_no,request_nonce,actor_admin_id,actor_username,
              reason,risk_acknowledged,requested_at) values (?,?,?,?,?,?,?,clock_timestamp())
            """, originalId, expectedResendCount + 1, nonce, adminId, adminUsername, reason.strip(), riskAcknowledged);
        return Result.ACCEPTED;
    }
}
