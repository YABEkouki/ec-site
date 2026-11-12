package com.example.ecsite.service.order.history;

import java.util.*;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceBundle.*;
import static com.example.ecsite.service.order.history.OrderChangeHistoryVerificationResult.*;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceChecks.*;

/** Pure historical verifier with exclusive Feature125 and known legacy writer paths. */
public final class OrderChangeHistoryVerifier {
    public OrderChangeHistoryVerificationResult verify(OrderChangeEvidenceBundle evidence){
        Objects.requireNonNull(evidence);
        var decisions=new ArrayList<HistoryDecision>();var issues=new ArrayList<Issue>();
        if(evidence.status()==Status.LIMIT_EXCEEDED)return new OrderChangeHistoryVerificationResult(evidence.status(),Displayability.NONE,List.of(),List.of(new Issue(Reason.LIMIT_EXCEEDED,null,null)));
        for(var r:evidence.rows(EvidenceSet.RESULTS))if("APPLIED".equals(r.value("disposition"))&&evidence.rows(EvidenceSet.HISTORIES).stream().noneMatch(h->Objects.equals(h.value("id"),r.value("application_history_id"))))
            issues.add(new Issue(Reason.APPLIED_WITHOUT_HISTORY,EvidenceSet.RESULTS,r.value("id")));
        try {
            var index=new OrderChangeEvidenceIndex(evidence);
            if(!evidence.diagnostics().isEmpty())throw new Invalid(Reason.MISSING_REFERENCE);
            validateUniqueRelations(index);
            var chain=new OrderChangeSnapshotChainVerifier(index);var verifier=new Feature125OrderChangeVerifier(index,chain);
            // Validate detached epochs too, so missing applied history cannot disappear behind a history-driven join.
            chain.validateAll();
            orphanApplications(index,issues);
            boolean broken=!issues.isEmpty();
            for(var h:index.rows(EvidenceSet.HISTORIES)){
                long id=number(h,"id");
                if(!index.feature125(h)){
                    try{var legacy=new LegacyOrderChangeVerifier(index).verify(h);decisions.add(new HistoryDecision(id,Classification.LEGACY_CONFIRMED,legacy,List.of()));}
                    catch(Invalid invalid){decisions.add(unverified(id,invalid.reason));issues.add(new Issue(invalid.reason,EvidenceSet.HISTORIES,Long.toString(id)));}
                    catch(IllegalArgumentException|ArithmeticException|NullPointerException|tools.jackson.core.JacksonException|java.time.DateTimeException malformed){decisions.add(unverified(id,Reason.UNKNOWN_FORMAT));issues.add(new Issue(Reason.UNKNOWN_FORMAT,EvidenceSet.HISTORIES,Long.toString(id)));}
                    continue;
                }
                try {var verified=verifier.verify(h);decisions.add(new HistoryDecision(id,Classification.FEATURE125_CONFIRMED,verified,List.of()));}
                catch(Invalid invalid){decisions.add(unverified(id,invalid.reason));issues.add(new Issue(invalid.reason,EvidenceSet.HISTORIES,Long.toString(id)));broken=true;}
                catch(IllegalArgumentException|ArithmeticException|NullPointerException|tools.jackson.core.JacksonException|java.time.DateTimeException malformed){decisions.add(unverified(id,Reason.UNKNOWN_FORMAT));issues.add(new Issue(Reason.UNKNOWN_FORMAT,EvidenceSet.HISTORIES,Long.toString(id)));broken=true;}
            }
            // Conservative policy: until an independent component is proven, one F125 break hides all F125 output.
            if(broken){decisions.replaceAll(d->d.change()==null?d:unverified(d.historyId(),Reason.DEPENDENCY_UNPROVEN));return result(evidence,Displayability.NONE,decisions,issues);}
            boolean legacy=decisions.stream().anyMatch(d->d.classification()==Classification.UNVERIFIED);
            var display=legacy?(decisions.stream().anyMatch(d->d.change()!=null)?Displayability.PARTIAL:Displayability.NONE):Displayability.ALL;
            decisions.sort(Comparator.<HistoryDecision,java.time.Instant>comparing(d->d.change()==null?java.time.Instant.MAX:d.change().sortInstant()).thenComparingLong(HistoryDecision::historyId));
            return result(evidence,display,decisions,issues);
        }catch(Invalid invalid){issues.add(new Issue(invalid.reason,null,null));}
        catch(IllegalArgumentException|ArithmeticException|NullPointerException|tools.jackson.core.JacksonException|java.time.DateTimeException malformed){issues.add(new Issue(Reason.UNKNOWN_FORMAT,null,null));}
        return result(evidence,Displayability.NONE,allUnverified(evidence),issues);
    }
    private void orphanApplications(OrderChangeEvidenceIndex index,List<Issue> issues){
        for(var r:index.rows(EvidenceSet.RESULTS))if("APPLIED".equals(r.value("disposition"))){
            String history=nullable(r,"application_history_id");if(history==null||index.matching(EvidenceSet.HISTORIES,"id",history).size()!=1)issues.add(new Issue(Reason.APPLIED_WITHOUT_HISTORY,EvidenceSet.RESULTS,text(r,"id")));
        }
        for(var a:index.rows(EvidenceSet.ADMISSIONS))if("COMPLETED".equals(a.value("execution_state"))){
            var ops=index.matching(EvidenceSet.OPERATIONS,"id",nullable(a,"applied_operation_id"));
            if(ops.size()!=1||ops.getFirst().value("content_change_history_id")==null||index.matching(EvidenceSet.HISTORIES,"id",ops.getFirst().value("content_change_history_id")).size()!=1)issues.add(new Issue(Reason.APPLIED_WITHOUT_HISTORY,EvidenceSet.ADMISSIONS,text(a,"id")));
        }
    }
    private void validateUniqueRelations(OrderChangeEvidenceIndex index){
        unique(index,EvidenceSet.ADMISSIONS,"submission_key");unique(index,EvidenceSet.OPERATIONS,"submission_key");
        unique(index,EvidenceSet.OPERATIONS,"content_change_history_id");unique(index,EvidenceSet.RESULTS,"application_operation_id");unique(index,EvidenceSet.RESULTS,"application_history_id");
        for(var field:List.of("origin_operation_id","origin_history_id","previous_epoch_id","payment_id","authorization_transaction_id"))unique(index,EvidenceSet.EPOCHS,field);
        unique(index,EvidenceSet.HISTORIES,"previous_history_id");
        var first=new HashSet<String>();var revisions=new HashSet<String>();
        for(var h:index.rows(EvidenceSet.HISTORIES))if(h.value("evidence_version")!=null){
            check(revisions.add(text(h,"after_content_revision")),Reason.SNAPSHOT_CHAIN);
            if(nullable(h,"previous_history_id")==null)check(first.add(text(h,"evidence_epoch_id")),Reason.SNAPSHOT_CHAIN);
        }
    }
    private void unique(OrderChangeEvidenceIndex index,EvidenceSet set,String field){var seen=new HashSet<String>();for(var r:index.rows(set)){String v=nullable(r,field);if(v!=null)check(seen.add(v),Reason.DUPLICATE_KEY);}}
    private List<HistoryDecision> allUnverified(OrderChangeEvidenceBundle b){
        // Do not drop duplicate IDs from malformed input. Invalid IDs remain represented by the order-level issue.
        var result=new ArrayList<HistoryDecision>();for(var h:b.rows(EvidenceSet.HISTORIES))try{result.add(unverified(number(h,"id"),Reason.DEPENDENCY_UNPROVEN));}catch(Invalid ignored){}return result;
    }
    private HistoryDecision unverified(long id,Reason reason){return new HistoryDecision(id,Classification.UNVERIFIED,null,List.of(reason));}
    private OrderChangeHistoryVerificationResult result(OrderChangeEvidenceBundle b,Displayability d,List<HistoryDecision> rows,List<Issue> issues){return new OrderChangeHistoryVerificationResult(b.status(),d,rows,issues);}
}
