package com.example.ecsite.service.payment.safety.payload;

import com.example.ecsite.payment.capture.CaptureConnectionIdentity;
import com.example.ecsite.service.payment.*;
import com.example.ecsite.service.payment.safety.*;
import com.example.ecsite.service.payment.safety.PaymentSafetyReadFacts.*;
import tools.jackson.core.*;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.*;
import java.security.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.function.Supplier;

/** Closed V1 type universe. Reflection accesses only these records, never a payload-supplied class. */
final class PayloadEncodingV1 {
    private static final Set<Class<?>> RECORDS;
    static {
        var types=new HashSet<Class<?>>();
        for(var root:List.of(PaymentSafetyReadFacts.class,PaymentSafetyClassification.class,PaymentSafetySnapshot.class,
                CaptureEvidenceValidation.class,CaptureEvidenceProfiles.class,AssessmentRecordDigestV1.class,AuditEventDigestV1.class)) {
            if(root.isRecord())types.add(root);
            for(var nested:root.getDeclaredClasses())if(nested.isRecord())types.add(nested);
        }
        types.add(CaptureConnectionIdentity.class);RECORDS=Set.copyOf(types);
    }
    private static final DateTimeFormatter LOCAL=new DateTimeFormatterBuilder().appendPattern("uuuu-MM-dd'T'HH:mm:ss")
        .appendFraction(java.time.temporal.ChronoField.NANO_OF_SECOND,0,9,true).toFormatter(Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    private final PaymentSafetyPayloadCodecV1.Limits limits;
    private final JsonMapper mapper;
    PayloadEncodingV1(PaymentSafetyPayloadCodecV1.Limits limits) {
        this.limits=limits;
        var factory=JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(limits.maxDepth()).maxDocumentLength(limits.maxPayloadBytes())
                .maxTokenCount(limits.maxTokens()).maxStringLength(limits.maxStringLength()).maxNameLength(256).maxNumberLength(32).build()).build();
        mapper=JsonMapper.builder(factory).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build();
    }
    static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid payment safety payload");}
    <T> T safe(Supplier<T> operation) {
        try{return operation.get();}catch(RuntimeException e){throw invalid();}
    }
    ObjectNode object(){return mapper.createObjectNode();}
    JsonNode parse(String json) {
        return safe(()->{total(json);var node=mapper.readTree(json);if(node==null)throw invalid();checkTree(node,0,new long[]{0});return node;});
    }
    void total(String... json) {
        long total=0;for(String s:json){if(s==null)throw invalid();total+=utf8Length(s);if(total>limits.maxPayloadBytes())throw invalid();}
    }
    private long utf8Length(String s) {
        long count=0;
        for(int i=0;i<s.length();i++) {
            char c=s.charAt(i);if(c==0)throw invalid(); // PostgreSQL JSONB cannot store U+0000.
            if(c<0x80)count++;else if(c<0x800)count+=2;
            else if(Character.isHighSurrogate(c)){if(++i>=s.length() || !Character.isLowSurrogate(s.charAt(i)))throw invalid();count+=4;}
            else if(Character.isLowSurrogate(c))throw invalid();else count+=3;
        }return count;
    }
    String json(JsonNode node) {
        checkTree(node,0,new long[]{0});
        // Bounded output stops serialization rather than generating an oversized unbounded String.
        var sink=new ByteArrayOutputStream();
        try {
            mapper.writeValue(new FilterOutputStream(sink){long count;
                private void add(int n){count+=n;if(count>limits.maxPayloadBytes())throw invalid();}
                @Override public void write(int b)throws IOException{add(1);out.write(b);}
                @Override public void write(byte[] b,int o,int n)throws IOException{add(n);out.write(b,o,n);}
            },node);
            return sink.toString(StandardCharsets.UTF_8);
        }catch(RuntimeException e){throw invalid();}
    }
    private void checkTree(JsonNode n,int depth,long[] tokens) {
        if(depth>limits.maxDepth() || ++tokens[0]>limits.maxTokens())throw invalid();
        if(n.isString()){if(n.stringValue().length()>limits.maxStringLength())throw invalid();utf8Length(n.stringValue());}
        if(n.isArray() && n.size()>limits.maxArrayElements())throw invalid();
        if(n.isFloatingPointNumber() && (Math.abs((long)n.decimalValue().scale())>limits.maxStringLength() || n.decimalValue().precision()>limits.maxStringLength()))throw invalid();
        if(n.isObject())for(String key:n.propertyNames()){if(key.length()>256)throw invalid();utf8Length(key);checkTree(n.get(key),depth+1,tokens);}
        else if(n.isArray())for(var item:n)checkTree(item,depth+1,tokens);
    }
    void keys(JsonNode node,String... names) {
        if(node==null || !node.isObject() || node.size()!=names.length)throw invalid();
        for(String n:names)if(!node.has(n))throw invalid();
    }
    private static String tag(Class<?> c) {
        if(c==String.class || c==UUID.class)return "text";
        if(c==Long.class || c==long.class)return "int64";
        if(c==Integer.class || c==int.class)return "int32";
        if(c==Short.class || c==short.class)return "int16";
        if(c==LocalDateTime.class)return "timestamp";if(c==Instant.class)return "timestamptz";
        if(c==Bytes.class)return "bytes";if(c.isEnum())return "enum";throw invalid();
    }
    JsonNode encode(Object value,Type type){return encode(value,type,0,new long[]{0,0});}
    private JsonNode encode(Object value,Type type,int depth,long[] count) {
        if(depth>limits.maxDepth() || ++count[0]>limits.maxTokens())throw invalid();
        if(type instanceof ParameterizedType p) {
            if(value==null)throw invalid();var a=mapper.createArrayNode();
            if(p.getRawType()==List.class) {
                var list=(List<?>)value;if(list.size()>limits.maxArrayElements())throw invalid();
                for(var x:list)a.add(encode(x,p.getActualTypeArguments()[0],depth+1,count));
            }else if(p.getRawType()==Map.class) {
                var map=(Map<?,?>)value;if(map.size()>limits.maxArrayElements())throw invalid();
                // Deterministic encoded keys, independent of Map iteration order.
                var entries=new TreeMap<String,JsonNode>();
                for(var e:map.entrySet()) {
                    if(e.getKey()==null || e.getValue()==null)throw invalid();
                    var key=encode(e.getKey(),p.getActualTypeArguments()[0],depth+1,count);
                    var entry=object();entry.set("key",key);entry.set("value",encode(e.getValue(),p.getActualTypeArguments()[1],depth+1,count));
                    if(entries.put(json(key),entry)!=null)throw invalid();
                }entries.values().forEach(a::add);
            }else throw invalid();return a;
        }
        var c=(Class<?>)type;
        if(c.isRecord()) {
            if(!RECORDS.contains(c))throw invalid();if(value==null)return NullNode.getInstance();var result=object();
            for(var component:c.getRecordComponents())try {
                result.set(component.getName(),encode(component.getAccessor().invoke(value),component.getGenericType(),depth+1,count));
            }catch(ReflectiveOperationException e){throw invalid();}return result;
        }
        var leaf=object();leaf.put("type",tag(c));
        if(value==null){if(c.isPrimitive())throw invalid();leaf.putNull("value");return leaf;}
        if(value instanceof Instant t){var n=object();n.put("epochSecond",Long.toString(t.getEpochSecond()));n.put("nano",t.getNano());leaf.set("value",n);}
        else {
            String s;
            if(value instanceof Bytes b) {
                long base64Length=4L*((b.length()+2L)/3L);
                if(base64Length>limits.maxStringLength() || base64Length>limits.maxPayloadBytes()-count[1])throw invalid();
                s=Base64.getEncoder().encodeToString(b.value());
            }else s=value instanceof LocalDateTime t?LOCAL.format(t):value instanceof Enum<?> e?e.name():value.toString();
            if(s.length()>limits.maxStringLength())throw invalid();count[1]+=utf8Length(s);if(count[1]>limits.maxPayloadBytes())throw invalid();leaf.put("value",s);
        }return leaf;
    }
    Object decode(JsonNode node,Type type) {
        if(type instanceof ParameterizedType p) {
            if(!node.isArray() || node.size()>limits.maxArrayElements())throw invalid();
            if(p.getRawType()==List.class){var list=new ArrayList<>();for(var x:node)list.add(decode(x,p.getActualTypeArguments()[0]));return List.copyOf(list);}
            if(p.getRawType()==Map.class){var map=new LinkedHashMap<>();for(var e:node){keys(e,"key","value");var k=decode(e.get("key"),p.getActualTypeArguments()[0]);var v=decode(e.get("value"),p.getActualTypeArguments()[1]);if(k==null || v==null || map.putIfAbsent(k,v)!=null)throw invalid();}return Map.copyOf(map);}
            throw invalid();
        }
        var c=(Class<?>)type;
        if(c.isRecord()) {
            if(!RECORDS.contains(c))throw invalid();if(node.isNull())return null;
            var components=c.getRecordComponents();keys(node,Arrays.stream(components).map(RecordComponent::getName).toArray(String[]::new));
            Object[] args=new Object[components.length];for(int i=0;i<args.length;i++)args[i]=decode(node.get(components[i].getName()),components[i].getGenericType());
            try{return c.getDeclaredConstructor(Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new)).newInstance(args);}
            catch(ReflectiveOperationException e){throw invalid();}
        }
        keys(node,"type","value");if(!node.get("type").isString() || !tag(c).equals(node.get("type").stringValue()))throw invalid();
        var v=node.get("value");if(v.isNull()){if(c.isPrimitive())throw invalid();return null;}
        if(c==Instant.class){keys(v,"epochSecond","nano");String s=text(v.get("epochSecond"));integer(s);var n=v.get("nano");if(!n.isIntegralNumber() || !n.canConvertToInt() || n.intValue()<0 || n.intValue()>999999999)throw invalid();return Instant.ofEpochSecond(Long.parseLong(s),n.intValue());}
        String s=text(v);
        if(c==String.class)return s;if(c==UUID.class){var uuid=UUID.fromString(s);if(!uuid.toString().equals(s))throw invalid();return uuid;}
        if(c==Long.class || c==long.class){integer(s);return Long.valueOf(s);}
        if(c==Integer.class || c==int.class){integer(s);return Integer.valueOf(s);}
        if(c==Short.class || c==short.class){integer(s);return Short.valueOf(s);}
        if(c==LocalDateTime.class)return LocalDateTime.parse(s,LOCAL);
        if(c==Bytes.class){byte[] bytes=Base64.getDecoder().decode(s);if(!Base64.getEncoder().encodeToString(bytes).equals(s))throw invalid();return new Bytes(bytes);}
        if(c.isEnum())return enumValue(c,s);throw invalid();
    }
    @SuppressWarnings({"unchecked","rawtypes"}) private Object enumValue(Class<?> c,String s){return Enum.valueOf((Class)c,s);}
    private String text(JsonNode n){if(!n.isString())throw invalid();return n.stringValue();}
    private void integer(String s){if(!s.matches("0|-?[1-9][0-9]*"))throw invalid();}
    void rawLimit(PaymentSafetyReadFacts facts){
        PaymentSafetyRawInputLimit.requireWithinReaderLimit(facts);
    }
    static void verifyDigest(String actual,String expected) {
        if(expected==null || !expected.matches("[0-9a-f]{64}") || !MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),expected.getBytes(StandardCharsets.US_ASCII)))throw invalid();
    }
    static String digest(String domain,Object record) {
        var engine=new PayloadEncodingV1(PaymentSafetyPayloadCodecV1.Limits.defaults());
        return engine.safe(()->{if(record instanceof AssessmentRecordDigestV1.Record r)engine.rawLimit(r.facts());return engine.hash(domain,engine.encode(record,record.getClass()));});
    }
    String eventDigest(AuditEventDigestV1.Record event) {
        return safe(()->{var node=(ObjectNode)encode(event,AuditEventDigestV1.Record.class);node.set("payloadJson",parse(event.payloadJson()));return hash("ec-site/payment-safety/audit-event",node);});
    }
    private String hash(String domain,JsonNode node) {
        checkTree(node,0,new long[]{0});
        try {
            var digest=MessageDigest.getInstance("SHA-256");var out=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest));
            canonical(out,StringNode.valueOf(domain));out.writeInt(1);canonical(out,node);out.flush();return HexFormat.of().formatHex(digest.digest());
        }catch(IOException | NoSuchAlgorithmException e){throw new IllegalStateException("Cannot digest payment safety record");}
    }
    /** Type tags + lengths; object keys sorted, arrays ordered, duplicates retained. No JSON text hashing. */
    private void canonical(DataOutputStream out,JsonNode n)throws IOException {
        if(n.isNull()){out.writeByte(0);}
        else if(n.isString()){out.writeByte(1);byte[] b=n.stringValue().getBytes(StandardCharsets.UTF_8);out.writeInt(b.length);out.write(b);}
        else if(n.isBoolean()){out.writeByte(2);out.writeBoolean(n.booleanValue());}
        else if(n.isNumber()){out.writeByte(3);canonical(out,StringNode.valueOf(n.decimalValue().stripTrailingZeros().toPlainString()));}
        else if(n.isArray()){out.writeByte(5);out.writeInt(n.size());for(var x:n)canonical(out,x);}
        else if(n.isObject()){out.writeByte(6);out.writeInt(n.size());for(var k:new TreeSet<>(n.propertyNames())){canonical(out,StringNode.valueOf(k));canonical(out,n.get(k));}}
        else throw invalid();
    }
}
