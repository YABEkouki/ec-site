package com.example.ecsite.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Fixtures for isolated Testcontainers databases; never invokes observation or delivery. */
public final class PaymentAuditNotificationHistoryFixtures {
    public static final Instant NOW = Instant.parse("2026-10-09T01:00:00Z");
    private final JdbcTemplate jdbc;
    public PaymentAuditNotificationHistoryFixtures(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void clear() {
        jdbc.update("delete from payment_audit_notification_attempts");
        jdbc.update("delete from payment_audit_notification_items");
        jdbc.update("delete from payment_audit_notifications");
    }
    public long notification(String status, Instant created) {
        int count = switch (status) { case "SENDING", "SENT", "RETRY_WAIT" -> 1; case "EXHAUSTED" -> 3; default -> 0; };
        return notification(status, created, count);
    }
    public long notification(String status, Instant created, int count) {
        boolean claimed = status.equals("CLAIMED") || status.equals("SENDING");
        boolean due = status.equals("PENDING") || status.equals("RETRY_WAIT");
        boolean closed = status.equals("SENT") || status.equals("EXHAUSTED") || status.equals("CANCELLED");
        return jdbc.queryForObject("""
            insert into payment_audit_notifications(status,evaluated_at,from_address,recipients,recipient_set_hash,
                admin_url,attempt_count,max_attempts,retry_delay_ms,next_attempt_at,claim_token,claimed_by,lease_until,
                created_at,updated_at,sent_at,closed_at,close_reason,delivery_uncertain)
            values(?,?,'hidden-from@example.com',ARRAY['hidden-first@example.com','hidden-second@example.com'],repeat('a',64),
                'http://localhost:8080/admin/payment-discrepancy-audits',?,3,300000,?,?,?,?,?,?,?,?,?,false) returning id
            """, Long.class, status, Timestamp.from(created.minusSeconds(1)), count,
            due ? Timestamp.from(created.plusSeconds(300)) : null,
            claimed ? UUID.randomUUID() : null, claimed ? UUID.randomUUID() : null,
            claimed ? Timestamp.from(created.plusSeconds(180)) : null,
            Timestamp.from(created), Timestamp.from(created.plusSeconds(10)),
            status.equals("SENT") ? Timestamp.from(created.plusSeconds(10)) : null,
            closed ? Timestamp.from(created.plusSeconds(10)) : null,
            status.equals("EXHAUSTED") ? "MAX_ATTEMPTS" : status.equals("CANCELLED") ? "RESOLVED" : null);
    }
    public void item(long id, String type, boolean removed) {
        jdbc.update("""
            insert into payment_audit_notification_items(notification_id,warning_type,episode_no,item_status,
                first_observed_at,related_at,warning_count,error_code,resolved_at,removed_at,created_at)
            values(?,?,?,?,?,?,2,'ITEM_FAILURE',?,?,?)
            """, id, type, id, removed ? "REMOVED_RESOLVED" : "INCLUDED", Timestamp.from(NOW.minusSeconds(60)),
            Timestamp.from(NOW.minusSeconds(120)), removed ? Timestamp.from(NOW) : null,
            removed ? Timestamp.from(NOW.plusSeconds(1)) : null, Timestamp.from(NOW));
    }
    public void attempt(long id, int number, String result) {
        jdbc.update("""
            insert into payment_audit_notification_attempts(notification_id,attempt_no,claim_token,result,started_at,
                finished_at,failure_code,subject,body,recipients)
            values(?,?,?,?,?,?,?,'hidden-subject','hidden-body',ARRAY['hidden-actual@example.com'])
            """, id, number, UUID.randomUUID(), result, Timestamp.from(NOW.plusSeconds(number)),
            result.equals("IN_PROGRESS") ? null : Timestamp.from(NOW.plusSeconds(number + 1)),
            switch (result) { case "UNKNOWN" -> "SEND_DEADLINE_EXCEEDED"; case "FAILURE" -> "SMTP_SEND_FAILED"; default -> null; });
    }
}
