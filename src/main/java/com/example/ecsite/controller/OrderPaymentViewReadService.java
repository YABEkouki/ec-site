package com.example.ecsite.controller;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.PaymentTransaction;
import com.example.ecsite.repository.PaymentRepository;
import jakarta.persistence.EntityManager;
import com.example.ecsite.service.orderchange.OrderChangeStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Two collection queries plus bounded, read-only operation evidence lookups; no locks or Gateway access. */
@Service
public class OrderPaymentViewReadService {
    private final PaymentRepository payments;
    private final EntityManager em;
    private final OrderPaymentViewFactory factory;
    private final OrderChangeStore changes;
    public OrderPaymentViewReadService(PaymentRepository payments, EntityManager em, OrderPaymentViewFactory factory, OrderChangeStore changes) {
        this.payments = payments; this.em = em; this.factory = factory; this.changes = changes;
    }
    @Transactional(readOnly = true)
    public OrderPaymentViewFactory.View read(Order order) {
        if (changes.unresolved(order.getId())) return OrderPaymentViewFactory.unavailable();
        var current = payments.findByOrderIdOrderByCreatedAtAscIdAsc(order.getId());
        var history = em.createQuery("select t from PaymentTransaction t join fetch t.payment p where p.order.id=:orderId order by t.id", PaymentTransaction.class)
                .setParameter("orderId", order.getId()).getResultList();
        // Reads are hints and can race with a commit. The mutating Service remains authoritative.
        return factory.create(order, current, history, changes.completedOperationIds(order.getId()), changes.abortedOperationIds(order.getId()));
    }
}
