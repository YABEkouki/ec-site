package com.example.ecsite.service.payment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.repository.*;
class PaymentOperationSnapshotServiceTest {
    PaymentRepository payments=mock(PaymentRepository.class);
    PaymentTransactionRepository transactions=mock(PaymentTransactionRepository.class);
    PaymentOperationSnapshotService snapshots=new PaymentOperationSnapshotService(transactions,payments);
    @ParameterizedTest @EnumSource(value=PaymentTransactionType.class,names={"AUTHORIZE","CAPTURE","CANCEL"})
    void fixesIdentityRequestAndSavedActor(PaymentTransactionType type) {
        var payment=payment(type);var operation=operation(payment,type);
        when(transactions.findByPaymentIdOrderByCreatedAtAscIdAsc(20L)).thenReturn(List.of(operation));
        var actual=snapshots.pendingForProvider("pf",type).orElseThrow();
        assertThat(actual).isEqualTo(PaymentOperationSnapshot.from(operation));
        assertThat(actual.transactionId()).isEqualTo(100L);assertThat(actual.orderContentRevision()).isEqualTo(2);
        assertThat(actual.idempotencyKey()).isEqualTo("saved-key");
    }
    @Test void missingProviderSkipsDatabase() {
        assertThat(snapshots.pendingForProvider(null,PaymentTransactionType.AUTHORIZE)).isEmpty();
        assertThat(snapshots.pendingForProvider(" ",PaymentTransactionType.AUTHORIZE)).isEmpty();verifyNoInteractions(payments,transactions);
    }
    @Test void ambiguousPendingIsRejectedBeforeGet() {
        var p=payment(PaymentTransactionType.CAPTURE);
        when(transactions.findByPaymentIdOrderByCreatedAtAscIdAsc(20L)).thenReturn(List.of(operation(p,PaymentTransactionType.CAPTURE),operation(p,PaymentTransactionType.CAPTURE)));
        assertThatThrownBy(()->snapshots.pendingForProvider("pf",PaymentTransactionType.CAPTURE)).isInstanceOf(IllegalStateException.class);
    }
    @Test void terminalPaymentAndCompletedTransactionAreNotCandidates() {
        var p=payment(PaymentTransactionType.CAPTURE);p.markCaptured(LocalDateTime.now());
        assertThat(snapshots.pendingForProvider("pf",PaymentTransactionType.CAPTURE)).isEmpty();verifyNoInteractions(transactions);
    }
    @Test void completedOrDifferentOperationIsNotSelected() {
        var p=payment(PaymentTransactionType.CAPTURE);var auth=operation(p,PaymentTransactionType.AUTHORIZE);auth.markSucceeded(null,LocalDateTime.now());
        when(transactions.findByPaymentIdOrderByCreatedAtAscIdAsc(20L)).thenReturn(List.of(auth,operation(p,PaymentTransactionType.CANCEL)));
        assertThat(snapshots.pendingForProvider("pf",PaymentTransactionType.CAPTURE)).isEmpty();
    }

