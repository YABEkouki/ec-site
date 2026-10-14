package com.example.ecsite.service.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.example.ecsite.entity.*;
import com.example.ecsite.payment.*;

class PaymentOperationSyncServiceTest {
    PaymentGateway gateway=mock(PaymentGateway.class);
    PaymentAuthorizationResultService authorization=mock(PaymentAuthorizationResultService.class);
    PaymentCaptureResultService capture=mock(PaymentCaptureResultService.class);
    PaymentCancellationResultService userCancel=mock(PaymentCancellationResultService.class);
    AdminPaymentCancellationResultService adminCancel=mock(AdminPaymentCancellationResultService.class);
    PaymentOperationSyncService service=new PaymentOperationSyncService(gateway,authorization,capture,userCancel,adminCancel);
    Logger logger=(Logger)LoggerFactory.getLogger(PaymentOperationSyncService.class);
    ListAppender<ILoggingEvent> events;Level previous;
    PaymentOperationSnapshot snapshot=PaymentSnapshotFixtures.of(10L,20L,100L,PaymentTransactionType.CAPTURE,"do-not-log-key",3300,0,"pf",PaymentTransactionInitiatorType.ADMIN,30L,"saved","memo");
    @BeforeEach void setup(){previous=logger.getLevel();logger.setLevel(Level.DEBUG);events=new ListAppender<>();events.start();logger.addAppender(events);}
    @AfterEach void cleanup(){logger.detachAppender(events);events.stop();logger.setLevel(previous);}
    @ParameterizedTest @EnumSource(PaymentResultApplication.class)
    void classificationsHaveDistinctSeverityWithoutLoggingRequestKeys(PaymentResultApplication outcome){
        when(gateway.retrievePaymentFlow("pf")).thenReturn(new PaymentFlowState("pf",PaymentFlowStatus.SUCCEEDED,null,null));
        when(capture.apply(any(),any())).thenReturn(outcome);
        assertThat(service.synchronize(snapshot)).isEqualTo(PaymentSynchronizationOutcome.from(outcome));
        assertThat(events.list).hasSize(1);
        var event=events.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(outcome==PaymentResultApplication.CONFLICT?Level.WARN:outcome==PaymentResultApplication.ALREADY_APPLIED?Level.DEBUG:Level.INFO);
        assertThat(event.getFormattedMessage()).contains("outcome="+outcome,"transactionId=100","paymentId=20").doesNotContain("do-not-log-key");
        assertThat(event.getThrowableProxy()).isNull();
    }
    @Test void uncertainProviderStateIsWaitingAndStillRevalidatesExactTransaction(){
        when(gateway.retrievePaymentFlow("pf")).thenReturn(new PaymentFlowState("pf",PaymentFlowStatus.REQUIRES_CAPTURE,null,null));
        var result=new CaptureResult(CaptureResultStatus.PENDING,"pf",null,null);
        when(capture.apply(snapshot,result)).thenReturn(PaymentResultApplication.APPLIED);
        assertThat(service.synchronize(snapshot)).isEqualTo(PaymentSynchronizationOutcome.WAITING);
        verify(capture).apply(snapshot,result);
        assertThat(events.list.getFirst().getLevel()).isEqualTo(Level.DEBUG);
    }
    @Test void actualGatewayFailurePropagatesAndLogsOperationIdentity(){
        when(gateway.retrievePaymentFlow("pf")).thenThrow(new PaymentGatewayException("network failed"));
        assertThatThrownBy(()->service.synchronize(snapshot)).isInstanceOf(PaymentGatewayException.class);
        verifyNoInteractions(capture);
        assertThat(events.list).hasSize(1);assertThat(events.list.getFirst().getLevel()).isEqualTo(Level.ERROR);
        assertThat(events.list.getFirst().getFormattedMessage()).contains("transactionId=100");
    }
}
