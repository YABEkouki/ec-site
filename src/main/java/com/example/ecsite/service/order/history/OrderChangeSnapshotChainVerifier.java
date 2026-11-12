package com.example.ecsite.service.order.history;

import java.util.*;
import com.example.ecsite.service.order.evidence.*;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceBundle.*;
import static com.example.ecsite.service.order.history.OrderChangeHistoryVerificationResult.*;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceChecks.*;

/** Resolves immutable historical content, never today's order or financial eligibility. */
final class OrderChangeSnapshotChainVerifier {
    private final OrderChangeEvidenceIndex index;
    private final OrderContentSnapshotCodec codec=new OrderContentSnapshotCodec();
    private final Map<String,OrderContentSnapshot> cache=new HashMap<>();
    private final Set<String> visiting=new HashSet<>();
    OrderChangeSnapshotChainVerifier(OrderChangeEvidenceIndex index){this.index=index;}
    void validateAll(){
        record Node(boolean epoch,int revision,EvidenceRow row) {}
        var nodes=new ArrayList<Node>();
        for(var e:index.rows(EvidenceSet.EPOCHS))nodes.add(new Node(true,integer(e,"start_content_revision"),e));
        for(var h:index.rows(EvidenceSet.HISTORIES))if(h.value("evidence_version")!=null)nodes.add(new Node(false,integer(h,"after_content_revision"),h));
        nodes.sort(Comparator.comparingInt(Node::revision));
        for(var n:nodes)if(n.epoch())epoch(text(n.row(),"id"));else history(n.row());
        // Check every epoch boundary, not just the current one. Sorting above bounds valid-chain recursion.
        for(var e:index.rows(EvidenceSet.EPOCHS)){
            var histories=index.matching(EvidenceSet.HISTORIES,"evidence_epoch_id",text(e,"id"));
            String head=histories.stream().max(Comparator.comparingInt(h->integer(h,"after_content_revision"))).map(h->text(h,"id")).orElse(null);
            var next=index.matching(EvidenceSet.EPOCHS,"previous_epoch_id",text(e,"id"));
            if(next.isEmpty()){
                equal(text(e,"id"),index.bundle.order().epochId(),Reason.SNAPSHOT_CHAIN);
                equal(head,index.bundle.order().headHistoryId()==null?null:index.bundle.order().headHistoryId().toString(),Reason.SNAPSHOT_CHAIN);
            }else{
                check(next.size()==1,Reason.SNAPSHOT_CHAIN);var op=index.one(EvidenceSet.OPERATIONS,"id",text(next.getFirst(),"origin_operation_id"));
                equal(nullable(op,"base_evidence_history_id"),head,Reason.SNAPSHOT_CHAIN);
            }
        }
    }
    OrderContentSnapshot before(EvidenceRow admission){
        var epoch=text(admission,"base_evidence_epoch_id");var history=nullable(admission,"base_evidence_history_id");
        if(history==null)return epoch(epoch);
        var h=index.one(EvidenceSet.HISTORIES,"id",history);
        equal(nullable(h,"evidence_epoch_id"),epoch,Reason.SNAPSHOT_CHAIN);
        return history(h);
    }
    OrderContentSnapshot history(EvidenceRow h){
        String key="h:"+text(h,"id");if(cache.containsKey(key))return cache.get(key);
        check(visiting.add(key),Reason.SNAPSHOT_CHAIN);
        try {
            check(integer(h,"evidence_version")==1,Reason.UNKNOWN_FORMAT);
            var ep=index.one(EvidenceSet.EPOCHS,"id",text(h,"evidence_epoch_id"));
            var prev=nullable(h,"previous_history_id");OrderContentSnapshot before;String priorDigest;
            if(prev==null){before=epoch(text(ep,"id"));priorDigest=hex(bytes(ep,"evidence_digest"));}
            else {var p=index.one(EvidenceSet.HISTORIES,"id",prev);check(integer(p,"after_content_revision")<integer(h,"after_content_revision"),Reason.SNAPSHOT_CHAIN);equal(text(p,"evidence_epoch_id"),text(ep,"id"),Reason.SNAPSHOT_CHAIN);before=history(p);priorDigest=hex(bytes(p,"evidence_digest"));}
            var after=decode(h,"after_snapshot");digest(bytes(h,"after_snapshot"),bytes(h,"after_fingerprint"),Reason.SNAPSHOT_CHAIN);
            equal(integer(h,"before_content_revision"),before.contentRevision(),Reason.SNAPSHOT_CHAIN);
            equal(integer(h,"after_content_revision"),Math.incrementExact(before.contentRevision()),Reason.SNAPSHOT_CHAIN);
            equal(after.contentRevision(),integer(h,"after_content_revision"),Reason.SNAPSHOT_CHAIN);
            equal(integer(h,"old_total_amount"),before.totalAmount(),Reason.SNAPSHOT_CHAIN);
            equal(integer(h,"new_total_amount"),after.totalAmount(),Reason.SNAPSHOT_CHAIN);
            equal(hex(bytes(h,"before_fingerprint")),codec.fingerprint(before),Reason.SNAPSHOT_CHAIN);
            check(after.totalAmount()<=integer(ep,"authorized_amount"),Reason.FINANCIAL_EVIDENCE);
            var ops=index.operations(h);check(ops.size()==1,Reason.INCOMPLETE_APPLICATION);var op=ops.getFirst();
            equal(text(op,"state"),"COMPLETED",Reason.INCOMPLETE_APPLICATION);
            equal(text(op,"execution_route"),"RETAIN_AUTH",Reason.SNAPSHOT_CHAIN);
            var change=new OrderPaymentEvidence.Change(1,number(h,"id"),index.bundle.order().orderId(),uuid(op,"id"),uuid(ep,"id"),prev==null?null:Long.valueOf(prev),before.contentRevision(),before.totalAmount(),codec.fingerprint(before),after,priorDigest,null);
            equal(OrderEvidenceDigests.change(change),hex(bytes(h,"evidence_digest")),Reason.SNAPSHOT_CHAIN);
            cache.put(key,after);return after;
        } finally {visiting.remove(key);}
    }
    OrderContentSnapshot epoch(String id){
        String key="e:"+id;if(cache.containsKey(key))return cache.get(key);
        check(visiting.add(key),Reason.SNAPSHOT_CHAIN);
        try {
            var e=index.one(EvidenceSet.EPOCHS,"id",id);check(integer(e,"evidence_version")==1,Reason.UNKNOWN_FORMAT);
            var start=decode(e,"start_snapshot");digest(bytes(e,"start_snapshot"),bytes(e,"start_fingerprint"),Reason.SNAPSHOT_CHAIN);
            digest(bytes(e,"observation_payload"),bytes(e,"observation_fingerprint"),Reason.FINANCIAL_EVIDENCE);
            check(!instant(e,"observation_finished_at").isBefore(instant(e,"observation_started_at")),Reason.FINANCIAL_EVIDENCE);
            equal(start.contentRevision(),integer(e,"start_content_revision"),Reason.SNAPSHOT_CHAIN);
            equal(start.totalAmount(),integer(e,"start_total_amount"),Reason.SNAPSHOT_CHAIN);
            check(integer(e,"authorization_revision")<=start.contentRevision()&&start.totalAmount()<=integer(e,"authorized_amount"),Reason.FINANCIAL_EVIDENCE);
            var payment=index.one(EvidenceSet.PAYMENTS,"id",text(e,"payment_id"));
            var auth=index.one(EvidenceSet.TRANSACTIONS,"id",text(e,"authorization_transaction_id"));
            payment(payment);success(auth,"AUTHORIZE",number(payment,"id"),integer(e,"authorized_amount"),integer(e,"authorization_revision"),text(e,"provider_flow_id"));
            equal(text(payment,"provider_payment_id"),text(e,"provider_flow_id"),Reason.FINANCIAL_EVIDENCE);
            equal(integer(payment,"amount"),integer(e,"authorized_amount"),Reason.FINANCIAL_EVIDENCE);
            equal(text(e,"authorization_type"),"AUTHORIZE",Reason.FINANCIAL_EVIDENCE);equal(text(e,"authorization_status"),"SUCCEEDED",Reason.FINANCIAL_EVIDENCE);
            OrderPaymentEvidence.Origin origin;
            try{origin=OrderPaymentEvidence.Origin.valueOf(text(e,"origin_kind"));}catch(IllegalArgumentException bad){throw new Invalid(Reason.UNKNOWN_FORMAT);}
            OrderPaymentEvidence.Reauthorization reauth=null;OrderPaymentEvidence.Checkpoint checkpoint=null;
            if(origin==OrderPaymentEvidence.Origin.REAUTH_COMPLETED){
                var op=index.one(EvidenceSet.OPERATIONS,"id",text(e,"origin_operation_id"));
                var h=index.one(EvidenceSet.HISTORIES,"id",text(e,"origin_history_id"));
                equal(text(op,"content_change_history_id"),text(h,"id"),Reason.SNAPSHOT_CHAIN);
                equal(text(op,"state"),"COMPLETED",Reason.INCOMPLETE_APPLICATION);
                equal(text(op,"new_payment_id"),text(e,"payment_id"),Reason.FINANCIAL_EVIDENCE);
                equal(integer(op,"after_amount"),start.totalAmount(),Reason.FINANCIAL_EVIDENCE);
                equal(integer(op,"base_content_revision")+1,start.contentRevision(),Reason.SNAPSHOT_CHAIN);
                var previous=nullable(e,"previous_epoch_id");check(previous!=null,Reason.SNAPSHOT_CHAIN);check(integer(index.one(EvidenceSet.EPOCHS,"id",previous),"start_content_revision")<start.contentRevision(),Reason.SNAPSHOT_CHAIN);epoch(previous);
                equal(previous,text(op,"base_evidence_epoch_id"),Reason.SNAPSHOT_CHAIN);
                equal(nullable(auth,"order_change_operation_id"),text(op,"id"),Reason.FINANCIAL_EVIDENCE);
                equal(text(auth,"purpose"),"ORDER_CHANGE_AUTHORIZATION",Reason.FINANCIAL_EVIDENCE);
                equal(number(auth,"order_change_order_id"),index.bundle.order().orderId(),Reason.OWNERSHIP_MISMATCH);
                var cancel=cancel(op);
                reauth=new OrderPaymentEvidence.Reauthorization(uuid(op,"id"),number(op,"order_id"),number(op,"old_payment_id"),number(op,"new_payment_id"),number(auth,"id"),number(h,"id"),integer(op,"base_content_revision"),integer(op,"after_amount"),text(op,"state"),number(cancel,"id"),text(cancel,"status"),integer(cancel,"amount"),integer(cancel,"order_content_revision"),text(cancel,"provider_transaction_id"));
            }else{
                check(nullable(e,"origin_operation_id")==null&&nullable(e,"origin_history_id")==null&&nullable(e,"previous_epoch_id")==null,Reason.SNAPSHOT_CHAIN);
                if(origin==OrderPaymentEvidence.Origin.LEGACY_CURRENT_CHECKPOINT){
                    var receipts=new OrderCheckpointReceiptCodec();var r=receipts.decode(bytes(e,"observation_payload"));
                    check(r.orderId()==start.orderId()&&r.paymentId()==number(e,"payment_id")&&r.authorizationId()==number(auth,"id")&&r.revision()==start.contentRevision()&&r.contentFingerprint().equals(codec.fingerprint(start)),Reason.SNAPSHOT_CHAIN);
                    var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
                    var epochNode=mapper.createObjectNode();epochNode.put("provider_flow_id",text(e,"provider_flow_id"));epochNode.put("authorized_amount",integer(e,"authorized_amount"));epochNode.put("observation_started_at",text(e,"observation_started_at"));epochNode.put("observation_finished_at",text(e,"observation_finished_at"));
                    checkpoint=HistoricalOrderCheckpointValidation.validate(bytes(e,"observation_payload"),epochNode,start);
                    if(nullable(auth,"order_change_operation_id")!=null){
                        var op=index.one(EvidenceSet.OPERATIONS,"id",text(auth,"order_change_operation_id"));
                        equal(text(op,"state"),"COMPLETED",Reason.FINANCIAL_EVIDENCE);equal(text(op,"new_payment_id"),text(e,"payment_id"),Reason.FINANCIAL_EVIDENCE);
                        equal(text(auth,"purpose"),"ORDER_CHANGE_AUTHORIZATION",Reason.FINANCIAL_EVIDENCE);equal(number(auth,"order_change_order_id"),index.bundle.order().orderId(),Reason.OWNERSHIP_MISMATCH);
                        var h=index.one(EvidenceSet.HISTORIES,"id",text(op,"content_change_history_id"));
                        equal(integer(h,"old_total_amount"),integer(op,"before_amount"),Reason.FINANCIAL_EVIDENCE);equal(integer(h,"new_total_amount"),integer(op,"after_amount"),Reason.FINANCIAL_EVIDENCE);
                        check(integer(auth,"order_content_revision")==Math.incrementExact(integer(op,"base_content_revision"))&&integer(auth,"amount")==integer(op,"after_amount"),Reason.FINANCIAL_EVIDENCE);
                        var cancel=cancel(op);reauth=new OrderPaymentEvidence.Reauthorization(uuid(op,"id"),number(op,"order_id"),number(op,"old_payment_id"),number(op,"new_payment_id"),number(auth,"id"),number(h,"id"),integer(op,"base_content_revision"),integer(op,"after_amount"),text(op,"state"),number(cancel,"id"),text(cancel,"status"),integer(cancel,"amount"),integer(cancel,"order_content_revision"),text(cancel,"provider_transaction_id"));
                    }
                }else check(nullable(auth,"order_change_operation_id")==null,Reason.FINANCIAL_EVIDENCE);
                if(nullable(auth,"order_change_operation_id")==null)check(nullable(auth,"purpose")==null||"INITIAL_ORDER_AUTHORIZATION".equals(nullable(auth,"purpose")),Reason.FINANCIAL_EVIDENCE);
            }
            if(origin!=OrderPaymentEvidence.Origin.LEGACY_CURRENT_CHECKPOINT)check(start.contentRevision()==integer(e,"authorization_revision")&&start.totalAmount()==integer(e,"authorized_amount"),Reason.FINANCIAL_EVIDENCE);
            var fact=new OrderPaymentEvidence.Epoch(1,uuid(e,"id"),number(e,"order_id"),number(e,"payment_id"),number(e,"authorization_transaction_id"),integer(e,"authorized_amount"),integer(e,"authorization_revision"),origin,start,reauth,checkpoint,null);
            equal(OrderEvidenceDigests.epoch(fact),hex(bytes(e,"evidence_digest")),Reason.SNAPSHOT_CHAIN);
            cache.put(key,start);return start;
        }finally{visiting.remove(key);}
    }
    EvidenceRow cancel(EvidenceRow op){
        var cancels=index.matching(EvidenceSet.TRANSACTIONS,"order_change_operation_id",text(op,"id")).stream().filter(t->"ORDER_CHANGE_OLD_AUTH_CANCEL".equals(t.value("purpose"))).toList();
        check(cancels.size()==1,Reason.FINANCIAL_EVIDENCE);var c=cancels.getFirst();var old=index.one(EvidenceSet.PAYMENTS,"id",text(op,"old_payment_id"));payment(old);
        var next=index.one(EvidenceSet.PAYMENTS,"id",text(op,"new_payment_id"));
        check(number(old,"id")!=number(next,"id")&&!text(old,"provider_payment_id").equals(text(next,"provider_payment_id")),Reason.FINANCIAL_EVIDENCE);
        success(c,"CANCEL",number(old,"id"),integer(old,"amount"),integer(op,"base_content_revision"),text(old,"provider_payment_id"));
        equal(number(c,"order_change_order_id"),index.bundle.order().orderId(),Reason.OWNERSHIP_MISMATCH);
        return c;
    }
    static void payment(EvidenceRow p){equal(text(p,"provider"),"PAYJP",Reason.FINANCIAL_EVIDENCE);equal(text(p,"payment_method"),"CARD",Reason.FINANCIAL_EVIDENCE);check(integer(p,"amount")>0,Reason.FINANCIAL_EVIDENCE);}
    static void success(EvidenceRow t,String type,long payment,int amount,int revision,String flow){
        check(text(t,"transaction_type").equals(type)&&text(t,"status").equals("SUCCEEDED")&&number(t,"payment_id")==payment&&integer(t,"amount")==amount&&integer(t,"order_content_revision")==revision&&text(t,"provider_transaction_id").equals(flow),Reason.FINANCIAL_EVIDENCE);text(t,"completed_at");
    }
    private OrderContentSnapshot decode(EvidenceRow row,String field){
        var s=codec.decode(bytes(row,field));check(s.orderId()==index.bundle.order().orderId()&&s.customerId()==index.bundle.order().customerId(),Reason.OWNERSHIP_MISMATCH);
        check(s.contentRevision()<=index.bundle.order().revision(),Reason.SNAPSHOT_CHAIN);return s;
    }
    static List<Object> identity(OrderContentSnapshot s){
        var items=new HashMap<List<Object>,Long>();for(var i:s.items())items.merge(List.of(i.productId(),i.productName(),i.categoryId(),i.categoryName(),i.unitPrice(),i.taxCategoryId(),i.taxCategoryCode(),i.taxCategoryName(),i.taxRateBasisPoints()),(long)i.quantity(),Math::addExact);
        return List.of(items,s.charges().stream().map(c->List.of(c.type(),c.name(),c.amount(),c.taxCategoryId(),c.taxCategoryCode(),c.taxCategoryName(),c.taxRateBasisPoints(),c.displayOrder())).toList(),s.itemSubtotal(),s.chargeTotal(),s.taxAmount(),s.totalAmount());
    }
}
