package com.example.ecsite.service.order.history;

/** Stored yen amounts only; tax is included in total, not added to it. */
public record CustomerOrderChangeAmounts(int itemSubtotal, int shipping, int taxAmount, int totalAmount) {}
