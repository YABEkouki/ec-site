package com.example.ecsite.entity;

/**
 * How an order's change deadline is based.
 * LEGACY: created before Feature127; deadline fixed from ordered_at and never recalculated.
 * AUTHORIZED_ACCEPTANCE: deadline set once from the formal acceptance time.
 */
public enum OrderAcceptanceMode {
    LEGACY,
    AUTHORIZED_ACCEPTANCE
}
