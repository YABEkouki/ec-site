package com.example.ecsite.exception;

public class EmailNotRegisteredException extends RuntimeException {

    public EmailNotRegisteredException() {
        super("メールアドレスが登録されていません。");
    }
}
