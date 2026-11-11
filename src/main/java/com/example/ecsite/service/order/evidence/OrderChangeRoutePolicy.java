package com.example.ecsite.service.order.evidence;

import static com.example.ecsite.service.order.evidence.OrderPaymentEvidence.Facts;

/** Pure route proposal only. Candidate is priced at the CURRENT revision, before acceptance.
 * External observation, ownership/admission, locks, stock and execution remain caller responsibilities. */
public final class OrderChangeRoutePolicy {
    public enum Route { NO_CHANGE, RETAIN_AUTH, REAUTHORIZE, BLOCKED }
    public record Decision(Route route, OrderPaymentEvidenceDecision evidence) {}
    private final OrderContentSnapshotCodec codec=new OrderContentSnapshotCodec();
    private final OrderPaymentEvidenceValidator validator=new OrderPaymentEvidenceValidator();

    public Decision decide(Facts facts, OrderContentSnapshot candidate) {
        var evidence=validator.validate(facts);
        if(!evidence.locallyVerified())return new Decision(Route.BLOCKED,evidence);
        try {
            codec.encode(candidate);var before=facts.current();
            if(candidate.orderId()!=before.orderId() || candidate.customerId()!=before.customerId()
                    || candidate.contentRevision()!=before.contentRevision())return new Decision(Route.BLOCKED,evidence);
            if(OrderContentSemantics.same(before,candidate))return new Decision(Route.NO_CHANGE,evidence);
            return new Decision(candidate.totalAmount()<=evidence.authorizedAmount()?Route.RETAIN_AUTH:Route.REAUTHORIZE,evidence);
        } catch(RuntimeException invalid) { return new Decision(Route.BLOCKED,evidence); }
    }

}
