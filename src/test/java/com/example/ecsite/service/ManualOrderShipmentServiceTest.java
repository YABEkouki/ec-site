package com.example.ecsite.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.LocalDateTime;
import java.util.*;
import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.repository.OrderRepository;
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.payment.OrderPaymentGuard;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

class ManualOrderShipmentServiceTest {
    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderWriteContext context = mock(OrderWriteContext.class);
    private final OrderShipmentGuard guard = spy(new OrderShipmentGuard());
    private final OrderPaymentGuard payments = mock(OrderPaymentGuard.class);
    private final OrderStatusHistoryService histories = mock(OrderStatusHistoryService.class);
    private final ManualOrderShipmentService service = new ManualOrderShipmentService(orders, context, guard, payments, histories);
    private static final LocalDateTime NOW = LocalDateTime.of(2026,10,16,15,0,0,123456000);
    private Order order;
    @BeforeEach void setup() {
        var roles = List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
        var actor = new AdminUserDetails(9L,"shipper","password",true,roles);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,"password",roles));
        order = new Order(1L,100,NOW.withHour(10),NOW.withHour(14)); order.markAsPaid();
        order.beginShippingPreparation(NOW.minusHours(1),7L,"preparer");
        when(orders.findByIdForUpdate(42L)).thenReturn(Optional.of(order));
        when(orders.findCurrentTimeInTokyo()).thenReturn(NOW);
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void validatesInLockOrderThenUsesDbTimeAndAuthenticatedActor() {
        var paidAt = order.getPaidAt();
        service.ship(42L,9L,"shipper","note");
        var sequence = inOrder(context,orders,guard,payments,histories);
        sequence.verify(context).requireCleanWritableReadCommitted();
        sequence.verify(orders).findByIdForUpdate(42L);
        sequence.verify(context).refreshWritableOrder(order);
        sequence.verify(guard).requirePrepared(order);
        sequence.verify(context).refreshPayments(order);
        sequence.verify(payments).validateManualShipment(order);
        sequence.verify(orders).findCurrentTimeInTokyo();
        sequence.verify(histories).record(order,OrderStatus.PAID,OrderStatus.SHIPPED,OrderStatusHistoryActorType.ADMIN,9L,"shipper","note");
        assertThat(order.getShippedAt()).isEqualTo(NOW);
        assertThat(order.getShippedByAccountId()).isEqualTo(9L);
        assertThat(order.getShippedByUsername()).isEqualTo("shipper");
        assertThat(order.getShippingPreparationStartedByAccountId()).isEqualTo(7L);
        assertThat(order.getPaidAt()).isEqualTo(paidAt);
        assertThat(order.getShipmentCapturePaymentId()).isNull();
    }
    @ParameterizedTest @ValueSource(ints={0,1,2,3,4,5,6})
    void incompletePreparationIsRejectedBeforePaymentOrTime(int mask) {
        ReflectionTestUtils.setField(order,"shippingPreparationStartedAt",(mask&1)!=0?NOW:null);
        ReflectionTestUtils.setField(order,"shippingPreparationStartedByAccountId",(mask&2)!=0?7L:null);
        ReflectionTestUtils.setField(order,"shippingPreparationStartedByUsername",(mask&4)!=0?"preparer":null);
        assertThatThrownBy(() -> service.ship(42L,9L,"shipper",null)).isInstanceOf(InvalidOrderStatusException.class);
        assertUnshipped(); verifyNoInteractions(payments,histories); verify(orders,never()).findCurrentTimeInTokyo();
    }
    @ParameterizedTest @EnumSource(value=OrderStatus.class,names={"ORDERED","SHIPPED","CANCELLED"})
    void wrongStatusIsRejected(OrderStatus status) {
        ReflectionTestUtils.setField(order,"status",status);
        assertThatThrownBy(() -> service.ship(42L,9L,"shipper",null)).isInstanceOf(InvalidOrderStatusException.class);
        assertThat(order.getShippedAt()).isNull(); verifyNoInteractions(payments,histories);
    }
    @Test void invalidPaymentDoesNotMutateOrReadTime() {
        doThrow(new InvalidOrderStatusException("pending")).when(payments).validateManualShipment(order);
        assertThatThrownBy(() -> service.ship(42L,9L,"shipper",null)).isInstanceOf(InvalidOrderStatusException.class);
        assertUnshipped(); verifyNoInteractions(histories); verify(orders,never()).findCurrentTimeInTokyo();
    }
    @Test void missingAuthenticationFailsBeforeContextAndQueries() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> service.ship(42L,9L,"shipper",null)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(context,histories); verify(orders,never()).findByIdForUpdate(any());
    }
    @Test void forgedActorFailsBeforeContextAndQueries() {
        assertThatThrownBy(() -> service.ship(42L,99L,"forged",null)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(context,histories); verify(orders,never()).findByIdForUpdate(any());
    }
    @Test void dirtyContextFailsBeforeLock() {
        doThrow(new IllegalStateException("dirty")).when(context).requireCleanWritableReadCommitted();
        assertThatIllegalStateException().isThrownBy(() -> service.ship(42L,9L,"shipper",null));
        verify(orders,never()).findByIdForUpdate(any()); assertUnshipped();
    }
    @Test void readOnlyOrderFailsBeforePaymentAndTime() {
        doThrow(new IllegalStateException("readonly")).when(context).refreshWritableOrder(order);
        assertThatIllegalStateException().isThrownBy(() -> service.ship(42L,9L,"shipper",null));
        verifyNoInteractions(payments,histories); verify(orders,never()).findCurrentTimeInTokyo(); assertUnshipped();
    }
    @Test void dbTimeFailureDoesNotMutate() {
        when(orders.findCurrentTimeInTokyo()).thenThrow(new IllegalStateException("db time"));
        assertThatIllegalStateException().isThrownBy(() -> service.ship(42L,9L,"shipper",null));
        verifyNoInteractions(histories); assertUnshipped();
    }
    private void assertUnshipped() {
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID); assertThat(order.getShippedAt()).isNull();
        assertThat(order.getShippedByAccountId()).isNull(); assertThat(order.getShippedByUsername()).isNull();
    }
}
