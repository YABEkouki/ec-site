package com.example.ecsite.service.unpaidclosure;

/** The closure was not started; the message is shown to the administrator. */
public class UnpaidOrderClosureException extends RuntimeException {
    public UnpaidOrderClosureException(String message) {
        super(message);
    }
}
