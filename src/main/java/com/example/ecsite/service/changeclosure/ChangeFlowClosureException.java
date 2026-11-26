package com.example.ecsite.service.changeclosure;

/** The closure was not started; the message is shown to the administrator. */
public class ChangeFlowClosureException extends RuntimeException {
    public ChangeFlowClosureException(String message) {
        super(message);
    }
}
