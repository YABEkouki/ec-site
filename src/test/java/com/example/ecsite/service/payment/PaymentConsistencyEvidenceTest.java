package com.example.ecsite.service.payment;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.example.ecsite.entity.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentConsistencyEvidenceTest {
    @Test void enrolledEqualAmountAndDecreasedAmountCannotUseLegacyFallbackWithoutProof(){
        var order=new Order();ReflectionTestUtils.setField(order,"totalAmount",5000);
        ReflectionTestUtils.setField(order,"currentEvidenceEpochId",UUID.randomUUID());
        var payment=mock(Payment.class);var authorization=mock(PaymentTransaction.class);
        when(payment.getAmount()).thenReturn(6000);when(authorization.getAmount()).thenReturn(6000);
        var evaluator=new PaymentConsistencyEvaluator();
        assertThat(evaluator.evaluate(order,payment,authorization).canCapture()).isFalse();
        ReflectionTestUtils.setField(order,"totalAmount",6000);
        assertThat(evaluator.evaluate(order,payment,authorization).canCapture()).isFalse();
        ReflectionTestUtils.setField(order,"currentEvidenceEpochId",null);
        ReflectionTestUtils.setField(order,"evidenceHeadHistoryId",0L);
        assertThat(evaluator.evaluate(order,payment,authorization).canCapture()).isFalse();
    }
}
