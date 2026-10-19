package com.example.ecsite.service.payment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
class PaymentCaptureStartServiceTest {
    CaptureShipmentOperations operations=mock(CaptureShipmentOperations.class);
    PaymentCaptureStartService service=new PaymentCaptureStartService(operations);
    @Test void delegatesExactlyOnceToProtectedOperation() {
        var expected=mock(PaymentCaptureStart.class);when(operations.start(10L,30L,"admin","note")).thenReturn(expected);
        assertThat(service.start(10L,30L,"admin","note")).isSameAs(expected);
        verify(operations).start(10L,30L,"admin","note");verifyNoMoreInteractions(operations);
    }
    @Test void propagatesRejectionWithoutOtherSideEffects() {
        when(operations.start(10L,30L,"admin",null)).thenThrow(new IllegalStateException("recovery"));
        assertThatThrownBy(() -> service.start(10L,30L,"admin",null)).isInstanceOf(IllegalStateException.class);
    }
}
