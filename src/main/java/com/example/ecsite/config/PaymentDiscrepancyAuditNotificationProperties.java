package com.example.ecsite.config;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.AddressException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.payment.discrepancy-audit.notification")
public record PaymentDiscrepancyAuditNotificationProperties(
        @DefaultValue("false") boolean enabled,
        List<String> recipients,
        @DefaultValue("1m") Duration fixedDelay,
        @DefaultValue("15m") Duration noHistoryGracePeriod,
        @DefaultValue("5m") Duration retryDelay,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("3m") Duration claimLease,
        @DefaultValue Smtp smtp) {
    private static final String PREFIX = "app.payment.discrepancy-audit.notification.";

    public PaymentDiscrepancyAuditNotificationProperties {
        recipients = normalizeRecipients(recipients);
        if (enabled && recipients.isEmpty()) invalid("recipients", "通知ON時は1件以上20件以下が必要です");
        duration("fixed-delay", fixedDelay, Duration.ofSeconds(10), Duration.ofHours(1));
        duration("no-history-grace-period", noHistoryGracePeriod, Duration.ofMinutes(1), Duration.ofHours(24));
        duration("retry-delay", retryDelay, Duration.ofMinutes(1), Duration.ofHours(24));
        duration("claim-lease", claimLease, Duration.ofSeconds(30), Duration.ofMinutes(30));
        if (maxAttempts < 1 || maxAttempts > 10) invalid("max-attempts", "1以上10以下で指定してください");
        if (smtp == null) invalid("smtp", "設定が必要です");
        if (claimLease.compareTo(smtp.sendDeadline().plusMinutes(1)) < 0)
            invalid("claim-lease", "smtp.send-deadlineより1分以上長く指定してください");
    }

    private static List<String> normalizeRecipients(List<String> values) {
        var normalized = new LinkedHashSet<String>();
        if (values != null) for (String value : values) {
            if (value == null || value.chars().anyMatch(Character::isISOControl))
                invalid("recipients", "制御文字を含まないメールアドレスを指定してください");
            String address = value.trim();
            if (address.isEmpty()) continue;
            if (address.length() > 254) invalid("recipients", "254文字以内で指定してください");
            try {
                InternetAddress parsed = new InternetAddress(address, true);
                parsed.validate();
                if (parsed.getPersonal() != null || parsed.isGroup() || !address.equals(parsed.getAddress())
                        || address.indexOf('@') < 1)
                    invalid("recipients", "表示名のない単一メールアドレスを指定してください");
            } catch (AddressException failure) {
                // Never propagate the invalid address or parser exception text.
                invalid("recipients", "有効な単一メールアドレスを指定してください");
            }
            int at = address.lastIndexOf('@');
            normalized.add(address.substring(0, at) + address.substring(at).toLowerCase(Locale.ROOT));
        }
        if (normalized.size() > 20) invalid("recipients", "20件以下で指定してください");
        return List.copyOf(normalized);
    }

    private static void duration(String name, Duration value, Duration minimum, Duration maximum) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0)
            invalid(name, minimum + "以上" + maximum + "以下で指定してください");
    }

    private static void invalid(String name, String message) {
        throw new IllegalArgumentException(PREFIX + name + "は" + message);
    }

    @Override public String toString() {
        return "PaymentDiscrepancyAuditNotificationProperties[enabled=" + enabled + ", recipientCount="
                + recipients.size() + "]";
    }

    public record Smtp(
            @DefaultValue("5s") Duration connectionTimeout,
            @DefaultValue("10s") Duration readTimeout,
            @DefaultValue("10s") Duration writeTimeout,
            @DefaultValue("2m") Duration sendDeadline) {
        public Smtp {
            duration("smtp.connection-timeout", connectionTimeout, Duration.ofSeconds(1), Duration.ofSeconds(30));
            duration("smtp.read-timeout", readTimeout, Duration.ofSeconds(1), Duration.ofSeconds(60));
            duration("smtp.write-timeout", writeTimeout, Duration.ofSeconds(1), Duration.ofSeconds(60));
            duration("smtp.send-deadline", sendDeadline, Duration.ofSeconds(30), Duration.ofMinutes(10));
            if (connectionTimeout.compareTo(sendDeadline) > 0 || readTimeout.compareTo(sendDeadline) > 0
                    || writeTimeout.compareTo(sendDeadline) > 0)
                invalid("smtp.send-deadline", "各SMTPタイムアウト以上で指定してください");
        }
    }
}
