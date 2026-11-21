package com.example.ecsite.service;

import java.util.Objects;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.entity.OrderStatusHistoryActorType;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.exception.OrderNotFoundException;
import com.example.ecsite.repository.OrderRepository;
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.payment.OrderPaymentGuard;
import com.example.ecsite.service.warehouse.WarehouseShipmentActor;
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
    private com.example.ecsite.service.warehouse.WarehouseExportShipmentGuard exportGuard;

    /** Spring always injects it; hand-built unit fixtures predate Feature131 and V87 still guards the database. */
    @org.springframework.beans.factory.annotation.Autowired
    void setExportGuard(com.example.ecsite.service.warehouse.WarehouseExportShipmentGuard exportGuard) {
        this.exportGuard = exportGuard;
    }

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
        ship(id, OrderStatusHistoryActorType.ADMIN, actor.getId(), actor.getUsername(), internalNote);
    }

    /**
     * Feature132-A: ships an exported paid order as the system (warehouse shipment import), with no logged-in
     * administrator. Only the warehouse package can hold a {@link WarehouseShipmentActor}. Feature138-A: an
     * emergency-shipped order may ship this way as well.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void shipAsWarehouseImport(Long id, WarehouseShipmentActor actor) {
        if (id == null || id <= 0) throw new IllegalArgumentException("正の注文IDが必要です。");
        if (actor == null) throw new AccessDeniedException("倉庫出荷取込みの権限が必要です。");
        ship(id, OrderStatusHistoryActorType.SYSTEM, null, actor.username(), null);
    }

    private void ship(Long id, OrderStatusHistoryActorType actorType, Long actorId, String actorName, String internalNote) {
        boolean system = actorType == OrderStatusHistoryActorType.SYSTEM;
        context.requireCleanWritableReadCommitted();
        var order = orders.findByIdForUpdate(id).orElseThrow(() -> new OrderNotFoundException(id));
        context.refreshWritableOrder(order);
        if (order.getStatus() != OrderStatus.PAID)
            throw new InvalidOrderStatusException("支払済みの注文だけを発送済みに変更できます。");
        shipmentGuard.requirePrepared(order);
        // Feature138-A: the system ships an exported order or an emergency-shipped order (V89/V103).
        if (system && (exportGuard == null || !exportGuard.allowsSystemShipment(order)))
            throw new InvalidOrderStatusException("倉庫出力または緊急出荷で出荷準備に移った注文だけを倉庫出荷取込みで発送できます。");
        paymentGuard.requireNoOrderChange(order);
        context.refreshPayments(order);
        paymentGuard.validateManualShipment(order);
        // Feature131/138: an exported or emergency-shipped paid order ships only as handed to the warehouse
        // (no payment, same revision and amount).
        if (exportGuard != null && !exportGuard.matchesExport(order, null))
            throw new InvalidOrderStatusException("倉庫へ出力した内容と注文が一致しないため、発送できません。");
        var now = orders.findCurrentTimeInTokyo();
        order.confirmManualShipment(now, actorId, actorName);
        histories.record(order, OrderStatus.PAID, OrderStatus.SHIPPED, actorType, actorId, actorName, internalNote);
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
