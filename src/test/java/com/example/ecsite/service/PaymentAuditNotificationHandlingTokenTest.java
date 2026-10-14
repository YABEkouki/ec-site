package com.example.ecsite.service;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PaymentAuditNotificationHandlingTokenTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-09T00:00:00Z"), ZoneOffset.UTC);
    private PaymentAuditNotificationHandlingToken tokens(Clock c) {
        return new PaymentAuditNotificationHandlingToken(Base64.getEncoder().encodeToString(new byte[32]), c);
    }
    @Test void roundTripNormalizesUnknowns() {
        var t=tokens(clock); String token=t.issue(1,2,false,0,List.of(3,1,3));
        assertThat(t.verify(token,1,2).unknowns()).containsExactly(1,3);
    }
    @Test void rejectsTamperingAndWrongIdentity() {
        var t=tokens(clock); String token=t.issue(1,2,true,4,List.of(1));
        assertThatThrownBy(()->t.verify(token+"x",1,2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->t.verify(token,3,2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->t.verify(token,1,3)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsExpiredMalformedAndMissingKey() {
        String token=tokens(clock).issue(1,2,false,0,List.of());
        assertThatThrownBy(()->tokens(Clock.offset(clock,Duration.ofMinutes(15))).verify(token,1,2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->tokens(clock).verify("bad",1,2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PaymentAuditNotificationHandlingToken("",clock).issue(1,2,false,0,List.of())).isInstanceOf(IllegalStateException.class);
    }
    @Test void exactWhitespaceAndCodePointLimit() {
        for(int cp:new int[]{9,10,11,12,13,32,0x85,0xa0,0x1680,0x2000,0x2001,0x2002,0x2003,0x2004,0x2005,0x2006,0x2007,0x2008,0x2009,0x200a,0x200b,0x2028,0x2029,0x202f,0x205f,0x3000})
            assertThatThrownBy(()->PaymentAuditNotificationHandlingComments.validate(new String(Character.toChars(cp)),true)).isInstanceOf(IllegalArgumentException.class);
        PaymentAuditNotificationHandlingComments.validate("😀".repeat(1000),true);
        assertThatThrownBy(()->PaymentAuditNotificationHandlingComments.validate("😀".repeat(1001),true)).isInstanceOf(IllegalArgumentException.class);
        PaymentAuditNotificationHandlingComments.validate(null,false);
    }
    @Test void manualIdsAreSeparateAndNormalized() {
        var t=tokens(clock);String token=t.issue(1,2,false,0,List.of(3,1),List.of(3L,1L,3L));
        assertThat(t.verify(token,1,2).unknowns()).containsExactly(1,3);
        assertThat(t.verify(token,1,2).manualUnknowns()).containsExactly(1L,3L);
    }
    @Test void rejectsAuthenticallySignedLegacyToken() throws Exception {
        byte[] payload="1|1|2|false|0||1791504000|1791504900".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(new byte[32],"HmacSHA256"));
        String old=Base64.getUrlEncoder().withoutPadding().encodeToString(payload)+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload));
        assertThatThrownBy(()->tokens(clock).verify(old,1,2)).isInstanceOf(IllegalArgumentException.class);
    }
}
