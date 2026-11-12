package com.example.ecsite.service.order.history;

import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.example.ecsite.entity.PaymentTransactionPurpose;
import com.example.ecsite.payment.capture.CaptureConnectionIdentity;
import com.example.ecsite.payment.orderchange.OrderChangeHttpRequest;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceBundle.*;
import static com.example.ecsite.service.order.history.OrderChangeHistoryVerificationResult.*;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceChecks.*;

/** Checks saved observations and immutable request bodies. Does not restore any live permission. */
final class OrderChangeFinancialEvidenceVerifier {
    private final OrderChangeEvidenceIndex index;
    private final JsonMapper json=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(2097152).maxStringLength(2097152).maxNestingDepth(24).build()).build()).build();
    OrderChangeFinancialEvidenceVerifier(OrderChangeEvidenceIndex index){this.index=index;}
    void verify(EvidenceRow a,EvidenceRow op,EvidenceRow base,EvidenceRow afterEpoch){
        var original=parse(bytes(base,"observation_payload"));
        if("LEGACY_CURRENT_CHECKPOINT".equals(text(base,"origin_kind")))original=parse(new com.example.ecsite.service.order.evidence.OrderCheckpointReceiptCodec().decode(bytes(base,"observation_payload")).externalObservation().getBytes(StandardCharsets.UTF_8));
        digest(bytes(op,"admission_observation_payload"),bytes(op,"admission_observation_fingerprint"),Reason.FINANCIAL_EVIDENCE);
        var oldAuth=index.one(EvidenceSet.TRANSACTIONS,"id",text(a,"base_authorization_id"));
        observation(bytes(op,"admission_observation_payload"),text(base,"provider_flow_id"),integer(a,"authorized_amount"),nullable(oldAuth,"order_change_operation_id"),original);
        if(afterEpoch==null){
            check(index.matching(EvidenceSet.FINANCIAL_REQUESTS,"operation_id",text(op,"id")).isEmpty(),Reason.FINANCIAL_EVIDENCE);return;
        }
        var current=observation(bytes(afterEpoch,"observation_payload"),text(afterEpoch,"provider_flow_id"),integer(a,"after_amount"),text(op,"id"),original);
        equal(time(current,"startedAt"),instant(afterEpoch,"observation_started_at"),Reason.FINANCIAL_EVIDENCE);equal(time(current,"finishedAt"),instant(afterEpoch,"observation_finished_at"),Reason.FINANCIAL_EVIDENCE);
        var requests=index.matching(EvidenceSet.FINANCIAL_REQUESTS,"operation_id",text(op,"id"));check(requests.size()==2,Reason.FINANCIAL_EVIDENCE);
        for(String kind:List.of("CREATE","OLD_CANCEL")){
            var rs=requests.stream().filter(r->kind.equals(r.value("request_kind"))).toList();check(rs.size()==1,Reason.FINANCIAL_EVIDENCE);var r=rs.getFirst();
            var tx=index.one(EvidenceSet.TRANSACTIONS,"id",text(r,"transaction_id"));
            var connection=new CaptureConnectionIdentity(text(r,"connection_id"),text(r,"provider_account_ref"),text(r,"mode"),text(r,"api_origin"));
            var saved=OrderChangeHttpRequest.restore(connection,uuid(op,"id"),number(a,"order_id"),integer(r,"amount"),text(r,"idempotency_key"),PaymentTransactionPurpose.valueOf(text(r,"purpose")),nullable(r,"provider_flow_id"),text(r,"http_method"),text(r,"api_path"),text(r,"content_type"),integer(r,"body_format_version"),bytes(r,"request_body"),integer(r,"fingerprint_version"),bytes(r,"request_fingerprint"));
            equal(saved.kind(),kind,Reason.FINANCIAL_EVIDENCE);equal(saved.transactionType(),text(r,"transaction_type"),Reason.FINANCIAL_EVIDENCE);
            equal(text(tx,"order_change_operation_id"),text(op,"id"),Reason.FINANCIAL_EVIDENCE);equal(number(tx,"order_change_order_id"),number(a,"order_id"),Reason.OWNERSHIP_MISMATCH);
            for(var k:List.of("payment_id","amount","purpose","idempotency_key","transaction_type"))equal(text(tx,k),text(r,k),Reason.FINANCIAL_EVIDENCE);
            equal(text(tx,"status"),"SUCCEEDED",Reason.FINANCIAL_EVIDENCE);text(tx,"completed_at");
            equal(text(r,"payment_id"),kind.equals("CREATE")?text(op,"new_payment_id"):text(op,"old_payment_id"),Reason.FINANCIAL_EVIDENCE);
            equal(integer(r,"amount"),kind.equals("CREATE")?integer(a,"after_amount"):integer(a,"authorized_amount"),Reason.FINANCIAL_EVIDENCE);
            if(kind.equals("CREATE"))equal(text(tx,"id"),text(afterEpoch,"authorization_transaction_id"),Reason.FINANCIAL_EVIDENCE);
            else equal(text(r,"provider_flow_id"),text(base,"provider_flow_id"),Reason.FINANCIAL_EVIDENCE);
            var dispatch=index.one(EvidenceSet.DISPATCHES,"request_id",text(r,"id"));equal(text(dispatch,"state"),"OBSERVED",Reason.FINANCIAL_EVIDENCE);
            var connectionNode=current.get("connection");equal(connectionNode,json.valueToTree(connection),Reason.FINANCIAL_EVIDENCE);
        }
        check(index.matching(EvidenceSet.TRANSACTIONS,"order_change_operation_id",text(op,"id")).size()==2,Reason.FINANCIAL_EVIDENCE);
    }
    private JsonNode observation(byte[] raw,String flow,int amount,String operation,JsonNode original){
        var j=parse(raw);check(j.isObject(),Reason.FINANCIAL_EVIDENCE);
        numeric(j,"version",1);numeric(j,"amount",amount);numeric(j,"amountCapturable",amount);numeric(j,"amountReceived",0);
        string(j,"flowId",flow);string(j,"status","requires_capture");string(j,"currency","jpy");string(j,"captureMethod","manual");
        var c=j.get("connection");check(c!=null&&c.isObject(),Reason.FINANCIAL_EVIDENCE);
        var connection=new CaptureConnectionIdentity(string(c,"connectionId"),string(c,"providerAccountRef"),string(c,"mode"),string(c,"apiOrigin"));
        var live=j.get("liveMode");check(live!=null&&live.isBoolean()&&live.booleanValue()==connection.mode().equals("LIVE"),Reason.FINANCIAL_EVIDENCE);
        var op=j.get("authorizationOperationId");check(op!=null,Reason.FINANCIAL_EVIDENCE);equal(op.isNull()?null:op.asString(),operation,Reason.FINANCIAL_EVIDENCE);
        if(original.has("connection")&&original.get("connection").isObject())equal(c,original.get("connection"),Reason.FINANCIAL_EVIDENCE);
        if(original.has("liveMode")&&original.get("liveMode").isBoolean())equal(live,original.get("liveMode"),Reason.FINANCIAL_EVIDENCE);
        var start=time(j,"startedAt");var finish=time(j,"finishedAt");check(!finish.isBefore(start)&&Duration.between(start,finish).compareTo(Duration.ofSeconds(5))<=0,Reason.FINANCIAL_EVIDENCE);
        var expiry=j.get("expiredAt");check(expiry!=null,Reason.FINANCIAL_EVIDENCE);if(!expiry.isNull())check(Instant.parse(expiry.asString()).isAfter(finish),Reason.FINANCIAL_EVIDENCE);
        return j;
    }
    private JsonNode parse(byte[] raw){check(raw.length>0&&raw.length<=2097152,Reason.FINANCIAL_EVIDENCE);return json.readTree(raw);}
    private static String string(JsonNode n,String k){var v=n.get(k);check(v!=null&&v.isString(),Reason.FINANCIAL_EVIDENCE);return v.asString();}
    private static void string(JsonNode n,String k,String expected){equal(string(n,k),expected,Reason.FINANCIAL_EVIDENCE);}
    private static void numeric(JsonNode n,String k,long expected){var v=n.get(k);check(v!=null&&v.isIntegralNumber()&&v.canConvertToLong()&&v.longValue()==expected,Reason.FINANCIAL_EVIDENCE);}
    private static Instant time(JsonNode n,String k){return OffsetDateTime.parse(string(n,k)).toInstant();}
}
