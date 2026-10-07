package com.example.ecsite.service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.Payment;
import com.example.ecsite.entity.PaymentProvider;
import com.example.ecsite.entity.PaymentTransaction;
import com.example.ecsite.entity.PaymentTransactionInitiatorType;
import com.example.ecsite.entity.PaymentTransactionStatus;
import com.example.ecsite.entity.PaymentTransactionType;
import com.example.ecsite.payment.AuthorizationRecovery;
import com.example.ecsite.payment.AuthorizationResult;
import com.example.ecsite.payment.AuthorizationResultStatus;
import com.example.ecsite.payment.PaymentFlowState;
import com.example.ecsite.payment.PaymentFlowStatus;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.repository.PaymentTransactionRepository;

class PaymentReconciliationItemServiceTest {

    private PaymentTransactionRepository paymentTransactionRepository;
    private PaymentGateway paymentGateway;
    private PaymentAuthorizationResultService authorizationResultService;
    private PaymentReconciliationItemService service;
    private PaymentCaptureResultService captureResultService;
    private PaymentCancellationResultService userCancellationResultService;
    private AdminPaymentCancellationResultService adminCancellationResultService;

    @BeforeEach
    void setUp() {

        paymentTransactionRepository = mock(PaymentTransactionRepository.class);

        paymentGateway = mock(PaymentGateway.class);

        authorizationResultService = mock(PaymentAuthorizationResultService.class);

        captureResultService = mock(PaymentCaptureResultService.class);

        userCancellationResultService = mock(PaymentCancellationResultService.class);

        adminCancellationResultService = mock(AdminPaymentCancellationResultService.class);

        service = new PaymentReconciliationItemService(
                paymentTransactionRepository,
                paymentGateway,
                authorizationResultService,
                captureResultService,
                userCancellationResultService,
                adminCancellationResultService);
    }

    @Test
    void pendingAuthorizationRefetchesLatestStateAndAppliesIt() {

        PaymentTransaction transaction = mock(PaymentTransaction.class);
        Payment payment = mock(Payment.class);
        Order order = mock(Order.class);

        when(paymentTransactionRepository.findById(100L))
                .thenReturn(Optional.of(transaction));

        when(transaction.getStatus())
                .thenReturn(PaymentTransactionStatus.PENDING);

        when(transaction.getTransactionType())
                .thenReturn(PaymentTransactionType.AUTHORIZE);

        when(transaction.getPayment())
                .thenReturn(payment);

        when(payment.getProvider())
                .thenReturn(PaymentProvider.PAYJP);

        when(payment.getProviderPaymentId())
                .thenReturn("pf_test_123");

        when(payment.getId())
                .thenReturn(20L);

        when(payment.getOrder())
                .thenReturn(order);

        when(order.getId())
                .thenReturn(10L);

        AuthorizationResult result = new AuthorizationResult(
                AuthorizationResultStatus.AUTHORIZED,
                "pf_test_123",
                null,
                null);

        when(paymentGateway.retrieveAuthorization("pf_test_123"))
                .thenReturn(new AuthorizationRecovery(
                        result,
                        null));

        service.reconcile(100L);

        verify(paymentGateway)
                .retrieveAuthorization("pf_test_123");

        verify(authorizationResultService)
                .apply(
                        10L,
                        20L,
                        result);
    }

