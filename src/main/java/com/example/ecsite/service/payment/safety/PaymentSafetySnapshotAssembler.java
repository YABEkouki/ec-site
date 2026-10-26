package com.example.ecsite.service.payment.safety;

import java.util.*;
import com.example.ecsite.service.payment.PaymentSafetySnapshot;
import com.example.ecsite.service.payment.PaymentSafetySnapshot.*;

/** Pure assembly only. Caller must supply complete reader facts; no database/API/config lookup.
 * No enum conversion, current-time check, purpose inference or active-pointer selection occurs.
 * Primitive numeric NULLs cannot be represented by the existing classifier DTO: reject explicitly
 * rather than inventing zero values. Other invalid raw fields remain for conservative classification.
 */
public final class PaymentSafetySnapshotAssembler {
    public PaymentSafetySnapshotAssembly assemble(PaymentSafetyReadFacts facts,CaptureEvidenceProfiles profiles) {
        Objects.requireNonNull(facts);Objects.requireNonNull(profiles);
        var verification=new CaptureRequestEvidenceVerifier().verify(facts,profiles);
        var raw=facts.order();
        var order=new OrderFacts(raw.id(),raw.status(),required(raw.totalAmount(),"Order.total_amount"),required(raw.contentRevision(),"Order.content_revision"),
            raw.paidAt(),raw.cancelledAt(),raw.shippedAt(),new ShippingPreparationFacts(raw.shippingPreparationStartedAt(),raw.shippingPreparationStartedByAccountId(),raw.shippingPreparationStartedByUsername()),
            raw.shipmentCapturePaymentId(),raw.shippedByAccountId(),raw.shippedByUsername());
        var payments=facts.payments().stream().map(p->new PaymentFacts(p.id(),p.orderId(),p.provider(),p.paymentMethod(),p.status(),required(p.amount(),"Payment.amount"),p.providerPaymentId())).toList();
        var transactions=facts.transactions().stream().map(t->new TransactionFacts(t.id(),t.paymentId(),t.transactionType(),t.status(),required(t.amount(),"Transaction.amount"),required(t.orderContentRevision(),"Transaction.order_content_revision"),
            t.idempotencyKey(),t.providerTransactionId(),t.createdAt(),t.completedAt(),t.initiatorType(),t.initiatorId(),t.initiatorUsername(),t.purpose())).toList();
        var history=facts.statusHistory().stream().map(h->new StatusHistory(h.id(),h.orderId(),h.fromStatus(),h.toStatus(),h.changedAt(),h.changedByType(),h.changedByAccountId(),h.changedByUsername())).toList();
        // Identity/amount/revision/key below describe the target Transaction even when request is
        // missing. Only VERIFIED attests equality to the stored fields; raw request rows and all
        // findings remain in the assembly. Missing/ambiguous requests never invent a stored Flow ID.
        var evidence=verification.captures().stream().map(a->{
            var t=a.transaction();String flow=a.requests().size()==1?a.requests().getFirst().providerFlowId():null;
            return new CaptureEvidence(t.id(),t.paymentId(),flow,required(t.amount(),"Capture.amount"),required(t.orderContentRevision(),"Capture.order_content_revision"),t.idempotencyKey(),a.verification());
        }).toList();
        boolean complete=verification.globalFindings().isEmpty() && verification.captures().stream().allMatch(a->a.verification()==CaptureVerification.VERIFIED);
        var snapshot=new PaymentSafetySnapshot(order,payments,transactions,history,evidence,complete,facts.timeOrigins());
        return new PaymentSafetySnapshotAssembly(facts,snapshot,verification,PaymentSafetyInputFingerprint.sha256Final(facts,verification));
    }
    private static int required(Integer value,String field) {
        if(value==null)throw new IllegalArgumentException("Unrepresentable NULL persisted input: "+field);
        return value;
    }
}
