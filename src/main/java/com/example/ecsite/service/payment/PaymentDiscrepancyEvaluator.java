package com.example.ecsite.service.payment;

import org.springframework.stereotype.Component;

import com.example.ecsite.entity.PaymentStatus;
import com.example.ecsite.payment.PaymentFlowStatus;

@Component
public class PaymentDiscrepancyEvaluator {

    public PaymentDiscrepancyStatus evaluate(
            PaymentStatus localStatus,
            PaymentFlowStatus providerStatus) {

        return switch (localStatus) {
            case PENDING -> switch (providerStatus) {
                case REQUIRES_PAYMENT_METHOD,
                     REQUIRES_CONFIRMATION ->
                        PaymentDiscrepancyStatus.CONSISTENT;
                case PROCESSING ->
                        PaymentDiscrepancyStatus.IN_PROGRESS;
                default ->
                        PaymentDiscrepancyStatus.INCONSISTENT;
            };

            case REQUIRES_ACTION -> switch (providerStatus) {
                case REQUIRES_ACTION ->
                        PaymentDiscrepancyStatus.CONSISTENT;
                case PROCESSING ->
                        PaymentDiscrepancyStatus.IN_PROGRESS;
                default ->
                        PaymentDiscrepancyStatus.INCONSISTENT;
            };

            case AUTHORIZED ->
                    providerStatus == PaymentFlowStatus.REQUIRES_CAPTURE
                            ? PaymentDiscrepancyStatus.CONSISTENT
                            : PaymentDiscrepancyStatus.INCONSISTENT;

            case CAPTURED ->
                    providerStatus == PaymentFlowStatus.SUCCEEDED
                            ? PaymentDiscrepancyStatus.CONSISTENT
                            : PaymentDiscrepancyStatus.INCONSISTENT;

            case CANCELLED, FAILED ->
                    providerStatus == PaymentFlowStatus.CANCELED
                            ? PaymentDiscrepancyStatus.CONSISTENT
                            : PaymentDiscrepancyStatus.INCONSISTENT;
        };
    }
}
