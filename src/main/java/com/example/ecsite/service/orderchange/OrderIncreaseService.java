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

/** Accepts existing-item increases and local reservations only. Never calls a financial API. */
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
    public OrderChangeStore.Operation accept(Request request){return accept(request,null);}
    @Transactional
    public OrderChangeStore.Operation acceptConfirmed(Preview preview){
        Objects.requireNonNull(preview);return accept(preview.request(),preview);
    }
    private OrderChangeStore.Operation accept(Request request,Preview confirmed){
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
        var amount=candidateAmount(order,request);
        if(confirmed!=null && (confirmed.beforeAmount()!=order.getTotalAmount() || !confirmed.amount().equals(amount)
                || !confirmed.lines().equals(lines(order,request.quantities()))))
            throw new IllegalStateException("Candidate pricing changed; confirmation is required again");
        // Lock and validate saved financial history only when accepting, never while previewing.
        long payment=active.initializeForOrderChange(order.getId());
        var byId=new HashMap<Long,OrderItem>();order.getItems().forEach(i->byId.put(i.getId(),i));
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
    public record Line(long id,String name,int price,int beforeQuantity,int afterQuantity){}
    public record Preview(Request request,int beforeAmount,OrderAmount amount,List<Line> lines){
        public Preview{lines=List.copyOf(lines);}
    }
    public record Input(long orderId,int revision,int totalAmount,List<Line> lines,OrderChangeStore.Operation pending,boolean available){
        public Input{lines=List.copyOf(lines);}
    }
    @Transactional
    public Input input(long orderId,long customerId){
        var order=lockedOrder(orderId,customerId);
        var pending=store.unresolvedOperation(orderId).orElse(null);
        return new Input(orderId,order.getContentRevision(),order.getTotalAmount(),lines(order,Map.of()),pending,
            pending==null && order.getStatus()==OrderStatus.ORDERED && policy.allowsModification(order,orders.findCurrentTimeInTokyo()));
    }
    @Transactional
    public Preview preview(Request request){
        var order=lockedOrder(request.orderId(),request.customerId());
        guard.requireNoOrderChange(order);
        if(order.getStatus()!=OrderStatus.ORDERED || order.getContentRevision()!=request.baseRevision())
            throw new IllegalStateException("Order state or revision changed");
        deadline.requireAllowed(order);
        return new Preview(request,order.getTotalAmount(),candidateAmount(order,request),lines(order,request.quantities()));
    }
    @Transactional
    public OrderChangeStore.Operation status(long orderId,long customerId,UUID id){
        lockedOrder(orderId,customerId);
        return store.operation(orderId,id).orElseThrow(()->new com.example.ecsite.exception.OrderNotFoundException(orderId));
    }
    private List<Line> lines(Order order,Map<Long,Integer> quantities){
        if(order.getItems().isEmpty() || order.getItems().size()>1000)throw new IllegalStateException("Unsupported order size");
        return order.getItems().stream().map(i->new Line(i.getId(),i.getProductName(),i.getPrice(),i.getQuantity(),
            quantities.getOrDefault(i.getId(),i.getQuantity()))).toList();
    }
    private OrderAmount candidateAmount(Order order,Request request){
        var all=order.getItems();
        if(all.isEmpty() || all.size()>1000)throw new IllegalStateException("Invalid order item set");
        var byId=new HashMap<Long,OrderItem>();all.forEach(i->{if(byId.put(i.getId(),i)!=null)throw new IllegalStateException("Duplicate item");});
        for(var e:request.quantities().entrySet()){
            var item=byId.get(e.getKey());
            if(item==null || e.getValue()<=item.getQuantity())throw new IllegalArgumentException("Only existing-item increases are accepted");
        }
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
        return amount;
    }
    @Transactional
    public void abort(long orderId,long customerId,UUID operationId){
        context.requireCleanWritableReadCommitted();var order=lockedOrder(orderId,customerId);
        release(order,store.operation(orderId,operationId).orElseThrow(()->new com.example.ecsite.exception.OrderNotFoundException(orderId)));
    }
    @Transactional(readOnly=true)
    public List<OrderChangeStore.ExpiringOperation> expiredReservedCandidates(int limit){return store.expiredReserved(limit);}
    /** Local-only expiry under the same Order/operation locks as acceptance and withdrawal. */
    @Transactional
    public boolean expireReserved(long orderId,UUID operationId){
        context.requireCleanWritableReadCommitted();var order=orders.findByIdForUpdate(orderId).orElseThrow();context.refreshWritableOrder(order);
        var op=store.operation(orderId,operationId).orElseThrow();
        if(!op.state().equals("RESERVED") || !policy.canExpireChange(op.graceUntil(),op.switchDecidedAt(),orders.findCurrentTimeInTokyo()))return false;
        if(store.financialStarted(operationId))return false;
        release(order,op);return true;
    }
    private Order lockedOrder(long id,long customer){
        var order=orders.findByIdAndUserIdForUpdate(id,customer).orElseThrow(()->new com.example.ecsite.exception.OrderNotFoundException(id));
        context.refreshWritableOrder(order);return order;
    }
    private void release(Order order,OrderChangeStore.Operation op){
        if(op.state().equals("ABORTED"))return;
        if(!op.state().equals("RESERVED") || op.switchDecidedAt()!=null || store.financialStarted(op.id()))throw new IllegalStateException("Financial change cannot be aborted here");
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
