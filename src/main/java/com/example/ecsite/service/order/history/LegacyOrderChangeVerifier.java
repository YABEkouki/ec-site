package com.example.ecsite.service.order.history;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceBundle.*;
import static com.example.ecsite.service.order.history.OrderChangeHistoryVerificationResult.*;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceChecks.*;
import static com.example.ecsite.service.order.history.LegacyOrderChangeEvidence.*;

/** Known Feature95/96 writer shapes plus old reauthorization. Pure saved facts only. */
final class LegacyOrderChangeVerifier {
    private final OrderChangeEvidenceIndex index;
    LegacyOrderChangeVerifier(OrderChangeEvidenceIndex index){this.index=index;}
    VerifiedOrderChange verify(EvidenceRow h){
        check(!index.feature125(h),Reason.UNKNOWN_FORMAT);oldHistory(h);
        equal(text(h,"change_source"),"CUSTOMER",Reason.HISTORY_DIFFERENCE);equal(text(h,"changed_by_type"),"USER",Reason.HISTORY_DIFFERENCE);
        equal(number(h,"changed_by_account_id"),index.bundle.order().customerId(),Reason.OWNERSHIP_MISMATCH);text(h,"changed_by_username");
        var operations=index.operations(h);check(operations.size()<=1,Reason.DUPLICATE_KEY);boolean increase=!operations.isEmpty();
        equal(nullable(h,"reason"),increase?"数量増加・再与信":null,Reason.UNKNOWN_FORMAT);
        var before=amounts(h,"old_");var after=amounts(h,"new_");
        var rows=index.matching(EvidenceSet.ITEM_CHANGES,"history_id",text(h,"id"));check(!rows.isEmpty(),Reason.HISTORY_DIFFERENCE);
        var ids=new HashSet<Long>();var items=new ArrayList<ItemChange>();int oldSum=0,newSum=0;
        for(var row:rows){
            long id=number(row,"order_item_id");check(id>0&&ids.add(id),Reason.DUPLICATE_KEY);check(number(row,"product_id")>0,Reason.HISTORY_DIFFERENCE);
            var type=text(row,"change_type");var old=side(row,"old_");ItemSide next;
            if(type.equals("REMOVED")){
                check(!increase,Reason.HISTORY_DIFFERENCE);nullSide(row,"new_");next=null;
            }else{
                equal(type,"UPDATED",Reason.UNKNOWN_FORMAT);next=side(row,"new_");
                check(increase?next.quantity()>old.quantity():next.quantity()<old.quantity(),Reason.HISTORY_DIFFERENCE);
                check(old.unitPrice()==next.unitPrice()&&old.tax().sameAs(next.tax()),Reason.HISTORY_DIFFERENCE);
            }
            oldSum=Math.addExact(oldSum,old.subtotal());if(next!=null)newSum=Math.addExact(newSum,next.subtotal());
            items.add(new ItemChange(id,number(row,"product_id"),text(row,"product_name"),type,old,next));
        }
        check(Math.subtractExact(after.itemSubtotal(),before.itemSubtotal())==Math.subtractExact(newSum,oldSum),Reason.HISTORY_DIFFERENCE);
        int oldResidual=Math.subtractExact(before.itemSubtotal(),oldSum),newResidual=Math.subtractExact(after.itemSubtotal(),newSum);
        check(oldResidual>=0&&newResidual>=0&&oldResidual==newResidual,Reason.HISTORY_DIFFERENCE);
        var charges=index.matching(EvidenceSet.CHARGE_CHANGES,"history_id",text(h,"id"));check(charges.size()<=1,Reason.HISTORY_DIFFERENCE);ShippingChange shipping=null;
        if(charges.isEmpty())check(before.chargeTotal()==after.chargeTotal(),Reason.HISTORY_DIFFERENCE);
        else{
            var c=charges.getFirst();equal(text(c,"charge_type"),"SHIPPING",Reason.UNKNOWN_FORMAT);equal(text(c,"change_type"),"UPDATED",Reason.UNKNOWN_FORMAT);
            long id=number(c,"order_charge_id");check(id>0,Reason.HISTORY_DIFFERENCE);var b=shipping(c,"old_");var a=shipping(c,"new_");
            check(b.amount()==before.chargeTotal()&&a.amount()==after.chargeTotal()&&b.tax().sameAs(a.tax()),Reason.HISTORY_DIFFERENCE);
            check(!sameShipping(b,a),Reason.HISTORY_DIFFERENCE);shipping=new ShippingChange(id,b,a);
        }
        verifyTax(before,items,true,oldResidual,shipping==null?null:shipping.before());
        verifyTax(after,items,false,newResidual,shipping==null?null:shipping.after());
        items.sort(Comparator.comparingLong(ItemChange::orderItemId));
        var data=new LegacyOrderChangeEvidence(increase?Kind.LEGACY_REAUTHORIZE:Kind.LEGACY_DECREASE,before,after,items,shipping,null,TaxBreakdown.NOT_RECORDED);
        if(increase)data=new LegacyReauthorizationVerifier(index).verify(h,operations.getFirst(),data);
        return VerifiedOrderChange.legacy(number(h,"id"),LocalDateTime.parse(text(h,"changed_at")),data);
    }
    /** Saved inclusive-tax rates can prove a lower bound, or an exact total when all gross is covered.
     * Missing rates remain unknown. Grouping never invents tax-category breakdown evidence. */
    private void verifyTax(Amounts parent,List<ItemChange> items,boolean before,int residual,ShippingSide shipping){
        var gross=new HashMap<BigDecimal,Integer>();boolean complete=residual==0;
        for(var change:items){var side=before?change.before():change.after();if(side==null||side.subtotal()==0)continue;
            if(side.tax().rate()==null)complete=false;else gross.merge(side.tax().rate().stripTrailingZeros(),side.subtotal(),Math::addExact);
        }
        if(parent.chargeTotal()>0){if(shipping==null||shipping.tax().rate()==null)complete=false;else gross.merge(shipping.tax().rate().stripTrailingZeros(),parent.chargeTotal(),Math::addExact);}
        int knownTax=0;
        for(var e:gross.entrySet())knownTax=Math.addExact(knownTax,BigDecimal.valueOf(e.getValue()).multiply(e.getKey()).divide(new BigDecimal("100").add(e.getKey()),0,java.math.RoundingMode.DOWN).intValueExact());
        check(parent.taxAmount()>=knownTax&&(!complete||parent.taxAmount()==knownTax),Reason.HISTORY_DIFFERENCE);
    }
    private void oldHistory(EvidenceRow h){
        for(var k:List.of("evidence_version","before_content_revision","after_content_revision","evidence_epoch_id","previous_history_id"))check(nullable(h,k)==null,Reason.UNKNOWN_FORMAT);
        for(var k:List.of("before_fingerprint","after_fingerprint","after_snapshot","evidence_digest"))check(optionalBytes(h,k)==null,Reason.UNKNOWN_FORMAT);
    }
    private Amounts amounts(EvidenceRow h,String prefix){
        int subtotal=integer(h,prefix+"item_subtotal"),charge=integer(h,prefix+"charge_total"),tax=integer(h,prefix+"tax_amount"),total=integer(h,prefix+"total_amount");
        check(subtotal>=0&&charge>=0&&tax>=0&&total>0&&tax<=total&&Math.addExact(subtotal,charge)==total,Reason.HISTORY_DIFFERENCE);return new Amounts(subtotal,charge,tax,total);
    }
    private ItemSide side(EvidenceRow row,String prefix){
        int price=integer(row,prefix+"price"),qty=integer(row,prefix+"quantity"),subtotal=integer(row,prefix+"subtotal");check(price>=0&&qty>0&&subtotal>=0&&Math.multiplyExact(price,qty)==subtotal,Reason.HISTORY_DIFFERENCE);return new ItemSide(price,qty,subtotal,tax(row,prefix));
    }
    private void nullSide(EvidenceRow row,String prefix){for(var k:List.of("price","quantity","subtotal","tax_category_id","tax_category_code","tax_category_name","tax_rate"))check(nullable(row,prefix+k)==null,Reason.HISTORY_DIFFERENCE);}
    static TaxEvidence tax(EvidenceRow row,String prefix){
        String id=nullable(row,prefix+"tax_category_id"),rate=nullable(row,prefix+"tax_rate");Long category=id==null?null:Long.valueOf(id);BigDecimal value=rate==null?null:new BigDecimal(rate);
        check(category==null||category>0,Reason.HISTORY_DIFFERENCE);check(value==null||value.signum()>=0&&value.compareTo(new BigDecimal("999.99"))<=0&&value.movePointRight(2).intValueExact()>=0,Reason.HISTORY_DIFFERENCE);
        return new TaxEvidence(category,nullable(row,prefix+"tax_category_code"),nullable(row,prefix+"tax_category_name"),value);
    }
    static ShippingSide shipping(EvidenceRow row,String prefix){int amount=integer(row,prefix+"amount");check(amount>=0,Reason.HISTORY_DIFFERENCE);return new ShippingSide(text(row,prefix+"name"),amount,integer(row,prefix+"display_order"),tax(row,prefix));}
    private static boolean sameShipping(ShippingSide a,ShippingSide b){return a.name().equals(b.name())&&a.amount()==b.amount()&&a.displayOrder()==b.displayOrder()&&a.tax().sameAs(b.tax());}
}
