package com.example.ecsite.payment.capture;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;

/** Immutable HTTP data, not an authorization to send or replay a Capture POST.
 * V1 fingerprint: each UTF-8 field, then body, prefixed by a four-byte big-endian length.
 * Field order: fingerprint version, body version, connection, account, mode, origin,
 * Flow ID, method, path, Content-Type, decimal amount, idempotency key, body.
 * Credentials and transport-generated headers are deliberately excluded.
 */
public final class CaptureHttpRequest {
    private final CaptureConnectionIdentity connection;
    private final String flowId;
    private final int amount;
    private final String idempotencyKey;
    private final byte[] body;
    private final byte[] fingerprint;

    private CaptureHttpRequest(CaptureConnectionIdentity connection, String flowId, int amount, String idempotencyKey) {
        if (connection == null || flowId == null || flowId.length() > 255 || !flowId.matches("pfw_[A-Za-z0-9_-]+")
                || amount <= 0 || idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new IllegalArgumentException("Invalid Capture request identity or amount");
        }
        this.connection = connection;
        this.flowId = flowId;
        this.amount = amount;
        this.idempotencyKey = idempotencyKey;
        this.body = ("{\"amount_to_capture\":" + amount + "}").getBytes(StandardCharsets.UTF_8);
        this.fingerprint = calculateFingerprint();
    }
    public static CaptureHttpRequest create(CaptureConnectionIdentity connection, String flowId, int amount, String key) {
        return new CaptureHttpRequest(connection, flowId, amount, key);
    }
    /** Validate every persisted HTTP field before exposing any request bytes. */
    public static CaptureHttpRequest restore(CaptureConnectionIdentity connection, String flowId, int amount, String key,
            String method, String path, String contentType, int bodyVersion, byte[] storedBody,
            int fingerprintVersion, byte[] storedFingerprint) {
        var request = create(connection, flowId, amount, key);
        if (bodyVersion != 1 || fingerprintVersion != 1 || !request.method().equals(method)
                || !request.path().equals(path) || !request.contentType().equals(contentType)
                || !Arrays.equals(request.body, storedBody) || storedFingerprint == null
                || !MessageDigest.isEqual(request.fingerprint, storedFingerprint)) {
            throw new IllegalArgumentException("Stored Capture request integrity check failed");
        }
        return new CaptureHttpRequest(request, storedBody, storedFingerprint);
    }
    private CaptureHttpRequest(CaptureHttpRequest validated, byte[] storedBody, byte[] storedFingerprint) {
        this.connection = validated.connection; this.flowId = validated.flowId; this.amount = validated.amount;
        this.idempotencyKey = validated.idempotencyKey;
        this.body = storedBody.clone(); this.fingerprint = storedFingerprint.clone();
    }
    private byte[] calculateFingerprint() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String value : List.of("1", "1", connection.connectionId(), connection.providerAccountRef(),
                    connection.mode(), connection.apiOrigin(), flowId, method(), path(), contentType(),
                    Integer.toString(amount), idempotencyKey)) {
                add(digest, value.getBytes(StandardCharsets.UTF_8));
            }
            add(digest, body);
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
    private static void add(MessageDigest digest, byte[] bytes) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
    public CaptureConnectionIdentity connection() { return connection; }
    public String flowId() { return flowId; }
    public int amount() { return amount; }
    public String idempotencyKey() { return idempotencyKey; }
    public String method() { return "POST"; }
    public String path() { return "/v2/payment_flows/" + flowId + "/capture"; }
    public String contentType() { return "application/json"; }
    public int bodyFormatVersion() { return 1; }
    public int fingerprintVersion() { return 1; }
    public byte[] body() { return body.clone(); }
    public byte[] fingerprint() { return fingerprint.clone(); }
    @Override public String toString() { return "CaptureHttpRequest[redacted]"; }
}
