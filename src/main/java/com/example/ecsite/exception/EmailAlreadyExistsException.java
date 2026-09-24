package com.example.ecsite.exception;

public class EmailAlreadyExistsException
        extends RuntimeException {

    public EmailAlreadyExistsException(
            String email,
            Throwable cause) {

        super(
                "メールアドレスは既に使用されています: "
                        + email,
                cause);
    }
}
