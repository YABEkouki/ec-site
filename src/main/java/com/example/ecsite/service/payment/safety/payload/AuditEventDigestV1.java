package com.example.ecsite.service.payment.safety.payload;

import java.time.Instant;
import java.util.*;

/** All V56 event business columns, including FK references and occurredAt. Own id, event_uuid,
 * record_digest and DB recorded_at are deliberately absent. UUID is the retry address, not content.
 * occurredAt must be microsecond-exact: PostgreSQL must not round a value after it was hashed. */
public final class AuditEventDigestV1 {
    private AuditEventDigestV1() {}
    public record Record(String eventScope,String eventType,UUID runId,Long caseId,Long orderId,
            Long targetOrderId,Integer attemptNo,Long leaseEpoch,Long assessmentId,Long caseVersionBefore,
            Long caseVersionAfter,String fromStatus,String toStatus,String outcome,String actorType,
            Long actorAccountId,String actorUsername,Instant occurredAt,String comment,String errorStage,
            String errorCode,String sqlState,String errorSummary,short payloadFormatVersion,String payloadJson) {
        public Record {
            Objects.requireNonNull(runId);Objects.requireNonNull(occurredAt);
            if(payloadFormatVersion!=1 || occurredAt.getNano()%1000!=0)throw PayloadEncodingV1.invalid();
            // Fail before SQL; parser rejects duplicate keys/trailing content and never exposes source text.
            var payload=new PayloadEncodingV1(PaymentSafetyPayloadCodecV1.Limits.event()).parse(payloadJson);
            if(!payload.isObject() || !payload.has("version") || !payload.get("version").isIntegralNumber()
                || !payload.get("version").canConvertToInt() || payload.get("version").intValue()!=1)throw PayloadEncodingV1.invalid();
        }
        @Override public String toString(){return "AuditEventRecord[redacted]";}
    }
    public static String sha256(Record event) {
        Objects.requireNonNull(event);
        var engine=new PayloadEncodingV1(PaymentSafetyPayloadCodecV1.Limits.event());
        // JSON object order and whitespace are not business content; array order and scalar types are.
        return engine.eventDigest(event);
    }
    /** Compare business fields as well as the independently verified digest. JSONB key order and
     * whitespace are presentation; array order, duplicates, scalar values and NULL remain content. */
    public static boolean sameContent(Record left,Record right) {
        Objects.requireNonNull(left);Objects.requireNonNull(right);
        var engine=new PayloadEncodingV1(PaymentSafetyPayloadCodecV1.Limits.event());
        var a=(tools.jackson.databind.node.ObjectNode)engine.encode(left,Record.class);
        var b=(tools.jackson.databind.node.ObjectNode)engine.encode(right,Record.class);
        a.set("payloadJson",engine.parse(left.payloadJson()));
        b.set("payloadJson",engine.parse(right.payloadJson()));
        return a.equals(b);
    }
    public static void verify(Record event,String expected){PayloadEncodingV1.verifyDigest(sha256(event),expected);}
}
