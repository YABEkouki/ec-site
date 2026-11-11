package com.example.ecsite.service.order.evidence;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;
import static com.example.ecsite.service.order.evidence.OrderPaymentEvidence.*;

/** Independent of Feature124 hashes. Every field is framed with a signed big-endian int32 byte
 * length (-1 = null), followed by UTF-8 strings, int32, int64, or raw 32-byte hashes.
 * UUIDs use lowercase standard UTF-8 form. Nested receipts are framed field streams too.
 * Domain/order below are the V1 wire contract; the stored digest itself is never an input. */
public final class OrderEvidenceDigests {
    private static final OrderContentSnapshotCodec CODEC = new OrderContentSnapshotCodec();
    private OrderEvidenceDigests() {}
    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }

    public static String epoch(Epoch e) {
        return sha256(encode(w -> {
            w.text("ec-site/order-evidence/epoch/v1"); w.integer(e.version()); w.number(e.orderId());
            w.uuid(e.id()); w.number(e.paymentId()); w.number(e.authorizationId()); w.text(e.origin().name());
            w.integer(e.authorizedAmount()); w.integer(e.authorizationRevision());
            w.integer(e.start().contentRevision()); w.integer(e.start().totalAmount());
            w.hash(CODEC.fingerprint(e.start()));
            w.field(e.reauthorization()==null?null:encode(r -> {
                var p=e.reauthorization();r.uuid(p.operationId());r.number(p.orderId());r.number(p.oldPaymentId());
                r.number(p.newPaymentId());r.number(p.authorizationId());r.number(p.historyId());r.integer(p.baseRevision());
                r.integer(p.afterAmount());r.text(p.state());r.number(p.oldCancelId());r.text(p.oldCancelStatus());
                r.integer(p.oldCancelAmount());r.integer(p.oldCancelRevision());r.text(p.oldCancelFlow());
            }));
            w.field(e.checkpoint()==null?null:encode(r -> {
                var p=e.checkpoint();r.number(p.orderId());r.number(p.paymentId());r.number(p.authorizationId());
                r.integer(p.revision());r.hash(p.contentFingerprint());r.hash(p.localAssessmentDigest());r.hash(p.externalObservationDigest());
            }));
        }));
    }

    public static String change(Change c) {
        return sha256(encode(w -> {
            w.text("ec-site/order-evidence/change/v1");w.integer(c.version());w.number(c.orderId());w.uuid(c.epochId());
            w.number(c.id());w.uuid(c.operationId());
            if(c.previousHistoryId()==null)w.field(null);else w.number(c.previousHistoryId());
            w.integer(c.beforeRevision());w.integer(c.after().contentRevision());w.integer(c.beforeAmount());w.integer(c.after().totalAmount());
            w.hash(c.beforeFingerprint());w.hash(CODEC.fingerprint(c.after()));w.hash(c.previousDigest());
        }));
    }

    static boolean validHash(String value) { return value!=null && value.matches("[0-9a-f]{64}"); }
    static boolean matches(String expected, String actual) {
        return validHash(expected) && validHash(actual) && MessageDigest.isEqual(HexFormat.of().parseHex(expected),HexFormat.of().parseHex(actual));
    }
    private static byte[] encode(Consumer<Writer> action) { var w=new Writer();action.accept(w);return w.bytes.toByteArray(); }
    private static final class Writer {
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        void field(byte[] value) {
            try { var out=new DataOutputStream(bytes);out.writeInt(value==null?-1:value.length);if(value!=null)out.write(value); }
            catch(IOException e) { throw new IllegalStateException("Cannot encode evidence",e); }
        }
        void integer(int n) { field(ByteBuffer.allocate(4).putInt(n).array()); }
        void number(long n) { field(ByteBuffer.allocate(8).putLong(n).array()); }
        void text(String s) { field(s==null?null:s.getBytes(StandardCharsets.UTF_8)); }
        void uuid(UUID id) { text(id==null?null:id.toString()); }
        void hash(String hash) { if(!validHash(hash))throw new IllegalArgumentException("Invalid evidence hash");field(HexFormat.of().parseHex(hash)); }
    }
}