    @Test
    void completedTransactionDoesNotCallPayJp() {

        PaymentTransaction transaction = mock(PaymentTransaction.class);

        when(paymentTransactionRepository.findById(100L))
                .thenReturn(Optional.of(transaction));

        when(transaction.getStatus())
                .thenReturn(PaymentTransactionStatus.SUCCEEDED);

        service.reconcile(100L);

        verify(paymentGateway, never())
                .retrieveAuthorization(
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void missingTransactionDoesNotCallPayJp() {

        when(paymentTransactionRepository.findById(100L))
                .thenReturn(Optional.empty());

        service.reconcile(100L);

        verify(paymentGateway, never())
                .retrieveAuthorization(
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void nonPayJpAuthorizationDoesNotCallPayJp() {

        PaymentTransaction transaction = mock(PaymentTransaction.class);
        Payment payment = mock(Payment.class);

        when(paymentTransactionRepository.findById(100L))
                .thenReturn(Optional.of(transaction));

        when(transaction.getStatus())
                .thenReturn(PaymentTransactionStatus.PENDING);

        when(transaction.getTransactionType())
                .thenReturn(PaymentTransactionType.AUTHORIZE);

        when(transaction.getPayment())
                .thenReturn(payment);

        when(payment.getProvider())
                .thenReturn(PaymentProvider.MOCK);

        service.reconcile(100L);

        verify(paymentGateway, never())
                .retrieveAuthorization(
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void authorizationWithoutProviderPaymentIdDoesNotCallPayJp() {

        PaymentTransaction transaction = mock(PaymentTransaction.class);
        Payment payment = mock(Payment.class);

        when(paymentTransactionRepository.findById(100L))
                .thenReturn(Optional.of(transaction));

        when(transaction.getStatus())
                .thenReturn(PaymentTransactionStatus.PENDING);

        when(transaction.getTransactionType())
                .thenReturn(PaymentTransactionType.AUTHORIZE);

        when(transaction.getPayment())
                .thenReturn(payment);

        when(payment.getProvider())
                .thenReturn(PaymentProvider.PAYJP);

        when(payment.getProviderPaymentId())
                .thenReturn(null);

        service.reconcile(100L);

        verify(paymentGateway, never())
                .retrieveAuthorization(
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void pendingCaptureWithSucceededPaymentFlowAppliesCaptureResult() {

        PaymentTransaction transaction = mock(PaymentTransaction.class);
        Payment payment = mock(Payment.class);
        Order order = mock(Order.class);

        when(paymentTransactionRepository.findById(100L))
                .thenReturn(Optional.of(transaction));

        when(transaction.getStatus())
                .thenReturn(PaymentTransactionStatus.PENDING);

        when(transaction.getTransactionType())
                .thenReturn(PaymentTransactionType.CAPTURE);

        when(transaction.getPayment())
                .thenReturn(payment);

        when(transaction.getInitiatorType())
                .thenReturn(PaymentTransactionInitiatorType.ADMIN);

        when(transaction.getInitiatorId())
                .thenReturn(30L);

        when(transaction.getInitiatorUsername())
                .thenReturn("admin");

        when(transaction.getInternalNote())
                .thenReturn("発送");

        when(payment.getProvider())
                .thenReturn(PaymentProvider.PAYJP);

        when(payment.getProviderPaymentId())
                .thenReturn("pf_capture_123");

        when(payment.getId())
                .thenReturn(20L);

        when(payment.getOrder())
                .thenReturn(order);

        when(order.getId())
                .thenReturn(10L);

        when(paymentGateway.retrievePaymentFlow("pf_capture_123"))
                .thenReturn(new PaymentFlowState(
                        "pf_capture_123",
                        PaymentFlowStatus.SUCCEEDED,
                        null,
                        null));

        service.reconcile(100L);

        verify(paymentGateway)
                .retrievePaymentFlow("pf_capture_123");

        verify(captureResultService)
                .apply(
                        org.mockito.ArgumentMatchers.eq(10L),
                        org.mockito.ArgumentMatchers.eq(20L),
                        org.mockito.ArgumentMatchers.eq(30L),
                        org.mockito.ArgumentMatchers.eq("admin"),
                        org.mockito.ArgumentMatchers.eq("発送"),
                        org.mockito.ArgumentMatchers.argThat(
                                result -> result.status() == com.example.ecsite.payment.CaptureResultStatus.CAPTURED
                                        && "pf_capture_123".equals(
                                                result.providerTransactionId())));
    }

    @Test
    void pendingCaptureWithRequiresCapturePaymentFlowDoesNotApplyResult() {

        PaymentTransaction transaction = mock(PaymentTransaction.class);
        Payment payment = mock(Payment.class);

        when(paymentTransactionRepository.findById(100L))
                .thenReturn(Optional.of(transaction));

        when(transaction.getStatus())
                .thenReturn(PaymentTransactionStatus.PENDING);

        when(transaction.getTransactionType())
                .thenReturn(PaymentTransactionType.CAPTURE);

        when(transaction.getPayment())
                .thenReturn(payment);

        when(transaction.getInitiatorType())
                .thenReturn(PaymentTransactionInitiatorType.ADMIN);

        when(payment.getProvider())
                .thenReturn(PaymentProvider.PAYJP);

        when(payment.getProviderPaymentId())
                .thenReturn("pf_capture_123");

        when(paymentGateway.retrievePaymentFlow("pf_capture_123"))
                .thenReturn(new PaymentFlowState(
                        "pf_capture_123",
                        PaymentFlowStatus.REQUIRES_CAPTURE,
                        null,
                        null));

        service.reconcile(100L);

        verify(paymentGateway)
                .retrievePaymentFlow("pf_capture_123");

        verify(captureResultService, never())
                .apply(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void captureWithoutAdminInitiatorDoesNotCallPayJp() {

        PaymentTransaction transaction = mock(PaymentTransaction.class);
        Payment payment = mock(Payment.class);

        when(paymentTransactionRepository.findById(100L))
                .thenReturn(Optional.of(transaction));

        when(transaction.getStatus())
                .thenReturn(PaymentTransactionStatus.PENDING);

        when(transaction.getTransactionType())
                .thenReturn(PaymentTransactionType.CAPTURE);

        when(transaction.getPayment())
                .thenReturn(payment);

        when(payment.getProvider())
                .thenReturn(PaymentProvider.PAYJP);

        when(transaction.getInitiatorType())
                .thenReturn(PaymentTransactionInitiatorType.USER);

        service.reconcile(100L);

        verify(paymentGateway, never())
                .retrievePaymentFlow(
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void pendingUserCancellationWithCanceledPaymentFlowAppliesUserResult() {

        PaymentTransaction transaction = mock(PaymentTransaction.class);
        Payment payment = mock(Payment.class);
        Order order = mock(Order.class);

        when(paymentTransactionRepository.findById(100L))
                .thenReturn(Optional.of(transaction));

        when(transaction.getStatus())
                .thenReturn(PaymentTransactionStatus.PENDING);
        when(transaction.getTransactionType())
                .thenReturn(PaymentTransactionType.CANCEL);
        when(transaction.getPayment())
                .thenReturn(payment);
        when(transaction.getInitiatorType())
                .thenReturn(PaymentTransactionInitiatorType.USER);
        when(transaction.getInitiatorId())
                .thenReturn(30L);
        when(transaction.getInitiatorUsername())
                .thenReturn("user");

        when(payment.getProvider())
                .thenReturn(PaymentProvider.PAYJP);
        when(payment.getProviderPaymentId())
                .thenReturn("pf_cancel_123");
        when(payment.getId())
                .thenReturn(20L);
        when(payment.getOrder())
                .thenReturn(order);
        when(order.getId())
                .thenReturn(10L);

        when(paymentGateway.retrievePaymentFlow("pf_cancel_123"))
                .thenReturn(new PaymentFlowState(
                        "pf_cancel_123",
                        PaymentFlowStatus.CANCELED,
                        null,
                        null));

        service.reconcile(100L);

        verify(userCancellationResultService)
                .apply(
                        org.mockito.ArgumentMatchers.eq(10L),
                        org.mockito.ArgumentMatchers.eq(20L),
                        org.mockito.ArgumentMatchers.eq(30L),
                        org.mockito.ArgumentMatchers.eq("user"),
                        org.mockito.ArgumentMatchers.argThat(
                                result -> result
                                        .status() == com.example.ecsite.payment.CancellationResultStatus.CANCELLED
                                        && "pf_cancel_123".equals(
                                                result.providerTransactionId())));

        verify(adminCancellationResultService, never())
                .apply(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void pendingAdminCancellationWithCanceledPaymentFlowAppliesAdminResult() {

        PaymentTransaction transaction = mock(PaymentTransaction.class);
        Payment payment = mock(Payment.class);
        Order order = mock(Order.class);

        when(paymentTransactionRepository.findById(100L))
                .thenReturn(Optional.of(transaction));

        when(transaction.getStatus())
                .thenReturn(PaymentTransactionStatus.PENDING);
        when(transaction.getTransactionType())
                .thenReturn(PaymentTransactionType.CANCEL);
        when(transaction.getPayment())
                .thenReturn(payment);
        when(transaction.getInitiatorType())
                .thenReturn(PaymentTransactionInitiatorType.ADMIN);
        when(transaction.getInitiatorId())
                .thenReturn(30L);
        when(transaction.getInitiatorUsername())
                .thenReturn("admin");
        when(transaction.getInternalNote())
                .thenReturn("管理者キャンセル");

        when(payment.getProvider())
                .thenReturn(PaymentProvider.PAYJP);
        when(payment.getProviderPaymentId())
                .thenReturn("pf_cancel_123");
        when(payment.getId())
                .thenReturn(20L);
        when(payment.getOrder())
                .thenReturn(order);
        when(order.getId())
                .thenReturn(10L);

        when(paymentGateway.retrievePaymentFlow("pf_cancel_123"))
                .thenReturn(new PaymentFlowState(
                        "pf_cancel_123",
                        PaymentFlowStatus.CANCELED,
                        null,
                        null));

        service.reconcile(100L);

        verify(adminCancellationResultService)
                .apply(
                        org.mockito.ArgumentMatchers.eq(10L),
                        org.mockito.ArgumentMatchers.eq(20L),
                        org.mockito.ArgumentMatchers.eq(30L),
                        org.mockito.ArgumentMatchers.eq("admin"),
                        org.mockito.ArgumentMatchers.eq("管理者キャンセル"),
                        org.mockito.ArgumentMatchers.argThat(
                                result -> result
                                        .status() == com.example.ecsite.payment.CancellationResultStatus.CANCELLED));

        verify(userCancellationResultService, never())
                .apply(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void pendingCancellationWithNonTerminalPaymentFlowDoesNotApplyResult() {

        PaymentTransaction transaction = mock(PaymentTransaction.class);
        Payment payment = mock(Payment.class);

        when(paymentTransactionRepository.findById(100L))
                .thenReturn(Optional.of(transaction));

        when(transaction.getStatus())
                .thenReturn(PaymentTransactionStatus.PENDING);
        when(transaction.getTransactionType())
                .thenReturn(PaymentTransactionType.CANCEL);
        when(transaction.getPayment())
                .thenReturn(payment);
        when(transaction.getInitiatorType())
                .thenReturn(PaymentTransactionInitiatorType.USER);

        when(payment.getProvider())
                .thenReturn(PaymentProvider.PAYJP);
        when(payment.getProviderPaymentId())
                .thenReturn("pf_cancel_123");

        when(paymentGateway.retrievePaymentFlow("pf_cancel_123"))
                .thenReturn(new PaymentFlowState(
                        "pf_cancel_123",
                        PaymentFlowStatus.REQUIRES_CAPTURE,
                        null,
                        null));

        service.reconcile(100L);

        verify(userCancellationResultService, never())
                .apply(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());

        verify(adminCancellationResultService, never())
                .apply(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void cancellationWithoutSupportedInitiatorDoesNotCallPayJp() {

        PaymentTransaction transaction = mock(PaymentTransaction.class);
        Payment payment = mock(Payment.class);

        when(paymentTransactionRepository.findById(100L))
                .thenReturn(Optional.of(transaction));

        when(transaction.getStatus())
                .thenReturn(PaymentTransactionStatus.PENDING);
        when(transaction.getTransactionType())
                .thenReturn(PaymentTransactionType.CANCEL);
        when(transaction.getPayment())
                .thenReturn(payment);

        when(payment.getProvider())
                .thenReturn(PaymentProvider.PAYJP);

        when(transaction.getInitiatorType())
                .thenReturn(null);

        service.reconcile(100L);

        verify(paymentGateway, never())
                .retrievePaymentFlow(
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void reconcileRunsWithinTransaction() throws Exception {

        Method method = PaymentReconciliationItemService.class
                .getMethod("reconcile", Long.class);

        assertThat(method.isAnnotationPresent(Transactional.class))
                .isTrue();
    }

}
