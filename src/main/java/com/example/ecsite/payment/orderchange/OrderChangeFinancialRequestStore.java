package com.example.ecsite.payment.orderchange;

import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.example.ecsite.entity.PaymentTransactionPurpose;
import com.example.ecsite.payment.capture.CaptureConnectionIdentity;

/** Joins the caller's short transaction; no network, key generation or automatic financial recovery. */
@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class OrderChangeFinancialRequestStore {
    private final JdbcTemplate jdbc;
    public OrderChangeFinancialRequestStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public StoredOrderChangeRequest save(long paymentId,long transactionId,OrderChangeHttpRequest request){
        return save(paymentId,transactionId,request,false);
    }
    /** Only a permit minted by the internal, validated preparation transaction may cross this boundary. */
    public StoredOrderChangeRequest savePreparedAbort(com.example.ecsite.service.orderchange.OrderReauthorizationTransactions.PreparedAbortRequest permit){
        Objects.requireNonNull(permit);
        if(!"NEW_ABORT".equals(permit.request().kind()))throw new IllegalArgumentException("Expected prepared new abort");
        return save(permit.paymentId(),permit.transactionId(),permit.request(),true);
    }
    private StoredOrderChangeRequest save(long paymentId,long transactionId,OrderChangeHttpRequest request,boolean requestedAbort){
        requireTransaction();
        if(TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("Writable transaction required");
        var bridge=new com.example.ecsite.service.order.evidence.OrderEditReauthorizationBridge(jdbc);
        if(bridge.isOwned(request.orderId(),request.operationId()))bridge.canonicalLock(request.orderId(),request.operationId());
        // Order -> operation -> Payment ascending -> Transaction, including calls outside Spring proxies.
        jdbc.queryForObject("select id from orders where id=? for update",Long.class,request.orderId());
        var operation=jdbc.queryForMap("select * from order_change_operations where id=? and order_id=? for update",request.operationId(),request.orderId());
        jdbc.queryForList("select id from payments where order_id=? order by id for update",request.orderId());
        var target=jdbc.queryForMap("""
            select t.*,p.provider,p.payment_method,p.provider_payment_id,p.amount payment_amount,p.order_id
            from payment_transactions t join payments p on p.id=t.payment_id where t.id=? for update of t
            """,transactionId);
        Object paymentReference=operation.get(request.kind().equals("OLD_CANCEL")?"old_payment_id":"new_payment_id");
        if(!(paymentReference instanceof Number))throw new IllegalArgumentException("Order change payment reference missing");
        long expectedPayment=((Number)paymentReference).longValue();
        int expectedAmount=((Number)(request.kind().equals("CREATE")?operation.get("after_amount"):target.get("payment_amount"))).intValue();
        if(paymentId!=expectedPayment||paymentId!=((Number)target.get("payment_id")).longValue()
            ||request.orderId()!=((Number)target.get("order_id")).longValue()
            ||!request.operationId().equals(target.get("order_change_operation_id"))
            ||!request.purpose().name().equals(target.get("purpose"))||!request.transactionType().equals(target.get("transaction_type"))
            ||request.amount()!=((Number)target.get("amount")).intValue()||request.amount()!=expectedAmount
            ||((Number)target.get("order_content_revision")).intValue()!=Math.addExact(((Number)operation.get("base_content_revision")).intValue(),
                request.kind().equals("CREATE")&&operation.get("base_order_fingerprint")!=null?1:0)
            ||!request.idempotencyKey().equals(target.get("idempotency_key"))
            ||!"PAYJP".equals(target.get("provider"))||!"CARD".equals(target.get("payment_method"))
            ||(request.flowId()!=null&&!request.flowId().equals(target.get("provider_payment_id"))))
            throw new IllegalArgumentException("Order change request target mismatch");
        var existing=jdbc.query("select * from order_change_financial_requests where operation_id=? and purpose=?",
            (rs,n)->map(rs),request.operationId(),request.purpose().name());
        if(!existing.isEmpty()){
            var saved=existing.getFirst();
            if(saved.paymentId()!=paymentId||saved.transactionId()!=transactionId
                ||!java.security.MessageDigest.isEqual(saved.httpRequest().fingerprint(),request.fingerprint()))
                throw new IllegalArgumentException("Order change request content conflict");
            return saved;
        }
        if(operation.get("abort_request_id")!=null&&(!requestedAbort||!"NEW_ABORT".equals(request.kind())
                ||!"ABORTING".equals(operation.get("state"))||operation.get("abort_flow_closed_at")!=null))
            throw new IllegalStateException("Abort intent forbids unprepared financial requests");
        if(!"PENDING".equals(target.get("status")))throw new IllegalStateException("New request requires pending transaction");
        if(request.kind().equals("CREATE")&&(!"RESERVED".equals(operation.get("state"))||operation.get("switch_decided_at")!=null
                ||target.get("provider_payment_id")!=null||request.amount()!=((Number)target.get("payment_amount")).intValue()
                ||paymentId==((Number)operation.get("old_payment_id")).longValue()))
            throw new IllegalStateException("New authorization request is not eligible");
        if(request.kind().equals("OLD_CANCEL")&&operation.get("switch_decided_at")==null)
            throw new IllegalStateException("Old Cancel requires a durable switch decision");
        if(request.kind().equals("NEW_ABORT")&&(operation.get("switch_decided_at")!=null
                ||!List.of("ABORTING","ABORT_UNKNOWN").contains(operation.get("state"))))
            throw new IllegalStateException("Cannot abort after switch decision");
        return jdbc.queryForObject("""
            insert into order_change_financial_requests(operation_id,order_id,payment_id,transaction_id,purpose,request_kind,
              transaction_type,amount,provider_flow_id,idempotency_key,connection_id,provider_account_ref,mode,api_origin,
              http_method,api_path,content_type,body_format_version,request_body,fingerprint_version,request_fingerprint,created_at,replay_not_after)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,?,1,?,statement_timestamp(),statement_timestamp()+interval '23 hours') returning *
            """,(rs,n)->map(rs),request.operationId(),request.orderId(),paymentId,transactionId,request.purpose().name(),request.kind(),
            request.transactionType(),request.amount(),request.flowId(),request.idempotencyKey(),request.connection().connectionId(),
            request.connection().providerAccountRef(),request.connection().mode(),request.connection().apiOrigin(),request.method(),request.path(),
            request.contentType(),request.body(),request.fingerprint());
    }
    public StoredOrderChangeRequest find(long id){
        requireTransaction();
        return jdbc.queryForObject("select * from order_change_financial_requests where id=?",(rs,n)->map(rs),id);
    }
    /** Eligibility has already been consumed, or predates dispatch tracking. Observation only. */
    public static final class DispatchUnavailable extends RuntimeException {
        public DispatchUnavailable(){super("Request is legacy or dispatch has already started; observation only");}
    }
    /** CREATE was never started and, the change deadline having passed, can never start (V82 guard). */
    public static final class DispatchDeadlinePassed extends RuntimeException {
        public DispatchDeadlinePassed(){super("Order change deadline passed before the authorization request started");}
    }
    /** SQLSTATE raised by the V82 deadline guards, including the deferred check at commit. */
    public static final String DEADLINE_SQLSTATE="EC128";
    public static boolean isDeadlineViolation(Throwable failure){
        int depth=0;
        for(var cause=failure;cause!=null&&depth<32;cause=cause.getCause(),depth++)
            if(cause instanceof SQLException sql&&DEADLINE_SQLSTATE.equals(sql.getSQLState()))return true;
        return false;
    }
    /** Called only after validated provider GET evidence, in the result-application transaction. */
    public void observed(UUID operationId,String kind){
        requireTransaction();
        jdbc.update("""
            update order_change_request_dispatches d set state='OBSERVED',observed_at=clock_timestamp()
            from order_change_financial_requests r
            where r.id=d.request_id and r.operation_id=? and r.request_kind=? and d.state='STARTED'
            """,operationId,kind);
    }
    /** Atomically consume eligibility before minting a token. Caller must commit before POST. */
    public StoredOrderChangeRequest findForDispatch(long id){return findForDispatch(id,null);}
    public StoredOrderChangeRequest findForDispatch(long id,com.example.ecsite.service.order.evidence.OrderEditApplicationPlan.Ownership owner){
        requireTransaction();
        var saved=find(id);var request=saved.httpRequest();
        var bridge=new com.example.ecsite.service.order.evidence.OrderEditReauthorizationBridge(jdbc);
        boolean orderEdit=bridge.isOwned(request.orderId(),request.operationId());
        if(orderEdit){
            if(owner==null)throw new IllegalStateException("Order edit ownership required");
            bridge.canonicalLock(request.orderId(),request.operationId());
            bridge.requireOwner(request.orderId(),request.operationId(),owner);
        }else if(owner!=null)throw new IllegalStateException("Not an owned order edit request");
        jdbc.queryForObject("select id from orders where id=? for update",Long.class,request.orderId());
        if("CREATE".equals(request.kind())&&!orderEdit)
            new com.example.ecsite.service.order.evidence.OrderEditAdmissionGuard(jdbc).requireClear(request.orderId());
        var op=jdbc.queryForMap("select * from order_change_operations where id=? and order_id=? for update",request.operationId(),request.orderId());
        jdbc.queryForList("select id from payments where order_id=? order by id for update",request.orderId());
        if(op.get("abort_request_id")!=null)throw new DispatchUnavailable();
        // Reuse all ownership, revision, amount, key and exact-request checks.
        save(saved.paymentId(),saved.transactionId(),request);
        if(!"PENDING".equals(jdbc.queryForObject("select status from payment_transactions where id=?",String.class,saved.transactionId())))
            throw new IllegalStateException("Settled financial transaction is observation-only");
        boolean eligible=switch(request.kind()){
            case "CREATE" -> op.get("switch_decided_at")==null&&List.of("RESERVED","AUTHORIZING","AUTH_UNKNOWN").contains(op.get("state"));
            case "OLD_CANCEL" -> op.get("switch_decided_at")!=null&&List.of("OLD_CANCEL_PENDING","OLD_CANCEL_UNKNOWN").contains(op.get("state"));
            case "NEW_ABORT" -> op.get("switch_decided_at")==null&&List.of("ABORTING","ABORT_UNKNOWN").contains(op.get("state"));
            default -> false;
        };
        if(!eligible)throw new IllegalStateException("Order change is not eligible for request dispatch");
        saved.requireReplayAt(jdbc.queryForObject("select clock_timestamp()",OffsetDateTime.class).toInstant());
        // A legacy (untracked) or already consumed permit stays observation-only; only a READY permit is judged
        // against the deadline. Database clock under the Order lock; V82 repeats this on the row and at commit.
        if(!List.of("READY").equals(jdbc.queryForList("select state from order_change_request_dispatches where request_id=? for update",String.class,id)))
            throw new DispatchUnavailable();
        if("CREATE".equals(request.kind())&&!Boolean.TRUE.equals(jdbc.queryForObject("""
            select change_deadline_at is not null and timezone('Asia/Tokyo',clock_timestamp())<change_deadline_at
              and not exists(select 1 from order_change_deadline_closures c where c.operation_id=?)
            from orders where id=?
            """,Boolean.class,request.operationId(),request.orderId())))throw new DispatchDeadlinePassed();
        if(orderEdit){
            bridge.validateBase(request.orderId(),request.operationId());
            bridge.requireOwner(request.orderId(),request.operationId(),owner);
        }
        if(jdbc.update("""
            update order_change_request_dispatches set state='STARTED',started_at=clock_timestamp()
            where request_id=? and state='READY'
            """,id)!=1)throw new DispatchUnavailable();
        return saved;
    }
    private static void requireTransaction(){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Existing transaction required");
    }
    private static StoredOrderChangeRequest map(ResultSet rs)throws SQLException{
        var connection=new CaptureConnectionIdentity(rs.getString("connection_id"),rs.getString("provider_account_ref"),rs.getString("mode"),rs.getString("api_origin"));
        var request=OrderChangeHttpRequest.restore(connection,rs.getObject("operation_id",UUID.class),rs.getLong("order_id"),rs.getInt("amount"),
            rs.getString("idempotency_key"),PaymentTransactionPurpose.valueOf(rs.getString("purpose")),rs.getString("provider_flow_id"),
            rs.getString("http_method"),rs.getString("api_path"),rs.getString("content_type"),rs.getInt("body_format_version"),rs.getBytes("request_body"),
            rs.getInt("fingerprint_version"),rs.getBytes("request_fingerprint"));
        if(!request.kind().equals(rs.getString("request_kind"))||!request.transactionType().equals(rs.getString("transaction_type")))
            throw new IllegalArgumentException("Stored request kind mismatch");
        return new StoredOrderChangeRequest(rs.getLong("id"),rs.getLong("payment_id"),rs.getLong("transaction_id"),request,
            rs.getObject("created_at",OffsetDateTime.class).toInstant(),rs.getObject("replay_not_after",OffsetDateTime.class).toInstant());
    }
}
