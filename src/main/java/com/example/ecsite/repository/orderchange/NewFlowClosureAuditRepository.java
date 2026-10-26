package com.example.ecsite.repository.orderchange;

import java.lang.reflect.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Storage only. No anomaly evaluation, episode transition, financial gateway or worker dependency.
 * Writes join a caller-owned short transaction. Acquire Order -> operation -> Payment IDs before audit rows;
 * callers must never enter holding audit locks and subsequently acquire financial locks.
 * Historical actor IDs are snapshots, not proof of administrator identity/authority.
 */
@Repository
@Transactional(readOnly=true)
public class NewFlowClosureAuditRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    public NewFlowClosureAuditRepository(JdbcTemplate jdbc){this.jdbc=jdbc;this.named=new NamedParameterJdbcTemplate(jdbc);}
    public record Issue(Long id,String subjectType,String subjectKey,UUID operationId,Long orderId,Long newPaymentId,String anomalyType,String severity,String status,long episodeNo,long detectionCount,Instant firstDetectedAt,Instant lastDetectedAt,UUID lastAttemptId,Instant acknowledgedAt,Long acknowledgedBy,Long assigneeId,long version,Instant resolvedAt,String resolutionReason,Instant createdAt,Instant updatedAt) {
        @Override public String toString(){return "ClosureAuditIssue[redacted]";}
    }
    public record Event(UUID id,long issueId,long episodeNo,String eventKind,UUID attemptId,UUID commandId,String actorType,Long actorId,Instant occurredAt,String safeReasonCode,byte[] contextDigest,String comment) {
        public Event{contextDigest=contextDigest==null?null:contextDigest.clone();}
        @Override public byte[] contextDigest(){return contextDigest==null?null:contextDigest.clone();}
        @Override public String toString(){return "ClosureAuditEvent[redacted]";}
    }
    public record Attempt(UUID id,UUID operationId,long orderId,Long newPaymentId,UUID runId,String triggerKind,byte[] contextDigest,String status,String resultCode,String reasonCode,long version,Instant startedAt,Instant finishedAt,Instant createdAt) {
        public Attempt{contextDigest=contextDigest==null?null:contextDigest.clone();}
        @Override public byte[] contextDigest(){return contextDigest==null?null:contextDigest.clone();}
        @Override public String toString(){return "ClosureAuditAttempt[redacted]";}
    }
    public record Run(UUID id,String runKind,String status,long version,Instant startedAt,Instant finishedAt,Instant heartbeatAt,Integer candidateCount,int processedCount,int closedCount,int retryCount,int conflictCount,int failureCount,boolean enabledSnapshot,Long durationMs,Instant createdAt) {
        @Override public String toString(){return "ClosureAuditRun[redacted]";}
    }
    public Optional<Issue> findIssue(long id){return one("select * from order_change_closure_issues where id=?",Issue.class,id);}
    public Optional<Issue> findIssue(String type,String key,String anomaly){return one("select * from order_change_closure_issues where subject_type=? and subject_key=? and anomaly_type=?",Issue.class,type,key,anomaly);}
    public List<Issue> openIssues(int limit){return rows("select * from order_change_closure_issues where status='OPEN' order by first_detected_at,id limit ?",Issue.class,bound(limit));}
    public List<Issue> issuesForOperation(UUID id){return rows("select * from order_change_closure_issues where operation_id=? order by id",Issue.class,id);}
    public List<Event> eventsForIssue(long id){return rows("select * from order_change_closure_issue_events where issue_id=? order by occurred_at,id",Event.class,id);}
    public Optional<Attempt> findAttempt(UUID id){return one("select * from order_change_closure_attempts where id=?",Attempt.class,id);}
    public List<Attempt> attemptsForOperation(UUID id){return rows("select * from order_change_closure_attempts where operation_id=? order by started_at,id",Attempt.class,id);}
    public List<Attempt> unfinishedAttempts(Instant cutoff,int limit){return rows("select * from order_change_closure_attempts where status='STARTED' and started_at<=? order by started_at,id limit ?",Attempt.class,Timestamp.from(cutoff),bound(limit));}
    public Optional<Run> findRun(UUID id){return one("select * from order_change_closure_runs where id=?",Run.class,id);}
    public List<Run> recentRuns(String kind,int limit){return rows("select * from order_change_closure_runs where run_kind=? order by started_at desc,id limit ?",Run.class,kind,bound(limit));}
    public List<Run> unfinishedRuns(Instant cutoff,int limit){return rows("select * from order_change_closure_runs where status='RUNNING' and heartbeat_at<=? order by heartbeat_at,id limit ?",Run.class,Timestamp.from(cutoff),bound(limit));}

    @Transactional(propagation=Propagation.MANDATORY)
    public Issue insertIssue(Issue row){
        writable();if(row.id()!=null)throw new IllegalArgumentException("New issue ID must be absent");
        ownership(row.operationId(),row.orderId(),row.newPaymentId());
        long id=insert("order_change_closure_issues",row,Long.class,true);return findIssue(id).orElseThrow();
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Event appendEvent(Event row){
        writable();var issue=findIssue(row.issueId()).orElseThrow();ownership(issue.operationId(),issue.orderId(),issue.newPaymentId());
        insert("order_change_closure_issue_events",row,UUID.class,false);return one("select * from order_change_closure_issue_events where id=?",Event.class,row.id()).orElseThrow();
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Attempt insertAttempt(Attempt row){
        writable();ownership(row.operationId(),row.orderId(),row.newPaymentId());insert("order_change_closure_attempts",row,UUID.class,false);return findAttempt(row.id()).orElseThrow();
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Run insertRun(Run row){writable();insert("order_change_closure_runs",row,UUID.class,false);return findRun(row.id()).orElseThrow();}
    /** Persistence CAS only. Terminal attempts cannot be reopened, including direct SQL. */
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean finishAttempt(UUID id,long version,String status,String result,String reason,Instant finished){
        writable();return jdbc.update("update order_change_closure_attempts set status=?,result_code=?,reason_code=?,finished_at=?,version=version+1 where id=? and version=? and status='STARTED'",
            status,result,reason,Timestamp.from(finished),id,version)==1;
    }
    /** Heartbeat/count/final-result storage only; this never starts a scan. */
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean updateRun(Run row,long expectedVersion){
        writable();return jdbc.update("""
            update order_change_closure_runs set status=?,finished_at=?,heartbeat_at=?,candidate_count=?,processed_count=?,
                closed_count=?,retry_count=?,conflict_count=?,failure_count=?,duration_ms=?,version=version+1
            where id=? and version=? and status='RUNNING'
            """,row.status(),timestamp(row.finishedAt()),timestamp(row.heartbeatAt()),row.candidateCount(),row.processedCount(),row.closedCount(),row.retryCount(),
                row.conflictCount(),row.failureCount(),row.durationMs(),row.id(),expectedVersion)==1;
    }
    private void ownership(UUID operation,Long order,Long payment){
        if(operation==null){if(order!=null||payment!=null)throw new IllegalArgumentException("Invalid system audit target");return;}
        if(order==null)throw new IllegalArgumentException("Missing audit order");
        jdbc.queryForObject("select id from orders where id=? for update",Long.class,order);
        var target=jdbc.queryForMap("select new_payment_id from order_change_operations where id=? and order_id=? for update",operation,order);
        jdbc.queryForList("select id from payments where order_id=? order by id for update",order);
        if(payment!=null&&!Objects.equals(payment,target.get("new_payment_id")))throw new IllegalArgumentException("Audit new Payment mismatch");
    }
    private static void writable(){
        if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("Audit storage requires a caller-owned writable transaction");
    }
    private static int bound(int limit){if(limit<1||limit>1000)throw new IllegalArgumentException("Invalid audit page size");return limit;}
    private static Timestamp timestamp(Instant value){return value==null?null:Timestamp.from(value);}
    private <T> Optional<T> one(String sql,Class<T> type,Object...args){return rows(sql,type,args).stream().findFirst();}
    private <T> List<T> rows(String sql,Class<T> type,Object...args){return jdbc.query(sql,(r,n)->map(r,type),args);}
    // Only these fixed record types are passed here; table names are private constants, never caller input.
    private <T> T insert(String table,Record row,Class<T> keyType,boolean generatedId){
        var values=new MapSqlParameterSource();var columns=new ArrayList<String>();var parameters=new ArrayList<String>();
        try{for(var c:row.getClass().getRecordComponents()){
            if(generatedId&&c.getName().equals("id"))continue;
            var column=snake(c.getName());Object value=c.getAccessor().invoke(row);
            if(value instanceof Instant instant)value=Timestamp.from(instant);
            columns.add(column);parameters.add(":"+column);values.addValue(column,value);
        }}catch(ReflectiveOperationException failure){throw new IllegalStateException("Audit record mapping unavailable");}
        return Objects.requireNonNull(named.queryForObject("insert into "+table+"("+String.join(",",columns)+") values("+String.join(",",parameters)+") returning id",values,keyType));
    }
    private static String snake(String name){return name.replaceAll("([a-z])([A-Z])","$1_$2").toLowerCase(Locale.ROOT);}
    private static <T>T map(ResultSet r,Class<T> type)throws SQLException{
        var parts=type.getRecordComponents();var types=new Class<?>[parts.length];var values=new Object[parts.length];
        for(int i=0;i<parts.length;i++){
            types[i]=parts[i].getType();String key=snake(parts[i].getName());
            if(types[i]==Instant.class){var time=r.getTimestamp(key);values[i]=time==null?null:time.toInstant();}
            else if(types[i]==byte[].class)values[i]=r.getBytes(key);
            else if(types[i]==long.class)values[i]=r.getLong(key);
            else if(types[i]==int.class)values[i]=r.getInt(key);
            else if(types[i]==boolean.class)values[i]=r.getBoolean(key);
            else values[i]=r.getObject(key,types[i]);
        }
        try{return type.getDeclaredConstructor(types).newInstance(values);}
        catch(ReflectiveOperationException failure){throw new IllegalStateException("Audit row mapping unavailable");}
    }
}
