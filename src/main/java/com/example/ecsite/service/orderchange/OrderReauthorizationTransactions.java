package com.example.ecsite.service.orderchange;

import java.io.*;
import java.security.*;
import java.time.*;
import java.util.*;
import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.repository.*;
import com.example.ecsite.service.*;
import com.example.ecsite.service.order.*;
import com.example.ecsite.service.pricing.*;
import com.example.ecsite.service.payment.PaymentOperationSnapshot;
import com.example.ecsite.payment.*;
import com.example.ecsite.payment.orderchange.*;
import com.example.ecsite.payment.payjp.PayJpPaymentFlowObservation;
import com.example.ecsite.config.*;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Short Order -> operation -> all Payments -> transactions -> reservations -> Products transactions.
 * Provider observations enter here from HTTP, webhook or reconciliation; no network calls occur here. */
@Service
@Transactional(propagation=Propagation.REQUIRES_NEW)
public class OrderReauthorizationTransactions {
    public record View(UUID id,long orderId,String state,Long newPaymentId,String newFlow,String oldFlow,
            Long createRequest,Long oldCancelRequest,Long abortRequest,boolean abortRequested,boolean flowClosed){}
    public enum AbortRequesterType { CUSTOMER, ADMINISTRATOR, SYSTEM }
    public enum AbortReasonCode { CUSTOMER_REQUEST, BROWSER_INTERRUPTED, AUTHENTICATION_INTERRUPTED, COMMUNICATION_FAILURE, PAYMENT_FAILURE }
    public enum AbortResult { ACCEPTED, REPLAYED, CONTENT_CONFLICT, VERSION_CONFLICT, NOT_ALLOWED }
    public record AbortRequest(long orderId,long customerId,UUID operationId,UUID requestId,long expectedVersion,
            AbortRequesterType requesterType,AbortReasonCode reasonCode){
        public AbortRequest{
            if(orderId<=0||customerId<=0||expectedVersion<0)throw new IllegalArgumentException("Invalid abort identity or version");
            Objects.requireNonNull(operationId);Objects.requireNonNull(requestId);Objects.requireNonNull(requesterType);Objects.requireNonNull(reasonCode);
        }
    }
    public record AbortReceipt(AbortResult result,long version){}
    /** Lookup only; the closure loader acquires Order -> operation locks and revalidates this selection. */
    @Transactional(propagation=Propagation.MANDATORY)
    Optional<Map<String,Object>> newFlowClosureTarget(UUID operationId){
        var rows=jdbc.queryForList("select order_id,state,abort_requested_at from order_change_operations where id=?",operationId);
        return rows.stream().findFirst();
    }
    /** Closure-only snapshot. No POST eligibility, replay deadline or successful Cancel is inferred. */
    @Transactional(propagation=Propagation.MANDATORY)
    com.example.ecsite.service.orderchange.NewFlowClosureControl.Expected loadNewFlowClosureExpected(long orderId,UUID operationId){
        var initial=lock(orderId,operationId);
        // Strong FOR UPDATE conflicts with FK KEY SHARE, unlike a possible JPA NO KEY UPDATE.
        jdbc.queryForList("select id from payments where order_id=? order by id for update",orderId);
        jdbc.queryForList("select t.id from payment_transactions t join payments p on p.id=t.payment_id where p.order_id=? order by t.payment_id,t.id for update of t",orderId);
        var freshHistory=new ArrayList<PaymentTransaction>();
        for(var payment:initial.payments()){
            var history=transactions.findByPaymentIdOrderByCreatedAtAscIdAsc(payment.getId());history.forEach(em::refresh);freshHistory.addAll(history);
        }
        var data=new Locked(initial.order(),initial.operation(),initial.payments(),freshHistory,initial.items());var op=data.operation();
        if(!abortRequested(data)||!List.of("AUTHORIZING","REQUIRES_ACTION","AUTH_UNKNOWN","AUTHORIZED","ABORTING","ABORT_UNKNOWN").contains(op.get("state"))
                ||op.get("new_payment_id")==null||op.get("switch_decided_at")!=null||op.get("content_change_history_id")!=null)
            throw new IllegalStateException("Closure observation not eligible");
        validateBase(data);requireHeld(data);
        var next=payment(data,number(op,"new_payment_id"));var old=payment(data,number(op,"old_payment_id"));
        var auth=transaction(data,PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION);
        if(next.getId().equals(old.getId())||old.getStatus()!=PaymentStatus.AUTHORIZED
                ||next.getProvider()!=PaymentProvider.PAYJP||next.getPaymentMethod()!=PaymentMethod.CARD
                ||next.getAmount()!=integer(op,"after_amount")||!validAuthorization(data,auth,next)
                ||!List.of(PaymentStatus.PENDING,PaymentStatus.REQUIRES_ACTION,PaymentStatus.AUTHORIZED,PaymentStatus.CANCELLED,PaymentStatus.FAILED).contains(next.getStatus()))
            throw new IllegalStateException("Closure saved Payment mismatch");
        if(data.transactions().stream().anyMatch(t->t.getPurpose()==PaymentTransactionPurpose.ORDER_CHANGE_OLD_AUTH_CANCEL
                    &&Objects.equals(t.getOrderChangeOperationId(),operationId)
                ||t.getPayment().getId().equals(next.getId())&&t.getTransactionType()==PaymentTransactionType.CAPTURE))
            throw new IllegalStateException("Competing financial history");
        var rows=jdbc.queryForList("select id,request_kind from order_change_financial_requests where operation_id=? order by id",operationId);
        if(rows.stream().anyMatch(r->"OLD_CANCEL".equals(r.get("request_kind"))))throw new IllegalStateException("Old Cancel request exists");
        var creationRows=rows.stream().filter(r->"CREATE".equals(r.get("request_kind"))).toList();
        if(creationRows.size()!=1)throw new IllegalStateException("CREATE context missing");
        var creation=requests.find(number(creationRows.getFirst(),"id"));var create=creation.httpRequest();
        var identity=connection.requireIdentity(provider);
        if(creation.paymentId()!=next.getId()||creation.transactionId()!=auth.getId()||create.orderId()!=orderId
                ||!create.operationId().equals(operationId)||create.amount()!=next.getAmount()
                ||!create.connection().equals(identity)||create.purpose()!=PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION
                ||!Objects.equals(auth.getIdempotencyKey(),create.idempotencyKey()))throw new IllegalStateException("Closure CREATE mismatch");
        var abortRows=rows.stream().filter(r->"NEW_ABORT".equals(r.get("request_kind"))).toList();
        var cancels=data.transactions().stream().filter(t->Objects.equals(t.getOrderChangeOperationId(),operationId)
            &&t.getPurpose()==PaymentTransactionPurpose.ORDER_CHANGE_NEW_AUTH_ABORT).toList();
        if(abortRows.size()>1||cancels.size()!=abortRows.size())throw new IllegalStateException("Partial NEW_ABORT history");
        if(data.transactions().stream().anyMatch(t->t.getStatus()==PaymentTransactionStatus.PENDING
                &&!t.getId().equals(auth.getId())&&cancels.stream().noneMatch(c->c.getId().equals(t.getId()))))
            throw new IllegalStateException("Competing pending transaction");
        Optional<com.example.ecsite.payment.payjp.NewFlowClosureObservation.RequestBinding> abort=Optional.empty();

        if(!abortRows.isEmpty()){
            var saved=requests.find(number(abortRows.getFirst(),"id"));var request=saved.httpRequest();var cancel=cancels.getFirst();
            if(saved.paymentId()!=next.getId()||saved.transactionId()!=cancel.getId()||!cancel.getPayment().getId().equals(next.getId())
                    ||cancel.getTransactionType()!=PaymentTransactionType.CANCEL||cancel.getAmount()!=next.getAmount()
                    ||cancel.getOrderContentRevision()!=integer(op,"base_content_revision")||request.amount()!=next.getAmount()
                    ||!Objects.equals(next.getProviderPaymentId(),request.flowId())||!request.connection().equals(create.connection())
                    ||!Objects.equals(cancel.getIdempotencyKey(),request.idempotencyKey()))throw new IllegalStateException("Closure NEW_ABORT mismatch");
            var states=jdbc.queryForList("select state from order_change_request_dispatches where request_id=?",String.class,saved.id());
            if(states.size()!=1||!List.of("READY","STARTED","OBSERVED").contains(states.getFirst()))throw new IllegalStateException("Legacy abort dispatch unknown");
            abort=Optional.of(closureBinding(saved));
        }
        var dispatchRows=jdbc.queryForList("select d.request_id from order_change_request_dispatches d join order_change_financial_requests r on r.id=d.request_id where r.operation_id=? order by d.request_id for update of d",operationId);
        // Missing legacy management rows are unknown, never proof that a request was unsent.
        if(dispatchRows.size()!=rows.size())throw new IllegalStateException("Closure dispatch context missing");
        var dispatch=jdbc.queryForObject("""
            select coalesce(jsonb_agg(jsonb_build_array(d.request_id,d.state,d.created_at,d.started_at,d.observed_at) order by d.request_id),'[]'::jsonb)::text
            from order_change_request_dispatches d join order_change_financial_requests r on r.id=d.request_id where r.operation_id=?
            """,String.class,operationId);
        // Selected columns only: no provider response, error payload, client secret or credentials.
        var paymentSnapshot=jdbc.queryForObject("select jsonb_build_array(id,order_id,provider,payment_method,status,amount,provider_payment_id,created_at)::text from payments where id=?",String.class,next.getId());
        var transactionSnapshot=jdbc.queryForList("""
            select jsonb_build_array(id,payment_id,transaction_type,purpose,status,amount,order_content_revision,
                order_change_operation_id,order_change_order_id,provider_transaction_id,idempotency_key,created_at,completed_at)::text
            from payment_transactions where payment_id=? order by id
            """,String.class,next.getId());
        return new com.example.ecsite.service.orderchange.NewFlowClosureControl.Expected(next.getId(),number(op,"version"),
            new NewFlowTerminationEvidence.Expected(orderId,operationId,next.getProviderPaymentId(),next.getAmount(),create.connection()),
            new com.example.ecsite.payment.payjp.NewFlowClosureObservation.AbortIntent((UUID)op.get("abort_request_id"),
                op.get("abort_requested_at") instanceof java.sql.Timestamp t?t.toLocalDateTime():(LocalDateTime)op.get("abort_requested_at"),
                (String)op.get("abort_requester_type"),(String)op.get("abort_reason_code"),number(op,"abort_expected_version")),
            closureBinding(creation),abort,new com.example.ecsite.payment.payjp.NewFlowClosureObservation.FinancialSnapshot(paymentSnapshot,transactionSnapshot,dispatch));
    }
    /** Only a consumed, transport-minted observation can reach this writer; no financial result is invented. */
    @Transactional(propagation=Propagation.MANDATORY)
    NewFlowClosureControl.RecordResult recordNewFlowClosure(com.example.ecsite.payment.payjp.NewFlowClosureObservation observation){
        var expected=Objects.requireNonNull(observation).consumeBound(); // Irreversible first action, including direct internal Tx calls.
        final NewFlowClosureControl.Expected current;
        try{
            current=loadNewFlowClosureExpected(expected.flow().orderId(),expected.flow().operationId());
            observation.requireConsumedFor(current);
        }catch(IllegalArgumentException|IllegalStateException conflict){return NewFlowClosureControl.RecordResult.CONFLICT;}
        if(!expected.matches(current))return NewFlowClosureControl.RecordResult.CONFLICT;
        var id=current.flow().operationId();var row=jdbc.queryForMap("select * from order_change_operations where id=?",id);
        byte[] digest=NewFlowClosureDigest.compute(current);
        boolean frozen=row.get("abort_flow_closed_at")!=null;
        if(!settleClosureFinancialHistory(current,frozen))return NewFlowClosureControl.RecordResult.CONFLICT;
        if(frozen){
            return ((Number)row.get("abort_flow_close_version")).intValue()==1
                &&MessageDigest.isEqual(digest,(byte[])row.get("abort_flow_close_digest"))
                ?NewFlowClosureControl.RecordResult.REPLAYED:NewFlowClosureControl.RecordResult.CONFLICT;
        }
        observation.requireConsumedFor(current);
        int updated=jdbc.update("""
            update order_change_operations set abort_flow_closed_at=clock_timestamp(),abort_flow_close_digest=?,abort_flow_close_version=1,
                state=case when state='ABORT_UNKNOWN' then state else 'ABORTING' end,
                version=version+1,updated_at=timezone('Asia/Tokyo',clock_timestamp())
            where id=? and order_id=? and version=? and abort_request_id=? and new_payment_id=?
                and abort_flow_closed_at is null and abort_flow_close_digest is null and abort_flow_close_version is null
                and switch_decided_at is null and content_change_history_id is null
                and state in ('AUTHORIZING','REQUIRES_ACTION','AUTH_UNKNOWN','AUTHORIZED','ABORTING','ABORT_UNKNOWN')
            """,digest,id,current.flow().orderId(),current.version(),current.intent().requestId(),current.paymentId());
        // Financial writes have already been flushed. A lost CAS must roll them all back.
        if(updated!=1)throw new IllegalStateException("Financial closure CAS conflict");
        return NewFlowClosureControl.RecordResult.RECORDED;
    }
    /** Applies only a fully identified terminal history. A canceled Flow never invents AUTHORIZE history.
     * All eligibility checks precede writes; frozen legacy inconsistencies are observation-only conflicts.
     */
    private boolean settleClosureFinancialHistory(NewFlowClosureControl.Expected expected,boolean frozen){
        var next=payments.findById(expected.paymentId()).orElseThrow();
        var own=transactions.findByPaymentIdOrderByCreatedAtAscIdAsc(next.getId());
        var operation=expected.flow().operationId();var flow=expected.flow().newFlowId();
        var authorizations=own.stream().filter(t->t.getPurpose()==PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION).toList();
        if(authorizations.size()!=1||own.stream().anyMatch(t->!operation.equals(t.getOrderChangeOperationId())
                ||!Objects.equals(t.getPayment().getOrder().getId(),expected.flow().orderId())||t.getAmount()!=expected.flow().amount()))return false;
        var auth=authorizations.getFirst();
        if(auth.getCompletedAt()==null||auth.getStatus()==PaymentTransactionStatus.PENDING)return false;
        var createState=jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,expected.create().id());
        if(!List.of("STARTED","OBSERVED").contains(createState)||frozen&&!"OBSERVED".equals(createState))return false;
        PaymentTransaction cancel=null;
        if(auth.getStatus()==PaymentTransactionStatus.FAILED){
            if(own.size()!=1||expected.abort().isPresent()
                    ||!List.of(PaymentStatus.PENDING,PaymentStatus.REQUIRES_ACTION,PaymentStatus.FAILED).contains(next.getStatus())
                    ||frozen&&next.getStatus()!=PaymentStatus.FAILED
                    ||auth.getProviderTransactionId()!=null&&!flow.equals(auth.getProviderTransactionId()))return false;
        }else if(auth.getStatus()==PaymentTransactionStatus.SUCCEEDED){
            if(!flow.equals(auth.getProviderTransactionId())||own.size()!=2||expected.abort().isEmpty()
                    ||!List.of(PaymentStatus.AUTHORIZED,PaymentStatus.CANCELLED).contains(next.getStatus()))return false;
            var request=requests.find(expected.abort().orElseThrow().id());
            cancel=own.stream().filter(t->t.getId().equals(request.transactionId())).findFirst().orElse(null);
            if(cancel==null||cancel.getTransactionType()!=PaymentTransactionType.CANCEL
                    ||cancel.getPurpose()!=PaymentTransactionPurpose.ORDER_CHANGE_NEW_AUTH_ABORT
                    ||!List.of(PaymentTransactionStatus.PENDING,PaymentTransactionStatus.SUCCEEDED).contains(cancel.getStatus()))return false;
            var dispatch=jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,request.id());
            if(!List.of("STARTED","OBSERVED").contains(dispatch))return false;
            if(cancel.getStatus()==PaymentTransactionStatus.SUCCEEDED
                    &&(!flow.equals(cancel.getProviderTransactionId())||cancel.getCompletedAt()==null))return false;
            if(cancel.getStatus()==PaymentTransactionStatus.PENDING&&cancel.getCompletedAt()!=null)return false;
            if(frozen&&(next.getStatus()!=PaymentStatus.CANCELLED||cancel.getStatus()!=PaymentTransactionStatus.SUCCEEDED||!"OBSERVED".equals(dispatch)))return false;
        }else return false;
        if(frozen)return true;
        // V64 still permits these writes while closure is NULL. Flush BEFORE setting the freeze flag.
        if(auth.getStatus()==PaymentTransactionStatus.FAILED){
            if(next.getStatus()!=PaymentStatus.FAILED)next.markFailed(now());
        }else{
            if(cancel.getStatus()==PaymentTransactionStatus.PENDING)cancel.markSucceeded(flow,now());
            if(next.getStatus()!=PaymentStatus.CANCELLED)next.markCancelled(now());
            requests.observed(expected.flow().operationId(),"NEW_ABORT");
        }
        requests.observed(expected.flow().operationId(),"CREATE");
        em.flush();
        return true;
    }
    /** Tx A/B expectation loader; a completed receipt does not depend on subsequent normal order business. */
    @Transactional(propagation=Propagation.MANDATORY)
    com.example.ecsite.payment.payjp.OldAuthorizationObservation.Expected loadAbortCompletionExpected(long orderId,UUID operationId){
        var initial=lock(orderId,operationId);
        if("ABORTED".equals(initial.operation().get("state"))){
            requireAbortCompletionReceipt(initial);return null;
        }
        var closure=loadNewFlowClosureExpected(orderId,operationId);
        var op=jdbc.queryForMap("select * from order_change_operations where id=?",operationId);
        if(!List.of("ABORTING","ABORT_UNKNOWN").contains(op.get("state"))||op.get("abort_completed_at")!=null
                ||op.get("abort_flow_closed_at")==null||integer(op,"abort_flow_close_version")!=1
                ||!MessageDigest.isEqual(NewFlowClosureDigest.compute(closure),(byte[])op.get("abort_flow_close_digest"))
                ||!settleClosureFinancialHistory(closure,true))throw new IllegalStateException("New financial closure not established");
        var old=payment(initial,number(op,"old_payment_id"));
        var own=transactions.findByPaymentIdOrderByCreatedAtAscIdAsc(old.getId());own.forEach(em::refresh);
        if(old.getProvider()!=PaymentProvider.PAYJP||old.getPaymentMethod()!=PaymentMethod.CARD
                ||old.getStatus()!=PaymentStatus.AUTHORIZED||old.getAmount()!=integer(op,"before_amount")
                ||old.getProviderPaymentId()==null||own.size()!=1)throw new IllegalStateException("Original authorization history changed");
        var auth=own.getFirst();
        if(auth.getTransactionType()!=PaymentTransactionType.AUTHORIZE||auth.getStatus()!=PaymentTransactionStatus.SUCCEEDED
                ||auth.getCompletedAt()==null||!Objects.equals(auth.getProviderTransactionId(),old.getProviderPaymentId())
                ||auth.getOrderContentRevision()!=integer(op,"base_content_revision")||auth.getAmount()!=old.getAmount()
                ||!List.of(PaymentTransactionPurpose.INITIAL_ORDER_AUTHORIZATION,PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION).contains(auth.getPurpose()))
            throw new IllegalStateException("Original authorization evidence changed");
        // Entire local snapshots catch changes not represented by operation.version (including held_at).
        String snapshot=jdbc.queryForObject("""
            select jsonb_build_array(to_jsonb(o),to_jsonb(c),
                (select jsonb_agg(to_jsonb(p) order by p.id) from payments p where p.order_id=o.id),
                (select jsonb_agg(to_jsonb(t) order by t.payment_id,t.id) from payment_transactions t join payments p on p.id=t.payment_id where p.order_id=o.id),
                (select jsonb_agg(to_jsonb(i) order by i.id) from order_items i where i.order_id=o.id),
                (select jsonb_agg(to_jsonb(i) order by i.order_item_id) from order_change_items i where i.operation_id=c.id),
                (select jsonb_agg(to_jsonb(r) order by r.order_item_id) from inventory_reservations r where r.operation_id=c.id))::text
            from orders o join order_change_operations c on c.order_id=o.id where o.id=? and c.id=?
            """,String.class,orderId,operationId);
        return new com.example.ecsite.payment.payjp.OldAuthorizationObservation.Expected(orderId,operationId,old.getId(),old.getProviderPaymentId(),
            old.getAmount(),closure.flow().connection(),number(op,"version"),
            HexFormat.of().formatHex(NewFlowClosureDigest.hash(List.of("abort-completion-snapshot/v1",snapshot))));
    }
    /** Strict, single-use old authorization evidence gates this one atomic inventory transaction. */
    @Transactional(propagation=Propagation.MANDATORY)
    RequestedAbortCompletionControl.Result completeRequestedAbort(com.example.ecsite.payment.payjp.OldAuthorizationObservation observation){
        var expected=Objects.requireNonNull(observation).consumeBound();
        var current=loadAbortCompletionExpected(expected.orderId(),expected.operationId());
        if(current==null)return RequestedAbortCompletionControl.Result.REPLAYED;
        observation.requireConsumedFor(current);
        var data=lock(current.orderId(),current.operationId());var held=changes.reservations(current.operationId());
        for(long product:held.stream().map(OrderChangeStore.Reservation::productId).distinct().sorted().toList())
            em.refresh(products.findByIdForUpdateIncludingInactive(product).orElseThrow());
        observation.requireConsumedFor(current);
        // The movement ID is required by RELEASED's FK/CHECK. Both operations remain in this Tx.
        for(var reservation:held){
            long movement=inventory.releaseForOrderChange(reservation.productId(),reservation.quantity(),current.orderId(),current.operationId(),reservation.itemId());
            changes.released(current.operationId(),reservation.itemId(),movement,now());
        }
        em.flush();
        var digest=abortCompletionDigest(data);
        observation.requireConsumedFor(current);
        if(jdbc.update("""
            update order_change_operations set state='ABORTED',abort_completed_at=clock_timestamp(),abort_completion_digest=?,
                abort_completion_version=1,abort_old_authorization_observed_at=?,version=version+1,updated_at=timezone('Asia/Tokyo',clock_timestamp())
            where id=? and order_id=? and version=? and state in ('ABORTING','ABORT_UNKNOWN')
                and abort_completed_at is null and abort_request_id is not null and abort_flow_closed_at is not null
                and switch_decided_at is null and content_change_history_id is null
            """,digest,observation.completedAt().atOffset(ZoneOffset.UTC),current.operationId(),current.orderId(),current.version())!=1)
            throw new IllegalStateException("Abort completion CAS conflict");
        return RequestedAbortCompletionControl.Result.COMPLETED;
    }
    private void requireAbortCompletionReceipt(Locked data){
        var op=data.operation();
        if(op.get("abort_request_id")==null||op.get("abort_completed_at")==null||op.get("abort_old_authorization_observed_at")==null
                ||integer(op,"abort_completion_version")!=1||op.get("abort_flow_closed_at")==null
                ||op.get("switch_decided_at")!=null||op.get("content_change_history_id")!=null
                ||!MessageDigest.isEqual(abortCompletionDigest(data),(byte[])op.get("abort_completion_digest")))
            throw new IllegalStateException("Abort completion receipt conflict");
    }
    /** Stable v1 receipt: excludes mutable current Order/old-Payment status, retaining original roles,
     * frozen new history/request identity and exact release movements. Later capture/cancel/increase is legitimate. */
    private byte[] abortCompletionDigest(Locked data){
        var op=data.operation();var id=(UUID)op.get("id");
        String proof=jdbc.queryForObject("""
            select jsonb_build_array(c.id,c.order_id,c.customer_id,c.abort_request_id,c.old_payment_id,c.new_payment_id,
                c.before_amount,c.after_amount,c.base_content_revision,c.base_order_fingerprint,
                encode(c.abort_flow_close_digest,'hex'),c.abort_flow_close_version,
                (select jsonb_build_array(p.id,p.provider,p.payment_method,p.amount,p.provider_payment_id) from payments p where p.id=c.old_payment_id),
                (select jsonb_build_array(p.id,p.provider,p.payment_method,p.amount,p.provider_payment_id,p.status) from payments p where p.id=c.new_payment_id),
                (select jsonb_agg(jsonb_build_array(t.id,t.payment_id,t.transaction_type,t.purpose,t.status,t.amount,t.order_content_revision,t.provider_transaction_id,t.completed_at) order by t.id) from payment_transactions t where t.payment_id=c.new_payment_id),
                (select jsonb_agg(jsonb_build_array(r.id,encode(r.request_fingerprint,'hex'),d.state) order by r.id) from order_change_financial_requests r left join order_change_request_dispatches d on d.request_id=r.id where r.operation_id=c.id),
                (select jsonb_agg(to_jsonb(i) order by i.order_item_id) from order_change_items i where i.operation_id=c.id),
                (select jsonb_agg(jsonb_build_array(to_jsonb(r),to_jsonb(m)) order by r.order_item_id) from inventory_reservations r left join stock_movements m on m.id=r.release_movement_id where r.operation_id=c.id))::text
            from order_change_operations c where c.id=?
            """,String.class,id);
        return NewFlowClosureDigest.hash(List.of("ec-site/order-change/abort-completed/v1",proof));
    }
    private static com.example.ecsite.payment.payjp.NewFlowClosureObservation.RequestBinding closureBinding(StoredOrderChangeRequest request){
        return new com.example.ecsite.payment.payjp.NewFlowClosureObservation.RequestBinding(request.id(),HexFormat.of().formatHex(request.httpRequest().fingerprint()));
    }
    /** Classification only; every subsequent writer/transport expectation independently revalidates. */
    public record WithdrawalFacts(boolean local,boolean authorized,boolean terminalAuthorization,Long abortRequest,boolean ready){}
    public WithdrawalFacts withdrawalFacts(long orderId,long customerId,UUID operationId){
        var data=lock(orderId,operationId);var op=data.operation();
        if(data.order().getUserId()!=customerId)throw new com.example.ecsite.exception.OrderNotFoundException(orderId);
        boolean local=!abortRequested(data)&&!changes.financialStarted(operationId)
            &&List.of("RESERVED","ABORTED").contains(op.get("state"));
        if(op.get("new_payment_id")==null)return new WithdrawalFacts(local,false,false,null,false);
        var next=payment(data,number(op,"new_payment_id"));
        var auths=data.transactions().stream().filter(t->operationId.equals(t.getOrderChangeOperationId())
            &&t.getPurpose()==PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION).toList();
        boolean identified=auths.size()==1&&validAuthorization(data,auths.getFirst(),next)&&next.getProviderPaymentId()!=null;
        boolean authorized=identified&&next.getStatus()==PaymentStatus.AUTHORIZED&&auths.getFirst().getStatus()==PaymentTransactionStatus.SUCCEEDED;
        boolean terminal=identified&&((next.getStatus()==PaymentStatus.FAILED&&auths.getFirst().getStatus()==PaymentTransactionStatus.FAILED)
            ||(next.getStatus()==PaymentStatus.CANCELLED&&auths.getFirst().getStatus()==PaymentTransactionStatus.SUCCEEDED));
        var ids=jdbc.queryForList("select id from order_change_financial_requests where operation_id=? and request_kind='NEW_ABORT'",Long.class,operationId);
        Long request=ids.size()==1?ids.getFirst():null;
        boolean ready=request!=null&&"ABORTING".equals(op.get("state"))&&Boolean.TRUE.equals(jdbc.queryForObject(
            "select exists(select 1 from order_change_request_dispatches where request_id=? and state='READY')",Boolean.class,request));
        return new WithdrawalFacts(local,authorized,terminal,request,ready);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    NewFlowClosureControl.Expected loadAbortPreparationExpected(long orderId,UUID operationId){
        var expected=loadNewFlowClosureExpected(orderId,operationId);
        var op=jdbc.queryForMap("select * from order_change_operations where id=?",operationId);
        var next=payments.findById(expected.paymentId()).orElseThrow();
        var auth=transactions.findById(requests.find(expected.create().id()).transactionId()).orElseThrow();
        if(expected.abort().isPresent()||op.get("abort_flow_closed_at")!=null||next.getStatus()!=PaymentStatus.AUTHORIZED
            ||auth.getStatus()!=PaymentTransactionStatus.SUCCEEDED||auth.getCompletedAt()==null)
            throw new IllegalStateException("Known authorization required before abort preparation");
        return expected;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    StoredOrderChangeRequest prepareTrustedAbort(com.example.ecsite.payment.payjp.AbortPreparationObservation observation){
        var expected=Objects.requireNonNull(observation).consumeBound();
        var current=loadAbortPreparationExpected(expected.flow().orderId(),expected.flow().operationId());
        observation.requireConsumedFor(current);
        return prepareRequestedAbort(current.flow().orderId(),current.flow().operationId(),current.version(),
            new RequestedAbortEvidence(current.flow().orderId(),current.flow().operationId(),current.paymentId(),current.flow()));
    }
    /** Dedicated internal read/claim boundary. The general Store/Sender guard remains unchanged. */
    @Transactional(propagation=Propagation.MANDATORY)
    RequestedAbortDispatchControl.Context loadRequestedAbortDispatchContext(long requestId){
        var saved=requests.find(requestId);var request=saved.httpRequest();
        if(!"NEW_ABORT".equals(request.kind())||request.purpose()!=PaymentTransactionPurpose.ORDER_CHANGE_NEW_AUTH_ABORT
                ||!"CANCEL".equals(request.transactionType()))throw new IllegalStateException("Expected dedicated new abort request");
        var data=lock(request.orderId(),request.operationId());var op=data.operation();
        if(!abortRequested(data)||!"ABORTING".equals(op.get("state"))||op.get("switch_decided_at")!=null
                ||op.get("content_change_history_id")!=null||op.get("abort_flow_closed_at")!=null
                ||op.get("abort_flow_close_digest")!=null||op.get("abort_flow_close_version")!=null
                ||op.get("new_payment_id")==null||number(op,"new_payment_id")!=saved.paymentId()
                ||number(op,"old_payment_id")==saved.paymentId())throw new IllegalStateException("Requested abort dispatch is not eligible");
        var states=jdbc.queryForList("select state from order_change_request_dispatches where request_id=?",String.class,requestId);
        if(states.size()!=1||!"READY".equals(states.getFirst()))throw new IllegalStateException("Abort dispatch is legacy or consumed");
        return new RequestedAbortDispatchControl.Context(saved,number(op,"version"));
    }
    /** Full read-only expectation snapshot. No request creation, dispatch claim or state mutation. */
    @Transactional(propagation=Propagation.MANDATORY)
    com.example.ecsite.payment.payjp.RequestedAbortObservation.Expected loadRequestedAbortObservationExpected(long requestId){
        var context=loadRequestedAbortDispatchContext(requestId);var saved=context.request();var request=saved.httpRequest();
        var data=lock(request.orderId(),request.operationId());var op=data.operation();
        jdbc.queryForList("select t.id from payment_transactions t join payments p on p.id=t.payment_id where p.order_id=? order by t.payment_id,t.id for update of t",request.orderId());
        validateBase(data);requireHeld(data);
        var next=payment(data,saved.paymentId());var old=payment(data,number(op,"old_payment_id"));
        var auth=transaction(data,PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION);
        var cancel=transaction(data,PaymentTransactionPurpose.ORDER_CHANGE_NEW_AUTH_ABORT);
        if(next.getId().equals(old.getId())||old.getStatus()!=PaymentStatus.AUTHORIZED||next.getStatus()!=PaymentStatus.AUTHORIZED
                ||next.getProvider()!=PaymentProvider.PAYJP||next.getPaymentMethod()!=PaymentMethod.CARD
                ||next.getAmount()!=integer(op,"after_amount")||!validAuthorization(data,auth,next)||auth.getStatus()!=PaymentTransactionStatus.SUCCEEDED
                ||cancel.getId()!=saved.transactionId()||cancel.getPayment().getId().longValue()!=saved.paymentId()
                ||cancel.getStatus()!=PaymentTransactionStatus.PENDING||cancel.getTransactionType()!=PaymentTransactionType.CANCEL
                ||cancel.getAmount()!=request.amount()||cancel.getOrderContentRevision()!=integer(op,"base_content_revision")
                ||!Objects.equals(cancel.getIdempotencyKey(),request.idempotencyKey())
                ||next.getAmount()!=request.amount()||!Objects.equals(next.getProviderPaymentId(),request.flowId())
                ||!request.connection().equals(connection.requireIdentity(provider)))throw new IllegalStateException("Abort observation saved target mismatch");
        requireNoPending(data,cancel.getId());
        if(data.transactions().stream().anyMatch(t->Objects.equals(t.getOrderChangeOperationId(),request.operationId())
                &&t.getPurpose()==PaymentTransactionPurpose.ORDER_CHANGE_OLD_AUTH_CANCEL)
                ||jdbc.queryForObject("select count(*) from order_change_financial_requests where operation_id=? and request_kind='OLD_CANCEL'",Integer.class,request.operationId())!=0)
            throw new IllegalStateException("Old cancellation already exists");
        var creationId=jdbc.queryForObject("select id from order_change_financial_requests where operation_id=? and request_kind='CREATE'",Long.class,request.operationId());
        var creation=requests.find(creationId);var create=creation.httpRequest();
        if(creation.paymentId()!=saved.paymentId()||!create.connection().equals(request.connection())
                ||create.amount()!=request.amount()||create.orderId()!=request.orderId()
                ||!create.operationId().equals(request.operationId()))throw new IllegalStateException("Create context mismatch");
        var states=jdbc.queryForList("select state from order_change_request_dispatches where request_id=? for update",String.class,requestId);
        if(states.size()!=1||!"READY".equals(states.getFirst()))throw new IllegalStateException("Abort request is not READY");
        saved.requireReplayAt(jdbc.queryForObject("select clock_timestamp()",OffsetDateTime.class).toInstant());
        return new com.example.ecsite.payment.payjp.RequestedAbortObservation.Expected(saved.id(),saved.paymentId(),creation.id(),
            request.fingerprint(),context.version(),new NewFlowTerminationEvidence.Expected(request.orderId(),request.operationId(),
                request.flowId(),request.amount(),request.connection()));
    }
    @Transactional(propagation=Propagation.MANDATORY)
    RequestedAbortDispatchControl.Claimed claimTrustedRequestedAbortDispatch(
            com.example.ecsite.payment.payjp.RequestedAbortObservation.Expected expected,
            com.example.ecsite.payment.payjp.RequestedAbortObservation observation){
        var current=loadRequestedAbortObservationExpected(expected.requestId());
        observation.requireConsumedFor(current);
        if(!expected.matches(current))throw new IllegalStateException("Abort observation/version conflict");
        var saved=requests.find(current.requestId());
        saved.requireReplayAt(jdbc.queryForObject("select clock_timestamp()",OffsetDateTime.class).toInstant());
        observation.requireConsumedFor(current);
        return markRequestedAbortStarted(saved,current.version());
    }
    /** Narrow outcome recording only; never calls legacy cancellation(), release() or provider transport. */
    @Transactional(propagation=Propagation.MANDATORY)
    boolean recordRequestedAbortPostUnknown(RequestedAbortDispatchControl.Dispatch dispatch,boolean manualReview){
        var claim=dispatch.claimedForOutcome();var saved=requests.find(claim.request().id());var request=saved.httpRequest();
        if(!new RequestedAbortDispatchControl.Context(saved,0).matches(new RequestedAbortDispatchControl.Context(claim.request(),0)))
            throw new IllegalStateException("Abort outcome request changed");
        var data=lock(request.orderId(),request.operationId());var op=data.operation();
        jdbc.queryForList("select t.id from payment_transactions t join payments p on p.id=t.payment_id where p.order_id=? order by t.payment_id,t.id for update of t",request.orderId());
        var dispatchState=jdbc.queryForList("select state from order_change_request_dispatches where request_id=? for update",String.class,saved.id());
        if(dispatchState.size()!=1||!"STARTED".equals(dispatchState.getFirst())||!abortRequested(data)
                ||op.get("new_payment_id")==null||number(op,"new_payment_id")!=saved.paymentId()
                ||number(op,"old_payment_id")==saved.paymentId()||op.get("switch_decided_at")!=null
                ||op.get("content_change_history_id")!=null||op.get("abort_flow_closed_at")!=null
                ||op.get("abort_flow_close_digest")!=null||op.get("abort_flow_close_version")!=null)return false;
        if("ABORT_UNKNOWN".equals(op.get("state"))&&number(op,"version")==claim.version()+1
                &&"NEW_ABORT_POST_UNKNOWN".equals(op.get("financial_review_reason")))return true;
        if(!"ABORTING".equals(op.get("state"))||number(op,"version")!=claim.version())return false;
        return jdbc.update("""
            update order_change_operations set state=?,financial_review_reason=?,version=version+1,
                updated_at=timezone('Asia/Tokyo',clock_timestamp())
            where id=? and order_id=? and version=? and state='ABORTING' and abort_request_id is not null
              and new_payment_id=? and switch_decided_at is null and content_change_history_id is null
              and abort_flow_closed_at is null and abort_flow_close_digest is null and abort_flow_close_version is null
            """,manualReview?"MANUAL_REVIEW":"ABORT_UNKNOWN",manualReview?"NEW_ABORT_HTTP_REVIEW":"NEW_ABORT_POST_UNKNOWN",
                request.operationId(),request.orderId(),claim.version(),saved.paymentId())==1;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    RequestedAbortDispatchControl.Claimed claimRequestedAbortDispatch(RequestedAbortDispatchControl.Context expected,
            RequestedAbortDispatchControl.Observation observation){
        if(expected==null||observation==null)throw new IllegalArgumentException("Validated abort observation required");
        var current=loadRequestedAbortDispatchContext(expected.request().id());
        if(!expected.matches(current))throw new IllegalStateException("Abort dispatch context/version conflict");
        observation.requireContext(current);
        var request=current.request().httpRequest();
        // Reuses full base-order, successful authorization, connection and HELD validation.
        // The request is known to exist; preparation must only return that same immutable request.
        var saved=prepareRequestedAbort(request.orderId(),request.operationId(),current.version(),observation.authorization());
        if(!current.matches(new RequestedAbortDispatchControl.Context(saved,current.version())))
            throw new IllegalStateException("Abort request identity changed");
        if(!"PENDING".equals(jdbc.queryForObject("select status from payment_transactions where id=?",String.class,saved.transactionId())))
            throw new IllegalStateException("Settled cancellation is observation only");
        // All transaction/reservation locks are already held; the dispatch row is locked last.
        var state=jdbc.queryForList("select state from order_change_request_dispatches where request_id=? for update",String.class,saved.id());
        if(state.size()!=1||!"READY".equals(state.getFirst()))throw new IllegalStateException("Abort dispatch permit unavailable");
        var dbNow=jdbc.queryForObject("select clock_timestamp()",OffsetDateTime.class).toInstant();
        observation.requireFresh(dbNow);saved.requireReplayAt(dbNow);
        return markRequestedAbortStarted(saved,current.version());
    }
    private RequestedAbortDispatchControl.Claimed markRequestedAbortStarted(StoredOrderChangeRequest saved,long version){
        var request=saved.httpRequest();
        if(jdbc.update("""
            update order_change_operations set version=version+1,updated_at=timezone('Asia/Tokyo',clock_timestamp())
            where id=? and order_id=? and version=? and state='ABORTING' and abort_request_id is not null
              and switch_decided_at is null and content_change_history_id is null
              and abort_flow_closed_at is null and abort_flow_close_digest is null and abort_flow_close_version is null
            """,request.operationId(),request.orderId(),version)!=1)throw new IllegalStateException("Abort dispatch version CAS conflict");
        if(jdbc.update("update order_change_request_dispatches set state='STARTED',started_at=clock_timestamp() where request_id=? and state='READY'",saved.id())!=1)
            throw new IllegalStateException("Abort dispatch CAS conflict");
        return new RequestedAbortDispatchControl.Claimed(saved,version+1);
    }
    /** Internal observation boundary only. No production transport or HTTP caller is connected in A2-2a. */
    static final class RequestedAbortEvidence {
        final long orderId,paymentId;final UUID operationId;
        final com.example.ecsite.payment.orderchange.NewFlowTerminationEvidence.Expected expected;
        private RequestedAbortEvidence(long order,UUID operation,long payment,
                com.example.ecsite.payment.orderchange.NewFlowTerminationEvidence.Expected expected){
            this.orderId=order;this.operationId=operation;this.paymentId=payment;this.expected=expected;
        }
        static RequestedAbortEvidence verify(long order,UUID operation,long payment,String flow,int amount,
                com.example.ecsite.payment.capture.CaptureConnectionIdentity connection,tools.jackson.databind.JsonNode body){
            var expected=new NewFlowTerminationEvidence.Expected(order,operation,flow,amount,connection);
            if(payment<=0)throw new IllegalArgumentException("Invalid abort payment");
            PayJpPaymentFlowObservation.requireRequestedAbortAuthorization(expected,body);
            return new RequestedAbortEvidence(order,operation,payment,expected);
        }
        @Override public String toString(){return "RequestedAbortEvidence[redacted]";}
    }
    /** Unforgeable repository permit, minted only inside the validated preparation transaction. */
    public static final class PreparedAbortRequest {
        private final long paymentId,transactionId;private final OrderChangeHttpRequest request;
        private PreparedAbortRequest(long payment,long transaction,OrderChangeHttpRequest request){paymentId=payment;transactionId=transaction;this.request=request;}
        public long paymentId(){return paymentId;}
        public long transactionId(){return transactionId;}
        public OrderChangeHttpRequest request(){return request;}
        @Override public String toString(){return "PreparedAbortRequest[redacted]";}
    }
    /** Package-private, unconnected preparation only. This neither starts dispatch nor closes a Flow. */
    StoredOrderChangeRequest prepareRequestedAbort(long orderId,UUID operationId,long expectedVersion,RequestedAbortEvidence evidence){
        var data=lock(orderId,operationId);var op=data.operation();
        if(evidence==null||!abortRequested(data)||terminal(data)||op.get("switch_decided_at")!=null
                ||op.get("content_change_history_id")!=null||op.get("abort_flow_closed_at")!=null
                ||!List.of("AUTHORIZING","REQUIRES_ACTION","AUTH_UNKNOWN","AUTHORIZED","ABORTING").contains(op.get("state"))
                ||number(op,"version")!=expectedVersion)throw new IllegalStateException("Requested abort preparation is not eligible");
        // lock() owns Order/operation/all Payments; take transaction locks before reservation locks.
        // Replay then re-enters the Store with these same locks already held.
        jdbc.queryForList("select t.id from payment_transactions t join payments p on p.id=t.payment_id where p.order_id=? order by t.payment_id,t.id for update of t",orderId);
        validateBase(data);requireHeld(data);
        var next=payment(data,number(op,"new_payment_id"));var old=payment(data,number(op,"old_payment_id"));
        var auth=transaction(data,PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION);
        if(next.getId().equals(old.getId())||old.getStatus()!=PaymentStatus.AUTHORIZED||next.getStatus()!=PaymentStatus.AUTHORIZED
                ||next.getProvider()!=PaymentProvider.PAYJP||next.getPaymentMethod()!=PaymentMethod.CARD
                ||next.getAmount()!=integer(op,"after_amount")||!validAuthorization(data,auth,next)||auth.getStatus()!=PaymentTransactionStatus.SUCCEEDED
                ||evidence.orderId!=orderId||!operationId.equals(evidence.operationId)||evidence.paymentId!=next.getId()
                ||!Objects.equals(evidence.expected.newFlowId(),next.getProviderPaymentId())||evidence.expected.amount()!=next.getAmount()
                ||!evidence.expected.connection().equals(connection.requireIdentity(provider)))throw new IllegalStateException("New abort authorization target mismatch");
        if(data.transactions().stream().anyMatch(t->Objects.equals(t.getOrderChangeOperationId(),operationId)
                &&t.getPurpose()==PaymentTransactionPurpose.ORDER_CHANGE_OLD_AUTH_CANCEL)
                ||jdbc.queryForObject("select count(*) from order_change_financial_requests where operation_id=? and request_kind='OLD_CANCEL'",Integer.class,operationId)!=0)
            throw new IllegalStateException("Old cancellation already exists");
        var creation=jdbc.queryForMap("select * from order_change_financial_requests where operation_id=? and request_kind='CREATE'",operationId);
        var identity=evidence.expected.connection();
        if(number(creation,"payment_id")!=next.getId()||!identity.connectionId().equals(creation.get("connection_id"))
                ||!identity.providerAccountRef().equals(creation.get("provider_account_ref"))||!identity.mode().equals(creation.get("mode"))
                ||!identity.apiOrigin().equals(creation.get("api_origin")))throw new IllegalStateException("Create connection mismatch");
        var existing=jdbc.queryForList("select id from order_change_financial_requests where operation_id=? and request_kind='NEW_ABORT'",Long.class,operationId);
        var cancels=data.transactions().stream().filter(t->Objects.equals(t.getOrderChangeOperationId(),operationId)
                &&t.getPurpose()==PaymentTransactionPurpose.ORDER_CHANGE_NEW_AUTH_ABORT).toList();
        if(!existing.isEmpty()){
            if(!"ABORTING".equals(op.get("state"))||cancels.size()!=1)throw new IllegalStateException("Abort preparation history mismatch");
            var cancel=cancels.getFirst();requireNoPending(data,cancel.getId());
            return requests.savePreparedAbort(new PreparedAbortRequest(next.getId(),cancel.getId(),OrderChangeHttpRequest.cancel(identity,
                operationId,orderId,next.getAmount(),cancel.getIdempotencyKey(),next.getProviderPaymentId(),true)));
        }
        if(!cancels.isEmpty())throw new IllegalStateException("Abort transaction without request");
        requireNoPending(data,null);
        state(data,"ABORTING",null);
        var cancel=transactions.saveAndFlush(PaymentTransaction.forOrderChange(next,PaymentTransactionType.CANCEL,next.getAmount(),integer(op,"base_content_revision"),
            UUID.randomUUID().toString(),now(),PaymentTransactionPurpose.ORDER_CHANGE_NEW_AUTH_ABORT,operationId));
        return requests.savePreparedAbort(new PreparedAbortRequest(next.getId(),cancel.getId(),OrderChangeHttpRequest.cancel(identity,
            operationId,orderId,next.getAmount(),cancel.getIdempotencyKey(),next.getProviderPaymentId(),true)));
    }
    /** Internal command only. Acceptance does not cancel any Flow or complete/release a candidate. */
    public AbortReceipt requestAbort(AbortRequest command){
        var data=lock(command.orderId(),command.operationId());var op=data.operation();long version=number(op,"version");
        if(data.order().getUserId()!=command.customerId())throw new com.example.ecsite.exception.OrderNotFoundException(command.orderId());
        if(abortRequested(data)){
            boolean same=command.requestId().equals(op.get("abort_request_id"))
                &&command.requesterType().name().equals(op.get("abort_requester_type"))
                &&command.reasonCode().name().equals(op.get("abort_reason_code"))
                &&command.expectedVersion()==number(op,"abort_expected_version");
            return new AbortReceipt(same?AbortResult.REPLAYED:AbortResult.CONTENT_CONFLICT,version);
        }
        if(op.get("switch_decided_at")!=null||op.get("content_change_history_id")!=null
                ||!List.of("RESERVED","AUTHORIZING","REQUIRES_ACTION","AUTH_UNKNOWN","AUTHORIZED","MANUAL_REVIEW").contains(op.get("state")))
            return new AbortReceipt(AbortResult.NOT_ALLOWED,version);
        if(version!=command.expectedVersion())return new AbortReceipt(AbortResult.VERSION_CONFLICT,version);
        validateBase(data);requireHeld(data);
        if(jdbc.queryForObject("select count(*) from order_change_financial_requests where operation_id=? and request_kind='OLD_CANCEL'",Integer.class,command.operationId())!=0)
            return new AbortReceipt(AbortResult.NOT_ALLOWED,version);
        try{
            int changed=jdbc.update("""
                update order_change_operations set abort_requested_at=timezone('Asia/Tokyo',clock_timestamp()),abort_request_id=?,
                    abort_requester_type=?,abort_reason_code=?,abort_expected_version=?,version=version+1,updated_at=timezone('Asia/Tokyo',clock_timestamp())
                where id=? and order_id=? and version=? and abort_request_id is null and switch_decided_at is null
                """,command.requestId(),command.requesterType().name(),command.reasonCode().name(),command.expectedVersion(),
                command.operationId(),command.orderId(),command.expectedVersion());
            if(changed!=1)throw new IllegalStateException("Abort intent CAS conflict");
        }catch(org.springframework.dao.DuplicateKeyException conflict){
            org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return new AbortReceipt(AbortResult.CONTENT_CONFLICT,version);
        }
        return new AbortReceipt(AbortResult.ACCEPTED,version+1);
    }
    private record Locked(Order order,Map<String,Object> operation,List<Payment> payments,List<PaymentTransaction> transactions,
            List<Map<String,Object>> items){}
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final PaymentTransactionRepository transactions;
    private final OrderContentChangeHistoryRepository histories;
    private final ProductRepository products;
    private final OrderChangeStore changes;
    private final OrderChangeFinancialRequestStore requests;
    private final JdbcTemplate jdbc;
    private final EntityManager em;
    private final OrderWriteContext context;
    private final OrderModificationPolicy policy;
    private final OrderAmountCalculator calculator;
    private final InventoryService inventory;
    private final CaptureConnectionProperties connection;
    private final PayJpProperties provider;
    private final OldCancelRecoveryAuditRepository recoveryAudits;
    public OrderReauthorizationTransactions(OrderRepository orders,PaymentRepository payments,PaymentTransactionRepository transactions,
            OrderContentChangeHistoryRepository histories,ProductRepository products,OrderChangeStore changes,
            OrderChangeFinancialRequestStore requests,JdbcTemplate jdbc,EntityManager em,OrderWriteContext context,
            OrderModificationPolicy policy,OrderAmountCalculator calculator,InventoryService inventory,
            CaptureConnectionProperties connection,PayJpProperties provider,OldCancelRecoveryAuditRepository recoveryAudits){
        this.orders=orders;this.payments=payments;this.transactions=transactions;this.histories=histories;this.products=products;
        this.changes=changes;this.requests=requests;this.jdbc=jdbc;this.em=em;this.context=context;this.policy=policy;
        this.calculator=calculator;this.inventory=inventory;this.connection=connection;this.provider=provider;this.recoveryAudits=recoveryAudits;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=true,isolation=Isolation.REPEATABLE_READ)
    OldCancelRecovery.Snapshot recoverySnapshot(OldCancelRecovery.Command command,boolean committing,OldCancelRecovery.Actor actor){
        requireRecoveryActor(actor);
        var order=orders.findById(command.orderId()).orElseThrow();
        var op=jdbc.queryForMap("select * from order_change_operations where id=? and order_id=?",command.operationId(),command.orderId());
        var all=payments.findByOrderIdOrderByCreatedAtAscIdAsc(command.orderId());
        var history=new ArrayList<PaymentTransaction>();for(var p:all)history.addAll(transactions.findByPaymentIdOrderByCreatedAtAscIdAsc(p.getId()));
        var data=new Locked(order,op,all,history,jdbc.queryForList("select * from order_change_items where operation_id=? order by order_item_id",command.operationId()));
        return recoveryTarget(data,command,committing,actor);
    }
    private OldCancelRecovery.Snapshot recoveryTarget(Locked data,OldCancelRecovery.Command command,boolean committing,OldCancelRecovery.Actor actor){
        var op=data.operation();long expected=committing?Math.addExact(command.expectedVersion(),1):command.expectedVersion();
        if(number(op,"version")!=expected||!(committing?"COMMIT_PENDING":"MANUAL_REVIEW").equals(op.get("state"))
                ||(!committing&&!"CANCEL_FLOW_EVIDENCE_MISMATCH".equals(op.get("financial_review_reason")))
                ||abortRequested(data)||op.get("switch_decided_at")==null||op.get("new_payment_id")==null
                ||op.get("content_change_history_id")!=null||op.get("abort_flow_closed_at")!=null)
            throw new IllegalStateException("Recovery operation is not eligible");
        validateBase(data);
        var held=jdbc.queryForList("select order_item_id,product_id,quantity,state from inventory_reservations where operation_id=?",command.operationId());
        if(held.size()!=data.items().size()||held.stream().anyMatch(r->!"HELD".equals(r.get("state"))||data.items().stream().noneMatch(i->
            number(i,"order_item_id")==number(r,"order_item_id")&&number(i,"product_id")==number(r,"product_id")&&integer(i,"increase_quantity")==integer(r,"quantity"))))
            throw new IllegalStateException("Recovery held reservation mismatch");
        var decision=((java.sql.Timestamp)op.get("switch_decided_at")).toLocalDateTime();
        if(decision.isBefore(((java.sql.Timestamp)op.get("accepted_at")).toLocalDateTime())
                ||decision.isAfter(((java.sql.Timestamp)op.get("grace_until")).toLocalDateTime()))throw new IllegalStateException("Recovery decision outside accepted window");
        var old=payment(data,number(op,"old_payment_id"));var next=payment(data,number(op,"new_payment_id"));
        var cancel=transaction(data,PaymentTransactionPurpose.ORDER_CHANGE_OLD_AUTH_CANCEL);
        var auth=transaction(data,PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION);
        if(old.getId().equals(next.getId())||old.getStatus()!=(committing?PaymentStatus.CANCELLED:PaymentStatus.AUTHORIZED)
                ||next.getStatus()!=PaymentStatus.AUTHORIZED||old.getAmount()!=integer(op,"before_amount")||next.getAmount()!=integer(op,"after_amount")
                ||old.getProvider()!=PaymentProvider.PAYJP||next.getProvider()!=PaymentProvider.PAYJP
                ||old.getPaymentMethod()!=PaymentMethod.CARD||next.getPaymentMethod()!=PaymentMethod.CARD
                ||old.getProviderPaymentId()==null||next.getProviderPaymentId()==null||old.getProviderPaymentId().equals(next.getProviderPaymentId())
                ||auth.getStatus()!=PaymentTransactionStatus.SUCCEEDED||!validAuthorization(data,auth,next)
                ||cancel.getStatus()!=(committing?PaymentTransactionStatus.SUCCEEDED:PaymentTransactionStatus.PENDING)
                ||cancel.getTransactionType()!=PaymentTransactionType.CANCEL||!cancel.getPayment().getId().equals(old.getId())
                ||cancel.getAmount()!=old.getAmount()||cancel.getOrderContentRevision()!=integer(op,"base_content_revision")
                ||(committing&&!Objects.equals(cancel.getProviderTransactionId(),old.getProviderPaymentId())))
            throw new IllegalStateException("Recovery financial history mismatch");
        var oldAuth=data.transactions().stream().filter(t->t.getPayment().getId().equals(old.getId())&&t.getTransactionType()==PaymentTransactionType.AUTHORIZE).toList();
        if(oldAuth.size()!=1||oldAuth.getFirst().getStatus()!=PaymentTransactionStatus.SUCCEEDED||oldAuth.getFirst().getAmount()!=old.getAmount()
                ||oldAuth.getFirst().getOrderContentRevision()!=integer(op,"base_content_revision")
                ||!Objects.equals(oldAuth.getFirst().getProviderTransactionId(),old.getProviderPaymentId())
                ||data.transactions().stream().anyMatch(t->t.getTransactionType()==PaymentTransactionType.CAPTURE
                    ||t.getPurpose()==PaymentTransactionPurpose.ORDER_CHANGE_NEW_AUTH_ABORT
                    ||(t.getStatus()==PaymentTransactionStatus.PENDING&&!t.getId().equals(cancel.getId()))))
            throw new IllegalStateException("Recovery competing financial operation");
        var rows=jdbc.queryForList("select id,request_kind from order_change_financial_requests where operation_id=?",command.operationId());
        if(rows.size()!=2||rows.stream().filter(r->"CREATE".equals(r.get("request_kind"))).count()!=1
                ||rows.stream().filter(r->"OLD_CANCEL".equals(r.get("request_kind"))).count()!=1)throw new IllegalStateException("Recovery request set mismatch");
        var saved=requests.find(requestId(rows,"OLD_CANCEL"));var creation=requests.find(requestId(rows,"CREATE"));
        var request=saved.httpRequest();var create=creation.httpRequest();var identity=connection.requireIdentity(provider);
        if(saved.paymentId()!=old.getId()||saved.transactionId()!=cancel.getId()||request.orderId()!=command.orderId()
                ||!request.operationId().equals(command.operationId())||request.purpose()!=PaymentTransactionPurpose.ORDER_CHANGE_OLD_AUTH_CANCEL
                ||!"OLD_CANCEL".equals(request.kind())||!"CANCEL".equals(request.transactionType())||request.amount()!=old.getAmount()
                ||!Objects.equals(request.flowId(),old.getProviderPaymentId())||!request.idempotencyKey().equals(cancel.getIdempotencyKey())
                ||!request.connection().equals(identity)||creation.paymentId()!=next.getId()||creation.transactionId()!=auth.getId()
                ||create.orderId()!=command.orderId()||!create.operationId().equals(command.operationId())||!"CREATE".equals(create.kind())
                ||create.amount()!=next.getAmount()||!create.idempotencyKey().equals(auth.getIdempotencyKey())||!create.connection().equals(identity))
            throw new IllegalStateException("Recovery saved request mismatch");
        if(!List.of(committing?"OBSERVED":"STARTED").equals(jdbc.queryForList("select state from order_change_request_dispatches where request_id=?",String.class,saved.id()))
                ||!List.of("OBSERVED").equals(jdbc.queryForList("select state from order_change_request_dispatches where request_id=?",String.class,creation.id())))
            throw new IllegalStateException("Recovery dispatch state mismatch");
        if(committing){
            var audit=jdbc.queryForMap("select * from order_change_recovery_audits where request_id=?",command.requestId());
            OldCancelRecoveryAuditRepository.requireIdentity(audit,command,actor);
            if(!"COMMIT_PENDING".equals(audit.get("result_code"))||number(audit,"applied_version")!=expected
                    ||number(audit,"old_cancel_request_id")!=saved.id()||!old.getProviderPaymentId().equals(audit.get("old_flow_id"))
                    ||!next.getProviderPaymentId().equals(audit.get("new_flow_id")))throw new IllegalStateException("Recovery resume is not anchored");
        }
        return new OldCancelRecovery.Snapshot(command,expected,committing,old.getId(),next.getId(),saved,creation,
            old.getProviderPaymentId(),next.getProviderPaymentId(),recoveryDigest(data));
    }
    private String recoveryDigest(Locked data){
        var text=new StringBuilder("old-cancel-recovery/context/v1").append(baseFingerprint(data.order()));
        text.append(jdbc.queryForObject("select to_jsonb(o)::text from order_change_operations o where id=?",String.class,data.operation().get("id")));
        text.append(jdbc.queryForList("select to_jsonb(p)::text from payments p where order_id=? order by id",String.class,data.order().getId()));
        text.append(jdbc.queryForList("select to_jsonb(t)::text from payment_transactions t join payments p on p.id=t.payment_id where p.order_id=? order by t.id",String.class,data.order().getId()));
        for(String table:List.of("order_change_items","inventory_reservations","order_change_financial_requests"))
            text.append(jdbc.queryForList("select to_jsonb(r)::text from "+table+" r where operation_id=? order by to_jsonb(r)::text",String.class,data.operation().get("id")));
        text.append(jdbc.queryForList("select to_jsonb(d)::text from order_change_request_dispatches d join order_change_financial_requests r on r.id=d.request_id where r.operation_id=? order by d.request_id",String.class,data.operation().get("id")));
        return HexFormat.of().formatHex(recoveryHash(text.toString()));
    }
    private static byte[] recoveryHash(String text){
        try{return MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("Recovery digest unavailable");}
    }
    private Locked recoveryLock(long orderId,UUID operationId){
        var data=lock(orderId,operationId);
        jdbc.queryForList("select id from payments where order_id=? order by id for update",orderId);
        jdbc.queryForList("select t.id from payment_transactions t join payments p on p.id=t.payment_id where p.order_id=? order by t.payment_id,t.id for update of t",orderId);
        data.transactions().forEach(em::refresh);
        jdbc.queryForList("select order_item_id from inventory_reservations where operation_id=? order by order_item_id for update",operationId);
        return data;
    }
    void applyRecoveredOldCancellation(OldCancelRecovery.Observation observed,OldCancelRecovery.Actor actor,Clock clock,java.util.function.LongSupplier nanos){
        requireRecoveryActor(actor);var saved=observed.snapshot;var c=saved.command();
        if(saved.committing())throw new IllegalStateException("Recovery phase mismatch");
        var data=recoveryLock(c.orderId(),c.operationId());var target=recoveryTarget(data,c,false,actor);
        var audit=recoveryAudits.locked(c.requestId());OldCancelRecoveryAuditRepository.requireIdentity(audit,c,actor);
        if((!saved.digest().equals(target.digest())||saved.version()!=target.version()||saved.cancellation().id()!=target.cancellation().id()||saved.creation().id()!=target.creation().id())||!Arrays.equals((byte[])audit.get("context_digest"),HexFormat.of().parseHex(saved.digest()))
                ||number(audit,"old_cancel_request_id")!=target.cancellation().id()
                ||!List.of("STARTED","HOLD").contains(audit.get("result_code")))throw new IllegalStateException("Recovery snapshot conflict");
        observed.consume(clock,nanos);requireRecoveryNewAuthorization(data,observed.newFlow,clock);
        var old=payment(data,saved.oldPaymentId());
        if(observed.oldFlow==null||!"canceled".equals(observed.oldFlow.status())||!matchesOldCancellation(data,old,observed.oldFlow))
            throw new OldCancelRecovery.EvidenceFailure(OldCancelRecovery.Detail.OLD_EVIDENCE_UNCONFIRMED);
        var cancel=transaction(data,PaymentTransactionPurpose.ORDER_CHANGE_OLD_AUTH_CANCEL);
        cancel.markSucceeded(old.getProviderPaymentId(),now());old.markCancelled(now());requests.observed(c.operationId(),"OLD_CANCEL");
        state(data,"COMMIT_PENDING",null);
        var evidence=recoveryHash(saved.digest()+"|"+observed.oldFlow.id()+"|"+observed.oldFlow.status()+"|"+observed.oldFlow.amountReceived()+"|"+observed.oldFlow.amountCapturable()
            +"|"+observed.newFlow.id()+"|"+observed.newFlow.amount()+"|"+observed.started+"|"+observed.finished);
        recoveryAudits.applied(c.requestId(),number(data.operation(),"version"),evidence);
    }
    OldCancelRecovery.Result completeRecoveredOrder(OldCancelRecovery.Observation observed,OldCancelRecovery.Actor actor,Clock clock,java.util.function.LongSupplier nanos){
        requireRecoveryActor(actor);var saved=observed.snapshot;var c=saved.command();
        if(!saved.committing())throw new IllegalStateException("Recovery phase mismatch");
        var data=recoveryLock(c.orderId(),c.operationId());var target=recoveryTarget(data,c,true,actor);
        var audit=recoveryAudits.locked(c.requestId());OldCancelRecoveryAuditRepository.requireIdentity(audit,c,actor);
        if((!saved.digest().equals(target.digest())||saved.version()!=target.version()||saved.cancellation().id()!=target.cancellation().id()||saved.creation().id()!=target.creation().id()))throw new IllegalStateException("Recovery completion snapshot conflict");
        observed.consume(clock,nanos);requireRecoveryNewAuthorization(data,observed.newFlow,clock);
        // Self invocation deliberately shares this transaction; never hold locks across a nested REQUIRES_NEW.
        var view=completeInternal(c.orderId(),c.operationId(),observed.newFlow,true);
        if(!"COMPLETED".equals(view.state()))throw new IllegalStateException("Recovery completion did not apply");
        var op=jdbc.queryForMap("select version,content_change_history_id from order_change_operations where id=?",c.operationId());
        recoveryAudits.completed(c.requestId(),number(op,"version"),number(op,"content_change_history_id"));
        return OldCancelRecovery.Result.COMPLETED;
    }
    private void requireRecoveryNewAuthorization(Locked data,PayJpPaymentFlowObservation observed,Clock clock){
        var next=payment(data,number(data.operation(),"new_payment_id"));
        if(!matchesFlow(data,next,observed,true)||!authorized(observed,next.getAmount())
                ||(observed.expiredAt()!=null&&!observed.expiredAt().toInstant().isAfter(clock.instant().plusSeconds(5))))
            throw new OldCancelRecovery.EvidenceFailure(OldCancelRecovery.Detail.NEW_AUTH_UNAVAILABLE);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=true)
    OldCancelRecovery.Result recoveryCompleted(OldCancelRecovery.Command c,OldCancelRecovery.Actor actor){
        requireRecoveryActor(actor);var row=jdbc.queryForMap("select * from order_change_recovery_audits where request_id=?",c.requestId());
        OldCancelRecoveryAuditRepository.requireIdentity(row,c,actor);
        var op=jdbc.queryForMap("select * from order_change_operations where id=? and order_id=?",c.operationId(),c.orderId());
        if(!"COMPLETED".equals(row.get("result_code"))||!"COMPLETED".equals(op.get("state"))
                ||!Objects.equals(row.get("content_change_history_id"),op.get("content_change_history_id"))
                ||number(row,"completed_version")!=number(op,"version"))throw new IllegalStateException("Recovery completed outcome conflict");
        return OldCancelRecovery.Result.COMPLETED;
    }
    /** Audit and business state must be read from one database snapshot during a concurrent completion. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=true,isolation=Isolation.REPEATABLE_READ)
    OldCancelRecovery.Result recoveryReadback(OldCancelRecovery.Command c,OldCancelRecovery.Actor actor){
        requireRecoveryActor(actor);
        var audit=jdbc.queryForMap("select * from order_change_recovery_audits where request_id=?",c.requestId());
        OldCancelRecoveryAuditRepository.requireIdentity(audit,c,actor);
        if("COMPLETED".equals(audit.get("result_code")))return recoveryCompleted(c,actor);
        if("COMMIT_PENDING".equals(audit.get("result_code"))){
            try{recoverySnapshot(c,true,actor);}catch(IllegalStateException changed){return OldCancelRecovery.Result.CONFLICT;}
            return OldCancelRecovery.Result.COMMIT_PENDING;
        }
        if("CONFLICT".equals(audit.get("result_code")))return OldCancelRecovery.Result.CONFLICT;
        try{recoverySnapshot(c,false,actor);}catch(IllegalStateException changed){return OldCancelRecovery.Result.CONFLICT;}
        return OldCancelRecovery.Result.HOLD;
    }
    private void requireRecoveryActor(OldCancelRecovery.Actor actor){
        var authentication=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null||!authentication.isAuthenticated()||authentication.getAuthorities().stream().noneMatch(a->"ROLE_ADMIN".equals(a.getAuthority()))
                ||!(authentication.getPrincipal() instanceof com.example.ecsite.security.AdminUserDetails principal)||!principal.isEnabled()
                ||actor==null||!Objects.equals(principal.getId(),actor.id())||!Objects.equals(principal.getUsername(),actor.username()))
            throw new org.springframework.security.access.AccessDeniedException("Valid administrator authentication required");
    }
    /** One durable first-confirm screen. Not a provider POST permit and never contains a secret. */
    public boolean issueBrowserConfirmation(long orderId,UUID operationId,PayJpPaymentFlowObservation observed){
        var data=lock(orderId,operationId);var op=data.operation();
        if(!"AUTHORIZING".equals(op.get("state")) || op.get("switch_decided_at")!=null
                || abortRequested(data) || op.get("new_payment_id")==null || op.get("browser_confirmation_issued_at")!=null || expired(data))return false;
        var next=payment(data,number(op,"new_payment_id"));
        var auth=transaction(data,PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION);
        if(!matchesFlow(data,next,observed,true) || !"requires_payment_method".equals(observed.status())
                || auth.getStatus()!=PaymentTransactionStatus.PENDING || !validAuthorization(data,auth,next))return false;
        return jdbc.update("""
            update order_change_operations set browser_confirmation_issued_at=?,version=version+1,updated_at=?
            where id=? and version=? and browser_confirmation_issued_at is null
              and state='AUTHORIZING' and switch_decided_at is null
              and grace_until>timezone('Asia/Tokyo',clock_timestamp())
            """,now(),now(),operationId,op.get("version"))==1;
    }
    public View prepare(long orderId,UUID operationId){
        var data=lock(orderId,operationId);var op=data.operation();
        if(progressBlocked(data)||!"RESERVED".equals(op.get("state")))return view(data);
        if(op.get("new_payment_id")!=null)return review(data,"LEGACY_REQUEST_REVIEW");
        validateBase(data);requireHeld(data);
        if(expired(data)){release(data);return view(data);}
        requireNoPending(data,null);
        var old=payment(data,number(op,"old_payment_id"));
        if(old.getStatus()!=PaymentStatus.AUTHORIZED)throw new IllegalStateException("Old authorization is not available");
        int revision=Math.addExact(integer(op,"base_content_revision"),1);
        String fingerprint=baseFingerprint(data.order());
        var next=payments.saveAndFlush(new Payment(data.order(),PaymentProvider.PAYJP,PaymentMethod.CARD,integer(op,"after_amount"),now()));
        var auth=transactions.saveAndFlush(PaymentTransaction.forOrderChange(next,PaymentTransactionType.AUTHORIZE,next.getAmount(),revision,
            UUID.randomUUID().toString(),now(),PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION,operationId));
        jdbc.update("update order_change_operations set new_payment_id=?,base_order_fingerprint=? where id=?",next.getId(),fingerprint,operationId);
        requests.save(next.getId(),auth.getId(),OrderChangeHttpRequest.create(connection.requireIdentity(provider),operationId,orderId,next.getAmount(),auth.getIdempotencyKey()));
        state(data,"AUTHORIZING",null);return current(orderId,operationId);
    }
    public View describe(long orderId,UUID operationId){return view(lock(orderId,operationId));}
    public View forSnapshot(PaymentOperationSnapshot snapshot){
        var operations=jdbc.queryForList("select order_change_operation_id from payment_transactions where id=? and payment_id=?",UUID.class,snapshot.transactionId(),snapshot.paymentId());
        if(operations.size()!=1||operations.getFirst()==null)throw new IllegalArgumentException("Missing order change transaction");
        UUID operation=operations.getFirst();
        if(operation==null)throw new IllegalArgumentException("Missing order change operation");
        var data=lock(snapshot.orderId(),operation);
        var target=data.transactions().stream().filter(t->Objects.equals(t.getId(),snapshot.transactionId())).findFirst().orElseThrow(()->new IllegalArgumentException("Order change transaction ownership mismatch"));
        if(target.getPurpose()!=snapshot.purpose()||target.getTransactionType()!=snapshot.type()||target.getAmount()!=snapshot.amount()
                ||target.getPayment().getProvider()!=snapshot.provider()||target.getPayment().getPaymentMethod()!=PaymentMethod.CARD
                ||target.getOrderContentRevision()!=snapshot.orderContentRevision()||!target.getIdempotencyKey().equals(snapshot.idempotencyKey())
                ||!Objects.equals(target.getPayment().getProviderPaymentId(),snapshot.providerPaymentId()))
            throw new IllegalArgumentException("Order change snapshot identity mismatch");
        return view(data);
    }
    public View attachFlow(long orderId,UUID operationId,String flowId){
        var data=lock(orderId,operationId);
        if(terminal(data))return view(data);
        if(flowId==null||!flowId.matches("pfw_[A-Za-z0-9_-]{1,251}"))return review(data,"INVALID_CREATE_RESPONSE");
        var next=payment(data,number(data.operation(),"new_payment_id"));
        if(next.getProviderPaymentId()!=null&&!next.getProviderPaymentId().equals(flowId))return review(data,"FLOW_ID_CONFLICT");
        if(next.getProviderPaymentId()==null)next.setProviderPaymentId(flowId,now());
        em.flush();return view(data);
    }
    public View createDispatchUnavailable(long orderId,UUID operationId){
        var data=lock(orderId,operationId);var view=view(data);
        if(progressBlocked(data)||view.newFlow()!=null||data.operation().get("switch_decided_at")!=null)return view;
        if(List.of("AUTHORIZING","AUTH_UNKNOWN").contains(view.state()))state(data,"AUTH_UNKNOWN","CREATE_DISPATCH_UNCONFIRMED");
        return view(data);
    }
    public View failed(long orderId,UUID operationId,boolean create,PaymentGatewayErrorCategory category){
        if(!create)return cancelFailed(orderId,operationId,false,category);
        var data=lock(orderId,operationId);if(progressBlocked(data))return view(data);
        if(create&&(data.operation().get("switch_decided_at")!=null||view(data).newFlow()!=null
                ||!List.of("AUTHORIZING","AUTH_UNKNOWN").contains(data.operation().get("state"))))return view(data);
        if(!create&&!List.of("OLD_CANCEL_PENDING","OLD_CANCEL_UNKNOWN").contains(data.operation().get("state")))return view(data);
        if(create&&category!=PaymentGatewayErrorCategory.TRANSPORT_RESULT_UNKNOWN&&category!=PaymentGatewayErrorCategory.IDEMPOTENCY_WAIT_TIMEOUT)
            return review(data,"CREATE_RESULT_UNKNOWN");
        state(data,create?"AUTH_UNKNOWN":"OLD_CANCEL_UNKNOWN",null);return view(data);
    }
    public View cancelFailed(long orderId,UUID operationId,boolean abort,PaymentGatewayErrorCategory category){
        var data=lock(orderId,operationId);
        if(progressBlocked(data)||!(abort?List.of("ABORTING","ABORT_UNKNOWN"):List.of("OLD_CANCEL_PENDING","OLD_CANCEL_UNKNOWN")).contains(data.operation().get("state")))return view(data);
        if(category==PaymentGatewayErrorCategory.IDEMPOTENCY_PARAMETER_CONFLICT||category==PaymentGatewayErrorCategory.OTHER_HTTP_ERROR){
            if(abort)return review(data,"ABORT_POST_REJECTED");
            state(data,"OLD_CANCEL_REJECTED","CANCEL_POST_REJECTED");return view(data);
        }
        state(data,abort?"ABORT_UNKNOWN":"OLD_CANCEL_UNKNOWN",null);return view(data);
    }
    /** Rejection and phase check share one lock/transaction; a stale sender cannot overwrite another caller's progress. */
    public View dispatchBlocked(long orderId,UUID operationId,boolean create){
        var data=lock(orderId,operationId);var view=view(data);
        if(progressBlocked(data)||"COMMIT_PENDING".equals(view.state()))return view;
        if(create&&(view.newFlow()!=null||data.operation().get("switch_decided_at")!=null
                ||!List.of("AUTHORIZING","AUTH_UNKNOWN").contains(view.state())))return view;
        if(!create&&!List.of("OLD_CANCEL_PENDING","OLD_CANCEL_UNKNOWN","ABORTING","ABORT_UNKNOWN").contains(view.state()))return view;
        return review(data,create?"CREATE_DISPATCH_BLOCKED":"CANCEL_DISPATCH_BLOCKED");
    }

    public View authorization(long orderId,UUID operationId,PayJpPaymentFlowObservation observed){
        var data=lock(orderId,operationId);var op=data.operation();
        if(op.get("abort_flow_closed_at")!=null){
            var next=payment(data,number(op,"new_payment_id"));
            if(!matchesClosedFlowIdentity(data,next,observed)||!validAuthorization(data,transaction(data,PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION),next))
                throw new IllegalArgumentException("Closed Flow authorization identity conflict");
            return view(data); // Stale result: before CREATE OBSERVED or any Payment/transaction mutation.
        }
        if(terminal(data))return view(data);
        if(abortRequested(data)&&op.get("new_payment_id")==null)return view(data);
        var next=payment(data,number(op,"new_payment_id"));
        if(!matchesFlow(data,next,observed,true))return review(data,"NEW_FLOW_EVIDENCE_MISMATCH");
        var auth=transaction(data,PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION);
        if(!validAuthorization(data,auth,next))return review(data,"AUTHORIZATION_HISTORY_MISMATCH");
        requests.observed(operationId,"CREATE");
        if(abortRequested(data)){
            // Record confirmed financial success only. Do not advance the operation, cancel, or release.
            if(authorized(observed,next.getAmount())&&auth.getStatus()==PaymentTransactionStatus.PENDING){
                auth.markSucceeded(next.getProviderPaymentId(),now());next.markAuthorized(now());
            }
            return view(data);
        }
        if(op.get("switch_decided_at")!=null){
            if(!authorized(observed,next.getAmount()))return review(data,"DECIDED_NEW_AUTH_UNAVAILABLE");
            return view(data); // A late authorization notification never rewinds the cancel/commit phase.
        }
        if("canceled".equals(observed.status())){
            if(auth.getStatus()==PaymentTransactionStatus.SUCCEEDED)return review(data,"ESTABLISHED_AUTH_CANCELED_EXTERNALLY");
            if(auth.getStatus()==PaymentTransactionStatus.PENDING)auth.markFailed(next.getProviderPaymentId(),"FLOW_CANCELED","Flow canceled",now());
            next.markFailed(now());release(data);return view(data);
        }
        if(authorized(observed,next.getAmount())){
            if(auth.getStatus()==PaymentTransactionStatus.FAILED)return review(data,"AUTHORIZATION_TERMINAL_CONFLICT");
            if(auth.getStatus()==PaymentTransactionStatus.PENDING)auth.markSucceeded(next.getProviderPaymentId(),now());
            if(next.getStatus()!=PaymentStatus.AUTHORIZED)next.markAuthorized(now());
            // Once abort has been requested, authorization events cannot undo that decision.
            if(!List.of("ABORTING","ABORT_UNKNOWN").contains(op.get("state")))state(data,"AUTHORIZED",null);
        }else if(auth.getStatus()!=PaymentTransactionStatus.PENDING){return review(data,"AUTHORIZATION_STATE_REGRESSION");
        }else if("requires_action".equals(observed.status())){if(next.getStatus()!=PaymentStatus.REQUIRES_ACTION)next.markRequiresAction(now());state(data,"REQUIRES_ACTION",null);
        }else if(List.of("requires_payment_method","requires_confirmation","processing").contains(observed.status())){
            state(data,"AUTHORIZING",null);
            if(expired(data))return review(data,"UNSETTLED_AUTH_AFTER_GRACE");
        }else return review(data,"UNCONFIRMED_AUTH_STATE");
        return view(data);
    }
    public View decide(long orderId,UUID operationId,PayJpPaymentFlowObservation newObservation,PayJpPaymentFlowObservation oldObservation){
        var data=lock(orderId,operationId);var op=data.operation();if(progressBlocked(data)||op.get("switch_decided_at")!=null)return view(data);
        if(!"AUTHORIZED".equals(op.get("state")))throw new IllegalStateException("Authorization is not confirmed");
        validateBase(data);requireHeld(data);requireNoPending(data,null);
        var next=payment(data,number(op,"new_payment_id"));var old=payment(data,number(op,"old_payment_id"));
        if(!matchesFlow(data,next,newObservation,true)||!authorized(newObservation,next.getAmount())
                ||next.getStatus()!=PaymentStatus.AUTHORIZED||!validAuthorization(data,transaction(data,PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION),next)
                ||transaction(data,PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION).getStatus()!=PaymentTransactionStatus.SUCCEEDED)
            return review(data,"NEW_AUTH_NOT_READY");
        if(expired(data))return prepareAbort(data,next);
        if(old.getStatus()!=PaymentStatus.AUTHORIZED||!matchesFlow(data,old,oldObservation,false)||!authorized(oldObservation,old.getAmount()))
            return review(data,"OLD_AUTH_NOT_READY");
        if(newObservation.expiredAt()!=null&&!newObservation.expiredAt().toInstant().isAfter(now().atZone(ZoneId.of("Asia/Tokyo")).toInstant().plusSeconds(300)))
            return review(data,"NEW_AUTH_EXPIRY_UNCONFIRMED");
        var cancel=transactions.saveAndFlush(PaymentTransaction.forOrderChange(old,PaymentTransactionType.CANCEL,old.getAmount(),integer(op,"base_content_revision"),
            UUID.randomUUID().toString(),now(),PaymentTransactionPurpose.ORDER_CHANGE_OLD_AUTH_CANCEL,operationId));
        if(jdbc.update("update order_change_operations set switch_decided_at=?,state='OLD_CANCEL_PENDING',version=version+1,updated_at=? where id=? and version=? and switch_decided_at is null and abort_request_id is null",now(),now(),operationId,op.get("version"))!=1)
            throw new IllegalStateException("Old Cancel decision CAS conflict");
        requests.save(old.getId(),cancel.getId(),OrderChangeHttpRequest.cancel(connection.requireIdentity(provider),operationId,orderId,old.getAmount(),cancel.getIdempotencyKey(),old.getProviderPaymentId(),false));
        return current(orderId,operationId);
    }
    private View prepareAbort(Locked data,Payment next){
        var op=data.operation();UUID id=(UUID)op.get("id");
        state(data,"ABORTING",null);
        var cancel=transactions.saveAndFlush(PaymentTransaction.forOrderChange(next,PaymentTransactionType.CANCEL,next.getAmount(),integer(op,"base_content_revision"),
            UUID.randomUUID().toString(),now(),PaymentTransactionPurpose.ORDER_CHANGE_NEW_AUTH_ABORT,id));
        requests.save(next.getId(),cancel.getId(),OrderChangeHttpRequest.cancel(connection.requireIdentity(provider),id,data.order().getId(),next.getAmount(),cancel.getIdempotencyKey(),next.getProviderPaymentId(),true));
        return current(data.order().getId(),id);
    }
    public View cancellation(long orderId,UUID operationId,boolean abort,PayJpPaymentFlowObservation observed){
        var data=lock(orderId,operationId);if(progressBlocked(data))return view(data);
        var op=data.operation();var target=payment(data,number(op,abort?"new_payment_id":"old_payment_id"));
        if(abort&&op.get("switch_decided_at")!=null)return review(data,"ABORT_AFTER_DECISION");
        if(!abort&&op.get("switch_decided_at")==null)return review(data,"CANCEL_WITHOUT_DECISION");
        if(!abort&&observed!=null&&"canceled".equals(observed.status())){
            if(!matchesOldCancellation(data,target,observed))return review(data,"CANCEL_FLOW_EVIDENCE_MISMATCH");
        }else if(!matchesFlow(data,target,observed,abort))return review(data,"CANCEL_FLOW_EVIDENCE_MISMATCH");
        requests.observed(operationId,abort?"NEW_ABORT":"OLD_CANCEL");
        var cancel=transaction(data,abort?PaymentTransactionPurpose.ORDER_CHANGE_NEW_AUTH_ABORT:PaymentTransactionPurpose.ORDER_CHANGE_OLD_AUTH_CANCEL);
        if(cancel.getStatus()==PaymentTransactionStatus.SUCCEEDED)return view(data);
        if(!"canceled".equals(observed.status())){
            if(!List.of("MANUAL_REVIEW","OLD_CANCEL_REJECTED").contains(op.get("state")))state(data,abort?"ABORT_UNKNOWN":"OLD_CANCEL_UNKNOWN",null);return view(data);
        }
        if(cancel.getStatus()==PaymentTransactionStatus.FAILED)return review(data,"CANCEL_TERMINAL_CONFLICT");
        if(cancel.getStatus()==PaymentTransactionStatus.PENDING)cancel.markSucceeded(target.getProviderPaymentId(),now());
        target.markCancelled(now());
        if(abort)release(data);else state(data,"COMMIT_PENDING",null);
        return view(data);
    }
    public View complete(long orderId,UUID operationId,PayJpPaymentFlowObservation observed){
        return completeInternal(orderId,operationId,observed,false);
    }
    private View completeInternal(long orderId,UUID operationId,PayJpPaymentFlowObservation observed,boolean explicitRecovery){
        var data=lock(orderId,operationId);if(progressBlocked(data))return view(data);var op=data.operation();
        if(!"COMMIT_PENDING".equals(op.get("state"))||op.get("switch_decided_at")==null)throw new IllegalStateException("Old cancellation has not been confirmed");
        // Dedicated recovery must finish together with its audit, never through an automatic result route.
        if(!explicitRecovery&&Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from order_change_recovery_audits where operation_id=? and applied_version is not null)",Boolean.class,operationId)))return view(data);
        validateBase(data);requireHeld(data);requireNoPending(data,null);
        var next=payment(data,number(op,"new_payment_id"));var old=payment(data,number(op,"old_payment_id"));
        if(old.getStatus()!=PaymentStatus.CANCELLED||transaction(data,PaymentTransactionPurpose.ORDER_CHANGE_OLD_AUTH_CANCEL).getStatus()!=PaymentTransactionStatus.SUCCEEDED
                ||!Objects.equals(transaction(data,PaymentTransactionPurpose.ORDER_CHANGE_OLD_AUTH_CANCEL).getProviderTransactionId(),old.getProviderPaymentId())
                ||next.getStatus()!=PaymentStatus.AUTHORIZED||!validAuthorization(data,transaction(data,PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION),next)
                ||transaction(data,PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION).getStatus()!=PaymentTransactionStatus.SUCCEEDED
                ||!matchesFlow(data,next,observed,true)||!authorized(observed,next.getAmount()))return review(data,"COMMIT_AUTHORIZATION_UNAVAILABLE");
        var order=data.order();var before=OrderAmountSnapshot.from(order);var beforeShipping=OrderChargeSnapshot.from(order.getCharges().getFirst());
        var amount=candidateAmount(data);
        var history=new OrderContentChangeHistory(order,OrderContentChangeSource.CUSTOMER,"数量増加・再与信",OrderContentChangeHistoryActorType.USER,
            order.getUserId(),jdbc.queryForObject("select username from users where id=?",String.class,order.getUserId()),before.itemSubtotal(),amount.itemSubtotal(),
            before.chargeTotal(),amount.chargeTotal(),before.taxAmount(),amount.taxAmount(),before.totalAmount(),amount.totalAmount(),now());
        for(var item:order.getItems()){
            var change=data.items().stream().filter(r->number(r,"order_item_id")==item.getId()).findFirst();
            if(change.isEmpty())continue;
            int oldQuantity=item.getQuantity(),oldSubtotal=item.getSubtotal();item.setQuantity(integer(change.get(),"after_quantity"));
            history.addItem(OrderContentChangeItem.updated(item.getId(),item.getProductId(),item.getProductName(),item.getPrice(),oldQuantity,item.getQuantity(),oldSubtotal,item.getSubtotal(),
                item.getTaxCategoryId(),item.getTaxCategoryCode(),item.getTaxCategoryName(),item.getTaxRate()));
        }
        var shipping=order.getCharges().getFirst();shipping.updateAmount(amount.charges().getFirst().amount());
        if(beforeShipping.amount()!=shipping.getAmount())history.addCharge(OrderContentChangeCharge.updated(shipping.getId(),shipping.getChargeType(),beforeShipping.name(),shipping.getName(),
            beforeShipping.amount(),shipping.getAmount(),beforeShipping.taxCategoryId(),shipping.getTaxCategoryId(),beforeShipping.taxCategoryCode(),shipping.getTaxCategoryCode(),
            beforeShipping.taxCategoryName(),shipping.getTaxCategoryName(),beforeShipping.taxRate(),shipping.getTaxRate(),beforeShipping.displayOrder(),shipping.getDisplayOrder()));
        order.applyAmount(amount);order.incrementContentRevision();order.switchActivePaymentAfterOrderChange(old.getId(),next);
        var saved=histories.saveAndFlush(history);
        int consumed=jdbc.update("update inventory_reservations set state='CONSUMED',consumed_at=? where operation_id=? and state='HELD'",now(),operationId);
        if(consumed!=data.items().size())throw new IllegalStateException("Reservation consumption conflict");
        jdbc.update("update order_change_operations set content_change_history_id=? where id=?",saved.getId(),operationId);
        state(data,"COMPLETED",null);return view(data);
    }
    private Locked lock(long orderId,UUID operationId){
        context.requireCleanWritableReadCommitted();
        var order=orders.findByIdForUpdate(orderId).orElseThrow();context.refreshWritableOrder(order);
        changes.operation(orderId,operationId).orElseThrow();
        var operation=jdbc.queryForMap("select * from order_change_operations where id=? and order_id=?",operationId,orderId);
        var all=payments.findByOrderIdForUpdate(orderId);all.forEach(em::refresh);
        var history=new ArrayList<PaymentTransaction>();for(var payment:all){var own=transactions.findByPaymentIdOrderByCreatedAtAscIdAsc(payment.getId());own.forEach(em::refresh);history.addAll(own);}
        var items=jdbc.queryForList("select * from order_change_items where operation_id=? order by order_item_id",operationId);
        return new Locked(order,operation,all,history,items);
    }
    private void validateBase(Locked data){
        var order=data.order();var op=data.operation();
        if(order.getStatus()!=OrderStatus.ORDERED||order.getContentRevision()!=integer(op,"base_content_revision")||order.getTotalAmount()!=integer(op,"before_amount")
                ||!Objects.equals(order.getActivePaymentId(),number(op,"old_payment_id"))||order.getShipmentCapturePaymentId()!=null
                ||order.getShippingPreparationStartedAt()!=null||order.getShippingPreparationStartedByAccountId()!=null||order.getShippingPreparationStartedByUsername()!=null
                ||order.getUserId()!=number(op,"customer_id")||!order.getChangeDeadlineAt().equals(op.get("deadline_at") instanceof java.sql.Timestamp t?t.toLocalDateTime():op.get("deadline_at")))
            throw new IllegalStateException("Original order changed");
        if(op.get("base_order_fingerprint")!=null&&!op.get("base_order_fingerprint").equals(baseFingerprint(order)))throw new IllegalStateException("Original order fingerprint mismatch");
        if(data.items().isEmpty())throw new IllegalStateException("Candidate items missing");
        for(var row:data.items()){
            var item=order.getItems().stream().filter(i->i.getId()==number(row,"order_item_id")).findFirst().orElseThrow();
            if(item.getProductId()!=number(row,"product_id")||item.getQuantity()!=integer(row,"before_quantity")||item.getPrice()!=integer(row,"unit_price")
                    ||item.getSubtotal()!=integer(row,"before_subtotal")||item.getTaxCategoryId()!=number(row,"tax_category_id")
                    ||!item.getTaxCategoryCode().equals(row.get("tax_category_code"))||!item.getTaxCategoryName().equals(row.get("tax_category_name"))
                    ||item.getTaxRate().compareTo((java.math.BigDecimal)row.get("tax_rate"))!=0)throw new IllegalStateException("Candidate original item changed");
        }
        candidateAmount(data);
    }
    private OrderAmount candidateAmount(Locked data){
        var pricing=new OrderPricingContext();
        for(var item:data.order().getItems()){
            int quantity=data.items().stream().filter(r->number(r,"order_item_id")==item.getId()).findFirst().map(r->integer(r,"after_quantity")).orElse(item.getQuantity());
            Math.multiplyExact(item.getPrice(),quantity);
            pricing.addItem(item.getPrice(),quantity,item.getTaxCategoryId(),item.getTaxCategoryCode(),item.getTaxCategoryName(),item.getTaxRate());
        }
        if(data.order().getCharges().size()!=1||data.order().getCharges().getFirst().getChargeType()!=OrderChargeType.SHIPPING)throw new IllegalStateException("Unsupported charges");
        var amount=calculator.calculate(pricing,ChargeTaxSnapshot.from(data.order().getCharges().getFirst()));var op=data.operation();var shipping=amount.charges().getFirst();
        if(amount.totalAmount()!=integer(op,"after_amount")||amount.itemSubtotal()!=integer(op,"item_subtotal")||amount.chargeTotal()!=integer(op,"charge_total")
                ||amount.taxAmount()!=integer(op,"tax_amount")||shipping.amount()!=integer(op,"shipping_amount")||!shipping.name().equals(op.get("shipping_name"))
                ||shipping.taxCategoryId()!=number(op,"shipping_tax_category_id")||!shipping.taxCategoryCode().equals(op.get("shipping_tax_category_code"))
                ||!shipping.taxCategoryName().equals(op.get("shipping_tax_category_name"))||shipping.taxRate().compareTo((java.math.BigDecimal)op.get("shipping_tax_rate"))!=0
                ||shipping.displayOrder()!=integer(op,"shipping_display_order"))throw new IllegalStateException("Frozen candidate amount changed");
        return amount;
    }
    private void requireHeld(Locked data){
        var reservations=changes.reservations((UUID)data.operation().get("id"));
        if(reservations.size()!=data.items().size()||reservations.stream().anyMatch(r->!"HELD".equals(r.state())||data.items().stream().noneMatch(
                i->number(i,"order_item_id")==r.itemId()&&number(i,"product_id")==r.productId()&&integer(i,"increase_quantity")==r.quantity())))
            throw new IllegalStateException("Held reservation proof missing");
    }
    private void release(Locked data){
        if(abortRequested(data)||data.operation().get("switch_decided_at")!=null)throw new IllegalStateException("Operation cannot release reservations");
        requireHeld(data);var id=(UUID)data.operation().get("id");var held=changes.reservations(id);
        for(long product:held.stream().map(OrderChangeStore.Reservation::productId).distinct().sorted().toList())em.refresh(products.findByIdForUpdateIncludingInactive(product).orElseThrow());
        for(var reservation:held){long movement=inventory.releaseForOrderChange(reservation.productId(),reservation.quantity(),data.order().getId(),id,reservation.itemId());changes.released(id,reservation.itemId(),movement,now());}
        state(data,"ABORTED",null);
    }
    private boolean validAuthorization(Locked data,PaymentTransaction auth,Payment next){
        return auth.getPayment().getId().equals(next.getId())&&auth.getAmount()==integer(data.operation(),"after_amount")
            &&auth.getOrderContentRevision()==Math.addExact(integer(data.operation(),"base_content_revision"),1)
            &&auth.getTransactionType()==PaymentTransactionType.AUTHORIZE&&Objects.equals(auth.getOrderChangeOperationId(),data.operation().get("id"))
            &&(auth.getStatus()!=PaymentTransactionStatus.SUCCEEDED||Objects.equals(auth.getProviderTransactionId(),next.getProviderPaymentId()));
    }
    private boolean matchesClosedFlowIdentity(Locked data,Payment payment,PayJpPaymentFlowObservation value){
        var identity=connection.requireIdentity(provider);
        return payment.getProvider()==PaymentProvider.PAYJP&&payment.getPaymentMethod()==PaymentMethod.CARD
            &&value!=null&&Objects.equals(payment.getProviderPaymentId(),value.id())&&value.amount()!=null&&value.amount()==payment.getAmount()
            &&"jpy".equals(value.currency())&&"manual".equals(value.captureMethod())&&value.livemode()!=null
            &&value.livemode()==identity.mode().equals("LIVE")&&value.metadata()!=null
            &&Long.toString(data.order().getId()).equals(value.metadata().get("order_id"))
            &&data.operation().get("id").toString().equals(value.metadata().get("order_change_operation_id"));
    }
    private boolean matchesOldCancellation(Locked data,Payment old,PayJpPaymentFlowObservation observed){
        var op=data.operation();var identity=connection.requireIdentity(provider);
        validateBase(data);
        if(old.getId()!=number(op,"old_payment_id")||!Objects.equals(old.getOrder().getId(),data.order().getId())
                ||old.getProvider()!=PaymentProvider.PAYJP||old.getPaymentMethod()!=PaymentMethod.CARD
                ||old.getAmount()!=integer(op,"before_amount")||observed.oldCancellationEvidence()==null
                ||!observed.oldCancellationEvidence().matches(old.getProviderPaymentId(),old.getAmount(),identity))return false;
        var cancel=transaction(data,PaymentTransactionPurpose.ORDER_CHANGE_OLD_AUTH_CANCEL);
        var requestId=view(data).oldCancelRequest();if(requestId==null)return false;
        var saved=requests.find(requestId);var request=saved.httpRequest();
        if(saved.paymentId()!=old.getId()||saved.transactionId()!=cancel.getId()
                ||!request.operationId().equals(op.get("id"))||request.orderId()!=data.order().getId()
                ||!"OLD_CANCEL".equals(request.kind())||!"CANCEL".equals(request.transactionType())
                ||request.purpose()!=PaymentTransactionPurpose.ORDER_CHANGE_OLD_AUTH_CANCEL
                ||request.amount()!=old.getAmount()||!Objects.equals(request.flowId(),old.getProviderPaymentId())
                ||!request.connection().equals(identity)||!request.idempotencyKey().equals(cancel.getIdempotencyKey())
                ||!Objects.equals(cancel.getPayment().getId(),old.getId())||cancel.getAmount()!=old.getAmount()
                ||cancel.getOrderContentRevision()!=integer(op,"base_content_revision"))return false;
        if(jdbc.queryForObject("select count(*) from order_change_request_dispatches where request_id=? and state in ('STARTED','OBSERVED')",Integer.class,requestId)!=1)return false;
        if(data.transactions().stream().noneMatch(t->t.getPayment().getId().equals(old.getId())
                &&t.getTransactionType()==PaymentTransactionType.AUTHORIZE&&t.getStatus()==PaymentTransactionStatus.SUCCEEDED
                &&t.getAmount()==old.getAmount()&&Objects.equals(t.getProviderTransactionId(),old.getProviderPaymentId())))return false;
        return data.transactions().stream().noneMatch(t->t.getTransactionType()==PaymentTransactionType.CAPTURE
            ||(t.getStatus()==PaymentTransactionStatus.PENDING&&!t.getId().equals(cancel.getId())));
    }
    private boolean matchesFlow(Locked data,Payment payment,PayJpPaymentFlowObservation value,boolean requireMetadata){
        var identity=connection.requireIdentity(provider);
        return payment.getProvider()==PaymentProvider.PAYJP&&payment.getPaymentMethod()==PaymentMethod.CARD
            &&value!=null&&Objects.equals(payment.getProviderPaymentId(),value.id())&&value.status()!=null&&value.amount()!=null&&value.amount()==payment.getAmount()
            &&"jpy".equals(value.currency())&&"manual".equals(value.captureMethod())&&value.livemode()!=null&&value.livemode()==identity.mode().equals("LIVE")
            // Established authorization requires full capturable evidence and a present null/zero received field.
            // Only unconfirmed new Flows may omit received; canceled evidence remains unchanged.
            &&("requires_capture".equals(value.status()) ? value.hasUncapturedAuthorization(payment.getAmount())
                : value.amountReceived()!=null ? value.amountReceived()==0L
                    : requireMetadata&&List.of("requires_payment_method","requires_confirmation","requires_action","processing").contains(value.status()))
            &&(!"canceled".equals(value.status())||(value.amountCapturable()!=null&&value.amountCapturable()==0L))
            &&(!requireMetadata||(value.metadata()!=null&&Long.toString(data.order().getId()).equals(value.metadata().get("order_id"))
                &&data.operation().get("id").toString().equals(value.metadata().get("order_change_operation_id"))));
    }
    private boolean authorized(PayJpPaymentFlowObservation value,int amount){return value!=null&&value.hasUncapturedAuthorization(amount);}
    private void requireNoPending(Locked data,Long except){if(data.transactions().stream().anyMatch(t->t.getStatus()==PaymentTransactionStatus.PENDING&&!Objects.equals(t.getId(),except)))throw new IllegalStateException("Competing financial operation");}
    private Payment payment(Locked data,long id){return data.payments().stream().filter(p->p.getId()==id).findFirst().orElseThrow();}
    private PaymentTransaction transaction(Locked data,PaymentTransactionPurpose purpose){var matches=data.transactions().stream().filter(t->t.getPurpose()==purpose&&Objects.equals(t.getOrderChangeOperationId(),data.operation().get("id"))).toList();if(matches.size()!=1)throw new IllegalStateException("Ambiguous change transaction");return matches.getFirst();}
    private View review(Locked data,String reason){state(data,"MANUAL_REVIEW",reason);return view(data);}
    private void state(Locked data,String state,String reason){
        if(jdbc.update("update order_change_operations set state=?,financial_review_reason=?,version=version+1,updated_at=? where id=? and version=?",state,reason,now(),data.operation().get("id"),data.operation().get("version"))!=1)throw new IllegalStateException("Operation version conflict");
        data.operation().put("state",state);data.operation().put("version",number(data.operation(),"version")+1);
    }
    private boolean abortRequested(Locked data){return data.operation().get("abort_request_id")!=null;}
    private boolean progressBlocked(Locked data){return terminal(data)||abortRequested(data);}
    private boolean terminal(Locked data){return List.of("COMPLETED","ABORTED","MANUAL_REVIEW").contains(data.operation().get("state"));}
    private boolean expired(Locked data){var grace=(java.sql.Timestamp)data.operation().get("grace_until");return policy.canExpireChange(grace.toLocalDateTime(),null,now());}
    private LocalDateTime now(){return orders.findCurrentTimeInTokyo();}
    private View current(long order,UUID operation){return view(lock(order,operation));}
    private View view(Locked data){
        var op=data.operation();var next=op.get("new_payment_id")==null?null:payment(data,number(op,"new_payment_id"));
        var requestRows=jdbc.queryForList("select id,request_kind from order_change_financial_requests where operation_id=?",op.get("id"));
        return new View((UUID)op.get("id"),data.order().getId(),(String)op.get("state"),next==null?null:next.getId(),next==null?null:next.getProviderPaymentId(),
            payment(data,number(op,"old_payment_id")).getProviderPaymentId(),requestId(requestRows,"CREATE"),requestId(requestRows,"OLD_CANCEL"),requestId(requestRows,"NEW_ABORT"),abortRequested(data),op.get("abort_flow_closed_at")!=null);
    }
    private Long requestId(List<Map<String,Object>> rows,String kind){return rows.stream().filter(r->kind.equals(r.get("request_kind"))).map(r->number(r,"id")).findFirst().orElse(null);}
    private static int integer(Map<String,Object> row,String field){return ((Number)row.get(field)).intValue();}
    private static long number(Map<String,Object> row,String field){return ((Number)row.get(field)).longValue();}
    private static String baseFingerprint(Order order){
        try{var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeUTF("ec-site/order-change/base/v1");
            out.writeLong(order.getId());out.writeLong(order.getUserId());out.writeInt(order.getContentRevision());out.writeInt(order.getTotalAmount());out.writeInt(order.getItemSubtotal());out.writeInt(order.getChargeTotal());out.writeInt(order.getTaxAmount());out.writeLong(order.getActivePaymentId());
            var items=order.getItems().stream().sorted(Comparator.comparing(OrderItem::getId)).toList();out.writeInt(items.size());
            for(var item:items){out.writeUTF(OrderItemSnapshot.from(item).toString());out.writeLong(item.getCategoryId());out.writeUTF(item.getCategoryName());}
            var charges=order.getCharges().stream().sorted(Comparator.comparing(OrderCharge::getId)).toList();out.writeInt(charges.size());for(var charge:charges)out.writeUTF(OrderChargeSnapshot.from(charge).toString());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        }catch(IOException|NoSuchAlgorithmException failure){throw new IllegalStateException("Cannot fingerprint original order",failure);}
    }
}
