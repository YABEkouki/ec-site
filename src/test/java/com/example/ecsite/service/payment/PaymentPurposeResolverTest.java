package com.example.ecsite.service.payment;

import com.example.ecsite.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PaymentPurposeResolverTest {
    @Test void allOrderChangePurposesStayOutsideOrdinaryResultApplication() {
        for(var purpose:List.of(PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION,
                PaymentTransactionPurpose.ORDER_CHANGE_OLD_AUTH_CANCEL,PaymentTransactionPurpose.ORDER_CHANGE_NEW_AUTH_ABORT)){
            var p=payment();var type=purpose==PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION?PaymentTransactionType.AUTHORIZE:PaymentTransactionType.CANCEL;
            var t=PaymentTransaction.forOrderChange(p,type,100,0,"change",now,purpose,java.util.UUID.randomUUID());
            ReflectionTestUtils.setField(t,"id",30L);
            assertFalse(resolver.matches(order,p,t,PaymentOperationSnapshot.from(t),List.of(p),List.of(t)));
            assertNotNull(t.getOrderChangeOperationId());
        }
    }
    final PaymentPurposeResolver resolver = new PaymentPurposeResolver();
    final LocalDateTime now = LocalDateTime.of(2026,1,1,10,0);
    final Order order = new Order(1L, 100, now, now.withHour(14));
    Payment payment() {
        ReflectionTestUtils.setField(order,"id",10L);
        var p = new Payment(order,PaymentProvider.PAYJP,PaymentMethod.CARD,100,now);
        ReflectionTestUtils.setField(p,"id",20L);
        return p;
    }
    @Test void uniquePendingLegacyAuthorizationIsRecognizedWithoutWritingPurpose() {
        var p=payment(); var t=new PaymentTransaction(p,PaymentTransactionType.AUTHORIZE,100,0,"key",now);
        ReflectionTestUtils.setField(t,"id",30L);
        assertTrue(resolver.matches(order,p,t,PaymentOperationSnapshot.from(t),List.of(p),List.of(t)));
        assertNull(t.getPurpose());
    }
    @Test void futurePurposeAndIncompleteHistoryAreRejected() {
        var p=payment();var t=new PaymentTransaction(p,PaymentTransactionType.AUTHORIZE,100,0,"key",now);
        ReflectionTestUtils.setField(t,"id",30L);
        assertFalse(resolver.matches(order,p,t,PaymentOperationSnapshot.from(t),List.of(p),List.of()));
        ReflectionTestUtils.setField(t,"purpose",PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION);
        assertFalse(resolver.matches(order,p,t,PaymentOperationSnapshot.from(t),List.of(p),List.of(t)));
    }
    @Test void competingAuthorizationIsNotInferredFromType() {
        var p=payment();var t=new PaymentTransaction(p,PaymentTransactionType.AUTHORIZE,100,0,"key",now);
        ReflectionTestUtils.setField(t,"id",30L);
        var second=new PaymentTransaction(p,PaymentTransactionType.AUTHORIZE,100,0,"other",now);
        ReflectionTestUtils.setField(second,"id",31L);
        assertFalse(resolver.matches(order,p,t,PaymentOperationSnapshot.from(t),List.of(p),List.of(t,second)));
    }
    @Test void cancellationCannotUseAuthorizationForDifferentFlow() {
        var p=payment();p.setProviderPaymentId("pfw_B",now);p.markAuthorized(now);
        var auth=new PaymentTransaction(p,PaymentTransactionType.AUTHORIZE,100,0,"auth",now);
        ReflectionTestUtils.setField(auth,"id",30L);auth.markSucceeded("pfw_A",now);
        var cancel=new PaymentTransaction(p,PaymentTransactionType.CANCEL,100,0,"cancel",now,
                new PaymentTransactionInitiator(PaymentTransactionInitiatorType.ADMIN,1L,"admin",null));
        ReflectionTestUtils.setField(cancel,"id",31L);
        assertFalse(resolver.matches(order,p,cancel,PaymentOperationSnapshot.from(cancel),List.of(p),List.of(auth,cancel)));
    }
    @Test void endedPaymentWithPendingOperationIsNotIgnored() {
        var p=payment();var auth=new PaymentTransaction(p,PaymentTransactionType.AUTHORIZE,100,0,"auth",now);
        ReflectionTestUtils.setField(auth,"id",30L);
        var old=new Payment(order,PaymentProvider.PAYJP,PaymentMethod.CARD,100,now);
        ReflectionTestUtils.setField(old,"id",21L);old.markFailed(now);
        var unresolved=new PaymentTransaction(old,PaymentTransactionType.AUTHORIZE,100,0,"old",now);
        ReflectionTestUtils.setField(unresolved,"id",31L);
        assertFalse(resolver.matches(order,p,auth,PaymentOperationSnapshot.from(auth),List.of(p,old),List.of(auth,unresolved)));
    }
}
