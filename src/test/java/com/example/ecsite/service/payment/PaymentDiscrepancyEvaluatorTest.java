package com.example.ecsite.service.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.example.ecsite.entity.PaymentStatus;
import com.example.ecsite.payment.PaymentFlowStatus;

class PaymentDiscrepancyEvaluatorTest {

    private final PaymentDiscrepancyEvaluator evaluator =
            new PaymentDiscrepancyEvaluator();

    @ParameterizedTest
    @CsvSource({
            // PENDING
            "PENDING, REQUIRES_PAYMENT_METHOD, CONSISTENT",
            "PENDING, REQUIRES_CONFIRMATION, CONSISTENT",
            "PENDING, REQUIRES_ACTION, INCONSISTENT",
            "PENDING, PROCESSING, IN_PROGRESS",
            "PENDING, REQUIRES_CAPTURE, INCONSISTENT",
            "PENDING, SUCCEEDED, INCONSISTENT",
            "PENDING, CANCELED, INCONSISTENT",

            // REQUIRES_ACTION
            "REQUIRES_ACTION, REQUIRES_PAYMENT_METHOD, INCONSISTENT",
            "REQUIRES_ACTION, REQUIRES_CONFIRMATION, INCONSISTENT",
            "REQUIRES_ACTION, REQUIRES_ACTION, CONSISTENT",
            "REQUIRES_ACTION, PROCESSING, IN_PROGRESS",
            "REQUIRES_ACTION, REQUIRES_CAPTURE, INCONSISTENT",
            "REQUIRES_ACTION, SUCCEEDED, INCONSISTENT",
            "REQUIRES_ACTION, CANCELED, INCONSISTENT",

            // AUTHORIZED
            "AUTHORIZED, REQUIRES_PAYMENT_METHOD, INCONSISTENT",
            "AUTHORIZED, REQUIRES_CONFIRMATION, INCONSISTENT",
            "AUTHORIZED, REQUIRES_ACTION, INCONSISTENT",
            "AUTHORIZED, PROCESSING, INCONSISTENT",
            "AUTHORIZED, REQUIRES_CAPTURE, CONSISTENT",
            "AUTHORIZED, SUCCEEDED, INCONSISTENT",
            "AUTHORIZED, CANCELED, INCONSISTENT",

            // CAPTURED
            "CAPTURED, REQUIRES_PAYMENT_METHOD, INCONSISTENT",
            "CAPTURED, REQUIRES_CONFIRMATION, INCONSISTENT",
            "CAPTURED, REQUIRES_ACTION, INCONSISTENT",
            "CAPTURED, PROCESSING, INCONSISTENT",
            "CAPTURED, REQUIRES_CAPTURE, INCONSISTENT",
            "CAPTURED, SUCCEEDED, CONSISTENT",
            "CAPTURED, CANCELED, INCONSISTENT",

            // CANCELLED
            "CANCELLED, REQUIRES_PAYMENT_METHOD, INCONSISTENT",
            "CANCELLED, REQUIRES_CONFIRMATION, INCONSISTENT",
            "CANCELLED, REQUIRES_ACTION, INCONSISTENT",
            "CANCELLED, PROCESSING, INCONSISTENT",
            "CANCELLED, REQUIRES_CAPTURE, INCONSISTENT",
            "CANCELLED, SUCCEEDED, INCONSISTENT",
            "CANCELLED, CANCELED, CONSISTENT",

            // FAILED
            "FAILED, REQUIRES_PAYMENT_METHOD, INCONSISTENT",
            "FAILED, REQUIRES_CONFIRMATION, INCONSISTENT",
            "FAILED, REQUIRES_ACTION, INCONSISTENT",
            "FAILED, PROCESSING, INCONSISTENT",
            "FAILED, REQUIRES_CAPTURE, INCONSISTENT",
            "FAILED, SUCCEEDED, INCONSISTENT",
            "FAILED, CANCELED, CONSISTENT"
    })
    void evaluatesPaymentAndProviderStatuses(
            PaymentStatus localStatus,
            PaymentFlowStatus providerStatus,
            PaymentDiscrepancyStatus expected) {

        assertEquals(
                expected,
                evaluator.evaluate(localStatus, providerStatus));
    }
}
