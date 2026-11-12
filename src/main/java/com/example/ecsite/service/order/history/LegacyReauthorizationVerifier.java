package com.example.ecsite.service.order.history;

import java.time.*;
import java.util.*;
import com.example.ecsite.entity.PaymentTransactionPurpose;
import com.example.ecsite.payment.capture.CaptureConnectionIdentity;
import com.example.ecsite.payment.orderchange.OrderChangeHttpRequest;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceBundle.*;
import static com.example.ecsite.service.order.history.OrderChangeHistoryVerificationResult.*;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceChecks.*;
import static com.example.ecsite.service.order.history.LegacyOrderChangeEvidence.*;

/** Old completed operation receipts. Never requires Feature125 APPLIED or current Payment eligibility. */
final class LegacyReauthorizationVerifier {
    private final OrderChangeEvidenceIndex index;
    LegacyReauthorizationVerifier(OrderChangeEvidenceIndex index){this.index=index;}
    LegacyOrderChangeEvidence verify(EvidenceRow h,EvidenceRow op,LegacyOrderChangeEvidence data){
        String oid=text(op,"id");uuid(op,"id");uuid(op,"submission_key");
        for(var k:List.of("execution_version","execution_route","base_evidence_epoch_id","base_evidence_history_id","old_authorization_transaction_id","old_authorized_amount","old_authorization_revision","application_xid"))check(nullable(op,k)==null,Reason.UNKNOWN_FORMAT);
        for(var k:List.of("admission_observation_payload","admission_observation_fingerprint"))check(optionalBytes(op,k)==null,Reason.UNKNOWN_FORMAT);
        check(text(op,"request_digest").matches("[0-9a-f]{64}"),Reason.UNKNOWN_FORMAT);
        equal(text(op,"state"),"COMPLETED",Reason.INCOMPLETE_APPLICATION);equal(text(op,"content_change_history_id"),text(h,"id"),Reason.HISTORY_DIFFERENCE);
        check(integer(op,"base_content_revision")>=0&&Math.incrementExact(integer(op,"base_content_revision"))<=index.bundle.order().revision()&&number(op,"version")>=0,Reason.UNKNOWN_FORMAT);
        LocalDateTime accepted=local(op,"accepted_at"),deadline=local(op,"deadline_at");check(accepted.isBefore(deadline)&&local(op,"grace_until").equals(deadline.plusMinutes(10)),Reason.HISTORY_DIFFERENCE);var decision=local(op,"switch_decided_at");check(!decision.isBefore(accepted)&&!decision.isAfter(local(op,"grace_until")),Reason.FINANCIAL_EVIDENCE);
        for(var k:List.of("abort_request_id","abort_requested_at"))if(op.values().containsKey(k))check(nullable(op,k)==null,Reason.CONTRADICTORY_CLOSURE);
        noFeature125(h,op);
        check(number(op,"customer_id")==index.bundle.order().customerId(),Reason.OWNERSHIP_MISMATCH);
        check(integer(op,"before_amount")==data.before().totalAmount()&&integer(op,"after_amount")==data.after().totalAmount()
            &&integer(op,"item_subtotal")==data.after().itemSubtotal()&&integer(op,"charge_total")==data.after().chargeTotal()&&integer(op,"tax_amount")==data.after().taxAmount(),Reason.HISTORY_DIFFERENCE);
        var shipping=LegacyOrderChangeVerifier.shipping(op,"shipping_");check(shipping.tax().complete()&&shipping.amount()==data.after().chargeTotal(),Reason.HISTORY_DIFFERENCE);
        if(data.shippingChange()!=null)check(sameShipping(shipping,data.shippingChange().after()),Reason.HISTORY_DIFFERENCE);
        items(op,data);
        var old=index.one(EvidenceSet.PAYMENTS,"id",text(op,"old_payment_id"));var next=index.one(EvidenceSet.PAYMENTS,"id",text(op,"new_payment_id"));payment(old);payment(next);
        check(number(old,"id")!=number(next,"id")&&!text(old,"provider_payment_id").equals(text(next,"provider_payment_id")),Reason.FINANCIAL_EVIDENCE);
        equal(integer(next,"amount"),data.after().totalAmount(),Reason.FINANCIAL_EVIDENCE);equal(data.before().totalAmount(),integer(old,"amount"),Reason.FINANCIAL_EVIDENCE);
        var linked=index.matching(EvidenceSet.TRANSACTIONS,"order_change_operation_id",oid);check(linked.size()==2,Reason.FINANCIAL_EVIDENCE);
        var auth=purpose(linked,"ORDER_CHANGE_AUTHORIZATION");var cancel=purpose(linked,"ORDER_CHANGE_OLD_AUTH_CANCEL");
        success(auth,"AUTHORIZE",next,integer(next,"amount"),Math.incrementExact(integer(op,"base_content_revision")),oid);
        success(cancel,"CANCEL",old,integer(old,"amount"),integer(op,"base_content_revision"),oid);
        var oldAuth=index.matching(EvidenceSet.TRANSACTIONS,"payment_id",text(old,"id")).stream().filter(t->"AUTHORIZE".equals(t.value("transaction_type"))&&"SUCCEEDED".equals(t.value("status"))).toList();
        check(oldAuth.size()==1,Reason.FINANCIAL_EVIDENCE);var previous=oldAuth.getFirst();
        check(integer(previous,"amount")==integer(old,"amount")&&integer(previous,"order_content_revision")>=0&&integer(previous,"order_content_revision")<=integer(op,"base_content_revision")&&text(previous,"provider_transaction_id").equals(text(old,"provider_payment_id")),Reason.FINANCIAL_EVIDENCE);local(previous,"completed_at");
        var requests=index.matching(EvidenceSet.FINANCIAL_REQUESTS,"operation_id",oid);check(requests.size()==2,Reason.FINANCIAL_EVIDENCE);
        var create=request(requests,"CREATE",auth,next,op);var oldCancel=request(requests,"OLD_CANCEL",cancel,old,op);equal(create.connection(),oldCancel.connection(),Reason.FINANCIAL_EVIDENCE);
        var cancellation=requests.stream().filter(r->"OLD_CANCEL".equals(r.value("request_kind"))).findFirst().orElseThrow();
        recovery(h,op,cancellation,old,next,previous);
        return new LegacyOrderChangeEvidence(Kind.LEGACY_REAUTHORIZE,data.before(),data.after(),data.items(),data.shippingChange(),shipping,TaxBreakdown.NOT_RECORDED);
    }
    private void noFeature125(EvidenceRow h,EvidenceRow op){
        String oid=text(op,"id");check(!index.feature125(h),Reason.UNKNOWN_FORMAT);
        check(index.rows(EvidenceSet.ADMISSIONS).stream().noneMatch(a->oid.equals(a.value("applied_operation_id"))||text(op,"submission_key").equals(a.value("submission_key"))),Reason.UNKNOWN_FORMAT);
        check(index.matching(EvidenceSet.RESULTS,"application_operation_id",oid).isEmpty()&&index.matching(EvidenceSet.RESULTS,"application_history_id",text(h,"id")).isEmpty(),Reason.UNKNOWN_FORMAT);
        check(index.matching(EvidenceSet.EPOCHS,"origin_operation_id",oid).isEmpty()&&index.matching(EvidenceSet.EPOCHS,"origin_history_id",text(h,"id")).isEmpty(),Reason.UNKNOWN_FORMAT);
        check(index.matching(EvidenceSet.FAILURE_CONTROLS,"operation_id",oid).isEmpty(),Reason.CONTRADICTORY_CLOSURE);
    }
    private void items(EvidenceRow op,LegacyOrderChangeEvidence data){
        var items=index.matching(EvidenceSet.OPERATION_ITEMS,"operation_id",text(op,"id"));var reservations=index.matching(EvidenceSet.RESERVATIONS,"operation_id",text(op,"id"));
        check(items.size()==data.items().size()&&reservations.size()==items.size(),Reason.HISTORY_DIFFERENCE);
        for(var change:data.items()){
            var matching=items.stream().filter(i->number(i,"order_item_id")==change.orderItemId()).toList();check(matching.size()==1,Reason.HISTORY_DIFFERENCE);var item=matching.getFirst();
            equal(text(item,"operation_id"),text(op,"id"),Reason.HISTORY_DIFFERENCE);
            check(number(item,"product_id")==change.productId()&&integer(item,"before_quantity")==change.before().quantity()&&integer(item,"after_quantity")==change.after().quantity()
                &&integer(item,"unit_price")==change.before().unitPrice()&&integer(item,"before_subtotal")==change.before().subtotal()&&integer(item,"after_subtotal")==change.after().subtotal(),Reason.HISTORY_DIFFERENCE);
            int increase=Math.subtractExact(change.after().quantity(),change.before().quantity());check(increase>0&&integer(item,"increase_quantity")==increase,Reason.HISTORY_DIFFERENCE);
            var tax=LegacyOrderChangeVerifier.tax(item,"");check(tax.complete()&&tax.sameAs(change.before().tax())&&tax.sameAs(change.after().tax()),Reason.HISTORY_DIFFERENCE);
            var matches=reservations.stream().filter(r->number(r,"order_item_id")==change.orderItemId()).toList();check(matches.size()==1,Reason.HISTORY_DIFFERENCE);var r=matches.getFirst();
            check(number(r,"product_id")==change.productId()&&integer(r,"quantity")==increase&&text(r,"state").equals("CONSUMED")&&number(r,"hold_movement_id")>0,Reason.HISTORY_DIFFERENCE);local(r,"consumed_at");
        }
    }
    private OrderChangeHttpRequest request(List<EvidenceRow> requests,String kind,EvidenceRow tx,EvidenceRow payment,EvidenceRow op){
        var candidates=requests.stream().filter(r->kind.equals(r.value("request_kind"))).toList();check(candidates.size()==1,Reason.FINANCIAL_EVIDENCE);var r=candidates.getFirst();
        equal(text(r,"transaction_id"),text(tx,"id"),Reason.FINANCIAL_EVIDENCE);equal(text(r,"payment_id"),text(payment,"id"),Reason.FINANCIAL_EVIDENCE);
        for(var key:List.of("amount","purpose","transaction_type","idempotency_key"))equal(text(r,key),text(tx,key),Reason.FINANCIAL_EVIDENCE);
        var c=new CaptureConnectionIdentity(text(r,"connection_id"),text(r,"provider_account_ref"),text(r,"mode"),text(r,"api_origin"));
        var request=OrderChangeHttpRequest.restore(c,uuid(op,"id"),index.bundle.order().orderId(),integer(r,"amount"),text(r,"idempotency_key"),PaymentTransactionPurpose.valueOf(text(r,"purpose")),nullable(r,"provider_flow_id"),text(r,"http_method"),text(r,"api_path"),text(r,"content_type"),integer(r,"body_format_version"),bytes(r,"request_body"),integer(r,"fingerprint_version"),bytes(r,"request_fingerprint"));
        equal(request.kind(),kind,Reason.FINANCIAL_EVIDENCE);
        if(kind.equals("OLD_CANCEL"))equal(text(r,"provider_flow_id"),text(payment,"provider_payment_id"),Reason.FINANCIAL_EVIDENCE);
        var created=instant(r,"created_at");var until=instant(r,"replay_not_after");check(until.isAfter(created)&&!until.isAfter(created.plus(Duration.ofHours(23))),Reason.FINANCIAL_EVIDENCE);
        var dispatch=index.one(EvidenceSet.DISPATCHES,"request_id",text(r,"id"));equal(text(dispatch,"state"),"OBSERVED",Reason.FINANCIAL_EVIDENCE);instant(dispatch,"started_at");instant(dispatch,"observed_at");
        return request;
    }
    private void recovery(EvidenceRow h,EvidenceRow op,EvidenceRow cancellation,EvidenceRow old,EvidenceRow next,EvidenceRow previous){
        var audits=index.matching(EvidenceSet.RECOVERY_AUDITS,"operation_id",text(op,"id"));int applied=0;
        for(var a:index.matching(EvidenceSet.RECOVERY_AUDITS,"content_change_history_id",text(h,"id")))equal(text(a,"operation_id"),text(op,"id"),Reason.CONTRADICTORY_CLOSURE);
        for(var a:audits){
            equal(text(a,"old_cancel_request_id"),text(cancellation,"id"),Reason.CONTRADICTORY_CLOSURE);equal(text(a,"old_flow_id"),text(old,"provider_payment_id"),Reason.CONTRADICTORY_CLOSURE);equal(text(a,"new_flow_id"),text(next,"provider_payment_id"),Reason.CONTRADICTORY_CLOSURE);
            check(number(a,"actor_id")>0&&integer(a,"evidence_format")==1&&bytes(a,"context_digest").length==32,Reason.CONTRADICTORY_CLOSURE);text(a,"actor_username");
            equal(text(a,"reason_code"),"VERIFY_OLD_CANCEL",Reason.CONTRADICTORY_CLOSURE);equal(text(a,"original_reason"),"CANCEL_FLOW_EVIDENCE_MISMATCH",Reason.CONTRADICTORY_CLOSURE);
            var start=instant(a,"started_at");check(!instant(a,"updated_at").isBefore(start),Reason.CONTRADICTORY_CLOSURE);long expected=number(a,"expected_version");check(expected>=0,Reason.CONTRADICTORY_CLOSURE);
            var result=text(a,"result_code");
            if(Set.of("STARTED","HOLD","CONFLICT").contains(result)){
                check(nullable(a,"applied_version")==null&&nullable(a,"completed_version")==null&&nullable(a,"content_change_history_id")==null&&optionalBytes(a,"evidence_digest")==null,Reason.CONTRADICTORY_CLOSURE);
                if(result.equals("STARTED"))check(nullable(a,"finished_at")==null,Reason.CONTRADICTORY_CLOSURE);else check(!instant(a,"finished_at").isBefore(start),Reason.CONTRADICTORY_CLOSURE);
            }else{
                equal(result,"COMPLETED",Reason.CONTRADICTORY_CLOSURE);applied++;
                equal(integer(previous,"order_content_revision"),integer(op,"base_content_revision"),Reason.CONTRADICTORY_CLOSURE);
                check(number(a,"applied_version")==Math.addExact(expected,1)&&number(a,"completed_version")==Math.addExact(expected,2)&&number(a,"completed_version")==number(op,"version"),Reason.CONTRADICTORY_CLOSURE);
                equal(text(a,"content_change_history_id"),text(h,"id"),Reason.CONTRADICTORY_CLOSURE);check(bytes(a,"evidence_digest").length==32&&!instant(a,"finished_at").isBefore(start),Reason.CONTRADICTORY_CLOSURE);
            }
        }
        check(applied<=1,Reason.CONTRADICTORY_CLOSURE);
    }
    private EvidenceRow purpose(List<EvidenceRow> rows,String purpose){var found=rows.stream().filter(r->purpose.equals(r.value("purpose"))).toList();check(found.size()==1,Reason.FINANCIAL_EVIDENCE);return found.getFirst();}
    private void success(EvidenceRow tx,String type,EvidenceRow payment,int amount,int revision,String operation){
        check(text(tx,"transaction_type").equals(type)&&text(tx,"status").equals("SUCCEEDED")&&text(tx,"payment_id").equals(text(payment,"id"))&&integer(tx,"amount")==amount&&integer(tx,"order_content_revision")==revision&&text(tx,"provider_transaction_id").equals(text(payment,"provider_payment_id")),Reason.FINANCIAL_EVIDENCE);
        equal(text(tx,"order_change_operation_id"),operation,Reason.FINANCIAL_EVIDENCE);equal(number(tx,"order_change_order_id"),index.bundle.order().orderId(),Reason.OWNERSHIP_MISMATCH);local(tx,"completed_at");
    }
    private void payment(EvidenceRow p){check(text(p,"provider").equals("PAYJP")&&text(p,"payment_method").equals("CARD")&&integer(p,"amount")>0&&text(p,"provider_payment_id").matches("pfw_[A-Za-z0-9_-]{1,251}"),Reason.FINANCIAL_EVIDENCE);}
    private static LocalDateTime local(EvidenceRow row,String key){return LocalDateTime.parse(text(row,key));}
    private static boolean sameShipping(ShippingSide a,ShippingSide b){return a.name().equals(b.name())&&a.amount()==b.amount()&&a.displayOrder()==b.displayOrder()&&a.tax().sameAs(b.tax());}
}
