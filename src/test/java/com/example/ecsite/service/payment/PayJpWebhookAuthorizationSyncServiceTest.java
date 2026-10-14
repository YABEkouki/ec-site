package com.example.ecsite.service.payment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.example.ecsite.entity.*;
class PayJpWebhookAuthorizationSyncServiceTest {
    PaymentOperationSnapshotService snapshots=mock(PaymentOperationSnapshotService.class);
    PaymentOperationSyncService synchronization=mock(PaymentOperationSyncService.class);
    PayJpWebhookAuthorizationSyncService service=new PayJpWebhookAuthorizationSyncService(snapshots,synchronization);
    @ParameterizedTest @EnumSource(PaymentSynchronizationOutcome.class)
    void delegatesExactSnapshotAndKeepsNormalConcurrentOutcomes(PaymentSynchronizationOutcome outcome) {
        var snapshot=PaymentSnapshotFixtures.of(10L,20L,100L,PaymentTransactionType.AUTHORIZE,"key",3300,0,"pf",PaymentTransactionInitiatorType.ADMIN,30L,"saved","memo");
        when(snapshots.pendingForProvider("pf",PaymentTransactionType.AUTHORIZE)).thenReturn(Optional.of(snapshot));
        when(synchronization.synchronize(snapshot)).thenReturn(outcome);
        if(outcome==PaymentSynchronizationOutcome.CONFLICT) assertThatThrownBy(()->service.synchronize("payment_flow.processing","pf")).isInstanceOf(IllegalStateException.class);
        else assertThat(service.synchronize("payment_flow.processing","pf")).isEqualTo(outcome);
        verify(synchronization).synchronize(snapshot);
    }
    @Test void noEligibleOperationDoesNotCallProvider() {
        when(snapshots.pendingForProvider("pf",PaymentTransactionType.AUTHORIZE)).thenReturn(Optional.empty());
        assertThat(service.synchronize("payment_flow.processing","pf")).isEqualTo(PaymentSynchronizationOutcome.SKIPPED);verifyNoInteractions(synchronization);
    }
    @Test void unrelatedAuthorizationEventDoesNotReadDatabaseOrCallProvider() { assertThat(service.synchronize("payment_flow.other","pf")).isEqualTo(PaymentSynchronizationOutcome.SKIPPED);verifyNoInteractions(snapshots,synchronization); }
}
