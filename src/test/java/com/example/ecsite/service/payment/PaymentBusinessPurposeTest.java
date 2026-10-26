package com.example.ecsite.service.payment;

import com.example.ecsite.entity.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentBusinessPurposeTest {
    @Test void purposeIsExplicitAndLegacyRemainsNull() {
        var payment = mock(Payment.class);
        var legacy = new PaymentTransaction(payment, PaymentTransactionType.AUTHORIZE, 100, 0, "key", LocalDateTime.of(2026,1,1,0,0));
        assertNull(legacy.getPurpose());
        var explicit = new PaymentTransaction(payment, PaymentTransactionType.AUTHORIZE, 100, 0, "key", LocalDateTime.of(2026,1,1,0,0), PaymentTransactionPurpose.INITIAL_ORDER_AUTHORIZATION);
        assertEquals(PaymentTransactionPurpose.INITIAL_ORDER_AUTHORIZATION, explicit.getPurpose());
        assertThrows(IllegalArgumentException.class, () -> new PaymentTransaction(payment, PaymentTransactionType.CANCEL, 100, 0, "key", LocalDateTime.of(2026,1,1,0,0), PaymentTransactionPurpose.INITIAL_ORDER_AUTHORIZATION));
    }
}
