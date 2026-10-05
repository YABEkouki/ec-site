package com.example.ecsite.service.payment;

import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.example.ecsite.entity.Payment;
import com.example.ecsite.entity.PaymentProvider;
import com.example.ecsite.entity.PaymentStatus;
import com.example.ecsite.payment.AuthorizationRecovery;
import com.example.ecsite.payment.AuthorizationResult;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.repository.PaymentRepository;

@Service
public class PayJpWebhookAuthorizationSyncService {

    private static final Set<String> AUTHORIZATION_EVENTS = Set.of(
            "payment_flow.requires_action",
            "payment_flow.processing",
            "payment_flow.amount_capturable_updated",
            "payment_flow.payment_failed");

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentAuthorizationResultService resultService;

    public PayJpWebhookAuthorizationSyncService(
            PaymentRepository paymentRepository,
            PaymentGateway paymentGateway,
            PaymentAuthorizationResultService resultService) {

        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.resultService = resultService;
    }

    public void synchronize(
            String eventType,
            String providerPaymentId) {

        if (!AUTHORIZATION_EVENTS.contains(eventType)) {
            return;
        }

        Optional<Payment> paymentOptional =
                paymentRepository.findByProviderAndProviderPaymentId(
                        PaymentProvider.PAYJP,
                        providerPaymentId);

        if (paymentOptional.isEmpty()) {
            return;
        }

        Payment payment = paymentOptional.get();

        if (payment.getStatus() != PaymentStatus.PENDING
                && payment.getStatus() != PaymentStatus.REQUIRES_ACTION) {
            return;
        }

        AuthorizationRecovery recovery =
                paymentGateway.retrieveAuthorization(providerPaymentId);

        AuthorizationResult result = recovery.result();

        resultService.apply(
                payment.getOrder().getId(),
                payment.getId(),
                result);
    }
}
