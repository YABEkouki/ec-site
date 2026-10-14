package com.example.ecsite.payment;

import static org.assertj.core.api.Assertions.*;
import com.example.ecsite.service.payment.PaymentSafetyStopException;
import org.junit.jupiter.api.Test;

class PaymentSafetyMessagesTest {
    @Test void confirmedAuthorizationDoesNotBecomeAnUnknownOutcomeMessage() {
        String message="カード与信は確定済みです。注文詳細を確認してください。";
        assertThat(PaymentSafetyMessages.customer(10L, new IllegalStateException(message)))
                .isEqualTo(message).doesNotContain("結果は不明");
    }
    @Test void missingProviderHasExplicitStopAndOrderContact() {
        var failure=new PaymentSafetyStopException(PaymentSafetyStopException.Reason.PROVIDER_ID_MISSING,10L);
        assertThat(PaymentSafetyMessages.customer(10L,failure))
                .contains("自動再開できません", "失敗確定ではありません", "再送を停止", "管理者確認", "注文番号「10」");
    }
    @Test void administratorUnknownMessageHasExistingOperationalRoute() {
        assertThat(PaymentSafetyMessages.administrator(10L,new PaymentGatewayException("private response")))
                .contains("決済不整合一覧", "注文番号「10」", "失敗確定ではありません").doesNotContain("private response");
    }
}
