package com.example.ecsite.payment;

import com.example.ecsite.service.payment.PaymentSafetyStopException;

public final class PaymentSafetyMessages {
    private PaymentSafetyMessages() {}
    public static String customer(Long orderId, RuntimeException failure) {
        if (failure instanceof PaymentSafetyStopException) return failure.getMessage();
        // Internal validation / already-finalized operations are not provider uncertainty.
        if (failure instanceof IllegalStateException) return failure.getMessage();
        return "決済状態を確認中です。決済結果は不明であり、失敗確定ではありません。"
                + "安全のため決済リクエストの再送を停止しています。管理者確認が必要な場合があります。"
                + "解消しない場合は注文番号「" + orderId + "」を添えてお問い合わせください。";
    }
    public static String administrator(Long orderId, RuntimeException failure) {
        return customer(orderId, failure) + "注文・決済不整合一覧で確認してください。決済IDがない場合は自動再開できません。";
    }
}
