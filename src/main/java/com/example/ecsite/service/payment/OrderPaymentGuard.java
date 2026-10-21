package com.example.ecsite.service.payment;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.example.ecsite.entity.*;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.repository.PaymentRepository;
import com.example.ecsite.repository.PaymentTransactionRepository;

/** Called only after the caller locks Order. Payments are first read under write locks. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class OrderPaymentGuard {
    private final com.example.ecsite.service.orderchange.OrderChangeStore changes;
    private final PaymentRepository payments;
    private final PaymentTransactionRepository transactions;

    public OrderPaymentGuard(PaymentRepository payments, PaymentTransactionRepository transactions,
            com.example.ecsite.service.orderchange.OrderChangeStore changes) {
        this.changes = changes;
        this.payments = payments;
        this.transactions = transactions;
    }

    public void validateItemChange(Order order) {
        assertNoPending(lockedPayments(order));
    }

    public void validateManualPayment(Order order) {
        validateLocalCancellation(order);
    }

    public void validateManualShipment(Order order) {
        List<Payment> current = lockedPayments(order);
        assertNoPending(current);
        if (current.stream().anyMatch(p -> unresolved(p) || p.getStatus() == PaymentStatus.AUTHORIZED)) {
            reject("カード決済が確定していないため、通常発送できません。");
        }
    }

    public void validateLocalCancellation(Order order) {
        List<Payment> current = lockedPayments(order);
        assertNoPending(current);
        if (current.stream().anyMatch(p -> unresolved(p) || p.getStatus() == PaymentStatus.AUTHORIZED
                || p.getStatus() == PaymentStatus.CAPTURED)) {
            reject("未確定または有効なカード決済があるため、注文状態だけを変更できません。");
        }
    }

    public boolean requiresProviderCancellation(Order order) {
        List<Payment> current = lockedPayments(order);
        List<Payment> active = current.stream().filter(p -> p.getStatus() != PaymentStatus.FAILED
                && p.getStatus() != PaymentStatus.CANCELLED).toList();
        if (active.size() > 1) reject("有効な決済が一意でないため、注文を取り消せません。");
        Payment authorized = active.isEmpty() ? null : active.getFirst();
        if (authorized != null && (authorized.getStatus() != PaymentStatus.AUTHORIZED
                || authorized.getProvider() != PaymentProvider.PAYJP
                || authorized.getPaymentMethod() != PaymentMethod.CARD)) {
            reject("決済が未確定または取消対象外のため、注文を取り消せません。");
        }
        int pendingCancels = 0;
        for (Payment payment : current) {
            for (PaymentTransaction transaction : pending(payment)) {
                if (transaction.getTransactionType() != PaymentTransactionType.CANCEL
                        || authorized == null || !payment.getId().equals(authorized.getId())) {
                    reject("競合する決済処理中のため、注文を取り消せません。");
                }
                pendingCancels++;
            }
        }
        if (pendingCancels > 1) reject("複数の取消処理が存在するため、注文を取り消せません。");
        return authorized != null;
    }

    public void requireNoOrderChange(Order order) {
        if (changes.unresolved(order.getId())) reject("未解決の注文変更があるため、この操作を開始できません。");
    }

    private List<Payment> lockedPayments(Order order) {
        requireNoOrderChange(order);
        return payments.findByOrderIdForUpdate(order.getId());
    }

    private List<PaymentTransaction> pending(Payment payment) {
        return transactions.findByPaymentIdOrderByCreatedAtAscIdAsc(payment.getId()).stream()
                .filter(t -> t.getStatus() == PaymentTransactionStatus.PENDING)
                .filter(t -> t.getTransactionType() == PaymentTransactionType.AUTHORIZE
                        || t.getTransactionType() == PaymentTransactionType.CAPTURE
                        || t.getTransactionType() == PaymentTransactionType.CANCEL).toList();
    }

    private void assertNoPending(List<Payment> current) {
        if (current.stream().anyMatch(p -> !pending(p).isEmpty())) {
            reject("決済処理中のため、注文を変更できません。");
        }
    }

    private boolean unresolved(Payment payment) {
        return payment.getStatus() == PaymentStatus.PENDING || payment.getStatus() == PaymentStatus.REQUIRES_ACTION;
    }

    private void reject(String message) {
        throw new InvalidOrderStatusException(message);
    }
}
