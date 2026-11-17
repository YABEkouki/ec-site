package com.example.ecsite.form;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Feature133-C: manual resend request (input -> confirm -> submit). */
public class AdminCustomerNotificationResendForm {
    @NotBlank(message = "再送理由を入力してください。")
    @Size(max = 500, message = "再送理由は500文字以内で入力してください。")
    private String reason;
    private boolean riskAcknowledged;
    /** Fixed when the input form is first shown; a resubmitted form never records a second request. */
    private String nonce;
    /** The resend count the administrator saw; a change meanwhile rejects the request. */
    private int expectedResendCount;

    @AssertTrue(message = "画面を開き直してから、もう一度操作してください。")
    public boolean isNonceValid() {
        try { java.util.UUID.fromString(nonce); return true; }
        catch (RuntimeException invalid) { return false; }
    }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public boolean isRiskAcknowledged() { return riskAcknowledged; }
    public void setRiskAcknowledged(boolean riskAcknowledged) { this.riskAcknowledged = riskAcknowledged; }
    public String getNonce() { return nonce; }
    public void setNonce(String nonce) { this.nonce = nonce; }
    public int getExpectedResendCount() { return expectedResendCount; }
    public void setExpectedResendCount(int expectedResendCount) { this.expectedResendCount = expectedResendCount; }
}
