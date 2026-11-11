package com.example.ecsite.service.order.evidence;

import java.time.LocalDateTime;
import java.util.List;

/** Detached preview values. No persistence IDs are allocated and no execution is authorized. */
public final class OrderEditCandidate {
    private OrderEditCandidate() {}

    /** Complete final state: omitted existing lines are removed; zero quantities are excluded.
     * A changed product on an existing line is a replacement at the current offering.
     * Repeated additions of a product merge; existing lines never merge or reprice implicitly. */
    public record Request(long orderId, int expectedRevision, List<Line> lines) {
        public Request { lines=List.copyOf(lines); }
    }
    public record Line(Long existingItemId, long productId, int quantity) {}
    public record Offer(long productId, String productName, long categoryId, String categoryName,
            int unitPrice, long taxCategoryId, String taxCategoryCode, String taxCategoryName,
            int taxRateBasisPoints, boolean active) {}

    /** now is supplied by the caller in Asia/Tokyo, not read from a system clock here. */
    public record Context(OrderPaymentEvidence.Facts facts, long customerId, String status,
            LocalDateTime orderedAt, LocalDateTime deadline, LocalDateTime preparationStartedAt,
            Long preparationAccountId, String preparationUsername, LocalDateTime now) {}

    public record StockDelta(long productId, int beforeQuantity, int afterQuantity) {
        public int delta() { return Math.subtractExact(afterQuantity,beforeQuantity); }
        public int additionalRequired() { return Math.max(0,delta()); }
        public int releaseRequired() { return Math.max(0,-delta()); }
    }
    /** previewItemId is a deterministic, candidate-local key, NEVER a generated database ID. */
    public record NewLineReference(long previewItemId, long productId) {}
    public record Candidate(OrderPaymentEvidence.Facts sourceEvidence, OrderContentSnapshot previewSnapshot,
            List<NewLineReference> newLineReferences, List<Offer> newOffers, List<StockDelta> stockDeltas) {
        public Candidate {
            newLineReferences=List.copyOf(newLineReferences);newOffers=List.copyOf(newOffers);stockDeltas=List.copyOf(stockDeltas);
        }
        @Override public String toString() { return "OrderEditCandidate[preview only]"; }
    }
    public enum Reason { CALCULATED, INVALID_INPUT, ADMISSION_DENIED, INVALID_EVIDENCE, UNAVAILABLE_PRODUCT, CONFIRMATION_CHANGED }
    public record Result(OrderChangeRoutePolicy.Route route, Reason reason, Candidate candidate) {}
}
