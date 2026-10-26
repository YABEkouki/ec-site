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
    @Test void completedChangedAuthorizationSupportsOrdinaryCancellationOnlyWithExplicitBinding() {
        var p=payment();p.setProviderPaymentId("pfw_changed",now);p.markAuthorized(now);
        var change=java.util.UUID.randomUUID();
        var auth=PaymentTransaction.forOrderChange(p,PaymentTransactionType.AUTHORIZE,100,0,"changed",now,
                PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION,change);
        ReflectionTestUtils.setField(auth,"id",30L);auth.markSucceeded("pfw_changed",now);
        var cancel=new PaymentTransaction(p,PaymentTransactionType.CANCEL,100,0,"cancel",now,
                new PaymentTransactionInitiator(PaymentTransactionInitiatorType.ADMIN,1L,"admin",null));
        ReflectionTestUtils.setField(cancel,"id",31L);
        var history=List.of(auth,cancel);var snapshot=PaymentOperationSnapshot.from(cancel);
        assertFalse(resolver.matches(order,p,cancel,snapshot,List.of(p),history,java.util.Set.of(change)));
        order.bindActivePayment(p);
        assertFalse(resolver.matches(order,p,cancel,snapshot,List.of(p),history,java.util.Set.of()));
        assertTrue(resolver.matches(order,p,cancel,snapshot,List.of(p),history,java.util.Set.of(change)));
        assertFalse(resolver.matches(order,p,auth,PaymentOperationSnapshot.from(auth),List.of(p),history,java.util.Set.of(change)));
    }
    @Test void exactTerminalOrdinaryCancellationReplaysAfterChangedAuthorizationBindingCleared() {
        var p=payment();p.setProviderPaymentId("changed",now);p.markAuthorized(now);order.bindActivePayment(p);
        var change=java.util.UUID.randomUUID();
        var auth=PaymentTransaction.forOrderChange(p,PaymentTransactionType.AUTHORIZE,100,0,"changed-auth",now,
                PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION,change);
        ReflectionTestUtils.setField(auth,"id",30L);auth.markSucceeded("changed",now);
        var cancel=new PaymentTransaction(p,PaymentTransactionType.CANCEL,100,0,"ordinary-cancel",now,
                new PaymentTransactionInitiator(PaymentTransactionInitiatorType.ADMIN,1L,"admin",null),PaymentTransactionPurpose.ORDER_CANCELLATION);
        ReflectionTestUtils.setField(cancel,"id",31L);var snapshot=PaymentOperationSnapshot.from(cancel);
        cancel.markSucceeded("changed",now);p.markCancelled(now);order.clearActivePaymentAfterTermination(p.getId());
        ReflectionTestUtils.setField(order,"status",OrderStatus.CANCELLED);
        assertTrue(resolver.matches(order,p,cancel,snapshot,List.of(p),List.of(auth,cancel),java.util.Set.of(change)));
        assertFalse(resolver.matches(order,p,cancel,snapshot,List.of(p),List.of(auth,cancel),java.util.Set.of()));
        var aborted=java.util.UUID.randomUUID();
        var closed=new Payment(order,PaymentProvider.PAYJP,PaymentMethod.CARD,100,now);
        ReflectionTestUtils.setField(closed,"id",21L);closed.markFailed(now);
        var failed=PaymentTransaction.forOrderChange(closed,PaymentTransactionType.AUTHORIZE,100,0,"aborted-auth",now,
                PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION,aborted);
        ReflectionTestUtils.setField(failed,"id",32L);failed.markFailed(null,"canceled","canceled",now);
        assertTrue(resolver.matches(order,p,cancel,snapshot,List.of(p,closed),List.of(auth,cancel,failed),
                java.util.Set.of(change),java.util.Set.of(aborted)));
        assertFalse(resolver.matches(order,p,cancel,snapshot,List.of(p,closed),List.of(auth,cancel,failed),
                java.util.Set.of(change),java.util.Set.of()));
        ReflectionTestUtils.setField(cancel,"idempotencyKey","different");
        assertFalse(resolver.matches(order,p,cancel,snapshot,List.of(p),List.of(auth,cancel),java.util.Set.of(change)));
        ReflectionTestUtils.setField(cancel,"idempotencyKey","ordinary-cancel");
        ReflectionTestUtils.setField(order,"status",OrderStatus.ORDERED);
        assertFalse(resolver.matches(order,p,cancel,snapshot,List.of(p),List.of(auth,cancel),java.util.Set.of(change)));
    }
    @Test void staleCancellationIdentityRejectsContradictorySupportingCapture() {
        var p=payment();p.setProviderPaymentId("flow",now);p.markAuthorized(now);
        var auth=new PaymentTransaction(p,PaymentTransactionType.AUTHORIZE,100,0,"auth",now);
        ReflectionTestUtils.setField(auth,"id",30L);auth.markSucceeded("flow",now);
        var cancel=new PaymentTransaction(p,PaymentTransactionType.CANCEL,100,0,"cancel",now,
                new PaymentTransactionInitiator(PaymentTransactionInitiatorType.ADMIN,1L,"admin",null));
        ReflectionTestUtils.setField(cancel,"id",31L);cancel.markFailed(null,"failed","failed",now);
        var capture=new PaymentTransaction(p,PaymentTransactionType.CAPTURE,60,0,"capture",now);
        ReflectionTestUtils.setField(capture,"id",32L);
        var snapshot=PaymentOperationSnapshot.from(cancel);var history=List.of(auth,cancel,capture);
        assertTrue(resolver.matchesResultIdentity(order,p,cancel,snapshot,history));
        ReflectionTestUtils.setField(capture,"amount",101);
        assertFalse(resolver.matchesResultIdentity(order,p,cancel,snapshot,history));
        ReflectionTestUtils.setField(capture,"amount",60);
        ReflectionTestUtils.setField(capture,"providerTransactionId","other-flow");
        assertFalse(resolver.matchesResultIdentity(order,p,cancel,snapshot,history));
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
