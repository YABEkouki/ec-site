package com.example.ecsite.payment.orderchange;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import com.example.ecsite.payment.capture.CaptureConnectionIdentity;
import com.example.ecsite.service.order.evidence.OrderUnappliedClosureCodec;
import static com.example.ecsite.payment.orderchange.ThreeDsFailureEvidence.*;

/** Pure V78 verification. No transport, repository, Spring bean, mutation or execution permit.
 * Provider authentication must be supplied by a future trusted GET transport; calling this
 * class with arbitrary JSON does not attest where the JSON came from. */
public final class ThreeDsFailureEvidenceVerifier {
    private static final Set<String> DOCUMENT_FIELDS=Set.of("amount","amount_capturable","amount_received","api_origin","canceled_at",
        "capture_method","connection_id","currency","flow_id","last_error_code","livemode","metadata","next_action","provider_account_ref","status");
    private static final DateTimeFormatter PG_TIME=new DateTimeFormatterBuilder().appendPattern("uuuu-MM-dd'T'HH:mm:ss")
        .appendFraction(ChronoField.NANO_OF_SECOND,0,6,true).appendLiteral("+00:00").toFormatter(Locale.ROOT).withZone(ZoneOffset.UTC);
    private final JsonMapper json=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(4194304).maxNestingDepth(64).maxNumberLength(1100).maxStringLength(4194304).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build();
    private final OrderUnappliedClosureCodec codec=new OrderUnappliedClosureCodec();

