package com.example.ecsite.service.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.ecsite.entity.*;
import com.example.ecsite.payment.PaymentGatewayException;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AdminCaptureRequeryServiceTest {
 final AdminCaptureRequeryPreparationService preparation=mock(AdminCaptureRequeryPreparationService.class);
 final PaymentOperationSyncService sync=mock(PaymentOperationSyncService.class);
 final AdminCaptureRequeryService service=new AdminCaptureRequeryService(preparation,sync);
 final PaymentOperationSnapshot snapshot=new PaymentOperationSnapshot(42L,52L,62L,PaymentTransactionType.CAPTURE,"key",200,0,"pf",PaymentProvider.PAYJP,PaymentTransactionStatus.PENDING,PaymentTransactionInitiatorType.ADMIN,7L,"shipper","memo");
 PaymentCaptureStart operation(PaymentStartDisposition disposition){var capability=disposition==PaymentStartDisposition.NEW?mock(CaptureShipmentOperations.InitialPost.class):null;if(capability!=null)when(capability.snapshot()).thenReturn(snapshot);return new PaymentCaptureStart(52L,62L,"pf",200,"key",disposition,snapshot,capability);}
 @Test void pendingUsesOnlySharedGetSynchronizationThenChecksStoredState(){when(preparation.prepare(42L)).thenReturn(operation(PaymentStartDisposition.REUSED));when(sync.synchronize(snapshot)).thenReturn(PaymentSynchronizationOutcome.WAITING);when(preparation.latest(snapshot)).thenReturn(PaymentStartDisposition.REUSED);assertThat(service.requery(42L)).isEqualTo(AdminCaptureRequeryService.Result.WAITING);var order=inOrder(preparation,sync);order.verify(preparation).prepare(42L);order.verify(sync).synchronize(snapshot);order.verify(preparation).latest(snapshot);verifyNoMoreInteractions(preparation,sync);}
 @Test void alreadyAppliedDoesNotContactGateway(){when(preparation.prepare(42L)).thenReturn(operation(PaymentStartDisposition.ALREADY_APPLIED));assertThat(service.requery(42L)).isEqualTo(AdminCaptureRequeryService.Result.CONFIRMED);verifyNoInteractions(sync);}
 @Test void newIsRejectedEvenIfPreparationIncorrectlyReturnsIt(){var created=operation(PaymentStartDisposition.NEW);when(preparation.prepare(42L)).thenReturn(created);assertThatThrownBy(()->service.requery(42L)).isInstanceOf(CaptureRequeryRejectedException.class);verifyNoInteractions(sync);}
 @ParameterizedTest @ValueSource(strings={"IGNORED_STALE","ALREADY_APPLIED","WAITING","APPLIED"})
 void latestCommittedSuccessTakesPriorityOverOlderResponse(String outcome){when(preparation.prepare(42L)).thenReturn(operation(PaymentStartDisposition.REUSED));when(sync.synchronize(snapshot)).thenReturn(PaymentSynchronizationOutcome.valueOf(outcome));when(preparation.latest(snapshot)).thenReturn(PaymentStartDisposition.ALREADY_APPLIED);assertThat(service.requery(42L)).isEqualTo(AdminCaptureRequeryService.Result.CONFIRMED);}
 @ParameterizedTest @ValueSource(booleans={false,true}) void gatewayFailureOrTimeoutNeverBecomesCaptureFailure(boolean timeout){var failure=new PaymentGatewayException("private",timeout?new SocketTimeoutException():new RuntimeException());when(preparation.prepare(42L)).thenReturn(operation(PaymentStartDisposition.REUSED));when(sync.synchronize(snapshot)).thenThrow(failure);when(preparation.latest(snapshot)).thenReturn(PaymentStartDisposition.REUSED);assertThatThrownBy(()->service.requery(42L)).isSameAs(failure);}
 @Test void committedSuccessAlsoWinsWhenGetThrows(){when(preparation.prepare(42L)).thenReturn(operation(PaymentStartDisposition.REUSED));when(sync.synchronize(snapshot)).thenThrow(new PaymentGatewayException("timeout"));when(preparation.latest(snapshot)).thenReturn(PaymentStartDisposition.ALREADY_APPLIED);assertThat(service.requery(42L)).isEqualTo(AdminCaptureRequeryService.Result.CONFIRMED);}
 @ParameterizedTest @ValueSource(strings={"CONFLICT","SKIPPED"}) void unresolvedConflictStopsSafely(String outcome){when(preparation.prepare(42L)).thenReturn(operation(PaymentStartDisposition.REUSED));when(sync.synchronize(snapshot)).thenReturn(PaymentSynchronizationOutcome.valueOf(outcome));when(preparation.latest(snapshot)).thenReturn(PaymentStartDisposition.REUSED);assertThatThrownBy(()->service.requery(42L)).isInstanceOf(CaptureRequeryRejectedException.class);}
 @Test void unexpectedFailureIsNotConvertedToBusinessResult(){var failure=new NullPointerException("unexpected");when(preparation.prepare(42L)).thenReturn(operation(PaymentStartDisposition.REUSED));when(sync.synchronize(snapshot)).thenThrow(failure);assertThatThrownBy(()->service.requery(42L)).isSameAs(failure);verify(preparation,never()).latest(any());}
}
