package com.example.ecsite.service.orderchange;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OldCancelRecoveryObservationTest {
    final Instant start=Instant.parse("2026-10-27T00:00:00Z");
    @ParameterizedTest @CsvSource({"0,0","5000000000,5"})
    void acceptsAtMostFiveSecondsFromStart(long elapsed,long wall){
        var observed=new OldCancelRecovery.Observation(null,null,null,start,start,100,100);
        observed.consume(Clock.fixed(start.plusSeconds(wall),ZoneOffset.UTC),()->100+elapsed);
        assertThatThrownBy(()->observed.consume(Clock.fixed(start.plusSeconds(wall),ZoneOffset.UTC),()->100+elapsed)).isInstanceOf(IllegalStateException.class);
    }
    @ParameterizedTest @CsvSource({"5000000001,5","-1,0","1,6","1,-1"})
    void rejectsExpiredNegativeOrFutureObservations(long elapsed,long wall){
        var observed=new OldCancelRecovery.Observation(null,null,null,start,start,100,100);
        assertThatThrownBy(()->observed.consume(Clock.fixed(start.plusSeconds(wall),ZoneOffset.UTC),()->100+elapsed)).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsCompletionBeforeStart(){
        var observed=new OldCancelRecovery.Observation(null,null,null,start,start.minusSeconds(1),100,101);
        assertThatThrownBy(()->observed.consume(Clock.fixed(start,ZoneOffset.UTC),()->102)).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsNegativeMeasuredGetDuration(){
        var observed=new OldCancelRecovery.Observation(null,null,null,start,start,100,99);
        assertThatThrownBy(()->observed.consume(Clock.fixed(start,ZoneOffset.UTC),()->102)).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsClockEarlierThanGetCompletion(){
        var observed=new OldCancelRecovery.Observation(null,null,null,start,start.plusSeconds(1),100,101);
        assertThatThrownBy(()->observed.consume(Clock.fixed(start,ZoneOffset.UTC),()->102)).isInstanceOf(IllegalStateException.class);
    }
}
