package com.example.ecsite.exception;

public class IncorrectCurrentPasswordException extends RuntimeException {

    public IncorrectCurrentPasswordException() {
        super("現在のパスワードが正しくありません。");
    }
}
