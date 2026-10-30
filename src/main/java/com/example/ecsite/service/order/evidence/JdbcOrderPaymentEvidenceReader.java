package com.example.ecsite.service.order.evidence;

import static com.example.ecsite.service.order.evidence.OrderContentSnapshotFactory.*;
import static com.example.ecsite.service.order.evidence.OrderPaymentEvidence.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Standalone; not a Spring bean. Caller owns a consistent transaction. Read-only RR for
 * candidates, or Order -> operation -> ascending Payment -> transaction locks at RC for finalization.
 * Consistency relies on existing writers taking Order first; arbitrary SQL writers
 * ignoring that protocol are not made serializable by this reader.
 * No missing-value repair. Invalid/partial data never yields a usable Facts value. */
public final class JdbcOrderPaymentEvidenceReader {
    public static final int MAX_READ_BYTES=16*1024*1024;
    public enum State { READ, NOT_FOUND, INVALID, CONFLICT }
    public record Read(State state,Facts facts,OrderPaymentEvidenceDecision decision,String localAssessment,String localFingerprint,
            JsonNode order,byte[] storedObservation) {
        public Read {storedObservation=storedObservation==null?null:storedObservation.clone();}
        @Override public byte[] storedObservation(){return storedObservation==null?null:storedObservation.clone();}
        @Override public String toString(){return "OrderEvidenceRead["+state+"]";}
    }
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper=JsonMapper.builder().build();
    private final OrderContentSnapshotCodec codec=new OrderContentSnapshotCodec();
    public JdbcOrderPaymentEvidenceReader(JdbcTemplate jdbc){this.jdbc=Objects.requireNonNull(jdbc);}
    public Read read(long orderId) {
        if(!ownsTransaction()||!"repeatable read".equals(jdbc.queryForObject("show transaction_isolation",String.class)))return failure(State.CONFLICT);
        return readData(orderId);
    }
    public Read readLocked(long orderId) {lock(orderId);return readData(orderId);}
    private Read readData(long orderId) {
        try {
            var g=groups(orderId);var orders=g.get("orders");if(orders.isEmpty())return failure(State.NOT_FOUND);
            var o=orders.getFirst();var current=new OrderContentSnapshotFactory().fromRows(o,g.get("items"),g.get("charges"));
            var payments=g.get("payments").stream().map(p->new Payment(l(p,"id"),l(p,"order_id"),t(p,"provider"),t(p,"payment_method"),t(p,"status"),i(p,"amount"),nullableText(p,"provider_payment_id"))).toList();
            for(var p:payments)if(p.orderId()!=orderId)throw invalid();
            var auths=new ArrayList<Authorization>();boolean pending=false;
            for(var a:g.get("transactions")) {
                if(payments.stream().noneMatch(p->p.id()==l(a,"payment_id")))throw invalid();
                String status=t(a,"status"),type=t(a,"transaction_type");
                if(!Set.of("PENDING","SUCCEEDED","FAILED").contains(status)||!Set.of("AUTHORIZE","CAPTURE","CANCEL").contains(type))throw invalid();
                pending|="PENDING".equals(status);
                pending|=!"AUTHORIZE".equals(type)&&Objects.equals(nullableLong(o,"active_payment_id"),l(a,"payment_id"));
                if("SUCCEEDED".equals(status)&&nullableText(a,"completed_at")==null)throw invalid();
                if("AUTHORIZE".equals(type))auths.add(new Authorization(l(a,"id"),orderId,l(a,"payment_id"),type,status,i(a,"amount"),i(a,"order_content_revision"),nullableText(a,"provider_transaction_id"),nullableText(a,"purpose"),uuid(a,"order_change_operation_id")));
            }
            boolean unresolved=g.get("operations").stream().anyMatch(c->!Set.of("COMPLETED","ABORTED").contains(t(c,"state")));
            UUID epochId=uuid(o,"current_evidence_epoch_id");Epoch epoch=null;byte[] observation=null;
            var changes=new ArrayList<Change>();
            if(epochId!=null) {
                var e=unique(g.get("epochs"),r->epochId.equals(uuid(r,"id")));
                var start=codec.decode(bytes(e,"start_snapshot"));if(!hash(e,"start_fingerprint").equals(codec.fingerprint(start)))throw invalid();
                observation=bytes(e,"observation_payload");if(!hash(e,"observation_fingerprint").equals(OrderEvidenceDigests.sha256(observation)))throw invalid();
                var origin=Origin.valueOf(t(e,"origin_kind"));Reauthorization reauth=null;Checkpoint checkpoint=null;
                var a=unique(auths,x->x.id()==l(e,"authorization_transaction_id"));
                if(a.operationId()!=null)reauth=reauthorization(g,a,origin==Origin.REAUTH_COMPLETED?nullableLong(e,"origin_history_id"):null);
                if(origin==Origin.LEGACY_CURRENT_CHECKPOINT) {
                    var receipts=new OrderCheckpointReceiptCodec();var r=receipts.decode(observation);receipts.validateStored(r,e,start);checkpoint=receipts.checkpoint(r);
                    if(r.orderId()!=orderId||r.paymentId()!=l(e,"payment_id")||r.authorizationId()!=l(e,"authorization_transaction_id")
                        ||r.revision()!=start.contentRevision()||!r.contentFingerprint().equals(codec.fingerprint(start)))throw invalid();
                }
                epoch=new Epoch(i(e,"evidence_version"),epochId,l(e,"order_id"),l(e,"payment_id"),l(e,"authorization_transaction_id"),i(e,"authorized_amount"),i(e,"authorization_revision"),origin,start,reauth,checkpoint,hash(e,"evidence_digest"));
                for(var h:g.get("histories"))if(epochId.equals(uuid(h,"evidence_epoch_id"))) {
                    var after=codec.decode(bytes(h,"after_snapshot"));
                    if(!hash(h,"after_fingerprint").equals(codec.fingerprint(after))||after.contentRevision()!=i(h,"after_content_revision")||after.totalAmount()!=i(h,"new_total_amount"))throw invalid();
                    var op=unique(g.get("operations"),c->Objects.equals(nullableLong(c,"content_change_history_id"),l(h,"id")));
                    if(!"COMPLETED".equals(t(op,"state"))||!"RETAIN_AUTH".equals(t(op,"execution_route"))||l(op,"order_id")!=orderId)throw invalid();
                    Long previous=nullableLong(h,"previous_history_id");
                    String digest=previous==null?epoch.digest():hash(unique(g.get("histories"),p->l(p,"id")==previous),"evidence_digest");
                    changes.add(new Change(i(h,"evidence_version"),l(h,"id"),l(h,"order_id"),uuid(op,"id"),epochId,previous,i(h,"before_content_revision"),i(h,"old_total_amount"),hash(h,"before_fingerprint"),after,digest,hash(h,"evidence_digest")));
                }
            } else if(nullableLong(o,"evidence_head_history_id")!=null||!g.get("epochs").isEmpty()
                ||g.get("histories").stream().anyMatch(h->!h.get("evidence_version").isNull()))throw invalid();
            for(var a:auths)if(a.operationId()!=null&&Objects.equals(nullableLong(o,"active_payment_id"),a.paymentId()))reauthorization(g,a,null);
            validateRequests(g);
            var facts=new Facts(current,nullableLong(o,"active_payment_id"),payments,auths,epochId!=null,epoch,changes,nullableLong(o,"evidence_head_history_id"),unresolved,pending);
            String assessment=mapper.writeValueAsString(g);
            if(assessment.getBytes(StandardCharsets.UTF_8).length>MAX_READ_BYTES)throw invalid();
            return new Read(State.READ,facts,new OrderPaymentEvidenceValidator().validate(facts),assessment,OrderCheckpointReceiptCodec.hash(assessment),o,observation);
        } catch(org.springframework.dao.ConcurrencyFailureException e){return failure(State.CONFLICT);}
        catch(IllegalArgumentException|NoSuchElementException|ArithmeticException e){return failure(State.INVALID);}
    }
    Reauthorization reauthorizationFor(long orderId,Authorization a) {
        return a.operationId()==null?null:reauthorization(groups(orderId),a,null);
    }
    private Reauthorization reauthorization(Map<String,List<JsonNode>> g,Authorization a,Long expectedHistory) {
        var op=unique(g.get("operations"),c->a.operationId().equals(uuid(c,"id")));
        long history=l(op,"content_change_history_id");if(expectedHistory!=null&&history!=expectedHistory)throw invalid();
        var h=unique(g.get("histories"),r->l(r,"id")==history);
        if(l(h,"order_id")!=a.orderId()||i(h,"old_total_amount")!=i(op,"before_amount")||i(h,"new_total_amount")!=i(op,"after_amount"))throw invalid();
        var cancel=unique(g.get("transactions"),c->a.operationId().equals(uuid(c,"order_change_operation_id"))&&"ORDER_CHANGE_OLD_AUTH_CANCEL".equals(nullableText(c,"purpose")));
        if(!"CANCEL".equals(t(cancel,"transaction_type"))||l(cancel,"payment_id")!=l(op,"old_payment_id")||l(cancel,"order_change_order_id")!=a.orderId())throw invalid();
        var old=unique(g.get("payments"),p->l(p,"id")==l(op,"old_payment_id"));
        if(l(op,"order_id")!=a.orderId()||l(op,"new_payment_id")!=a.paymentId()||!"COMPLETED".equals(t(op,"state"))
            ||(long)i(op,"base_content_revision")+1!=a.revision()||i(op,"after_amount")!=a.amount()
            ||!"CANCELLED".equals(t(old,"status"))||!"PAYJP".equals(t(old,"provider"))||!"CARD".equals(t(old,"payment_method"))
            ||!"SUCCEEDED".equals(t(cancel,"status"))||i(cancel,"amount")!=i(old,"amount")||i(cancel,"order_content_revision")!=i(op,"base_content_revision")
            ||!Objects.equals(t(cancel,"provider_transaction_id"),t(old,"provider_payment_id"))||Objects.equals(t(old,"provider_payment_id"),a.flowId()))throw invalid();
        return new Reauthorization(a.operationId(),l(op,"order_id"),l(op,"old_payment_id"),l(op,"new_payment_id"),a.id(),history,i(op,"base_content_revision"),i(op,"after_amount"),t(op,"state"),l(cancel,"id"),t(cancel,"status"),i(cancel,"amount"),i(cancel,"order_content_revision"),t(cancel,"provider_transaction_id"));
    }
    private Map<String,List<JsonNode>> groups(long id) {
        var g=new TreeMap<String,List<JsonNode>>();int[] remaining={MAX_READ_BYTES};
        java.util.function.BiFunction<String,String,List<JsonNode>> read=(table,where)->rows(table,where,id,"id",remaining);
        g.put("orders",read.apply("orders","id=?"));g.put("items",read.apply("order_items","order_id=?"));g.put("charges",read.apply("order_charges","order_id=?"));
        g.put("operations",read.apply("order_change_operations","order_id=?"));g.put("payments",read.apply("payments","order_id=?"));
        g.put("transactions",read.apply("payment_transactions","payment_id in(select id from payments where order_id=?)"));
        g.put("epochs",read.apply("order_payment_evidence_epochs","order_id=?"));g.put("histories",read.apply("order_content_change_histories","order_id=?"));
        g.put("statusHistories",read.apply("order_status_histories","order_id=?"));
        g.put("requests",read.apply("order_change_financial_requests","order_id=?"));
        g.put("captureRequests",read.apply("capture_requests","payment_id in(select id from payments where order_id=?)"));
        g.put("dispatches",rows("order_change_request_dispatches","request_id in(select id from order_change_financial_requests where order_id=?)",id,"request_id",remaining));
        return g;
    }
    private List<JsonNode> rows(String table,String where,long id,String key,int[] remaining) {
        var result=jdbc.query("select to_jsonb(r)::text from (select * from "+table+" where "+where+" order by "+key+" limit 1002) r",(rs,n)->{
            String s=rs.getString(1);if(s==null)throw invalid();remaining[0]-=s.getBytes(StandardCharsets.UTF_8).length;if(remaining[0]<0)throw invalid();return mapper.readTree(s);
        },id);
        if(result.size()>1001)throw invalid();return List.copyOf(result);
    }
    private void validateRequests(Map<String,List<JsonNode>> g) {
        for(var r:g.get("requests")) {
            var connection=new com.example.ecsite.payment.capture.CaptureConnectionIdentity(t(r,"connection_id"),t(r,"provider_account_ref"),t(r,"mode"),t(r,"api_origin"));
            var request=com.example.ecsite.payment.orderchange.OrderChangeHttpRequest.restore(connection,uuid(r,"operation_id"),l(r,"order_id"),i(r,"amount"),t(r,"idempotency_key"),
                com.example.ecsite.entity.PaymentTransactionPurpose.valueOf(t(r,"purpose")),nullableText(r,"provider_flow_id"),t(r,"http_method"),t(r,"api_path"),t(r,"content_type"),
                i(r,"body_format_version"),bytes(r,"request_body"),i(r,"fingerprint_version"),bytes(r,"request_fingerprint"));
            if(!request.kind().equals(t(r,"request_kind"))||!request.transactionType().equals(t(r,"transaction_type")))throw invalid();
            var op=unique(g.get("operations"),o->uuid(o,"id").equals(uuid(r,"operation_id")));
            var transaction=unique(g.get("transactions"),a->l(a,"id")==l(r,"transaction_id"));
            if(!Objects.equals(uuid(transaction,"order_change_operation_id"),uuid(r,"operation_id"))||l(transaction,"order_change_order_id")!=l(r,"order_id")
                ||l(transaction,"payment_id")!=l(r,"payment_id")||i(transaction,"amount")!=i(r,"amount")
                ||!t(transaction,"transaction_type").equals(t(r,"transaction_type"))||!t(transaction,"purpose").equals(t(r,"purpose"))
                ||!t(transaction,"idempotency_key").equals(t(r,"idempotency_key")))throw invalid();
            var dispatch=unique(g.get("dispatches"),d->l(d,"request_id")==l(r,"id"));
            var payment=unique(g.get("payments"),p->l(p,"id")==l(r,"payment_id"));
            boolean unsent="READY".equals(t(dispatch,"state"))&&"CREATE".equals(request.kind())&&"ABORTED".equals(t(op,"state"))
                &&"FAILED".equals(t(transaction,"status"))&&"FAILED".equals(t(payment,"status"))&&nullableText(payment,"provider_payment_id")==null;
            if(!"OBSERVED".equals(t(dispatch,"state"))&&!unsent)throw invalid();
        }
        for(var r:g.get("captureRequests")) {
            var c=new com.example.ecsite.payment.capture.CaptureConnectionIdentity(t(r,"connection_id"),t(r,"provider_account_ref"),t(r,"mode"),t(r,"api_origin"));
            com.example.ecsite.payment.capture.CaptureHttpRequest.restore(c,t(r,"provider_flow_id"),i(r,"amount_to_capture"),t(r,"idempotency_key"),t(r,"http_method"),t(r,"api_path"),t(r,"content_type"),i(r,"body_format_version"),bytes(r,"request_body"),i(r,"fingerprint_version"),bytes(r,"request_fingerprint"));
        }
        // Every operation-linked successful authorization in the active generation requires
        // its actual immutable CREATE and old-CANCEL requests, never invented provenance.
        Long active=nullableLong(g.get("orders").getFirst(),"active_payment_id");
        for(var a:g.get("transactions"))if("AUTHORIZE".equals(t(a,"transaction_type"))&&Objects.equals(active,l(a,"payment_id"))&&uuid(a,"order_change_operation_id")!=null) {
            UUID operation=uuid(a,"order_change_operation_id");
            for(String kind:List.of("CREATE","OLD_CANCEL"))unique(g.get("requests"),r->operation.equals(uuid(r,"operation_id"))&&kind.equals(t(r,"request_kind")));
        }
    }
    /** No external calls. All writers using this API follow the existing lock order. */
    public void lock(long id) {
        if(!ownsTransaction())throw invalid();
        jdbc.execute("set local lock_timeout='2s'");
        if(!"read committed".equals(jdbc.queryForObject("show transaction_isolation",String.class)))throw invalid();
        jdbc.queryForList("select id from orders where id=? for update",Long.class,id);
        jdbc.queryForList("select id from order_change_operations where order_id=? order by id for update",UUID.class,id);
        jdbc.queryForList("select id from payments where order_id=? order by id for update",Long.class,id);
        jdbc.queryForList("select id from payment_transactions where payment_id in(select id from payments where order_id=?) order by payment_id,id for update",Long.class,id);
        jdbc.queryForList("select request_id from order_change_request_dispatches where request_id in(select id from order_change_financial_requests where order_id=?) order by request_id for update",Long.class,id);
        jdbc.queryForList("select id from order_items where order_id=? order by id for update",Long.class,id);
        jdbc.queryForList("select id from order_charges where order_id=? order by id for update",Long.class,id);
    }
    private boolean ownsTransaction() {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
            ||!org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))return false;
        return Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)c->!c.getAutoCommit()));
    }
    private static <T>T unique(List<T> rows,java.util.function.Predicate<T> predicate){var a=rows.stream().filter(predicate).toList();if(a.size()!=1)throw invalid();return a.getFirst();}
    private static Read failure(State state){return new Read(state,null,null,null,null,null,null);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Incomplete or inconsistent saved evidence");}
}
