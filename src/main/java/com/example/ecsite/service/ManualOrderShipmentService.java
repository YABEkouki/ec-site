package com.example.ecsite.service;

import java.util.Objects;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.entity.OrderStatusHistoryActorType;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.exception.OrderNotFoundException;
import com.example.ecsite.repository.OrderRepository;
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.payment.OrderPaymentGuard;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Internal manual-shipment operation; OrderService supplies the surrounding transaction. */
@Service
public class ManualOrderShipmentService {
    private final OrderRepository orders;
    private final OrderWriteContext context;
    private final OrderShipmentGuard shipmentGuard;
    private final OrderPaymentGuard paymentGuard;
    private final OrderStatusHistoryService histories;
    public ManualOrderShipmentService(OrderRepository orders, OrderWriteContext context, OrderShipmentGuard shipmentGuard,
            OrderPaymentGuard paymentGuard, OrderStatusHistoryService histories) {
        this.orders = orders; this.context = context; this.shipmentGuard = shipmentGuard;
        this.paymentGuard = paymentGuard; this.histories = histories;
    }

    /** Retains the existing caller API but never trusts caller-supplied actor snapshots. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void ship(Long id, Long accountId, String username, String internalNote) {
        if (id == null || id <= 0) throw new IllegalArgumentException("正の注文IDが必要です。");
        AdminUserDetails actor = requireActor(accountId, username);
        context.requireCleanWritableReadCommitted();
        var order = orders.findByIdForUpdate(id).orElseThrow(() -> new OrderNotFoundException(id));
        context.refreshWritableOrder(order);
        if (order.getStatus() != OrderStatus.PAID)
            throw new InvalidOrderStatusException("支払済みの注文だけを発送済みに変更できます。");
        shipmentGuard.requirePrepared(order);
        context.refreshPayments(order);
        paymentGuard.validateManualShipment(order);
        var now = orders.findCurrentTimeInTokyo();
        order.confirmManualShipment(now, actor.getId(), actor.getUsername());
        histories.record(order, OrderStatus.PAID, OrderStatus.SHIPPED, OrderStatusHistoryActorType.ADMIN,
                actor.getId(), actor.getUsername(), internalNote);
    }

    private AdminUserDetails requireActor(Long accountId, String username) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))
                || !(authentication.getPrincipal() instanceof AdminUserDetails actor)
                || actor.getId() == null || actor.getId() <= 0 || actor.getUsername() == null
                || actor.getUsername().isBlank() || actor.getUsername().length() > 100
                || !Objects.equals(accountId, actor.getId()) || !Objects.equals(username, actor.getUsername())) {
            throw new AccessDeniedException("有効な管理者認証が必要です。");
        }
        return actor;
    }
}
