package com.example.ecsite.service.order.evidence;

import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Feature129-B: the owned (bulk order-edit) counterpart of Feature129-A. A confirmed NOT_ADOPTED order
 * change (Feature128 A-2) whose new authorization later succeeds at PAY.JP must not sit forever waiting for
 * the customer to reopen the page. Unlike the non-owned path, no new SYSTEM abort-intent pipeline is needed
 * here: {@code decideForOrderEdit} already cancels the unneeded new authorization inline once
 * expired/switchClosed, and {@link OrderEditExecutionService#reconcile} already re-claims, resumes and
 * retries a stalled admission safely (it is a no-op whenever the Feature125 runtime is STOPPED). This service
 * only has to keep calling {@code reconcile} for eligible admissions — nothing else does. */
@Service
@Transactional(propagation=Propagation.NEVER)
public class OrderEditNotAdoptedReconciliationService {
    private static final Logger log=LoggerFactory.getLogger(OrderEditNotAdoptedReconciliationService.class);
    private final JdbcTemplate jdbc;
    private final OrderEditExecutionService execution;
    public OrderEditNotAdoptedReconciliationService(JdbcTemplate jdbc,OrderEditExecutionService execution){
        this.jdbc=jdbc;this.execution=execution;
    }
    /** Returns the number of admissions for which reconcile() was attempted. Failures are retried next run. */
    public int reconcileDue(int limit){
        if(limit<1||limit>1000)throw new IllegalArgumentException("Invalid reconciliation batch size");
        int attempted=0;
        for(var admission:candidates(limit)){
            try{execution.reconcile(admission);attempted++;}
            catch(RuntimeException failure){log.warn("Order edit not-adopted reconciliation deferred. admissionId={}",admission);}
        }
        return attempted;
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
}
