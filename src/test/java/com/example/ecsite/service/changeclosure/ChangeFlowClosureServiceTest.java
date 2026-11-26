package com.example.ecsite.service.changeclosure;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.service.changeclosure.ChangeFlowClosureStore.Due;

/** Feature143-B: one change that cannot be requested never stops the others in the same pass. */
class ChangeFlowClosureServiceTest {
    @Test void oneFailureDoesNotStopTheOthers() {
        var store = mock(ChangeFlowClosureStore.class);
        var gateway = mock(PaymentGateway.class);
        Duration after = Duration.ofMinutes(60), retry = Duration.ofHours(6);
        var first = new Due(1, UUID.randomUUID());
        var second = new Due(2, UUID.randomUUID());
        when(store.dueOperations(after, retry, 20)).thenReturn(List.of(first, second));
        when(store.requestDue(first, after, retry)).thenThrow(new IllegalStateException("locked"));
        when(store.requestDue(second, after, retry)).thenReturn(Optional.empty());

        new ChangeFlowClosureService(store, gateway).closeDue(after, retry, 20);

        verify(store).requestDue(first, after, retry);
        verify(store).requestDue(second, after, retry);
        verify(gateway, never()).cancelUnpaidCheckout(any());
    }
}
