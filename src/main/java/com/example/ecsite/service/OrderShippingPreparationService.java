package com.example.ecsite.service;

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
    private final OrderWriteContext writeContext;

    public OrderShippingPreparationService(OrderRepository orders, PaymentService payments,
            OrderPaymentGuard paymentGuard, OrderModificationPolicy policy, OrderWriteContext writeContext) {
        this.orders = orders;
        this.payments = payments;
        this.paymentGuard = paymentGuard;
        this.policy = policy;
        this.writeContext = writeContext;
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
        writeContext.requireCleanWritableReadCommitted();
        Order order = orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        writeContext.refreshWritableOrder(order);
        if (order.getStatus() != OrderStatus.ORDERED && order.getStatus() != OrderStatus.PAID) {
            throw new IllegalStateException("この注文状態では発送準備を開始できません。");
        }
        if (isAlreadyPrepared(order)) {
            return result(order, ShippingPreparationStartResult.Status.ALREADY_STARTED);
        }
        paymentGuard.requireNoOrderChange(order);
        writeContext.refreshPayments(order);
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
