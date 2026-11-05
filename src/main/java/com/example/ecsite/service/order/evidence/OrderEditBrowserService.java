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
    public record BrowserResult(UUID id,String label,boolean canContinue,boolean canAuthenticate){
        public BrowserResult(UUID id,String label,boolean canContinue){this(id,label,canContinue,false);}
    }
    public OrderEditBrowserService(OrderEditPreviewService previews,ObjectProvider<LegacyOrderEvidenceEnrollment> enrollment,JdbcTemplate jdbc,ObjectProvider<OrderEditAdmissionService> admissions,ObjectProvider<OrderEditExecutionService> executions,ObjectProvider<OrderEditCardService> cards){this.previews=previews;this.enrollment=enrollment;this.jdbc=jdbc;this.admissions=admissions;this.executions=executions;this.cards=cards;}
    public OrderEditPreviewService.PageContext load(long order){return previews.load(order,customer());}
    public OrderEditPreviewService.Preview preview(long order,OrderEditCandidate.Request request){return previews.preview(order,customer(),request);}
    public OrderEditCandidate.Offer offer(long product){customer();return previews.offer(product);}
    public Page<Product> search(String keyword,int page){customer();return previews.search(keyword,page);}
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
    private void executeAccepted(long order,OrderEditAdmissionService.Result result){
        if(result.outcome()!=OrderEditAdmissionService.Outcome.ACCEPTED||result.admissionId()==null||!submissionAvailable())return;
        var row=receipt(order,result.admissionId());
        if(!"ACCEPTED".equals(row.get("admission_state"))||!"QUEUED".equals(row.get("execution_state")))return;
        try{executions.getObject().execute(result.admissionId());}
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
        return new BrowserResult(id,label,retry&&available,Boolean.TRUE.equals(row.get("auth_available"))&&available);
    }
    private void requireOwner(long order,long customer){
        if(jdbc.queryForList("select id from orders where id=? and user_id=?",order,customer).size()!=1)throw new OrderNotFoundException(order);
    }
    private Map<String,Object> receipt(long order,UUID id){
        outside();long customer=customer();if(id==null)throw new OrderNotFoundException(order);
        var rows=jdbc.queryForList("""
            select a.id,a.submission_key,a.admission_state,a.execution_state,a.closed_at,a.applied_operation_id,a.route,
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
