package com.example.ecsite.service.payment;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.entity.Payment;
import com.example.ecsite.entity.PaymentMethod;
import com.example.ecsite.entity.PaymentProvider;
import com.example.ecsite.entity.PaymentStatus;
import com.example.ecsite.entity.PaymentTransaction;
import com.example.ecsite.entity.PaymentTransactionPurpose;
import com.example.ecsite.entity.PaymentTransactionInitiator;
import com.example.ecsite.entity.PaymentTransactionStatus;
import com.example.ecsite.entity.PaymentTransactionType;
import com.example.ecsite.payment.AuthorizationResult;
import com.example.ecsite.payment.AuthorizationResultStatus;
import com.example.ecsite.payment.CancellationResult;
import com.example.ecsite.payment.CancellationResultStatus;
import com.example.ecsite.payment.CaptureResult;
import com.example.ecsite.service.OrderModificationGuard;
import com.example.ecsite.repository.PaymentRepository;
import com.example.ecsite.repository.PaymentTransactionRepository;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final PaymentConsistencyEvaluator paymentConsistencyEvaluator;
    private final Clock clock;
    private final CaptureShipmentOperations captureOperations;
    private final OrderModificationGuard orderModificationGuard;
    private final OrderPaymentGuard orderPaymentGuard;

    @Autowired
    public PaymentService(
            PaymentRepository paymentRepository,
            PaymentTransactionRepository paymentTransactionRepository,
            PaymentConsistencyEvaluator paymentConsistencyEvaluator,
            OrderModificationGuard orderModificationGuard,
            CaptureShipmentOperations captureOperations, OrderPaymentGuard orderPaymentGuard) {

        this(
                paymentRepository,
                paymentTransactionRepository,
                paymentConsistencyEvaluator,
                orderModificationGuard,
                captureOperations,
                orderPaymentGuard,
                Clock.system(ZoneId.of("Asia/Tokyo")));
    }

    PaymentService(
            PaymentRepository paymentRepository,
            PaymentTransactionRepository paymentTransactionRepository,
            PaymentConsistencyEvaluator paymentConsistencyEvaluator,
            OrderModificationGuard orderModificationGuard,
            CaptureShipmentOperations captureOperations,
            OrderPaymentGuard orderPaymentGuard,
            Clock clock) {

        this.paymentRepository = paymentRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.paymentConsistencyEvaluator = paymentConsistencyEvaluator;
        this.clock = clock;
        this.captureOperations = captureOperations;
        this.orderModificationGuard = orderModificationGuard;
        this.orderPaymentGuard = orderPaymentGuard;
    }

    /**
     * Validation only: the caller must already hold the Order write lock and call
     * through the Spring proxy. Payment locks are acquired in ascending ID order
     * and remain held by the caller's transaction. No payment operation is started.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void validateCardPaymentForShippingPreparation(Order order) {
        if (order == null || order.getId() == null) {
            throw new IllegalArgumentException("永続化された注文は必須です。");
        }
        if (order.getStatus() != OrderStatus.ORDERED) {
            throw new IllegalStateException("注文受付中の注文だけをカード決済の発送準備対象にできます。");
        }

        List<Payment> payments = paymentRepository.findByOrderIdForUpdate(order.getId());
        for (Payment payment : payments) {
            if (payment.getOrder() == null
                    || !Objects.equals(payment.getOrder().getId(), order.getId())) {
                throw new IllegalStateException("決済情報と注文が一致しません。");
            }
            // Includes REFUND, and includes histories on FAILED/CANCELLED payments.
            if (paymentTransactionRepository.findByPaymentIdOrderByCreatedAtAscIdAsc(payment.getId())
                    .stream().anyMatch(t -> t.getStatus() == PaymentTransactionStatus.PENDING)) {
                throw new IllegalStateException("決済処理中のため、発送準備を開始できません。");
            }
        }

        validateSingleActivePayment(payments);
        Payment payment = payments.stream()
                .filter(p -> p.getStatus() != PaymentStatus.FAILED && p.getStatus() != PaymentStatus.CANCELLED)
                .findFirst().orElseThrow();
        if (!isAuthorizedCardPayment(payment)) {
            throw new IllegalStateException("発送準備可能なカード与信ではありません。");
        }
        if (payment.getProviderPaymentId() == null || payment.getProviderPaymentId().isBlank()) {
            throw new IllegalStateException("決済プロバイダーIDが設定されていません。");
        }
        // Preserve the existing decreased-amount policy, including revision changes.
        validateCaptureConsistency(order, payment);
    }

    @Transactional
    public PaymentAuthorizationStart startAuthorization(Order order) {
        orderPaymentGuard.requireNoOrderChange(order);

        List<Payment> payments = paymentRepository
                .findByOrderIdForUpdate(
                        order.getId());
        validateInactivePayments(payments);

        List<Payment> activePayments = payments.stream()
                .filter(payment -> payment.getStatus() != PaymentStatus.FAILED
                        && payment.getStatus() != PaymentStatus.CANCELLED)
                .toList();

        if (activePayments.size() > 1) {
            throw new IllegalStateException(
                    "複数の処理中決済が存在するため、与信を開始できません。");
        }

        if (activePayments.size() == 1) {

            Payment payment = activePayments.getFirst();
            if (payment.getProvider() != PaymentProvider.PAYJP
                    || payment.getPaymentMethod() != PaymentMethod.CARD
                    || (payment.getStatus() != PaymentStatus.PENDING
                        && payment.getStatus() != PaymentStatus.REQUIRES_ACTION)) {
                throw new IllegalStateException("既存の有効な決済があるため、新しい与信を開始できません。");
            }
            validatePendingOperations(payment, PaymentTransactionType.AUTHORIZE);

            PaymentTransaction transaction = paymentTransactionRepository
                    .findByPaymentIdAndTransactionTypeAndStatus(
                            payment.getId(),
                            PaymentTransactionType.AUTHORIZE,
                            PaymentTransactionStatus.PENDING)
                    .orElseThrow(() -> new IllegalStateException(
                            "処理中決済に対応する与信操作が見つかりません。"));

            validatePendingSnapshot(order, payment, transaction, true);
            return new PaymentAuthorizationStart(
                    payment.getId(),
                    transaction.getId(),
                    payment.getAmount(),
                    transaction.getIdempotencyKey(),
                    PaymentStartDisposition.REUSED, PaymentOperationSnapshot.from(transaction));
        }

        LocalDateTime now = LocalDateTime.now(clock);

        Payment payment = new Payment(
                order,
                PaymentProvider.PAYJP,
                PaymentMethod.CARD,
                order.getTotalAmount(),
                now);

        paymentRepository.save(payment);
        order.bindActivePayment(payment);

        PaymentTransaction transaction = new PaymentTransaction(
                payment,
                PaymentTransactionType.AUTHORIZE,
                payment.getAmount(),
                order.getContentRevision(),
                UUID.randomUUID().toString(),
                now, PaymentTransactionPurpose.INITIAL_ORDER_AUTHORIZATION);

        paymentTransactionRepository.save(transaction);

        return new PaymentAuthorizationStart(
                payment.getId(),
                transaction.getId(),
                payment.getAmount(),
                transaction.getIdempotencyKey(),
                PaymentStartDisposition.NEW, PaymentOperationSnapshot.from(transaction));
    }

    @Transactional(readOnly = true)
    public Optional<String> findProviderPaymentId(Long paymentId) {

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "決済情報が見つかりません。"));

        return Optional.ofNullable(payment.getProviderPaymentId())
                .filter(providerPaymentId -> !providerPaymentId.isBlank());
    }

    @Transactional(readOnly = true)
    public boolean canResumeAuthorization(Order order) {
        // Resume only by GET; a providerless persisted PENDING cannot authorize another POST.
        return findRecoverableAuthorizationPayment(order).isPresent();
    }

    @Transactional(readOnly = true)
    public boolean requiresAuthorizationConfirmation(Order order) {
        return paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(order.getId()).stream()
                .anyMatch(p -> p.getProvider() == PaymentProvider.PAYJP && p.getPaymentMethod() == PaymentMethod.CARD
                        && (p.getStatus() == PaymentStatus.PENDING || p.getStatus() == PaymentStatus.REQUIRES_ACTION)
                        && (p.getProviderPaymentId() == null || p.getProviderPaymentId().isBlank()));
    }

    @Transactional(readOnly = true)
    public boolean canRecoverAuthorization(Order order) {
        return findRecoverableAuthorizationPayment(order).isPresent();
    }

    @Transactional(readOnly = true)
    public Long getRecoverableAuthorizationPaymentId(Order order) {

        return findRecoverableAuthorizationPayment(order)
                .orElseThrow(() -> new IllegalStateException(
                        "復旧可能なカード決済が見つかりません。"))
                .getId();
    }

    private Optional<Payment> findRecoverableAuthorizationPayment(Order order) {

        List<Payment> recoverablePayments = paymentRepository
                .findByOrderIdOrderByCreatedAtAscIdAsc(order.getId())
                .stream()
                .filter(payment -> payment.getProvider() == PaymentProvider.PAYJP)
                .filter(payment -> payment.getPaymentMethod() == PaymentMethod.CARD)
                .filter(payment -> payment.getStatus() == PaymentStatus.PENDING
                        || payment.getStatus() == PaymentStatus.REQUIRES_ACTION)
                .toList();

        if (recoverablePayments.size() != 1) {
            return Optional.empty();
        }

        Payment payment = recoverablePayments.getFirst();

        if (payment.getAmount() != order.getTotalAmount()) {
            return Optional.empty();
        }

        if (payment.getProviderPaymentId() == null
                || payment.getProviderPaymentId().isBlank()) {
            return Optional.empty();
        }

        Optional<PaymentTransaction> transaction = paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        payment.getId(),
                        PaymentTransactionType.AUTHORIZE,
                        PaymentTransactionStatus.PENDING);

        if (transaction.isEmpty()) {
            return Optional.empty();
        }

        if (transaction.get().getOrderContentRevision() != order.getContentRevision()) {
            return Optional.empty();
        }

        return Optional.of(payment);
    }

    @Transactional
    public PaymentResultApplication setProviderPaymentId(Order order, PaymentOperationSnapshot snapshot,
            String providerPaymentId) {
        if (providerPaymentId == null || providerPaymentId.isBlank())
            throw new IllegalArgumentException("決済プロバイダーIDは必須です。");
        var target = target(order, snapshot, PaymentTransactionType.AUTHORIZE, true);
        if (target == null) return PaymentResultApplication.CONFLICT;
        Payment payment = target.payment();
        if (payment.getProviderPaymentId() != null && !Objects.equals(payment.getProviderPaymentId(), providerPaymentId))
            return PaymentResultApplication.CONFLICT;
        if (target.transaction().getStatus() != PaymentTransactionStatus.PENDING
                || (payment.getStatus() != PaymentStatus.PENDING && payment.getStatus() != PaymentStatus.REQUIRES_ACTION)
                || !matchesOrder(order, snapshot) || order.getStatus() != OrderStatus.ORDERED
                || payment.getAmount() != snapshot.amount() || competingPending(target))
            return PaymentResultApplication.IGNORED_STALE;
        if (!target.currentPurposeMatches()) return PaymentResultApplication.CONFLICT;
        if (Objects.equals(payment.getProviderPaymentId(), providerPaymentId))
            return PaymentResultApplication.ALREADY_APPLIED;
        payment.setProviderPaymentId(providerPaymentId, LocalDateTime.now(clock));
        return PaymentResultApplication.APPLIED;
    }

    @Transactional
    public PaymentResultApplication applyAuthorizationResult(Order order, PaymentOperationSnapshot snapshot,
            AuthorizationResult result) {
        var target = target(order, snapshot, PaymentTransactionType.AUTHORIZE, false);
        if (target == null || (result.providerTransactionId()!=null
                && !Objects.equals(snapshot.providerPaymentId(),result.providerTransactionId())))
            return PaymentResultApplication.CONFLICT;
        Payment payment = target.payment(); PaymentTransaction transaction = target.transaction();
        if (transaction.getStatus() != PaymentTransactionStatus.PENDING) {
            boolean same = (transaction.getStatus() == PaymentTransactionStatus.SUCCEEDED
                    && result.status() == AuthorizationResultStatus.AUTHORIZED && payment.getStatus() == PaymentStatus.AUTHORIZED
                    && order.getStatus() == OrderStatus.ORDERED)
                    || (transaction.getStatus() == PaymentTransactionStatus.FAILED
                    && result.status() == AuthorizationResultStatus.FAILED && payment.getStatus() == PaymentStatus.FAILED
                    && order.getStatus() == OrderStatus.CANCELLED);
            return same && target.currentPurposeMatches() && !competingPending(target) ? PaymentResultApplication.ALREADY_APPLIED : PaymentResultApplication.IGNORED_STALE;
        }
        if (!target.currentPurposeMatches()) return PaymentResultApplication.CONFLICT;
        if (!matchesOrder(order, snapshot) || payment.getAmount() != snapshot.amount())
            return PaymentResultApplication.CONFLICT;
        if (order.getStatus() != OrderStatus.ORDERED
                || (payment.getStatus() != PaymentStatus.PENDING && payment.getStatus() != PaymentStatus.REQUIRES_ACTION)
                || competingPending(target)) return PaymentResultApplication.IGNORED_STALE;
        LocalDateTime now = LocalDateTime.now(clock);
        switch (result.status()) {
            case REQUIRES_ACTION -> {
                if (payment.getStatus() == PaymentStatus.REQUIRES_ACTION) return PaymentResultApplication.ALREADY_APPLIED;
                payment.markRequiresAction(now);
            }
            case AUTHORIZED -> { payment.markAuthorized(now); transaction.markSucceeded(result.providerTransactionId(), now); }
            case FAILED -> { order.clearActivePaymentAfterTermination(payment.getId()); payment.markFailed(now); transaction.markFailed(result.providerTransactionId(), result.failureCode(), result.failureMessage(), now); }
            case PENDING, REQUIRES_PAYMENT_METHOD, REQUIRES_CONFIRMATION -> { }
        }
        return PaymentResultApplication.APPLIED;
    }

    @Transactional(readOnly = true)
    public String getProviderPaymentId(Long paymentId) {

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "決済情報が見つかりません。"));

        String providerPaymentId = payment.getProviderPaymentId();

        if (providerPaymentId == null
                || providerPaymentId.isBlank()) {
            throw new IllegalStateException(
                    "決済プロバイダーIDが設定されていません。");
        }

        return providerPaymentId;
    }

    @Transactional(readOnly = true)
    public void validatePaymentBelongsToOrder(
            Long paymentId,
            Long orderId) {

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "決済情報が見つかりません。"));

        if (!payment.getOrder().getId().equals(orderId)) {
            throw new IllegalArgumentException(
                    "決済情報と注文が一致しません。");
        }
    }

    /** Direct callers use the same locked, authenticated shipment operation as the HTTP flow. */
    @Transactional
    public PaymentCaptureStart startCapture(Order order, PaymentTransactionInitiator initiator) {
        if (order == null || initiator == null || initiator.type() != com.example.ecsite.entity.PaymentTransactionInitiatorType.ADMIN)
            throw new IllegalArgumentException("管理者による発送操作が必要です。");
        return captureOperations.start(order.getId(), initiator.id(), initiator.username(), initiator.internalNote());
    }

    @Transactional
    public PaymentResultApplication applyCaptureResult(Order order, PaymentOperationSnapshot snapshot, CaptureResult result) {
        if (order == null || snapshot == null || !Objects.equals(order.getId(), snapshot.orderId()))
            return PaymentResultApplication.CONFLICT;
        return captureOperations.apply(snapshot, result);
    }

    @Transactional
    public PaymentCancellationStart startCancellation(
            Order order,
            PaymentTransactionInitiator initiator) {

        orderPaymentGuard.requireNoOrderChange(order);
        List<Payment> payments = paymentRepository.findByOrderIdForUpdate(
                order.getId());
        validateInactivePayments(payments);

        validateSingleActivePayment(payments);
        Payment payment = payments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.AUTHORIZED)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "取消可能な与信済み決済が見つかりません。"));
        if (!isAuthorizedCardPayment(payment)) {
            throw new IllegalStateException("取消可能なカード与信ではありません。");
        }
        validatePendingOperations(payment, PaymentTransactionType.CANCEL);

        String providerPaymentId = payment.getProviderPaymentId();

        if (providerPaymentId == null
                || providerPaymentId.isBlank()) {

            throw new IllegalStateException(
                    "決済プロバイダーIDが設定されていません。");
        }

        boolean capturePending = paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        payment.getId(),
                        PaymentTransactionType.CAPTURE,
                        PaymentTransactionStatus.PENDING)
                .isPresent();

        if (capturePending) {
            throw new IllegalStateException(
                    "売上確定処理中のため、決済を取り消せません。");
        }

        var existingTransaction = paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        payment.getId(),
                        PaymentTransactionType.CANCEL,
                        PaymentTransactionStatus.PENDING);

        if (existingTransaction.isPresent()) {

            PaymentTransaction transaction = existingTransaction.get();
            validatePendingSnapshot(order, payment, transaction, false);

            return new PaymentCancellationStart(
                    payment.getId(),
                    transaction.getId(),
                    providerPaymentId,
                    transaction.getIdempotencyKey(),
                    PaymentStartDisposition.REUSED, PaymentOperationSnapshot.from(transaction));
        }

        // Existing operations above keep their original actor, snapshot and key.
        // Only a new cancellation is subject to the current modification window.
        LocalDateTime now = orderModificationGuard.requireAllowed(order);

        PaymentTransaction transaction = new PaymentTransaction(
                payment,
                PaymentTransactionType.CANCEL,
                payment.getAmount(),
                order.getContentRevision(),
                UUID.randomUUID().toString(),
                now,
                initiator, PaymentTransactionPurpose.ORDER_CANCELLATION);

        paymentTransactionRepository.save(transaction);

        return new PaymentCancellationStart(
                payment.getId(),
                transaction.getId(),
                providerPaymentId,
                transaction.getIdempotencyKey(),
                PaymentStartDisposition.NEW, PaymentOperationSnapshot.from(transaction));
    }

    @Transactional
    public PaymentResultApplication applyCancellationResult(Order order, PaymentOperationSnapshot snapshot,
            CancellationResult result) {
        var target = target(order, snapshot, PaymentTransactionType.CANCEL, false);
        if (target == null || !Objects.equals(snapshot.providerPaymentId(), result.providerTransactionId()))
            return PaymentResultApplication.CONFLICT;
        Payment payment = target.payment(); PaymentTransaction transaction = target.transaction();
        if (transaction.getStatus() != PaymentTransactionStatus.PENDING) {
            boolean same = transaction.getStatus() == PaymentTransactionStatus.SUCCEEDED
                    && result.status() == CancellationResultStatus.CANCELLED && payment.getStatus() == PaymentStatus.CANCELLED
                    && order.getStatus() == OrderStatus.CANCELLED;
            return same && target.currentPurposeMatches() && !competingPending(target) ? PaymentResultApplication.ALREADY_APPLIED : PaymentResultApplication.IGNORED_STALE;
        }
        if (!target.currentPurposeMatches()) return PaymentResultApplication.CONFLICT;
        if (transaction.getOrderContentRevision() != order.getContentRevision()
                || snapshot.amount() != payment.getAmount()) return PaymentResultApplication.CONFLICT;
        if (payment.getStatus() != PaymentStatus.AUTHORIZED || order.getStatus() != OrderStatus.ORDERED
                || competingPending(target)) return PaymentResultApplication.IGNORED_STALE;
        if (result.status() == CancellationResultStatus.CANCELLED) {
            LocalDateTime now = LocalDateTime.now(clock);
            order.clearActivePaymentAfterTermination(payment.getId());
            payment.markCancelled(now); transaction.markSucceeded(result.providerTransactionId(), now);
        }
        return PaymentResultApplication.APPLIED;
    }

    // Caller must hold the Order lock before this first Payment read.
    private ResultTarget target(Order order, PaymentOperationSnapshot snapshot, PaymentTransactionType type,
            boolean assigningProviderId) {
        if (snapshot == null || snapshot.transactionId() == null || snapshot.paymentId() == null
                || !Objects.equals(order.getId(), snapshot.orderId()) || snapshot.type() != type
                || snapshot.idempotencyKey() == null || snapshot.idempotencyKey().isBlank()) return null;
        List<Payment> lockedPayments = paymentRepository.findByOrderIdForUpdate(order.getId());
        Payment payment = lockedPayments.stream().filter(p -> Objects.equals(p.getId(), snapshot.paymentId())).findFirst().orElse(null);
        PaymentTransaction transaction = paymentTransactionRepository.findById(snapshot.transactionId()).orElse(null);
        if (payment == null || transaction == null
                || !Objects.equals(payment.getOrder().getId(), order.getId())
                || !Objects.equals(transaction.getPayment().getId(), payment.getId())
                || transaction.getTransactionType() != type
                || transaction.getPurpose() != snapshot.purpose()
                || transaction.getOrderChangeOperationId() != null
                || !Objects.equals(transaction.getIdempotencyKey(), snapshot.idempotencyKey())
                || transaction.getAmount() != snapshot.amount()
                || transaction.getOrderContentRevision() != snapshot.orderContentRevision()
                || payment.getProvider() != PaymentProvider.PAYJP || snapshot.provider() != PaymentProvider.PAYJP
                || payment.getPaymentMethod() != PaymentMethod.CARD
                || transaction.getInitiatorType() != snapshot.initiatorType()
                || !Objects.equals(transaction.getInitiatorId(), snapshot.initiatorId())
                || !Objects.equals(transaction.getInitiatorUsername(), snapshot.initiatorUsername())
                || !Objects.equals(transaction.getInternalNote(), snapshot.internalNote())) return null;
        if (!assigningProviderId && (snapshot.providerPaymentId() == null || snapshot.providerPaymentId().isBlank()
                || !Objects.equals(payment.getProviderPaymentId(), snapshot.providerPaymentId()))) return null;
        if (assigningProviderId && snapshot.providerPaymentId() != null
                && !Objects.equals(payment.getProviderPaymentId(), snapshot.providerPaymentId())) return null;
        List<PaymentTransaction> history = lockedPayments.stream().flatMap(p -> paymentTransactionRepository
                .findByPaymentIdOrderByCreatedAtAscIdAsc(p.getId()).stream()).toList();
        // Historical identity may justify ignoring a stale response, never applying it.
        // Current-state eligibility is retained separately and required before every mutation.
        var resolver = new PaymentPurposeResolver();
        boolean currentPurposeMatches = resolver.matches(order, payment, transaction, snapshot, lockedPayments, history,
                orderPaymentGuard.completedOrderChanges(order), orderPaymentGuard.abortedOrderChanges(order));
        if (!currentPurposeMatches && !resolver.matchesResultIdentity(order, payment, transaction, snapshot, history)) return null;
        return new ResultTarget(payment, transaction, currentPurposeMatches);
    }

    private boolean competingPending(ResultTarget target) {
        if (paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(target.payment().getOrder().getId()).stream()
                .anyMatch(p -> !Objects.equals(p.getId(), target.payment().getId())
                        && p.getStatus() != PaymentStatus.FAILED && p.getStatus() != PaymentStatus.CANCELLED)) return true;
        return paymentTransactionRepository.findByPaymentIdOrderByCreatedAtAscIdAsc(target.payment().getId()).stream()
                .anyMatch(t -> t.getStatus() == PaymentTransactionStatus.PENDING
                        && !Objects.equals(t.getId(), target.transaction().getId()));
    }

    private boolean matchesOrder(Order order, PaymentOperationSnapshot snapshot) {
        return order.getTotalAmount() == snapshot.amount() && order.getContentRevision() == snapshot.orderContentRevision();
    }

    private record ResultTarget(Payment payment, PaymentTransaction transaction, boolean currentPurposeMatches) { }

    @Transactional(readOnly = true)
    public PaymentTransactionInitiator getCancellationInitiator(Long transactionId, Long paymentId) {
        PaymentTransaction transaction = paymentTransactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalStateException("取消操作が見つかりません。"));
        if (transaction.getTransactionType() != PaymentTransactionType.CANCEL
                || !transaction.getPayment().getId().equals(paymentId)) {
            throw new IllegalStateException("取消操作と決済が一致しません。");
        }
        return new PaymentTransactionInitiator(transaction.getInitiatorType(), transaction.getInitiatorId(),
                transaction.getInitiatorUsername(), transaction.getInternalNote());
    }

    @Transactional(readOnly = true)
    public boolean canCaptureForShipment(Long orderId) {

        List<Payment> payments = paymentRepository
                .findByOrderIdOrderByCreatedAtAscIdAsc(orderId);

        List<Payment> authorizedPayments = payments.stream()
                .filter(payment -> payment.getStatus() == PaymentStatus.AUTHORIZED)
                .toList();

        if (authorizedPayments.size() != 1) {
            return false;
        }

        Payment payment = authorizedPayments.getFirst();

        if (payment.getProvider() != PaymentProvider.PAYJP
                || payment.getPaymentMethod() != PaymentMethod.CARD) {
            return false;
        }

        Order order = payment.getOrder();

        return isCaptureConsistent(order, payment);
    }

    @Transactional(readOnly = true)
    public boolean hasAuthorizedCardPayment(Long orderId) {

        List<Payment> payments = paymentRepository
                .findByOrderIdOrderByCreatedAtAscIdAsc(orderId);

        return payments.stream()
                .anyMatch(this::isAuthorizedCardPayment);
    }

    @Transactional(readOnly = true)
    public boolean requiresAuthorizationCancellation(Long orderId) {

        List<Payment> payments = paymentRepository
                .findByOrderIdOrderByCreatedAtAscIdAsc(orderId);

        return payments.stream()
                .anyMatch(this::isAuthorizedCardPayment);
    }

    private void validateInactivePayments(List<Payment> payments) {
        for (Payment payment : payments) {
            if ((payment.getStatus() == PaymentStatus.FAILED || payment.getStatus() == PaymentStatus.CANCELLED)
                    && paymentTransactionRepository.findByPaymentIdOrderByCreatedAtAscIdAsc(payment.getId())
                        .stream().anyMatch(t -> t.getStatus() == PaymentTransactionStatus.PENDING)) {
                throw new IllegalStateException("終了済み決済に処理中の操作が残っているため、決済操作を開始できません。");
            }
        }
    }

    private void validateSingleActivePayment(List<Payment> payments) {
        long active = payments.stream()
                .filter(p -> p.getStatus() != PaymentStatus.FAILED && p.getStatus() != PaymentStatus.CANCELLED)
                .count();
        boolean referenceMismatch = payments.stream().anyMatch(p -> p.getStatus() != PaymentStatus.FAILED
                && p.getStatus() != PaymentStatus.CANCELLED && p.getOrder().getActivePaymentId() != null
                && !Objects.equals(p.getOrder().getActivePaymentId(), p.getId()));
        if (active != 1 || referenceMismatch) {
            throw new IllegalStateException("有効な決済が一意でないため、決済操作を開始できません。");
        }
    }

    private void validatePendingOperations(Payment payment, PaymentTransactionType type) {
        List<PaymentTransaction> pending = paymentTransactionRepository
                .findByPaymentIdOrderByCreatedAtAscIdAsc(payment.getId()).stream()
                .filter(t -> t.getStatus() == PaymentTransactionStatus.PENDING).toList();
        if (pending.size() > 1 || pending.stream().anyMatch(t -> t.getTransactionType() != type)) {
            throw new IllegalStateException("競合する処理中の決済操作が存在します。");
        }
    }

    private void validatePendingSnapshot(Order order, Payment payment,
            PaymentTransaction transaction, boolean matchOrderAmount) {
        int expectedAmount = matchOrderAmount ? order.getTotalAmount() : payment.getAmount();
        if (transaction.getPurpose() != null && !new PaymentPurposePolicy().isUsableInStage1A(
                transaction.getTransactionType().name(), transaction.getPurpose()))
            throw new IllegalStateException("Unsupported pending payment purpose");
        if (transaction.getAmount() != expectedAmount
                || transaction.getOrderContentRevision() != order.getContentRevision()
                || (transaction.getTransactionType() == PaymentTransactionType.AUTHORIZE
                    && payment.getAmount() != expectedAmount)) {
            throw new IllegalStateException("処理中の決済操作と現在の注文内容が一致しません。");
        }
    }

    private boolean isAuthorizedCardPayment(Payment payment) {
        return payment.getProvider() == PaymentProvider.PAYJP
                && payment.getPaymentMethod() == PaymentMethod.CARD
                && payment.getStatus() == PaymentStatus.AUTHORIZED;
    }

    private void validateCaptureConsistency(
            Order order,
            Payment payment) {

        PaymentConsistency consistency = findCaptureConsistency(order, payment)
                .orElseThrow(() -> new IllegalStateException(
                        "成功した与信情報が見つからないため、売上確定できません。"));

        if (!consistency.canCapture()) {
            throw new IllegalStateException(
                    "注文内容とカード与信が一致しないため、売上確定できません。");
        }
    }

    private boolean isCaptureConsistent(
            Order order,
            Payment payment) {

        return findCaptureConsistency(order, payment)
                .map(PaymentConsistency::canCapture)
                .orElse(false);
    }

    private Optional<PaymentConsistency> findCaptureConsistency(
            Order order,
            Payment payment) {

        return paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        payment.getId(),
                        PaymentTransactionType.AUTHORIZE,
                        PaymentTransactionStatus.SUCCEEDED)
                .filter(transaction -> transaction.getPurpose() == null
                        || transaction.getPurpose() == PaymentTransactionPurpose.INITIAL_ORDER_AUTHORIZATION
                        || (transaction.getPurpose() == PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION
                            && Objects.equals(order.getActivePaymentId(), payment.getId())
                            && transaction.getOrderChangeOperationId()!=null
                            && orderPaymentGuard.completedOrderChanges(order).contains(transaction.getOrderChangeOperationId())))
                .map(transaction -> paymentConsistencyEvaluator.evaluate(
                        order,
                        payment,
                        transaction));
    }

}
