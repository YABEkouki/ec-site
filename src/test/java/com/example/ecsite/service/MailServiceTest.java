package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(MockitoExtension.class)
class MailServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @Test
    void sendEmailVerificationSendsVerificationMail() {

        MailService service = new MailService(
                mailSender,
                "no-reply@ec-site.local",
                "http://localhost:8080");

        service.sendEmailVerification(
                "user@example.com",
                "test-token");

        ArgumentCaptor<SimpleMailMessage> captor =
                ArgumentCaptor.forClass(SimpleMailMessage.class);

        verify(mailSender).send(captor.capture());

        SimpleMailMessage message = captor.getValue();

        assertEquals(
                "no-reply@ec-site.local",
                message.getFrom());

        assertNotNull(message.getTo());
        assertEquals(
                "user@example.com",
                message.getTo()[0]);

        assertEquals(
                "メールアドレスの確認",
                message.getSubject());

        assertNotNull(message.getText());

        String expectedUrl =
                "http://localhost:8080/email/verify?token=test-token";

        org.junit.jupiter.api.Assertions.assertTrue(
                message.getText().contains(expectedUrl));

        org.junit.jupiter.api.Assertions.assertTrue(
                message.getText().contains("24時間"));
    }
}
