package com.example.ecsite;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class PasswordEncoderTest {

    @Test
    void generatePassword() {
        BCryptPasswordEncoder encoder =
                new BCryptPasswordEncoder();

        String encodedPassword =
                encoder.encode("password");

        System.out.println(encodedPassword);
    }
}