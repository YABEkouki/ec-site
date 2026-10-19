package com.example.ecsite.service.payment;

/** Identifies the creating request; this is not a durable execution lease. */
public enum PaymentStartDisposition {
    NEW,
    REUSED,
    ALREADY_APPLIED
}
