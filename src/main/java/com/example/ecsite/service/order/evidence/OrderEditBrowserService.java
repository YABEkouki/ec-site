package com.example.ecsite.service.order.evidence;

import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.Page;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.example.ecsite.entity.Product;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.exception.*;

/** Customer boundary. No ambient business Tx; only owner-bound explicit commands may execute. */
@Service
public class OrderEditBrowserService {
    public record Admission(UUID id,String admissionState,String executionState){}
    private final OrderEditPreviewService previews;
    private final ObjectProvider<LegacyOrderEvidenceEnrollment> enrollment;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<OrderEditAdmissionService> admissions;
    private final ObjectProvider<OrderEditExecutionService> executions;
    private final ObjectProvider<OrderEditCardService> cards;
    public enum NextAction { DISPLAY_ONLY, AUTO_CONTINUE_ALLOWED, AUTO_RESUME_REAUTH_ALLOWED, OBSERVE_ONLY, MANUAL_REVIEW_REQUIRED }
    public record NextStep(NextAction action,String reason,Long version,long waitMillis,long remainingMillis,String stage) {
        public NextStep(NextAction action,String reason){this(action,reason,0L,0,30_000,"INITIAL:0");}
    }
    public record BrowserResult(UUID id,String label,boolean canContinue,boolean canAuthenticate,NextStep nextStep){
        public BrowserResult(UUID id,String label,boolean canContinue,boolean canAuthenticate){
            this(id,label,canContinue,canAuthenticate,new NextStep(NextAction.DISPLAY_ONLY,"UNCLASSIFIED"));
        }
        public BrowserResult(UUID id,String label,boolean canContinue){this(id,label,canContinue,false);}
        public boolean autoContinueAllowed(){return nextStep!=null&&Set.of(NextAction.AUTO_CONTINUE_ALLOWED,NextAction.AUTO_RESUME_REAUTH_ALLOWED).contains(nextStep.action());}
    }
    public OrderEditBrowserService(OrderEditPreviewService previews,ObjectProvider<LegacyOrderEvidenceEnrollment> enrollment,JdbcTemplate jdbc,ObjectProvider<OrderEditAdmissionService> admissions,ObjectProvider<OrderEditExecutionService> executions,ObjectProvider<OrderEditCardService> cards){this.previews=previews;this.enrollment=enrollment;this.jdbc=jdbc;this.admissions=admissions;this.executions=executions;this.cards=cards;}
    public OrderEditPreviewService.PageContext load(long order){return previews.load(order,customer());}
    public OrderEditPreviewService.Preview preview(long order,OrderEditCandidate.Request request){return previews.preview(order,customer(),request);}
    public OrderEditCandidate.Offer offer(long product){customer();return previews.offer(product);}
    public Page<Product> search(String keyword,int page){customer();return previews.search(keyword,page);}
    /** Explicit edit-start command; display GETs never enroll or call the provider. */
    public LegacyOrderEvidenceEnrollment.Result startEditing(long order){
        outside();long customer=customer();var page=previews.load(order,customer);
        String reason=OrderEditPreviewService.stateReason(page.context());
        if(reason!=null)throw new InvalidOrderStatusException(reason);
        if(page.context().facts().evidenceRequired()){
            if(!page.editable())throw new InvalidOrderStatusException(page.unavailableReason());
            return new LegacyOrderEvidenceEnrollment.Result(LegacyOrderEvidenceEnrollment.Status.ALREADY_ENROLLED,page.context().facts().epoch().id());
        }
        return enroll(order);
    }
    /** Owner-bound display link to a saved Feature124 operation, including recovery after the edit deadline. */
    public UUID legacyChangeOperation(long order){
        outside();long customer=customer();requireOwner(order,customer);
        var ids=jdbc.queryForList("""
            select id from order_change_operations where order_id=? and customer_id=? and execution_version is null
            order by case when state in ('COMPLETED','ABORTED') then 1 else 0 end, created_at desc,id desc limit 1
            """,UUID.class,order,customer);
        return ids.isEmpty()?null:ids.getFirst();
    }
    public LegacyOrderEvidenceEnrollment.Result enroll(long order){
        outside();long customer=customer();var page=previews.load(order,customer);
        String reason=OrderEditPreviewService.stateReason(page.context());
        if(reason!=null)throw new InvalidOrderStatusException(reason);
        final LegacyOrderEvidenceEnrollment legacy;
        try{legacy=enrollment.getObject();}catch(BeanCreationException invalidConfiguration){
            return new LegacyOrderEvidenceEnrollment.Result(LegacyOrderEvidenceEnrollment.Status.REVIEW_REQUIRED,null);
        }
        var result=legacy.enrollForCustomer(order,customer);
        if(result.status()==LegacyOrderEvidenceEnrollment.Status.NOT_FOUND)throw new OrderNotFoundException(order);
        return result;
    }
    /** Display hint only; authoritative runtime identity/generation fencing remains in the backend. */
    public boolean submissionAvailable(){
        customer();try{return Boolean.TRUE.equals(jdbc.queryForObject("select f125_runtime_available()",Boolean.class));}
        catch(org.springframework.dao.DataAccessException unavailable){return false;}
    }
    public OrderEditAdmissionService.Result submit(long order,UUID key,OrderEditCandidate.Request request,OrderEditCandidate.Candidate candidate){
        outside();long customer=customer();requireOwner(order,customer);
        if(request==null||candidate==null||request.orderId()!=order||candidate.sourceEvidence().current().orderId()!=order
            ||candidate.sourceEvidence().current().customerId()!=customer)throw new IllegalArgumentException("確認内容を確認できません。入力し直してください。");
        var result=admissions.getObject().submit(new OrderEditAdmissionService.Command(customer,key,request,candidate));
        executeAccepted(order,result);return result;
    }
    public Optional<com.example.ecsite.service.orderchange.OrderChangeBrowserService.Card> startCard(long order,UUID id){
        outside();var row=receipt(order,id);
        if(!"REAUTHORIZE".equals(row.get("route"))||!submissionAvailable())return Optional.empty();
        try{return cards.getObject().start(order,customer(),id);}catch(BeanCreationException unavailable){return Optional.empty();}
    }
    public OrderEditAdmissionService.Result continueAdmission(long order,UUID id){
        outside();var row=receipt(order,id); // Authorization does not depend on a currently editable deadline.
        var result=admissions.getObject().recover(customer(),order,(UUID)row.get("submission_key"));
        executeAccepted(order,result);
        if(result.outcome()==OrderEditAdmissionService.Outcome.ACCEPTED&&submissionAvailable()){
            var fresh=receipt(order,id);
            if("REAUTHORIZE".equals(fresh.get("route"))&&Set.of("RECOVERY_PENDING","REVIEW_REQUIRED").contains((String)fresh.get("execution_state")))
                try{executions.getObject().reconcile(id);}catch(RuntimeException unconfirmed){/* Existing proof/holds remain unresolved. */}
        }
        return result;
    }
    /** Automatic requests never enter recovery reconciliation. Every POST rechecks the read-only hint,
     * then the existing independent receipt verification and fenced claim/application revalidate again. */
    public void continueAutomatically(long order,UUID id){continueAutomatically(order,id,null);}
    public void continueAutomatically(long order,UUID id,Long expectedVersion){
        outside();var hint=result(order,id);if(!hint.autoContinueAllowed())return;
        boolean resumed=hint.nextStep().action()==NextAction.AUTO_RESUME_REAUTH_ALLOWED;
        if(resumed&&(expectedVersion==null||!expectedVersion.equals(hint.nextStep().version())||hint.nextStep().waitMillis()>0))return;
        if(!resumed&&expectedVersion!=null&&expectedVersion!=0)return;
        var row=receipt(order,id);
        var verified=admissions.getObject().recover(customer(),order,(UUID)row.get("submission_key"));
        if(!resumed){executeAccepted(order,verified,true);return;}
        if(verified.outcome()!=OrderEditAdmissionService.Outcome.ACCEPTED||!id.equals(verified.admissionId()))return;
        var fresh=result(order,id);
        if(fresh.nextStep().action()!=NextAction.AUTO_RESUME_REAUTH_ALLOWED||!expectedVersion.equals(fresh.nextStep().version())
            ||fresh.nextStep().waitMillis()>0)return;
        try{executions.getObject().executeRequeued(id,expectedVersion);}
        catch(RuntimeException unconfirmed){/* Read saved state after response loss; never replay a financial request. */}
    }
    private void executeAccepted(long order,OrderEditAdmissionService.Result result){executeAccepted(order,result,false);}
    private void executeAccepted(long order,OrderEditAdmissionService.Result result,boolean automatic){
        if(result.outcome()!=OrderEditAdmissionService.Outcome.ACCEPTED||result.admissionId()==null||!submissionAvailable())return;
        var row=receipt(order,result.admissionId());
        if(!"ACCEPTED".equals(row.get("admission_state"))||!"QUEUED".equals(row.get("execution_state")))return;
        try{if(automatic)executions.getObject().executeInitial(result.admissionId());else executions.getObject().execute(result.admissionId());}
        catch(RuntimeException unconfirmed){/* Saved receipt stays unresolved. No guessed rollback or automatic retry. */}
    }
    /** All receipt access binds order/customer/id in a single read; GET never calls recover or execute. */
    public Admission admission(long order,UUID id){
        var row=receipt(order,id);return new Admission(id,(String)row.get("admission_state"),(String)row.get("execution_state"));
    }
    /** Read-only lookup after an ambiguous Tx-A response; never verifies or creates a receipt. */
    public UUID findAdmission(long order,UUID key){
        outside();long customer=customer();requireOwner(order,customer);Objects.requireNonNull(key);
        var ids=jdbc.queryForList("select id from order_edit_admissions where order_id=? and customer_id=? and submission_key=?",UUID.class,order,customer,key);
        if(ids.size()>1)throw new IllegalStateException("Ambiguous submission identity");return ids.isEmpty()?null:ids.getFirst();
    }
    public BrowserResult result(long order,UUID id){
        var row=receipt(order,id);String a=(String)row.get("admission_state"),x=(String)row.get("execution_state");
        String label="確認待ち";boolean retry=false;
        if("ACCEPTED".equals(a)&&"COMPLETED".equals(x)&&row.get("closed_at")!=null&&row.get("applied_operation_id")!=null)label="変更完了";
        else if(row.get("closed_at")!=null&&row.get("applied_operation_id")==null
            &&("REJECTED_LATE".equals(a)||Set.of("CLOSED_UNAPPLIED","FAILED_UNAPPLIED").contains(x==null?"":x)))label="未適用終了";
        else if(Boolean.TRUE.equals(row.get("auth_waiting")))label="カード認証待ち";
        else if("ACCEPTED".equals(a)&&"QUEUED".equals(x)){label="受付済み";retry=true;}
        else if("ACCEPTED".equals(a)&&Set.of("OBSERVING","APPLYING").contains(x==null?"":x))label="処理中";
        else if(Set.of("RECORDED_UNVERIFIED","UNPROVEN").contains(a==null?"":a))retry=true;
        if("ACCEPTED".equals(a)&&"REAUTHORIZE".equals(row.get("route"))&&Set.of("RECOVERY_PENDING","REVIEW_REQUIRED").contains(x==null?"":x))retry=true;
        boolean available=(retry||Boolean.TRUE.equals(row.get("auth_available")))&&submissionAvailable();
        return new BrowserResult(id,label,retry&&available,Boolean.TRUE.equals(row.get("auth_available"))&&available,nextStep(row,available,order,id));
    }
    private NextStep nextStep(Map<String,Object> row,boolean available,long order,UUID id){
        String state=(String)row.get("execution_state");
        if(row.get("closed_at")!=null)return new NextStep(NextAction.DISPLAY_ONLY,"TERMINAL");
        if(Set.of("RECOVERY_PENDING","REVIEW_REQUIRED").contains(state==null?"":state))
            return new NextStep(NextAction.MANUAL_REVIEW_REQUIRED,"RECOVERY_REQUIRES_EXPLICIT_CONFIRMATION");
        if(Boolean.TRUE.equals(row.get("auth_waiting")))return new NextStep(NextAction.DISPLAY_ONLY,"CARD_AUTHENTICATION_REQUIRED");
        if(Set.of("OBSERVING","APPLYING").contains(state==null?"":state))return new NextStep(NextAction.OBSERVE_ONLY,"EXECUTION_IN_PROGRESS");
        if(!"ACCEPTED".equals(row.get("admission_state"))||!"QUEUED".equals(state))return new NextStep(NextAction.DISPLAY_ONLY,"RECEIPT_NOT_READY");
        if(!available||!Boolean.TRUE.equals(row.get("runtime_matches")))return new NextStep(NextAction.MANUAL_REVIEW_REQUIRED,"RUNTIME_UNAVAILABLE_OR_GENERATION_CHANGED");
        if(!Set.of("RETAIN_AUTH","REAUTHORIZE").contains(Objects.toString(row.get("route"),"")))return new NextStep(NextAction.MANUAL_REVIEW_REQUIRED,"UNKNOWN_ROUTE");
        if("REAUTHORIZE".equals(row.get("route"))&&!Boolean.TRUE.equals(row.get("initial_execution"))
            &&Boolean.TRUE.equals(row.get("proof_ready"))){
            var ready=jdbc.queryForList(OrderEditRequeuedAuthorization.FACTS+" and a.order_id=? and a.customer_id=?",id,order,customer());
            if(ready.size()==1){
                var r=ready.getFirst();long wait=((Number)r.get("wait_millis")).longValue(),remaining=((Number)r.get("remaining_millis")).longValue();
                long version=((Number)r.get("version")).longValue();
                if(version>0&&wait>=0&&wait<=OrderEditRequeuedAuthorization.MAX_WAIT_MILLIS&&remaining>wait+1000)
                    return new NextStep(NextAction.AUTO_RESUME_REAUTH_ALLOWED,"CONCLUSIVE_OWNED_REAUTH_REQUEUED",version,wait,remaining,
                        "REAUTH:"+version+":"+r.get("operation_version"));
            }
            return new NextStep(NextAction.MANUAL_REVIEW_REQUIRED,"REQUEUED_REAUTH_NOT_PROVEN_OR_OUTSIDE_WINDOW");
        }
        for(String fact:List.of("proof_ready","initial_execution","finance_clear","basis_matches"))
            if(!Boolean.TRUE.equals(row.get(fact)))return new NextStep(NextAction.MANUAL_REVIEW_REQUIRED,"UNPROVEN_"+fact.toUpperCase(Locale.ROOT));
        return new NextStep(NextAction.AUTO_CONTINUE_ALLOWED,"VERIFIED_INITIAL_QUEUE_WITHOUT_PRIOR_EXECUTION_OR_FINANCIAL_WORK");
    }
    private void requireOwner(long order,long customer){
        if(jdbc.queryForList("select id from orders where id=? and user_id=?",order,customer).size()!=1)throw new OrderNotFoundException(order);
    }
    // The current financial document is compared only; it never replaces the immutable Tx-A baseline.
    // These unlocked facts are display hints, not permission to bypass the POST's proof/claim checks.
    private Map<String,Object> receipt(long order,UUID id){
        outside();long customer=customer();if(id==null)throw new OrderNotFoundException(order);
        var rows=jdbc.queryForList("""
            select a.id,a.submission_key,a.admission_state,a.execution_state,a.closed_at,a.applied_operation_id,a.route,
              exists(select 1 from order_edit_runtime_control rc where rc.id=1 and rc.generation_id=a.db_generation_id
                and rc.state in ('RUNNING','ISOLATED_TEST')) as runtime_matches,
              exists(select 1 from order_edit_admission_evidences e where e.admission_id=a.id and e.decision='ACCEPTED'
                and e.original_xid=a.original_xid and e.db_generation_id=a.db_generation_id and e.deadline_at=a.deadline_at
                and e.request_digest=a.request_digest and e.candidate_digest=a.candidate_digest
                and e.original_commit_at<a.deadline_at and e.status_before='committed' and e.status_after='committed'
                and a.financial_snapshot_version=1 and a.financial_canonical=f125_financial_encode(a.financial_snapshot)
                and a.financial_digest=sha256(a.financial_canonical)
                and e.financial_binding=f125_financial_binding_build(a,e)
                and e.financial_binding_canonical=f125_financial_encode(e.financial_binding,'binding')
                and e.financial_binding_digest=sha256(e.financial_binding_canonical)) as proof_ready,
              (not exists(select 1 from order_edit_execution_controls c where c.admission_id=a.id)
                and not exists(select 1 from order_edit_execution_attempts t where t.admission_id=a.id)
                and not exists(select 1 from order_edit_execution_attempt_results t where t.admission_id=a.id)
                and not exists(select 1 from order_edit_admission_events v where v.admission_id=a.id
                  and v.after_execution in ('OBSERVING','APPLYING','RECOVERY_PENDING','REVIEW_REQUIRED'))) as initial_execution,
              (not exists(select 1 from order_change_operations op where op.order_id=a.order_id
                  and (op.submission_key=a.submission_key or op.state not in ('COMPLETED','ABORTED')))
                and not exists(select 1 from payments p join payment_transactions t on t.payment_id=p.id
                  where p.order_id=a.order_id and t.status='PENDING')
                and not exists(select 1 from order_change_financial_requests q
                  left join order_change_request_dispatches d on d.request_id=q.id
                  where q.order_id=a.order_id and d.state is distinct from 'OBSERVED')
                and not exists(select 1 from capture_requests q join payments p on p.id=q.payment_id where p.order_id=a.order_id)) as finance_clear,
              (o.status='ORDERED' and o.shipping_preparation_started_at is null and o.shipment_capture_payment_id is null
                and o.content_revision=a.base_content_revision and o.total_amount=a.before_amount
                and o.active_payment_id=a.base_payment_id and o.current_evidence_epoch_id=a.base_evidence_epoch_id
                and o.evidence_head_history_id is not distinct from a.base_evidence_history_id
                and a.financial_snapshot=f125_financial_build(a)) as basis_matches,
              exists(select 1 from order_change_operations op join payments p on p.id=op.new_payment_id and p.order_id=a.order_id
                where op.order_id=a.order_id and op.submission_key=a.submission_key and f125_reauth_is_owned(a.order_id,op.id)
                  and op.state in ('AUTHORIZING','REQUIRES_ACTION') and p.provider_payment_id is not null) as auth_waiting,
              exists(select 1 from order_change_operations op join order_edit_execution_controls c on c.admission_id=a.id
                join payments p on p.id=op.new_payment_id and p.order_id=a.order_id
                where op.order_id=a.order_id and op.submission_key=a.submission_key and f125_reauth_is_owned(a.order_id,op.id)
                  and op.state='AUTHORIZING' and op.browser_confirmation_issued_at is null and p.provider_payment_id is not null
                  and a.execution_state='RECOVERY_PENDING' and c.active_attempt_id is null) as auth_available
            from order_edit_admissions a join orders o on o.id=a.order_id and o.user_id=a.customer_id
            where o.id=? and o.user_id=? and a.id=?
            """,order,customer,id);
        if(rows.size()!=1)throw new OrderNotFoundException(order);return rows.getFirst();
    }
    private static long customer(){
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null||!auth.isAuthenticated()||!(auth.getPrincipal() instanceof CustomUserDetails user)||!user.isEnabled()
            ||user.getId()==null||user.getId()<=0||auth.getAuthorities().stream().noneMatch(a->"ROLE_USER".equals(a.getAuthority())))
            throw new AccessDeniedException("Customer authentication required");
        return user.getId();
    }
    private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Customer command must run outside a caller transaction");}
}