    public Observation failure(Context context,LocalFacts local,CaptureConnectionIdentity transport,String providerJson,Window window,Instant now) {
        try {return observe(context,local,transport,providerJson,window,now,Kind.THREE_DS_FAILURE,null);}
        catch(RuntimeException failure){throw invalid();}
    }
    public Observation terminal(Context context,LocalFacts local,CaptureConnectionIdentity transport,String providerJson,Window window,Instant now,Observation before) {
        try {return observe(context,local,transport,providerJson,window,now,Kind.FLOW_TERMINAL,before);}
        catch(RuntimeException failure){throw invalid();}
    }
    private Observation observe(Context c,LocalFacts local,CaptureConnectionIdentity transport,String raw,Window w,Instant now,Kind kind,Observation before) {
        local(c,local);require(c.ownership().connection().equals(transport));window(w,now);
        var p=read(raw);require(p.isObject());
        var d=json.createObjectNode();
        d.set("flow_id",required(p,"id"));
        for(String k:List.of("amount","currency","capture_method","livemode","metadata","status","amount_received","amount_capturable","canceled_at","next_action"))d.set(k,required(p,k));
        var error=required(p,"last_payment_error");require(error.isObject());d.set("last_error_code",required(error,"code"));
        d.put("connection_id",transport.connectionId());d.put("provider_account_ref",transport.providerAccountRef());d.put("api_origin",transport.apiOrigin());
        document(c,d,kind,w);link(c,kind,w,before);
        return new Observation(c,kind,d.toString(),w,null,null,null);
    }
    private void local(Context c,LocalFacts f) {
        require(c!=null&&f!=null&&c.ownership().equals(f.ownership())&&c.executionVersion()==f.executionVersion()&&MessageDigest.isEqual(c.historyDigest(),f.historyDigest())
            &&"AUTHORIZED".equals(f.oldPaymentStatus())&&Set.of("PENDING","REQUIRES_ACTION").contains(f.newPaymentStatus())
            &&"PENDING".equals(f.authorizationStatus())&&!f.authorizationCompleted()
            &&!f.activeAttempt()&&!f.activeLease()&&!f.switchDecided()&&!f.orderApplied()&&!f.oldCancelRequested());
        for(var r:f.results())require(r!=null&&!((c.ownership().operationId().equals(r.operationId())||r.paymentId()==c.ownership().newPaymentId())
            &&"SUCCEEDED".equals(r.status())&&Set.of("AUTHORIZE","CAPTURE").contains(r.type())));
        var q=f.cancel();
        if(q!=null) {
            var o=c.ownership();require(q.requestId()>0&&q.operationId().equals(o.operationId())&&q.orderId()==o.orderId()
                &&q.paymentId()==o.newPaymentId()&&q.flowId().equals(o.flowId())&&q.amount()==o.amount()
                &&"NEW_ABORT".equals(q.kind())&&q.connection().equals(o.connection()));
        }
        require(c.cancelRequestId()==null||q!=null&&c.cancelRequestId()==q.requestId());
    }
    private void document(Context c,JsonNode d,Kind kind,Window w) {
        require(d.isObject()&&keys(d).equals(DOCUMENT_FIELDS));var o=c.ownership();var connection=o.connection();
        require(text(d,"flow_id").equals(o.flowId())&&integer(d,"amount")==o.amount()
            &&text(d,"currency").equals("jpy")&&text(d,"capture_method").equals("manual")
            &&required(d,"livemode").isBoolean()&&d.get("livemode").booleanValue()==connection.mode().equals("LIVE")
            &&text(d,"connection_id").equals(connection.connectionId())&&text(d,"provider_account_ref").equals(connection.providerAccountRef())
            &&text(d,"api_origin").equals(connection.apiOrigin())&&text(d,"last_error_code").equals("three_d_secure_failed")
            &&required(d,"amount_received").isNull()&&required(d,"amount_capturable").isNull()
            &&required(d,"next_action").isObject()&&d.get("next_action").isEmpty());
        var metadata=required(d,"metadata");require(metadata.isObject()&&keys(metadata).equals(Set.of("order_id","order_change_operation_id"))
            &&text(metadata,"order_id").equals(Long.toString(o.orderId()))&&text(metadata,"order_change_operation_id").equals(o.operationId().toString()));
        if(kind==Kind.THREE_DS_FAILURE)require(c.state()==State.REQUESTED&&text(d,"status").equals("requires_payment_method")&&required(d,"canceled_at").isNull());
        else {
            require(c.state()==State.FAILURE_OBSERVED&&text(d,"status").equals("canceled"));
            String stamp=text(d,"canceled_at");require(stamp.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]{1,6})?(Z|[+-][0-9]{2}:[0-9]{2})"));
            require(!OffsetDateTime.parse(stamp,DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().isAfter(w.finishedAt()));
        }
    }
    private void link(Context c,Kind kind,Window w,Observation before) {
        if(kind==Kind.FLOW_TERMINAL)require(before!=null&&before.kind()==Kind.THREE_DS_FAILURE
            &&c.ownership().equals(before.context().ownership())&&c.version()==before.context().version()+1
            &&MessageDigest.isEqual(c.intentHistoryDigest(),before.context().intentHistoryDigest())
            &&c.executionVersion()==before.context().executionVersion()&&!w.startedAt().isBefore(before.window().finishedAt()));
        else require(before==null);
    }
    /** Verify a committed-row representation without expiring historical evidence at read time.
     * This is not freshness permission for a subsequent financial or business write. */
    public Observation storedObservation(Context c,LocalFacts local,StoredObservation row,Observation before) {
        try {
            local(c,local);require(row!=null&&row.controlId().equals(c.ownership().controlId())&&row.controlVersion()==c.version()
                &&row.evidenceVersion()==1&&MessageDigest.isEqual(row.historyDigest(),c.historyDigest()));
            xid(row.recordingXid());window(row.window(),row.recordedAt());
            var doc=read(row.document());document(c,doc,row.kind(),row.window());link(c,row.kind(),row.window(),before);
            canonical(observationEnvelope(row),row.canonical(),row.digest());
            return new Observation(c,row.kind(),doc.toString(),row.window(),row.id(),row.recordedAt(),row.digest());
        } catch(RuntimeException failure){throw invalid();}
    }
    /** Exact V78 f125_failure_observation_bytes envelope (UTC PostgreSQL timestamp JSON). */
    String observationEnvelope(StoredObservation r) {
        var n=json.createObjectNode();n.put("domain","f125/3ds-failure/observation/v1");n.put("id",r.id().toString());n.put("control",r.controlId().toString());
        n.put("controlVersion",r.controlVersion());n.put("kind",r.kind().name());n.set("document",read(r.document()));
        n.put("started",pg(r.window().startedAt()));n.put("finished",pg(r.window().finishedAt()));n.put("history",HexFormat.of().formatHex(r.historyDigest()));
        n.put("version",r.evidenceVersion());n.put("xid",r.recordingXid());n.put("recorded",pg(r.recordedAt()));return n.toString();
    }
    public ResolutionEvidence storedResolution(Context c,LocalFacts local,UnknownSource source,Observation terminal,StoredResolution row) {
        try {
            local(c,local);require(c.state()==State.TERMINAL_OBSERVED&&source!=null&&!source.conclusiveResultExists()
                &&terminal!=null&&terminal.kind()==Kind.FLOW_TERMINAL&&terminal.id()!=null&&terminal.digest()!=null
                &&c.ownership().equals(terminal.context().ownership())&&c.version()==terminal.context().version()+1
                &&MessageDigest.isEqual(c.intentHistoryDigest(),terminal.context().intentHistoryDigest())
                &&MessageDigest.isEqual(c.historyDigest(),terminal.context().historyDigest())
                &&c.executionVersion()==terminal.context().executionVersion()
                &&(terminal.context().cancelRequestId()==null||Objects.equals(c.cancelRequestId(),terminal.context().cancelRequestId())));
            require(row!=null&&row.controlId().equals(c.ownership().controlId())&&row.unknownResultId().equals(source.resultId())
                &&row.terminalObservationId().equals(terminal.id())&&row.controlVersion()==c.version()
                &&row.evidenceVersion()==1&&"TERMINATED_UNAPPLIED".equals(row.disposition()));
            xid(row.recordingXid());require(terminal.recordedAt()!=null&&!row.recordedAt().isBefore(terminal.recordedAt()));pg(row.recordedAt());
            String expected=resolutionDocument(c,source,terminal,row);
            require(read(row.document()).equals(read(expected)));canonical(expected,row.canonical(),row.digest());
            return new ResolutionEvidence(row);
        } catch(RuntimeException failure){throw invalid();}
    }
    String resolutionDocument(Context c,UnknownSource source,Observation terminal,StoredResolution row) {
        var a=read(source.attemptAudit());var u=read(source.resultAudit());var o=c.ownership();
        require(a.isObject()&&u.isObject()&&text(a,"id").equals(source.attemptId().toString())&&text(u,"id").equals(source.resultId().toString())
            &&text(u,"attempt_id").equals(source.attemptId().toString())&&text(a,"admission_id").equals(o.admissionId().toString())
            &&text(u,"admission_id").equals(o.admissionId().toString())&&integer(a,"order_id")==o.orderId()&&integer(u,"order_id")==o.orderId()
            &&text(a,"db_generation_id").equals(o.generationId().toString())&&text(u,"disposition").equals("UNKNOWN")
            &&integer(a,"fencing_token")>0&&integer(a,"fencing_token")==integer(u,"fencing_token")
            &&integer(a,"control_version")>0&&integer(u,"control_version")>=integer(a,"control_version")
            &&integer(u,"control_version")<c.executionVersion());
        var n=json.createObjectNode();n.put("domain","f125/3ds-failure/resolution/v1");n.put("control",o.controlId().toString());n.put("controlVersion",c.version());
        n.put("admission",o.admissionId().toString());n.put("operation",o.operationId().toString());n.put("generation",o.generationId().toString());
        n.put("oldPayment",o.oldPaymentId());n.put("newPayment",o.newPaymentId());n.put("authorization",o.authorizationId());n.put("flow",o.flowId());
        if(c.cancelRequestId()==null)n.putNull("cancelRequest");else n.put("cancelRequest",c.cancelRequestId());
        n.set("attempt",a);n.set("unknown",u);n.put("terminal",terminal.id().toString());n.put("terminalDigest",HexFormat.of().formatHex(terminal.digest()));
        n.put("disposition",row.disposition());n.put("version",row.evidenceVersion());n.put("xid",row.recordingXid());n.put("recorded",pg(row.recordedAt()));return n.toString();
    }
    private JsonNode read(String text){require(text!=null);codec.canonicalValue(text);return json.readTree(text);}
    private static JsonNode required(JsonNode n,String field){var value=n.get(field);require(value!=null);return value;}
    private static String text(JsonNode n,String field){var v=required(n,field);require(v.isString());return v.stringValue();}
    private static long integer(JsonNode n,String field){var v=required(n,field);require(v.isIntegralNumber()&&v.canConvertToLong());return v.longValue();}
    private static Set<String> keys(JsonNode n){var keys=new HashSet<String>();n.propertyNames().forEach(keys::add);return keys;}
    private static String pg(Instant i){require(i!=null&&i.getNano()%1000==0);String s=PG_TIME.format(i);require(s.matches("[0-9]{4}-.+"));return s;}
    private static void window(Window w,Instant at){require(w!=null&&at!=null&&!w.finishedAt().isAfter(at)
        &&Duration.between(w.startedAt(),at).compareTo(Duration.ofSeconds(5))<=0);}
    private static void xid(String value){require(value!=null&&value.matches("[0-9]{1,20}")&&new BigInteger(value).signum()>0
        &&new BigInteger(value).compareTo(new BigInteger("18446744073709551615"))<=0);}
    private void canonical(String document,byte[] bytes,byte[] digest){require(bytes!=null&&digest!=null&&digest.length==32
        &&Arrays.equals(codec.canonicalValue(document),bytes)&&MessageDigest.isEqual(codec.sha256(bytes),digest));}
}
