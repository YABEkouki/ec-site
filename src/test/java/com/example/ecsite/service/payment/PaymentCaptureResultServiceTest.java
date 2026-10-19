package com.example.ecsite.service.payment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.example.ecsite.payment.*;
class PaymentCaptureResultServiceTest {
    CaptureShipmentOperations operations=mock(CaptureShipmentOperations.class);
    PaymentCaptureResultService service=new PaymentCaptureResultService(operations);
    @ParameterizedTest @EnumSource(PaymentResultApplication.class)
    void returnsSharedOperationOutcomeWithoutShippingAgain(PaymentResultApplication outcome) {
        var snapshot=mock(PaymentOperationSnapshot.class);var result=new CaptureResult(CaptureResultStatus.CAPTURED,"provider",null,null);
        when(operations.apply(snapshot,result)).thenReturn(outcome);
        assertThat(service.apply(snapshot,result)).isEqualTo(outcome);
        verify(operations).apply(snapshot,result);verifyNoMoreInteractions(operations);
    }
    @Test void propagatesFailureForTransactionRollback() {
        when(operations.apply(null,null)).thenThrow(new IllegalStateException("failure"));
        assertThatThrownBy(() -> service.apply(null,null)).isInstanceOf(IllegalStateException.class);
    }
}
