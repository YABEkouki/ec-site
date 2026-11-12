package com.example.ecsite.service.order.history;

/** Caller must own a read-only REPEATABLE READ transaction. No unauthorised read entry point. */
public interface CustomerOrderChangeEvidenceReader {
    OrderChangeEvidenceBundle readForCustomer(long orderId,long customerId);
}
