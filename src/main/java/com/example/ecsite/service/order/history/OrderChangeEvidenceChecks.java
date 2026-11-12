package com.example.ecsite.service.order.history;

import java.time.*;
import java.security.*;
import java.util.*;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceBundle.*;
import static com.example.ecsite.service.order.history.OrderChangeHistoryVerificationResult.*;

/** Strict accessors: missing columns and SQL NULL are separate contracts. */
final class OrderChangeEvidenceChecks {
    static final class Invalid extends RuntimeException {
        final Reason reason;
        Invalid(Reason reason){super(reason.name());this.reason=reason;}
    }
    static void check(boolean condition,Reason reason){if(!condition)throw new Invalid(reason);}
    static String nullable(EvidenceRow row,String key){check(row.values().containsKey(key),Reason.ACQUISITION_CONTRACT);return row.value(key);}
    static String text(EvidenceRow row,String key){var v=nullable(row,key);check(v!=null,Reason.ACQUISITION_CONTRACT);return v;}
    static long number(EvidenceRow row,String key){try{return Long.parseLong(text(row,key));}catch(NumberFormatException e){throw new Invalid(Reason.UNKNOWN_FORMAT);}}
    static int integer(EvidenceRow row,String key){try{return Math.toIntExact(number(row,key));}catch(ArithmeticException e){throw new Invalid(Reason.UNKNOWN_FORMAT);}}
    static UUID uuid(EvidenceRow row,String key){try{var s=text(row,key);var u=UUID.fromString(s);check(u.toString().equals(s),Reason.UNKNOWN_FORMAT);return u;}catch(IllegalArgumentException e){throw new Invalid(Reason.UNKNOWN_FORMAT);}}
    static byte[] optionalBytes(EvidenceRow row,String key){check(row.binaries().containsKey(key),Reason.ACQUISITION_CONTRACT);var v=row.binaries().get(key);return v==null?null:v.bytes();}
    static byte[] bytes(EvidenceRow row,String key){var b=optionalBytes(row,key);check(b!=null,Reason.ACQUISITION_CONTRACT);return b;}
    static Instant instant(EvidenceRow row,String key){try{return OffsetDateTime.parse(text(row,key)).toInstant();}catch(DateTimeException e){throw new Invalid(Reason.UNKNOWN_FORMAT);}}
    static String hex(byte[] bytes){return HexFormat.of().formatHex(bytes);}
    static byte[] hash(byte[] value){try{return MessageDigest.getInstance("SHA-256").digest(value);}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    static void digest(byte[] data,byte[] expected,Reason reason){check(expected.length==32&&MessageDigest.isEqual(hash(data),expected),reason);}
    static void equal(Object a,Object b,Reason reason){check(Objects.equals(a,b),reason);}
    static boolean bool(EvidenceRow row,String key){var s=text(row,key);check(Set.of("t","f","true","false").contains(s),Reason.UNKNOWN_FORMAT);return s.equals("t")||s.equals("true");}
    static long micros(Instant i){return Math.addExact(Math.multiplyExact(i.getEpochSecond(),1_000_000L),i.getNano()/1000);}
    private OrderChangeEvidenceChecks(){}
}
