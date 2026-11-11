package com.example.ecsite.service.order.evidence;

import java.sql.Connection;
import java.util.Set;
import com.example.ecsite.exception.InvalidOrderStatusException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** No admission creation or bypass capability. Never call from financial result application. */
@Component
@Transactional(propagation=Propagation.MANDATORY)
public class OrderEditAdmissionGuard {
    private final JdbcTemplate jdbc;
    public OrderEditAdmissionGuard(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    /** Acquire/reenter Order first; callers must not enter with independently acquired Payment locks. */
    public void requireClear(long orderId) {
        if(orderId<=0 || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("Writable Order transaction required for admission gate");
        jdbc.execute((ConnectionCallback<Void>) c->{
            if(c.isReadOnly() || c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)
                throw new IllegalStateException("READ COMMITTED required for admission gate");
            return null;
        });
        // Two separate statements: a snapshot taken BEFORE a lock wait cannot certify the gate.
        if(jdbc.queryForList("select id from orders where id=? for update",Long.class,orderId).size()!=1)
            throw new IllegalStateException("Admission gate order missing");
        var blocked=jdbc.query("""
            select admission_state,execution_state,closed_at is not null,applied_operation_id is not null
            from order_edit_admissions where order_id=? order by id
            """,(rs,n)->blocks(rs.getString(1),rs.getString(2),rs.getBoolean(3),rs.getBoolean(4)),orderId);
        if(blocked.stream().anyMatch(Boolean::booleanValue))
            throw new InvalidOrderStatusException("未解決または不整合な注文編集受付があるため、この操作を開始できません。");
    }

    static boolean blocks(String admission,String execution,boolean closed,boolean applied) {
        if(!closed) return true;
        if("ACCEPTED".equals(admission) && "COMPLETED".equals(execution)) return !applied;
        if(applied) return true;
        if("REJECTED_LATE".equals(admission)) return !"NOT_READY".equals(execution);
        return !(Set.of("RECORDED_UNVERIFIED","UNPROVEN","ACCEPTED").contains(admission==null?"":admission)
            && Set.of("CLOSED_UNAPPLIED","FAILED_UNAPPLIED").contains(execution==null?"":execution));
    }
}
