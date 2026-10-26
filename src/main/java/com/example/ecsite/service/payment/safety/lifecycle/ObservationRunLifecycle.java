package com.example.ecsite.service.payment.safety.lifecycle;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import com.example.ecsite.service.payment.safety.payload.*;
import com.example.ecsite.service.payment.safety.payload.ObservationLifecycleCommands.*;
import static com.example.ecsite.service.payment.safety.lifecycle.ObservationRunReceipt.Status.*;

/** Explicit maintenance-only entry point, deliberately NOT a Spring bean/runner. Supply a dedicated
 * observer DataSource. No development defaults, target queries, financial calls or background worker.
 * This class alone owns connections/commit/rollback. Retain Prepared Commands for retries.
 */
public final class ObservationRunLifecycle {
    private final DataSource source;
    private final JdbcObservationRunRepository repository=new JdbcObservationRunRepository();
    public ObservationRunLifecycle(DataSource observerSource){source=Objects.requireNonNull(observerSource);}
    public Command prepareCreate(ObservationRunRequest request,UUID owner) {
        return ObservationLifecycleCommands.create(request,owner,databaseTime(request));
    }
    public Command prepare(ObservationRunRequest request,Action action,UUID control,UUID before,UUID after,
            long epoch,long expectedVersion,ObservationRunRequest.Actor actor) {
        return ObservationLifecycleCommands.prepare(request,action,control,before,after,epoch,expectedVersion,actor,databaseTime(request));
    }
    private java.time.Instant databaseTime(ObservationRunRequest request) {
        Objects.requireNonNull(request);
        try(var c=source.getConnection()) {configure(c,request.manifest().policy());var time=JdbcObservationRunRepository.now(c);c.rollback();return time;}
        catch(SQLException failure){throw new IllegalStateException("Observation command preparation failed");}
    }
    public ObservationRunReceipt execute(Command command) {
        Objects.requireNonNull(command);Connection c=null;boolean commitSent=false;
        try {
            c=source.getConnection();configure(c,command.request().manifest().policy());
            var result=repository.execute(c,command);
            if(result.status()==APPLIED || result.status()==REPLAYED){commitSent=true;c.commit();}
            else c.rollback();
            return result;
        }catch(JdbcObservationRunRepository.IntegrityFailure | IllegalArgumentException corrupt) {
            rollback(c);return failure(command,INTEGRITY_ERROR,null);
        }catch(SQLException failure) {
            boolean rolledBack=rollback(c);String state=safeState(failure);
            if(!commitSent && rolledBack && state!=null && !state.startsWith("08") && !Set.of("55P03","57014","23505").contains(state))return failure(command,STORAGE_FAILED,state);
            close(c);c=null;
            return resolve(command); // new connection; never repeat a write as unknown-outcome recovery
        }finally {close(c);}
    }
    public ObservationRunReceipt resolve(Command command) {
        Objects.requireNonNull(command);Connection c=null;
        try {
            c=source.getConnection();configure(c,command.request().manifest().policy());
            var result=repository.resolve(c,command);c.rollback();return result;
        }catch(JdbcObservationRunRepository.IntegrityFailure | IllegalArgumentException corrupt) {
            rollback(c);return failure(command,INTEGRITY_ERROR,null);
        }catch(SQLException failure) {
            rollback(c);return failure(command,UNKNOWN,safeState(failure));
        }finally {close(c);}
    }
    static void configure(Connection c,ObservationRunRequest.Policy policy) throws SQLException {
        c.setReadOnly(false);c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);
        try(var q=c.prepareStatement("select set_config('lock_timeout',?,true),set_config('statement_timeout',?,true),set_config('transaction_timeout',?,true)")) {
            q.setString(1,policy.lockTimeoutMillis()+"ms");q.setString(2,policy.statementTimeoutSeconds()+"s");q.setString(3,policy.transactionTimeoutSeconds()+"s");
            try(var r=q.executeQuery()){if(!r.next())throw new SQLException("Observer timeouts unavailable","08000");}
        }
    }
    private static ObservationRunReceipt failure(Command command,ObservationRunReceipt.Status status,String state) {
        return new ObservationRunReceipt(status,null,command.event().map(ObservationEventFactory.Event::eventUuid).orElse(null),command.event().map(ObservationEventFactory.Event::recordDigest).orElse(null),state);
    }
    private static String safeState(SQLException failure){String state=failure.getSQLState();return state!=null && state.matches("[0-9A-Z]{5}")?state:null;}
    private static boolean rollback(Connection c){if(c==null)return false;try{c.rollback();return true;}catch(SQLException ignored){return false;}}
    private static void close(Connection c){if(c!=null)try{c.close();}catch(SQLException ignored){/* a close error never changes an acknowledged commit */}}
}
