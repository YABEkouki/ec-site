package com.example.ecsite.service.order.evidence;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.math.BigDecimal;
import java.util.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** V1 typed recursive closure encoding, independent of JSON whitespace, key order and decimal scale.
 * Object keys sort by unsigned UTF-8 bytes. All lengths/counts are int32 big endian.
 * Audit UTF-8 <=4 MiB, canonical <=2 MiB; root container has depth 1 (max 64).
 * Raw/expanded decimal digits <=1000, exponent has at most three digits (abs<=999).
 * Audit JSON retains all DB snapshot facts; no JSON textual representation is hashed. */
public final class OrderUnappliedClosureCodec {
    public static final String DOMAIN="ec-site/order-edit/unapplied/v1";
    public static final int MAX_AUDIT_BYTES=4*1024*1024;
    public static final int MAX_CANONICAL_BYTES=2*1024*1024;
    public static final int MAX_NUMBER_DIGITS=1000;
    public static final int MAX_CONTAINER_DEPTH=64;
    // Raw digits include coefficient and exponent digits; sign/dot/e are not digits.
    // A 1..3 digit exponent bounds conversion before BigDecimal/JSONB normalization.
    private static final Set<String> FIELDS=Set.of("domain","version","admissionId","orderId","generation","requestId","requestVersion",
        "originalXid","closureXid","financialDigest","bindingDigest","business","terminal","reason","closedAt","invalidatedToken","controlVersion","actor","attempts","results");
    private final JsonMapper json=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(MAX_AUDIT_BYTES).maxNestingDepth(MAX_CONTAINER_DEPTH).maxNumberLength(1100).maxStringLength(MAX_AUDIT_BYTES).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build();
    public byte[] canonicalValue(String audit){
        try{return canonical(read(audit));}
        catch(RuntimeException e){throw new IllegalArgumentException("Invalid closure canonical value",e);}
    }
    private JsonNode read(String audit){
        if(audit==null||utf8(audit).length>MAX_AUDIT_BYTES)throw new IllegalArgumentException();
        // Validate raw numeric tokens before JSON tree parsing discards their lexical form.
        try(var parser=json.createParser(audit)){
            tools.jackson.core.JsonToken token;
            while((token=parser.nextToken())!=null)if(token.isNumeric())number(parser.getText());
        }
        return json.readTree(audit);
    }
    private byte[] canonical(JsonNode node){var out=new ByteArrayOutputStream();encode(node,out,0);return out.toByteArray();}
    private static String number(String raw){
        if(raw.chars().filter(c->c>='0'&&c<='9').count()>MAX_NUMBER_DIGITS)throw new IllegalArgumentException();
        int exponent=Math.max(raw.indexOf('e'),raw.indexOf('E'));
        if(exponent>=0&&!raw.substring(exponent+1).matches("[+-]?[0-9]{1,3}"))throw new IllegalArgumentException();
        String plain=new BigDecimal(raw).stripTrailingZeros().toPlainString();
        if(plain.chars().filter(c->c>='0'&&c<='9').count()>MAX_NUMBER_DIGITS)throw new IllegalArgumentException();
        return plain;
    }
    public OrderUnappliedClosure.Evidence verify(String audit,byte[] canonical,byte[] digest){
        try{
            var n=read(audit);var keys=new HashSet<String>();n.propertyNames().forEach(keys::add);
            if(!n.isObject()||!keys.equals(FIELDS)||!DOMAIN.equals(n.path("domain").asString())||!n.path("version").isIntegralNumber()||n.path("version").asInt()!=1
                ||!n.path("business").isObject()||!n.path("attempts").isArray()||!n.path("results").isArray()
                ||!Set.of("CLOSED_UNAPPLIED","FAILED_UNAPPLIED").contains(n.path("terminal").asString())
                ||!Arrays.equals(canonical(n),canonical)||!MessageDigest.isEqual(sha256(canonical),digest))throw new IllegalArgumentException();
            return new OrderUnappliedClosure.Evidence(UUID.fromString(n.path("admissionId").asString()),UUID.fromString(n.path("requestId").asString()),
                n.path("requestVersion").longValue(),n.path("terminal").asString(),n.path("reason").asString(),audit,canonical,digest);
        }catch(RuntimeException e){throw new IllegalArgumentException("Incomplete or inconsistent closure evidence",e);}
    }
    public byte[] sha256(byte[] bytes){try{return MessageDigest.getInstance("SHA-256").digest(Objects.requireNonNull(bytes));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private void encode(JsonNode n,ByteArrayOutputStream o,int depth){
        if(n==null||((n.isArray()||n.isObject())&&depth>=MAX_CONTAINER_DEPTH))throw new IllegalArgumentException();
        if(n.isNull())o.write(0);
        else if(n.isBoolean()){o.write(1);o.write(n.booleanValue()?1:0);}
        else if(n.isString()){o.write(2);text(o,n.asString());}
        else if(n.isNumber()){o.write(3);text(o,number(n.decimalValue().stripTrailingZeros().toPlainString()));}
        else if(n.isArray()){o.write(4);integer(o,n.size());for(var v:n)encode(v,o,depth+1);}
        else if(n.isObject()){
            o.write(5);integer(o,n.size());var keys=new ArrayList<String>();n.propertyNames().forEach(keys::add);
            keys.sort((a,b)->Arrays.compareUnsigned(utf8(a),utf8(b)));
            for(var k:keys){text(o,k);encode(n.get(k),o,depth+1);}
        }else throw new IllegalArgumentException();
        if(o.size()>MAX_CANONICAL_BYTES)throw new IllegalArgumentException();
    }
    private static byte[] utf8(String s){if(s.indexOf(0)>=0)throw new IllegalArgumentException();try{var b=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
        .encode(java.nio.CharBuffer.wrap(s));var v=new byte[b.remaining()];b.get(v);return v;}catch(CharacterCodingException e){throw new IllegalArgumentException(e);}}
    private static void integer(ByteArrayOutputStream o,int value){o.writeBytes(ByteBuffer.allocate(4).putInt(value).array());}
    private static void text(ByteArrayOutputStream o,String s){var b=utf8(s);integer(o,b.length);o.writeBytes(b);}
}
