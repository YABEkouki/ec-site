package com.example.ecsite.service.order.evidence;

import java.util.*;
import static com.example.ecsite.service.order.evidence.OrderContentSnapshot.Item;

/** Detached representation only. Neither an apply permit nor a reservation/financial command. */
public final class OrderEditApplicationPlan {
    private OrderEditApplicationPlan() {}
    public enum KeyKind { EXISTING, NEW }
    public record LineKey(KeyKind kind,long value) {
        public LineKey {Objects.requireNonNull(kind);if(value<=0)throw new IllegalArgumentException("Invalid line key");}
    }
    /** New lines have no persistedItemId; their numeric preview key is not a database ID. */
    public record Line(LineKey key,Long persistedItemId,Item before,Item after) {
        public Line {
            Objects.requireNonNull(key);
            if(key.kind()==KeyKind.NEW) {
                if(persistedItemId!=null||before!=null||after==null||after.orderItemId()!=key.value())throw new IllegalArgumentException("Invalid new line");
            } else if(persistedItemId==null||persistedItemId!=key.value()||before==null||before.orderItemId()!=key.value()
                    ||after!=null&&after.orderItemId()!=key.value())throw new IllegalArgumentException("Invalid existing line");
        }
    }
    public record InventoryPlan(long productId,int beforeQuantity,int afterQuantity,int reserveQuantity,int releaseQuantity,String reservationState) {}
    public record Plan(UUID admissionId,OrderEditAdmissionPayloadCodec.CandidatePayload candidate,
            List<Line> lines,List<InventoryPlan> inventory) {
        public Plan {Objects.requireNonNull(admissionId);Objects.requireNonNull(candidate);lines=List.copyOf(lines);inventory=List.copyOf(inventory);}
        @Override public String toString(){return "OrderEditApplicationPlan[detached]";}
    }
    public record Ownership(UUID admissionId,UUID attemptId,long token,long controlVersion) {
        public Ownership {Objects.requireNonNull(admissionId);Objects.requireNonNull(attemptId);if(token<=0||controlVersion<=0)throw new IllegalArgumentException("Invalid ownership");}
    }
    /** A checked observation of saved ownership, not permission to apply later without revalidation. */
    public record Context(int version,Ownership ownership,long orderId,int beforeRevision,int afterRevision,
            int afterAmount,String candidateDigest) {
        public Context {
            Objects.requireNonNull(ownership);
            if(version!=1||orderId<=0||beforeRevision<0||afterRevision!=Math.incrementExact(beforeRevision)||afterAmount<=0
                ||!OrderEvidenceDigests.validHash(candidateDigest))throw new IllegalArgumentException("Invalid application context");
        }
    }
    /** Future result format; no persistence method exists while the application writer is disabled. */
    public record Completion(Context context,UUID operationId,long historyId,String afterFingerprint) {
        public Completion {Objects.requireNonNull(context);Objects.requireNonNull(operationId);if(historyId<=0||!OrderEvidenceDigests.validHash(afterFingerprint))throw new IllegalArgumentException("Invalid completion format");}
    }
}
