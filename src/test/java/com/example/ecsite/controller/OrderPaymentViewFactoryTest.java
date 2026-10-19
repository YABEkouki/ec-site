package com.example.ecsite.controller;

import static org.assertj.core.api.Assertions.*;
import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.service.OrderModificationPolicy;
import com.example.ecsite.service.payment.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class OrderPaymentViewFactoryTest {
 static final LocalDateTime DEADLINE=LocalDateTime.of(2026,10,16,14,0);
 Order order; Payment payment; PaymentTransaction auth; List<PaymentTransaction> history;
 OrderPaymentViewFactory factory(LocalDateTime now){return new OrderPaymentViewFactory(new CaptureShipmentPolicy(new PaymentConsistencyEvaluator(),new CaptureShipmentSnapshotMatcher()),new OrderModificationPolicy(),Clock.fixed(now.atZone(ZoneId.of("Asia/Tokyo")).toInstant(),ZoneId.of("Asia/Tokyo")),new PaymentConsistencyEvaluator());}
 @BeforeEach void setup(){order=new Order(1L,200,DEADLINE.withHour(10),DEADLINE);id(order,42L);payment=new Payment(order,PaymentProvider.PAYJP,PaymentMethod.CARD,200,DEADLINE);id(payment,52L);payment.setProviderPaymentId("pf",DEADLINE);payment.markAuthorized(DEADLINE);auth=new PaymentTransaction(payment,PaymentTransactionType.AUTHORIZE,200,0,"auth",DEADLINE);id(auth,62L);auth.markSucceeded("pf",DEADLINE);history=new ArrayList<>(List.of(auth));}
 void id(Object value,long id){ReflectionTestUtils.setField(value,"id",id);}
 void prepare(){order.beginShippingPreparation(DEADLINE,7L,"<preparer>");}
 PaymentTransaction ship(){prepare();order.confirmCardShipment(DEADLINE,9L,"shipper",52L);var t=new PaymentTransaction(payment,PaymentTransactionType.CAPTURE,200,0,"capture",DEADLINE,new PaymentTransactionInitiator(PaymentTransactionInitiatorType.ADMIN,9L,"shipper","memo"));id(t,63L);history.add(t);return t;}
 OrderPaymentViewFactory.View view(){return factory(DEADLINE).create(order,List.of(payment),history);}
 @Test void authorizedDisplayDoesNotRequirePreparationButCaptureButtonDoes(){assertThat(view().message()).isEqualTo("与信済み");assertThat(view().canCapture()).isFalse();prepare();assertThat(view().canCapture()).isTrue();}
 @Test void decreasedAuthorizationIsStillCaptureCandidate(){ReflectionTestUtils.setField(payment,"amount",300);ReflectionTestUtils.setField(auth,"amount",300);ReflectionTestUtils.setField(order,"contentRevision",1);prepare();assertThat(view().canCapture()).isTrue();}
 @Test void pendingShipmentShowsConfirmationAndNoNewOperation(){ship();assertThat(view().message()).isEqualTo("売上確定確認中");assertThat(view().canRequery()).isTrue();assertThat(view().canCapture()).isFalse();assertThat(view().canCancel()).isFalse();}
 @Test void capturedShipmentRequiresConsistentSuccessAndPaidAt(){var cap=ship();cap.markSucceeded("pf",DEADLINE);payment.markCaptured(DEADLINE);order.recordShipmentCaptureSucceeded(52L,DEADLINE);assertThat(view().message()).isEqualTo("売上確定済み");assertThat(view().canRequery()).isFalse();}
 @Test void captureFailureIsNotPaymentFailure(){var cap=ship();cap.markFailed("pf","failed","private",DEADLINE);assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);assertThat(view().message()).isEqualTo("決済要確認");assertThat(view().canRequery()).isFalse();assertThat(view().message()).doesNotContain("private");}
 @ParameterizedTest @ValueSource(strings={"fixed","provider","amount","revision","duplicate","paidAt","partial","foreign","paymentFailed"})
 void inconsistentShipmentFailsClosed(String mode){var cap=ship();switch(mode){case "fixed"->ReflectionTestUtils.setField(order,"shipmentCapturePaymentId",999L);case "provider"->payment.setProviderPaymentId("different",DEADLINE);case "amount"->ReflectionTestUtils.setField(cap,"amount",300);case "revision"->ReflectionTestUtils.setField(cap,"orderContentRevision",1);case "duplicate"->history.add(cap);case "paidAt"->ReflectionTestUtils.setField(order,"paidAt",DEADLINE);case "partial"->ReflectionTestUtils.setField(order,"shippingPreparationStartedByUsername",null);case "foreign"->{var other=new Order(1L,200,DEADLINE.withHour(10),DEADLINE);id(other,99);ReflectionTestUtils.setField(payment,"order",other);}case "paymentFailed"->ReflectionTestUtils.setField(payment,"status",PaymentStatus.FAILED);}
 assertThat(view().message()).isEqualTo("決済情報要確認");assertThat(view().canRequery()).isFalse();assertThat(view().canCapture()).isFalse();assertThat(view().canManualShip()).isFalse();assertThat(view().canCancel()).isFalse();}
 @Test void conflictingPendingHidesCapture(){prepare();var t=new PaymentTransaction(payment,PaymentTransactionType.REFUND,200,0,"refund",DEADLINE);id(t,64);history.add(t);assertThat(view().canCapture()).isFalse();}
 @Test void oldShippedIsNeverCompletedByGuessing(){ReflectionTestUtils.setField(order,"status",OrderStatus.SHIPPED);ReflectionTestUtils.setField(order,"shippedAt",DEADLINE);assertThat(view().message()).contains("既存","要確認");assertThat(view().canCapture()).isFalse();}
 @Test void oldCapturedRecordIsDisplayedWithoutClaimingNewFlowCompletion(){ReflectionTestUtils.setField(order,"status",OrderStatus.SHIPPED);ReflectionTestUtils.setField(order,"shippedAt",DEADLINE);ReflectionTestUtils.setField(order,"paidAt",DEADLINE);payment.markCaptured(DEADLINE);assertThat(view().recordedPaymentStatus()).isEqualTo("CAPTURED");assertThat(view().message()).contains("既存","要確認").doesNotContain("売上確定済み");}
 @ParameterizedTest @ValueSource(strings={"missing-shipped-time","missing-shipper","stale-shipped-time"})
 void invalidManualShipmentRecordIsNotOfferedOrPresentedAsComplete(String mode){prepare();ReflectionTestUtils.setField(order,"status",OrderStatus.PAID);ReflectionTestUtils.setField(order,"paidAt",DEADLINE);if(mode.equals("stale-shipped-time"))ReflectionTestUtils.setField(order,"shippedAt",DEADLINE);else{order.confirmManualShipment(DEADLINE,9L,"shipper");ReflectionTestUtils.setField(order,mode.equals("missing-shipped-time")?"shippedAt":"shippedByAccountId",null);}var result=factory(DEADLINE).create(order,List.of(),List.of());assertThat(result.message()).isEqualTo("決済情報要確認");assertThat(result.canManualShip()).isFalse();}
 @Test void manualPaidAndShippedAreIndependentOfCardAuthorization(){ReflectionTestUtils.setField(order,"status",OrderStatus.PAID);ReflectionTestUtils.setField(order,"paidAt",DEADLINE);var v=factory(DEADLINE).create(order,List.of(),List.of());assertThat(v.message()).isEqualTo("手動決済（支払済み）");assertThat(v.canManualShip()).isFalse();prepare();assertThat(factory(DEADLINE).create(order,List.of(),List.of()).canManualShip()).isTrue();order.confirmManualShipment(DEADLINE,9L,"shipper");assertThat(factory(DEADLINE).create(order,List.of(),List.of()).message()).isEqualTo("手動決済（支払済み）");}
 @ParameterizedTest @ValueSource(longs={-1,0,1}) void cancellationDeadlineBoundary(long nanos){assertThat(factory(DEADLINE.plusNanos(nanos)).create(order,List.of(payment),history).canCancel()).isEqualTo(nanos<0);}
 @Test void preparedCancellationIsHiddenEvenBeforeDeadline(){prepare();assertThat(factory(DEADLINE.minusNanos(1)).create(order,List.of(payment),history).canCancel()).isFalse();}
 @Test void localCancellationWithoutPaymentIsAllowedBeforeDeadline(){assertThat(factory(DEADLINE.minusNanos(1)).create(order,List.of(),List.of()).canCancel()).isTrue();}
 @Test void existingPendingCancelIsNotPresentedAsNewCancellation(){var cancel=new PaymentTransaction(payment,PaymentTransactionType.CANCEL,200,0,"cancel",DEADLINE);id(cancel,65);history.add(cancel);assertThat(factory(DEADLINE.minusNanos(1)).create(order,List.of(payment),history).canCancel()).isFalse();}
}
