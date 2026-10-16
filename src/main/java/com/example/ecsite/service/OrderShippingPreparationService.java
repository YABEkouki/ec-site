package com.example.ecsite.service;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.hibernate.FlushMode;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.example.ecsite.repository.PaymentRepository;
import com.example.ecsite.repository.PaymentTransactionRepository;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.exception.OrderNotFoundException;
import com.example.ecsite.repository.OrderRepository;
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.payment.OrderPaymentGuard;
import com.example.ecsite.service.payment.PaymentService;

@Service
public class OrderShippingPreparationService {
    private final OrderRepository orders;
    private final PaymentService payments;
    private final OrderPaymentGuard paymentGuard;
    private final OrderModificationPolicy policy;
    private final EntityManager entityManager;
    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository transactionRepository;

    public OrderShippingPreparationService(OrderRepository orders, PaymentService payments,
            OrderPaymentGuard paymentGuard, OrderModificationPolicy policy, EntityManager entityManager,
            PaymentRepository paymentRepository, PaymentTransactionRepository transactionRepository) {
        this.orders = orders;
        this.payments = payments;
        this.paymentGuard = paymentGuard;
        this.policy = policy;
        this.entityManager = entityManager;
        this.paymentRepository = paymentRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Call through the Spring bean. Joins a writable READ COMMITTED transaction;
     * Order then Payment locks last until its commit. Any tracked unflushed change,
     * including an unrelated entity or collection, is rejected before queries/refresh.
     * Already flushed but uncommitted writes cannot be detected by isDirty(); they
     * remain part of the caller's transaction and its rollback. Callers must still
     * follow Order -> Payment -> Product lock order for their own earlier writes.
     */
    @Transactional
    public ShippingPreparationStartResult start(Long orderId) {
        if (orderId == null || orderId <= 0) {
            throw new IllegalArgumentException("正の注文IDが必要です。");
        }
        AdminUserDetails actor = requireAdmin();
        requireCleanWritableReadCommittedContext();
        Order order = orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        // refresh preserves an entity's individual read-only flag; do not discard
        // untracked edits or report STARTED for an Order that cannot be dirty-checked.
        if (entityManager.unwrap(Session.class).isReadOnly(order)) {
            throw new IllegalStateException("読取り専用の注文では発送準備を開始できません。");
        }
        // A locking query alone does not replace an already-managed entity's values.
        entityManager.refresh(order);
        if (order.getStatus() != OrderStatus.ORDERED && order.getStatus() != OrderStatus.PAID) {
            throw new IllegalStateException("この注文状態では発送準備を開始できません。");
        }
        if (isAlreadyPrepared(order)) {
            return result(order, ShippingPreparationStartResult.Status.ALREADY_STARTED);
        }
        refreshPaymentState(order);
        if (order.getStatus() == OrderStatus.ORDERED) {
            payments.validateCardPaymentForShippingPreparation(order);
        } else {
            paymentGuard.validateManualShipment(order);
        }
        // Read after all lock waits and payment validation, before any mutation.
        var now = orders.findCurrentTimeInTokyo();
        if (!policy.allowsPreparationStart(order, now)) {
            throw new IllegalStateException("注文変更期限に到達していないか、注文期限が不正です。");
        }
        order.beginShippingPreparation(now, actor.getId(), actor.getUsername());
        // The locked Order is managed; dirty checking persists all three fields together.
        return result(order, ShippingPreparationStartResult.Status.STARTED);
    }

    private void requireCleanWritableReadCommittedContext() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("書込み可能なトランザクションが必要です。");
        }
        Session session = entityManager.unwrap(Session.class);
        if (session.isDefaultReadOnly() || session.getHibernateFlushMode() == FlushMode.MANUAL) {
            throw new IllegalStateException("書込み可能なPersistenceContextが必要です。");
        }
        if (session.isDirty()) {
            throw new IllegalStateException("未flush変更があるため、発送準備を開始できません。");
        }
        // Raw JDBC on the transaction's own connection does not trigger JPA AUTO flush.
        // Read server settings, not Spring metadata or a presumed connection default.
        session.doWork(connection -> {
            try (var statement = connection.prepareStatement(
                    "select current_setting('transaction_isolation'), current_setting('transaction_read_only')");
                    var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException("トランザクション設定を確認できません。");
                }
                if (!"read committed".equals(result.getString(1))) {
                    throw new IllegalStateException("READ COMMITTEDトランザクションが必要です。");
                }
                if (!"off".equals(result.getString(2))) {
                    throw new IllegalStateException("書込み可能なDBトランザクションが必要です。");
                }
            }
        });
    }

    private void refreshPaymentState(Order order) {
        // Acquire ALL Payment locks in ascending ID order before reading histories.
        var current = paymentRepository.findByOrderIdForUpdate(order.getId());
        for (var payment : current) entityManager.refresh(payment);
        // Compliant operation creation/result application holds Order then Payment.
        // Their locks protect these ordinary refresh reads; no child write lock is needed.
        for (var payment : current) {
            for (var transaction : transactionRepository.findByPaymentIdOrderByCreatedAtAscIdAsc(payment.getId())) {
                entityManager.refresh(transaction);
            }
        }
        // Existing eligibility APIs re-lock the same parents in the same order.
    }

    private AdminUserDetails requireAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))
                || !(authentication.getPrincipal() instanceof AdminUserDetails actor)
                || actor.getId() == null || actor.getId() <= 0
                || actor.getUsername() == null || actor.getUsername().isBlank()
                || actor.getUsername().length() > 100) {
            throw new AccessDeniedException("有効な管理者認証が必要です。");
        }
        return actor;
    }

    private boolean isAlreadyPrepared(Order order) {
        var startedAt = order.getShippingPreparationStartedAt();
        var actorId = order.getShippingPreparationStartedByAccountId();
        var username = order.getShippingPreparationStartedByUsername();
        if (startedAt == null && actorId == null && username == null) {
            return false;
        }
        if (startedAt == null || actorId == null || actorId <= 0
                || username == null || username.isBlank() || username.length() > 100) {
            throw new IllegalStateException("発送準備開始情報が不整合です。");
        }
        return true;
    }

    private ShippingPreparationStartResult result(Order order, ShippingPreparationStartResult.Status status) {
        return new ShippingPreparationStartResult(status, order.getShippingPreparationStartedAt(),
                order.getShippingPreparationStartedByAccountId(), order.getShippingPreparationStartedByUsername());
    }
}
