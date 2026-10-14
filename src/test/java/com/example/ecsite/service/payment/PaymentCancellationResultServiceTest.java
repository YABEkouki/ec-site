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
class PaymentCancellationResultServiceTest {
    OrderRepository orders=mock(OrderRepository.class);
    PaymentService payment=mock(PaymentService.class);
    OrderService orderService=mock(OrderService.class);
    Order order=mock(Order.class);
    PaymentCancellationResultService service=new PaymentCancellationResultService(orders,payment,orderService);
    PaymentOperationSnapshot snapshot=PaymentSnapshotFixtures.of(10L,20L,100L,PaymentTransactionType.CANCEL,"key",3300,2,"pf_test_123",PaymentTransactionInitiatorType.USER,30L,"saved","memo");
    @BeforeEach void setup() { when(orders.findByIdForUpdate(10L)).thenReturn(Optional.of(order)); when(order.getUserId()).thenReturn(30L); }
    @ParameterizedTest @EnumSource(PaymentResultApplication.class)
    void onlyAppliedResultPerformsOrderSideEffects(PaymentResultApplication outcome) {
        var result=new CancellationResult(CancellationResultStatus.CANCELLED,"pf_test_123");
        when(payment.applyCancellationResult(order,snapshot,result)).thenReturn(outcome);
        assertThat(service.apply(snapshot,result)).isEqualTo(outcome);
        var sequence=inOrder(orders,payment,orderService);
        sequence.verify(orders).findByIdForUpdate(10L);
        sequence.verify(payment).applyCancellationResult(order,snapshot,result);
        if(outcome==PaymentResultApplication.APPLIED) sequence.verify(orderService).cancelOrderForUserAfterPaymentCancellation(10L,30L,"saved");
        else verifyNoInteractions(orderService);
    }
    @Test void resultFailureRollsBackByPropagatingException() {
        var result=new CancellationResult(CancellationResultStatus.CANCELLED,"pf_test_123");
        when(payment.applyCancellationResult(order,snapshot,result)).thenThrow(new IllegalStateException("conflict"));
        assertThatThrownBy(()->service.apply(snapshot,result)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(orderService);
    }
    @Test void pendingResultDoesNotPerformOrderSideEffects() {
        var result=new CancellationResult(CancellationResultStatus.PENDING,"pf_test_123");
        when(payment.applyCancellationResult(order,snapshot,result)).thenReturn(PaymentResultApplication.APPLIED);
        assertThat(service.apply(snapshot,result)).isEqualTo(PaymentResultApplication.APPLIED);
        verifyNoInteractions(orderService);
    }
}
