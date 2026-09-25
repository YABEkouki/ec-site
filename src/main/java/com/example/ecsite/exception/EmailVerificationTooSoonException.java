package com.example.ecsite.exception;

public class EmailVerificationTooSoonException extends RuntimeException {

    public EmailVerificationTooSoonException() {
        super("確認メールを再送するまで、しばらくお待ちください。");
    }
}
