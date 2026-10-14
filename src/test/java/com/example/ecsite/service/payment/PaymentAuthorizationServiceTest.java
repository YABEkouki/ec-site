package com.example.ecsite.service.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.ecsite.entity.Order;
import com.example.ecsite.payment.AuthorizationPreparation;
import com.example.ecsite.payment.AuthorizationRecovery;
import com.example.ecsite.payment.AuthorizationResult;
import com.example.ecsite.payment.AuthorizationResultStatus;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.payment.PaymentGatewayException;

@ExtendWith(MockitoExtension.class)
class PaymentAuthorizationServiceTest {

    @Mock
    private PaymentAuthorizationStartService startService;

    @Mock
    private PaymentService paymentService;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private PaymentAuthorizationResultService paymentAuthorizationResultService;

    @Mock
    private PaymentOperationSnapshotService snapshots;

    @Mock
    private Order order;

    private PaymentAuthorizationService service;

    @BeforeEach
    void setUp() {

        org.mockito.Mockito.lenient().when(paymentAuthorizationResultService.apply(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(PaymentResultApplication.APPLIED);
        org.mockito.Mockito.lenient().when(paymentAuthorizationResultService.setProviderPaymentId(org.mockito.ArgumentMatchers.any(PaymentOperationSnapshot.class), org.mockito.ArgumentMatchers.any())).thenReturn(PaymentResultApplication.APPLIED);
        service = new PaymentAuthorizationService(
                startService,
                paymentService,
                paymentGateway,
                paymentAuthorizationResultService, snapshots);
    }

    @Test
    void prepareAuthorizationCreatesProviderPaymentAndStoresProviderId() {

        Long paymentId = 10L;
        Long transactionId = 20L;
        int amount = 3300;
        String idempotencyKey = "authorization-idempotency-key";
        String providerPaymentId = "pf_test_123";
        String clientSecret = "client_secret_test";

        PaymentAuthorizationStart start = new PaymentAuthorizationStart(paymentId, transactionId, amount, idempotencyKey, PaymentStartDisposition.NEW, new PaymentOperationSnapshot(10L, paymentId, transactionId, com.example.ecsite.entity.PaymentTransactionType.AUTHORIZE, idempotencyKey, amount, 0, null, com.example.ecsite.entity.PaymentProvider.PAYJP, com.example.ecsite.entity.PaymentTransactionStatus.PENDING, null, null, null, null));

        AuthorizationPreparation preparation = new AuthorizationPreparation(
                providerPaymentId,
                clientSecret);

        when(startService.start(order.getId()))
                .thenReturn(start);


        when(paymentGateway.prepareAuthorization(
                new com.example.ecsite.payment.AuthorizationRequest(
                        amount,
                        idempotencyKey)))
                .thenReturn(preparation);

        PaymentAuthorizationPreparation actual = service.prepareAuthorization(order);

        assertSame(
                paymentId,
                actual.paymentId());

        org.junit.jupiter.api.Assertions.assertEquals(
                clientSecret,
                actual.clientSecret());

        InOrder inOrder = inOrder(
                startService,
                paymentService,
                paymentGateway, paymentAuthorizationResultService);

        inOrder.verify(startService)
                .start(order.getId());

        inOrder.verify(paymentGateway)
                .prepareAuthorization(
                        new com.example.ecsite.payment.AuthorizationRequest(
                                amount,
                                idempotencyKey));

        inOrder.verify(paymentAuthorizationResultService)
                .setProviderPaymentId(start.snapshot(), providerPaymentId);
    }

    @Test
    void prepareAuthorizationDoesNotStoreProviderIdWhenGatewayFails() {

        Long paymentId = 10L;
        Long transactionId = 20L;
        int amount = 3300;
        String idempotencyKey = "authorization-idempotency-key";

        PaymentAuthorizationStart start = new PaymentAuthorizationStart(paymentId, transactionId, amount, idempotencyKey, PaymentStartDisposition.NEW, new PaymentOperationSnapshot(10L, paymentId, transactionId, com.example.ecsite.entity.PaymentTransactionType.AUTHORIZE, idempotencyKey, amount, 0, null, com.example.ecsite.entity.PaymentProvider.PAYJP, com.example.ecsite.entity.PaymentTransactionStatus.PENDING, null, null, null, null));

        when(startService.start(order.getId()))
                .thenReturn(start);


        when(paymentGateway.prepareAuthorization(
                new com.example.ecsite.payment.AuthorizationRequest(
                        amount,
                        idempotencyKey)))
                .thenThrow(
                        new PaymentGatewayException(
                                "PAY.JP communication failed"));

        assertThrows(
                PaymentGatewayException.class,
                () -> service.prepareAuthorization(
                        order));

        verify(startService)
                .start(order.getId());

        verify(paymentGateway)
                .prepareAuthorization(
                        new com.example.ecsite.payment.AuthorizationRequest(
                                amount,
                                idempotencyKey));

        verify(paymentAuthorizationResultService, never())
                .setProviderPaymentId(org.mockito.ArgumentMatchers.any(PaymentOperationSnapshot.class), org.mockito.ArgumentMatchers.anyString());

        verifyNoInteractions(
                paymentAuthorizationResultService);
    }

    @Test
    void refreshAuthorizationRetrievesProviderStateAndAppliesResult() {

        Long orderId = 10L;
        Long paymentId = 20L;

        String providerPaymentId = "pf_test_123";

        AuthorizationResult result = new AuthorizationResult(
                AuthorizationResultStatus.AUTHORIZED,
                null,
                null,
                null);

        when(snapshots.authorization(orderId, paymentId)).thenReturn(PaymentSnapshotFixtures.authorization(orderId, paymentId));

        AuthorizationRecovery recovery = new AuthorizationRecovery(
                result,
                "client_secret_test");

        when(paymentGateway.retrieveAuthorization(
                providerPaymentId))
                .thenReturn(recovery);

        AuthorizationResult actual = service.refreshAuthorization(
                orderId,
                paymentId);

        assertSame(
                result,
                actual);

        InOrder inOrder = inOrder(
                startService,
                paymentService,
                paymentGateway,
                paymentAuthorizationResultService, snapshots);

        inOrder.verify(snapshots).authorization(orderId, paymentId);

        inOrder.verify(paymentGateway)
                .retrieveAuthorization(
                        providerPaymentId);

        inOrder.verify(
                paymentAuthorizationResultService)
                .apply(org.mockito.ArgumentMatchers.argThat(s -> java.util.Objects.equals(s.orderId(), orderId) && java.util.Objects.equals(s.paymentId(), paymentId)), org.mockito.ArgumentMatchers.eq(result));
    }

    @Test
    void refreshAuthorizationDoesNotUpdateLocalStateWhenGatewayFails() {

        Long orderId = 10L;
        Long paymentId = 20L;

        String providerPaymentId = "pf_test_123";

        when(snapshots.authorization(orderId, paymentId)).thenReturn(PaymentSnapshotFixtures.authorization(orderId, paymentId));

        when(paymentGateway.retrieveAuthorization(
                providerPaymentId))
                .thenThrow(
                        new PaymentGatewayException(
                                "PAY.JP communication failed"));

        assertThrows(
                PaymentGatewayException.class,
                () -> service.refreshAuthorization(
                        orderId,
                        paymentId));

        verify(snapshots).authorization(orderId, paymentId);

        verify(paymentGateway)
                .retrieveAuthorization(
                        providerPaymentId);

        verifyNoInteractions(
                paymentAuthorizationResultService);
    }

    @Test
    void reusedAuthorizationWithoutProviderIdStopsWithoutPosting() {
        when(startService.start(order.getId())).thenReturn(new PaymentAuthorizationStart(10L, 20L, 3300, "existing-key", PaymentStartDisposition.REUSED, new PaymentOperationSnapshot(10L, 10L, 20L, com.example.ecsite.entity.PaymentTransactionType.AUTHORIZE, "existing-key", 3300, 0, null, com.example.ecsite.entity.PaymentProvider.PAYJP, com.example.ecsite.entity.PaymentTransactionStatus.PENDING, null, null, null, null)));
        assertThrows(IllegalStateException.class, () -> service.prepareAuthorization(order));
        verifyNoInteractions(paymentGateway, paymentAuthorizationResultService);
        verify(paymentAuthorizationResultService, never()).setProviderPaymentId(org.mockito.ArgumentMatchers.any(PaymentOperationSnapshot.class), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void reusedAuthorizationWithProviderIdRetrievesExistingFlow() {
        when(order.getId()).thenReturn(10L);
        when(startService.start(order.getId())).thenReturn(new PaymentAuthorizationStart(10L, 20L, 3300, "existing-key", PaymentStartDisposition.REUSED, new PaymentOperationSnapshot(10L, 10L, 20L, com.example.ecsite.entity.PaymentTransactionType.AUTHORIZE, "existing-key", 3300, 0, "pf_existing", com.example.ecsite.entity.PaymentProvider.PAYJP, com.example.ecsite.entity.PaymentTransactionStatus.PENDING, null, null, null, null)));
        AuthorizationResult result = new AuthorizationResult(AuthorizationResultStatus.REQUIRES_ACTION, null, null, null);
        when(paymentGateway.retrieveAuthorization("pf_existing")).thenReturn(new AuthorizationRecovery(result, "existing-secret"));
        assertEquals(new PaymentAuthorizationPreparation(10L, "existing-secret"), service.prepareAuthorization(order));
        verify(paymentGateway, never()).prepareAuthorization(org.mockito.ArgumentMatchers.any());
        verify(paymentAuthorizationResultService).apply(org.mockito.ArgumentMatchers.argThat(s -> java.util.Objects.equals(s.orderId(), order.getId()) && java.util.Objects.equals(s.paymentId(), 10L)), org.mockito.ArgumentMatchers.eq(result));
    }

    @Test
    void recoverAuthorizationRetrievesExistingProviderPayment() {

        Long paymentId = 20L;
        Long orderId = 100L;

        when(order.getId())
                .thenReturn(orderId);

        when(paymentService.getRecoverableAuthorizationPaymentId(order))
                .thenReturn(paymentId);

        when(snapshots.authorization(order.getId(), paymentId)).thenAnswer(inv -> PaymentSnapshotFixtures.authorization(inv.getArgument(0), inv.getArgument(1)));

        AuthorizationResult result = new AuthorizationResult(
                AuthorizationResultStatus.REQUIRES_ACTION,
                null,
                null,
                null);

        AuthorizationRecovery recovery = new AuthorizationRecovery(
                result,
                "client-secret-123");

        when(paymentGateway.retrieveAuthorization("pf_test_123"))
                .thenReturn(recovery);

        PaymentAuthorizationRecovery actual = service.recoverAuthorization(order);

        assertEquals(
                paymentId,
                actual.paymentId());

        assertSame(
                result,
                actual.result());

        assertEquals(
                "client-secret-123",
                actual.clientSecret());

        assertEquals(
                PaymentAuthorizationRecoveryAction.RESUME_CHECKOUT,
                actual.action());

        verify(paymentService)
                .getRecoverableAuthorizationPaymentId(order);

        verify(snapshots).authorization(orderId, paymentId);

        verify(paymentGateway)
                .retrieveAuthorization("pf_test_123");

        verify(paymentAuthorizationResultService)
                .apply(org.mockito.ArgumentMatchers.argThat(s -> java.util.Objects.equals(s.orderId(), orderId) && java.util.Objects.equals(s.paymentId(), paymentId)), org.mockito.ArgumentMatchers.eq(result));
    }

    @Test
    void recoverAuthorizationAppliesAuthorizedResult() {

        Long orderId = 100L;
        Long paymentId = 20L;

        when(order.getId())
                .thenReturn(orderId);

        when(paymentService.getRecoverableAuthorizationPaymentId(order))
                .thenReturn(paymentId);

        when(snapshots.authorization(order.getId(), paymentId)).thenAnswer(inv -> PaymentSnapshotFixtures.authorization(inv.getArgument(0), inv.getArgument(1)));

        AuthorizationResult result = new AuthorizationResult(
                AuthorizationResultStatus.AUTHORIZED,
                null,
                null,
                null);

        AuthorizationRecovery recovery = new AuthorizationRecovery(
                result,
                "client-secret-123");

        when(paymentGateway.retrieveAuthorization("pf_test_123"))
                .thenReturn(recovery);

        PaymentAuthorizationRecovery actual = service.recoverAuthorization(order);

        assertEquals(
                paymentId,
                actual.paymentId());

        assertEquals(
                PaymentAuthorizationRecoveryAction.COMPLETED,
                actual.action());

        assertSame(
                result,
                actual.result());

        verify(paymentAuthorizationResultService)
                .apply(org.mockito.ArgumentMatchers.argThat(s -> java.util.Objects.equals(s.orderId(), orderId) && java.util.Objects.equals(s.paymentId(), paymentId)), org.mockito.ArgumentMatchers.eq(result));
    }

    @Test
    void recoverAuthorizationAppliesFailedResult() {

        Long orderId = 100L;
        Long paymentId = 20L;

        when(order.getId())
                .thenReturn(orderId);

        when(paymentService.getRecoverableAuthorizationPaymentId(order))
                .thenReturn(paymentId);

        when(snapshots.authorization(order.getId(), paymentId)).thenAnswer(inv -> PaymentSnapshotFixtures.authorization(inv.getArgument(0), inv.getArgument(1)));

        AuthorizationResult result = new AuthorizationResult(
                AuthorizationResultStatus.FAILED,
                null,
                null,
                null);

        AuthorizationRecovery recovery = new AuthorizationRecovery(
                result,
                "client-secret-123");

        when(paymentGateway.retrieveAuthorization("pf_test_123"))
                .thenReturn(recovery);

        PaymentAuthorizationRecovery actual = service.recoverAuthorization(order);

        assertEquals(
                paymentId,
                actual.paymentId());

        assertEquals(
                PaymentAuthorizationRecoveryAction.FAILED,
                actual.action());

        assertSame(
                result,
                actual.result());

        verify(paymentAuthorizationResultService)
                .apply(org.mockito.ArgumentMatchers.argThat(s -> java.util.Objects.equals(s.orderId(), orderId) && java.util.Objects.equals(s.paymentId(), paymentId)), org.mockito.ArgumentMatchers.eq(result));
    }

    @Test
    void recoverAuthorizationReturnsWaitForPendingResult() {

        Long paymentId = 20L;

        when(paymentService.getRecoverableAuthorizationPaymentId(order))
                .thenReturn(paymentId);

        when(snapshots.authorization(order.getId(), paymentId)).thenAnswer(inv -> PaymentSnapshotFixtures.authorization(inv.getArgument(0), inv.getArgument(1)));

        AuthorizationResult result = new AuthorizationResult(
                AuthorizationResultStatus.PENDING,
                null,
                null,
                null);

        when(paymentGateway.retrieveAuthorization("pf_test_123"))
                .thenReturn(new AuthorizationRecovery(
                        result,
                        "client-secret-123"));

        PaymentAuthorizationRecovery actual = service.recoverAuthorization(order);

        assertEquals(
                PaymentAuthorizationRecoveryAction.WAIT,
                actual.action());

        verify(paymentAuthorizationResultService).apply(org.mockito.ArgumentMatchers.any(PaymentOperationSnapshot.class), org.mockito.ArgumentMatchers.eq(result));
    }

    @Test
    void recoverAuthorizationReturnsResumeCheckoutForRequiresPaymentMethod() {

        Long paymentId = 20L;

        when(paymentService.getRecoverableAuthorizationPaymentId(order))
                .thenReturn(paymentId);

        when(snapshots.authorization(order.getId(), paymentId)).thenAnswer(inv -> PaymentSnapshotFixtures.authorization(inv.getArgument(0), inv.getArgument(1)));

        AuthorizationResult result = new AuthorizationResult(
                AuthorizationResultStatus.REQUIRES_PAYMENT_METHOD,
                null,
                "card_declined",
                "Card was declined");

        when(paymentGateway.retrieveAuthorization("pf_test_123"))
                .thenReturn(new AuthorizationRecovery(
                        result,
                        "client-secret-123"));

        PaymentAuthorizationRecovery actual = service.recoverAuthorization(order);

        assertEquals(
                PaymentAuthorizationRecoveryAction.RESUME_CHECKOUT,
                actual.action());

        verify(paymentAuthorizationResultService).apply(org.mockito.ArgumentMatchers.any(PaymentOperationSnapshot.class), org.mockito.ArgumentMatchers.eq(result));
    }

    @Test
    void recoverAuthorizationReturnsResumeCheckoutForRequiresConfirmation() {

        Long paymentId = 20L;

        when(paymentService.getRecoverableAuthorizationPaymentId(order))
                .thenReturn(paymentId);

        when(snapshots.authorization(order.getId(), paymentId)).thenAnswer(inv -> PaymentSnapshotFixtures.authorization(inv.getArgument(0), inv.getArgument(1)));

        AuthorizationResult result = new AuthorizationResult(
                AuthorizationResultStatus.REQUIRES_CONFIRMATION,
                null,
                null,
                null);

        when(paymentGateway.retrieveAuthorization("pf_test_123"))
                .thenReturn(new AuthorizationRecovery(
                        result,
                        "client-secret-123"));

        PaymentAuthorizationRecovery actual = service.recoverAuthorization(order);

        assertEquals(
                PaymentAuthorizationRecoveryAction.RESUME_CHECKOUT,
                actual.action());

        verify(paymentAuthorizationResultService).apply(org.mockito.ArgumentMatchers.any(PaymentOperationSnapshot.class), org.mockito.ArgumentMatchers.eq(result));
    }

    @Test
    void reusedAuthorizationKeepsTheStartingSnapshot() {
        var snapshot=PaymentSnapshotFixtures.authorization(10L,20L);
        var start=new PaymentAuthorizationStart(20L,100L,3300,"auth-key",PaymentStartDisposition.REUSED,snapshot);
        when(order.getId()).thenReturn(10L);when(startService.start(10L)).thenReturn(start);
        var result=new AuthorizationResult(AuthorizationResultStatus.REQUIRES_ACTION,null,null,null);
        when(paymentGateway.retrieveAuthorization("pf_test_123")).thenReturn(new AuthorizationRecovery(result,"secret"));
        service.prepareAuthorization(order);
        verify(paymentAuthorizationResultService).apply(snapshot,result);
        verifyNoInteractions(snapshots);
    }
}
