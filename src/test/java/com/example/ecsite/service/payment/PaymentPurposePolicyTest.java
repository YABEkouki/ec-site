package com.example.ecsite.service.payment;

import static org.junit.jupiter.api.Assertions.*;
import static com.example.ecsite.entity.PaymentTransactionPurpose.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.example.ecsite.entity.PaymentTransactionPurpose;

class PaymentPurposePolicyTest {
    private final PaymentPurposePolicy policy = new PaymentPurposePolicy();

    @ParameterizedTest
    @CsvSource({"AUTHORIZE,INITIAL_ORDER_AUTHORIZATION", "CANCEL,ORDER_CANCELLATION", "CAPTURE,SHIPMENT_CAPTURE"})
    void permitsOnlyExistingBusinessOperations(String type, PaymentTransactionPurpose purpose) {
        assertTrue(policy.isValidCombination(type, purpose));
        assertTrue(policy.isUsableInStage1A(type, purpose));
    }

    @ParameterizedTest
    @CsvSource({"AUTHORIZE,ORDER_CHANGE_AUTHORIZATION", "CANCEL,ORDER_CHANGE_OLD_AUTH_CANCEL", "CANCEL,ORDER_CHANGE_NEW_AUTH_ABORT"})
    void recognisesFutureCombinationWithoutEnablingIt(String type, PaymentTransactionPurpose purpose) {
        assertTrue(policy.isValidCombination(type, purpose));
        assertFalse(policy.isUsableInStage1A(type, purpose));
    }

    @Test
    void rejectsMismatchedUnknownNullAndLegacyCombinations() {
        for (var purpose : PaymentTransactionPurpose.values()) {
            assertFalse(policy.isUsableInStage1A("REFUND", purpose));
            assertFalse(policy.isValidCombination("FUTURE", purpose));
            assertFalse(policy.isValidCombination(null, purpose));
        }
        assertFalse(policy.isValidCombination("CAPTURE", INITIAL_ORDER_AUTHORIZATION));
        assertFalse(policy.isValidCombination("AUTHORIZE", ORDER_CANCELLATION));
        assertFalse(policy.isValidCombination("CANCEL", SHIPMENT_CAPTURE));
        assertFalse(policy.isValidCombination("CAPTURE", ORDER_CHANGE_NEW_AUTH_ABORT));
        assertFalse(policy.isUsableInStage1A("AUTHORIZE", LEGACY_UNKNOWN));
        assertFalse(policy.isValidCombination("AUTHORIZE", LEGACY_UNKNOWN));
        assertFalse(policy.isValidCombination("AUTHORIZE", null));
    }
}
