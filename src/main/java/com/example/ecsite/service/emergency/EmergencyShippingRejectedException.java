package com.example.ecsite.service.emergency;

/** Feature136: an emergency shipping request refused for a reason the administrator can act on (message is shown). */
public class EmergencyShippingRejectedException extends IllegalStateException {
    public EmergencyShippingRejectedException(String message) {
        super(message);
    }

    public EmergencyShippingRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
