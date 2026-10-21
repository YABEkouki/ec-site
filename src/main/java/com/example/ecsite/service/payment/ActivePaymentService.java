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
import java.util.Objects;

/** Targeted reference initialization, never a provider authorization check or execution permit.
 * No caller/runner is connected in this stage. */
@Service
public class ActivePaymentService {
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final EntityManager em;
    private final OrderWriteContext context;
    private final OrderModificationGuard modification;
    public ActivePaymentService(OrderRepository orders, PaymentRepository payments, EntityManager em,
            OrderWriteContext context, OrderModificationGuard modification) {
        this.orders=orders;this.payments=payments;this.em=em;this.context=context;this.modification=modification;
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
        var locked=payments.findByOrderIdForUpdate(orderId);
        var payment=locked.stream().filter(p->Objects.equals(p.getId(),selected)).findFirst().orElseThrow();
        em.refresh(payment);
        modification.requireAllowed(order);
        order.bindActivePayment(payment);
        return selected;
    }
}
