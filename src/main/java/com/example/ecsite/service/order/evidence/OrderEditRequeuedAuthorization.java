package com.example.ecsite.service.order.evidence;

/** Read-only eligibility shared by display hints and the locked claim guard. Never a financial permit. */
final class OrderEditRequeuedAuthorization {
    private OrderEditRequeuedAuthorization() {}
    static final class NotEligible extends IllegalStateException {
        NotEligible(){super("Requeued authorization is not eligible for this claim");}
    }
    static final long MAX_WAIT_MILLIS=10_000;
    static final String FACTS="""
        select c.version,op.version as operation_version,
          ceil(greatest(0,extract(epoch from c.next_attempt_at-clock_timestamp())*1000))::bigint as wait_millis,
          floor(extract(epoch from c.automatic_until-clock_timestamp())*1000)::bigint as remaining_millis
        from order_edit_admissions a join orders o on o.id=a.order_id and o.user_id=a.customer_id
        join order_edit_execution_controls c on c.admission_id=a.id and c.order_id=a.order_id
        join order_change_operations op on op.order_id=a.order_id and op.submission_key=a.submission_key
        join order_edit_runtime_control rc on rc.id=1 and rc.generation_id=a.db_generation_id
        where a.id=? and a.admission_state='ACCEPTED' and a.execution_state='QUEUED'
          and a.route='REAUTHORIZE' and a.closed_at is null and a.applied_operation_id is null
          and rc.state in ('RUNNING','ISOLATED_TEST') and f125_runtime_available()
          and c.db_generation_id=a.db_generation_id and c.policy_version=1 and c.version>0
          and c.active_attempt_id is null and c.lease_until is null and c.get_attempt_count<c.max_get_attempts
          and c.automatic_until>clock_timestamp() and c.next_attempt_at<c.automatic_until
          and c.next_attempt_at<=clock_timestamp()+interval '10 seconds'
          and o.status='ORDERED' and o.shipping_preparation_started_at is null and o.shipment_capture_payment_id is null
          and o.content_revision=a.base_content_revision and o.total_amount=a.before_amount
          and o.active_payment_id=a.base_payment_id and o.current_evidence_epoch_id=a.base_evidence_epoch_id
          and o.evidence_head_history_id is not distinct from a.base_evidence_history_id
          and op.state in ('AUTHORIZED','COMMIT_PENDING') and op.abort_requested_at is null and op.application_xid is null
          and f125_reauth_is_owned(a.order_id,op.id) and f125_reauth_conclusive(a.id)
          and exists(select 1 from order_edit_execution_attempt_results r
            where r.admission_id=a.id and r.control_version=c.version-1 and r.disposition='REQUEUE'
              and r.reason_code='REAUTH_FINANCE_CONCLUSIVE' and r.recovery and r.fencing_token<c.fencing_token)
          and not exists(select 1 from order_edit_execution_attempts t where t.admission_id=a.id
            and (t.fencing_token>=c.fencing_token or not exists(select 1 from order_edit_execution_attempt_results r
              where r.admission_id=a.id and r.attempt_id=t.id and r.disposition not in ('UNKNOWN','REVIEW'))))
          and not exists(select 1 from order_change_operations x where x.order_id=a.order_id and x.id<>op.id
            and x.state not in ('COMPLETED','ABORTED'))
          and not exists(select 1 from payments p join payment_transactions t on t.payment_id=p.id
            where p.order_id=a.order_id and t.status='PENDING')
          and not exists(select 1 from order_change_financial_requests q left join order_change_request_dispatches d on d.request_id=q.id
            where q.order_id=a.order_id and d.state is distinct from 'OBSERVED')
        """;
}
