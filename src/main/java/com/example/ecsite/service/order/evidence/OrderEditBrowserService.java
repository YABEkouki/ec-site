package com.example.ecsite.service.order.evidence;

import java.util.UUID;
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

/** Customer boundary. No ambient business Tx and no admission/execution commands exposed here. */
@Service
public class OrderEditBrowserService {
    public record Admission(UUID id,String admissionState,String executionState){}
    private final OrderEditPreviewService previews;
    private final ObjectProvider<LegacyOrderEvidenceEnrollment> enrollment;
    private final JdbcTemplate jdbc;
    public OrderEditBrowserService(OrderEditPreviewService previews,ObjectProvider<LegacyOrderEvidenceEnrollment> enrollment,JdbcTemplate jdbc){this.previews=previews;this.enrollment=enrollment;this.jdbc=jdbc;}
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
    /** Future callers must bind all three identities; an unguessable receipt UUID is not authorization. */
    public Admission admission(long order,UUID id){
        outside();long customer=customer();if(id==null)throw new OrderNotFoundException(order);
        var rows=jdbc.queryForList("""
            select a.id,a.admission_state,a.execution_state from order_edit_admissions a
            join orders o on o.id=a.order_id and o.user_id=a.customer_id
            where o.id=? and o.user_id=? and a.id=?
            """,order,customer,id);
        if(rows.size()!=1)throw new OrderNotFoundException(order);var row=rows.getFirst();
        return new Admission((UUID)row.get("id"),(String)row.get("admission_state"),(String)row.get("execution_state"));
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
