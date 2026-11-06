package com.example.ecsite.service.order.evidence;

import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.payment.capture.CaptureConnectionIdentity;
import com.example.ecsite.payment.payjp.OrderAuthorizationObservation;
import static com.example.ecsite.service.order.evidence.OrderEditExecution.*;
import static com.example.ecsite.service.order.evidence.OrderEditApplicationPlan.*;

/** Explicit internal caller only: no Bean, worker, Controller or scheduled execution.
 * Financial POSTs use only the existing Feature124 sealed, one-time dispatcher. */
public final class OrderEditExecutionService {
    public enum Status { COMPLETED, BLOCKED, HELD, COMMIT_UNKNOWN }
    public record Result(Status status,UUID admissionId,UUID operationId,Long historyId) {}
    private final OrderEditExecutionCoordinator control;
    private final OrderEditApplicationTransactions applications;
    private final PaymentGateway gateway;
    private final com.example.ecsite.service.orderchange.OrderReauthorizationService reauthorization;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate read;
    public OrderEditExecutionService(DataSource source,PaymentGateway gateway,CaptureConnectionIdentity connection){this(source,gateway,connection,new DataSourceTransactionManager(source));}
    public OrderEditExecutionService(DataSource source,PaymentGateway gateway,CaptureConnectionIdentity connection,PlatformTransactionManager manager){this(source,gateway,connection,manager,null);}
    public OrderEditExecutionService(DataSource source,PaymentGateway gateway,CaptureConnectionIdentity connection,com.example.ecsite.service.orderchange.OrderReauthorizationService reauthorization){this(source,gateway,connection,new DataSourceTransactionManager(source),reauthorization);}
    public OrderEditExecutionService(DataSource source,PaymentGateway gateway,CaptureConnectionIdentity connection,PlatformTransactionManager manager,com.example.ecsite.service.orderchange.OrderReauthorizationService reauthorization){
        this.reauthorization=reauthorization;
        this.gateway=Objects.requireNonNull(gateway);jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(5);
        control=new OrderEditExecutionCoordinator(source);applications=new OrderEditApplicationTransactions(source,connection,manager);
        read=new TransactionTemplate(new DataSourceTransactionManager(source));read.setReadOnly(true);read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public Result execute(UUID id){return execute(id,false);}
    /** Browser automation may claim only version zero. A concurrent earlier attempt makes CAS fail. */
    public Result executeInitial(UUID id){return execute(id,true);}
    private Result execute(UUID id,boolean initialOnly){
        outside();Objects.requireNonNull(id);var previous=recover(id);if(previous.status()==Status.COMPLETED)return previous;
        final Info info;
        try{info=read.execute(s->{var rows=jdbc.queryForList("select a.admission_state,a.execution_state,coalesce(c.version,0) as version from order_edit_admissions a left join order_edit_execution_controls c on c.admission_id=a.id where a.id=?",id);
            if(rows.size()!=1||!"ACCEPTED".equals(rows.getFirst().get("admission_state"))||!"QUEUED".equals(rows.getFirst().get("execution_state")))throw new IllegalStateException("Not queued");var row=jdbc.queryForMap("select order_id,route from order_edit_admissions where id=?",id);
            return new Info(((Number)row.get("order_id")).longValue(),(String)row.get("route"),((Number)rows.getFirst().get("version")).longValue());});}
        catch(RuntimeException unavailable){return result(Status.BLOCKED,id);}
        if(initialOnly&&info.version()!=0)return result(Status.BLOCKED,id);
        if("REAUTHORIZE".equals(info.route())&&reauthorization==null)return result(Status.BLOCKED,id);
        var claim=new Claim(id,UUID.randomUUID(),info.version());var claimed=control.claim(claim);
        if(claimed.outcome()!=Outcome.RECORDED||claimed.view()==null||!claim.attemptId().equals(claimed.view().activeAttemptId()))return result(claimed.outcome()==Outcome.COMMIT_UNKNOWN?Status.COMMIT_UNKNOWN:Status.BLOCKED,id);
        var owner=new Ownership(id,claim.attemptId(),claimed.view().token(),claimed.view().version());
        if("REAUTHORIZE".equals(info.route()))return executeReauthorization(owner,info.order());
        final OrderEditApplicationTransactions.Prepared prepared;
        try{prepared=applications.prepare(owner);}catch(RuntimeException invalid){return result(hold(owner,Disposition.REVIEW,"APPLICATION_PRECHECK_FAILED"),id);}
        final OrderAuthorizationObservation observation;
        try{observation=gateway.observeOrderAuthorization(prepared.expected());if(observation==null)throw new IllegalStateException("Missing observation");}
        catch(RuntimeException unknown){return result(hold(owner,Disposition.UNKNOWN,"AUTHORIZATION_OBSERVATION_UNKNOWN"),id);}
        try{var applied=applications.apply(prepared,observation);return new Result(Status.COMPLETED,id,applied.operationId(),applied.historyId());}
        catch(RuntimeException ambiguous){var saved=recover(id);return saved.status()==Status.COMPLETED?saved:result(Status.COMMIT_UNKNOWN,id);}
    }
    /** Read persisted completion, never infer rollback from absence and never replay apply automatically. */
    public Result recover(UUID id){
        outside();Objects.requireNonNull(id);
        try{return read.execute(s->{
            var rows=jdbc.query("""
              select r.application_operation_id,r.application_history_id,r.application_fingerprint,
                case when o.execution_route='REAUTHORIZE' then e.start_fingerprint else h.after_fingerprint end,
                case when o.execution_route='REAUTHORIZE' then e.start_snapshot else h.after_snapshot end
              from order_edit_admissions a join order_edit_execution_attempt_results r on r.admission_id=a.id and r.disposition='APPLIED'
              join order_change_operations o on o.id=r.application_operation_id and o.order_id=a.order_id and o.state='COMPLETED'
              join order_content_change_histories h on h.id=r.application_history_id and h.order_id=a.order_id
              left join order_payment_evidence_epochs e on e.origin_operation_id=o.id and e.origin_history_id=h.id and e.order_id=a.order_id and e.payment_id=o.new_payment_id and e.origin_kind='REAUTH_COMPLETED'
              where a.id=? and a.execution_state='COMPLETED' and a.applied_operation_id=o.id
              """,(r,n)->{
                byte[] fp=r.getBytes(3);var codec=new OrderContentSnapshotCodec();var snapshot=codec.decode(r.getBytes(5));
                if(!Arrays.equals(fp,r.getBytes(4))||!HexFormat.of().formatHex(fp).equals(codec.fingerprint(snapshot)))throw new IllegalStateException("Invalid completion evidence");
                return new Result(Status.COMPLETED,id,r.getObject(1,UUID.class),r.getLong(2));},id);
            return rows.size()==1?rows.getFirst():result(Status.COMMIT_UNKNOWN,id);
        });}catch(RuntimeException unavailable){return result(Status.COMMIT_UNKNOWN,id);}
    }
    private record Info(long order,String route,long version) {}
    private Result executeReauthorization(Ownership owner,long order){
        UUID operation;
        try{operation=applications.reauthorizationOperation(owner.admissionId());
            if(operation==null){
                var initial=applications.prepare(owner);
                final OrderAuthorizationObservation observation;
                try{observation=gateway.observeOrderAuthorization(initial.expected());if(observation==null)throw new IllegalStateException("Missing old observation");}
                catch(RuntimeException unavailable){return result(hold(owner,Disposition.UNKNOWN,"OLD_AUTH_OBSERVATION_UNKNOWN"),owner.admissionId());}
                operation=applications.initializeReauthorization(initial,observation);
            }
        }catch(RuntimeException unconfirmed){return result(hold(owner,Disposition.UNKNOWN,"REAUTHORIZATION_INITIALIZATION_UNCONFIRMED"),owner.admissionId());}
        final com.example.ecsite.service.orderchange.OrderReauthorizationTransactions.View finance;
        try{finance=reauthorization.advanceForOrderEdit(order,operation,owner);}
        catch(RuntimeException unconfirmed){return result(hold(owner,Disposition.UNKNOWN,"REAUTHORIZATION_RESULT_UNCONFIRMED"),owner.admissionId());}
        if(!"COMMIT_PENDING".equals(finance.state()))return result(hold(owner,"ABORTED".equals(finance.state())?Disposition.REVIEW:Disposition.UNKNOWN,
            "ABORTED".equals(finance.state())?"REAUTHORIZATION_ABORTED":"REAUTHORIZATION_WAITING"),owner.admissionId());
        final OrderEditApplicationTransactions.Prepared prepared;
        final OrderAuthorizationObservation observation;
        try{prepared=applications.prepareReauthorized(owner,operation);observation=gateway.observeOrderAuthorization(prepared.expected());if(observation==null)throw new IllegalStateException("Missing new observation");}
        catch(RuntimeException unavailable){return result(hold(owner,Disposition.UNKNOWN,"NEW_AUTH_OBSERVATION_UNCONFIRMED"),owner.admissionId());}
        try{var applied=applications.apply(prepared,observation);return new Result(Status.COMPLETED,owner.admissionId(),applied.operationId(),applied.historyId());}
        catch(RuntimeException ambiguous){var saved=recover(owner.admissionId());return saved.status()==Status.COMPLETED?saved:result(Status.COMMIT_UNKNOWN,owner.admissionId());}
    }
    /** Explicit GET-only convergence before a new fenced attempt; never resends an uncertain POST. */
    public Result reconcile(UUID admission){
        outside();Objects.requireNonNull(admission);var completed=recover(admission);if(completed.status()==Status.COMPLETED)return completed;
        if(reauthorization==null)return result(Status.BLOCKED,admission);
        try{
            UUID operation=applications.reauthorizationOperation(admission);
            long order=read.execute(s->jdbc.queryForObject("select order_id from order_edit_admissions where id=? and route='REAUTHORIZE'",Long.class,admission));
            if(operation!=null)reauthorization.reconcile(order,operation); // Existing GET/result application only.
            var view=read.execute(s->new OrderEditExecutionRepository(jdbc).read(admission));
            if(view==null)return result(Status.COMMIT_UNKNOWN,admission);
            if(view.activeAttemptId()!=null){
                boolean expired=read.execute(s->jdbc.queryForObject("select lease_until<=clock_timestamp() from order_edit_execution_controls where admission_id=?",Boolean.class,admission));
                if(!expired)return result(Status.COMMIT_UNKNOWN,admission);
                var fenced=control.recoverExpired(new Expired(admission,UUID.randomUUID(),view.version()));
                if(fenced.outcome()!=Outcome.RECORDED||fenced.view()==null)return result(Status.COMMIT_UNKNOWN,admission);view=fenced.view();
            }
            if(view.state()==State.RECOVERY_PENDING||view.state()==State.REVIEW_REQUIRED)applications.resumeReauthorization(admission,view.version());
            if(Boolean.TRUE.equals(read.execute(s->jdbc.queryForObject("select next_attempt_at>clock_timestamp() from order_edit_execution_controls where admission_id=?",Boolean.class,admission))))return result(Status.HELD,admission);
            return execute(admission);
        }catch(RuntimeException unavailable){return result(Status.COMMIT_UNKNOWN,admission);}
    }
    private Status hold(Ownership owner,Disposition disposition,String reason){
        var saved=control.resolve(new Resolution(owner.admissionId(),UUID.randomUUID(),owner.attemptId(),owner.token(),owner.controlVersion(),disposition,reason));
        return saved.outcome()==Outcome.RECORDED&&saved.view()!=null&&saved.view().activeAttemptId()==null
            &&(saved.view().state()==State.RECOVERY_PENDING||saved.view().state()==State.REVIEW_REQUIRED)?Status.HELD:Status.COMMIT_UNKNOWN;
    }
    private static Result result(Status status,UUID id){return new Result(status,id,null,null);}
    private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Execution must run outside transaction");}
}
