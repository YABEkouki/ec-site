package com.example.ecsite.exception;

public class UsernameAlreadyExistsException
        extends RuntimeException {

    public UsernameAlreadyExistsException(
            String username,
            Throwable cause) {

        super(
                "ユーザー名は既に使用されています: "
                        + username,
                cause);
    }
}