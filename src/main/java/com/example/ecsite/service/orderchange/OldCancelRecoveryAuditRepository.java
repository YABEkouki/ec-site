package com.example.ecsite.service.orderchange;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
public class OldCancelRecoveryAuditRepository {
    private final JdbcTemplate jdbc;
    public OldCancelRecoveryAuditRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=true)
    public Optional<Map<String,Object>> find(UUID id){return jdbc.queryForList("select * from order_change_recovery_audits where request_id=?",id).stream().findFirst();}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Map<String,Object> start(OldCancelRecovery.Snapshot snapshot,OldCancelRecovery.Actor actor){
        var c=snapshot.command();
        jdbc.update("""
            insert into order_change_recovery_audits(request_id,order_id,operation_id,old_cancel_request_id,actor_id,actor_username,
              reason_code,original_reason,expected_version,old_flow_id,new_flow_id,context_digest,result_code)
            values(?,?,?,?,?,?,?,'CANCEL_FLOW_EVIDENCE_MISMATCH',?,?,?,?, 'STARTED') on conflict(request_id) do nothing
            """,c.requestId(),c.orderId(),c.operationId(),snapshot.cancellation().id(),actor.id(),actor.username(),c.reason(),c.expectedVersion(),
            snapshot.oldFlow(),snapshot.newFlow(),HexFormat.of().parseHex(snapshot.digest()));
        var row=locked(c.requestId());requireIdentity(row,c,actor);
        if(!Arrays.equals((byte[])row.get("context_digest"),HexFormat.of().parseHex(snapshot.digest())))
            throw new IllegalStateException("Recovery request context conflict");
        return row;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    Map<String,Object> locked(UUID id){return jdbc.queryForMap("select * from order_change_recovery_audits where request_id=? for update",id);}
    static void requireIdentity(Map<String,Object> row,OldCancelRecovery.Command c,OldCancelRecovery.Actor a){
        if(!Objects.equals(row.get("operation_id"),c.operationId())||((Number)row.get("order_id")).longValue()!=c.orderId()
            ||((Number)row.get("expected_version")).longValue()!=c.expectedVersion()||((Number)row.get("actor_id")).longValue()!=a.id()
            ||!Objects.equals(row.get("actor_username"),a.username())||!Objects.equals(row.get("reason_code"),c.reason()))
            throw new IllegalStateException("Recovery UUID content conflict");
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void hold(UUID id){jdbc.update("""
        update order_change_recovery_audits set result_code='HOLD',updated_at=clock_timestamp(),finished_at=clock_timestamp()
        where request_id=? and result_code in ('STARTED','HOLD')
        """,id);}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void conflict(UUID id){jdbc.update("""
        update order_change_recovery_audits set result_code='CONFLICT',updated_at=clock_timestamp(),finished_at=clock_timestamp()
        where request_id=? and result_code in ('STARTED','HOLD')
        """,id);}
    @Transactional(propagation=Propagation.MANDATORY)
    void applied(UUID id,long version,byte[] digest){
        if(jdbc.update("""
            update order_change_recovery_audits set result_code='COMMIT_PENDING',applied_version=?,evidence_digest=?,
                updated_at=clock_timestamp(),finished_at=clock_timestamp()
            where request_id=? and result_code in ('STARTED','HOLD') and applied_version is null
            """,version,digest,id)!=1)throw new IllegalStateException("Recovery audit application conflict");
    }
    @Transactional(propagation=Propagation.MANDATORY)
    void completed(UUID id,long version,long history){
        if(jdbc.update("""
            update order_change_recovery_audits set result_code='COMPLETED',completed_version=?,content_change_history_id=?,
              updated_at=clock_timestamp(),finished_at=clock_timestamp() where request_id=? and result_code='COMMIT_PENDING'
            """,version,history,id)!=1)throw new IllegalStateException("Recovery audit completion conflict");
    }
}
