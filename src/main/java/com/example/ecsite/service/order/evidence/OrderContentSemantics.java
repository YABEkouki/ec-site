package com.example.ecsite.service.order.evidence;

import java.util.*;

/** Business identity ignores row IDs/order, but preserves every offering's saved price/name/tax/category.
 * Called only after Codec validation. Same-product rows with different snapshots remain distinct. */
final class OrderContentSemantics {
    private OrderContentSemantics() {}
    static boolean same(OrderContentSnapshot a, OrderContentSnapshot b) { return identity(a).equals(identity(b)); }
    private static List<Object> identity(OrderContentSnapshot s) {
        var quantities=new HashMap<List<Object>,Long>();
        for(var i:s.items()) {
            var key=List.<Object>of(i.productId(),i.productName(),i.categoryId(),i.categoryName(),i.unitPrice(),
                    i.taxCategoryId(),i.taxCategoryCode(),i.taxCategoryName(),i.taxRateBasisPoints());
            quantities.merge(key,(long)i.quantity(),Math::addExact);
        }
        var charges=s.charges().stream().map(c->List.of(c.type(),c.name(),c.amount(),c.taxCategoryId(),c.taxCategoryCode(),
                c.taxCategoryName(),c.taxRateBasisPoints(),c.displayOrder())).toList();
        return List.of(quantities,charges,s.itemSubtotal(),s.chargeTotal(),s.taxAmount(),s.totalAmount());
    }
}
