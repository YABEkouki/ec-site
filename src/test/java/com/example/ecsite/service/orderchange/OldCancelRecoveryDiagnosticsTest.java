package com.example.ecsite.service.orderchange;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;

class OldCancelRecoveryDiagnosticsTest {
    @Test void warnContainsOnlyAllowlistedCodesUuidAndDurationsWithoutExceptionPayload(){
        var logger=(Logger)LoggerFactory.getLogger(OldCancelRecoveryDiagnostics.class);
        var captured=new ListAppender<ILoggingEvent>();captured.start();logger.addAppender(captured);
        var prior=logger.getLevel();logger.setLevel(Level.WARN);
        try{
            var request=UUID.randomUUID();
            OldCancelRecoveryDiagnostics.emit(OldCancelRecoveryDiagnostics.LOG,new OldCancelRecoveryDiagnostics.Entry(request,
                OldCancelRecoveryDiagnostics.Stage.CONSUME,OldCancelRecoveryDiagnostics.Reason.OBSERVATION_STALE,5001,37));
            assertThat(captured.list).singleElement().satisfies(e->{
                assertThat(e.getLevel()).isEqualTo(Level.WARN);assertThat(e.getTimeStamp()).isPositive();
                assertThat(e.getFormattedMessage()).isEqualTo("old_cancel_recovery request="+request+" stage=CONSUME reason=OBSERVATION_STALE observation_elapsed_ms=5001 lock_revalidation_ms=37");
                assertThat(e.getThrowableProxy()).isNull();
            });
        }finally{logger.detachAppender(captured);logger.setLevel(prior);captured.stop();}
    }
    @Test void diagnosticSinkFailureIsNonThrowing(){
        assertThatCode(()->OldCancelRecoveryDiagnostics.emit(e->{throw new IllegalStateException("SECRET_message");},
            new OldCancelRecoveryDiagnostics.Entry(UUID.randomUUID(),OldCancelRecoveryDiagnostics.Stage.GET,
                OldCancelRecoveryDiagnostics.Reason.GET_UNKNOWN,-1,-1))).doesNotThrowAnyException();
    }
    @Test void observationReportsMeasuredLockAndValidationTime(){
        var start=java.time.Instant.EPOCH;
        var o=new OldCancelRecovery.Observation(null,null,null,start,start,0,1_000_000);
        o.lockStarted(2_000_000);o.revalidated(39_000_000);
        assertThat(o.elapsedMs(100_000_000)).isEqualTo(100);assertThat(o.lockMs(100_000_000)).isEqualTo(37);
        assertThat(o.elapsedMs(-1)).isEqualTo(-1);
    }
}