    @Test void checkoutTargetPinsOnlyTheInitialActiveAuthorization(){
        var p=checkoutPayment();var t=checkoutAuth(p);
        assertThat(snapshots.checkoutTarget(10,20,1)).contains(new PaymentOperationSnapshotService.CheckoutTarget(10,20,100));
        p.markAuthorized(LocalDateTime.now());t.markSucceeded("pf",LocalDateTime.now());
        assertThat(snapshots.checkoutTarget(10,20,1)).isPresent();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"owner","order","active","amount","revision","purpose","provider","missingProvider","ambiguous","pendingOther","failed","cancelled","operation","unknown","competingPayment"})
    void checkoutEligibilityFailsClosed(String reason){
        var p=checkoutPayment();var t=checkoutAuth(p);
        switch(reason){
            case "owner" -> {assertThat(snapshots.checkoutTarget(10,20,999)).isEmpty();return;}
            case "order" -> {assertThat(snapshots.checkoutTarget(99,20,1)).isEmpty();return;}
            case "active" -> ReflectionTestUtils.setField(p.getOrder(),"activePaymentId",99L);
            case "amount" -> ReflectionTestUtils.setField(p,"amount",3301);
            case "revision" -> ReflectionTestUtils.setField(t,"orderContentRevision",1);
            case "purpose" -> ReflectionTestUtils.setField(t,"purpose",PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION);
            case "provider" -> ReflectionTestUtils.setField(p,"provider",PaymentProvider.MOCK);
            case "missingProvider" -> ReflectionTestUtils.setField(p,"providerPaymentId",null);
            case "ambiguous" -> when(transactions.findByPaymentIdOrderByCreatedAtAscIdAsc(20L)).thenReturn(List.of(t,t));
            case "pendingOther" -> {var other=operation(p,PaymentTransactionType.CANCEL);ReflectionTestUtils.setField(other,"id",101L);when(transactions.findByPaymentIdOrderByCreatedAtAscIdAsc(20L)).thenReturn(List.of(t,other));}
            case "failed" -> t.markFailed("pf","declined","declined",LocalDateTime.now());
            case "cancelled" -> ReflectionTestUtils.setField(p.getOrder(),"status",OrderStatus.CANCELLED);
            case "operation" -> ReflectionTestUtils.setField(t,"orderChangeOperationId",UUID.randomUUID());
            case "competingPayment" -> {var other=new Payment(p.getOrder(),PaymentProvider.PAYJP,PaymentMethod.CARD,3300,LocalDateTime.now());ReflectionTestUtils.setField(other,"id",21L);when(payments.findByOrderIdOrderByCreatedAtAscIdAsc(10L)).thenReturn(List.of(p,other));}
            case "unknown" -> when(payments.findById(20L)).thenReturn(Optional.empty());
        }
        assertThat(snapshots.checkoutTarget(10,20,1)).isEmpty();
    }
    private Payment checkoutPayment(){
        var now=LocalDateTime.now();var o=new Order(1L,3300,now,now.plusDays(1));ReflectionTestUtils.setField(o,"id",10L);
        var p=new Payment(o,PaymentProvider.PAYJP,PaymentMethod.CARD,3300,now);ReflectionTestUtils.setField(p,"id",20L);
        p.setProviderPaymentId("pf",now);o.bindActivePayment(p);when(payments.findById(20L)).thenReturn(Optional.of(p));when(payments.findByOrderIdOrderByCreatedAtAscIdAsc(10L)).thenReturn(List.of(p));return p;
    }
    private PaymentTransaction checkoutAuth(Payment p){
        var t=new PaymentTransaction(p,PaymentTransactionType.AUTHORIZE,3300,0,"saved-key",LocalDateTime.now(),PaymentTransactionPurpose.INITIAL_ORDER_AUTHORIZATION);
        ReflectionTestUtils.setField(t,"id",100L);when(transactions.findByPaymentIdOrderByCreatedAtAscIdAsc(20L)).thenReturn(List.of(t));return t;
    }
    private Payment payment(PaymentTransactionType type) {
        LocalDateTime now=LocalDateTime.now();Order o=new Order(1L,3300,now,now.plusDays(1));ReflectionTestUtils.setField(o,"id",10L);
        Payment p=new Payment(o,PaymentProvider.PAYJP,PaymentMethod.CARD,3300,now);ReflectionTestUtils.setField(p,"id",20L);p.setProviderPaymentId("pf",now);
        if(type!=PaymentTransactionType.AUTHORIZE)p.markAuthorized(now);
        when(payments.findByProviderAndProviderPaymentId(PaymentProvider.PAYJP,"pf")).thenReturn(Optional.of(p));return p;
    }
    private PaymentTransaction operation(Payment p,PaymentTransactionType type) {
        var t=new PaymentTransaction(p,type,3300,2,"saved-key",LocalDateTime.now(),new PaymentTransactionInitiator(PaymentTransactionInitiatorType.ADMIN,30L,"saved","memo"));
        ReflectionTestUtils.setField(t,"id",100L);return t;
    }
}
