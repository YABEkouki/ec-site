package com.example.ecsite.payment.capture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import com.example.ecsite.entity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StoredCaptureRequestTest {
    final Instant created=Instant.parse("2026-10-19T01:00:00.123456Z");
    CaptureConnectionIdentity identity() { return new CaptureConnectionIdentity("primary","merchant","TEST","https://api.pay.jp"); }
    CaptureHttpRequest http() { return CaptureHttpRequest.create(identity(),"pfw_original",5500,"capture-key"); }
    StoredCaptureRequest stored() { return new StoredCaptureRequest(1,2,3,4,http(),created,created.plus(23,ChronoUnit.HOURS)); }
    Payment payment() {
        var order=mock(com.example.ecsite.entity.Order.class);
        when(order.getId()).thenReturn(9L);when(order.getTotalAmount()).thenReturn(5500);when(order.getContentRevision()).thenReturn(4);
        var payment=mock(Payment.class);when(payment.getId()).thenReturn(2L);when(payment.getOrder()).thenReturn(order);
        when(payment.getProvider()).thenReturn(PaymentProvider.PAYJP);when(payment.getPaymentMethod()).thenReturn(PaymentMethod.CARD);
        when(payment.getProviderPaymentId()).thenReturn("pfw_original");when(payment.getAmount()).thenReturn(6500);
        return payment;
    }
    PaymentTransaction capture(Payment p) {
        var t=mock(PaymentTransaction.class);when(t.getId()).thenReturn(3L);when(t.getPayment()).thenReturn(p);
        when(t.getTransactionType()).thenReturn(PaymentTransactionType.CAPTURE);when(t.getAmount()).thenReturn(5500);
        when(t.getOrderContentRevision()).thenReturn(4);when(t.getIdempotencyKey()).thenReturn("capture-key");return t;
    }
    @Test void validatesIdentityWithoutTreatingOriginalAuthorizationAmountAsCaptureAmount() {
        var p=payment();var t=capture(p);stored().validateAgainst(p,t,identity());
        assertThat(stored().body()).isEqualTo(http().body());
        byte[] b=stored().body();b[0]=0;assertThat(stored().body()).isEqualTo(http().body());
        assertThat(stored().toString()).doesNotContain("capture-key","pfw_original");
    }
    @ParameterizedTest @ValueSource(strings={"payment","transaction","owner","type","provider","method","flow","amount","revision","key","order-amount","order-revision","connection"})
    void rejectsTargetMismatch(String field) {
        var p=payment();var t=capture(p);var expected=identity();
        switch(field) {
            case "payment" -> when(p.getId()).thenReturn(20L);
            case "transaction" -> when(t.getId()).thenReturn(30L);
            case "owner" -> {var other=payment();when(other.getId()).thenReturn(77L);when(t.getPayment()).thenReturn(other);}
            case "type" -> when(t.getTransactionType()).thenReturn(PaymentTransactionType.AUTHORIZE);
            case "provider" -> when(p.getProvider()).thenReturn(null);
            case "method" -> when(p.getPaymentMethod()).thenReturn(null);
            case "flow" -> when(p.getProviderPaymentId()).thenReturn("pfw_other");
            case "amount" -> when(t.getAmount()).thenReturn(5501);
            case "revision" -> when(t.getOrderContentRevision()).thenReturn(5);
            case "key" -> when(t.getIdempotencyKey()).thenReturn("other");
            case "order-amount" -> when(p.getOrder().getTotalAmount()).thenReturn(5501);
            case "order-revision" -> when(p.getOrder().getContentRevision()).thenReturn(5);
            case "connection" -> expected=new CaptureConnectionIdentity("other","merchant","TEST","https://api.pay.jp");
        }
        var target=expected;
        assertThatIllegalArgumentException().isThrownBy(()->stored().validateAgainst(p,t,target));
    }
    @ParameterizedTest @ValueSource(strings={"id","payment","transaction","revision","http","created","equal","long","precision"})
    void rejectsInvalidMetadata(String field) {
        assertThatIllegalArgumentException().isThrownBy(()->new StoredCaptureRequest(
                field.equals("id")?0:1,field.equals("payment")?0:2,field.equals("transaction")?0:3,
                field.equals("revision")?-1:4,field.equals("http")?null:http(),field.equals("created")?null:created,
                field.equals("equal")?created:field.equals("long")?created.plus(24,ChronoUnit.HOURS):
                field.equals("precision")?created.plusSeconds(1).plusNanos(1):created.plusSeconds(1)));
    }
}
