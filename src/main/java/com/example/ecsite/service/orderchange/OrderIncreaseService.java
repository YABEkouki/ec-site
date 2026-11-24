package com.example.ecsite.service.orderchange;

import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.repository.*;
import com.example.ecsite.service.*;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Feature140-A: nothing new is accepted (V108 refuses it too). Only the status, withdrawal and expiry of quantity
 * increases accepted before are kept. Never calls a financial API. */
@Service
public class OrderIncreaseService {
    private final OrderRepository orders;
    private final ProductRepository products;
    private final OrderChangeStore store;
    private final OrderWriteContext context;
    private final OrderModificationPolicy policy;
    private final InventoryService inventory;
    private final EntityManager em;
    public OrderIncreaseService(OrderRepository orders,ProductRepository products,OrderChangeStore store,
            OrderWriteContext context,OrderModificationPolicy policy,InventoryService inventory,EntityManager em){
        this.orders=orders;this.products=products;this.store=store;this.context=context;this.policy=policy;
        this.inventory=inventory;this.em=em;
    }
    @Transactional
    public OrderChangeStore.Operation status(long orderId,long customerId,UUID id){
        lockedOrder(orderId,customerId);
        return store.legacyOperation(orderId,id).orElseThrow(()->new com.example.ecsite.exception.OrderNotFoundException(orderId));
    }
    @Transactional
    public void abort(long orderId,long customerId,UUID operationId){
        context.requireCleanWritableReadCommitted();var order=lockedOrder(orderId,customerId);
        release(order,store.legacyOperation(orderId,operationId).orElseThrow(()->new com.example.ecsite.exception.OrderNotFoundException(orderId)));
    }
    /** Customer confirmation CAS; release() rechecks that no financial work exists under these locks. */
    @Transactional
    public void withdrawReserved(long orderId,long customerId,UUID operationId,long expectedVersion){
        context.requireCleanWritableReadCommitted();var order=lockedOrder(orderId,customerId);
        var operation=store.legacyOperation(orderId,operationId).orElseThrow(()->new com.example.ecsite.exception.OrderNotFoundException(orderId));
        if(!"ABORTED".equals(operation.state())&&operation.version()!=expectedVersion)throw new IllegalStateException("Withdrawal version changed");
        release(order,operation);
    }
    @Transactional(readOnly=true)
    public List<OrderChangeStore.ExpiringOperation> expiredReservedCandidates(int limit){return store.expiredReserved(limit);}
    /** Local-only expiry under the same Order/operation locks as acceptance and withdrawal. */
    @Transactional
    public boolean expireReserved(long orderId,UUID operationId){
        context.requireCleanWritableReadCommitted();var order=orders.findByIdForUpdate(orderId).orElseThrow();context.refreshWritableOrder(order);
        var op=store.legacyOperation(orderId,operationId).orElseThrow();
        if(!op.state().equals("RESERVED") || !policy.canExpireChange(op.graceUntil(),op.switchDecidedAt(),orders.findCurrentTimeInTokyo()))return false;
        if(store.abortRequested(operationId)||store.financialStarted(operationId))return false;
        release(order,op);return true;
    }
    private Order lockedOrder(long id,long customer){
        var order=orders.findByIdAndUserIdForUpdate(id,customer).orElseThrow(()->new com.example.ecsite.exception.OrderNotFoundException(id));
        context.refreshWritableOrder(order);return order;
    }
    private void release(Order order,OrderChangeStore.Operation op){
        if(op.state().equals("ABORTED"))return;
        if(!op.state().equals("RESERVED") || op.switchDecidedAt()!=null || store.abortRequested(op.id()) || store.financialStarted(op.id()))throw new IllegalStateException("Financial change cannot be aborted here");
        var reservations=store.reservations(op.id());
        if(reservations.isEmpty() || reservations.stream().anyMatch(r->!r.state().equals("HELD")))throw new IllegalStateException("Reservation state mismatch");
        // Flush/refresh before any release: a caller may have loaded stale Product entities.
        for(long productId:reservations.stream().map(OrderChangeStore.Reservation::productId).distinct().toList()){
            var product=products.findByIdForUpdateIncludingInactive(productId).orElseThrow();
            em.refresh(product);
        }
        for(var r:reservations){
            long movement=inventory.releaseForOrderChange(r.productId(),r.quantity(),order.getId(),op.id(),r.itemId());
            store.released(op.id(),r.itemId(),movement,orders.findCurrentTimeInTokyo());
        }
        store.aborted(op,orders.findCurrentTimeInTokyo());
    }
}
