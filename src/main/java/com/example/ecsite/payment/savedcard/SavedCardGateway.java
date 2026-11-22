package com.example.ecsite.payment.savedcard;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Feature139: PAY.JP customers, saved cards (PaymentMethods) and Setup Flows. Every POST carries the caller's
 * idempotency key, which the caller records before sending. Never called inside a database transaction.
 * Card numbers never pass through here: cards are entered only in payments.js.
 */
public interface SavedCardGateway {

    /** Empty when PAY.JP answers 404. */
    Optional<Customer> findCustomer(String customerId);

    Customer createCustomer(String customerId, long userId, String idempotencyKey);

    /** Cards currently attached to the customer (detached cards are filtered out). */
    List<Card> listCards(String customerId);

    /** Empty when PAY.JP answers 404. */
    Optional<Card> findCard(String paymentMethodId);

    SetupStart createSetupFlow(String customerId, long userId, long operationId, String idempotencyKey);

    /** Empty when PAY.JP answers 404. */
    Optional<SetupState> findSetupFlow(String setupFlowId);

    /** Setup Flows of the customer whose metadata carries the operation id (recovery of an unconfirmed start). */
    List<SetupState> findSetupFlowsByOperation(String customerId, long operationId);

    Card detach(String paymentMethodId, String idempotencyKey);

    Customer setDefaultCard(String customerId, String paymentMethodId, String idempotencyKey);

    record Customer(String id, String defaultPaymentMethodId) {}

    /** Display facts only; the fingerprint is used in memory to refuse duplicate cards and is never stored. */
    record Card(String id, String customerId, String brand, String last4, int expMonth, int expYear,
            String fingerprint, OffsetDateTime createdAt, OffsetDateTime detachedAt) {
        public boolean attachedTo(String customer) {
            return detachedAt == null && customer != null && customer.equals(customerId);
        }
        @Override public String toString() {
            return "Card[" + id + "]";
        }
    }

    record SetupStart(String id, String clientSecret) {
        @Override public String toString() {
            return "SetupStart[" + id + ", redacted]";
        }
    }

    record SetupState(String id, String customerId, String status, String paymentMethodId) {}
}
