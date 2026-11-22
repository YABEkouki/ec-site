package com.example.ecsite.payment.savedcard;

/** Feature139: a failed PAY.JP call. Never carries response bodies, messages or client secrets. */
public class SavedCardGatewayException extends RuntimeException {

    public enum Kind {
        /** PAY.JP answered 4xx: it decided (the request had no effect unless the code says otherwise). */
        REJECTED,
        /** Timeout, connection failure, 5xx, idempotency wait/conflict or an unreadable response. */
        UNKNOWN
    }

    private final Kind kind;
    private final Integer httpStatus;
    private final String code;

    public SavedCardGatewayException(Kind kind, Integer httpStatus, String code) {
        super("PAY.JP saved card call failed: " + kind + (httpStatus == null ? "" : " " + httpStatus)
                + (code == null ? "" : " " + code));
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public Kind kind() { return kind; }
    public Integer httpStatus() { return httpStatus; }
    public String code() { return code; }
}
