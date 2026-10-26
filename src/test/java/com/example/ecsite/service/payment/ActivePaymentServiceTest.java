package com.example.ecsite.service.payment;

import com.example.ecsite.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class ActivePaymentServiceTest {
    @Test void completedChangedBindingRequiresWholeHistoryAndNoPendingOperations() {
        var now=LocalDateTime.of(2026,1,1,10,0);
        var order=new Order(1L,100,now,now.withHour(14));ReflectionTestUtils.setField(order,"id",1L);
        var payment=new Payment(order,PaymentProvider.PAYJP,PaymentMethod.CARD,100,now);ReflectionTestUtils.setField(payment,"id",10L);
        payment.setProviderPaymentId("changed",now);payment.markAuthorized(now);order.bindActivePayment(payment);
        var change=java.util.UUID.randomUUID();
        var auth=PaymentTransaction.forOrderChange(payment,PaymentTransactionType.AUTHORIZE,100,0,"auth",now,
                PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION,change);
        ReflectionTestUtils.setField(auth,"id",20L);auth.markSucceeded("changed",now);
        assertTrue(ActivePaymentService.establishedBoundAuthorization(order,java.util.List.of(payment),java.util.List.of(auth),java.util.Set.of(change)));
        assertFalse(ActivePaymentService.establishedBoundAuthorization(order,java.util.List.of(payment),java.util.List.of(auth),java.util.Set.of()));
        var pending=new PaymentTransaction(payment,PaymentTransactionType.CANCEL,100,0,"pending",now);
        assertFalse(ActivePaymentService.establishedBoundAuthorization(order,java.util.List.of(payment),java.util.List.of(auth,pending),java.util.Set.of(change)));
    }
    @Test void boundInitialAuthorizationCanBeReusedAfterFullySettledAbort() {
        var now=LocalDateTime.of(2026,1,1,10,0);
        var order=new Order(1L,100,now,now.withHour(14));ReflectionTestUtils.setField(order,"id",1L);
        var payment=new Payment(order,PaymentProvider.PAYJP,PaymentMethod.CARD,100,now);ReflectionTestUtils.setField(payment,"id",10L);
        payment.setProviderPaymentId("original",now);payment.markAuthorized(now);order.bindActivePayment(payment);
        var auth=new PaymentTransaction(payment,PaymentTransactionType.AUTHORIZE,100,0,"original-auth",now);
        ReflectionTestUtils.setField(auth,"id",20L);auth.markSucceeded("original",now);
        var closed=new Payment(order,PaymentProvider.PAYJP,PaymentMethod.CARD,100,now);ReflectionTestUtils.setField(closed,"id",11L);closed.markFailed(now);
        var change=java.util.UUID.randomUUID();
        var failed=PaymentTransaction.forOrderChange(closed,PaymentTransactionType.AUTHORIZE,100,0,"failed-auth",now,
                PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION,change);
        ReflectionTestUtils.setField(failed,"id",21L);failed.markFailed(null,"canceled","canceled",now);
        assertTrue(ActivePaymentService.establishedBoundAuthorization(order,java.util.List.of(payment,closed),java.util.List.of(auth,failed),java.util.Set.of(),java.util.Set.of(change)));
        assertFalse(ActivePaymentService.establishedBoundAuthorization(order,java.util.List.of(payment,closed),java.util.List.of(auth,failed),java.util.Set.of(),java.util.Set.of()));
    }
    @Test void bindingIsIdempotentButCannotReplaceOrCrossOrders() {
        var now=LocalDateTime.of(2026,1,1,10,0);
        var order=new Order(1L,100,now,now.withHour(14));ReflectionTestUtils.setField(order,"id",1L);
        var first=new Payment(order,PaymentProvider.PAYJP,PaymentMethod.CARD,100,now);ReflectionTestUtils.setField(first,"id",10L);
        var second=new Payment(order,PaymentProvider.PAYJP,PaymentMethod.CARD,100,now);ReflectionTestUtils.setField(second,"id",11L);
        order.bindActivePayment(first);order.bindActivePayment(first);
        assertEquals(10L,order.getActivePaymentId());
        assertThrows(IllegalStateException.class,()->order.bindActivePayment(second));
        assertThrows(IllegalStateException.class,()->order.clearActivePaymentAfterTermination(11L));
        order.clearActivePaymentAfterTermination(10L);assertNull(order.getActivePaymentId());
        var foreign=new Order(2L,100,now,now.withHour(14));ReflectionTestUtils.setField(foreign,"id",2L);
        assertThrows(IllegalStateException.class,()->foreign.bindActivePayment(first));
    }
}
