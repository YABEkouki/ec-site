package com.example.ecsite.service.orderchange;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.example.ecsite.payment.PaymentGateway;

/** Feature129-A: a confirmed NOT_ADOPTED order change whose new authorization later succeeds at PAY.JP must
 * not sit forever waiting for the customer to reopen the page. This records one SYSTEM abort intent per
 * eligible operation, then retries the same prepare/dispatch/closure/completion sequence the customer
 * withdrawal command uses ({@link RequestedAbortProgression}) — nothing else retries a SYSTEM-originated
 * intent, since there is no customer to click withdraw again. MANUAL_REVIEW and owned (order-edit) operations
 * are left for an administrator or a separate Feature129-B. */
@Service
@Transactional(propagation=Propagation.NEVER)
public class OrderChangeNotAdoptedCancellationService {
    private static final Logger log=LoggerFactory.getLogger(OrderChangeNotAdoptedCancellationService.class);
    private record NewCandidate(long orderId,UUID operationId,long customerId,long version){}
    private record RetryCandidate(long orderId,UUID operationId,long customerId){}
    private final JdbcTemplate jdbc;
    private final OrderReauthorizationTransactions results;
    private final PaymentGateway gateway;
    private final PlatformTransactionManager manager;
    private final Clock clock;
    public OrderChangeNotAdoptedCancellationService(JdbcTemplate jdbc,OrderReauthorizationTransactions results,
            PaymentGateway gateway,PlatformTransactionManager manager,Clock clock){
        this.jdbc=jdbc;this.results=results;this.gateway=gateway;this.manager=manager;this.clock=clock;
    }
    /** Returns the number of operations touched (newly requested or progressed). Failures are retried next run. */
    public int cancelDue(int limit){
        if(limit<1||limit>1000)throw new IllegalArgumentException("Invalid cancellation batch size");
        int touched=0;
        for(var candidate:newCandidates(limit)){
            try{
                var command=new OrderReauthorizationTransactions.AbortRequest(candidate.orderId(),candidate.customerId(),
                    candidate.operationId(),systemRequestId(candidate.operationId()),candidate.version(),
                    OrderReauthorizationTransactions.AbortRequesterType.SYSTEM,
                    OrderReauthorizationTransactions.AbortReasonCode.CHANGE_NOT_ADOPTED);
                var receipt=results.requestAbort(command);
                if(List.of(OrderReauthorizationTransactions.AbortResult.ACCEPTED,OrderReauthorizationTransactions.AbortResult.REPLAYED).contains(receipt.result())){
                    progress(candidate.orderId(),candidate.customerId(),candidate.operationId());touched++;
                }
            }catch(RuntimeException failure){
                log.warn("Order change not-adopted cancellation intent deferred. orderId={}, operationId={}",candidate.orderId(),candidate.operationId());
            }
        }
        for(var candidate:retryCandidates(limit)){
            try{progress(candidate.orderId(),candidate.customerId(),candidate.operationId());touched++;}
            catch(RuntimeException failure){
                log.warn("Order change not-adopted cancellation progress deferred. orderId={}, operationId={}",candidate.orderId(),candidate.operationId());
            }
        }
        return touched;
    }
    private void progress(long orderId,long customerId,UUID operationId){
        new RequestedAbortProgression(results,gateway,manager,clock).advance(orderId,customerId,operationId);
    }
    /** Deterministic per-operation request id: safe to recompute across runs; requestAbort itself is the CAS. */
    private static UUID systemRequestId(UUID operationId){
        return UUID.nameUUIDFromBytes(("feature129/not-adopted-cancellation/"+operationId).getBytes(StandardCharsets.UTF_8));
    }
    private List<NewCandidate> newCandidates(int limit){
        return jdbc.query("""
            select o.order_id,o.id,ord.user_id,o.version from order_change_operations o
            join orders ord on ord.id=o.order_id
            join order_change_final_decisions d on d.operation_id=o.id
            where d.outcome='NOT_ADOPTED' and o.abort_requested_at is null and o.switch_decided_at is null
              and o.state in ('RESERVED','AUTHORIZING','REQUIRES_ACTION','AUTH_UNKNOWN','AUTHORIZED')
              and not f125_reauth_is_owned(o.order_id,o.id)
            order by d.final_at,o.order_id limit ?
            """,(r,n)->new NewCandidate(r.getLong(1),r.getObject(2,UUID.class),r.getLong(3),r.getLong(4)),limit);
    }
    private List<RetryCandidate> retryCandidates(int limit){
        return jdbc.query("""
            select o.order_id,o.id,ord.user_id from order_change_operations o
            join orders ord on ord.id=o.order_id
            where o.abort_requester_type='SYSTEM' and o.abort_reason_code='CHANGE_NOT_ADOPTED'
              and o.state not in ('ABORTED','COMPLETED','MANUAL_REVIEW')
            order by o.abort_requested_at,o.order_id limit ?
            """,(r,n)->new RetryCandidate(r.getLong(1),r.getObject(2,UUID.class),r.getLong(3)),limit);
    }
}
