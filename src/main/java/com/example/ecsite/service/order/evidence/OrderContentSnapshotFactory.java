package com.example.ecsite.service.order.evidence;

import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;

/** Converts persisted rows only. No master-data lookup, inferred defaults or repricing. */
public final class OrderContentSnapshotFactory {
    public OrderContentSnapshot fromRows(JsonNode order,List<JsonNode> items,List<JsonNode> charges) {
        try {
            var s=new OrderContentSnapshot(1,l(order,"id"),l(order,"user_id"),i(order,"content_revision"),
                items.stream().map(r->new OrderContentSnapshot.Item(l(r,"id"),l(r,"product_id"),t(r,"product_name"),l(r,"category_id"),t(r,"category_name"),
                    i(r,"price"),i(r,"quantity"),i(r,"subtotal"),l(r,"tax_category_id"),t(r,"tax_category_code"),t(r,"tax_category_name"),rate(r))).toList(),
                charges.stream().map(r->new OrderContentSnapshot.Charge(l(r,"id"),t(r,"charge_type"),t(r,"name"),i(r,"amount"),l(r,"tax_category_id"),
                    t(r,"tax_category_code"),t(r,"tax_category_name"),rate(r),i(r,"display_order"))).toList(),
                i(order,"item_subtotal"),i(order,"charge_total"),i(order,"tax_amount"),i(order,"total_amount"));
            var codec=new OrderContentSnapshotCodec();return codec.decode(codec.encode(s));
        } catch(RuntimeException e) {throw new IllegalArgumentException("Invalid persisted order content");}
    }
    static JsonNode required(JsonNode n,String key) {
        var v=n.get(key);if(v==null||v.isNull())throw new IllegalArgumentException("Missing persisted field: "+key);return v;
    }
    static String t(JsonNode n,String key) {var v=required(n,key);if(!v.isString())throw new IllegalArgumentException("Invalid text");return v.stringValue();}
    static long l(JsonNode n,String key) {var v=required(n,key);if(!v.isIntegralNumber()||!v.canConvertToLong())throw new IllegalArgumentException("Invalid integer");return v.longValue();}
    static int i(JsonNode n,String key) {return Math.toIntExact(l(n,key));}
    static Long nullableLong(JsonNode n,String key) {var v=n.get(key);if(v==null)throw new IllegalArgumentException("Missing column");return v.isNull()?null:l(n,key);}
    static String nullableText(JsonNode n,String key) {var v=n.get(key);if(v==null)throw new IllegalArgumentException("Missing column");return v.isNull()?null:t(n,key);}
    static UUID uuid(JsonNode n,String key) {String s=nullableText(n,key);return s==null?null:UUID.fromString(s);}
    static byte[] bytes(JsonNode n,String key) {String s=t(n,key);if(!s.startsWith("\\x"))throw new IllegalArgumentException("Invalid BYTEA");return HexFormat.of().parseHex(s.substring(2));}
    static String hash(JsonNode n,String key) {byte[] b=bytes(n,key);if(b.length!=32)throw new IllegalArgumentException("Invalid hash");return HexFormat.of().formatHex(b);}
    private static int rate(JsonNode n) {var r=required(n,"tax_rate");if(!r.isNumber())throw new IllegalArgumentException("Invalid tax rate");return OrderContentSnapshotCodec.taxRateBasisPoints(new BigDecimal(r.toString()));}
}
