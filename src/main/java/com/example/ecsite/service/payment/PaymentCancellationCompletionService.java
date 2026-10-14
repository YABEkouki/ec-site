package com.example.ecsite.service.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.example.ecsite.entity.PaymentTransactionInitiator;
import com.example.ecsite.payment.CancellationResult;

/** HTTP retries use the operation's saved actor, just like webhook/reconciliation recovery. */
@Service
@Transactional(propagation = Propagation.NEVER)
public class PaymentCancellationCompletionService {
    private final PaymentService paymentService;
    private final PaymentCancellationResultService userResult;
    private final AdminPaymentCancellationResultService adminResult;

    public PaymentCancellationCompletionService(PaymentService paymentService,
            PaymentCancellationResultService userResult, AdminPaymentCancellationResultService adminResult) {
        this.paymentService = paymentService;
        this.userResult = userResult;
        this.adminResult = adminResult;
    }

    public void apply(Long orderId, PaymentCancellationStart start, CancellationResult result) {
        PaymentTransactionInitiator actor = paymentService.getCancellationInitiator(start.transactionId(), start.paymentId());
        switch (actor.type()) {
            case USER -> userResult.apply(orderId, start.paymentId(), actor.id(), actor.username(), result);
            case ADMIN -> adminResult.apply(orderId, start.paymentId(), actor.id(), actor.username(), actor.internalNote(), result);
            default -> throw new IllegalStateException("取消操作開始者が不正です。");
        }
    }
}
