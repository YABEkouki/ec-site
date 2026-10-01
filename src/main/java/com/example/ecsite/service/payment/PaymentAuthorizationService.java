package com.example.ecsite.service.payment;

import org.springframework.stereotype.Service;

import com.example.ecsite.entity.Order;
import com.example.ecsite.payment.AuthorizationPreparation;
import com.example.ecsite.payment.AuthorizationRequest;
import com.example.ecsite.payment.AuthorizationResult;
import com.example.ecsite.payment.PaymentGateway;

@Service
public class PaymentAuthorizationService {

    private final PaymentService paymentService;
    private final PaymentGateway paymentGateway;
    private final PaymentAuthorizationResultService paymentAuthorizationResultService;

    public PaymentAuthorizationService(
            PaymentService paymentService,
            PaymentGateway paymentGateway,
            PaymentAuthorizationResultService paymentAuthorizationResultService) {

        this.paymentService = paymentService;
        this.paymentGateway = paymentGateway;
        this.paymentAuthorizationResultService = paymentAuthorizationResultService;
    }

    public PaymentAuthorizationPreparation prepareAuthorization(
            Order order) {

        PaymentAuthorizationStart start = paymentService.startAuthorization(order);

        AuthorizationPreparation preparation = paymentGateway.prepareAuthorization(
                new AuthorizationRequest(
                        start.amount(),
                        start.idempotencyKey()));

        paymentService.setProviderPaymentId(
                start.paymentId(),
                preparation.providerPaymentId());

        return new PaymentAuthorizationPreparation(
                start.paymentId(),
                preparation.clientSecret());
    }

    public AuthorizationResult refreshAuthorization(
            Long orderId,
            Long paymentId) {

        String providerPaymentId = paymentService.getProviderPaymentId(
                paymentId);

        AuthorizationResult result = paymentGateway.retrieveAuthorization(
                providerPaymentId);

        paymentAuthorizationResultService.apply(
                orderId,
                paymentId,
                result);

        return result;
    }
}
