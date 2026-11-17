package com.example.ecsite.service.notification;

import static org.assertj.core.api.Assertions.*;

import java.net.ServerSocket;
import java.time.Duration;

import jakarta.mail.Message;

import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.example.ecsite.config.CustomerOrderNotificationProperties;
import com.example.ecsite.service.notification.CustomerOrderNotificationMailClient.Result;

/** Feature133-B: SMTP outcome classification. Only a connection that never opened is retryable. */
class CustomerOrderNotificationMailClientTest {

    private static CustomerOrderNotificationMailClient client(int port, Duration readTimeout) {
        var shared = new JavaMailSenderImpl();
        shared.setHost("127.0.0.1"); shared.setPort(port);
        var properties = new CustomerOrderNotificationProperties(true, Duration.ofSeconds(30), 20, Duration.ofMinutes(5),
                Duration.ofMinutes(5), Duration.ofHours(24),
                new CustomerOrderNotificationProperties.Smtp(Duration.ofSeconds(2), readTimeout, Duration.ofSeconds(2)));
        return new CustomerOrderNotificationMailClient(shared, properties, "no-reply@ec-site.local");
    }

    private static final CustomerOrderNotificationMail MAIL =
            new CustomerOrderNotificationMail("customer@example.test", "【ECサイト】ご注文を承りました（注文番号 1）", "本文です。");

    @Test void aRefusedConnectionIsARetryableFailure() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var outcome = client(port, Duration.ofSeconds(2)).send(MAIL);
        assertThat(outcome.result()).isEqualTo(Result.FAILURE);
        assertThat(outcome.failureCode()).isEqualTo("SMTP_CONNECT_FAILED");
    }

    @Test void aServerThatAcceptsButNeverAnswersIsUncertain() throws Exception {
        try (var silent = new ServerSocket(0)) {
            var outcome = client(silent.getLocalPort(), Duration.ofSeconds(1)).send(MAIL);
            assertThat(outcome.result()).isEqualTo(Result.UNKNOWN);
            assertThat(outcome.failureCode()).isEqualTo("SMTP_SEND_UNCERTAIN");
        }
    }

    @Test void theMessageIsPlainUtf8TextFromTheShopAddress() throws Exception {
        var message = client(25, Duration.ofSeconds(2)).createMessage(MAIL);
        message.saveChanges();
        assertThat(message.getFrom()[0].toString()).isEqualTo("no-reply@ec-site.local");
        assertThat(message.getRecipients(Message.RecipientType.TO)).extracting(Object::toString).containsExactly("customer@example.test");
        assertThat(message.getRecipients(Message.RecipientType.BCC)).isNull();
        assertThat(message.getSubject()).isEqualTo(MAIL.subject());
        assertThat(message.getContentType()).contains("text/plain").containsIgnoringCase("UTF-8");
        assertThat(message.getContent()).isEqualTo("本文です。");
        assertThat(MAIL.toString()).doesNotContain("customer@example.test");
    }
}
