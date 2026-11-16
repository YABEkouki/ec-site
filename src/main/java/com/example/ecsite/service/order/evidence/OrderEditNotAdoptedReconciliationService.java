package com.example.ecsite.service.order.evidence;

import java.sql.SQLException;
import java.util.*;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Feature129-B: the owned (bulk order-edit) counterpart of Feature129-A. A confirmed NOT_ADOPTED order
 * change (Feature128 A-2) whose new authorization later succeeds at PAY.JP must not sit forever waiting for
 * the customer to reopen the page. Unlike the non-owned path, no new SYSTEM abort-intent pipeline is needed
 * here: {@code decideForOrderEdit} already cancels the unneeded new authorization inline once
 * expired/switchClosed, and {@link OrderEditExecutionService#reconcile} already re-claims, resumes and
 * retries a stalled admission safely (it is a no-op whenever the Feature125 runtime is STOPPED). This service
 * keeps calling {@code reconcile} for eligible admissions — nothing else does.
 *
 * <p>Feature130-B: once that unwinding is complete (operation ABORTED, new authorization cancelled), the
 * admission is parked REVIEW_REQUIRED and would block shipping/capture forever. The second pass closes it
 * as CLOSED_UNAPPLIED through {@code f125_not_adopted_close_unapplied}, which re-verifies every fact under
 * the canonical locks and refuses anything not fully unwound. It never contacts the provider. */
@Service
@Transactional(propagation=Propagation.NEVER)
public class OrderEditNotAdoptedReconciliationService {
    private static final Logger log=LoggerFactory.getLogger(OrderEditNotAdoptedReconciliationService.class);
    private final JdbcTemplate jdbc;
    private final Supplier<OrderEditExecutionService> execution;
    private final TransactionTemplate write;
    /** The execution backend is a {@code @Lazy} bean that requires the Capture connection identity. Resolve it
     * only when there is reconciliation work, so the application (and the closure pass) still start without it. */
    @Autowired
    public OrderEditNotAdoptedReconciliationService(JdbcTemplate jdbc,ObjectProvider<OrderEditExecutionService> execution){
        this(jdbc,(Supplier<OrderEditExecutionService>)execution::getObject);
    }
    public OrderEditNotAdoptedReconciliationService(JdbcTemplate jdbc,OrderEditExecutionService execution){
        this(jdbc,(Supplier<OrderEditExecutionService>)()->Objects.requireNonNull(execution));
    }
    private OrderEditNotAdoptedReconciliationService(JdbcTemplate jdbc,Supplier<OrderEditExecutionService> execution){
        this.jdbc=Objects.requireNonNull(jdbc);this.execution=execution;
        write=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);write.setTimeout(10);
    }
    /** Returns the number of admissions for which reconcile() was attempted plus the number closed.
     * Failures are retried next run. */
    public int reconcileDue(int limit){
        if(limit<1||limit>1000)throw new IllegalArgumentException("Invalid reconciliation batch size");
        int attempted=0;
        var due=candidates(limit);
        OrderEditExecutionService backend=null;
        if(!due.isEmpty()){
            try{backend=execution.get();}
            catch(RuntimeException unavailable){log.warn("Order edit not-adopted reconciliation deferred: execution backend unavailable. admissions={}",due.size());}
        }
        if(backend!=null)for(var admission:due){
            try{backend.reconcile(admission);attempted++;}
            catch(RuntimeException failure){log.warn("Order edit not-adopted reconciliation deferred. admissionId={}",admission);}
        }
        return attempted+closeUnwound(limit);
    }
    private List<UUID> candidates(int limit){
        return jdbc.query("""
            select a.id from order_edit_admissions a
            join order_change_operations o on o.order_id=a.order_id and o.submission_key=a.submission_key
            join order_change_final_decisions d on d.operation_id=o.id
            where a.route='REAUTHORIZE' and o.execution_version=1 and o.execution_route='REAUTHORIZE'
              and d.outcome='NOT_ADOPTED' and o.state not in ('ABORTED','COMPLETED','MANUAL_REVIEW')
            order by d.final_at,a.order_id limit ?
            """,(r,n)->r.getObject(1,UUID.class),limit);
    }
    private record Unwound(UUID admission,long version) {}
    /** Feature130-B. A candidate only narrows the scan; the database function decides. */
    private int closeUnwound(int limit){
        var due=jdbc.query("""
            select a.id,c.version from order_edit_admissions a
            join order_edit_execution_controls c on c.admission_id=a.id
            join order_change_operations o on o.order_id=a.order_id and o.submission_key=a.submission_key
            join order_change_final_decisions d on d.operation_id=o.id
            where a.route='REAUTHORIZE' and a.closed_at is null and a.admission_state='ACCEPTED'
              and a.execution_state='REVIEW_REQUIRED' and a.reason_code='REAUTHORIZATION_ABORTED'
              and d.outcome='NOT_ADOPTED' and o.state='ABORTED' and o.switch_decided_at is null
              and c.active_attempt_id is null
            order by d.final_at,a.order_id limit ?
            """,(r,n)->new Unwound(r.getObject(1,UUID.class),r.getLong(2)),limit);
        int closed=0;
        for(var x:due){
            try{
                write.executeWithoutResult(s->jdbc.queryForObject("select f125_not_adopted_close_unapplied(?,?)",UUID.class,x.admission(),x.version()));
                closed++;
                log.info("Order edit admission closed after NOT_ADOPTED cancellation. admissionId={}",x.admission());
            }catch(RuntimeException failure){
                log.warn("Order edit not-adopted closure deferred. admissionId={} sqlState={}",x.admission(),sqlState(failure));
            }
        }
        return closed;
    }
    private static String sqlState(Throwable failure){
        for(var t=failure;t!=null;t=t.getCause())if(t instanceof SQLException sql)return sql.getSQLState();
        return null;
    }
}
