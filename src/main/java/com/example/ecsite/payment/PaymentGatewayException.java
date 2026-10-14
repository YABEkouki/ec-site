package com.example.ecsite.payment;

/** Never retains raw HTTP responses or transport exception causes. */
public class PaymentGatewayException extends RuntimeException {
    private final PaymentGatewayErrorCategory category;
    private final Integer httpStatus;
    private final String providerErrorCode;
    private final boolean resultUnknown;

    public PaymentGatewayException(String message) {
        this(PaymentGatewayErrorCategory.TRANSPORT_RESULT_UNKNOWN, null, null, true);
    }
    public PaymentGatewayException(String message, Throwable cause) {
        this(message);
    }
    public PaymentGatewayException(PaymentGatewayErrorCategory category, Integer httpStatus,
            String providerErrorCode, boolean resultUnknown) {
        super("PAY.JP operation stopped: category=" + category + ", status=" + httpStatus);
        this.category = category;
        this.httpStatus = httpStatus;
        this.providerErrorCode = providerErrorCode;
        this.resultUnknown = resultUnknown;
    }
    public PaymentGatewayErrorCategory category() { return category; }
    public Integer httpStatus() { return httpStatus; }
    public String providerErrorCode() { return providerErrorCode; }
    public boolean resultUnknown() { return resultUnknown; }
}
