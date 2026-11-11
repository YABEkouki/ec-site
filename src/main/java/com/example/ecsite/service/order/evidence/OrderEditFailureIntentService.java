package com.example.ecsite.service.order.evidence;

import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import com.example.ecsite.security.CustomUserDetails;

/** Internal customer command, not an endpoint/worker. Records intent only.
 * No transport, financial settlement, stock release or execution permission.
 * Commit exceptions are observed by immutable command ID, never auto-retried. */
public final class OrderEditFailureIntentService {
    public record Command(UUID id,UUID admission,long executionVersion,long operationVersion,
                          UUID generation,String historyDigest) {
        public Command {
            Objects.requireNonNull(id);Objects.requireNonNull(admission);Objects.requireNonNull(generation);
            if(executionVersion<0||operationVersion<0||historyDigest==null||!historyDigest.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid closure intent command");
        }
    }
    public enum Outcome { RECORDED, OBSERVED, NOT_OBSERVED, CONFLICT, COMMIT_UNKNOWN }
    public record Result(Outcome outcome,UUID id,String intentDigest) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate write,read;
    public OrderEditFailureIntentService(DataSource source){this(source,new DataSourceTransactionManager(source));}
    /** Manager must bind the same DataSource; supports commit-response fault tests. */
    public OrderEditFailureIntentService(DataSource source,PlatformTransactionManager manager){
        jdbc=new JdbcTemplate(Objects.requireNonNull(source));jdbc.setQueryTimeout(5);
        write=transaction(manager,false);read=transaction(new DataSourceTransactionManager(source),true);
    }
    private static TransactionTemplate transaction(PlatformTransactionManager manager,boolean readonly){
        var t=new TransactionTemplate(manager);t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        t.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);t.setReadOnly(readonly);t.setTimeout(10);return t;
    }
    public Result request(Command c){
        outside();Objects.requireNonNull(c);var actor=actor();
        try{return write.execute(s->{
            requireOwner(c,actor);
            jdbc.queryForObject("select f125_failure_request_intent(?,?,?,?,?,decode(?,'hex'),'CUSTOMER',?)",UUID.class,
                c.id(),c.admission(),c.executionVersion(),c.operationVersion(),c.generation(),c.historyDigest(),actor.getUsername());
            return observe(c,actor,Outcome.RECORDED);
        });}catch(RuntimeException uncertain){
            // Even an apparent SQL rejection may accompany an ambiguous commit.
            // Read separately; absence is not proof of rollback and is not retry permission.
            return recover(c);
        }
    }
    public Result recover(Command c){
        outside();Objects.requireNonNull(c);var actor=actor();
        try{return read.execute(s->{requireOwner(c,actor);return observe(c,actor,Outcome.OBSERVED);});}
        catch(org.springframework.security.access.AccessDeniedException denied){throw denied;}
        catch(RuntimeException unavailable){return new Result(Outcome.COMMIT_UNKNOWN,c.id(),null);}
    }
    private Result observe(Command c,CustomUserDetails actor,Outcome present){
        var rows=jdbc.queryForList("""
            select id,execution_version,intent_operation_version,generation_id,
              encode(history_digest,'hex') history,requester_type,actor_name,
              encode(intent_digest,'hex') digest,
              intent_digest=sha256(f125_failure_intent_bytes(x)) valid
            from order_edit_failure_closure_controls x where admission_id=?
            """,c.admission());
        if(rows.isEmpty())return new Result(Outcome.NOT_OBSERVED,c.id(),null);
        var r=rows.getFirst();boolean same=c.id().equals(r.get("id"))
            &&Objects.equals(c.executionVersion(),r.get("execution_version"))
            &&Objects.equals(c.operationVersion(),r.get("intent_operation_version"))
            &&c.generation().equals(r.get("generation_id"))&&c.historyDigest().equals(r.get("history"))
            &&"CUSTOMER".equals(r.get("requester_type"))&&actor.getUsername().equals(r.get("actor_name"))
            &&Boolean.TRUE.equals(r.get("valid"));
        return new Result(same?present:Outcome.CONFLICT,c.id(),same?(String)r.get("digest"):null);
    }
    private void requireOwner(Command c,CustomUserDetails actor){
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from order_edit_admissions where id=? and customer_id=? and actor_username=?)",
            Boolean.class,c.admission(),actor.getId(),actor.getUsername())))
            throw new org.springframework.security.access.AccessDeniedException("Closure owner mismatch");
    }
    private static CustomUserDetails actor(){
        var a=SecurityContextHolder.getContext().getAuthentication();
        if(a==null||!a.isAuthenticated()||!(a.getPrincipal() instanceof CustomUserDetails p)||!p.isEnabled()
            ||a.getAuthorities().stream().noneMatch(x->x.getAuthority().equals("ROLE_USER")))
            throw new org.springframework.security.access.AccessDeniedException("Customer authentication required");
        return p;
    }
    private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())
        throw new IllegalStateException("Closure intent requires a standalone transaction");}
}
