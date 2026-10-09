package com.example.ecsite.service.payment;

import java.nio.charset.StandardCharsets;
import java.util.Properties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import com.example.ecsite.config.PaymentDiscrepancyAuditNotificationProperties;

/** Private sender, never registered as a JavaMailSender bean; shared authentication mail is untouched. */
@Component
@ConditionalOnProperty(prefix = "app.payment.discrepancy-audit.notification", name = "enabled", havingValue = "true")
public class PaymentDiscrepancyAuditNotificationMailClient {
    private final JavaMailSenderImpl sender;

    public PaymentDiscrepancyAuditNotificationMailClient(JavaMailSender shared,
            PaymentDiscrepancyAuditNotificationProperties properties) {
        if (!(shared instanceof JavaMailSenderImpl existing))
            throw new IllegalStateException("Notification delivery requires a configurable existing mail sender");
        sender = new JavaMailSenderImpl();
        sender.setHost(existing.getHost()); sender.setPort(existing.getPort()); sender.setProtocol(existing.getProtocol());
        sender.setUsername(existing.getUsername()); sender.setPassword(existing.getPassword());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        // Copy session properties too, including inherited defaults, without sharing mutable state or debug streams.
        var copied = new Properties();
        for (String key : existing.getSession().getProperties().stringPropertyNames())
            copied.setProperty(key, existing.getSession().getProperty(key));
        copied.putAll(existing.getJavaMailProperties());
        copied.setProperty("mail.debug", "false"); copied.setProperty("mail.debug.auth", "false");
        copied.setProperty("mail.smtp.connectiontimeout", Long.toString(properties.smtp().connectionTimeout().toMillis()));
        copied.setProperty("mail.smtp.timeout", Long.toString(properties.smtp().readTimeout().toMillis()));
        copied.setProperty("mail.smtp.writetimeout", Long.toString(properties.smtp().writeTimeout().toMillis()));
        // JavaMailSenderImpl can be configured with smtps; apply the same bounds there as well.
        copied.setProperty("mail.smtps.connectiontimeout", Long.toString(properties.smtp().connectionTimeout().toMillis()));
        copied.setProperty("mail.smtps.timeout", Long.toString(properties.smtp().readTimeout().toMillis()));
        copied.setProperty("mail.smtps.writetimeout", Long.toString(properties.smtp().writeTimeout().toMillis()));
        sender.setJavaMailProperties(copied);
    }

    public void send(PaymentAuditNotificationMail mail) {
        sender.send(createMessage(mail));
    }

    MimeMessage createMessage(PaymentAuditNotificationMail mail) {
        try {
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
            helper.setFrom(mail.from()); helper.setBcc(mail.recipients().toArray(String[]::new));
            helper.setSubject(mail.subject()); helper.setText(mail.body(), false);
            return message;
        } catch (MessagingException failure) {
            // Do not propagate parser text that may contain addresses or message content.
            throw new MailPreparationException("Payment audit notification preparation failed");
        }
    }
}
