package com.example.ecsite.service.orderchange;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import com.example.ecsite.service.payment.*;

class NewFlowClosureAutomationTest {
    @Test void explicitOperationScannerExists() throws Exception {
        assertThat(NewFlowClosureReconciliationService.class.getDeclaredMethod("scan")).isNotNull();
    }
    @Test void webhookTriggerRunsOnlyAfterExistingSynchronizationReturnsNormally(){
        var auth=mock(PayJpWebhookAuthorizationSyncService.class);var capture=mock(PayJpWebhookCaptureSyncService.class);
        var cancel=mock(PayJpWebhookCancellationSyncService.class);var closures=mock(NewFlowClosureReconciliationService.class);
        var service=new PayJpWebhookSyncService(auth,capture,cancel,closures);
        service.synchronize("payment_flow.canceled","pfw_test");
        var order=inOrder(cancel,closures);order.verify(cancel).synchronize("pfw_test");order.verify(closures).afterWebhook("pfw_test");
        reset(cancel,closures);doThrow(new IllegalStateException("Synthetic existing financial failure")).when(cancel).synchronize(anyString());
        assertThatThrownBy(()->service.synchronize("payment_flow.canceled","pfw_test")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(closures);
    }
    @Test void schedulerDelegatesOnlyToGetOnlyScanner(){
        var service=mock(NewFlowClosureReconciliationService.class);new NewFlowClosureScheduler(service).scan();verify(service).scan();verifyNoMoreInteractions(service);
        reset(service);doThrow(new IllegalStateException("synthetic scan failure")).when(service).scan();
        assertThatCode(()->new NewFlowClosureScheduler(service).scan()).doesNotThrowAnyException();
    }
}
