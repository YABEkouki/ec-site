package com.example.ecsite.service.order.history;

import java.time.ZoneId;
import java.util.*;
import com.example.ecsite.service.order.evidence.OrderContentSnapshot;

/** Pure mapping of already verified saved content. No master lookup or tax recomputation. */
public final class CustomerOrderChangeHistoryMapper {
    public CustomerOrderChangeHistoryEntry map(VerifiedOrderChange change) {
        Objects.requireNonNull(change);
        if (change.legacy() != null) return legacy(change);
        var before = change.before(); var after = change.after();
        var old = items(before); var next = items(after);
        var keys = new TreeSet<>(old.keySet()); keys.addAll(next.keySet());
        var deltas = new ArrayList<CustomerOrderChangeItemDelta>();
        for (var key : keys) {
            var b = old.get(key); var a = next.get(key);
            if (!Objects.equals(b, a)) deltas.add(delta(side(b), side(a)));
        }
        return new CustomerOrderChangeHistoryEntry(change.changedAt().atZone(ZoneId.of("Asia/Tokyo")).toLocalDateTime(),
                CustomerOrderChangeHistoryEntry.Kind.CONTENT_CHANGE, deltas, amounts(before), amounts(after),
                CustomerOrderChangeTaxCapability.SAVED_TOTAL_ONLY);
    }
    private CustomerOrderChangeHistoryEntry legacy(VerifiedOrderChange change) {
        var saved = change.legacy(); var deltas = new ArrayList<CustomerOrderChangeItemDelta>();
        for (var item : saved.items()) deltas.add(delta(side(item.productName(), item.before()), side(item.productName(), item.after())));
        return new CustomerOrderChangeHistoryEntry(change.legacyChangedAt(),
                saved.kind() == LegacyOrderChangeEvidence.Kind.LEGACY_DECREASE
                    ? CustomerOrderChangeHistoryEntry.Kind.QUANTITY_DECREASE : CustomerOrderChangeHistoryEntry.Kind.QUANTITY_INCREASE,
                deltas, amounts(saved.before()), amounts(saved.after()), CustomerOrderChangeTaxCapability.SAVED_TOTAL_ONLY);
    }
    private Map<Long,OrderContentSnapshot.Item> items(OrderContentSnapshot snapshot) {
        var result = new TreeMap<Long,OrderContentSnapshot.Item>();
        for (var item : snapshot.items()) if (result.putIfAbsent(item.orderItemId(), item) != null)
            throw new IllegalStateException("Duplicate verified item");
        return result;
    }
    private CustomerOrderChangeItemDelta delta(CustomerOrderChangeItemDelta.Side before, CustomerOrderChangeItemDelta.Side after) {
        return new CustomerOrderChangeItemDelta(before == null ? CustomerOrderChangeItemDelta.Kind.ADDED
                : after == null ? CustomerOrderChangeItemDelta.Kind.REMOVED : CustomerOrderChangeItemDelta.Kind.UPDATED, before, after);
    }
    private CustomerOrderChangeItemDelta.Side side(OrderContentSnapshot.Item item) {
        return item == null ? null : new CustomerOrderChangeItemDelta.Side(item.productName(), item.quantity(), item.unitPrice(), item.subtotal());
    }
    private CustomerOrderChangeItemDelta.Side side(String name, LegacyOrderChangeEvidence.ItemSide item) {
        return item == null ? null : new CustomerOrderChangeItemDelta.Side(name, item.quantity(), item.unitPrice(), item.subtotal());
    }
    private CustomerOrderChangeAmounts amounts(OrderContentSnapshot snapshot) {
        if (snapshot.charges().size() != 1 || !"SHIPPING".equals(snapshot.charges().getFirst().type()))
            throw new IllegalStateException("Verified shipping required");
        return new CustomerOrderChangeAmounts(snapshot.itemSubtotal(), snapshot.charges().getFirst().amount(), snapshot.taxAmount(), snapshot.totalAmount());
    }
    private CustomerOrderChangeAmounts amounts(LegacyOrderChangeEvidence.Amounts amounts) {
        return new CustomerOrderChangeAmounts(amounts.itemSubtotal(), amounts.chargeTotal(), amounts.taxAmount(), amounts.totalAmount());
    }
}
