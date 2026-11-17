package com.example.ecsite.service.notification;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import com.example.ecsite.config.CustomerOrderNotificationProperties;

/**
 * Feature133-B: private SMTP sender with bounded timeouts, never registered as a JavaMailSender bean, so the
 * registration and password mails are untouched. Only failures that prove the server never accepted the message
 * are retryable; anything after the message may have been handed over is UNKNOWN and never resent automatically.
 */
@Component
@ConditionalOnProperty(prefix = "app.customer-notification", name = "enabled", havingValue = "true")
public class CustomerOrderNotificationMailClient {

    public enum Result { SUCCESS, FAILURE, UNKNOWN }

    public record Outcome(Result result, String failureCode) {
        static Outcome success() { return new Outcome(Result.SUCCESS, null); }
    }

    private final JavaMailSenderImpl sender;
    private final String from;

    public CustomerOrderNotificationMailClient(JavaMailSender shared, CustomerOrderNotificationProperties properties,
            @Value("${app.mail.from}") String from) {
        if (!(shared instanceof JavaMailSenderImpl existing))
            throw new IllegalStateException("Customer notification delivery requires a configurable mail sender");
        this.from = from;
        sender = new JavaMailSenderImpl();
        sender.setHost(existing.getHost()); sender.setPort(existing.getPort()); sender.setProtocol(existing.getProtocol());
        sender.setUsername(existing.getUsername()); sender.setPassword(existing.getPassword());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        var copied = new Properties();
        for (String key : existing.getSession().getProperties().stringPropertyNames())
            copied.setProperty(key, existing.getSession().getProperty(key));
        copied.putAll(existing.getJavaMailProperties());
        copied.setProperty("mail.debug", "false"); copied.setProperty("mail.debug.auth", "false");
        var smtp = properties.smtp();
        for (String protocol : new String[] {"smtp", "smtps"}) {
            copied.setProperty("mail." + protocol + ".connectiontimeout", Long.toString(smtp.connectionTimeout().toMillis()));
            copied.setProperty("mail." + protocol + ".timeout", Long.toString(smtp.readTimeout().toMillis()));
            copied.setProperty("mail." + protocol + ".writetimeout", Long.toString(smtp.writeTimeout().toMillis()));
        }
        sender.setJavaMailProperties(copied);
    }

    /** Never throws; SMTP diagnostics (addresses, message text) never leave this method. */
    public Outcome send(CustomerOrderNotificationMail mail) {
        MimeMessage message;
        try {
            message = createMessage(mail);
        } catch (RuntimeException failure) {
            return new Outcome(Result.FAILURE, "MESSAGE_PREPARATION_FAILED");
        }
        try {
            sender.send(message);
            return Outcome.success();
        } catch (MailAuthenticationException failure) {
            return new Outcome(Result.FAILURE, "SMTP_AUTHENTICATION_FAILED");
        } catch (RuntimeException failure) {
            return neverConnected(failure) ? new Outcome(Result.FAILURE, "SMTP_CONNECT_FAILED")
                    : new Outcome(Result.UNKNOWN, "SMTP_SEND_UNCERTAIN");
        } catch (Error failure) {
            return new Outcome(Result.UNKNOWN, "SMTP_SEND_UNCERTAIN");
        }
    }

    MimeMessage createMessage(CustomerOrderNotificationMail mail) {
        try {
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
            helper.setFrom(from); helper.setTo(mail.recipient());
            helper.setSubject(mail.subject()); helper.setText(mail.body(), false);
            return message;
        } catch (MessagingException failure) {
            throw new MailPreparationException("Customer notification preparation failed");
        }
    }

    /** True only when the connection itself could not be opened, so no message data reached the server. */
    static boolean neverConnected(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof UnknownHostException) return true;
            if (t instanceof jakarta.mail.MessagingException m && m.getNextException() != null
                    && neverConnected(m.getNextException())) return true;
            if (t instanceof org.springframework.mail.MailSendException s)
                for (Exception inner : s.getFailedMessages().values()) if (neverConnected(inner)) return true;
            if (t.getCause() == t) break;
        }
        return false;
    }
}
