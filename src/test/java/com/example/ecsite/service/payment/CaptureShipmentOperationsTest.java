package com.example.ecsite.service.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.LocalDateTime;
import java.util.*;
import jakarta.persistence.EntityManager;
import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.repository.*;
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.*;
import com.example.ecsite.payment.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

class CaptureShipmentOperationsTest {
    OrderRepository orders=mock(OrderRepository.class);
    PaymentRepository payments=mock(PaymentRepository.class);
    PaymentTransactionRepository transactions=mock(PaymentTransactionRepository.class);
    EntityManager em=mock(EntityManager.class);
    OrderWriteContext context=mock(OrderWriteContext.class);
    OrderStatusHistoryService histories=mock(OrderStatusHistoryService.class);
    CaptureShipmentPolicy policy=new CaptureShipmentPolicy(new PaymentConsistencyEvaluator(),new CaptureShipmentSnapshotMatcher());
    CaptureShipmentOperations service=new CaptureShipmentOperations(orders,payments,transactions,em,context,new OrderShipmentGuard(),policy,histories);
    LocalDateTime now=LocalDateTime.of(2026,10,19,15,0);
    Order order=new Order(1L,200,now.minusDays(1),now.minusHours(1));
    Payment payment;
    List<PaymentTransaction> history=new ArrayList<>();
    @BeforeEach void setup() {
        actor(9L,"shipper"); ReflectionTestUtils.setField(order,"id",42L);
        order.beginShippingPreparation(now.minusHours(1),7L,"preparer");
        payment=new Payment(order,PaymentProvider.PAYJP,PaymentMethod.CARD,200,now);
        ReflectionTestUtils.setField(payment,"id",52L); payment.setProviderPaymentId("provider",now); payment.markAuthorized(now);
        var auth=new PaymentTransaction(payment,PaymentTransactionType.AUTHORIZE,200,0,"auth",now);
        ReflectionTestUtils.setField(auth,"id",62L); auth.markSucceeded("provider",now); history.add(auth);
        when(orders.findByIdForUpdate(42L)).thenReturn(Optional.of(order));
        when(orders.findCurrentTimeInTokyo()).thenReturn(now);
        when(payments.findByOrderIdForUpdate(42L)).thenReturn(List.of(payment));
        when(transactions.findByPaymentIdOrderByCreatedAtAscIdAsc(52L)).thenReturn(history);
        when(transactions.save(any())).thenAnswer(call -> { PaymentTransaction t=call.getArgument(0);ReflectionTestUtils.setField(t,"id",63L);history.add(t);return t; });
    }
    @Test void requeryOnlyReadsExistingPendingOperationAndPreservesOriginalActor() {
        var first=start(); clearInvocations(transactions,histories,orders,context,em,payments); actor(10L,"second-admin");
        var target=service.prepareRequery(42L);
        assertThat(target.disposition()).isEqualTo(PaymentStartDisposition.REUSED);
        assertThat(target.snapshot()).isEqualTo(first.snapshot());
        var sequence=inOrder(context,orders,em);
        sequence.verify(context).requireCleanWritableReadCommitted(); sequence.verify(orders).findByIdForUpdate(42L);
        sequence.verify(context).refreshWritableOrder(order); sequence.verify(em).refresh(payment);
        verify(transactions,never()).save(any()); verifyNoInteractions(histories); verify(orders,never()).findCurrentTimeInTokyo();
    }
    @ParameterizedTest @ValueSource(strings={"ordered","failed","fixed","multiple","provider","snapshot","amount","revision","partial","foreign"})
    void unsafeRequeryNeverStartsShipmentOrCreatesAnOperation(String mode) {
        var first=start();
        switch(mode){
            case "ordered" -> ReflectionTestUtils.setField(order,"status",OrderStatus.ORDERED);
            case "failed" -> history.getLast().markFailed("provider","failed","private",now);
            case "fixed" -> ReflectionTestUtils.setField(order,"shipmentCapturePaymentId",99L);
            case "multiple" -> {var extra=new PaymentTransaction(payment,PaymentTransactionType.CAPTURE,200,0,"other",now);ReflectionTestUtils.setField(extra,"id",70L);history.add(extra);}
            case "provider" -> ReflectionTestUtils.setField(payment,"providerPaymentId",null);
            case "snapshot" -> ReflectionTestUtils.setField(history.getLast(),"idempotencyKey","changed");
            case "amount" -> ReflectionTestUtils.setField(history.getLast(),"amount",300);
            case "revision" -> order.incrementContentRevision();
            case "partial" -> ReflectionTestUtils.setField(order,"shippingPreparationStartedByUsername",null);
            case "foreign" -> {var other=new Order(1L,200,now.minusDays(1),now);ReflectionTestUtils.setField(other,"id",88L);ReflectionTestUtils.setField(payment,"order",other);}
        }
        clearInvocations(transactions,histories);
        assertThatThrownBy(() -> { if (mode.equals("snapshot")) service.confirmRequery(first.snapshot()); else service.prepareRequery(42L); })
                .isInstanceOfAny(CaptureRequeryRejectedException.class,com.example.ecsite.exception.InvalidOrderStatusException.class);
        verify(transactions,never()).save(any());verifyNoInteractions(histories);
    }
    @Test void requeryRejectsAnExpectedSnapshotForAnotherTransaction() {
        var first=start();var old=first.snapshot();
        var different=new PaymentOperationSnapshot(old.orderId(),old.paymentId(),999L,old.type(),old.idempotencyKey(),old.amount(),old.orderContentRevision(),old.providerPaymentId(),old.provider(),old.status(),old.initiatorType(),old.initiatorId(),old.initiatorUsername(),old.internalNote());
        clearInvocations(transactions,histories);
        assertThatThrownBy(()->service.confirmRequery(different)).isInstanceOf(CaptureRequeryRejectedException.class);
        verify(transactions,never()).save(any());verifyNoInteractions(histories);
    }
    @Test void requeryAuthenticationFailsBeforeAnyLock() {
        SecurityContextHolder.clearContext(); clearInvocations(orders,context,payments,transactions);
        assertThatThrownBy(() -> service.prepareRequery(42L)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(orders,context,payments,transactions);
    }
    @Test void requeryAlreadyAppliedHasNoAdditionalMutations() {
        var first=start(); service.apply(first.snapshot(),new CaptureResult(CaptureResultStatus.CAPTURED,"provider",null,null));
        var paidAt=order.getPaidAt(); clearInvocations(histories,transactions,orders);
        assertThat(service.prepareRequery(42L).disposition()).isEqualTo(PaymentStartDisposition.ALREADY_APPLIED);
        assertThat(service.confirmRequery(first.snapshot()).disposition()).isEqualTo(PaymentStartDisposition.ALREADY_APPLIED);
        assertThat(order.getPaidAt()).isEqualTo(paidAt);verify(transactions,never()).save(any());verifyNoInteractions(histories);verify(orders,never()).findCurrentTimeInTokyo();
    }
    static void actor(Long id,String name) {
        var principal=new AdminUserDetails(id,name,"password",true,List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,"",principal.getAuthorities()));
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    PaymentCaptureStart start() { return service.start(42L,9L,"shipper","memo"); }
    @Test void newOperationCommitsShipmentContractWithoutPaidAt() {
        var result=start(); assertThat(result.disposition()).isEqualTo(PaymentStartDisposition.NEW);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPED);assertThat(order.getPaidAt()).isNull();
        assertThat(order.getShipmentCapturePaymentId()).isEqualTo(52L);
        assertThat(result.snapshot().initiatorUsername()).isEqualTo("shipper");
        var sequence=inOrder(context,orders,em,transactions,histories);
        sequence.verify(context).requireCleanWritableReadCommitted();sequence.verify(orders).findByIdForUpdate(42L);
        sequence.verify(context).refreshWritableOrder(order);sequence.verify(em).refresh(payment);
        sequence.verify(orders).findCurrentTimeInTokyo();sequence.verify(transactions).save(any());
        sequence.verify(histories).record(order,OrderStatus.ORDERED,OrderStatus.SHIPPED,OrderStatusHistoryActorType.ADMIN,9L,"shipper","memo");
    }
    @Test void decreasedAmountWithOldAuthorizationRevisionIsAllowed() {
        order.setTotalAmount(100);order.incrementContentRevision();var result=start();
        assertThat(result.amount()).isEqualTo(100);assertThat(result.snapshot().orderContentRevision()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"unprepared","partial","increase","revision","unauthorized","pending","paid","cancelled"})
    void ineligibleOrderNeverCreatesCapture(String change) {
        switch(change) {
            case "unprepared" -> {ReflectionTestUtils.setField(order,"shippingPreparationStartedAt",null);ReflectionTestUtils.setField(order,"shippingPreparationStartedByAccountId",null);ReflectionTestUtils.setField(order,"shippingPreparationStartedByUsername",null);}
            case "partial" -> ReflectionTestUtils.setField(order,"shippingPreparationStartedByUsername",null);
            case "increase" -> order.setTotalAmount(300);
            case "revision" -> order.incrementContentRevision();
            case "unauthorized" -> payment.markCancelled(now);
            case "pending" -> { var tx=new PaymentTransaction(payment,PaymentTransactionType.REFUND,200,0,"refund",now);ReflectionTestUtils.setField(tx,"id",70L);history.add(tx); }
            case "paid" -> ReflectionTestUtils.setField(order,"status",OrderStatus.PAID);
            case "cancelled" -> ReflectionTestUtils.setField(order,"status",OrderStatus.CANCELLED);
        }
        assertThatThrownBy(this::start).isInstanceOfAny(IllegalStateException.class,com.example.ecsite.exception.InvalidOrderStatusException.class);
        verify(transactions,never()).save(any());verifyNoInteractions(histories);
    }
    @Test void continuationKeepsSavedActorAndDoesNotCreateNewTransaction() {
        var first=start(); actor(10L,"other");var next=service.start(42L,10L,"other","different");
        assertThat(next.disposition()).isEqualTo(PaymentStartDisposition.REUSED);
        assertThat(next.snapshot()).isEqualTo(first.snapshot());verify(transactions,times(1)).save(any());
        verify(histories,times(1)).record(any(),any(),any(),any(),any(),any(),any());
    }
    @Test void successRecordsPaidAtOnlyOnceAndStalePendingDoesNotRevertIt() {
        var first=start();var captured=new CaptureResult(CaptureResultStatus.CAPTURED,"provider",null,null);
        assertThat(service.apply(first.snapshot(),captured)).isEqualTo(PaymentResultApplication.APPLIED);
        var paid=order.getPaidAt();when(orders.findCurrentTimeInTokyo()).thenReturn(now.plusSeconds(1));
        assertThat(service.apply(first.snapshot(),captured)).isEqualTo(PaymentResultApplication.ALREADY_APPLIED);
        assertThat(service.apply(first.snapshot(),new CaptureResult(CaptureResultStatus.PENDING,"provider",null,null))).isEqualTo(PaymentResultApplication.IGNORED_STALE);
        assertThat(order.getPaidAt()).isEqualTo(paid);
        assertThat(start().disposition()).isEqualTo(PaymentStartDisposition.ALREADY_APPLIED);
        verify(histories,times(1)).record(any(),any(),any(),any(),any(),any(),any());
    }
    @ParameterizedTest @ValueSource(strings={"FAILED","PENDING"})
    void nonSuccessKeepsShipmentAndDoesNotPay(String status) {
        var first=start();service.apply(first.snapshot(),new CaptureResult(CaptureResultStatus.valueOf(status),"provider","code","message"));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPED);assertThat(order.getPaidAt()).isNull();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(history.getLast().getStatus()).isEqualTo(status.equals("FAILED")?PaymentTransactionStatus.FAILED:PaymentTransactionStatus.PENDING);
        if(status.equals("FAILED"))assertThatThrownBy(this::start).isInstanceOf(IllegalStateException.class);
    }
    @Test void readOnlyTargetFailurePrecedesRefreshAndResultMutation() {
        var first=start();doThrow(new IllegalStateException("read only")).when(context).refreshWritableCaptureTargets(payment,history.getLast());
        clearInvocations(em);assertThatThrownBy(() -> service.apply(first.snapshot(),new CaptureResult(CaptureResultStatus.CAPTURED,"provider",null,null))).isInstanceOf(IllegalStateException.class);
        verify(em,never()).refresh(payment);assertThat(order.getPaidAt()).isNull();
    }
    @Test void authenticationIsCheckedBeforeFirstQuery() {
        SecurityContextHolder.clearContext();assertThatThrownBy(this::start).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(context,orders);
    }
}
