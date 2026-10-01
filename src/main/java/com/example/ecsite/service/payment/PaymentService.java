package com.example.ecsite.service.payment;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
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

}
