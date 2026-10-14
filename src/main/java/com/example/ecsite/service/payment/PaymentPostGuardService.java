package com.example.ecsite.service.payment;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.ecsite.config.PayJpSafetyProperties;
import com.example.ecsite.entity.PaymentTransactionStatus;
import com.example.ecsite.repository.PaymentTransactionRepository;
import static com.example.ecsite.service.payment.PaymentSafetyStopException.Reason.*;

/** Short read transaction ends before POST. This is not a persistent execution lease. */
@Service
public class PaymentPostGuardService {
    // Existing timestamp columns are local JST, independent of the Clock zone.
    private static final ZoneId PERSISTED_ZONE = ZoneId.of("Asia/Tokyo");
    private final PaymentTransactionRepository transactions;
    private final Clock clock;
    private final PayJpSafetyProperties properties;
    public PaymentPostGuardService(PaymentTransactionRepository transactions, Clock clock, PayJpSafetyProperties properties) {
        this.transactions = transactions;
        this.clock = clock;
        this.properties = properties;
        if (!properties.isValid()) throw new IllegalArgumentException("Invalid PAY.JP safety configuration");
    }
    @Transactional(readOnly = true)
    public void beforeNewPost(PaymentOperationSnapshot snapshot) {
        var transaction = transactions.findById(snapshot.transactionId()).orElse(null);
        if (transaction == null || !PaymentOperationSnapshot.from(transaction).equals(snapshot)
                || transaction.getStatus() != PaymentTransactionStatus.PENDING
                || snapshot.idempotencyKey() == null || snapshot.idempotencyKey().isBlank()) {
            throw new PaymentSafetyStopException(INVALID_OPERATION_TIME, snapshot.orderId());
        }
        var created = transaction.getCreatedAt();
        var now = LocalDateTime.ofInstant(clock.instant(), PERSISTED_ZONE);
        if (created == null || created.isAfter(now))
            throw new PaymentSafetyStopException(INVALID_OPERATION_TIME, snapshot.orderId());
        if (Duration.between(created, now).compareTo(properties.postMaxAge()) >= 0)
            throw new PaymentSafetyStopException(POST_WINDOW_EXPIRED, snapshot.orderId());
    }

    /** Operator identification only; never changes state or retries POST. */
    @Transactional(readOnly = true)
    public void identifyStoppedPending(Long transactionId) {
        transactions.findById(transactionId).ifPresent(t -> {
            var p = t.getPayment();
            var created = t.getCreatedAt();
            if (t.getStatus() == PaymentTransactionStatus.PENDING
                    && (p.getProviderPaymentId() == null || p.getProviderPaymentId().isBlank())
                    && (created == null || !created.isAfter(LocalDateTime.ofInstant(clock.instant(), PERSISTED_ZONE).minus(properties.postMaxAge())))) {
                LoggerFactory.getLogger(PaymentPostGuardService.class).warn(
                        "Payment requires operator confirmation: reason=LONG_PENDING_WITHOUT_PROVIDER_ID, orderId={}, paymentId={}, transactionId={}, operation={}",
                        p.getOrder().getId(), p.getId(), t.getId(), t.getTransactionType());
            }
        });
    }
}
