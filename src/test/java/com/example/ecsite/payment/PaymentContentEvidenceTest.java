package com.example.ecsite.payment;

import java.util.*;
import org.junit.jupiter.api.Test;
import com.example.ecsite.entity.*;
import com.example.ecsite.service.order.evidence.*;
import com.example.ecsite.service.payment.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentContentEvidenceTest {
    @Test void verifiedContentBindsCurrentOrderPaymentAuthorizationAndGeneration(){
        UUID epoch=UUID.randomUUID();
        var snapshot=new OrderContentSnapshot(1,1,2,2,List.of(),List.of(),5000,0,454,5000);
        var savedPayment=new OrderPaymentEvidence.Payment(10,1,"PAYJP","CARD","AUTHORIZED",6000,"pfw_test");
        var savedAuth=new OrderPaymentEvidence.Authorization(20,1,10,"AUTHORIZE","SUCCEEDED",6000,0,"pfw_test","INITIAL_ORDER_AUTHORIZATION",null);
        var savedEpoch=new OrderPaymentEvidence.Epoch(1,epoch,1,10,20,6000,0,OrderPaymentEvidence.Origin.INITIAL_AUTH,snapshot,null,null,null);
        var proof=new PaymentContentEvidence(PaymentContentEvidence.State.VERIFIED,new OrderPaymentEvidence.Facts(snapshot,10L,List.of(savedPayment),List.of(savedAuth),true,savedEpoch,List.of(),31L,false,false));
        var order=mock(Order.class);when(order.getId()).thenReturn(1L);when(order.getUserId()).thenReturn(2L);
        when(order.getContentRevision()).thenReturn(2);when(order.getItemSubtotal()).thenReturn(5000);when(order.getTotalAmount()).thenReturn(5000);when(order.getTaxAmount()).thenReturn(454);
        when(order.getCurrentEvidenceEpochId()).thenReturn(epoch);when(order.getEvidenceHeadHistoryId()).thenReturn(31L);when(order.getActivePaymentId()).thenReturn(10L);
        var payment=mock(Payment.class);when(payment.getId()).thenReturn(10L);when(payment.getOrder()).thenReturn(order);when(payment.getAmount()).thenReturn(6000);
        when(payment.getProvider()).thenReturn(PaymentProvider.PAYJP);when(payment.getPaymentMethod()).thenReturn(PaymentMethod.CARD);when(payment.getProviderPaymentId()).thenReturn("pfw_test");
        var auth=mock(PaymentTransaction.class);when(auth.getId()).thenReturn(20L);when(auth.getPayment()).thenReturn(payment);when(auth.getAmount()).thenReturn(6000);
        when(auth.getTransactionType()).thenReturn(PaymentTransactionType.AUTHORIZE);when(auth.getStatus()).thenReturn(PaymentTransactionStatus.SUCCEEDED);
        when(auth.getProviderTransactionId()).thenReturn("pfw_test");when(auth.getPurpose()).thenReturn(PaymentTransactionPurpose.INITIAL_ORDER_AUTHORIZATION);
        var evaluator=new PaymentConsistencyEvaluator();
        assertThat(evaluator.evaluate(order,payment,auth,proof).status()).isEqualTo(PaymentConsistencyStatus.EVIDENCE_VERIFIED);
        assertThat(evaluator.evaluate(order,payment,auth,proof).canCapture()).isTrue();
        assertThat(evaluator.evaluate(order,payment,auth).canCapture()).isFalse();
        when(auth.getProviderTransactionId()).thenReturn("pfw_other");
        assertThat(evaluator.evaluate(order,payment,auth,proof).canCapture()).isFalse();
        when(auth.getProviderTransactionId()).thenReturn("pfw_test");when(order.getEvidenceHeadHistoryId()).thenReturn(32L);
        assertThat(evaluator.evaluate(order,payment,auth,proof).canCapture()).isFalse();
        when(order.getEvidenceHeadHistoryId()).thenReturn(31L);when(order.getCurrentEvidenceEpochId()).thenReturn(UUID.randomUUID());
        assertThat(evaluator.evaluate(order,payment,auth,proof).canCapture()).isFalse();
    }
}
