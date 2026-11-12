package com.example.ecsite.service.order.history;

import java.math.BigDecimal;
import java.util.*;
import com.example.ecsite.service.order.evidence.OrderContentSnapshot;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceBundle.*;
import static com.example.ecsite.service.order.history.OrderChangeHistoryVerificationResult.*;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceChecks.*;

/** Mirrors the stored diff shape, not a display diff or a current-price calculation. */
final class OrderChangeHistoryDifferenceVerifier {
    private final OrderChangeEvidenceIndex index;
    OrderChangeHistoryDifferenceVerifier(OrderChangeEvidenceIndex index){this.index=index;}
    void verify(EvidenceRow h,EvidenceRow op,OrderContentSnapshot before,OrderContentSnapshot after,EvidenceRow a){
        equal(text(h,"change_source"),"CUSTOMER",Reason.HISTORY_DIFFERENCE);equal(text(h,"changed_by_type"),"USER",Reason.HISTORY_DIFFERENCE);
        equal(number(h,"changed_by_account_id"),index.bundle.order().customerId(),Reason.OWNERSHIP_MISMATCH);
        equal(text(h,"changed_by_username"),text(a,"actor_username"),Reason.HISTORY_DIFFERENCE);check(nullable(h,"reason")==null,Reason.HISTORY_DIFFERENCE);
        amounts(h,"old_",before);amounts(h,"new_",after);
        equal(integer(op,"item_subtotal"),after.itemSubtotal(),Reason.HISTORY_DIFFERENCE);equal(integer(op,"charge_total"),after.chargeTotal(),Reason.HISTORY_DIFFERENCE);equal(integer(op,"tax_amount"),after.taxAmount(),Reason.HISTORY_DIFFERENCE);
        var old=new TreeMap<Long,OrderContentSnapshot.Item>();var now=new TreeMap<Long,OrderContentSnapshot.Item>();before.items().forEach(i->old.put(i.orderItemId(),i));after.items().forEach(i->now.put(i.orderItemId(),i));
        var ids=new TreeSet<>(old.keySet());ids.addAll(now.keySet());var expected=new HashSet<Long>();
        for(long id:ids)if(!Objects.equals(old.get(id),now.get(id)))expected.add(id);
        var rows=index.matching(EvidenceSet.ITEM_CHANGES,"history_id",text(h,"id"));check(rows.size()==expected.size(),Reason.HISTORY_DIFFERENCE);var seen=new HashSet<Long>();
        for(var row:rows){long id=number(row,"order_item_id");check(expected.contains(id)&&seen.add(id),Reason.HISTORY_DIFFERENCE);var b=old.get(id);var n=now.get(id);var saved=b==null?n:b;
            equal(number(row,"product_id"),saved.productId(),Reason.HISTORY_DIFFERENCE);equal(text(row,"product_name"),saved.productName(),Reason.HISTORY_DIFFERENCE);
            equal(text(row,"change_type"),b==null?"ADDED":n==null?"REMOVED":"UPDATED",Reason.HISTORY_DIFFERENCE);item(row,"old_",b);item(row,"new_",n);
        }
        var b=before.charges().getFirst();var n=after.charges().getFirst();
        field(op,"shipping_name",n.name());field(op,"shipping_amount",n.amount());field(op,"shipping_display_order",n.displayOrder());tax(op,"shipping_",n.taxCategoryId(),n.taxCategoryCode(),n.taxCategoryName(),n.taxRateBasisPoints());var charges=index.matching(EvidenceSet.CHARGE_CHANGES,"history_id",text(h,"id"));
        check(charges.size()==(b.equals(n)?0:1),Reason.HISTORY_DIFFERENCE);
        if(!charges.isEmpty()){var row=charges.getFirst();equal(number(row,"order_charge_id"),b.orderChargeId(),Reason.HISTORY_DIFFERENCE);equal(text(row,"charge_type"),"SHIPPING",Reason.HISTORY_DIFFERENCE);equal(text(row,"change_type"),"UPDATED",Reason.HISTORY_DIFFERENCE);charge(row,"old_",b);charge(row,"new_",n);}
    }
    private void amounts(EvidenceRow r,String prefix,OrderContentSnapshot s){equal(integer(r,prefix+"item_subtotal"),s.itemSubtotal(),Reason.HISTORY_DIFFERENCE);equal(integer(r,prefix+"charge_total"),s.chargeTotal(),Reason.HISTORY_DIFFERENCE);equal(integer(r,prefix+"tax_amount"),s.taxAmount(),Reason.HISTORY_DIFFERENCE);equal(integer(r,prefix+"total_amount"),s.totalAmount(),Reason.HISTORY_DIFFERENCE);}
    private void item(EvidenceRow row,String prefix,OrderContentSnapshot.Item i){
        field(row,prefix+"price",i==null?null:i.unitPrice());field(row,prefix+"quantity",i==null?null:i.quantity());field(row,prefix+"subtotal",i==null?null:i.subtotal());
        tax(row,prefix,i==null?null:i.taxCategoryId(),i==null?null:i.taxCategoryCode(),i==null?null:i.taxCategoryName(),i==null?null:i.taxRateBasisPoints());
    }
    private void charge(EvidenceRow row,String prefix,OrderContentSnapshot.Charge c){field(row,prefix+"name",c.name());field(row,prefix+"amount",c.amount());field(row,prefix+"display_order",c.displayOrder());tax(row,prefix,c.taxCategoryId(),c.taxCategoryCode(),c.taxCategoryName(),c.taxRateBasisPoints());}
    private void tax(EvidenceRow row,String prefix,Long id,String code,String name,Integer rate){field(row,prefix+"tax_category_id",id);field(row,prefix+"tax_category_code",code);field(row,prefix+"tax_category_name",name);
        String v=nullable(row,prefix+"tax_rate");if(rate==null)check(v==null,Reason.HISTORY_DIFFERENCE);else{check(v!=null,Reason.HISTORY_DIFFERENCE);equal(new BigDecimal(v).movePointRight(2).intValueExact(),rate,Reason.HISTORY_DIFFERENCE);}
    }
    private void field(EvidenceRow row,String field,Object expected){equal(nullable(row,field),expected==null?null:expected.toString(),Reason.HISTORY_DIFFERENCE);}
}
