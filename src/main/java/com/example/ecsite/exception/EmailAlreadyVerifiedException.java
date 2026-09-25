package com.example.ecsite.exception;

public class EmailAlreadyVerifiedException extends RuntimeException {

    public EmailAlreadyVerifiedException() {
        super("メールアドレスはすでに確認済みです。");
    }
}
