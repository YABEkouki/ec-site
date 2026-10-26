package com.example.ecsite.service.payment;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class PaymentReconciliationSchedulerTest {

    @Test
    void reconcileDelegatesToReconciliationService() {

        PaymentReconciliationService reconciliationService =
                mock(PaymentReconciliationService.class);

        PaymentReconciliationScheduler scheduler =
                new PaymentReconciliationScheduler(
                        reconciliationService, mock(com.example.ecsite.service.orderchange.OrderIncreaseService.class));

        scheduler.reconcile();

        verify(reconciliationService)
                .reconcilePendingTransactions();
    }
}
