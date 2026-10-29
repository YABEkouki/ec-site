package com.example.ecsite.service.order.evidence;

import java.util.*;
import static com.example.ecsite.service.order.evidence.OrderPaymentEvidence.*;
import static com.example.ecsite.service.order.evidence.OrderPaymentEvidenceDecision.*;

/** Validates complete detached LOCAL evidence only. Callers remain responsible for external
 * observation, admission/deadline, inventory, operation completion, and consistent/complete DB reads.
 * No fallback to the legacy decreased-amount rule is possible for enrolled orders. */
public final class OrderPaymentEvidenceValidator {
    private final OrderContentSnapshotCodec codec=new OrderContentSnapshotCodec();

    public OrderPaymentEvidenceDecision validate(Facts f) {
        Reason stage=Reason.INVALID_CONTENT;
        try {
            if(f==null)return decision(null,Disposition.BLOCKED,stage,null);
            codec.encode(f.current());
            if(f.unresolvedOperation())return decision(f,Disposition.BLOCKED,Reason.UNRESOLVED_OPERATION,null);
            stage=Reason.FINANCIAL_STATE;
            var auth=authorization(f);
            if(!f.evidenceRequired()) {
                if(f.epoch()!=null || !f.histories().isEmpty() || f.headHistoryId()!=null)
                    return decision(f,Disposition.REVIEW_REQUIRED,Reason.INVALID_EPOCH,auth);
                return decision(f,Disposition.LEGACY_UNENROLLED,Reason.LEGACY_REQUIRES_ADMISSION,auth);
            }
            var e=f.epoch();
            if(e==null)return decision(f,Disposition.REVIEW_REQUIRED,Reason.MISSING_EVIDENCE,auth);
            stage=Reason.INVALID_EPOCH;
            validateEpoch(f,auth);
            stage=Reason.INVALID_CHAIN;
            if(f.histories().size()>1000)throw invalid();
            var byId=new HashMap<Long,Change>();var byPrevious=new HashMap<Long,Change>();var operations=new HashSet<UUID>();
            for(var c:f.histories()) {
                if(c.version()!=1 || c.id()<=0 || c.operationId()==null || !operations.add(c.operationId())
                        || c.orderId()!=f.current().orderId() || !e.id().equals(c.epochId())
                        || byId.put(c.id(),c)!=null || byPrevious.put(c.previousHistoryId(),c)!=null
                        || (e.reauthorization()!=null && (c.operationId().equals(e.reauthorization().operationId()) || c.id()==e.reauthorization().historyId())))throw invalid();
                if(!OrderEvidenceDigests.matches(OrderEvidenceDigests.change(c),c.digest()))throw invalid();
            }
            var before=e.start();String previousDigest=e.digest();Long previousId=null;int seen=0;
            while(byPrevious.containsKey(previousId)) {
                if(++seen>byId.size())throw invalid();
                var c=byPrevious.get(previousId);
                if(c.beforeRevision()!=before.contentRevision() || c.beforeAmount()!=before.totalAmount()
                        || !OrderEvidenceDigests.matches(codec.fingerprint(before),c.beforeFingerprint())
                        || !OrderEvidenceDigests.matches(previousDigest,c.previousDigest())
                        || c.after().orderId()!=before.orderId() || c.after().customerId()!=before.customerId()
                        || c.after().contentRevision()!=Math.addExact(before.contentRevision(),1)
                        || c.after().totalAmount()>auth.amount())throw invalid();
                codec.encode(c.after());
                if(OrderContentSemantics.same(before,c.after()))throw invalid();
                before=c.after();previousDigest=c.digest();previousId=c.id();
            }
            if(seen!=byId.size() || !Objects.equals(previousId,f.headHistoryId()))throw invalid();
            stage=Reason.CURRENT_MISMATCH;
            if(!Arrays.equals(codec.encode(before),codec.encode(f.current())))throw invalid();
            if(f.current().totalAmount()>auth.amount())return decision(f,Disposition.BLOCKED,Reason.AMOUNT_EXCEEDS_AUTHORIZATION,auth);
            return decision(f,seen==0?Disposition.EVIDENCE_CURRENT:Disposition.EVIDENCE_RETAINED_AUTH,Reason.NONE,auth);
        } catch(RuntimeException malformed) {
            return decision(f,stage==Reason.FINANCIAL_STATE||stage==Reason.INVALID_CONTENT?Disposition.BLOCKED:Disposition.REVIEW_REQUIRED,stage,null);
        }
    }

