package com.example.ecsite.service.order.evidence;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Saved-state hint only. No locks, transport, permits or writes; the start POST must revalidate. */
@Service
public class OrderEditCardStartEligibility {
    private final JdbcTemplate jdbc;
    public OrderEditCardStartEligibility(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
    public String target(long order,long customer,UUID admission){
        var rows=jdbc.queryForList("""
            select op.id as operation_id,p.id as payment_id,t.id as authorization_id
            from order_edit_admissions a join orders o on o.id=a.order_id and o.user_id=a.customer_id
            join order_change_operations op on op.order_id=a.order_id and op.submission_key=a.submission_key
            join order_edit_execution_controls c on c.admission_id=a.id and c.order_id=a.order_id
            join order_edit_runtime_control rc on rc.id=1 and rc.generation_id=a.db_generation_id
            join payments p on p.id=op.new_payment_id and p.order_id=a.order_id
            join payments old on old.id=op.old_payment_id and old.order_id=a.order_id
            join payment_transactions t on t.payment_id=p.id and t.order_change_operation_id=op.id
            where a.id=? and a.order_id=? and a.customer_id=? and a.route='REAUTHORIZE'
              and a.admission_state='ACCEPTED' and a.execution_state='RECOVERY_PENDING'
              and a.closed_at is null and a.applied_operation_id is null
              and rc.state in ('RUNNING','ISOLATED_TEST') and f125_runtime_available()
              and c.db_generation_id=a.db_generation_id and c.policy_version=1
              and c.active_attempt_id is null and c.lease_until is null and c.automatic_until>clock_timestamp()
              and op.state='AUTHORIZING' and op.switch_decided_at is null and op.abort_requested_at is null
              and op.application_xid is null and op.browser_confirmation_issued_at is null
              and f125_reauth_is_owned(a.order_id,op.id)
              and p.status='PENDING' and nullif(trim(p.provider_payment_id),'') is not null and old.status='AUTHORIZED'
              and p.amount=a.after_amount and t.status='PENDING' and t.transaction_type='AUTHORIZE'
              and t.purpose='ORDER_CHANGE_AUTHORIZATION' and t.amount=a.after_amount
              and t.order_content_revision=a.base_content_revision+1 and t.order_change_order_id=a.order_id
              and exists(select 1 from order_edit_execution_attempt_results r where r.admission_id=a.id
                and r.disposition='UNKNOWN' and r.reason_code='REAUTHORIZATION_WAITING'
                and r.control_version=c.version-1 and r.fencing_token<c.fencing_token)
              and not exists(select 1 from order_edit_execution_attempts x where x.admission_id=a.id
                and (x.fencing_token>=c.fencing_token or not exists(select 1 from order_edit_execution_attempt_results r
                  where r.attempt_id=x.id and r.admission_id=a.id and r.disposition='UNKNOWN'
                    and r.reason_code='REAUTHORIZATION_WAITING')))
              and not exists(select 1 from payment_transactions x join payments xp on xp.id=x.payment_id
                where xp.order_id=a.order_id and x.status='PENDING' and x.id<>t.id)
              and not exists(select 1 from order_change_financial_requests q
                left join order_change_request_dispatches d on d.request_id=q.id
                where q.order_id=a.order_id and d.state is distinct from 'OBSERVED')
            """,admission,order,customer);
        if(rows.size()!=1)return null;
        // These existing assertion functions are non-locking and read-only; they validate
        // immutable Tx-A/Tx-B evidence and the owned financial progression, not a rebuilt baseline.
        jdbc.queryForObject("select f125_execution_assert_basis(?)",Object.class,admission);
        var operation=(UUID)rows.getFirst().get("operation_id");
        new OrderEditReauthorizationBridge(jdbc).requireHeld(order,operation);
        var r=rows.getFirst();
        return admission+":"+operation+":"+r.get("payment_id")+":"+r.get("authorization_id");
    }
}
