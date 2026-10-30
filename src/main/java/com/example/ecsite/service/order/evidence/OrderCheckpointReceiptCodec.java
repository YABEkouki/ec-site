package com.example.ecsite.service.order.evidence;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** V1 local assessment + allowlisted GET receipt. Stored bytes never recreate a live transport token. */
public final class OrderCheckpointReceiptCodec {
    public record Receipt(int version,long orderId,long paymentId,long authorizationId,int revision,String contentFingerprint,
            String localAssessment,String localAssessmentDigest,String externalObservation,String externalObservationDigest) {
        @Override public String toString(){return "OrderCheckpointReceipt[redacted]";}
    }
    private final JsonMapper mapper=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(OrderContentSnapshotCodec.MAX_BYTES).maxStringLength(OrderContentSnapshotCodec.MAX_BYTES)
            .maxNestingDepth(32).maxTokenCount(200000).build()).build())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public byte[] encode(Receipt r) {
        if(r==null||r.version()!=1||r.orderId()<=0||r.paymentId()<=0||r.authorizationId()<=0||r.revision()<0
            ||!OrderEvidenceDigests.validHash(r.contentFingerprint())||r.localAssessment()==null||r.externalObservation()==null
            ||!OrderEvidenceDigests.matches(hash(r.localAssessment()),r.localAssessmentDigest())
            ||!OrderEvidenceDigests.matches(hash(r.externalObservation()),r.externalObservationDigest()))throw invalid();
        byte[] b=mapper.writeValueAsBytes(r);if(b.length>OrderContentSnapshotCodec.MAX_BYTES)throw invalid();return b;
    }
    public Receipt decode(byte[] bytes) {
        try {if(bytes==null||bytes.length>OrderContentSnapshotCodec.MAX_BYTES)throw invalid();var r=mapper.readValue(bytes,Receipt.class);
            if(!Arrays.equals(bytes,encode(r)))throw invalid();return r;
        }catch(RuntimeException e){throw invalid();}
    }
    /** Checks persisted observation binding, never asserts that it is fresh now. */
    void validateStored(Receipt r,tools.jackson.databind.JsonNode epoch,OrderContentSnapshot start) {
        try {
            var local=mapper.readTree(r.localAssessment());var orders=local.get("orders");
            if(orders==null||!orders.isArray()||orders.size()!=1)throw invalid();
            var order=orders.get(0);var factory=new OrderContentSnapshotFactory();
            var items=new java.util.ArrayList<tools.jackson.databind.JsonNode>();local.get("items").forEach(items::add);
            var charges=new java.util.ArrayList<tools.jackson.databind.JsonNode>();local.get("charges").forEach(charges::add);
            var saved=factory.fromRows(order,items,charges);
            if(!new OrderContentSnapshotCodec().fingerprint(saved).equals(new OrderContentSnapshotCodec().fingerprint(start))
                ||!java.util.Objects.equals(OrderContentSnapshotFactory.nullableLong(order,"active_payment_id"),r.paymentId())
                ||OrderContentSnapshotFactory.uuid(order,"current_evidence_epoch_id")!=null)throw invalid();
            var obs=mapper.readTree(r.externalObservation());
            if(OrderContentSnapshotFactory.i(obs,"version")!=1||!"requires_capture".equals(OrderContentSnapshotFactory.t(obs,"status"))
                ||!"jpy".equals(OrderContentSnapshotFactory.t(obs,"currency"))||!"manual".equals(OrderContentSnapshotFactory.t(obs,"captureMethod"))
                ||!OrderContentSnapshotFactory.t(obs,"flowId").equals(OrderContentSnapshotFactory.t(epoch,"provider_flow_id"))
                ||OrderContentSnapshotFactory.i(obs,"amount")!=OrderContentSnapshotFactory.i(epoch,"authorized_amount")
                ||OrderContentSnapshotFactory.i(obs,"amountCapturable")!=OrderContentSnapshotFactory.i(epoch,"authorized_amount")
                ||OrderContentSnapshotFactory.i(obs,"amountReceived")!=0)throw invalid();
            var c=mapper.treeToValue(obs.get("connection"),com.example.ecsite.payment.capture.CaptureConnectionIdentity.class);
            var live=obs.get("liveMode");if(live==null||!live.isBoolean()||live.booleanValue()!=c.mode().equals("LIVE"))throw invalid();
            var auths=local.get("transactions");tools.jackson.databind.JsonNode authorization=null;
            for(var a:auths)if(OrderContentSnapshotFactory.l(a,"id")==r.authorizationId()) {if(authorization!=null)throw invalid();authorization=a;}
            if(authorization==null||!java.util.Objects.equals(OrderContentSnapshotFactory.nullableText(obs,"authorizationOperationId"),OrderContentSnapshotFactory.nullableText(authorization,"order_change_operation_id")))throw invalid();
            var a=java.time.Instant.parse(OrderContentSnapshotFactory.t(obs,"startedAt"));
            var b=java.time.Instant.parse(OrderContentSnapshotFactory.t(obs,"finishedAt"));
            String expiry=OrderContentSnapshotFactory.nullableText(obs,"expiredAt");
            if(expiry!=null&&!java.time.Instant.parse(expiry).isAfter(b))throw invalid();
            if(b.isBefore(a)||java.time.Duration.between(a,b).compareTo(java.time.Duration.ofSeconds(5))>0
                ||!a.equals(java.time.OffsetDateTime.parse(OrderContentSnapshotFactory.t(epoch,"observation_started_at")).toInstant())
                ||!b.equals(java.time.OffsetDateTime.parse(OrderContentSnapshotFactory.t(epoch,"observation_finished_at")).toInstant()))throw invalid();
        }catch(RuntimeException e){throw invalid();}
    }
    public OrderPaymentEvidence.Checkpoint checkpoint(Receipt r) {return new OrderPaymentEvidence.Checkpoint(r.orderId(),r.paymentId(),r.authorizationId(),r.revision(),r.contentFingerprint(),r.localAssessmentDigest(),r.externalObservationDigest());}
    public static String hash(String s){return OrderEvidenceDigests.sha256(s.getBytes(StandardCharsets.UTF_8));}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid checkpoint receipt");}
}
