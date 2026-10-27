package com.example.ecsite.service.orderchange;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.exception.OrderNotFoundException;

/** Whitelisted projections only. No Gateway, writable repository or result-application dependency. */
@Service
@Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
public class AdminOldCancelRecoveryViewService {
    public record Target(long orderId,UUID operationId,String state,String stopReason,long oldPaymentId,int oldAmount,
            long newPaymentId,int newAmount,long version,boolean eligible){}
    public record Audit(UUID requestId,long actorId,String actorUsername,String reason,long expectedVersion,Long appliedVersion,
            String result,String startedAt,String finishedAt){}
    public record Page(Target target,List<Audit> audits,OldCancelRecovery.Command resume){}
    private final JdbcTemplate jdbc;
    public AdminOldCancelRecoveryViewService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public List<Target> eligible(long orderId){
        actor();return targets(orderId).stream().filter(Target::eligible).toList();
    }
    public Page page(long orderId,UUID operationId){
        var actor=actor();var target=targets(orderId).stream().filter(t->t.operationId().equals(operationId)).findFirst()
            .orElseThrow(()->new OrderNotFoundException(orderId));
        var audits=jdbc.query("""
            select request_id,actor_id,actor_username,reason_code,expected_version,applied_version,result_code,started_at,finished_at
            from order_change_recovery_audits where order_id=? and operation_id=? order by started_at desc,request_id
            """,(r,n)->new Audit(r.getObject("request_id",UUID.class),r.getLong("actor_id"),r.getString("actor_username"),r.getString("reason_code"),
            r.getLong("expected_version"),(Long)r.getObject("applied_version"),r.getString("result_code"),display(r.getTimestamp("started_at")),display(r.getTimestamp("finished_at"))),orderId,operationId);
        var resume=audits.stream().filter(a->a.actorId()==actor.getId()&&a.actorUsername().equals(actor.getUsername())
            &&((target.eligible()&&a.expectedVersion()==target.version()&&List.of("STARTED","HOLD").contains(a.result()))
                ||("COMMIT_PENDING".equals(target.state())&&"COMMIT_PENDING".equals(a.result())&&Objects.equals(a.appliedVersion(),target.version()))))
            .findFirst().map(a->new OldCancelRecovery.Command(orderId,operationId,a.requestId(),a.expectedVersion(),a.reason())).orElse(null);
        return new Page(target,List.copyOf(audits),resume);
    }
    private List<Target> targets(long orderId){
        return jdbc.query("""
            select o.id,o.order_id,o.state,o.financial_review_reason,o.old_payment_id,o.new_payment_id,o.version,
              p.amount old_amount,n.amount new_amount,
              (o.state='MANUAL_REVIEW' and o.financial_review_reason='CANCEL_FLOW_EVIDENCE_MISMATCH' and exists(
                select 1 from order_change_financial_requests r where r.operation_id=o.id and r.order_id=o.order_id
                  and r.payment_id=o.old_payment_id and r.request_kind='OLD_CANCEL' and r.purpose='ORDER_CHANGE_OLD_AUTH_CANCEL')) eligible
            from order_change_operations o join payments p on p.id=o.old_payment_id join payments n on n.id=o.new_payment_id
            where o.order_id=? order by o.created_at desc,o.id
            """,(r,n)->new Target(r.getLong("order_id"),r.getObject("id",UUID.class),r.getString("state"),r.getString("financial_review_reason"),
            r.getLong("old_payment_id"),r.getInt("old_amount"),r.getLong("new_payment_id"),r.getInt("new_amount"),r.getLong("version"),r.getBoolean("eligible")),orderId);
    }
    private static String display(java.sql.Timestamp value){return value==null?"":DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss").withZone(ZoneId.of("Asia/Tokyo")).format(value.toInstant());}
    public static AdminUserDetails actor(){
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null||!auth.isAuthenticated()||auth.getAuthorities().stream().noneMatch(a->"ROLE_ADMIN".equals(a.getAuthority()))
            ||!(auth.getPrincipal() instanceof AdminUserDetails actor)||!actor.isEnabled()||actor.getId()==null||actor.getId()<=0)
            throw new AccessDeniedException("Valid administrator authentication required");
        return actor;
    }
}
