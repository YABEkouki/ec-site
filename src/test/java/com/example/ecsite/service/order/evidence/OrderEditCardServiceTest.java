package com.example.ecsite.service.order.evidence;
import java.util.*;
import org.junit.jupiter.api.*;
import com.example.ecsite.config.PayJpProperties;
import com.example.ecsite.payment.*;
import com.example.ecsite.payment.payjp.PayJpPaymentFlowObservation;
import com.example.ecsite.service.orderchange.OrderReauthorizationTransactions;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
class OrderEditCardServiceTest {
    OrderReauthorizationTransactions tx=mock(OrderReauthorizationTransactions.class);
    PaymentGateway gateway=mock(PaymentGateway.class);PayJpProperties provider=mock(PayJpProperties.class);
    UUID receipt=UUID.randomUUID(),operation=UUID.randomUUID();
    OrderEditCardService service=new OrderEditCardService(tx,gateway,provider,"http://localhost:8080");
    @Test void mapperCompatiblePendingResultCanIssueExactlyOneRedactedCard(){
        var target=new OrderReauthorizationTransactions.BrowserTarget(receipt,operation,1,2,10,20,"pfw_new",7000,1,3,2);
        var observed=new PayJpPaymentFlowObservation("pfw_new","requires_payment_method",7000L,"jpy",0L,0L,"manual",false,null,null,Map.of());
        when(tx.prepareOrderEditBrowser(1,2,receipt)).thenReturn(Optional.of(target));when(gateway.observeOrderChangeFlow("pfw_new")).thenReturn(observed);
        // The real mapper intentionally leaves providerTransactionId null before authorization.
        when(gateway.retrieveAuthorization("pfw_new")).thenReturn(new AuthorizationRecovery(new AuthorizationResult(AuthorizationResultStatus.REQUIRES_PAYMENT_METHOD,null,null,null),"private_widget_secret"));
        when(provider.publicKey()).thenReturn("pk_test_dummy");when(tx.issueOrderEditBrowser(eq(target),eq(observed),anyLong())).thenReturn(true,false);
        var card=service.start(1,2,receipt);assertThat(card).isPresent();assertThat(card.get().toString()).doesNotContain("private_widget_secret");
        assertThat(card.get().returnUrl()).isEqualTo("http://localhost:8080/orders/1/edit/"+receipt+"/payment/return");
        assertThat(service.start(1,2,receipt)).isEmpty();
    }
    @Test void mismatchedRecoveredFlowCannotIssue(){
        var target=new OrderReauthorizationTransactions.BrowserTarget(receipt,operation,1,2,10,20,"pfw_new",7000,1,3,2);
        when(tx.prepareOrderEditBrowser(1,2,receipt)).thenReturn(Optional.of(target));
        when(gateway.observeOrderChangeFlow("pfw_new")).thenReturn(new PayJpPaymentFlowObservation("pfw_new","requires_payment_method",7000L,"jpy",0L,0L,"manual",false,null,null,Map.of()));
        when(gateway.retrieveAuthorization("pfw_new")).thenReturn(new AuthorizationRecovery(new AuthorizationResult(AuthorizationResultStatus.REQUIRES_PAYMENT_METHOD,"pfw_other",null,null),"private_widget_secret"));
        assertThat(service.start(1,2,receipt)).isEmpty();verify(tx,never()).issueOrderEditBrowser(any(),any(),anyLong());
    }
    @Test void unavailableTargetNeverCallsProvider(){
        when(tx.prepareOrderEditBrowser(1,2,receipt)).thenReturn(Optional.empty());
        assertThat(service.start(1,2,receipt)).isEmpty();verifyNoInteractions(gateway);
    }
    @Test void transportFailureDoesNotIssueOrRetry(){
        var target=new OrderReauthorizationTransactions.BrowserTarget(receipt,operation,1,2,10,20,"pfw_new",7000,1,3,2);
        when(tx.prepareOrderEditBrowser(1,2,receipt)).thenReturn(Optional.of(target));
        when(gateway.observeOrderChangeFlow("pfw_new")).thenThrow(new IllegalStateException("mock timeout"));
        assertThat(service.start(1,2,receipt)).isEmpty();verify(tx,never()).issueOrderEditBrowser(any(),any(),anyLong());
        verify(gateway,times(1)).observeOrderChangeFlow("pfw_new");verifyNoMoreInteractions(gateway);
    }
}
