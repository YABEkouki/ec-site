package com.example.ecsite.service.warehouse;

/** Thrown inside the per-order export transaction; the whole transaction rolls back and nothing is written. */
public class WarehouseExportHoldException extends RuntimeException {

    /** Why an order is not exported now (design doc ch.8: never export what cannot be proven safe). */
    public enum Reason {
        /** Shipping preparation was started by an administrator (emergency shipping, ch.5); never auto-exported. */
        MANUALLY_PREPARED,
        /** The order is not ORDERED/PAID (cancelled, shipped, ...). */
        NOT_EXPORTABLE_STATUS,
        /** An order change operation or order-edit admission is unresolved. */
        ORDER_CHANGE_UNRESOLVED,
        /** The payment state does not prove a single valid original authorization (or a settled manual payment). */
        PAYMENT_NOT_READY,
        /** Not formally accepted, before deadline + 5 minutes, or the deadline is after the batch's business date. */
        NOT_DUE,
        /** The application judged the order exportable but a V86 database guard rejected it. */
        DATABASE_GUARD
    }

    private final Reason reason;

    public WarehouseExportHoldException(Reason reason, Throwable cause) {
        super("Warehouse export held: " + reason, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
