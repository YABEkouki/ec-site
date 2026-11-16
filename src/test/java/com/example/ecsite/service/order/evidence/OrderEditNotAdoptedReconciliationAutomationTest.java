package com.example.ecsite.service.order.evidence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;

class OrderEditNotAdoptedReconciliationAutomationTest {
    @Test void schedulerDelegatesOnlyToTheReconciliationService(){
        var service=mock(OrderEditNotAdoptedReconciliationService.class);
        new OrderEditNotAdoptedReconciliationScheduler(service).reconcileDue();
        verify(service).reconcileDue(100);verifyNoMoreInteractions(service);
        reset(service);doThrow(new IllegalStateException("synthetic scan failure")).when(service).reconcileDue(anyInt());
        assertThatCode(()->new OrderEditNotAdoptedReconciliationScheduler(service).reconcileDue()).doesNotThrowAnyException();
    }
}
