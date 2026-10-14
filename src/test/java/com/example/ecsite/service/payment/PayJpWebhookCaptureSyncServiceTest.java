package com.example.ecsite.service.payment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.example.ecsite.entity.*;
class PayJpWebhookCaptureSyncServiceTest {
    PaymentOperationSnapshotService snapshots=mock(PaymentOperationSnapshotService.class);
    PaymentOperationSyncService synchronization=mock(PaymentOperationSyncService.class);
    PayJpWebhookCaptureSyncService service=new PayJpWebhookCaptureSyncService(snapshots,synchronization);
    @ParameterizedTest @EnumSource(PaymentSynchronizationOutcome.class)
    void delegatesExactSnapshotAndKeepsNormalConcurrentOutcomes(PaymentSynchronizationOutcome outcome) {
        var snapshot=PaymentSnapshotFixtures.of(10L,20L,100L,PaymentTransactionType.CAPTURE,"key",3300,0,"pf",PaymentTransactionInitiatorType.ADMIN,30L,"saved","memo");
        when(snapshots.pendingForProvider("pf",PaymentTransactionType.CAPTURE)).thenReturn(Optional.of(snapshot));
        when(synchronization.synchronize(snapshot)).thenReturn(outcome);
        if(outcome==PaymentSynchronizationOutcome.CONFLICT) assertThatThrownBy(()->service.synchronize("pf")).isInstanceOf(IllegalStateException.class);
        else assertThat(service.synchronize("pf")).isEqualTo(outcome);
        verify(synchronization).synchronize(snapshot);
    }
    @Test void noEligibleOperationDoesNotCallProvider() {
        when(snapshots.pendingForProvider("pf",PaymentTransactionType.CAPTURE)).thenReturn(Optional.empty());
        assertThat(service.synchronize("pf")).isEqualTo(PaymentSynchronizationOutcome.SKIPPED);verifyNoInteractions(synchronization);
    }

}
