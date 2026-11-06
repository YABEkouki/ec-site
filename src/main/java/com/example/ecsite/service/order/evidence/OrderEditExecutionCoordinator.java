package com.example.ecsite.service.order.evidence;

import java.sql.SQLException;
import java.util.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import static com.example.ecsite.service.order.evidence.OrderEditExecution.*;

/** Standalone, deliberately not a Bean/worker/API. Never calls transport or applies an order.
 * Commands use an independent same-DataSource RC transaction; recovery only observes durable
 * immutable IDs in readonly RR. A commit exception never causes an automatic command retry. */
public final class OrderEditExecutionCoordinator {
    private final OrderEditExecutionRepository store;
    private final TransactionTemplate writeTx,readTx;
    public OrderEditExecutionCoordinator(DataSource source){this(source,new DataSourceTransactionManager(source));}
    /** Manager must bind this exact DataSource (also supports isolated commit-response fault injection). */
    public OrderEditExecutionCoordinator(DataSource source,PlatformTransactionManager manager){
        var jdbc=new JdbcTemplate(Objects.requireNonNull(source));jdbc.setQueryTimeout(5);store=new OrderEditExecutionRepository(jdbc);
        writeTx=template(manager,false);readTx=template(new DataSourceTransactionManager(source),true);
    }
    private static TransactionTemplate template(PlatformTransactionManager manager,boolean read){
        var tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(read?TransactionDefinition.ISOLATION_REPEATABLE_READ:TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setReadOnly(read);tx.setTimeout(10);return tx;
    }
    public Result claim(Claim c){outside();Objects.requireNonNull(c);return write(c.admissionId(),()->store.claim(c));}
    public Result claimRequeued(Claim c){outside();Objects.requireNonNull(c);return write(c.admissionId(),()->store.claimRequeued(c));}
    public Result resolve(Resolution c){outside();Objects.requireNonNull(c);return write(c.admissionId(),()->store.resolve(c));}
    /** An expired lease only causes fencing/suspension, never an automatic retry or gate release. */
    public Result recoverExpired(Expired c){outside();Objects.requireNonNull(c);return write(c.admissionId(),()->store.suspendExpired(c));}
    public Result recoverClaim(Claim c){
        outside();Objects.requireNonNull(c);return observed(c.admissionId(),()->{
            var v=store.read(c.admissionId());if(v==null)return result(Outcome.NOT_OBSERVED,null,"RECEIPT_NOT_VISIBLE");
            var a=v.attempts().stream().filter(x->x.id().equals(c.attemptId())).findFirst();
            if(a.isEmpty())return result(Outcome.NOT_OBSERVED,v,"CLAIM_NOT_VISIBLE_NOT_PROOF_OF_ROLLBACK");
            return result(a.get().version()-1==c.expectedVersion()?Outcome.OBSERVED:Outcome.CONFLICT,v,"IMMUTABLE_CLAIM_OBSERVATION_ONLY");
        });
    }
    public Result recoverResult(Resolution c){
        outside();Objects.requireNonNull(c);return observed(c.admissionId(),()->{
            var v=store.read(c.admissionId());if(v==null)return result(Outcome.NOT_OBSERVED,null,"RECEIPT_NOT_VISIBLE");
            var r=v.results().stream().filter(x->x.id().equals(c.resultId())).findFirst();
            if(r.isEmpty())return result(Outcome.NOT_OBSERVED,v,"RESULT_NOT_VISIBLE_NOT_PROOF_OF_ROLLBACK");
            var x=r.get();boolean same=x.attemptId().equals(c.attemptId())&&x.token()==c.token()&&x.version()==c.expectedVersion()
                &&!x.recovery()&&x.disposition().equals(c.disposition().name())&&x.reason().equals(c.reason());
            return result(same?Outcome.OBSERVED:Outcome.CONFLICT,v,"IMMUTABLE_RESULT_OBSERVATION_ONLY");
        });
    }
    public Result recoverExpiredResult(Expired c){
        outside();Objects.requireNonNull(c);return observed(c.admissionId(),()->{
            var v=store.read(c.admissionId());if(v==null)return result(Outcome.NOT_OBSERVED,null,"RECEIPT_NOT_VISIBLE");
            var r=v.results().stream().filter(x->x.id().equals(c.resultId())).findFirst();
            if(r.isEmpty())return result(Outcome.NOT_OBSERVED,v,"RECOVERY_NOT_VISIBLE_NOT_PROOF_OF_ROLLBACK");
            var x=r.get();boolean same=x.version()==c.expectedVersion()&&x.recovery()&&x.disposition().equals("UNKNOWN")&&x.reason().equals("EXPIRED_WORKER_UNCERTAIN");
            return result(same?Outcome.OBSERVED:Outcome.CONFLICT,v,"IMMUTABLE_RECOVERY_OBSERVATION_ONLY");
        });
    }
    public Result closeUnapplied(OrderUnappliedClosure.Command c){
        outside();Objects.requireNonNull(c);return write(c.admissionId(),()->store.close(c));
    }
    /** Observes immutable evidence only. Absence is never proof of rollback and triggers no retry. */
    public Result recoverClosure(OrderUnappliedClosure.Command c){
        outside();Objects.requireNonNull(c);return observed(c.admissionId(),()->{
            var v=store.read(c.admissionId());var proof=store.closure(c.admissionId());
            if(proof==null)return result(Outcome.NOT_OBSERVED,v,"CLOSURE_NOT_VISIBLE_NOT_PROOF_OF_ROLLBACK");
            return result(proof.matches(c)?Outcome.OBSERVED:Outcome.CONFLICT,v,"IMMUTABLE_CLOSURE_OBSERVATION_ONLY");
        });
    }
    private Result write(UUID admission,Runnable command){
        try{return writeTx.execute(s->{command.run();return result(Outcome.RECORDED,store.read(admission),"DURABLE_CONTROL_RECORD_NOT_EXECUTION_PERMISSION");});}
        catch(RuntimeException failure){
            var view=safeRead(admission);
            return result(sqlState(failure,"55000")?Outcome.RUNTIME_UNAVAILABLE:
                failure instanceof OrderEditRequeuedAuthorization.NotEligible||sqlState(failure,"23514")||sqlState(failure,"23505")?Outcome.BLOCKED:Outcome.COMMIT_UNKNOWN,view,"COMMAND_NOT_CONFIRMED_NO_AUTOMATIC_RETRY");
        }
    }
    private Result observed(UUID admission,Supplier<Result> read){try{return readTx.execute(s->read.get());}catch(RuntimeException failure){return result(Outcome.COMMIT_UNKNOWN,null,"OBSERVATION_UNAVAILABLE");}}
    private View safeRead(UUID id){try{return readTx.execute(s->store.read(id));}catch(RuntimeException failure){return null;}}
    private static Result result(Outcome o,View v,String reason){return new Result(o,v,reason);}
    private static boolean sqlState(Throwable error,String state){for(Throwable e=error;e!=null;e=e.getCause())if(e instanceof SQLException s&&state.equals(s.getSQLState()))return true;return false;}
    private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Execution coordinator must run outside caller transaction");}
}
