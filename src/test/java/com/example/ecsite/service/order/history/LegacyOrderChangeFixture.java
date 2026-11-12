package com.example.ecsite.service.order.history;

import java.util.*;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceBundle.*;
import static com.example.ecsite.service.order.history.Feature125EvidenceFixture.*;

/** Saved legacy formats only; no current master data or database. */
final class LegacyOrderChangeFixture {
    final EnumMap<EvidenceSet,List<EvidenceRow>> sets=new EnumMap<>(EvidenceSet.class);
    LegacyOrderChangeFixture(boolean removed,boolean shipping,boolean nullTax){
        for(var s:EvidenceSet.values())sets.put(s,new ArrayList<>());
        int price=shipping?3000:6000,oldChanged=price*2,newChanged=removed?0:price;
        int residual=removed?(shipping?3000:12000):0,oldSubtotal=oldChanged+residual,newSubtotal=newChanged+residual;
        var h=row("id",100,"order_id",1,"change_source","CUSTOMER","changed_by_type","USER","changed_by_account_id",2,"changed_by_username","saved-name","reason",null,"changed_at","2026-01-01T09:00:00",
            "old_item_subtotal",oldSubtotal,"new_item_subtotal",newSubtotal,"old_charge_total",0,"new_charge_total",shipping?550:0,"old_tax_amount",oldSubtotal/11,"new_tax_amount",(newSubtotal+(shipping?550:0))/11,"old_total_amount",oldSubtotal,"new_total_amount",newSubtotal+(shipping?550:0),
            "evidence_version",null,"before_content_revision",null,"after_content_revision",null,"evidence_epoch_id",null,"previous_history_id",null);
        add(EvidenceSet.HISTORIES,binary(h,"before_fingerprint",null,"after_fingerprint",null,"after_snapshot",null,"evidence_digest",null));
        var i=row("id",101,"history_id",100,"order_item_id",10,"product_id",5,"product_name","保存商品","change_type",removed?"REMOVED":"UPDATED","old_price",price,"new_price",removed?null:price,"old_quantity",2,"new_quantity",removed?null:1,"old_subtotal",oldChanged,"new_subtotal",removed?null:newChanged);
        i=tax(i,"old_",nullTax);i=tax(i,"new_",nullTax||removed);add(EvidenceSet.ITEM_CHANGES,i);
        if(shipping){var c=row("id",102,"history_id",100,"order_charge_id",20,"charge_type","SHIPPING","change_type","UPDATED","old_name","送料・梱包料","new_name","送料・梱包料","old_amount",0,"new_amount",550,"old_display_order",10,"new_display_order",10);c=tax(c,"old_",nullTax);c=tax(c,"new_",nullTax);add(EvidenceSet.CHARGE_CHANGES,c);}
    }
    static EvidenceRow tax(EvidenceRow r,String prefix,boolean absent){for(var entry:Map.<String,Object>of("tax_category_id",1,"tax_category_code","STANDARD","tax_category_name","標準税率","tax_rate","10.00").entrySet())r=scalar(r,prefix+entry.getKey(),absent?null:entry.getValue());return r;}
    void add(EvidenceSet set,EvidenceRow r){sets.get(set).add(r);}
    void edit(EvidenceSet set,int n,String key,Object value){sets.get(set).set(n,scalar(sets.get(set).get(n),key,value));}
    OrderChangeEvidenceBundle bundle(){return new OrderChangeEvidenceBundle(Status.COMPLETE,new OrderEvidence(1,2,1,null,null),sets,new Coverage(sets.values().stream().mapToLong(List::size).sum(),10000),List.of());}
}
