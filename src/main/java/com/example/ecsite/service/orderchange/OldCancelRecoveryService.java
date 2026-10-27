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
    @Autowired
    public OldCancelRecoveryService(OrderReauthorizationTransactions results,OldCancelRecoveryAuditRepository audits,PaymentGateway gateway,Clock clock){
        this(results,audits,gateway,clock,System::nanoTime);
    }
    OldCancelRecoveryService(OrderReauthorizationTransactions results,OldCancelRecoveryAuditRepository audits,PaymentGateway gateway,Clock clock,LongSupplier nanos){
        this.results=results;this.audits=audits;this.gateway=gateway;this.clock=clock;this.nanos=nanos;
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
        catch(IllegalStateException changed){if(existing.isPresent())return outcome(reread(command,actor));throw changed;}
        if(!committing){
            var row=audits.start(snapshot,actor);
            if("CONFLICT".equals(row.get("result_code")))return outcome(OldCancelRecovery.Result.CONFLICT);
            if("COMMIT_PENDING".equals(row.get("result_code"))||"COMPLETED".equals(row.get("result_code")))return outcome(reread(command,actor));
        }
        if(!committing){
            OldCancelRecovery.Observation observation;
            try{observation=observe(snapshot,true);}catch(RuntimeException unavailable){audits.hold(command.requestId());return new OldCancelRecovery.Outcome(OldCancelRecovery.Result.HOLD,OldCancelRecovery.Detail.GET_UNKNOWN);}
            try{results.applyRecoveredOldCancellation(observation,actor,clock,nanos);}
            catch(RuntimeException unknownCommit){return failedOutcome(reread(command,actor),unknownCommit);}
            try{snapshot=results.recoverySnapshot(command,true,actor);}catch(IllegalStateException changed){return outcome(reread(command,actor));}
        }
        final OldCancelRecovery.Observation latest;
        try{latest=observe(snapshot,false);}catch(RuntimeException unavailable){return new OldCancelRecovery.Outcome(OldCancelRecovery.Result.COMMIT_PENDING,OldCancelRecovery.Detail.GET_UNKNOWN);}
        try{return outcome(results.completeRecoveredOrder(latest,actor,clock,nanos));}
        catch(RuntimeException unknownCommit){return failedOutcome(reread(command,actor),unknownCommit);}
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
        var row=audits.find(command.requestId()).orElseThrow();OldCancelRecoveryAuditRepository.requireIdentity(row,command,actor);
        var result=results.recoveryReadback(command,actor);
        if(result==OldCancelRecovery.Result.HOLD)audits.hold(command.requestId());
        if(result==OldCancelRecovery.Result.CONFLICT)audits.conflict(command.requestId());
        return result;
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
