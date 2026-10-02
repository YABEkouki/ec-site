package com.example.ecsite.service.payment;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.Payment;
import com.example.ecsite.entity.PaymentMethod;
import com.example.ecsite.entity.PaymentProvider;
import com.example.ecsite.entity.PaymentStatus;
import com.example.ecsite.entity.PaymentTransaction;
import com.example.ecsite.entity.PaymentTransactionStatus;
import com.example.ecsite.entity.PaymentTransactionType;
import com.example.ecsite.payment.AuthorizationResult;
import com.example.ecsite.payment.AuthorizationResultStatus;
import com.example.ecsite.payment.CancellationResult;
import com.example.ecsite.payment.CancellationResultStatus;
import com.example.ecsite.payment.CaptureResult;
import com.example.ecsite.payment.CaptureResultStatus;
import com.example.ecsite.repository.PaymentRepository;
import com.example.ecsite.repository.PaymentTransactionRepository;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final Clock clock;

    @Autowired
    public PaymentService(
            PaymentRepository paymentRepository,
            PaymentTransactionRepository paymentTransactionRepository) {

        this(
                paymentRepository,
                paymentTransactionRepository,
                Clock.system(ZoneId.of("Asia/Tokyo")));
    }

    PaymentService(
            PaymentRepository paymentRepository,
            PaymentTransactionRepository paymentTransactionRepository,
            Clock clock) {

        this.paymentRepository = paymentRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.clock = clock;
    }

    @Transactional
    public PaymentAuthorizationStart startAuthorization(Order order) {

        LocalDateTime now = LocalDateTime.now(clock);

        Payment payment = new Payment(
                order,
                PaymentProvider.PAYJP,
                PaymentMethod.CARD,
                order.getTotalAmount(),
                now);

        paymentRepository.save(payment);

        PaymentTransaction transaction = new PaymentTransaction(
                payment,
                PaymentTransactionType.AUTHORIZE,
                payment.getAmount(),
                order.getContentRevision(),
                UUID.randomUUID().toString(),
                now);

        paymentTransactionRepository.save(transaction);

        return new PaymentAuthorizationStart(
                payment.getId(),
                transaction.getId(),
                payment.getAmount(),
                transaction.getIdempotencyKey());
    }

    @Transactional
    public void setProviderPaymentId(
            Long paymentId,
            String providerPaymentId) {

        if (providerPaymentId == null
                || providerPaymentId.isBlank()) {
            throw new IllegalArgumentException(
                    "決済プロバイダーIDは必須です。");
        }

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "決済情報が見つかりません。"));

        payment.setProviderPaymentId(
                providerPaymentId,
                LocalDateTime.now(clock));
    }

    @Transactional
    public void applyAuthorizationResult(
            Long paymentId,
            AuthorizationResult result) {

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "決済情報が見つかりません。"));

        /*
         * 与信がすでに確定済みの場合、
         * 同じAUTHORIZED結果の再通知・再照会は何もしない。
         */
        if (payment.getStatus() == PaymentStatus.AUTHORIZED
                && result.status() == AuthorizationResultStatus.AUTHORIZED) {
            return;
        }

        /*
         * 失敗がすでに確定済みの場合も、
         * 同じFAILED結果なら何もしない。
         */
        if (payment.getStatus() == PaymentStatus.FAILED
                && result.status() == AuthorizationResultStatus.FAILED) {
            return;
        }

        PaymentTransaction transaction = paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        paymentId,
                        PaymentTransactionType.AUTHORIZE,
                        PaymentTransactionStatus.PENDING)
                .orElseThrow(() -> new IllegalStateException(
                        "処理中の与信操作が見つかりません。"));

        LocalDateTime now = LocalDateTime.now(clock);

        switch (result.status()) {

            case PENDING -> {
                // 結果未確定なのでローカル状態は変更しない
            }

            case REQUIRES_ACTION -> {
                if (payment.getStatus() == PaymentStatus.PENDING) {
                    payment.markRequiresAction(now);
                }
            }

            case AUTHORIZED -> {
                payment.markAuthorized(now);

                transaction.markSucceeded(
                        result.providerTransactionId(),
                        now);
            }

            case FAILED -> {
                payment.markFailed(now);

                transaction.markFailed(
                        result.providerTransactionId(),
                        result.failureCode(),
                        result.failureMessage(),
                        now);
            }
        }
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

    @Transactional
    public PaymentCaptureStart startCapture(Order order) {

        List<Payment> payments = paymentRepository
                .findByOrderIdOrderByCreatedAtAscIdAsc(
                        order.getId());

        List<Payment> authorizedPayments = payments.stream()
                .filter(payment -> payment.getStatus() == PaymentStatus.AUTHORIZED)
                .toList();

        if (authorizedPayments.isEmpty()) {
            throw new IllegalStateException(
                    "売上確定可能な与信済み決済が見つかりません。");
        }

        if (authorizedPayments.size() != 1) {
            throw new IllegalStateException(
                    "複数の与信済み決済が存在するため、発送できません。");
        }

        Payment payment = authorizedPayments.getFirst();

        String providerPaymentId = payment.getProviderPaymentId();

        if (providerPaymentId == null
                || providerPaymentId.isBlank()) {

            throw new IllegalStateException(
                    "決済プロバイダーIDが設定されていません。");
        }

        boolean cancellationPending = paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        payment.getId(),
                        PaymentTransactionType.CANCEL,
                        PaymentTransactionStatus.PENDING)
                .isPresent();

        if (cancellationPending) {
            throw new IllegalStateException(
                    "決済取消処理中のため、売上確定できません。");
        }

        var existingTransaction = paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        payment.getId(),
                        PaymentTransactionType.CAPTURE,
                        PaymentTransactionStatus.PENDING);

        if (existingTransaction.isPresent()) {

            PaymentTransaction transaction = existingTransaction.get();

            return new PaymentCaptureStart(
                    payment.getId(),
                    transaction.getId(),
                    providerPaymentId,
                    transaction.getIdempotencyKey());
        }

        LocalDateTime now = LocalDateTime.now(clock);

        PaymentTransaction transaction = new PaymentTransaction(
                payment,
                PaymentTransactionType.CAPTURE,
                payment.getAmount(),
                order.getContentRevision(),
                UUID.randomUUID().toString(),
                now);

        paymentTransactionRepository.save(transaction);

        return new PaymentCaptureStart(
                payment.getId(),
                transaction.getId(),
                providerPaymentId,
                transaction.getIdempotencyKey());
    }

    @Transactional
    public void applyCaptureResult(
            Long paymentId,
            CaptureResult result) {

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "決済情報が見つかりません。"));

        if (payment.getStatus() == PaymentStatus.CAPTURED
                && result.status() == CaptureResultStatus.CAPTURED) {
            return;
        }

        PaymentTransaction transaction = paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        paymentId,
                        PaymentTransactionType.CAPTURE,
                        PaymentTransactionStatus.PENDING)
                .orElseThrow(() -> new IllegalStateException(
                        "処理中の売上確定操作が見つかりません。"));

        if (result.status() == CaptureResultStatus.PENDING) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);

        switch (result.status()) {

            case CAPTURED -> {
                payment.markCaptured(now);

                transaction.markSucceeded(
                        result.providerTransactionId(),
                        now);
            }

            case FAILED -> transaction.markFailed(
                    result.providerTransactionId(),
                    result.failureCode(),
                    result.failureMessage(),
                    now);

            case PENDING -> {
                // 上でreturnしているため到達しない
            }
        }
    }

    @Transactional
    public PaymentCancellationStart startCancellation(Order order) {

        List<Payment> payments = paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(
                order.getId());

        Payment payment = payments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.AUTHORIZED)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "取消可能な与信済み決済が見つかりません。"));

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

            return new PaymentCancellationStart(
                    payment.getId(),
                    transaction.getId(),
                    providerPaymentId,
                    transaction.getIdempotencyKey());
        }

        LocalDateTime now = LocalDateTime.now(clock);

        PaymentTransaction transaction = new PaymentTransaction(
                payment,
                PaymentTransactionType.CANCEL,
                payment.getAmount(),
                order.getContentRevision(),
                UUID.randomUUID().toString(),
                now);

        paymentTransactionRepository.save(transaction);

        return new PaymentCancellationStart(
                payment.getId(),
                transaction.getId(),
                providerPaymentId,
                transaction.getIdempotencyKey());
    }

    @Transactional
    public void applyCancellationResult(
            Long paymentId,
            CancellationResult result) {

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "決済情報が見つかりません。"));

        if (payment.getStatus() == PaymentStatus.CANCELLED
                && result.status() == CancellationResultStatus.CANCELLED) {
            return;
        }

        PaymentTransaction transaction = paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        paymentId,
                        PaymentTransactionType.CANCEL,
                        PaymentTransactionStatus.PENDING)
                .orElseThrow(() -> new IllegalStateException(
                        "処理中の取消操作が見つかりません。"));

        if (result.status() == CancellationResultStatus.PENDING) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);

        payment.markCancelled(now);

        transaction.markSucceeded(
                result.providerTransactionId(),
                now);
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

        return payment.getProvider() == PaymentProvider.PAYJP
                && payment.getPaymentMethod() == PaymentMethod.CARD;
    }

    @Transactional(readOnly = true)
    public boolean requiresAuthorizationCancellation(Long orderId) {

        List<Payment> payments = paymentRepository
                .findByOrderIdOrderByCreatedAtAscIdAsc(orderId);

        return payments.stream()
                .anyMatch(payment -> payment.getProvider() == PaymentProvider.PAYJP
                        && payment.getPaymentMethod() == PaymentMethod.CARD
                        && payment.getStatus() == PaymentStatus.AUTHORIZED);
    }

}
