package com.example.ecsite.service.order.history;

import java.util.*;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceBundle.*;
import static com.example.ecsite.service.order.history.OrderChangeHistoryVerificationResult.*;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceChecks.*;

/** Indexes lists without silently collapsing duplicates. No acquisition or persistence dependencies. */
final class OrderChangeEvidenceIndex {
    final OrderChangeEvidenceBundle bundle;
    private final Map<EvidenceSet,Map<List<String>,EvidenceRow>> indexed=new EnumMap<>(EvidenceSet.class);
    private final Map<EvidenceSet,Map<String,Map<String,List<EvidenceRow>>>> secondary=new EnumMap<>(EvidenceSet.class);
    OrderChangeEvidenceIndex(OrderChangeEvidenceBundle bundle){
        this.bundle=Objects.requireNonNull(bundle);
        check(bundle.order().orderId()>0&&bundle.order().customerId()>0&&bundle.order().revision()>=0,Reason.OWNERSHIP_MISMATCH);
        check(bundle.coverage().rows()>=0&&bundle.coverage().rawBytes()>=0,Reason.ACQUISITION_CONTRACT);
        for(var set:EvidenceSet.values()){
            check(bundle.evidence().containsKey(set),Reason.ACQUISITION_CONTRACT);
            var map=new HashMap<List<String>,EvidenceRow>();
            for(var row:rows(set)){
                var key=keys(set).stream().map(k->text(row,k)).toList();
                check(map.putIfAbsent(key,row)==null,Reason.DUPLICATE_KEY);
                if(switch(set){case HISTORIES,OPERATIONS,OPERATION_ITEMS,ADMISSIONS,CONTROLS,ATTEMPTS,RESULTS,EPOCHS,PAYMENTS,LEGACY_CLOSURE_ATTEMPTS,LEGACY_CLOSURE_ISSUES,FINANCIAL_REQUESTS,RECOVERY_AUDITS,UNAPPLIED,FAILURE_CONTROLS,FAILURE_BUSINESS_CLOSURES,INVENTORY_PLANS -> true;default -> false;})
                    equal(number(row,"order_id"),bundle.order().orderId(),Reason.OWNERSHIP_MISMATCH);
                else if(row.values().containsKey("order_id"))equal(number(row,"order_id"),bundle.order().orderId(),Reason.OWNERSHIP_MISMATCH);
                if(set==EvidenceSet.ADMISSIONS||set==EvidenceSet.OPERATIONS||row.values().containsKey("customer_id"))equal(number(row,"customer_id"),bundle.order().customerId(),Reason.OWNERSHIP_MISMATCH);
                if(set==EvidenceSet.HISTORIES)check(number(row,"id")>0,Reason.UNKNOWN_FORMAT);
            }
            indexed.put(set,map);
        }
        check(bundle.order().epochId()!=null||rows(EvidenceSet.EPOCHS).isEmpty(),Reason.SNAPSHOT_CHAIN);
        if(bundle.order().epochId()!=null)one(EvidenceSet.EPOCHS,"id",bundle.order().epochId());
        if(bundle.order().headHistoryId()!=null){
            check(bundle.order().epochId()!=null,Reason.SNAPSHOT_CHAIN);
            var h=one(EvidenceSet.HISTORIES,"id",bundle.order().headHistoryId().toString());
            equal(nullable(h,"evidence_epoch_id"),bundle.order().epochId(),Reason.SNAPSHOT_CHAIN);
        }
        // Incoming child relations are validated too, including rows that no displayed history visits.
        relation(EvidenceSet.ITEM_CHANGES,"history_id",EvidenceSet.HISTORIES,"id");
        relation(EvidenceSet.CHARGE_CHANGES,"history_id",EvidenceSet.HISTORIES,"id");
        relation(EvidenceSet.OPERATION_ITEMS,"operation_id",EvidenceSet.OPERATIONS,"id");
        relation(EvidenceSet.ADMISSION_PROOFS,"admission_id",EvidenceSet.ADMISSIONS,"id");
        relation(EvidenceSet.EVENTS,"admission_id",EvidenceSet.ADMISSIONS,"id");
        relation(EvidenceSet.CONTROLS,"admission_id",EvidenceSet.ADMISSIONS,"id");
        relation(EvidenceSet.ATTEMPTS,"admission_id",EvidenceSet.ADMISSIONS,"id");
        relation(EvidenceSet.RESULTS,"admission_id",EvidenceSet.ADMISSIONS,"id");
        relation(EvidenceSet.RESULTS,"attempt_id",EvidenceSet.ATTEMPTS,"id");
        relation(EvidenceSet.TRANSACTIONS,"payment_id",EvidenceSet.PAYMENTS,"id");
    }
    static List<String> keys(EvidenceSet set){return switch(set){
        case OPERATION_ITEMS,RESERVATIONS -> List.of("operation_id","order_item_id");
        case EVENTS -> List.of("admission_id","event_no");
        case INVENTORY_PLANS -> List.of("admission_id","product_id");
        case ADMISSION_PROOFS,CONTROLS,UNAPPLIED -> List.of("admission_id");
        case DISPATCHES,RECOVERY_AUDITS -> List.of("request_id");
        case FAILURE_BUSINESS_CLOSURES -> List.of("control_id");
        case FAILURE_WAITING_RESOLUTIONS -> List.of("unknown_result_id");
        default -> List.of("id");};}
    private void relation(EvidenceSet from,String field,EvidenceSet to,String target){for(var r:rows(from))one(to,target,text(r,field));}
    List<EvidenceRow> rows(EvidenceSet set){return bundle.rows(set);}
    List<EvidenceRow> matching(EvidenceSet set,String field,String value){
        if(keys(set).equals(List.of(field))){
            var row=value==null?null:indexed.get(set).get(List.of(value));return row==null?List.of():List.of(row);
        }
        var byField=secondary.computeIfAbsent(set,k->new HashMap<>());
        var byValue=byField.computeIfAbsent(field,k->{
            var grouped=new HashMap<String,List<EvidenceRow>>();for(var row:rows(set))grouped.computeIfAbsent(row.value(field),v->new ArrayList<>()).add(row);
            grouped.replaceAll((v,list)->List.copyOf(list));return grouped;
        });
        return byValue.getOrDefault(value,List.of());
    }
    EvidenceRow one(EvidenceSet set,String field,String value){var r=matching(set,field,value);check(value!=null&&r.size()==1,Reason.MISSING_REFERENCE);return r.getFirst();}
    List<EvidenceRow> operations(EvidenceRow history){return matching(EvidenceSet.OPERATIONS,"content_change_history_id",text(history,"id"));}
    boolean feature125(EvidenceRow h){
        for(var k:List.of("evidence_version","before_content_revision","after_content_revision","evidence_epoch_id","previous_history_id"))if(h.value(k)!=null)return true;
        for(var k:List.of("before_fingerprint","after_fingerprint","after_snapshot","evidence_digest"))if(h.binaries().get(k)!=null)return true;
        String id=text(h,"id");
        if(!matching(EvidenceSet.RESULTS,"application_history_id",id).isEmpty()||!matching(EvidenceSet.EPOCHS,"origin_history_id",id).isEmpty())return true;
        for(var op:operations(h)){
            if(op.value("execution_version")!=null||op.value("execution_route")!=null||op.value("application_xid")!=null)return true;
            if(!matching(EvidenceSet.RESULTS,"application_operation_id",op.value("id")).isEmpty()||!matching(EvidenceSet.ADMISSIONS,"applied_operation_id",op.value("id")).isEmpty())return true;
            if(rows(EvidenceSet.ADMISSIONS).stream().anyMatch(a->Objects.equals(a.value("submission_key"),op.value("submission_key"))))return true;
            if(!matching(EvidenceSet.EPOCHS,"origin_operation_id",op.value("id")).isEmpty())return true;
        }
        return false;
    }
}
