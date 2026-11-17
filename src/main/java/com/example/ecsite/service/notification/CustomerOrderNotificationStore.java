package com.example.ecsite.service.notification;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.config.CustomerOrderNotificationProperties;
import com.example.ecsite.service.notification.CustomerOrderNotificationMailClient.Outcome;
import com.example.ecsite.service.notification.CustomerOrderNotificationMailClient.Result;
import com.example.ecsite.service.warehouse.OrderShipmentTrackingReader;

import tools.jackson.databind.ObjectMapper;

/**
 * Feature133-B: short independent transactions over the V91 outbox. Never calls SMTP. Every time comes from the
 * database clock; the V91 guard re-checks each state transition, the per-order order and the retry budget.
 */
@Service
@ConditionalOnProperty(prefix = "app.customer-notification", name = "enabled", havingValue = "true")
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class CustomerOrderNotificationStore {

    public record Claim(long id, UUID token) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RELEASE = "claim_token=null,claimed_by=null,lease_until=null,version=version+1";

    private final JdbcTemplate jdbc;
    private final CustomerOrderNotificationProperties properties;
    private final CustomerOrderNotificationRenderer renderer;
    private final OrderShipmentTrackingReader tracking;

    public CustomerOrderNotificationStore(JdbcTemplate jdbc, CustomerOrderNotificationProperties properties,
            CustomerOrderNotificationRenderer renderer, OrderShipmentTrackingReader tracking) {
        this.jdbc = jdbc; this.properties = properties; this.renderer = renderer; this.tracking = tracking;
    }

    /**
     * A claim whose lease ran out goes back to the queue. A send whose lease ran out may already have reached the
     * customer, so it is closed as uncertain and never resent automatically.
     */
    public int recoverExpired() {
        var expired = jdbc.queryForList("""
            select id,status,claim_token from customer_order_notifications
            where status in ('CLAIMED','SENDING') and lease_until < clock_timestamp()
            order by id for update skip locked
            """);
        for (var row : expired) {
            long id = ((Number) row.get("id")).longValue();
            if ("CLAIMED".equals(row.get("status"))) {
                jdbc.update("update customer_order_notifications set status=case when attempt_count=0 then 'PENDING' else 'RETRY_WAIT' end,"
                        + "next_attempt_at=clock_timestamp()," + RELEASE + " where id=?", id);
            } else {
                finishAttempt(id, (UUID) row.get("claim_token"), Result.UNKNOWN, "CLAIM_LOST");
                jdbc.update("update customer_order_notifications set status='EXHAUSTED',close_reason='DELIVERY_UNCERTAIN',"
                        + "delivery_uncertain=true," + RELEASE + " where id=?", id);
            }
        }
        return expired.size();
    }

    /** The oldest due notification whose order has no earlier open notification (per-order FIFO). */
    public Optional<Claim> claim(UUID owner) {
        var ids = jdbc.queryForList("""
            select n.id from customer_order_notifications n
            where n.status in ('PENDING','RETRY_WAIT') and n.next_attempt_at <= clock_timestamp()
              and not exists (select 1 from customer_order_notifications p where p.order_id=n.order_id and p.id<n.id
                              and p.status not in ('SENT','EXHAUSTED','SKIPPED','EXPIRED'))
            order by n.next_attempt_at, n.id limit 1 for update of n skip locked
            """, Long.class);
        if (ids.isEmpty()) return Optional.empty();
        var token = UUID.randomUUID();
        jdbc.update("""
            update customer_order_notifications set status='CLAIMED',next_attempt_at=null,claim_token=?,claimed_by=?,
              lease_until=clock_timestamp()+make_interval(secs=>?),version=version+1 where id=?
            """, token, owner, properties.claimLease().toSeconds(), ids.getFirst());
        return Optional.of(new Claim(ids.getFirst(), token));
    }

    /**
     * Closes stale or undeliverable claims; otherwise renders the mail, counts the attempt and stores the exact
     * recipient and text before any SMTP work may start.
     */
    public Optional<CustomerOrderNotificationMail> prepare(Claim claim) {
        var row = locked(claim, "CLAIMED");
        if (row == null) return Optional.empty();
        if (Boolean.TRUE.equals(row.get("stale"))) {
            jdbc.update("update customer_order_notifications set status='EXPIRED',close_reason='STALE'," + RELEASE + " where id=?", claim.id());
            return Optional.empty();
        }
        String email = (String) row.get("email");
        if (email == null || email.isBlank() || row.get("email_verified_at") == null) {
            jdbc.update("update customer_order_notifications set status='SKIPPED',close_reason='NO_VERIFIED_EMAIL'," + RELEASE
                    + " where id=?", claim.id());
            return Optional.empty();
        }
        String type = (String) row.get("event_type");
        long orderId = ((Number) row.get("order_id")).longValue();
        var shipment = "ORDER_SHIPPED".equals(type) ? tracking.find(orderId) : Optional.<OrderShipmentTrackingReader.Tracking>empty();
        var rendered = renderer.render(type, JSON.readTree((String) row.get("payload")), shipment);
        var mail = new CustomerOrderNotificationMail(email.trim(), rendered.subject(), rendered.body());
        jdbc.update("update customer_order_notifications set status='SENDING',attempt_count=attempt_count+1,"
                + "lease_until=clock_timestamp()+make_interval(secs=>?),version=version+1 where id=?",
                properties.claimLease().toSeconds(), claim.id());
        jdbc.update("""
            insert into customer_order_notification_attempts(notification_id,attempt_no,claim_token,result,recipient,subject,body,started_at)
            select id,attempt_count,claim_token,'IN_PROGRESS',?,?,?,clock_timestamp() from customer_order_notifications where id=?
            """, mail.recipient(), mail.subject(), mail.body(), claim.id());
        return Optional.of(mail);
    }

    /** Records the SMTP outcome. Returns false when the claim was lost meanwhile (the recovery owns it then). */
    public boolean complete(Claim claim, Outcome outcome) {
        var row = locked(claim, "SENDING");
        if (row == null) return false;
        finishAttempt(claim.id(), claim.token(), outcome.result(), outcome.failureCode());
        switch (outcome.result()) {
            case SUCCESS -> jdbc.update("update customer_order_notifications set status='SENT'," + RELEASE + " where id=?", claim.id());
            case UNKNOWN -> jdbc.update("update customer_order_notifications set status='EXHAUSTED',close_reason='DELIVERY_UNCERTAIN',"
                    + "delivery_uncertain=true," + RELEASE + " where id=?", claim.id());
            case FAILURE -> {
                int attempts = ((Number) row.get("attempt_count")).intValue(), max = ((Number) row.get("max_attempts")).intValue();
                if (attempts < max)
                    jdbc.update("update customer_order_notifications set status='RETRY_WAIT',"
                            + "next_attempt_at=clock_timestamp()+make_interval(secs=>?)," + RELEASE + " where id=?",
                            properties.retryDelay().toSeconds(), claim.id());
                else
                    jdbc.update("update customer_order_notifications set status='EXHAUSTED',close_reason='MAX_ATTEMPTS',"
                            + RELEASE + " where id=?", claim.id());
            }
        }
        return true;
    }

    private Map<String, Object> locked(Claim claim, String status) {
        var rows = jdbc.queryForList("""
            select n.id,n.status,n.claim_token,n.event_type,n.order_id,n.payload::text payload,n.attempt_count,n.max_attempts,
                   n.created_at < clock_timestamp()-make_interval(secs=>?) stale,u.email,u.email_verified_at
            from customer_order_notifications n join users u on u.id=n.user_id where n.id=? for update of n
            """, properties.maxAge().toSeconds(), claim.id());
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        return status.equals(row.get("status")) && claim.token().equals(row.get("claim_token")) ? row : null;
    }

    private void finishAttempt(long id, UUID token, Result result, String failureCode) {
        jdbc.update("update customer_order_notification_attempts set result=?,failure_code=?,finished_at=clock_timestamp() "
                + "where notification_id=? and claim_token=? and result='IN_PROGRESS'",
                result.name(), result == Result.SUCCESS ? null : failureCode, id, token);
    }
}
