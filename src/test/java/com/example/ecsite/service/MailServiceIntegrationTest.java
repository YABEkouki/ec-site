package com.example.ecsite.service;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled("Mailpitを使用する手動疎通確認用テスト")
class MailServiceIntegrationTest {

    @Autowired
    private MailService mailService;

    @Test
    void sendEmailVerificationToMailpit() {

        mailService.sendEmailVerification(
                "test@example.com",
                "mailpit-test-token");
    }
}
