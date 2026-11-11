package com.example.ecsite.service.order.evidence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.example.ecsite.service.order.evidence.OrderEditExecution.*;

/** Internal, same-DataSource repository. Canonical SQL entry points lock before all DML.
 * Read facts are not authorization to perform work. No direct control/admission UPDATE. */
final class OrderEditExecutionRepository {
    private final JdbcTemplate jdbc;
    OrderEditExecutionRepository(JdbcTemplate jdbc){this.jdbc=Objects.requireNonNull(jdbc);}
    void claim(Claim c){
        writable();
        jdbc.queryForObject("select f125_execution_initialize(?)",Object.class,c.admissionId());
        jdbc.queryForObject("select f125_execution_claim(?,?,?)",UUID.class,c.admissionId(),c.attemptId(),c.expectedVersion());
    }
    void claimRequeued(Claim c){
        writable();
        // This is the first lock in the Tx: runtime -> Order -> operation -> Payment -> ... -> control.
        jdbc.queryForObject("select f125_execution_lock(?)",Object.class,c.admissionId());
        jdbc.queryForObject("select f125_financial_assert_saved(?)",Object.class,c.admissionId());
        jdbc.queryForObject("select f125_reauth_assert_current(?)",Object.class,c.admissionId());
        var ready=jdbc.queryForList(OrderEditRequeuedAuthorization.FACTS+" and c.version=? and c.next_attempt_at<=clock_timestamp()",
            c.admissionId(),c.expectedVersion());
        if(ready.size()!=1)throw new OrderEditRequeuedAuthorization.NotEligible();
        claim(c);
    }
    void resolve(Resolution c){
        writable();jdbc.queryForObject("select f125_execution_resolve(?,?,?,?,?,?,false,?)",Object.class,
            c.admissionId(),c.attemptId(),c.token(),c.expectedVersion(),c.disposition().name(),c.reason(),c.resultId());
    }
    void suspendExpired(Expired c){
        writable();jdbc.queryForObject("select f125_execution_suspend_expired(?,?,?)",Object.class,c.admissionId(),c.resultId(),c.expectedVersion());
    }
    void close(OrderUnappliedClosure.Command c){
        writable();jdbc.queryForObject("select f125_execution_close_request(?,?,?,?,?)",Object.class,
            c.admissionId(),c.requestId(),c.expectedVersion(),c.terminal(),c.reason());
    }
    OrderUnappliedClosure.Evidence closure(UUID id){
        transaction();var codec=new OrderUnappliedClosureCodec();
        var rows=jdbc.query("select evidence_document::text,canonical,evidence_digest from order_edit_unapplied_evidences where admission_id=?",
            (r,n)->codec.verify(r.getString(1),r.getBytes(2),r.getBytes(3)),id);
        return rows.isEmpty()?null:rows.getFirst();
    }
    View read(UUID id){
        transaction();
        var rows=jdbc.query("""
            select a.id,a.db_generation_id,a.admission_state,a.execution_state,c.fencing_token,c.version,
              c.active_attempt_id,c.lease_until,c.next_attempt_at,c.automatic_until,c.get_attempt_count
            from order_edit_admissions a left join order_edit_execution_controls c on c.admission_id=a.id where a.id=?
            """,(r,n)->new View(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),State.valueOf(r.getString(4)),
                r.getObject(5,Long.class),r.getObject(6,Long.class),r.getObject(7,UUID.class),instant(r,8),instant(r,9),instant(r,10),
                r.getObject(11,Integer.class),List.of(),List.of()),id);
        if(rows.isEmpty())return null;var v=rows.getFirst();
        var attempts=jdbc.query("select id,db_generation_id,fencing_token,control_version,claimed_at,lease_until from order_edit_execution_attempts where admission_id=? order by control_version,id",
            (r,n)->new Attempt(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getLong(3),r.getLong(4),instant(r,5),instant(r,6)),id);
        var results=jdbc.query("select id,attempt_id,fencing_token,control_version,recovery,disposition,reason_code from order_edit_execution_attempt_results where admission_id=? order by control_version,id",
            (r,n)->new AttemptResult(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getLong(3),r.getLong(4),r.getBoolean(5),r.getString(6),r.getString(7)),id);
        return new View(v.admissionId(),v.generation(),v.admissionState(),v.state(),v.token(),v.version(),v.activeAttemptId(),v.leaseUntil(),v.nextAttemptAt(),v.automaticUntil(),v.attemptCount(),attempts,results);
    }
    private void transaction(){
        if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))
            throw new IllegalStateException("Same-DataSource transaction required");
    }
    private void writable(){transaction();if(TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        ||!"read committed".equals(jdbc.queryForObject("show transaction_isolation",String.class)))throw new IllegalStateException("Writable READ COMMITTED transaction required");}
    private static Instant instant(ResultSet r,int column)throws SQLException{var t=r.getObject(column,OffsetDateTime.class);return t==null?null:t.toInstant();}
}
