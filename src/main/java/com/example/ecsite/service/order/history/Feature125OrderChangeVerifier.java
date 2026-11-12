package com.example.ecsite.service.order.history;

import java.util.*;
import com.example.ecsite.service.order.evidence.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceBundle.*;
import static com.example.ecsite.service.order.history.OrderChangeHistoryVerificationResult.*;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceChecks.*;

/** Saved application receipts only. No live eligibility or runtime policy checks. */
final class Feature125OrderChangeVerifier {
    private final OrderChangeEvidenceIndex index;
    private final OrderChangeSnapshotChainVerifier chain;
    private final OrderContentSnapshotCodec snapshots=new OrderContentSnapshotCodec();
    private final OrderEditAdmissionPayloadCodec payloads=new OrderEditAdmissionPayloadCodec();
    private final JsonMapper json=JsonMapper.builder().build();
    Feature125OrderChangeVerifier(OrderChangeEvidenceIndex index,OrderChangeSnapshotChainVerifier chain){this.index=index;this.chain=chain;}
    VerifiedOrderChange verify(EvidenceRow h){
        var ops=index.operations(h);check(ops.size()==1,Reason.INCOMPLETE_APPLICATION);var op=ops.getFirst();String oid=text(op,"id"),hid=text(h,"id");
        var admissions=index.rows(EvidenceSet.ADMISSIONS).stream().filter(a->oid.equals(a.value("applied_operation_id"))||Objects.equals(op.value("submission_key"),a.value("submission_key"))).toList();
        check(admissions.size()==1,Reason.INCOMPLETE_APPLICATION);var a=admissions.getFirst();String aid=text(a,"id");
        var applied=index.matching(EvidenceSet.RESULTS,"admission_id",aid).stream().filter(x->"APPLIED".equals(x.value("disposition"))).toList();check(applied.size()==1,Reason.INCOMPLETE_APPLICATION);var x=applied.getFirst();
        equal(text(a,"admission_state"),"ACCEPTED",Reason.INCOMPLETE_APPLICATION);equal(text(a,"execution_state"),"COMPLETED",Reason.INCOMPLETE_APPLICATION);instant(a,"closed_at");
        equal(text(a,"applied_operation_id"),oid,Reason.INCOMPLETE_APPLICATION);equal(text(x,"application_operation_id"),oid,Reason.INCOMPLETE_APPLICATION);equal(text(x,"application_history_id"),hid,Reason.INCOMPLETE_APPLICATION);
        check(integer(x,"application_version")==1&&!bool(x,"recovery"),Reason.UNKNOWN_FORMAT);
        equal(text(x,"reason_code"),"APPLICATION_COMPLETED",Reason.INCOMPLETE_APPLICATION);equal(text(a,"reason_code"),text(x,"reason_code"),Reason.INCOMPLETE_APPLICATION);
        equal(text(op,"state"),"COMPLETED",Reason.INCOMPLETE_APPLICATION);check(integer(op,"execution_version")==1,Reason.UNKNOWN_FORMAT);
        var route=text(a,"route");check(Set.of("RETAIN_AUTH","REAUTHORIZE").contains(route),Reason.UNKNOWN_FORMAT);equal(text(op,"execution_route"),route,Reason.PAYLOAD_BINDING);
        equal(text(op,"submission_key"),text(a,"submission_key"),Reason.PAYLOAD_BINDING);equal(text(op,"request_digest"),hex(bytes(a,"request_digest")),Reason.PAYLOAD_BINDING);
        for(var names:List.of(List.of("base_content_revision","base_content_revision"),List.of("base_evidence_epoch_id","base_evidence_epoch_id"),List.of("base_evidence_history_id","base_evidence_history_id"),List.of("old_payment_id","base_payment_id"),List.of("old_authorization_transaction_id","base_authorization_id"),List.of("old_authorized_amount","authorized_amount"),List.of("old_authorization_revision","authorization_revision"),List.of("before_amount","before_amount"),List.of("after_amount","after_amount")))equal(nullable(op,names.get(0)),nullable(a,names.get(1)),Reason.PAYLOAD_BINDING);
        int r=integer(a,"base_content_revision");check(r>=0,Reason.SNAPSHOT_CHAIN);
        check(integer(x,"application_before_revision")==r&&integer(x,"application_after_revision")==Math.incrementExact(r),Reason.SNAPSHOT_CHAIN);
        var p=index.one(EvidenceSet.ADMISSION_PROOFS,"admission_id",aid);proof(a,p);
        execution(a,x);
        var finalEvent=events(a,p);equal(text(finalEvent,"recording_xid"),text(x,"recording_xid"),Reason.EVENT_CHAIN);equal(text(op,"application_xid"),text(x,"recording_xid"),Reason.PAYLOAD_BINDING);
        check(index.matching(EvidenceSet.UNAPPLIED,"admission_id",aid).isEmpty(),Reason.CONTRADICTORY_CLOSURE);
        check(index.matching(EvidenceSet.FAILURE_BUSINESS_CLOSURES,"admission_id",aid).isEmpty(),Reason.CONTRADICTORY_CLOSURE);
        check(index.matching(EvidenceSet.FAILURE_CONTROLS,"admission_id",aid).isEmpty()&&index.matching(EvidenceSet.FAILURE_CONTROLS,"operation_id",oid).isEmpty(),Reason.CONTRADICTORY_CLOSURE);
        var before=chain.before(a);equal(before.contentRevision(),r,Reason.SNAPSHOT_CHAIN);equal(before.totalAmount(),integer(a,"before_amount"),Reason.SNAPSHOT_CHAIN);equal(snapshots.fingerprint(before),hex(bytes(a,"base_fingerprint")),Reason.SNAPSHOT_CHAIN);
        var request=payloads.decodeRequest(bytes(a,"request_payload"));var candidate=payloads.decodeCandidate(bytes(a,"candidate_payload"));
        check(integer(a,"request_version")==1&&integer(a,"candidate_version")==1,Reason.UNKNOWN_FORMAT);
        payloads.validateSaved(request,candidate,before);
        equal(candidate.epochId().toString(),text(a,"base_evidence_epoch_id"),Reason.PAYLOAD_BINDING);equal(candidate.headHistoryId()==null?null:candidate.headHistoryId().toString(),nullable(a,"base_evidence_history_id"),Reason.PAYLOAD_BINDING);
        equal(candidate.paymentId(),number(a,"base_payment_id"),Reason.PAYLOAD_BINDING);equal(candidate.authorizationId(),number(a,"base_authorization_id"),Reason.PAYLOAD_BINDING);
        equal(candidate.authorizedAmount(),integer(a,"authorized_amount"),Reason.PAYLOAD_BINDING);equal(candidate.authorizationRevision(),integer(a,"authorization_revision"),Reason.PAYLOAD_BINDING);
        var baseEpoch=index.one(EvidenceSet.EPOCHS,"id",text(a,"base_evidence_epoch_id"));
        equal(integer(baseEpoch,"authorized_amount"),candidate.authorizedAmount(),Reason.PAYLOAD_BINDING);equal(integer(baseEpoch,"authorization_revision"),candidate.authorizationRevision(),Reason.PAYLOAD_BINDING);
        equal(text(baseEpoch,"payment_id"),text(a,"base_payment_id"),Reason.PAYLOAD_BINDING);equal(text(baseEpoch,"authorization_transaction_id"),text(a,"base_authorization_id"),Reason.PAYLOAD_BINDING);
        equal(candidate.providerFlowId(),text(baseEpoch,"provider_flow_id"),Reason.PAYLOAD_BINDING);equal(candidate.epochDigest(),hex(bytes(baseEpoch,"evidence_digest")),Reason.SNAPSHOT_CHAIN);
        String head=nullable(a,"base_evidence_history_id");equal(candidate.headDigest(),head==null?candidate.epochDigest():hex(bytes(index.one(EvidenceSet.HISTORIES,"id",head),"evidence_digest")),Reason.SNAPSHOT_CHAIN);
        financial(a,p,candidate);
        OrderContentSnapshot after;EvidenceRow afterEpoch=null;
        if(route.equals("RETAIN_AUTH")){
            equal(text(h,"evidence_epoch_id"),text(a,"base_evidence_epoch_id"),Reason.SNAPSHOT_CHAIN);equal(nullable(h,"previous_history_id"),head,Reason.SNAPSHOT_CHAIN);after=chain.history(h);
            check(after.totalAmount()<=candidate.authorizedAmount(),Reason.FINANCIAL_EVIDENCE);check(nullable(op,"new_payment_id")==null,Reason.FINANCIAL_EVIDENCE);
        }else{
            for(var field:List.of("evidence_version","before_content_revision","after_content_revision","evidence_epoch_id","previous_history_id"))check(nullable(h,field)==null,Reason.SNAPSHOT_CHAIN);
            for(var field:List.of("before_fingerprint","after_fingerprint","after_snapshot","evidence_digest"))check(optionalBytes(h,field)==null,Reason.SNAPSHOT_CHAIN);
            var epochs=index.matching(EvidenceSet.EPOCHS,"origin_operation_id",oid);check(epochs.size()==1,Reason.SNAPSHOT_CHAIN);var e=epochs.getFirst();afterEpoch=e;
            equal(text(e,"origin_history_id"),hid,Reason.SNAPSHOT_CHAIN);equal(text(e,"previous_epoch_id"),text(a,"base_evidence_epoch_id"),Reason.SNAPSHOT_CHAIN);
            after=chain.epoch(text(e,"id"));check(after.totalAmount()>candidate.authorizedAmount(),Reason.FINANCIAL_EVIDENCE);
            check(!text(op,"old_payment_id").equals(text(op,"new_payment_id")),Reason.FINANCIAL_EVIDENCE);
        }
        check(after.contentRevision()==r+1&&after.totalAmount()==integer(a,"after_amount"),Reason.SNAPSHOT_CHAIN);
        equal(hex(bytes(x,"application_fingerprint")),snapshots.fingerprint(after),Reason.SNAPSHOT_CHAIN);
        new OrderChangeFinancialEvidenceVerifier(index).verify(a,op,baseEpoch,afterEpoch);
        compareCandidate(candidate,before,after);
        check(!OrderChangeSnapshotChainVerifier.identity(before).equals(OrderChangeSnapshotChainVerifier.identity(after)),Reason.HISTORY_DIFFERENCE);
        new OrderChangeHistoryDifferenceVerifier(index).verify(h,op,before,after,a);
        return new VerifiedOrderChange(number(h,"id"),route,instant(finalEvent,"recorded_at"),before,after);
    }
    private void proof(EvidenceRow a,EvidenceRow p){
        for(var key:List.of("original_xid","deadline_at","db_generation_id","scope")){
            if(key.equals("deadline_at"))equal(instant(a,key),instant(p,key),Reason.ADMISSION_PROOF);else equal(text(a,key),text(p,key),Reason.ADMISSION_PROOF);
        }
        check(!text(p,"original_xid").equals(text(p,"verification_xid")),Reason.ADMISSION_PROOF);
        check(instant(p,"original_commit_at").isBefore(instant(a,"deadline_at")),Reason.ADMISSION_PROOF);
        check(integer(p,"guard_version")==1&&text(p,"decision").equals("ACCEPTED")&&text(p,"status_before").equals("committed")&&text(p,"status_after").equals("committed"),Reason.ADMISSION_PROOF);
        for(var key:List.of("request","candidate")){digest(bytes(a,key+"_payload"),bytes(a,key+"_digest"),Reason.PAYLOAD_BINDING);check(Arrays.equals(bytes(a,key+"_digest"),bytes(p,key+"_digest")),Reason.ADMISSION_PROOF);}
        var generation=index.one(EvidenceSet.GENERATIONS,"id",text(a,"db_generation_id"));
        check(Set.of("ISOLATED_TEST","OPERATIONAL").contains(text(a,"scope")),Reason.UNKNOWN_FORMAT);
        equal(text(generation,"scope"),text(a,"scope"),Reason.ADMISSION_PROOF);
    }
    private void execution(EvidenceRow a,EvidenceRow applied){
        String aid=text(a,"id");var c=index.one(EvidenceSet.CONTROLS,"admission_id",aid);var attempts=index.matching(EvidenceSet.ATTEMPTS,"admission_id",aid);var results=index.matching(EvidenceSet.RESULTS,"admission_id",aid);
        equal(text(c,"db_generation_id"),text(a,"db_generation_id"),Reason.EXECUTION_SEQUENCE);
        digest(text(c,"baseline").getBytes(java.nio.charset.StandardCharsets.UTF_8),bytes(c,"baseline_digest"),Reason.EXECUTION_SEQUENCE);
        equal(json.readTree(text(c,"baseline")),json.readTree(text(a,"unapplied_baseline")),Reason.EXECUTION_SEQUENCE);
        check(nullable(c,"active_attempt_id")==null&&nullable(c,"lease_until")==null,Reason.EXECUTION_SEQUENCE);
        check(number(c,"version")==Math.addExact(number(applied,"control_version"),1)&&number(c,"fencing_token")==Math.addExact(number(applied,"fencing_token"),1),Reason.EXECUTION_SEQUENCE);
        check(number(c,"get_attempt_count")==attempts.size()&&number(c,"version")==attempts.size()+results.size()&&number(c,"fencing_token")==number(c,"version"),Reason.EXECUTION_SEQUENCE);
        var tokens=new HashSet<Long>();var sequence=new HashSet<Long>();
        for(var at:attempts){
            equal(text(at,"db_generation_id"),text(a,"db_generation_id"),Reason.EXECUTION_SEQUENCE);check(Arrays.equals(bytes(at,"baseline_digest"),bytes(c,"baseline_digest")),Reason.EXECUTION_SEQUENCE);
            check(tokens.add(number(at,"fencing_token"))&&sequence.add(number(at,"control_version"))&&number(at,"control_version")==number(at,"fencing_token"),Reason.EXECUTION_SEQUENCE);
            var rs=results.stream().filter(z->text(z,"attempt_id").equals(text(at,"id"))).toList();check(rs.stream().filter(z->!Set.of("REVIEW","UNKNOWN").contains(z.value("disposition"))).count()==1,Reason.EXECUTION_SEQUENCE);
            var seen=new HashSet<String>();
            for(var z:rs){
                var disposition=text(z,"disposition");check(Set.of("REQUEUE","CLOSE","FAIL","REVIEW","UNKNOWN","APPLIED").contains(disposition)&&seen.add(disposition),Reason.EXECUTION_SEQUENCE);
                equal(text(z,"admission_id"),aid,Reason.EXECUTION_SEQUENCE);equal(text(z,"fencing_token"),text(at,"fencing_token"),Reason.EXECUTION_SEQUENCE);
                // Results store the control version at resolution, possibly after REVIEW/UNKNOWN.
                check(number(z,"control_version")>=number(at,"control_version")&&sequence.add(Math.addExact(number(z,"control_version"),1)),Reason.EXECUTION_SEQUENCE);
                check(bytes(z,"local_digest").length==32,Reason.EXECUTION_SEQUENCE);
            }
        }
        check(sequence.size()==number(c,"version"),Reason.EXECUTION_SEQUENCE);
        for(long n=1;n<=sequence.size();n++)check(sequence.contains(n),Reason.EXECUTION_SEQUENCE);
        var at=index.one(EvidenceSet.ATTEMPTS,"id",text(applied,"attempt_id"));equal(text(at,"admission_id"),aid,Reason.EXECUTION_SEQUENCE);equal(text(at,"control_version"),text(applied,"control_version"),Reason.EXECUTION_SEQUENCE);
    }
    private EvidenceRow events(EvidenceRow a,EvidenceRow proof){
        var events=new ArrayList<>(index.matching(EvidenceSet.EVENTS,"admission_id",text(a,"id")));events.sort(Comparator.comparingLong(e->number(e,"event_no")));
        check(events.size()==number(a,"version")+1&&!events.isEmpty(),Reason.EVENT_CHAIN);EvidenceRow previous=null;
        int decisions=0;
        for(int i=0;i<events.size();i++){
            var e=events.get(i);equal(number(e,"event_no"),(long)i,Reason.EVENT_CHAIN);instant(e,"recorded_at");
            if(i==0){check(text(e,"event_type").equals("RECORDED")&&nullable(e,"before_admission")==null&&nullable(e,"before_execution")==null&&text(e,"after_admission").equals("RECORDED_UNVERIFIED")&&text(e,"after_execution").equals("NOT_READY")&&text(e,"recording_xid").equals(text(a,"original_xid")),Reason.EVENT_CHAIN);}
            else{equal(text(e,"before_admission"),text(previous,"after_admission"),Reason.EVENT_CHAIN);equal(text(e,"before_execution"),text(previous,"after_execution"),Reason.EVENT_CHAIN);check(Set.of("ADMISSION_DECIDED","STATE_CHANGED").contains(text(e,"event_type")),Reason.EVENT_CHAIN);}
            if(text(e,"event_type").equals("ADMISSION_DECIDED")){decisions++;equal(text(e,"recording_xid"),text(proof,"verification_xid"),Reason.EVENT_CHAIN);equal(text(e,"after_admission"),"ACCEPTED",Reason.EVENT_CHAIN);}
            previous=e;
        }
        check(decisions==1,Reason.EVENT_CHAIN);equal(text(previous,"after_admission"),text(a,"admission_state"),Reason.EVENT_CHAIN);equal(text(previous,"after_execution"),text(a,"execution_state"),Reason.EVENT_CHAIN);equal(text(previous,"reason_code"),text(a,"reason_code"),Reason.EVENT_CHAIN);return previous;
    }
    private void financial(EvidenceRow a,EvidenceRow proof,OrderEditAdmissionPayloadCodec.CandidatePayload candidate){
        check(integer(a,"financial_snapshot_version")==1,Reason.UNKNOWN_FORMAT);
        var codec=new OrderAdmissionFinancialSnapshotCodec();var f=codec.decode(text(a,"financial_snapshot"));
        check(Arrays.equals(codec.canonical(f),bytes(a,"financial_canonical"))&&Arrays.equals(codec.digest(f),bytes(a,"financial_digest")),Reason.PAYLOAD_BINDING);
        var doc=json.readTree(text(a,"financial_snapshot"));var bind=json.readTree(text(proof,"financial_binding"));
        check(Arrays.equals(codec.binding(text(proof,"financial_binding")),bytes(proof,"financial_binding_canonical"))&&Arrays.equals(codec.bindingDigest(text(proof,"financial_binding")),bytes(proof,"financial_binding_digest")),Reason.PAYLOAD_BINDING);
        var expected=new LinkedHashMap<String,Object>();
        expected.put("domain",codec.BINDING_DOMAIN);expected.put("version",1);expected.put("admissionId",text(a,"id"));expected.put("orderId",number(a,"order_id"));expected.put("dbGeneration",text(a,"db_generation_id"));expected.put("originalXid",text(a,"original_xid"));expected.put("verificationXid",text(proof,"verification_xid"));expected.put("requestDigest",hex(bytes(a,"request_digest")));expected.put("candidateDigest",hex(bytes(a,"candidate_digest")));expected.put("snapshotVersion",1);expected.put("snapshotDigest",hex(bytes(a,"financial_digest")));expected.put("originalCommit",micros(instant(proof,"original_commit_at")));expected.put("deadline",micros(instant(a,"deadline_at")));expected.put("statusBefore","committed");expected.put("statusAfter","committed");expected.put("decision","ACCEPTED");expected.put("guardVersion",1);
        check(Arrays.equals(codec.binding(json.writeValueAsString(expected)),bytes(proof,"financial_binding_canonical")),Reason.PAYLOAD_BINDING);
        for(var key:List.of("admissionId","orderId","dbGeneration","originalXid","requestDigest","candidateDigest","deadline"))equal(doc.get(key),bind.get(key),Reason.PAYLOAD_BINDING);
        check(f.customerId()==number(a,"customer_id")&&f.revision()==integer(a,"base_content_revision")&&f.status().equals("ORDERED")&&f.epoch().equals(candidate.epochId())&&Objects.equals(f.head(),candidate.headHistoryId())&&f.fingerprint().equals(candidate.baseFingerprint())&&f.epochDigest().equals(candidate.epochDigest())&&f.headDigest().equals(candidate.headDigest())&&f.activePayment()==candidate.paymentId()&&f.payment()==candidate.paymentId()&&f.authorization()==candidate.authorizationId()&&f.orderAmount()==candidate.beforeAmount()&&f.authorizedAmount()==candidate.authorizedAmount()&&f.authorizationRevision()==candidate.authorizationRevision(),Reason.PAYLOAD_BINDING);
        var payments=f.payments().stream().filter(p->p.id()==candidate.paymentId()).toList();var auths=f.transactions().stream().filter(t->t.id()==candidate.authorizationId()).toList();
        check(payments.size()==1&&auths.size()==1,Reason.FINANCIAL_EVIDENCE);var p=payments.getFirst();var t=auths.getFirst();
        check(p.orderId()==number(a,"order_id")&&p.provider().equals("PAYJP")&&p.method().equals("CARD")&&p.status().equals("AUTHORIZED")&&p.amount()==candidate.authorizedAmount()&&Objects.equals(p.providerId(),candidate.providerFlowId())&&t.paymentId()==p.id().longValue()&&t.type().equals("AUTHORIZE")&&t.status().equals("SUCCEEDED")&&t.amount()==candidate.authorizedAmount()&&t.revision()==candidate.authorizationRevision()&&Objects.equals(t.providerId(),p.providerId())&&t.completed()!=null,Reason.FINANCIAL_EVIDENCE);
    }
    private void compareCandidate(OrderEditAdmissionPayloadCodec.CandidatePayload c,OrderContentSnapshot before,OrderContentSnapshot after){
        var p=c.preview();check(p.itemSubtotal()==after.itemSubtotal()&&p.chargeTotal()==after.chargeTotal()&&p.taxAmount()==after.taxAmount()&&p.totalAmount()==after.totalAmount()&&p.charges().equals(after.charges()),Reason.PAYLOAD_BINDING);
        var locals=new HashSet<Long>();c.newLineReferences().forEach(r->locals.add(r.previewItemId()));
        var existing=p.items().stream().filter(i->!locals.contains(i.orderItemId())).toList();
        for(var i:existing)check(after.items().contains(i),Reason.PAYLOAD_BINDING);
        var oldIds=new HashSet<Long>();before.items().forEach(i->oldIds.add(i.orderItemId()));var newActual=new ArrayList<>(after.items());newActual.removeAll(existing);
        check(newActual.stream().noneMatch(i->oldIds.contains(i.orderItemId())),Reason.PAYLOAD_BINDING);
        var expected=p.items().stream().filter(i->locals.contains(i.orderItemId())).map(Feature125OrderChangeVerifier::withoutId).toList();
        var actual=newActual.stream().map(Feature125OrderChangeVerifier::withoutId).toList();
        check(multiset(expected).equals(multiset(actual)),Reason.PAYLOAD_BINDING);
    }
    private static List<Object> withoutId(OrderContentSnapshot.Item i){return List.of(i.productId(),i.productName(),i.categoryId(),i.categoryName(),i.unitPrice(),i.quantity(),i.subtotal(),i.taxCategoryId(),i.taxCategoryCode(),i.taxCategoryName(),i.taxRateBasisPoints());}
    private static Map<List<Object>,Long> multiset(List<List<Object>> rows){var m=new HashMap<List<Object>,Long>();rows.forEach(r->m.merge(r,1L,Long::sum));return m;}
}