    private Authorization authorization(Facts f) {
        if(f.pendingFinancialOperation() || f.activePaymentId()==null)throw invalid();
        var ids=new HashSet<Long>();
        for(var p:f.payments())if(p.id()<=0 || !ids.add(p.id()) || p.orderId()!=f.current().orderId()
                || p.amount()<=0 || !Set.of("PENDING","REQUIRES_ACTION","AUTHORIZED","CAPTURED","CANCELLED","FAILED").contains(p.status()))throw invalid();
        var active=f.payments().stream().filter(p->!Set.of("FAILED","CANCELLED").contains(p.status())).toList();
        if(active.size()!=1)throw invalid();var p=active.getFirst();
        if(p.id()!=f.activePaymentId() || !"AUTHORIZED".equals(p.status()) || !"PAYJP".equals(p.provider())
                || !"CARD".equals(p.method()) || p.flowId()==null || !p.flowId().matches("pfw_[A-Za-z0-9_-]{1,251}"))throw invalid();
        var authIds=new HashSet<Long>();
        for(var a:f.authorizations())if(a.id()<=0 || !authIds.add(a.id()) || a.orderId()!=f.current().orderId()
                || !ids.contains(a.paymentId()) || "PENDING".equals(a.status()))throw invalid();
        var own=f.authorizations().stream().filter(a->a.paymentId()==p.id()).toList();
        if(own.size()!=1)throw invalid();var a=own.getFirst();
        if(!"AUTHORIZE".equals(a.type()) || !"SUCCEEDED".equals(a.status()) || a.amount()!=p.amount()
                || a.revision()<0 || a.revision()>f.current().contentRevision() || !Objects.equals(a.flowId(),p.flowId()))throw invalid();
        boolean initial=a.purpose()==null || "INITIAL_ORDER_AUTHORIZATION".equals(a.purpose());
        boolean changed="ORDER_CHANGE_AUTHORIZATION".equals(a.purpose());
        if(!(initial&&a.operationId()==null || changed&&a.operationId()!=null))throw invalid();
        return a;
    }

    private void validateEpoch(Facts f, Authorization a) {
        var e=f.epoch();
        if(e.version()!=1 || e.id()==null || e.origin()==null || e.orderId()!=f.current().orderId()
                || e.paymentId()!=f.activePaymentId() || e.authorizationId()!=a.id()
                || e.authorizedAmount()!=a.amount() || e.authorizationRevision()!=a.revision())throw invalid();
        var s=e.start();codec.encode(s);
        if(s.orderId()!=e.orderId() || s.customerId()!=f.current().customerId() || s.contentRevision()<a.revision()
                || s.totalAmount()>a.amount() || !OrderEvidenceDigests.matches(OrderEvidenceDigests.epoch(e),e.digest()))throw invalid();
        if("ORDER_CHANGE_AUTHORIZATION".equals(a.purpose()))validateReauthorization(f,a,e.reauthorization());
        else if(e.reauthorization()!=null)throw invalid();
        switch(e.origin()) {
            case INITIAL_AUTH -> {
                if(a.operationId()!=null || e.checkpoint()!=null || s.contentRevision()!=a.revision() || s.totalAmount()!=a.amount())throw invalid();
            }
            case REAUTH_COMPLETED -> {
                if(e.reauthorization()==null || e.checkpoint()!=null || s.contentRevision()!=a.revision() || s.totalAmount()!=a.amount())throw invalid();
            }
            case LEGACY_CURRENT_CHECKPOINT -> {
                var c=e.checkpoint();
                if(c==null || c.orderId()!=e.orderId() || c.paymentId()!=e.paymentId() || c.authorizationId()!=a.id()
                        || c.revision()!=s.contentRevision() || s.contentRevision()<=a.revision() || s.totalAmount()>=a.amount()
                        || !OrderEvidenceDigests.matches(c.contentFingerprint(),codec.fingerprint(s))
                        || !OrderEvidenceDigests.validHash(c.localAssessmentDigest()) || !OrderEvidenceDigests.validHash(c.externalObservationDigest()))throw invalid();
            }
        }
    }

    private void validateReauthorization(Facts f, Authorization a, Reauthorization r) {
        if(r==null || !Objects.equals(r.operationId(),a.operationId()) || r.orderId()!=f.current().orderId()
                || r.newPaymentId()!=a.paymentId() || r.authorizationId()!=a.id() || r.historyId()<=0 || r.oldPaymentId()==r.newPaymentId()
                || r.baseRevision()<0 || a.revision()!=Math.addExact(r.baseRevision(),1) || r.afterAmount()!=a.amount()
                || !"COMPLETED".equals(r.state()) || r.oldCancelId()<=0 || r.oldCancelId()==a.id()
                || f.authorizations().stream().anyMatch(other->other.id()==r.oldCancelId())
                || !"SUCCEEDED".equals(r.oldCancelStatus()) || r.oldCancelRevision()!=r.baseRevision())throw invalid();
        var old=f.payments().stream().filter(p->p.id()==r.oldPaymentId()).findFirst().orElseThrow(OrderPaymentEvidenceValidator::invalid);
        if(!"CANCELLED".equals(old.status()) || !"PAYJP".equals(old.provider()) || !"CARD".equals(old.method())
                || old.amount()!=r.oldCancelAmount() || old.flowId()==null || Objects.equals(old.flowId(),a.flowId())
                || !Objects.equals(old.flowId(),r.oldCancelFlow()))throw invalid();
    }

    private static OrderPaymentEvidenceDecision decision(Facts f, Disposition d, Reason reason, Authorization auth) {
        return new OrderPaymentEvidenceDecision(d,reason,f==null||f.current()==null?0:f.current().orderId(),
                f==null?null:f.activePaymentId(),auth==null?null:auth.id(),f==null||f.epoch()==null?null:f.epoch().id(),
                f==null?null:f.headHistoryId(),auth==null?0:auth.amount());
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid local order payment evidence"); }
}
