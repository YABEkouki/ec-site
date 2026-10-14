package com.example.ecsite.service.payment;

/** No raw provider/DB exception is attached to customer-facing stops. */
public class PaymentSafetyStopException extends IllegalStateException {
    public enum Reason { PROVIDER_ID_MISSING, POST_WINDOW_EXPIRED, INVALID_OPERATION_TIME, RESULT_PERSISTENCE_UNKNOWN }
    private final Reason reason;
    public PaymentSafetyStopException(Reason reason, Long orderId) {
        super("決済結果が不明であり、失敗確定ではありません。安全のため再送を停止しました。"
                + (reason == Reason.PROVIDER_ID_MISSING ? "決済IDが未保存のため自動再開できません。" : "")
                + "管理者確認が必要です。注文番号「" + orderId + "」を添えてお問い合わせください。");
        this.reason = reason;
    }
    public Reason reason() { return reason; }
}
