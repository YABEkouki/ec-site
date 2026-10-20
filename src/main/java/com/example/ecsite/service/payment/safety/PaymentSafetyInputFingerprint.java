package com.example.ecsite.service.payment.safety;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.security.*;
import java.time.*;
import java.util.*;
import com.example.ecsite.service.payment.safety.PaymentSafetyReadFacts.Bytes;

/** Versioned canonical input encoding, independent of V54's HTTP request fingerprint.
 * Scalars have type + byte length, containers type + count; record component declaration order
 * and names/types are part of v1. Row collections are multisets sorted by full encoded bytes:
 * permutations do not matter, duplicates do. Maps sort encoded keys. Never stringifies unknown types.
 * Changing the model/encoding requires a format version change before persisted assessments exist.
 */
public final class PaymentSafetyInputFingerprint {
    public static final int FORMAT_VERSION=1;
    public static final String DOMAIN="ec-site/payment-safety/raw-input";
    private PaymentSafetyInputFingerprint() {}

    public static String sha256(PaymentSafetyReadFacts facts) {
        Objects.requireNonNull(facts);
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            try(var out=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest))) {
                write(out,DOMAIN);write(out,FORMAT_VERSION);write(out,facts);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) { throw new IllegalStateException("Cannot fingerprint input",e); }
    }

    static long encodedSize(Object value) {
        var counter=new OutputStream() {
            long count;
            @Override public void write(int b) { count++; }
            @Override public void write(byte[] b,int off,int len) { count+=len; }
        };
        try { write(new DataOutputStream(counter),value);return counter.count; }
        catch(IOException e) { throw new IllegalStateException(e); }
    }
    private static byte[] encoded(Object value) throws IOException {
        var bytes=new ByteArrayOutputStream();write(new DataOutputStream(bytes),value);return bytes.toByteArray();
    }
    private static void scalar(DataOutputStream out,int tag,int length) throws IOException { out.writeByte(tag);out.writeInt(length); }
    private static byte[] utf8(String value) {
        try {
            ByteBuffer b=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
            byte[] result=new byte[b.remaining()];b.get(result);return result;
        } catch(CharacterCodingException e) { throw new IllegalArgumentException("Malformed Unicode input",e); }
    }
    private static void write(DataOutputStream out,Object value) throws IOException {
        if(value==null) { scalar(out,0,0);return; }
        if(value instanceof String s) { byte[] b=utf8(s);scalar(out,1,b.length);out.write(b); }
        else if(value instanceof Long n) { scalar(out,2,8);out.writeLong(n); }
        else if(value instanceof Integer n) { scalar(out,3,4);out.writeInt(n); }
        else if(value instanceof Short n) { scalar(out,4,2);out.writeShort(n); }
        else if(value instanceof Bytes b) { scalar(out,5,b.length());b.writeTo(out); }
        else if(value instanceof LocalDateTime t) {
            scalar(out,6,28);out.writeInt(t.getYear());out.writeInt(t.getMonthValue());out.writeInt(t.getDayOfMonth());
            out.writeInt(t.getHour());out.writeInt(t.getMinute());out.writeInt(t.getSecond());out.writeInt(t.getNano());
        } else if(value instanceof Instant t) { scalar(out,7,12);out.writeLong(t.getEpochSecond());out.writeInt(t.getNano()); }
        else if(value instanceof Enum<?> e) { out.writeByte(8);write(out,e.getDeclaringClass().getName());write(out,e.name()); }
        else if(value.getClass().isRecord()) {
            out.writeByte(9);write(out,value.getClass().getName());
            var components=value.getClass().getRecordComponents();out.writeInt(components.length);
            for(var c:components) {
                write(out,c.getName());write(out,c.getType().getName());
                try { write(out,c.getAccessor().invoke(value)); }
                catch(ReflectiveOperationException e) { throw new IllegalArgumentException("Cannot encode record",e); }
            }
        } else if(value instanceof List<?> rows) {
            out.writeByte(10);out.writeInt(rows.size());var sorted=new ArrayList<byte[]>();
            for(var row:rows)sorted.add(encoded(row));sorted.sort(Arrays::compareUnsigned);
            for(var bytes:sorted) { out.writeInt(bytes.length);out.write(bytes); }
        } else if(value instanceof Map<?,?> map) {
            out.writeByte(11);out.writeInt(map.size());
            record Entry(byte[] key,Object value) {}
            var entries=new ArrayList<Entry>();for(var e:map.entrySet())entries.add(new Entry(encoded(e.getKey()),e.getValue()));
            entries.sort((a,b)->Arrays.compareUnsigned(a.key(),b.key()));
            for(var e:entries) { out.writeInt(e.key().length);out.write(e.key());write(out,e.value()); }
        } else throw new IllegalArgumentException("Unsupported canonical input type: "+value.getClass().getName());
    }
}
