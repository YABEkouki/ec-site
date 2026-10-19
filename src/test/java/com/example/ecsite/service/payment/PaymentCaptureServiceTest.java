package com.example.ecsite.service.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import com.example.ecsite.entity.*;
import com.example.ecsite.payment.*;
import com.example.ecsite.payment.capture.*;
import org.junit.jupiter.api.*;

class PaymentCaptureServiceTest {
    PaymentCaptureStartService start=mock(PaymentCaptureStartService.class);
    CaptureInitialPostPreparationService preparation=mock(CaptureInitialPostPreparationService.class);
    PaymentCaptureResultService results=mock(PaymentCaptureResultService.class);
    PaymentGateway gateway=mock(PaymentGateway.class);
    Instant now=Instant.parse("2026-10-19T06:00:00Z");
    PaymentCaptureService service=new PaymentCaptureService(start,gateway,results,preparation,Clock.fixed(now,ZoneOffset.UTC));
    CaptureShipmentOperations.InitialPost capability=mock(CaptureShipmentOperations.InitialPost.class);
    PaymentOperationSnapshot snapshot=new PaymentOperationSnapshot(10L,20L,40L,PaymentTransactionType.CAPTURE,"key",5500,0,"pfw_flow",PaymentProvider.PAYJP,PaymentTransactionStatus.PENDING,PaymentTransactionInitiatorType.ADMIN,30L,"admin","memo");
    CaptureHttpRequest http=CaptureHttpRequest.create(new CaptureConnectionIdentity("test","account","TEST","https://api.pay.jp"),"pfw_flow",5500,"key");
    @BeforeEach void setup() {lenient().when(capability.snapshot()).thenReturn(snapshot);}
    PaymentCaptureStart operation(PaymentStartDisposition disposition) {return new PaymentCaptureStart(20L,40L,"pfw_flow",5500,"key",disposition,snapshot,disposition==PaymentStartDisposition.NEW?capability:null);}
    CaptureResult run() { return service.captureForShipment(10L,30L,"admin","memo"); }
    void fresh() {
        var created=operation(PaymentStartDisposition.NEW);
        when(start.start(10L,30L,"admin","memo")).thenReturn(created);
        when(preparation.prepare(capability)).thenReturn(new CaptureInitialPostPreparationService.Ready(http,now.minusSeconds(1),now.plusSeconds(1)));
    }
    @Test void newUsesExactlyThePreparedPersistedBytesThenSharedResultApplication() {
        fresh();var result=new CaptureResult(CaptureResultStatus.CAPTURED,"pfw_flow",null,null);
        when(gateway.capture(http)).thenReturn(result);when(results.apply(snapshot,result)).thenReturn(PaymentResultApplication.APPLIED);
        assertThat(run()).isSameAs(result);
        var sequence=inOrder(start,preparation,gateway,results);
        sequence.verify(start).start(10L,30L,"admin","memo");sequence.verify(preparation).prepare(capability);
        sequence.verify(gateway).capture(same(http));sequence.verify(results).apply(snapshot,result);
    }
    @Test void gatewayFailureDoesNotApplyCaptureResult() {
        fresh();when(gateway.capture(http)).thenThrow(new PaymentGatewayException("timeout"));
        assertThatThrownBy(this::run).isInstanceOf(PaymentGatewayException.class);verifyNoInteractions(results);
    }
    @Test void preparationFailureNeverCallsGateway() {
        fresh();when(preparation.prepare(capability)).thenThrow(new IllegalStateException("invalid"));
        assertThatThrownBy(this::run).isInstanceOf(IllegalStateException.class);verifyNoInteractions(gateway,results);
    }
    @Test void webhookSuccessBetweenStartAndPreparationSuppressesPost() {
        fresh();when(preparation.prepare(capability)).thenReturn(new CaptureInitialPostPreparationService.AlreadyApplied());
        assertThat(run().status()).isEqualTo(CaptureResultStatus.CAPTURED);verifyNoInteractions(gateway,results);
    }
    @Test void deadlineIsCheckedAgainImmediatelyBeforeHttp() {
        fresh();when(preparation.prepare(capability)).thenReturn(new CaptureInitialPostPreparationService.Ready(http,now.minusSeconds(1),now));
        assertThatThrownBy(this::run).isInstanceOf(IllegalStateException.class);verifyNoInteractions(gateway,results);
    }
    @Test void reusedOnlyGetsAndDoesNotRequireNewConfigurationOrRequest() {
        when(start.start(10L,30L,"admin","memo")).thenReturn(operation(PaymentStartDisposition.REUSED));
        when(gateway.retrievePaymentFlow("pfw_flow")).thenReturn(new PaymentFlowState("pfw_flow",PaymentFlowStatus.SUCCEEDED,null,null));
        when(results.apply(eq(snapshot),any())).thenReturn(PaymentResultApplication.APPLIED);
        assertThat(run().status()).isEqualTo(CaptureResultStatus.CAPTURED);
        verify(gateway,never()).capture(any());verifyNoInteractions(preparation);
    }
    @Test void alreadyAppliedHasNoGatewayOrPreparation() {
        when(start.start(10L,30L,"admin","memo")).thenReturn(operation(PaymentStartDisposition.ALREADY_APPLIED));
        assertThat(run().status()).isEqualTo(CaptureResultStatus.CAPTURED);verifyNoInteractions(preparation,gateway,results);
    }
    @Test void reusedWithoutProviderIdCannotCallGateway() {
        when(start.start(10L,30L,"admin","memo")).thenReturn(new PaymentCaptureStart(20L,40L,null,5500,"key",PaymentStartDisposition.REUSED,snapshot));
        assertThatThrownBy(this::run).isInstanceOf(IllegalStateException.class);verifyNoInteractions(gateway,results,preparation);
    }
    @Test void pendingAloneCannotIssueInitialPostCapability() {
        assertThatIllegalArgumentException().isThrownBy(()->new PaymentCaptureStart(20L,40L,"pfw_flow",5500,"key",PaymentStartDisposition.NEW,snapshot));
        assertThatIllegalArgumentException().isThrownBy(()->new PaymentCaptureStart(20L,40L,"pfw_flow",5500,"key",PaymentStartDisposition.REUSED,snapshot,capability));
    }
}
