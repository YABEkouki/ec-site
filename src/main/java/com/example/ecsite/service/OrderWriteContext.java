package com.example.ecsite.service;

import jakarta.persistence.EntityManager;
import org.hibernate.FlushMode;
import org.hibernate.Session;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.example.ecsite.entity.Order;
import com.example.ecsite.repository.PaymentRepository;
import com.example.ecsite.repository.PaymentTransactionRepository;

/**
 * Hibernate-specific safety for locked order mutations. No implicit flush/clear/detach.
 * isDirty detects tracked unflushed changes only, not already-flushed uncommitted writes.
 * Callers remain responsible for lock order for any earlier writes in their transaction.
 */
@Component
public class OrderWriteContext {
    private final EntityManager entityManager;
    private final PaymentRepository payments;
    private final PaymentTransactionRepository transactions;

    public OrderWriteContext(EntityManager entityManager, PaymentRepository payments, PaymentTransactionRepository transactions) {
        this.entityManager = entityManager;
        this.payments = payments;
        this.transactions = transactions;
    }

    /** Must run before repository queries can AUTO-flush managed changes. */
    public void requireCleanWritableReadCommitted() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("書込み可能なトランザクションが必要です。");
        }
        Session session = entityManager.unwrap(Session.class);
        if (session.isDefaultReadOnly() || session.getHibernateFlushMode() == FlushMode.MANUAL) {
            throw new IllegalStateException("書込み可能なPersistenceContextが必要です。");
        }
        if (session.isDirty()) throw new IllegalStateException("未flush変更があるため、操作できません。");
        // Inspect the actual transaction connection without triggering a JPA query/flush.
        session.doWork(connection -> {
            try (var statement = connection.prepareStatement(
                    "select current_setting('transaction_isolation'), current_setting('transaction_read_only')");
                    var result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("トランザクション設定を確認できません。");
                if (!"read committed".equals(result.getString(1)))
                    throw new IllegalStateException("READ COMMITTEDトランザクションが必要です。");
                if (!"off".equals(result.getString(2)))
                    throw new IllegalStateException("書込み可能なDBトランザクションが必要です。");
            }
        });
    }

    /** Caller has acquired Order's write lock. Refuse read-only before refresh. */
    public void refreshWritableOrder(Order order) {
        if (entityManager.unwrap(Session.class).isReadOnly(order))
            throw new IllegalStateException("読取り専用の注文では操作できません。");
        entityManager.refresh(order);
    }

    /** Caller holds Order. Lock all Payments in ID order before refreshing their histories. */
    public void refreshPayments(Order order) {
        var current = payments.findByOrderIdForUpdate(order.getId());
        for (var payment : current) entityManager.refresh(payment);
        // Compliant transaction creation and result writers also lock Order then Payment.
        for (var payment : current) {
            for (var transaction : transactions.findByPaymentIdOrderByCreatedAtAscIdAsc(payment.getId()))
                entityManager.refresh(transaction);
        }
    }
}
