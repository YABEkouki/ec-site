package com.example.ecsite.service.orderchange;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;

class OrderChangeNotAdoptedCancellationAutomationTest {
    @Test void schedulerDelegatesOnlyToTheCancellationService(){
        var service=mock(OrderChangeNotAdoptedCancellationService.class);
        new OrderChangeNotAdoptedCancellationScheduler(service).cancelDue();
        verify(service).cancelDue(100);verifyNoMoreInteractions(service);
        reset(service);doThrow(new IllegalStateException("synthetic scan failure")).when(service).cancelDue(anyInt());
        assertThatCode(()->new OrderChangeNotAdoptedCancellationScheduler(service).cancelDue()).doesNotThrowAnyException();
    }
}
