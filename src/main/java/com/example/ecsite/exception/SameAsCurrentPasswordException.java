package com.example.ecsite.exception;

public class SameAsCurrentPasswordException extends RuntimeException {

    public SameAsCurrentPasswordException() {
        super("新しいパスワードには現在のパスワードと異なるパスワードを入力してください。");
    }
}
