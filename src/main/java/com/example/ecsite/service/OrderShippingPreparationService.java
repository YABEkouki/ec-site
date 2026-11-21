package com.example.ecsite.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
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
import com.example.ecsite.service.emergency.EmergencyShippingMode;
import com.example.ecsite.service.emergency.EmergencyShippingRejectedException;
import com.example.ecsite.service.emergency.EmergencyShippingStore;
import com.example.ecsite.service.payment.OrderPaymentGuard;
import com.example.ecsite.service.payment.PaymentService;

/**
 * Feature136: an administrator-started shipping preparation is an emergency shipping operation. It needs the
 * emergency shipping permission (admin_accounts.emergency_shipping_allowed, read from the DB), a reason, and the
 * content revision the administrator confirmed; it writes the V97 audit record in the same transaction.
 */
@Service
public class OrderShippingPreparationService {
    private final OrderRepository orders;
    private final PaymentService payments;
    private final OrderPaymentGuard paymentGuard;
    private final OrderModificationPolicy policy;
    private final OrderWriteContext writeContext;
    private EmergencyShippingStore emergency;

    public OrderShippingPreparationService(OrderRepository orders, PaymentService payments,
            OrderPaymentGuard paymentGuard, OrderModificationPolicy policy, OrderWriteContext writeContext) {
        this.orders = orders;
        this.payments = payments;
        this.paymentGuard = paymentGuard;
        this.policy = policy;
        this.writeContext = writeContext;
    }

    @Autowired
    void setEmergencyShippingStore(EmergencyShippingStore emergency) {
        this.emergency = emergency;
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
    public ShippingPreparationStartResult start(Long orderId, String reason, Integer expectedContentRevision) {
        if (orderId == null || orderId <= 0) {
            throw new IllegalArgumentException("正の注文IDが必要です。");
        }
        String trimmedReason = reason == null ? "" : reason.strip();
        if (trimmedReason.isEmpty() || trimmedReason.length() > EmergencyShippingStore.MAX_REASON_LENGTH) {
            throw new EmergencyShippingRejectedException("緊急出荷の理由を" + EmergencyShippingStore.MAX_REASON_LENGTH
                    + "文字以内で入力してください。");
        }
        if (expectedContentRevision == null || expectedContentRevision < 0) {
            throw new IllegalArgumentException("確認した注文内容の版が必要です。");
        }
        AdminUserDetails actor = requireAdmin();
        if (emergency == null) throw new IllegalStateException("緊急出荷の記録先が設定されていません。");
        writeContext.requireCleanWritableReadCommitted();
        // Current DB value, re-checked under lock by V97; a revoked permission takes effect without a new login.
        if (!emergency.isAllowed(actor.getId())) {
            throw new AccessDeniedException("緊急出荷の権限がありません。");
        }
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
        if (order.getContentRevision() != expectedContentRevision) {
            throw new EmergencyShippingRejectedException(
                    "画面を表示した後に注文内容が変更されました。最新の内容を確認してから操作してください。");
        }
        if (emergency.hasOpenPaymentDiscrepancy(order.getId())) {
            throw new EmergencyShippingRejectedException(
                    "未解決の決済不整合があるため、緊急出荷できません。決済不整合を確認してください。");
        }
        // Read after all lock waits and payment validation, before any mutation.
        var now = orders.findCurrentTimeInTokyo();
        // 136-A: past the change deadline; 136-C1: accepted after 14:00 and shipped on its acceptance day.
        EmergencyShippingMode mode;
        if (policy.allowsPreparationStart(order, now)) {
            mode = EmergencyShippingMode.AFTER_DEADLINE;
        } else if (policy.allowsSameDayEmergency(order, now)) {
            mode = EmergencyShippingMode.SAME_DAY;
        } else {
            throw new IllegalStateException("注文変更期限に到達しておらず、14時以降受付の当日出荷にも当たりません。");
        }
        // V97: the record comes first (order still unprepared), then the order preparation that must match it.
        try {
            emergency.insert(order.getId(), actor.getId(), actor.getUsername(), trimmedReason, now, mode);
        } catch (DataAccessException rejected) {
            throw new IllegalStateException("緊急出荷の記録をデータベースが受け付けませんでした。", rejected);
        }
        order.beginShippingPreparation(now, actor.getId(), actor.getUsername());
        orders.flush();
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
        if (startedAt == null || !Order.isValidShippingPreparationActor(actorId, username)) {
            throw new IllegalStateException("発送準備開始情報が不整合です。");
        }
        return true;
    }

    private ShippingPreparationStartResult result(Order order, ShippingPreparationStartResult.Status status) {
        return new ShippingPreparationStartResult(status, order.getShippingPreparationStartedAt(),
                order.getShippingPreparationStartedByAccountId(), order.getShippingPreparationStartedByUsername());
    }
}
