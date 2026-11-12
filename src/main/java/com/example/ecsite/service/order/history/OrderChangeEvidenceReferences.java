package com.example.ecsite.service.order.history;

import java.util.*;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceBundle.*;

/** Referential acquisition diagnostics only, deliberately not a completion verifier. */
final class OrderChangeEvidenceReferences {
    record Ref(EvidenceSet source,String field,EvidenceSet target) {}
    private static final List<Ref> REFS=List.of(
        new Ref(EvidenceSet.CAPTURE_REQUESTS,"payment_id",EvidenceSet.PAYMENTS),
        new Ref(EvidenceSet.CAPTURE_REQUESTS,"capture_transaction_id",EvidenceSet.TRANSACTIONS),
        new Ref(EvidenceSet.LEGACY_CLOSURE_ATTEMPTS,"operation_id",EvidenceSet.OPERATIONS),
        new Ref(EvidenceSet.LEGACY_CLOSURE_ATTEMPTS,"new_payment_id",EvidenceSet.PAYMENTS),
        new Ref(EvidenceSet.LEGACY_CLOSURE_ISSUES,"operation_id",EvidenceSet.OPERATIONS),
        new Ref(EvidenceSet.LEGACY_CLOSURE_ISSUES,"last_attempt_id",EvidenceSet.LEGACY_CLOSURE_ATTEMPTS),
        new Ref(EvidenceSet.LEGACY_CLOSURE_EVENTS,"issue_id",EvidenceSet.LEGACY_CLOSURE_ISSUES),
        new Ref(EvidenceSet.LEGACY_CLOSURE_EVENTS,"attempt_id",EvidenceSet.LEGACY_CLOSURE_ATTEMPTS),
        new Ref(EvidenceSet.ADMISSIONS,"db_generation_id",EvidenceSet.GENERATIONS),
        new Ref(EvidenceSet.CONTROLS,"db_generation_id",EvidenceSet.GENERATIONS),
        new Ref(EvidenceSet.ATTEMPTS,"db_generation_id",EvidenceSet.GENERATIONS),
        new Ref(EvidenceSet.ITEM_CHANGES,"history_id",EvidenceSet.HISTORIES),
        new Ref(EvidenceSet.CHARGE_CHANGES,"history_id",EvidenceSet.HISTORIES),
        new Ref(EvidenceSet.HISTORIES,"previous_history_id",EvidenceSet.HISTORIES),
        new Ref(EvidenceSet.HISTORIES,"evidence_epoch_id",EvidenceSet.EPOCHS),
        new Ref(EvidenceSet.OPERATIONS,"content_change_history_id",EvidenceSet.HISTORIES),
        new Ref(EvidenceSet.OPERATIONS,"base_evidence_epoch_id",EvidenceSet.EPOCHS),
        new Ref(EvidenceSet.OPERATIONS,"base_evidence_history_id",EvidenceSet.HISTORIES),
        new Ref(EvidenceSet.OPERATIONS,"old_payment_id",EvidenceSet.PAYMENTS),
        new Ref(EvidenceSet.OPERATIONS,"new_payment_id",EvidenceSet.PAYMENTS),
        new Ref(EvidenceSet.OPERATIONS,"old_authorization_transaction_id",EvidenceSet.TRANSACTIONS),
        new Ref(EvidenceSet.OPERATION_ITEMS,"operation_id",EvidenceSet.OPERATIONS),
        new Ref(EvidenceSet.ADMISSIONS,"base_evidence_epoch_id",EvidenceSet.EPOCHS),
        new Ref(EvidenceSet.ADMISSIONS,"base_evidence_history_id",EvidenceSet.HISTORIES),
        new Ref(EvidenceSet.ADMISSIONS,"base_payment_id",EvidenceSet.PAYMENTS),
        new Ref(EvidenceSet.ADMISSIONS,"base_authorization_id",EvidenceSet.TRANSACTIONS),
        new Ref(EvidenceSet.ADMISSIONS,"applied_operation_id",EvidenceSet.OPERATIONS),
        new Ref(EvidenceSet.ADMISSION_PROOFS,"admission_id",EvidenceSet.ADMISSIONS),
        new Ref(EvidenceSet.CONTROLS,"admission_id",EvidenceSet.ADMISSIONS),
        new Ref(EvidenceSet.CONTROLS,"active_attempt_id",EvidenceSet.ATTEMPTS),
        new Ref(EvidenceSet.ATTEMPTS,"admission_id",EvidenceSet.ADMISSIONS),
        new Ref(EvidenceSet.RESULTS,"admission_id",EvidenceSet.ADMISSIONS),
        new Ref(EvidenceSet.RESULTS,"attempt_id",EvidenceSet.ATTEMPTS),
        new Ref(EvidenceSet.RESULTS,"application_operation_id",EvidenceSet.OPERATIONS),
        new Ref(EvidenceSet.RESULTS,"application_history_id",EvidenceSet.HISTORIES),
        new Ref(EvidenceSet.EVENTS,"admission_id",EvidenceSet.ADMISSIONS),
        new Ref(EvidenceSet.EPOCHS,"previous_epoch_id",EvidenceSet.EPOCHS),
        new Ref(EvidenceSet.EPOCHS,"origin_operation_id",EvidenceSet.OPERATIONS),
        new Ref(EvidenceSet.EPOCHS,"origin_history_id",EvidenceSet.HISTORIES),
        new Ref(EvidenceSet.EPOCHS,"payment_id",EvidenceSet.PAYMENTS),
        new Ref(EvidenceSet.EPOCHS,"authorization_transaction_id",EvidenceSet.TRANSACTIONS),
        new Ref(EvidenceSet.TRANSACTIONS,"payment_id",EvidenceSet.PAYMENTS),
        new Ref(EvidenceSet.TRANSACTIONS,"order_change_operation_id",EvidenceSet.OPERATIONS),
        new Ref(EvidenceSet.FINANCIAL_REQUESTS,"operation_id",EvidenceSet.OPERATIONS),
        new Ref(EvidenceSet.FINANCIAL_REQUESTS,"payment_id",EvidenceSet.PAYMENTS),
        new Ref(EvidenceSet.FINANCIAL_REQUESTS,"transaction_id",EvidenceSet.TRANSACTIONS),
        new Ref(EvidenceSet.DISPATCHES,"request_id",EvidenceSet.FINANCIAL_REQUESTS),
        new Ref(EvidenceSet.RECOVERY_AUDITS,"operation_id",EvidenceSet.OPERATIONS),
        new Ref(EvidenceSet.RECOVERY_AUDITS,"content_change_history_id",EvidenceSet.HISTORIES),
        new Ref(EvidenceSet.RECOVERY_AUDITS,"old_cancel_request_id",EvidenceSet.FINANCIAL_REQUESTS),
        new Ref(EvidenceSet.UNAPPLIED,"admission_id",EvidenceSet.ADMISSIONS),
        new Ref(EvidenceSet.FAILURE_CONTROLS,"admission_id",EvidenceSet.ADMISSIONS),
        new Ref(EvidenceSet.FAILURE_CONTROLS,"operation_id",EvidenceSet.OPERATIONS),
        new Ref(EvidenceSet.FAILURE_CONTROLS,"old_payment_id",EvidenceSet.PAYMENTS),
        new Ref(EvidenceSet.FAILURE_CONTROLS,"new_payment_id",EvidenceSet.PAYMENTS),
        new Ref(EvidenceSet.FAILURE_CONTROLS,"authorization_id",EvidenceSet.TRANSACTIONS),
        new Ref(EvidenceSet.FAILURE_CONTROLS,"create_request_id",EvidenceSet.FINANCIAL_REQUESTS),
        new Ref(EvidenceSet.FAILURE_CONTROLS,"cancel_request_id",EvidenceSet.FINANCIAL_REQUESTS),
        new Ref(EvidenceSet.FAILURE_OBSERVATIONS,"control_id",EvidenceSet.FAILURE_CONTROLS),
        new Ref(EvidenceSet.FAILURE_UNKNOWN_RESOLUTIONS,"control_id",EvidenceSet.FAILURE_CONTROLS),
        new Ref(EvidenceSet.FAILURE_UNKNOWN_RESOLUTIONS,"unknown_result_id",EvidenceSet.RESULTS),
        new Ref(EvidenceSet.FAILURE_UNKNOWN_RESOLUTIONS,"terminal_observation_id",EvidenceSet.FAILURE_OBSERVATIONS),
        new Ref(EvidenceSet.FAILURE_BUSINESS_CLOSURES,"control_id",EvidenceSet.FAILURE_CONTROLS),
        new Ref(EvidenceSet.FAILURE_BUSINESS_CLOSURES,"admission_id",EvidenceSet.ADMISSIONS),
        new Ref(EvidenceSet.FAILURE_BUSINESS_CLOSURES,"new_payment_id",EvidenceSet.PAYMENTS),
        new Ref(EvidenceSet.FAILURE_BUSINESS_CLOSURES,"failure_observation_id",EvidenceSet.FAILURE_OBSERVATIONS),
        new Ref(EvidenceSet.FAILURE_WAITING_RESOLUTIONS,"control_id",EvidenceSet.FAILURE_CONTROLS),
        new Ref(EvidenceSet.FAILURE_WAITING_RESOLUTIONS,"unknown_result_id",EvidenceSet.RESULTS),
        new Ref(EvidenceSet.FAILURE_WAITING_RESOLUTIONS,"failure_observation_id",EvidenceSet.FAILURE_OBSERVATIONS),
        new Ref(EvidenceSet.INVENTORY_PLANS,"admission_id",EvidenceSet.ADMISSIONS),
        new Ref(EvidenceSet.INVENTORY_PLANS,"operation_id",EvidenceSet.OPERATIONS),
        new Ref(EvidenceSet.RESERVATIONS,"operation_id",EvidenceSet.OPERATIONS));
    static List<Diagnostic> inspect(OrderEvidence order,Map<EvidenceSet,List<EvidenceRow>> evidence,OrderChangeEvidenceQueries queries){
        var result=new ArrayList<>(inspect(evidence,queries));
        rootReference(result,order,evidence,queries,EvidenceSet.EPOCHS,order.epochId(),"current_evidence_epoch_id");
        rootReference(result,order,evidence,queries,EvidenceSet.HISTORIES,order.headHistoryId()==null?null:order.headHistoryId().toString(),"evidence_head_history_id");
        return result;
    }
    private static void rootReference(List<Diagnostic> result,OrderEvidence order,Map<EvidenceSet,List<EvidenceRow>> evidence,
            OrderChangeEvidenceQueries queries,EvidenceSet target,String id,String field){
        if(id==null||evidence.getOrDefault(target,List.of()).stream().anyMatch(r->id.equals(r.value("id"))))return;
        boolean exists=queries.existingReferenceIds(target,Set.of(id)).contains(id);
        result.add(new Diagnostic(exists?"OUT_OF_SCOPE_REFERENCE":"MISSING_REFERENCE",null,Long.toString(order.orderId()),field));
    }
    static List<Diagnostic> inspect(Map<EvidenceSet,List<EvidenceRow>> evidence,OrderChangeEvidenceQueries queries){
        var indexes=new EnumMap<EvidenceSet,Set<String>>(EvidenceSet.class);
        evidence.forEach((set,rows)->{var ids=new HashSet<String>();rows.forEach(r->ids.add(r.value("id")));indexes.put(set,ids);});
        var unknown=new EnumMap<EvidenceSet,Set<String>>(EvidenceSet.class);
        for(var ref:REFS)for(var row:evidence.getOrDefault(ref.source(),List.of())){
            String id=row.value(ref.field());
            if(id!=null&&!indexes.getOrDefault(ref.target(),Set.of()).contains(id))unknown.computeIfAbsent(ref.target(),k->new HashSet<>()).add(id);
        }
        var exists=new EnumMap<EvidenceSet,Set<String>>(EvidenceSet.class);
        unknown.forEach((target,ids)->exists.put(target,queries.existingReferenceIds(target,ids)));
        var diagnostics=new ArrayList<Diagnostic>();
        for(var ref:REFS)for(var row:evidence.getOrDefault(ref.source(),List.of())){
            String id=row.value(ref.field());
            if(id!=null&&unknown.getOrDefault(ref.target(),Set.of()).contains(id))diagnostics.add(new Diagnostic(
                exists.getOrDefault(ref.target(),Set.of()).contains(id)?"OUT_OF_SCOPE_REFERENCE":"MISSING_REFERENCE",
                ref.source(),row.value("id"),ref.field()));
        }
        return diagnostics;
    }
}
