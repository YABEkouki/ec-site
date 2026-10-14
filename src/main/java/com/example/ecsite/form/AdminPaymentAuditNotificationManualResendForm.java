package com.example.ecsite.form;

import jakarta.validation.constraints.AssertTrue;
import com.example.ecsite.service.PaymentAuditNotificationHandlingComments;

public class AdminPaymentAuditNotificationManualResendForm {
    private String reason;
    private String snapshotToken;
    private boolean riskAcknowledged;
    @AssertTrue(message="再送理由は空白だけにせず、1,000文字以内で入力してください。")
    public boolean isReasonValid(){try{PaymentAuditNotificationHandlingComments.validate(reason,true);return true;}catch(IllegalArgumentException e){return false;}}
    public String getReason(){return reason;}
    public void setReason(String v){reason=v;}
    public String getSnapshotToken(){return snapshotToken;}
    public void setSnapshotToken(String v){snapshotToken=v;}
    public boolean isRiskAcknowledged(){return riskAcknowledged;}
    public void setRiskAcknowledged(boolean v){riskAcknowledged=v;}
}
