package com.example.ecsite.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.service.pricing.OrderAmount;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "total_amount", nullable = false)
    private int totalAmount;

    @Column(name = "item_subtotal", nullable = false)
    private int itemSubtotal;

    @Column(name = "charge_total", nullable = false)
    private int chargeTotal;

    @Column(name = "tax_amount", nullable = false)
    private int taxAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderStatus status = OrderStatus.ORDERED;

    @Enumerated(EnumType.STRING)
    @Column(name = "handling_status", nullable = false, length = 30)
    private OrderHandlingStatus handlingStatus = OrderHandlingStatus.NONE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_admin_account_id")
    private AdminAccount assignedAdminAccount;

    @Column(name = "ordered_at", nullable = false)
    private LocalDateTime orderedAt;

    @Column(name = "change_deadline_at", nullable = false)
    private LocalDateTime changeDeadlineAt;

    @Column(name = "content_revision", nullable = false)
    private int contentRevision;

    @Column(name = "shipping_name", length = 100)
    private String shippingName;

    @Column(name = "shipping_postal_code", length = 8)
    private String shippingPostalCode;

    @Column(name = "shipping_prefecture", length = 20)
    private String shippingPrefecture;

    @Column(name = "shipping_city", length = 100)
    private String shippingCity;

    @Column(name = "shipping_address_line", length = 200)
    private String shippingAddressLine;

    @Column(name = "shipping_phone", length = 20)
    private String shippingPhone;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "shipped_at")
    private LocalDateTime shippedAt;

    @Column(name = "shipping_preparation_started_at")
    private LocalDateTime shippingPreparationStartedAt;

    @Column(name = "shipping_preparation_started_by_account_id")
    private Long shippingPreparationStartedByAccountId;

    @Column(name = "shipping_preparation_started_by_username", length = 100)
    private String shippingPreparationStartedByUsername;

    @Column(name = "shipped_by_account_id")
    private Long shippedByAccountId;

    @Column(name = "shipped_by_username", length = 100)
    private String shippedByUsername;

    @Column(name = "shipment_capture_payment_id")
    private Long shipmentCapturePaymentId;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderCharge> charges = new ArrayList<>();

    public Order() {
    }

    public Order(
            Long userId,
            int totalAmount,
            LocalDateTime orderedAt,
            LocalDateTime changeDeadlineAt) {
        this.userId = userId;
        this.totalAmount = totalAmount;
        this.status = OrderStatus.ORDERED;
        this.orderedAt = orderedAt;
        this.changeDeadlineAt = changeDeadlineAt;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public int getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(int totalAmount) {
        this.totalAmount = totalAmount;
    }

    public int getItemSubtotal() {
        return itemSubtotal;
    }

    public int getChargeTotal() {
        return chargeTotal;
    }

    public int getTaxAmount() {
        return taxAmount;
    }

    public void applyAmount(OrderAmount amount) {

        this.itemSubtotal = amount.itemSubtotal();
        this.chargeTotal = amount.chargeTotal();
        this.taxAmount = amount.taxAmount();
        this.totalAmount = amount.totalAmount();
    }

    public OrderStatus getStatus() {
        return status;
    }

    public LocalDateTime getOrderedAt() {
        return orderedAt;
    }

    public LocalDateTime getChangeDeadlineAt() {
        return changeDeadlineAt;
    }

    public int getContentRevision() {
        return contentRevision;
    }

    public void incrementContentRevision() {
        contentRevision++;
    }

    public OrderHandlingStatus getHandlingStatus() {
        return handlingStatus;
    }

    public void changeHandlingStatus(OrderHandlingStatus handlingStatus) {
        this.handlingStatus = handlingStatus;
    }

    public void setOrderedAt(LocalDateTime orderedAt) {
        this.orderedAt = orderedAt;
    }

    public LocalDateTime getPaidAt() {
        return paidAt;
    }

    public LocalDateTime getShippedAt() {
        return shippedAt;
    }

    /** Records preparation metadata together; status and deadline checks belong to the service. */
    public void beginShippingPreparation(LocalDateTime now, Long actorId, String username) {
        if (now == null || actorId == null || actorId <= 0
                || username == null || username.isBlank() || username.length() > 100) {
            throw new IllegalArgumentException("有効な発送準備開始日時と管理者情報が必要です。");
        }
        if (shippingPreparationStartedAt != null || shippingPreparationStartedByAccountId != null
                || shippingPreparationStartedByUsername != null) {
            throw new IllegalStateException("発送準備開始情報は上書きできません。");
        }
        shippingPreparationStartedAt = now;
        shippingPreparationStartedByAccountId = actorId;
        shippingPreparationStartedByUsername = username;
    }

    /** Shared metadata predicate; does not decide status, payment eligibility or deadlines. */
    public boolean hasCompleteShippingPreparation() {
        return shippingPreparationStartedAt != null
                && validShipmentActor(shippingPreparationStartedByAccountId, shippingPreparationStartedByUsername);
    }

    /**
     * Records a paid manual shipment. Caller owns transaction/locks, payment checks
     * and the single status-history entry. Existing paidAt is retained.
     */
    public void confirmManualShipment(LocalDateTime shippedAt, Long actorId, String username) {
        requireNewShipment(OrderStatus.PAID, shippedAt, actorId, username);
        recordShipment(shippedAt, actorId, username, null);
    }

    /**
     * Records shipment before capture. The caller must verify the positive payment
     * ID belongs to this order and fix it in the capture operation in the same TX.
     * No payment success or paidAt is recorded here.
     */
    public void confirmCardShipment(LocalDateTime shippedAt, Long actorId, String username, Long capturePaymentId) {
        if (capturePaymentId == null || capturePaymentId <= 0) {
            throw new IllegalArgumentException("有効なCapture対象Payment IDが必要です。");
        }
        requireNewShipment(OrderStatus.ORDERED, shippedAt, actorId, username);
        if (paidAt != null) throw new IllegalStateException("入金日時が設定済みの注文はカード発送できません。");
        recordShipment(shippedAt, actorId, username, capturePaymentId);
    }

    /**
     * Order's part of a successful capture only. Caller must apply verified Payment
     * and transaction success atomically in the same TX. Does not create shipment
     * history or modify shipment metadata; repeated success preserves first paidAt.
     * capturedAt is the application's capture-success confirmation/recording time
     * in Asia/Tokyo, supplied by the caller; LocalDateTime carries no time zone.
     * This method reads no clock and applies no precision rounding or tolerance.
     * It must be at or after shippedAt, including on repeated success calls.
     * A failed or unresolved capture must not call this method.
     */
    public void recordShipmentCaptureSucceeded(Long capturePaymentId, LocalDateTime capturedAt) {
        if (capturedAt == null || capturePaymentId == null || capturePaymentId <= 0) {
            throw new IllegalArgumentException("有効なCapture対象Payment IDと入金日時が必要です。");
        }
        if (status != OrderStatus.SHIPPED || !hasCompleteShippingPreparation()
                || shippedAt == null || !validShipmentActor(shippedByAccountId, shippedByUsername)
                || !capturePaymentId.equals(shipmentCapturePaymentId)) {
            throw new IllegalStateException("発送記録とCapture対象Paymentが一致しません。");
        }
        if (capturedAt.isBefore(shippedAt)) {
            throw new IllegalArgumentException("Capture成功記録日時は発送日時以降である必要があります。");
        }
        if (paidAt == null) paidAt = capturedAt;
    }

    private void requireNewShipment(OrderStatus sourceStatus, LocalDateTime at, Long actorId, String username) {
        if (at == null || !validShipmentActor(actorId, username)) {
            throw new IllegalArgumentException("有効な発送日時と管理者情報が必要です。");
        }
        if (status != sourceStatus) throw new InvalidOrderStatusException("この注文状態では発送できません。");
        if (!hasCompleteShippingPreparation()) throw new IllegalStateException("有効な発送準備情報が必要です。");
        if (shippedAt != null || shippedByAccountId != null || shippedByUsername != null
                || shipmentCapturePaymentId != null) {
            throw new IllegalStateException("発送記録は上書きできません。");
        }
    }

    private void recordShipment(LocalDateTime at, Long actorId, String username, Long capturePaymentId) {
        status = OrderStatus.SHIPPED;
        shippedAt = at;
        shippedByAccountId = actorId;
        shippedByUsername = username;
        shipmentCapturePaymentId = capturePaymentId;
    }

    private static boolean validShipmentActor(Long actorId, String username) {
        return actorId != null && actorId > 0 && username != null && !username.isBlank() && username.length() <= 100;
    }

    public LocalDateTime getShippingPreparationStartedAt() {
        return shippingPreparationStartedAt;
    }

    public Long getShippingPreparationStartedByAccountId() {
        return shippingPreparationStartedByAccountId;
    }

    public String getShippingPreparationStartedByUsername() {
        return shippingPreparationStartedByUsername;
    }

    public Long getShippedByAccountId() {
        return shippedByAccountId;
    }

    public String getShippedByUsername() {
        return shippedByUsername;
    }

    @Column(name = "active_payment_id")
    private Long activePaymentId;

    public Long getActivePaymentId() { return activePaymentId; }

    /** Reference assignment only; caller must hold the Order lock and validate eligibility. */
    public void bindActivePayment(Payment payment) {
        if (payment == null || payment.getId() == null || payment.getOrder() == null
                || !java.util.Objects.equals(id, payment.getOrder().getId()))
            throw new IllegalStateException("Payment does not belong to this order");
        if (status != OrderStatus.ORDERED || shipmentCapturePaymentId != null
                || (activePaymentId != null && !activePaymentId.equals(payment.getId())))
            throw new IllegalStateException("Active payment cannot be replaced");
        activePaymentId = payment.getId();
    }

    public void clearActivePaymentAfterTermination(Long paymentId) {
        if (paymentId == null || (activePaymentId != null && !activePaymentId.equals(paymentId)))
            throw new IllegalStateException("Active payment mismatch");
        activePaymentId = null;
    }

    public Long getShipmentCapturePaymentId() {
        return shipmentCapturePaymentId;
    }

    public LocalDateTime getCancelledAt() {
        return cancelledAt;
    }

    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);
    }

    public void removeItem(OrderItem item) {
        items.remove(item);
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public void addCharge(OrderCharge charge) {
        charges.add(charge);
        charge.setOrder(this);
    }

    public void replaceCharges(List<OrderCharge> newCharges) {
        charges.clear();

        for (OrderCharge charge : newCharges) {
            addCharge(charge);
        }
    }

    public List<OrderCharge> getCharges() {
        return Collections.unmodifiableList(charges);
    }

    public void setShippingAddress(
            String shippingName,
            String shippingPostalCode,
            String shippingPrefecture,
            String shippingCity,
            String shippingAddressLine,
            String shippingPhone) {

        this.shippingName = shippingName;
        this.shippingPostalCode = shippingPostalCode;
        this.shippingPrefecture = shippingPrefecture;
        this.shippingCity = shippingCity;
        this.shippingAddressLine = shippingAddressLine;
        this.shippingPhone = shippingPhone;
    }

    public String getShippingName() {
        return shippingName;
    }

    public String getShippingPostalCode() {
        return shippingPostalCode;
    }

    public String getShippingPrefecture() {
        return shippingPrefecture;
    }

    public String getShippingCity() {
        return shippingCity;
    }

    public String getShippingAddressLine() {
        return shippingAddressLine;
    }

    public String getShippingPhone() {
        return shippingPhone;
    }

    public void markAsPaid() {

        if (!canMarkAsPaid()) {
            throw new InvalidOrderStatusException(
                    "注文受付中の注文だけを"
                            + "支払済みに変更できます。");
        }

        status = OrderStatus.PAID;
        paidAt = LocalDateTime.now();
    }

    public void markAsShipped() {

        if (!canMarkAsShipped()) {
            throw new InvalidOrderStatusException(
                    "支払済みの注文だけを"
                            + "発送済みに変更できます。");
        }

        status = OrderStatus.SHIPPED;
        shippedAt = LocalDateTime.now();
    }

    public void cancel(LocalDateTime cancelledAt) {

        if (!canCancel()) {
            throw new InvalidOrderStatusException(
                    "注文受付中の注文だけを"
                            + "キャンセルできます。");
        }

        status = OrderStatus.CANCELLED;
        this.cancelledAt = cancelledAt;
    }

    public boolean canCancel() {
        return status == OrderStatus.ORDERED;
    }

    public boolean isWithinModificationPeriod(LocalDateTime now) {
        return now.isBefore(changeDeadlineAt);
    }

    public boolean canCancelByUser(LocalDateTime now) {
        return canCancel()
                && isWithinModificationPeriod(now);
    }

    public boolean canChangeShippingAddress(LocalDateTime now) {
        return (status == OrderStatus.ORDERED || status == OrderStatus.PAID)
                && isWithinModificationPeriod(now);
    }

    public boolean canChangeItemsByUser(LocalDateTime now) {
        return status == OrderStatus.ORDERED
                && isWithinModificationPeriod(now);
    }

    public boolean canMarkAsPaid() {
        return status == OrderStatus.ORDERED;
    }

    public boolean canMarkAsShipped() {
        return status == OrderStatus.PAID;
    }

    public void markAsShippedAfterPaymentCapture(
            LocalDateTime shippedAt) {

        if (status != OrderStatus.ORDERED) {
            throw new InvalidOrderStatusException(
                    "注文受付中の注文だけを発送済みに変更できます。");
        }

        if (shippedAt == null) {
            throw new IllegalArgumentException(
                    "発送日時は必須です。");
        }

        this.status = OrderStatus.SHIPPED;
        this.paidAt = shippedAt;
        this.shippedAt = shippedAt;
    }

    public AdminAccount getAssignedAdminAccount() {
        return assignedAdminAccount;
    }

    public void changeAssignedAdminAccount(AdminAccount assignedAdminAccount) {
        this.assignedAdminAccount = assignedAdminAccount;
    }

}
