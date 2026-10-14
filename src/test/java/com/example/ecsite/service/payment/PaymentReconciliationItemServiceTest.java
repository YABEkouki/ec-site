package com.example.ecsite.service.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.example.ecsite.entity.*;
import com.example.ecsite.payment.*;

class PaymentReconciliationItemServiceTest {
    PaymentOperationSnapshotService snapshots=mock(PaymentOperationSnapshotService.class);
    PaymentGateway gateway=mock(PaymentGateway.class);
    PaymentAuthorizationResultService authorization=mock(PaymentAuthorizationResultService.class);
    PaymentCaptureResultService capture=mock(PaymentCaptureResultService.class);
    PaymentCancellationResultService userCancel=mock(PaymentCancellationResultService.class);
    AdminPaymentCancellationResultService adminCancel=mock(AdminPaymentCancellationResultService.class);
    PaymentReconciliationItemService service=new PaymentReconciliationItemService(snapshots,new PaymentOperationSyncService(gateway,authorization,capture,userCancel,adminCancel), org.mockito.Mockito.mock(PaymentPostGuardService.class));

    @ParameterizedTest @EnumSource(value=PaymentTransactionType.class,names={"AUTHORIZE","CAPTURE","CANCEL"})
    void pendingOperationRefetchesAndPassesExactSnapshot(PaymentTransactionType type) {
        var s=snapshot(type,"pf_test_123",PaymentProvider.PAYJP,PaymentTransactionStatus.PENDING,PaymentTransactionInitiatorType.ADMIN);
        when(snapshots.find(100L)).thenReturn(Optional.of(s));
        if(type==PaymentTransactionType.AUTHORIZE) {
            var result=new AuthorizationResult(AuthorizationResultStatus.AUTHORIZED,null,null,null);
            when(gateway.retrieveAuthorization(s.providerPaymentId())).thenReturn(new AuthorizationRecovery(result,null));
            when(authorization.apply(s,result)).thenReturn(PaymentResultApplication.APPLIED);
            service.reconcile(100L); verify(authorization).apply(s,result);
        } else if(type==PaymentTransactionType.CAPTURE) {
            when(gateway.retrievePaymentFlow(s.providerPaymentId())).thenReturn(new PaymentFlowState(s.providerPaymentId(),PaymentFlowStatus.SUCCEEDED,null,null));
            var result=new CaptureResult(CaptureResultStatus.CAPTURED,s.providerPaymentId(),null,null);
            when(capture.apply(s,result)).thenReturn(PaymentResultApplication.APPLIED);
            service.reconcile(100L);verify(capture).apply(s,result);
        } else {
            when(gateway.retrievePaymentFlow(s.providerPaymentId())).thenReturn(new PaymentFlowState(s.providerPaymentId(),PaymentFlowStatus.CANCELED,null,null));
            var result=new CancellationResult(CancellationResultStatus.CANCELLED,s.providerPaymentId());
            when(adminCancel.apply(s,result)).thenReturn(PaymentResultApplication.APPLIED);
            service.reconcile(100L);verify(adminCancel).apply(s,result);verifyNoInteractions(userCancel);
        }
    }
    @Test void userCancellationUsesSavedUserSnapshot() {
        var s=snapshot(PaymentTransactionType.CANCEL,"pf_test_123",PaymentProvider.PAYJP,PaymentTransactionStatus.PENDING,PaymentTransactionInitiatorType.USER);
        var result=new CancellationResult(CancellationResultStatus.CANCELLED,s.providerPaymentId());
        when(snapshots.find(100L)).thenReturn(Optional.of(s));
        when(gateway.retrievePaymentFlow(s.providerPaymentId())).thenReturn(new PaymentFlowState(s.providerPaymentId(),PaymentFlowStatus.CANCELED,null,null));
        when(userCancel.apply(s,result)).thenReturn(PaymentResultApplication.APPLIED);
        service.reconcile(100L);verify(userCancel).apply(s,result);verifyNoInteractions(adminCancel);
    }
    @Test void missingTransactionDoesNotCallPayJp() {
        when(snapshots.find(100L)).thenReturn(Optional.empty());service.reconcile(100L);verifyNoInteractions(gateway);
    }
    @ParameterizedTest @EnumSource(value=PaymentTransactionStatus.class,names={"SUCCEEDED","FAILED"})
    void completedTransactionDoesNotCallPayJp(PaymentTransactionStatus status) {
        skip(snapshot(PaymentTransactionType.AUTHORIZE,"pf_test_123",PaymentProvider.PAYJP,status,null));
    }
    @Test void nonPayJpOperationDoesNotCallPayJp() {
        skip(snapshot(PaymentTransactionType.AUTHORIZE,"pf",PaymentProvider.MOCK,PaymentTransactionStatus.PENDING,null));
    }
    @Test void missingProviderIdDoesNotCallPayJp() {
        skip(snapshot(PaymentTransactionType.AUTHORIZE,null,PaymentProvider.PAYJP,PaymentTransactionStatus.PENDING,null));
    }
    @Test void captureWithoutAdminDoesNotCallPayJp() {
        skip(snapshot(PaymentTransactionType.CAPTURE,"pf",PaymentProvider.PAYJP,PaymentTransactionStatus.PENDING,PaymentTransactionInitiatorType.USER));
    }
    @Test void unsupportedCancelActorDoesNotCallPayJp() {
        skip(snapshot(PaymentTransactionType.CANCEL,"pf",PaymentProvider.PAYJP,PaymentTransactionStatus.PENDING,null));
    }
    @Test void refundIsOutOfScope() {
        skip(snapshot(PaymentTransactionType.REFUND,"pf",PaymentProvider.PAYJP,PaymentTransactionStatus.PENDING,null));
    }
    @ParameterizedTest @EnumSource(value=PaymentTransactionType.class,names={"CAPTURE","CANCEL"})
    void nonTerminalFlowRevalidatesAndRemainsWaiting(PaymentTransactionType type) {
        var s=snapshot(type,"pf",PaymentProvider.PAYJP,PaymentTransactionStatus.PENDING,PaymentTransactionInitiatorType.ADMIN);
        when(snapshots.find(100L)).thenReturn(Optional.of(s));
        when(gateway.retrievePaymentFlow("pf")).thenReturn(new PaymentFlowState("pf",PaymentFlowStatus.REQUIRES_CAPTURE,null,null));
        if(type==PaymentTransactionType.CAPTURE) {
            var result=new CaptureResult(CaptureResultStatus.PENDING,"pf",null,null);
            when(capture.apply(s,result)).thenReturn(PaymentResultApplication.APPLIED);
            assertThat(service.reconcile(100L)).isEqualTo(PaymentSynchronizationOutcome.WAITING);
            verify(capture).apply(s,result);verifyNoInteractions(authorization,userCancel,adminCancel);
        } else {
            var result=new CancellationResult(CancellationResultStatus.PENDING,"pf");
            when(adminCancel.apply(s,result)).thenReturn(PaymentResultApplication.APPLIED);
            assertThat(service.reconcile(100L)).isEqualTo(PaymentSynchronizationOutcome.WAITING);
            verify(adminCancel).apply(s,result);verifyNoInteractions(authorization,capture,userCancel);
        }
    }
    @ParameterizedTest @EnumSource(value=PaymentResultApplication.class,names={"IGNORED_STALE","CONFLICT"})
    void staleResultIsNotReportedAsApplied(PaymentResultApplication outcome) {
        var s=snapshot(PaymentTransactionType.AUTHORIZE,"pf",PaymentProvider.PAYJP,PaymentTransactionStatus.PENDING,null);
        var result=new AuthorizationResult(AuthorizationResultStatus.AUTHORIZED,null,null,null);
        when(snapshots.find(100L)).thenReturn(Optional.of(s));
        when(gateway.retrieveAuthorization("pf")).thenReturn(new AuthorizationRecovery(result,null));
        when(authorization.apply(s,result)).thenReturn(outcome);
        assertThat(service.reconcile(100L)).isEqualTo(PaymentSynchronizationOutcome.from(outcome));
    }
    private void skip(PaymentOperationSnapshot s) {
        when(snapshots.find(100L)).thenReturn(Optional.of(s));service.reconcile(100L);verifyNoInteractions(gateway,authorization,capture,userCancel,adminCancel);
    }
    private PaymentOperationSnapshot snapshot(PaymentTransactionType type,String provider,PaymentProvider paymentProvider,
            PaymentTransactionStatus status,PaymentTransactionInitiatorType actor) {
        return new PaymentOperationSnapshot(10L,20L,100L,type,"saved-key",3300,2,provider,paymentProvider,status,actor,30L,"saved","memo");
    }
}
