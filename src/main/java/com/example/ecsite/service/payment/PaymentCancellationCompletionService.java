package com.example.ecsite.service.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.example.ecsite.payment.CancellationResult;

/** HTTP retries use the operation's saved actor, just like webhook/reconciliation recovery. */
@Service
@Transactional(propagation = Propagation.NEVER)
public class PaymentCancellationCompletionService {
    private final PaymentCancellationResultService userResult;
    private final AdminPaymentCancellationResultService adminResult;

    public PaymentCancellationCompletionService(PaymentCancellationResultService userResult, AdminPaymentCancellationResultService adminResult) {
        this.userResult = userResult;
        this.adminResult = adminResult;
    }

    public void apply(Long orderId, PaymentCancellationStart start, CancellationResult result) {
        PaymentOperationSnapshot snapshot = start.snapshot();
        PaymentResultApplication outcome = switch (snapshot.initiatorType()) {
            case USER -> userResult.apply(snapshot, result);
            case ADMIN -> adminResult.apply(snapshot, result);
            default -> throw new IllegalStateException("取消操作開始者が不正です。");
        };
        outcome.requireAccepted();
    }
}
