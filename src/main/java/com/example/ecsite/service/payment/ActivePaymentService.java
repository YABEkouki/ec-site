package com.example.ecsite.service.payment;

import com.example.ecsite.entity.*;
import com.example.ecsite.repository.*;
import com.example.ecsite.service.OrderWriteContext;
import com.example.ecsite.service.OrderModificationGuard;
import com.example.ecsite.service.payment.safety.*;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Targeted reference initialization, never a provider authorization check or execution permit.
 * No caller/runner is connected in this stage. */
@Service
public class ActivePaymentService {
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final EntityManager em;
    private final OrderWriteContext context;
    private final OrderModificationGuard modification;
    private final OrderPaymentGuard paymentGuard;
    private final PaymentTransactionRepository transactions;
    public ActivePaymentService(OrderRepository orders, PaymentRepository payments, EntityManager em,
            OrderWriteContext context, OrderModificationGuard modification) {
        this(orders,payments,em,context,modification,null,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public ActivePaymentService(OrderRepository orders, PaymentRepository payments, EntityManager em,
            OrderWriteContext context, OrderModificationGuard modification, OrderPaymentGuard paymentGuard,
            PaymentTransactionRepository transactions) {
        this.orders=orders;this.payments=payments;this.em=em;this.context=context;this.modification=modification;
        this.paymentGuard=paymentGuard;this.transactions=transactions;
    }

    @Transactional
    public Long initializeForOrderChange(Long orderId) {
        if(orderId==null || orderId<=0)throw new IllegalArgumentException("Positive order ID required");
        context.requireCleanWritableReadCommitted();
        var order=orders.findByIdForUpdate(orderId).orElseThrow(()->new IllegalStateException("Order missing"));
        context.refreshWritableOrder(order);
        if(order.getStatus()!=OrderStatus.ORDERED || order.getShipmentCapturePaymentId()!=null)
            throw new IllegalStateException("Order is not eligible");
        // Hibernate owns both JPA and JDBC access: no independent connection or transaction.
        var facts=em.unwrap(Session.class).doReturningWork(c -> new JdbcPaymentSafetySnapshotReader()
                .readLocked(c,orderId,PaymentSafetyReadContext.defaults()).orElseThrow());
        // JDBC already locked every Payment in ID order; refresh complete managed evidence afterwards.
        var locked=payments.findByOrderIdForUpdate(orderId);
        locked.forEach(em::refresh);
        if(order.getActivePaymentId()!=null && paymentGuard!=null && transactions!=null) {
            var history=new ArrayList<PaymentTransaction>();
            for(var payment:locked) {
                var own=transactions.findByPaymentIdOrderByCreatedAtAscIdAsc(payment.getId());
                own.forEach(em::refresh);history.addAll(own);
            }
            if(establishedBoundAuthorization(order,locked,history,paymentGuard.completedOrderChanges(order),paymentGuard.abortedOrderChanges(order))) {
                modification.requireAllowed(order);
                return order.getActivePaymentId();
            }
        }
        var assembly=new PaymentSafetySnapshotAssembler().assemble(facts,CaptureEvidenceProfiles.none());
        var classification=new PaymentSafetyClassifier().classify(assembly.snapshot());
        if(classification.disposition()!=PaymentSafetyClassification.ActivePaymentDisposition.SELECTED)
            throw new IllegalStateException("Payment requires review");
        Long selected=classification.activePaymentId();
        var raw=facts.payments().stream().filter(p->Objects.equals(p.id(),selected)).findFirst().orElseThrow();
        if(!"AUTHORIZED".equals(raw.status()) || raw.providerPaymentId()==null || raw.providerPaymentId().isBlank()
                || facts.transactions().stream().anyMatch(t->"PENDING".equals(t.status())))
            throw new IllegalStateException("Payment not ready for targeted initialization");
        var authorization=facts.transactions().stream().filter(t->Objects.equals(t.paymentId(),selected)
                && "AUTHORIZE".equals(t.transactionType()) && "SUCCEEDED".equals(t.status())).toList();
        if(authorization.size()!=1)throw new IllegalStateException("Authorization is ambiguous");
        // Read/refresh managed entities only after JDBC has acquired all Payment locks in ID order.
        var payment=locked.stream().filter(p->Objects.equals(p.getId(),selected)).findFirst().orElseThrow();
        em.refresh(payment);
        modification.requireAllowed(order);
        order.bindActivePayment(payment);
        return selected;
    }
    static boolean establishedBoundAuthorization(Order order,List<Payment> payments,List<PaymentTransaction> history,
            Set<UUID> completedChanges) {
        return establishedBoundAuthorization(order,payments,history,completedChanges,Set.of());
    }
    static boolean establishedBoundAuthorization(Order order,List<Payment> payments,List<PaymentTransaction> history,
            Set<UUID> completedChanges,Set<UUID> abortedChanges) {
        if(!CaptureShipmentPolicy.validInput(order,payments,history) || order.getActivePaymentId()==null || history.stream().anyMatch(t->t.getStatus()==PaymentTransactionStatus.PENDING)
                || !PaymentPurposeResolver.usableHistory(order,payments,history,completedChanges,abortedChanges)) return false;
        var active=payments.stream().filter(p->p.getStatus()!=PaymentStatus.FAILED && p.getStatus()!=PaymentStatus.CANCELLED).toList();
        if(active.size()!=1) return false;
        var payment=active.getFirst();
        if(!Objects.equals(order.getActivePaymentId(),payment.getId()) || payment.getStatus()!=PaymentStatus.AUTHORIZED) return false;
        var own=history.stream().filter(t->Objects.equals(t.getPayment().getId(),payment.getId())).toList();
        if(own.size()!=1) return false;
        var auth=own.getFirst();
        boolean changed=auth.getPurpose()==PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION;
        boolean initialAfterAbort=(auth.getPurpose()==null || auth.getPurpose()==PaymentTransactionPurpose.INITIAL_ORDER_AUTHORIZATION)
                && abortedChanges!=null && history.stream().anyMatch(t->t.getOrderChangeOperationId()!=null && abortedChanges.contains(t.getOrderChangeOperationId()));
        return auth.getTransactionType()==PaymentTransactionType.AUTHORIZE && (changed || initialAfterAbort)
                && payment.getProvider()==PaymentProvider.PAYJP && payment.getPaymentMethod()==PaymentMethod.CARD
                && payment.getProviderPaymentId()!=null && !payment.getProviderPaymentId().isBlank()
                && Objects.equals(auth.getProviderTransactionId(),payment.getProviderPaymentId())
                && auth.getStatus()==PaymentTransactionStatus.SUCCEEDED
                && new PaymentConsistencyEvaluator().evaluate(order,payment,auth).canCapture();
    }

}
