package com.example.ecsite.service.order.history;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Verified saved deltas, never an inferred full order snapshot or revision. No financial identifiers. */
public record LegacyOrderChangeEvidence(Kind kind,Amounts before,Amounts after,List<ItemChange> items,
        ShippingChange shippingChange,ShippingSide operationShipping,TaxBreakdown taxBreakdown) {
    public enum Kind { LEGACY_DECREASE, LEGACY_REAUTHORIZE }
    public enum TaxBreakdown { NOT_RECORDED }
    public record Amounts(int itemSubtotal,int chargeTotal,int taxAmount,int totalAmount) {}
    /** NULL means no saved evidence. Partial tuples are preserved; no master-data completion. */
    public record TaxEvidence(Long categoryId,String code,String name,BigDecimal rate) {
        public boolean complete(){return categoryId!=null&&code!=null&&name!=null&&rate!=null;}
        boolean sameAs(TaxEvidence other){return Objects.equals(categoryId,other.categoryId)&&Objects.equals(code,other.code)&&Objects.equals(name,other.name)
            &&(rate==null?other.rate==null:other.rate!=null&&rate.compareTo(other.rate)==0);}
    }
    public record ItemSide(int unitPrice,int quantity,int subtotal,TaxEvidence tax) {}
    public record ItemChange(long orderItemId,long productId,String productName,String type,ItemSide before,ItemSide after) {}
    public record ShippingSide(String name,int amount,int displayOrder,TaxEvidence tax) {}
    public record ShippingChange(long orderChargeId,ShippingSide before,ShippingSide after) {}
    public LegacyOrderChangeEvidence {Objects.requireNonNull(kind);Objects.requireNonNull(before);Objects.requireNonNull(after);items=List.copyOf(items);Objects.requireNonNull(taxBreakdown);}
    @Override public String toString(){return "LegacyOrderChangeEvidence[redacted]";}
}
