package com.example.ecsite.service.orderchange;

import java.time.*;
import java.util.*;
import java.util.function.LongSupplier;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.security.AdminUserDetails;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Explicit admin orchestration. No Sender/advance/reconcile dependency; provider interactions are GET only. */
@Service
@Transactional(propagation=Propagation.NEVER)
public class OldCancelRecoveryService {
    private final OrderReauthorizationTransactions results;
    private final OldCancelRecoveryAuditRepository audits;
    private final PaymentGateway gateway;
    private final Clock clock;
    private final LongSupplier nanos;
    private final java.util.function.Consumer<OldCancelRecoveryDiagnostics.Entry> diagnostics;
    @Autowired
    public OldCancelRecoveryService(OrderReauthorizationTransactions results,OldCancelRecoveryAuditRepository audits,PaymentGateway gateway,Clock clock){
        this(results,audits,gateway,clock,System::nanoTime);
    }
    OldCancelRecoveryService(OrderReauthorizationTransactions results,OldCancelRecoveryAuditRepository audits,PaymentGateway gateway,Clock clock,LongSupplier nanos){
        this(results,audits,gateway,clock,nanos,OldCancelRecoveryDiagnostics.LOG);
    }
    OldCancelRecoveryService(OrderReauthorizationTransactions results,OldCancelRecoveryAuditRepository audits,PaymentGateway gateway,Clock clock,LongSupplier nanos,
            java.util.function.Consumer<OldCancelRecoveryDiagnostics.Entry> diagnostics){
        this.results=results;this.audits=audits;this.gateway=gateway;this.clock=clock;this.nanos=nanos;this.diagnostics=diagnostics;
    }
    public OldCancelRecovery.Result recover(OldCancelRecovery.Command command){return recoverDetailed(command).result();}
    /** Display classification only; uses exactly the same recovery decisions and transactions. */
    public OldCancelRecovery.Outcome recoverDetailed(OldCancelRecovery.Command command){
        var actor=actor();var existing=audits.find(command.requestId());
        if(existing.isPresent()){
            OldCancelRecoveryAuditRepository.requireIdentity(existing.get(),command,actor);
            if("COMPLETED".equals(existing.get().get("result_code")))return outcome(results.recoveryCompleted(command,actor));
            if("CONFLICT".equals(existing.get().get("result_code")))return outcome(OldCancelRecovery.Result.CONFLICT);
        }
        boolean committing=existing.isPresent()&&"COMMIT_PENDING".equals(existing.get().get("result_code"));
        OldCancelRecovery.Snapshot snapshot;
        try{snapshot=results.recoverySnapshot(command,committing,actor);}
        catch(IllegalStateException changed){diagnose(command,OldCancelRecoveryDiagnostics.Stage.SNAPSHOT,null,changed);if(existing.isPresent())return outcome(reread(command,actor));throw changed;}
        if(!committing){
            java.util.Map<String,Object> row;
            try{row=audits.start(snapshot,actor);}catch(RuntimeException failure){diagnose(command,OldCancelRecoveryDiagnostics.Stage.AUDIT_START,null,failure);throw failure;}
            if("CONFLICT".equals(row.get("result_code")))return outcome(OldCancelRecovery.Result.CONFLICT);
            if("COMMIT_PENDING".equals(row.get("result_code"))||"COMPLETED".equals(row.get("result_code")))return outcome(reread(command,actor));
        }
        if(!committing){
            OldCancelRecovery.Observation observation;
            try{observation=observe(snapshot,true);}catch(RuntimeException unavailable){diagnose(command,OldCancelRecoveryDiagnostics.Stage.GET,null,unavailable);hold(command);return new OldCancelRecovery.Outcome(OldCancelRecovery.Result.HOLD,OldCancelRecovery.Detail.GET_UNKNOWN);}
            try{return outcome(results.applyAndCompleteRecoveredOrder(observation,actor,clock,nanos));}
            catch(RuntimeException unknownCommit){diagnose(command,observation.stage,observation,unknownCommit);return failedOutcome(reread(command,actor),unknownCommit);}
        }
        final OldCancelRecovery.Observation latest;
        try{latest=observe(snapshot,false);}catch(RuntimeException unavailable){diagnose(command,OldCancelRecoveryDiagnostics.Stage.GET,null,unavailable);return new OldCancelRecovery.Outcome(OldCancelRecovery.Result.COMMIT_PENDING,OldCancelRecovery.Detail.GET_UNKNOWN);}
        try{return outcome(results.completeRecoveredOrder(latest,actor,clock,nanos));}
        catch(RuntimeException unknownCommit){diagnose(command,latest.stage,latest,unknownCommit);return failedOutcome(reread(command,actor),unknownCommit);}
    }
    private OldCancelRecovery.Outcome outcome(OldCancelRecovery.Result result){
        return new OldCancelRecovery.Outcome(result,switch(result){
            case COMPLETED->OldCancelRecovery.Detail.COMPLETED;case COMMIT_PENDING->OldCancelRecovery.Detail.COMMIT_PENDING;
            case CONFLICT->OldCancelRecovery.Detail.VERSION_CONFLICT;case HOLD->OldCancelRecovery.Detail.HOLD;});
    }
    private OldCancelRecovery.Outcome failedOutcome(OldCancelRecovery.Result result,RuntimeException failure){
        if(result==OldCancelRecovery.Result.COMPLETED||result==OldCancelRecovery.Result.CONFLICT)return outcome(result);
        var detail=failure instanceof OldCancelRecovery.EvidenceFailure evidence?evidence.detail:
            failure instanceof org.springframework.dao.DataAccessException?OldCancelRecovery.Detail.AUDIT_FAILED:outcome(result).detail();
        return new OldCancelRecovery.Outcome(result,detail);
    }
    private OldCancelRecovery.Observation observe(OldCancelRecovery.Snapshot snapshot,boolean includeOld){
        long start=nanos.getAsLong();var started=clock.instant();
        var old=includeOld?gateway.observeOrderChangeFlow(snapshot.oldFlow()):null;
        var next=gateway.observeOrderChangeFlow(snapshot.newFlow());
        return new OldCancelRecovery.Observation(snapshot,old,next,started,clock.instant(),start,nanos.getAsLong());
    }
    private OldCancelRecovery.Result reread(OldCancelRecovery.Command command,OldCancelRecovery.Actor actor){
        // A successful readback is required. If the database is unavailable, propagate and never replay a write.
        OldCancelRecovery.Result result;
        try{
            var row=audits.find(command.requestId()).orElseThrow();OldCancelRecoveryAuditRepository.requireIdentity(row,command,actor);
            result=results.recoveryReadback(command,actor);
        }catch(RuntimeException failure){diagnose(command,OldCancelRecoveryDiagnostics.Stage.READBACK,null,failure);throw failure;}
        if(result==OldCancelRecovery.Result.HOLD)hold(command);
        if(result==OldCancelRecovery.Result.CONFLICT){
            try{audits.conflict(command.requestId());}catch(RuntimeException failure){diagnose(command,OldCancelRecoveryDiagnostics.Stage.AUDIT_RESULT,null,failure);throw failure;}
        }
        return result;
    }
    private void hold(OldCancelRecovery.Command command){
        try{audits.hold(command.requestId());}catch(RuntimeException failure){diagnose(command,OldCancelRecoveryDiagnostics.Stage.AUDIT_RESULT,null,failure);throw failure;}
    }
    private void diagnose(OldCancelRecovery.Command command,OldCancelRecoveryDiagnostics.Stage stage,OldCancelRecovery.Observation observed,RuntimeException failure){
        // No exception message, class name, response payload or actor information crosses this boundary.
        try{
            var reason=failure instanceof OldCancelRecoveryDiagnostics.Failure typed?typed.reason:
                failure instanceof OldCancelRecovery.EvidenceFailure evidence?
                    (evidence.detail==OldCancelRecovery.Detail.OLD_EVIDENCE_UNCONFIRMED?OldCancelRecoveryDiagnostics.Reason.OLD_EVIDENCE_UNCONFIRMED:OldCancelRecoveryDiagnostics.Reason.NEW_AUTH_UNAVAILABLE):
                stage==OldCancelRecoveryDiagnostics.Stage.GET?OldCancelRecoveryDiagnostics.Reason.GET_UNKNOWN:
                stage==OldCancelRecoveryDiagnostics.Stage.READBACK?OldCancelRecoveryDiagnostics.Reason.READBACK_FAILED:
                stage==OldCancelRecoveryDiagnostics.Stage.AUDIT_START||stage==OldCancelRecoveryDiagnostics.Stage.AUDIT_RESULT?OldCancelRecoveryDiagnostics.Reason.AUDIT_FAILED:
                failure instanceof org.springframework.dao.DataAccessException?OldCancelRecoveryDiagnostics.Reason.DB_FAILURE:
                failure instanceof IllegalStateException&&(stage==OldCancelRecoveryDiagnostics.Stage.SNAPSHOT||stage==OldCancelRecoveryDiagnostics.Stage.REVALIDATE)?OldCancelRecoveryDiagnostics.Reason.TARGET_CONFLICT:
                OldCancelRecoveryDiagnostics.Reason.UNEXPECTED_FAILURE;
            long now=nanos.getAsLong();
            OldCancelRecoveryDiagnostics.emit(diagnostics,new OldCancelRecoveryDiagnostics.Entry(command.requestId(),stage,reason,
                observed==null?-1:observed.elapsedMs(now),observed==null?-1:observed.lockMs(now)));
        }catch(RuntimeException unavailable){ /* Diagnostic failure does not change recovery results. */ }
    }
    private OldCancelRecovery.Actor actor(){
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null||!authentication.isAuthenticated()
            ||authentication.getAuthorities().stream().noneMatch(a->"ROLE_ADMIN".equals(a.getAuthority()))
            ||!(authentication.getPrincipal() instanceof AdminUserDetails principal)||!principal.isEnabled()
            ||principal.getId()==null||principal.getId()<=0||principal.getUsername()==null||principal.getUsername().isBlank()||principal.getUsername().length()>100)
            throw new AccessDeniedException("Valid administrator authentication required");
        return new OldCancelRecovery.Actor(principal.getId(),principal.getUsername());
    }
}
