package com.example.ecsite.service.order.evidence;

import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.payment.capture.CaptureConnectionIdentity;
import static com.example.ecsite.payment.orderchange.ThreeDsFailureEvidence.*;

/** Explicit customer withdrawal after a verified 3DS failure. Provider GET only;
 * local business closure never claims provider cancellation. No worker/retry loop. */
public final class OrderEditFailureClosureService {
    public enum Outcome { CLOSED_UNAPPLIED, HELD, COMMIT_UNKNOWN }
    private record Prepared(Context context,LocalFacts local,OrderEditFailureIntentService.Command command) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate read,write;
    private final OrderEditFailureIntentService intents;
    private final PaymentGateway gateway;
    public OrderEditFailureClosureService(DataSource source,PaymentGateway gateway){
        this.gateway=Objects.requireNonNull(gateway);jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(5);
        read=transaction(source,true);write=transaction(source,false);intents=new OrderEditFailureIntentService(source);
    }
    private static TransactionTemplate transaction(DataSource source,boolean readonly){
        var t=new TransactionTemplate(new DataSourceTransactionManager(source));
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        t.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);t.setReadOnly(readonly);t.setTimeout(10);return t;
    }
    public Outcome terminate(UUID admission){
        outside();var actor=actor();Objects.requireNonNull(admission);
        // Owner denial is outside the recovery catch, before any provider access.
        read.execute(s->{owner(admission,actor);return null;});
        try {
            var already=read.execute(s->closed(admission));
            if(Boolean.TRUE.equals(already))return Outcome.CLOSED_UNAPPLIED;
            Prepared p=read.execute(s->prepare(admission,actor));
            if(p==null)return Outcome.HELD;
            var observation=gateway.observeOrderEditFailure(p.context(),p.local());
            if(observation==null||observation.kind()!=Kind.THREE_DS_FAILURE
                ||!observation.context().equals(p.context()))return Outcome.HELD;
            // The immutable 3C command is committed before closure. If its response is
            // ambiguous, do not automatically continue writing or resend the command.
            if(intents.request(p.command()).outcome()!=OrderEditFailureIntentService.Outcome.RECORDED)return recover(admission);
            return write.execute(s->{
                owner(admission,actor);
                var c=p.context();var id=c.ownership().controlId();
                jdbc.queryForObject("select f125_reauth_lock(?,?)",Object.class,c.ownership().orderId(),c.ownership().operationId());
                jdbc.update("""
                    insert into order_edit_failure_closure_observations(id,control_id,control_version,kind,document,
                      observed_started_at,observed_finished_at,history_digest,canonical,digest)
                    values(?,?,0,'THREE_DS_FAILURE',?::jsonb,?,?,?,null,null)
                    """,UUID.randomUUID(),id,observation.document(),
                    java.sql.Timestamp.from(observation.window().startedAt()),java.sql.Timestamp.from(observation.window().finishedAt()),c.historyDigest());
                jdbc.queryForObject("select f125_failure_advance(?,0,'FAILURE_OBSERVED')",Object.class,id);
                jdbc.queryForObject("select f125_failure_close_unapplied(?,1)",UUID.class,id);
                return Outcome.CLOSED_UNAPPLIED;
            });
        }catch(AccessDeniedException denied){throw denied;}
        catch(RuntimeException uncertain){return recover(admission);}
    }
    /** Read-only after failure/commit response loss. Absence does not authorize a retry. */
    public Outcome recover(UUID admission){
        outside();var actor=actor();
        try{return read.execute(s->{owner(admission,actor);return closed(admission)?Outcome.CLOSED_UNAPPLIED:Outcome.HELD;});}
        catch(AccessDeniedException denied){throw denied;}
        catch(RuntimeException unavailable){return Outcome.COMMIT_UNKNOWN;}
    }
    private boolean closed(UUID admission){return Boolean.TRUE.equals(jdbc.queryForObject("""
        select exists(select 1 from order_edit_failure_closure_controls c
        where c.admission_id=? and f125_failure_business_closed(c.id))
        """,Boolean.class,admission));}
    private Prepared prepare(UUID admission,CustomUserDetails actor){
        owner(admission,actor);
        var rows=jdbc.queryForList("""
            select r.order_id,r.db_generation_id,r.base_payment_id,r.after_amount,r.base_content_revision,
              op.id operation_id,op.version operation_version,op.new_payment_id,op.switch_decided_at,
              op.content_change_history_id,q.id create_request_id,q.transaction_id,q.connection_id,q.provider_account_ref,q.mode,q.api_origin,
              p.provider_payment_id,p.status new_status,oldp.status old_status,t.status auth_status,t.completed_at,
              x.version execution_version,x.active_attempt_id,x.lease_until,
              f125_failure_history(r.id) history,c.id control_id,c.state control_state,c.version control_version,
              c.history_digest intent_history,c.execution_version intent_execution,c.intent_operation_version intent_operation
            from order_edit_admissions r
            join order_change_operations op on op.order_id=r.order_id and op.submission_key=r.submission_key
            join payments p on p.id=op.new_payment_id join payments oldp on oldp.id=r.base_payment_id
            join order_change_financial_requests q on q.operation_id=op.id and q.request_kind='CREATE'
            join payment_transactions t on t.id=q.transaction_id
            join order_edit_execution_controls x on x.admission_id=r.id
            left join order_edit_failure_closure_controls c on c.admission_id=r.id
            where r.id=? and r.admission_state='ACCEPTED' and r.execution_state='RECOVERY_PENDING'
              and r.closed_at is null and r.applied_operation_id is null and r.route='REAUTHORIZE'
              and op.state in ('AUTHORIZING','AUTH_UNKNOWN','REQUIRES_ACTION')
              and f125_runtime_available()
              and x.active_attempt_id is null and x.lease_until is null
              and not exists(select 1 from order_change_financial_requests fq
                left join order_change_request_dispatches fd on fd.request_id=fq.id
                where fq.order_id=r.order_id and (fd.request_id is null or fd.state<>'OBSERVED'))
              and not exists(select 1 from order_edit_execution_attempt_results result
                where result.admission_id=r.id and result.disposition='UNKNOWN' and result.reason_code<>'REAUTHORIZATION_WAITING')
            """,admission);
        if(rows.size()!=1)return null;var r=rows.getFirst();
        if(r.get("control_state")!=null&&!"REQUESTED".equals(r.get("control_state")))return null;
        UUID id=(UUID)r.get("control_id");if(id==null)id=UUID.randomUUID();
        var ownership=new Ownership(id,admission,(UUID)r.get("operation_id"),number(r,"order_id"),(UUID)r.get("db_generation_id"),
            number(r,"base_payment_id"),number(r,"new_payment_id"),number(r,"transaction_id"),number(r,"create_request_id"),
            (String)r.get("provider_payment_id"),Math.toIntExact(number(r,"after_amount")),Math.toIntExact(number(r,"base_content_revision")),
            new CaptureConnectionIdentity((String)r.get("connection_id"),(String)r.get("provider_account_ref"),(String)r.get("mode"),(String)r.get("api_origin")));
        byte[] history=(byte[])r.get("history");byte[] intent=(byte[])r.get("intent_history");if(intent==null)intent=history;
        var context=new Context(ownership,0,number(r,"execution_version"),State.REQUESTED,intent,history,1,null);
        var results=jdbc.query("select order_change_operation_id,payment_id,transaction_type,status from payment_transactions where payment_id=? or order_change_operation_id=?",
            (rs,n)->new FinancialResult(rs.getObject(1,UUID.class),rs.getLong(2),rs.getString(3),rs.getString(4)),ownership.newPaymentId(),ownership.operationId());
        boolean oldCancel=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from order_change_financial_requests where operation_id=? and request_kind='OLD_CANCEL')",Boolean.class,ownership.operationId()));
        var local=new LocalFacts(ownership,context.executionVersion(),(String)r.get("old_status"),(String)r.get("new_status"),(String)r.get("auth_status"),
            r.get("completed_at")!=null,r.get("active_attempt_id")!=null,r.get("lease_until")!=null,r.get("switch_decided_at")!=null,
            r.get("content_change_history_id")!=null,oldCancel,results,history,null);
        long execution=r.get("intent_execution")==null?context.executionVersion():number(r,"intent_execution");
        long operation=r.get("intent_operation")==null?number(r,"operation_version"):number(r,"intent_operation");
        return new Prepared(context,local,new OrderEditFailureIntentService.Command(id,admission,execution,operation,ownership.generationId(),HexFormat.of().formatHex(intent)));
    }
    private static long number(Map<String,Object> r,String key){return ((Number)r.get(key)).longValue();}
    private void owner(UUID admission,CustomUserDetails actor){
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from order_edit_admissions where id=? and customer_id=? and actor_username=?)",Boolean.class,admission,actor.getId(),actor.getUsername())))
            throw new AccessDeniedException("Failure closure owner mismatch");
    }
    private static CustomUserDetails actor(){
        var a=SecurityContextHolder.getContext().getAuthentication();
        if(a==null||!a.isAuthenticated()||!(a.getPrincipal() instanceof CustomUserDetails p)||!p.isEnabled()
            ||a.getAuthorities().stream().noneMatch(x->x.getAuthority().equals("ROLE_USER")))throw new AccessDeniedException("Customer authentication required");
        return p;
    }
    private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Failure closure requires a standalone command");}
}
