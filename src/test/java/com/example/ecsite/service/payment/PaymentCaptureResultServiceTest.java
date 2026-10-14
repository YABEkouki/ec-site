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
import com.example.ecsite.service.OrderService;
class PaymentCaptureResultServiceTest {
    OrderRepository orders=mock(OrderRepository.class);
    PaymentService payment=mock(PaymentService.class);
    OrderService orderService=mock(OrderService.class);
    Order order=mock(Order.class);
    PaymentCaptureResultService service=new PaymentCaptureResultService(orders,payment,orderService);
    PaymentOperationSnapshot snapshot=PaymentSnapshotFixtures.of(10L,20L,100L,PaymentTransactionType.CAPTURE,"key",3300,2,"pf_test_123",PaymentTransactionInitiatorType.ADMIN,30L,"saved","memo");
    @BeforeEach void setup() { when(orders.findByIdForUpdate(10L)).thenReturn(Optional.of(order)); when(order.getUserId()).thenReturn(30L); }
    @ParameterizedTest @EnumSource(PaymentResultApplication.class)
    void onlyAppliedResultPerformsOrderSideEffects(PaymentResultApplication outcome) {
        var result=new CaptureResult(CaptureResultStatus.CAPTURED,"pf_test_123","failure","reason");
        when(payment.applyCaptureResult(order,snapshot,result)).thenReturn(outcome);
        assertThat(service.apply(snapshot,result)).isEqualTo(outcome);
        var sequence=inOrder(orders,payment,orderService);
        sequence.verify(orders).findByIdForUpdate(10L);
        sequence.verify(payment).applyCaptureResult(order,snapshot,result);
        if(outcome==PaymentResultApplication.APPLIED) sequence.verify(orderService).markAsShippedAfterPaymentCapture(order,30L,"saved","memo");
        else verifyNoInteractions(orderService);
    }
    @Test void resultFailureRollsBackByPropagatingException() {
        var result=new CaptureResult(CaptureResultStatus.CAPTURED,"pf_test_123","failure","reason");
        when(payment.applyCaptureResult(order,snapshot,result)).thenThrow(new IllegalStateException("conflict"));
        assertThatThrownBy(()->service.apply(snapshot,result)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(orderService);
    }
    @Test void pendingResultDoesNotPerformOrderSideEffects() {
        var result=new CaptureResult(CaptureResultStatus.PENDING,"pf_test_123",null,null);
        when(payment.applyCaptureResult(order,snapshot,result)).thenReturn(PaymentResultApplication.APPLIED);
        assertThat(service.apply(snapshot,result)).isEqualTo(PaymentResultApplication.APPLIED);
        verifyNoInteractions(orderService);
    }
}
