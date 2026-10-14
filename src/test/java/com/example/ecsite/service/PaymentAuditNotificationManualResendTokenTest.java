package com.example.ecsite.service;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PaymentAuditNotificationManualResendTokenTest {
    final String key=Base64.getEncoder().encodeToString(new byte[32]);
    final Instant now=Instant.parse("2026-10-13T00:00:00Z");
    PaymentAuditNotificationManualResendToken tokens(Instant time){return new PaymentAuditNotificationManualResendToken(key,Clock.fixed(time,ZoneOffset.UTC));}
    @Test void validAndIdentityBound(){var t=tokens(now); String token=t.issue(1,7,UUID.randomUUID(),"generation",false,0); assertThat(t.verify(token,1,7).generation()).isEqualTo("generation"); assertThatThrownBy(()->t.verify(token,2,7)).isInstanceOf(IllegalArgumentException.class); assertThatThrownBy(()->t.verify(token,1,8)).isInstanceOf(IllegalArgumentException.class);}
    @Test void tamperedMalformedExpiredAndFuture(){String token=tokens(now).issue(1,7,UUID.randomUUID(),"generation",true,4); for(String bad:List.of("", "invalid",token.substring(1),token+"a")) assertThatThrownBy(()->tokens(now).verify(bad,1,7)).isInstanceOf(IllegalArgumentException.class); assertThatThrownBy(()->tokens(now.plusSeconds(900)).verify(token,1,7)).isInstanceOf(IllegalArgumentException.class); assertThatThrownBy(()->tokens(now.minusSeconds(1)).verify(token,1,7)).isInstanceOf(IllegalArgumentException.class);}
    @Test void purposeSeparatedAndMissingKey(){String handling=new PaymentAuditNotificationHandlingToken(key,Clock.fixed(now,ZoneOffset.UTC)).issue(1,7,false,0,List.of()); assertThatThrownBy(()->tokens(now).verify(handling,1,7)).isInstanceOf(IllegalArgumentException.class); var missing=new PaymentAuditNotificationManualResendToken("",Clock.systemUTC()); assertThat(missing.isConfigured()).isFalse(); assertThatThrownBy(()->missing.issue(1,7,UUID.randomUUID(),"generation",false,0)).isInstanceOf(IllegalStateException.class);}
}
