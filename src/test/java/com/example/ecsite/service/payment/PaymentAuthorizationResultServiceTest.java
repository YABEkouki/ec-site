package com.example.ecsite.service.payment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.payment.*;
import com.example.ecsite.repository.OrderRepository;
import com.example.ecsite.service.OrderAcceptanceService;
import com.example.ecsite.service.OrderService;
class PaymentAuthorizationResultServiceTest {
    OrderRepository orders=mock(OrderRepository.class);
    PaymentService payment=mock(PaymentService.class);
    OrderService orderService=mock(OrderService.class);
    Order order=mock(Order.class);
    OrderAcceptanceService acceptance=mock(OrderAcceptanceService.class);
    PaymentAuthorizationResultService service=new PaymentAuthorizationResultService(orders,payment,orderService,acceptance);
    PaymentOperationSnapshot snapshot=PaymentSnapshotFixtures.of(10L,20L,100L,PaymentTransactionType.AUTHORIZE,"key",3300,2,"pf_test_123",null,30L,"saved","memo");
    @BeforeEach void setup() { when(orders.findByIdForUpdate(10L)).thenReturn(Optional.of(order)); when(order.getUserId()).thenReturn(30L); }
    @ParameterizedTest @EnumSource(PaymentResultApplication.class)
    void onlyAppliedResultPerformsOrderSideEffects(PaymentResultApplication outcome) {
        var result=new AuthorizationResult(AuthorizationResultStatus.FAILED,"pf_test_123","failure","reason");
        when(payment.applyAuthorizationResult(order,snapshot,result)).thenReturn(outcome);
        assertThat(service.apply(snapshot,result)).isEqualTo(outcome);
        var sequence=inOrder(orders,payment,orderService);
        sequence.verify(orders).findByIdForUpdate(10L);
        sequence.verify(payment).applyAuthorizationResult(order,snapshot,result);
        if(outcome==PaymentResultApplication.APPLIED) sequence.verify(orderService).cancelOrderForPaymentFailure(10L);
        else verifyNoInteractions(orderService);
        verifyNoInteractions(acceptance);
    }
    PaymentOperationSnapshot initial(PaymentTransactionPurpose purpose) {
        var s=snapshot;
        return new PaymentOperationSnapshot(s.orderId(),s.paymentId(),s.transactionId(),s.type(),s.idempotencyKey(),s.amount(),
                s.orderContentRevision(),s.providerPaymentId(),s.provider(),s.status(),s.initiatorType(),s.initiatorId(),
                s.initiatorUsername(),s.internalNote(),purpose);
    }
    @Test void appliedInitialAuthorizationFormallyAcceptsAfterPaymentSuccessInSameCall() {
        var target=initial(PaymentTransactionPurpose.INITIAL_ORDER_AUTHORIZATION);
        var result=new AuthorizationResult(AuthorizationResultStatus.AUTHORIZED,"pf_test_123",null,null);
        when(payment.applyAuthorizationResult(order,target,result)).thenReturn(PaymentResultApplication.APPLIED);
        assertThat(service.apply(target,result)).isEqualTo(PaymentResultApplication.APPLIED);
        var sequence=inOrder(orders,payment,acceptance);
        sequence.verify(orders).findByIdForUpdate(10L);
        sequence.verify(payment).applyAuthorizationResult(order,target,result);
        sequence.verify(acceptance).acceptAfterInitialAuthorization(order,20L,100L);
        verifyNoInteractions(orderService);
    }
    @ParameterizedTest @EnumSource(value=PaymentResultApplication.class,names="APPLIED",mode=EnumSource.Mode.EXCLUDE)
    void nonAppliedAuthorizationNeverAccepts(PaymentResultApplication outcome) {
        var target=initial(PaymentTransactionPurpose.INITIAL_ORDER_AUTHORIZATION);
        var result=new AuthorizationResult(AuthorizationResultStatus.AUTHORIZED,"pf_test_123",null,null);
        when(payment.applyAuthorizationResult(order,target,result)).thenReturn(outcome);
        service.apply(target,result);
        verifyNoInteractions(acceptance);
    }
    @ParameterizedTest @EnumSource(value=AuthorizationResultStatus.class,names="AUTHORIZED",mode=EnumSource.Mode.EXCLUDE)
    void threeDsOrPendingOrFailedResultNeverAccepts(AuthorizationResultStatus status) {
        var target=initial(PaymentTransactionPurpose.INITIAL_ORDER_AUTHORIZATION);
        var result=new AuthorizationResult(status,"pf_test_123",null,null);
        when(payment.applyAuthorizationResult(order,target,result)).thenReturn(PaymentResultApplication.APPLIED);
        service.apply(target,result);
        verifyNoInteractions(acceptance);
    }
    @Test void orderChangeAuthorizationNeverRedefinesAcceptance() {
        var target=initial(PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION);
        var result=new AuthorizationResult(AuthorizationResultStatus.AUTHORIZED,"pf_test_123",null,null);
        when(payment.applyAuthorizationResult(order,target,result)).thenReturn(PaymentResultApplication.APPLIED);
        service.apply(target,result);
        verifyNoInteractions(acceptance);
    }
    @Test void resultFailureRollsBackByPropagatingException() {
        var result=new AuthorizationResult(AuthorizationResultStatus.FAILED,"pf_test_123","failure","reason");
        when(payment.applyAuthorizationResult(order,snapshot,result)).thenThrow(new IllegalStateException("conflict"));
        assertThatThrownBy(()->service.apply(snapshot,result)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(orderService);
    }
    @Test void pendingResultDoesNotPerformOrderSideEffects() {
        var result=new AuthorizationResult(AuthorizationResultStatus.PENDING,"pf_test_123",null,null);
        when(payment.applyAuthorizationResult(order,snapshot,result)).thenReturn(PaymentResultApplication.APPLIED);
        assertThat(service.apply(snapshot,result)).isEqualTo(PaymentResultApplication.APPLIED);
        verifyNoInteractions(orderService);
    }
}
