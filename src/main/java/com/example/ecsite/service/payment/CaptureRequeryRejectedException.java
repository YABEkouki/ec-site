package com.example.ecsite.service.payment;

/** An identified operation is not safe to query automatically. No provider/DB details are exposed. */
public class CaptureRequeryRejectedException extends IllegalStateException {
    public CaptureRequeryRejectedException() {
        super("売上確定結果を安全に再照会できません。");
    }
}
