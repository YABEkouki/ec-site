package com.example.ecsite.service;

import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import com.example.ecsite.entity.*;
import com.example.ecsite.repository.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminPaymentAuditNotificationManualResendServiceTest {
    final Instant now=Instant.parse("2026-10-13T00:00:00Z");
    AdminPaymentAuditNotificationManualResendService service(long elapsed,String key){
        var notifications=mock(PaymentAuditNotificationRepository.class);var requests=mock(PaymentAuditNotificationManualResendRepository.class);
        var attempts=mock(PaymentAuditNotificationManualResendAttemptRepository.class);var handlings=mock(PaymentAuditNotificationHandlingRepository.class);var jdbc=mock(JdbcTemplate.class);
        var n=mock(PaymentAuditNotification.class);when(n.getId()).thenReturn(1L);when(n.getStatus()).thenReturn(PaymentAuditNotificationStatus.EXHAUSTED);when(notifications.findById(1L)).thenReturn(Optional.of(n));
        var previous=new PaymentAuditNotificationManualResend();previous.setStatus(PaymentAuditNotificationManualResendStatus.CANCELLED);previous.setRequestedAt(now.minusSeconds(elapsed));
        when(requests.findByNotificationIdOrderByRequestNoAsc(1L)).thenReturn(List.of(previous));when(handlings.findById(1L)).thenReturn(Optional.empty());
        when(handlings.findUnknownAttemptNumbers(1L)).thenReturn(List.of());when(attempts.findUnknownIdsByNotificationId(1L)).thenReturn(List.of());when(jdbc.queryForObject("select clock_timestamp()",Timestamp.class)).thenReturn(Timestamp.from(now));
        return new AdminPaymentAuditNotificationManualResendService(notifications,requests,attempts,handlings,new PaymentAuditNotificationManualResendToken(key,Clock.fixed(now,ZoneOffset.UTC)),jdbc,true);
    }
    @ParameterizedTest @ValueSource(longs={1799,1800,1801}) void exactDatabaseTimeBoundary(long elapsed){
        var result=service(elapsed,Base64.getEncoder().encodeToString(new byte[32])).prepare(1,7);
        assertThat(result.eligible()).isEqualTo(elapsed>=1800);
        if(elapsed<1800)assertThat(result.rejection()).isEqualTo(PaymentAuditNotificationManualResendRejectedException.Reason.TOO_SOON);
        else {assertThat(result.snapshotToken()).isNotNull();assertThat(result.toString()).doesNotContain(result.snapshotToken());}
    }
    @Test void noKeyRemainsReadOnly(){var result=service(1800,"").prepare(1,7);assertThat(result.eligible()).isTrue();assertThat(result.signingAvailable()).isFalse();assertThat(result.snapshotToken()).isNull();}
}
