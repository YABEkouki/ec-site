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
            Long createRequest,Long oldCancelRequest,Long abortRequest){}
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
    public OrderReauthorizationTransactions(OrderRepository orders,PaymentRepository payments,PaymentTransactionRepository transactions,
            OrderContentChangeHistoryRepository histories,ProductRepository products,OrderChangeStore changes,
            OrderChangeFinancialRequestStore requests,JdbcTemplate jdbc,EntityManager em,OrderWriteContext context,
            OrderModificationPolicy policy,OrderAmountCalculator calculator,InventoryService inventory,
            CaptureConnectionProperties connection,PayJpProperties provider){
        this.orders=orders;this.payments=payments;this.transactions=transactions;this.histories=histories;this.products=products;
        this.changes=changes;this.requests=requests;this.jdbc=jdbc;this.em=em;this.context=context;this.policy=policy;
        this.calculator=calculator;this.inventory=inventory;this.connection=connection;this.provider=provider;
    }
    public View prepare(long orderId,UUID operationId){
        var data=lock(orderId,operationId);var op=data.operation();
        if(!"RESERVED".equals(op.get("state")))return view(data);
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
        UUID operation=jdbc.queryForObject("select order_change_operation_id from payment_transactions where id=? and payment_id=?",UUID.class,snapshot.transactionId(),snapshot.paymentId());
        if(operation==null)throw new IllegalArgumentException("Missing order change operation");
        var data=lock(snapshot.orderId(),operation);
        var target=data.transactions().stream().filter(t->Objects.equals(t.getId(),snapshot.transactionId())).findFirst().orElseThrow();
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
    public View failed(long orderId,UUID operationId,boolean create,PaymentGatewayErrorCategory category){
        if(!create)return cancelFailed(orderId,operationId,false,category);
        var data=lock(orderId,operationId);if(terminal(data))return view(data);
        if(create&&(data.operation().get("switch_decided_at")!=null||view(data).newFlow()!=null
                ||!List.of("AUTHORIZING","AUTH_UNKNOWN").contains(data.operation().get("state"))))return view(data);
        if(!create&&!List.of("OLD_CANCEL_PENDING","OLD_CANCEL_UNKNOWN").contains(data.operation().get("state")))return view(data);
        if(create&&category!=PaymentGatewayErrorCategory.TRANSPORT_RESULT_UNKNOWN&&category!=PaymentGatewayErrorCategory.IDEMPOTENCY_WAIT_TIMEOUT)
            return review(data,"CREATE_RESULT_UNKNOWN");
        state(data,create?"AUTH_UNKNOWN":"OLD_CANCEL_UNKNOWN",null);return view(data);
    }
    public View cancelFailed(long orderId,UUID operationId,boolean abort,PaymentGatewayErrorCategory category){
        var data=lock(orderId,operationId);
        if(terminal(data)||!(abort?List.of("ABORTING","ABORT_UNKNOWN"):List.of("OLD_CANCEL_PENDING","OLD_CANCEL_UNKNOWN")).contains(data.operation().get("state")))return view(data);
        if(category==PaymentGatewayErrorCategory.IDEMPOTENCY_PARAMETER_CONFLICT||category==PaymentGatewayErrorCategory.OTHER_HTTP_ERROR){
            if(abort)return review(data,"ABORT_POST_REJECTED");
            state(data,"OLD_CANCEL_REJECTED","CANCEL_POST_REJECTED");return view(data);
        }
        state(data,abort?"ABORT_UNKNOWN":"OLD_CANCEL_UNKNOWN",null);return view(data);
    }
    /** Rejection and phase check share one lock/transaction; a stale sender cannot overwrite another caller's progress. */
    public View dispatchBlocked(long orderId,UUID operationId,boolean create){
        var data=lock(orderId,operationId);var view=view(data);
        if(terminal(data)||"COMMIT_PENDING".equals(view.state()))return view;
        if(create&&(view.newFlow()!=null||data.operation().get("switch_decided_at")!=null
                ||!List.of("AUTHORIZING","AUTH_UNKNOWN").contains(view.state())))return view;
        if(!create&&!List.of("OLD_CANCEL_PENDING","OLD_CANCEL_UNKNOWN","ABORTING","ABORT_UNKNOWN").contains(view.state()))return view;
        return review(data,create?"CREATE_DISPATCH_BLOCKED":"CANCEL_DISPATCH_BLOCKED");
    }

    public View authorization(long orderId,UUID operationId,PayJpPaymentFlowObservation observed){
        var data=lock(orderId,operationId);if(terminal(data))return view(data);
        var op=data.operation();var next=payment(data,number(op,"new_payment_id"));
        if(!matchesFlow(data,next,observed,true))return review(data,"NEW_FLOW_EVIDENCE_MISMATCH");
        var auth=transaction(data,PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION);
        if(!validAuthorization(data,auth,next))return review(data,"AUTHORIZATION_HISTORY_MISMATCH");
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
        var data=lock(orderId,operationId);var op=data.operation();if(terminal(data)||op.get("switch_decided_at")!=null)return view(data);
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
        jdbc.update("update order_change_operations set switch_decided_at=?,state='OLD_CANCEL_PENDING',version=version+1,updated_at=? where id=?",now(),now(),operationId);
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
        var data=lock(orderId,operationId);if(terminal(data))return view(data);
        var op=data.operation();var target=payment(data,number(op,abort?"new_payment_id":"old_payment_id"));
        if(abort&&op.get("switch_decided_at")!=null)return review(data,"ABORT_AFTER_DECISION");
        if(!abort&&op.get("switch_decided_at")==null)return review(data,"CANCEL_WITHOUT_DECISION");
        if(!matchesFlow(data,target,observed,abort))return review(data,"CANCEL_FLOW_EVIDENCE_MISMATCH");
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
        var data=lock(orderId,operationId);if(terminal(data))return view(data);var op=data.operation();
        if(!"COMMIT_PENDING".equals(op.get("state"))||op.get("switch_decided_at")==null)throw new IllegalStateException("Old cancellation has not been confirmed");
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
        if(data.operation().get("switch_decided_at")!=null)throw new IllegalStateException("Decided operation cannot abort");
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
    private boolean matchesFlow(Locked data,Payment payment,PayJpPaymentFlowObservation value,boolean requireMetadata){
        var identity=connection.requireIdentity(provider);
        return payment.getProvider()==PaymentProvider.PAYJP&&payment.getPaymentMethod()==PaymentMethod.CARD
            &&value!=null&&Objects.equals(payment.getProviderPaymentId(),value.id())&&value.status()!=null&&value.amount()!=null&&value.amount()==payment.getAmount()
            &&"jpy".equals(value.currency())&&"manual".equals(value.captureMethod())&&value.livemode()!=null&&value.livemode()==identity.mode().equals("LIVE")
            &&value.amountReceived()!=null&&value.amountReceived()==0L
            &&(!"canceled".equals(value.status())||(value.amountCapturable()!=null&&value.amountCapturable()==0L))
            &&(!requireMetadata||(value.metadata()!=null&&Long.toString(data.order().getId()).equals(value.metadata().get("order_id"))
                &&data.operation().get("id").toString().equals(value.metadata().get("order_change_operation_id"))));
    }
    private boolean authorized(PayJpPaymentFlowObservation value,int amount){return value!=null&&"requires_capture".equals(value.status())&&value.amountCapturable()!=null&&value.amountCapturable()==amount;}
    private void requireNoPending(Locked data,Long except){if(data.transactions().stream().anyMatch(t->t.getStatus()==PaymentTransactionStatus.PENDING&&!Objects.equals(t.getId(),except)))throw new IllegalStateException("Competing financial operation");}
    private Payment payment(Locked data,long id){return data.payments().stream().filter(p->p.getId()==id).findFirst().orElseThrow();}
    private PaymentTransaction transaction(Locked data,PaymentTransactionPurpose purpose){var matches=data.transactions().stream().filter(t->t.getPurpose()==purpose&&Objects.equals(t.getOrderChangeOperationId(),data.operation().get("id"))).toList();if(matches.size()!=1)throw new IllegalStateException("Ambiguous change transaction");return matches.getFirst();}
    private View review(Locked data,String reason){state(data,"MANUAL_REVIEW",reason);return view(data);}
    private void state(Locked data,String state,String reason){
        if(jdbc.update("update order_change_operations set state=?,financial_review_reason=?,version=version+1,updated_at=? where id=? and version=?",state,reason,now(),data.operation().get("id"),data.operation().get("version"))!=1)throw new IllegalStateException("Operation version conflict");
        data.operation().put("state",state);data.operation().put("version",number(data.operation(),"version")+1);
    }
    private boolean terminal(Locked data){return List.of("COMPLETED","ABORTED").contains(data.operation().get("state"));}
    private boolean expired(Locked data){var grace=(java.sql.Timestamp)data.operation().get("grace_until");return policy.canExpireChange(grace.toLocalDateTime(),null,now());}
    private LocalDateTime now(){return orders.findCurrentTimeInTokyo();}
    private View current(long order,UUID operation){return view(lock(order,operation));}
    private View view(Locked data){
        var op=data.operation();var next=op.get("new_payment_id")==null?null:payment(data,number(op,"new_payment_id"));
        var requestRows=jdbc.queryForList("select id,request_kind from order_change_financial_requests where operation_id=?",op.get("id"));
        return new View((UUID)op.get("id"),data.order().getId(),(String)op.get("state"),next==null?null:next.getId(),next==null?null:next.getProviderPaymentId(),
            payment(data,number(op,"old_payment_id")).getProviderPaymentId(),requestId(requestRows,"CREATE"),requestId(requestRows,"OLD_CANCEL"),requestId(requestRows,"NEW_ABORT"));
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
