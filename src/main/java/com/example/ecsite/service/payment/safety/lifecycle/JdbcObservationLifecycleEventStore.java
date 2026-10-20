package com.example.ecsite.service.payment.safety.lifecycle;

import java.sql.*;
import java.time.*;
import java.util.*;
import com.example.ecsite.service.payment.safety.payload.*;

/** Lifecycle-only SQL. Caller must already hold the Run lock; never acquires Order/Payment locks. */
final class JdbcObservationLifecycleEventStore {
    record Stored(UUID uuid,String digest,AuditEventDigestV1.Record record) {}
    Optional<Stored> find(Connection c,UUID uuid) throws SQLException {
        try(var q=c.prepareStatement("select * from payment_safety_audit_events where event_uuid=?")) {
            q.setObject(1,uuid);try(var r=q.executeQuery()) {
                if(!r.next())return Optional.empty();
                var record=new AuditEventDigestV1.Record(r.getString("event_scope"),r.getString("event_type"),r.getObject("run_id",UUID.class),
                    r.getObject("case_id",Long.class),r.getObject("order_id",Long.class),r.getObject("target_order_id",Long.class),r.getObject("attempt_no",Integer.class),r.getObject("lease_epoch",Long.class),
                    r.getObject("assessment_id",Long.class),r.getObject("case_version_before",Long.class),r.getObject("case_version_after",Long.class),r.getString("from_status"),r.getString("to_status"),r.getString("outcome"),
                    r.getString("actor_type"),r.getObject("actor_account_id",Long.class),r.getString("actor_username"),r.getObject("occurred_at",OffsetDateTime.class).toInstant(),
                    r.getString("comment"),r.getString("error_stage"),r.getString("error_code"),r.getString("sql_state"),r.getString("error_summary"),r.getShort("payload_format_version"),r.getString("payload"));
                String digest=r.getString("record_digest");
                try{AuditEventDigestV1.verify(record,digest);}catch(IllegalArgumentException corrupt){throw new JdbcObservationRunRepository.IntegrityFailure();}
                return Optional.of(new Stored(uuid,digest,record));
            }
        }
    }
    boolean matches(Stored stored,ObservationEventFactory.Event expected) {
        return stored.uuid().equals(expected.eventUuid()) && stored.digest().equals(expected.recordDigest())
            && AuditEventDigestV1.sameContent(stored.record(),expected.record());
    }
    void insert(Connection c,ObservationEventFactory.Event event) throws SQLException {
        var r=event.record();
        if(!r.eventScope().equals("RUN") || !Set.of("STARTED","PAUSED","RESUMED","LEASE_TAKEN","FINISHED").contains(r.eventType()))throw new JdbcObservationRunRepository.IntegrityFailure();
        try(var q=c.prepareStatement("insert into payment_safety_audit_events(event_uuid,record_digest,event_scope,event_type,run_id,lease_epoch,actor_type,actor_account_id,actor_username,occurred_at,payload_format_version,payload) values (?,?,'RUN',?,?,?,?,?,?,?,1,?::jsonb)")) {
            q.setObject(1,event.eventUuid());q.setString(2,event.recordDigest());q.setString(3,r.eventType());q.setObject(4,r.runId());q.setObject(5,r.leaseEpoch());
            q.setString(6,r.actorType());q.setObject(7,r.actorAccountId());q.setString(8,r.actorUsername());q.setObject(9,r.occurredAt().atOffset(ZoneOffset.UTC));q.setString(10,r.payloadJson());
            if(q.executeUpdate()!=1)throw new JdbcObservationRunRepository.IntegrityFailure();
        }
    }
}
