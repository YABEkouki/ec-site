package com.example.ecsite.service.orderchange;

import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.repository.*;
import com.example.ecsite.service.*;
import com.example.ecsite.service.payment.*;
import com.example.ecsite.service.pricing.*;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import java.security.*;
import java.util.*;

/** Internal stage-II API. Accepts only increases; no customer endpoint or financial calls. */
@Service
public class OrderIncreaseService {
    public record Request(long orderId,long customerId,UUID submissionKey,int baseRevision,Map<Long,Integer> quantities) {
        public Request {
            if(orderId<=0 || customerId<=0 || submissionKey==null || baseRevision<0 || quantities==null || quantities.isEmpty() || quantities.size()>1000)
                throw new IllegalArgumentException("Invalid quantity-increase request");
            quantities=Map.copyOf(quantities);
            if(quantities.entrySet().stream().anyMatch(e->e.getKey()<=0 || e.getValue()<=0))
                throw new IllegalArgumentException("Positive item IDs and quantities required");
        }
        String digest(){
            try {
                var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
                out.writeUTF("ecsite.order-increase.request.v1");out.writeLong(orderId);out.writeLong(customerId);out.writeInt(baseRevision);
                out.writeInt(quantities.size());for(var e:new TreeMap<>(quantities).entrySet()){out.writeLong(e.getKey());out.writeInt(e.getValue());}
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
            }catch(IOException|NoSuchAlgorithmException e){throw new IllegalStateException("Cannot encode change request",e);}
        }
    }
    private final OrderRepository orders;
    private final ProductRepository products;
    private final OrderChangeStore store;
    private final OrderPaymentGuard guard;
    private final OrderWriteContext context;
    private final ActivePaymentService active;
    private final OrderAmountCalculator calculator;
    private final OrderModificationGuard deadline;
    private final OrderModificationPolicy policy;
    private final InventoryService inventory;
    private final EntityManager em;
    public OrderIncreaseService(OrderRepository orders,ProductRepository products,OrderChangeStore store,OrderPaymentGuard guard,
            OrderWriteContext context,ActivePaymentService active,OrderAmountCalculator calculator,OrderModificationGuard deadline,
            OrderModificationPolicy policy,InventoryService inventory,EntityManager em){
        this.orders=orders;this.products=products;this.store=store;this.guard=guard;this.context=context;this.active=active;
        this.calculator=calculator;this.deadline=deadline;this.policy=policy;this.inventory=inventory;this.em=em;
    }
    @Transactional
    public OrderChangeStore.Operation accept(Request request){
        Objects.requireNonNull(request);
        context.requireCleanWritableReadCommitted();
        var order=lockedOrder(request.orderId(),request.customerId());
        String digest=request.digest();
        var replay=store.submission(order.getId(),request.submissionKey());
        if(replay.isPresent()){
            if(!replay.get().requestDigest().equals(digest))throw new IllegalStateException("Submission content conflict");
            return replay.get();
        }
        guard.requireNoOrderChange(order);
        if(order.getStatus()!=OrderStatus.ORDERED || order.getContentRevision()!=request.baseRevision())
            throw new IllegalStateException("Order state or revision changed");
        var all=order.getItems();
        if(all.isEmpty() || all.size()>1000)throw new IllegalStateException("Invalid order item set");
        var byId=new HashMap<Long,OrderItem>();all.forEach(i->{if(byId.put(i.getId(),i)!=null)throw new IllegalStateException("Duplicate item");});
        for(var e:request.quantities().entrySet()){
            var item=byId.get(e.getKey());
            if(item==null || e.getValue()<=item.getQuantity())throw new IllegalArgumentException("Only existing-item increases are accepted");
        }
        // This locks all Payments in ascending ID order and validates complete saved history.
        long payment=active.initializeForOrderChange(order.getId());
        var pricing=new OrderPricingContext();long subtotal=0;
        for(var item:all){
            int quantity=request.quantities().getOrDefault(item.getId(),item.getQuantity());
            subtotal=Math.addExact(subtotal,Math.multiplyExact((long)item.getPrice(),quantity));
            if(item.getPrice()<0 || subtotal>Integer.MAX_VALUE)throw new IllegalArgumentException("Candidate amount exceeds supported range");
            pricing.addItem(item.getPrice(),quantity,item.getTaxCategoryId(),item.getTaxCategoryCode(),item.getTaxCategoryName(),item.getTaxRate());
        }
        var shipping=order.getCharges().stream().filter(c->c.getChargeType()==OrderChargeType.SHIPPING).toList();
        if(shipping.size()!=1 || order.getCharges().size()!=1)throw new IllegalStateException("Shipping snapshot is ambiguous");
        var amount=calculator.calculate(pricing,ChargeTaxSnapshot.from(shipping.getFirst()));
        if(amount.charges().size()!=1 || amount.totalAmount()<=0 || amount.taxAmount()<0
                || (long)amount.itemSubtotal()+amount.chargeTotal()!=amount.totalAmount())throw new IllegalStateException("Unsupported candidate charges");
        var changed=request.quantities().keySet().stream().map(byId::get)
                .sorted(Comparator.comparing(OrderItem::getProductId).thenComparing(OrderItem::getId)).toList();
        // Lock ALL Products before accepting the deadline or making the first reservation.
        for(long productId:changed.stream().map(OrderItem::getProductId).distinct().toList()){
            var product=products.findByIdForUpdate(productId).orElseThrow(()->new IllegalStateException("Product is unavailable"));
            em.refresh(product);
        }
        var now=deadline.requireAllowed(order);
        UUID id=UUID.randomUUID();
        store.insert(id,request,digest,payment,order.getTotalAmount(),amount,now,order.getChangeDeadlineAt(),policy.changeGraceDeadline(order));
        for(var item:changed){
            int quantity=request.quantities().get(item.getId()),delta=quantity-item.getQuantity();
            store.item(id,order.getId(),item,quantity);
            long movement=inventory.reserveForOrderChange(item.getProductId(),delta,order.getId(),id,item.getId());
            store.held(id,item.getId(),item.getProductId(),delta,movement,now);
        }
        return store.operation(order.getId(),id).orElseThrow();
    }
    @Transactional
    public void abort(long orderId,long customerId,UUID operationId){
        context.requireCleanWritableReadCommitted();var order=lockedOrder(orderId,customerId);
        release(order,store.operation(orderId,operationId).orElseThrow());
    }
    /** Internal expiry hook: no scheduler and no automatic financial cleanup is introduced. */
    @Transactional
    public boolean expireReserved(long orderId,UUID operationId){
        context.requireCleanWritableReadCommitted();var order=orders.findByIdForUpdate(orderId).orElseThrow();context.refreshWritableOrder(order);
        var op=store.operation(orderId,operationId).orElseThrow();
        if(!op.state().equals("RESERVED") || !policy.canExpireChange(op.graceUntil(),op.switchDecidedAt(),orders.findCurrentTimeInTokyo()))return false;
        release(order,op);return true;
    }
    private Order lockedOrder(long id,long customer){
        var order=orders.findByIdAndUserIdForUpdate(id,customer).orElseThrow(()->new IllegalStateException("Order unavailable"));
        context.refreshWritableOrder(order);return order;
    }
    private void release(Order order,OrderChangeStore.Operation op){
        if(op.state().equals("ABORTED"))return;
        if(!op.state().equals("RESERVED") || op.switchDecidedAt()!=null)throw new IllegalStateException("Financial change cannot be aborted here");
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
