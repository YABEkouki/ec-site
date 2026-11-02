package com.example.ecsite.service.order.evidence;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.time.LocalDateTime;
import java.util.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** Closed, typed, length-framed binary V1 contract; JSON text/key order is not a digest input.
 * Every value is 00 (null), or 01 + signed-positive int32 byte length + bytes, big endian.
 * Arrays contain int32 count followed by framed records. No implicit normalization. */
public final class OrderAdmissionFinancialSnapshotCodec {
    public static final String DOMAIN="ec-site/order-admission/financial/v1";
    public static final String BINDING_DOMAIN="ec-site/order-admission/commit-binding/v1";
    private static final Map<String,String> SCHEMAS=Map.ofEntries(
        Map.entry("financial", "domain:s version:i admissionId:u orderId:l customerId:l dbGeneration:u originalXid:x requestDigest:h candidateDigest:h revision:i status:s deadline:t epoch:u head:l? fingerprint:h epochDigest:h headDigest:h activePayment:l orderAmount:i payment:l authorization:l authorizedAmount:i authorizationRevision:i payments:[payment] transactions:[transaction] operations:[operation] requests:[request] dispatches:[dispatch] captures:[capture]"),
        Map.entry("payment", "id:l orderId:l provider:s method:s status:s amount:i providerId:s?"),
        Map.entry("transaction", "id:l paymentId:l type:s status:s amount:i revision:i purpose:s? operation:u? providerId:s? completed:n?"),
        Map.entry("operation", "id:u orderId:l state:s version:l baseRevision:i oldPayment:l newPayment:l? beforeAmount:i afterAmount:i switchDecided:n? history:l? executionVersion:i? route:s?"),
        Map.entry("request", "id:l operation:u orderId:l payment:l transaction:l purpose:s kind:s type:s amount:i flow:s? idempotency:s connection:s account:s mode:s origin:s method:s path:s contentType:s bodyVersion:i bodyDigest:h fingerprintVersion:i fingerprint:h created:t replayUntil:t"),
        Map.entry("dispatch", "request:l state:s created:t started:t? observed:t?"),
        Map.entry("capture", "id:l payment:l transaction:l type:s provider:s method:s flow:s connection:s account:s mode:s origin:s httpMethod:s path:s contentType:s amount:i revision:i idempotency:s bodyVersion:i bodyDigest:h fingerprintVersion:i fingerprint:h created:t replayUntil:t"),
        Map.entry("binding", "domain:s version:i admissionId:u orderId:l dbGeneration:u originalXid:x verificationXid:x requestDigest:h candidateDigest:h snapshotVersion:i snapshotDigest:h originalCommit:t deadline:t statusBefore:s statusAfter:s decision:s guardVersion:i"));
    private final JsonMapper json=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(4_000_000).maxStringLength(8000)
            .maxNestingDepth(12).maxTokenCount(500000).maxNumberLength(30).build()).build())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public OrderAdmissionFinancialSnapshot decode(String audit) {
        try {if(audit==null||audit.getBytes(StandardCharsets.UTF_8).length>4_000_000)throw invalid();var node=json.readTree(audit);encode(node,"financial");return json.treeToValue(node,OrderAdmissionFinancialSnapshot.class);}
        catch(RuntimeException e){throw invalid();}
    }
    public String audit(OrderAdmissionFinancialSnapshot value){return json.writeValueAsString(value);}
    public byte[] canonical(OrderAdmissionFinancialSnapshot value){return encode(json.valueToTree(value),"financial");}
    public byte[] digest(OrderAdmissionFinancialSnapshot value){return hash(canonical(value));}
    public byte[] binding(String audit){try{return encode(json.readTree(audit),"binding");}catch(RuntimeException e){throw invalid();}}
    public byte[] bindingDigest(String audit){return hash(binding(audit));}
    private byte[] encode(JsonNode node,String kind) {
        if(node==null||!node.isObject())throw invalid();
        var schema=SCHEMAS.get(kind).split(" ");if(node.size()!=schema.length)throw invalid();
        var out=new ByteArrayOutputStream();
        for(String entry:schema){
            var parts=entry.split(":");String key=parts[0],type=parts[1];boolean optional=type.endsWith("?");
            if(optional)type=type.substring(0,type.length()-1);
            var v=node.get(key);if(v==null)throw invalid();
            if(v.isNull()){if(!optional)throw invalid();frame(out,null);continue;}
            byte[] raw;
            if(type.startsWith("[")) {
                if(!v.isArray()||v.size()>1000)throw invalid();
                var array=new ByteArrayOutputStream();array.writeBytes(i32(v.size()));
                String row=type.substring(1,type.length()-1);byte[] previous=null;
                for(var item:v) {
                    byte[] sort=sortKey(item,row);if(previous!=null&&Arrays.compareUnsigned(previous,sort)>=0)throw invalid();previous=sort;
                    frame(array,encode(item,row));
                }
                raw=array.toByteArray();
            } else raw=primitive(v,type);
            frame(out,raw);
        }
        if((kind.equals("financial")||kind.equals("binding"))&&(!node.get("version").asString().equals("1")
            ||!node.get("domain").asString().equals(kind.equals("financial")?DOMAIN:BINDING_DOMAIN)))throw invalid();
        return out.toByteArray();
    }
    private byte[] sortKey(JsonNode row,String kind){
        if(kind.equals("operation"))return primitive(row.get("id"),"u");
        if(kind.equals("transaction")){var b=ByteBuffer.allocate(16);b.put(primitive(row.get("paymentId"),"l"));b.put(primitive(row.get("id"),"l"));return b.array();}
        return primitive(row.get(kind.equals("dispatch")?"request":"id"),"l");
    }
    private byte[] primitive(JsonNode v,String type){
        if(v==null||v.isNull())throw invalid();
        if(Set.of("i","l","t").contains(type)) {
            if(!v.isIntegralNumber())throw invalid();
            var number=new BigInteger(v.asString());
            return type.equals("i")?i32(number.intValueExact()):ByteBuffer.allocate(8).putLong(number.longValueExact()).array();
        }
        if(!v.isString())throw invalid();String s=v.asString();if(utf8(s).length>8000)throw invalid();
        return switch(type){
            case "u" -> {if(!s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw invalid();var u=UUID.fromString(s);yield ByteBuffer.allocate(16).putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits()).array();}
            case "x" -> {if(!s.matches("0|[1-9][0-9]{0,19}"))throw invalid();var n=new BigInteger(s);if(n.bitLength()>64)throw invalid();byte[] b=n.toByteArray(),r=new byte[8];System.arraycopy(b,Math.max(0,b.length-8),r,Math.max(0,8-b.length),Math.min(8,b.length));yield r;}
            case "h" -> {if(!s.matches("[0-9a-f]{64}"))throw invalid();yield HexFormat.of().parseHex(s);}
            case "n" -> {if(!s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}"))throw invalid();LocalDateTime.parse(s);yield utf8(s);}
            case "s" -> utf8(s);
            default -> throw invalid();
        };
    }
    private static byte[] utf8(String s){try{var b=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(s));byte[] r=new byte[b.remaining()];b.get(r);return r;}catch(CharacterCodingException e){throw invalid();}}
    private static byte[] i32(int value){return ByteBuffer.allocate(4).putInt(value).array();}
    private static void frame(ByteArrayOutputStream out,byte[] value){if(value==null){out.write(0);return;}out.write(1);out.writeBytes(i32(value.length));out.writeBytes(value);}
    private static byte[] hash(byte[] value){try{return MessageDigest.getInstance("SHA-256").digest(value);}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid admission financial snapshot");}
}
