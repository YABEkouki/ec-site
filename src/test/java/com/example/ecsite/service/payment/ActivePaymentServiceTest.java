package com.example.ecsite.service.payment;

import com.example.ecsite.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class ActivePaymentServiceTest {
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
