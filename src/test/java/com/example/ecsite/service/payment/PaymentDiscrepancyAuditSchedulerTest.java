package com.example.ecsite.service.payment;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class PaymentDiscrepancyAuditSchedulerTest {

    @Test
    void delegatesAuditToService() {
        PaymentDiscrepancyAuditService auditService =
                mock(PaymentDiscrepancyAuditService.class);

        PaymentDiscrepancyAuditScheduler scheduler =
                new PaymentDiscrepancyAuditScheduler(auditService);

        scheduler.auditPaymentDiscrepancies();

        verify(auditService).auditPayments();
    }
}
