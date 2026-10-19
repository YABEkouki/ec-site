package com.example.ecsite.service.payment;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDateTime;
import java.util.*;
import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.test.util.ReflectionTestUtils;

class CaptureShipmentPolicyTest {
    private final CaptureShipmentPolicy policy = new CaptureShipmentPolicy(new PaymentConsistencyEvaluator(), new CaptureShipmentSnapshotMatcher());
    private static final LocalDateTime NOW=LocalDateTime.of(2026,10,19,15,0);
    private final Order order=new Order(1L,200,NOW.withHour(10),NOW.withHour(14));
    private final List<Payment> payments=new ArrayList<>();
    private final List<PaymentTransaction> transactions=new ArrayList<>();
    private PaymentOperationSnapshot expected;
    private Payment payment;

    private void setupNew() {
        ReflectionTestUtils.setField(order,"id",42L); order.beginShippingPreparation(NOW.minusHours(1),7L,"preparer");
        payment=new Payment(order,PaymentProvider.PAYJP,PaymentMethod.CARD,200,NOW); ReflectionTestUtils.setField(payment,"id",52L);
        payment.setProviderPaymentId("provider-id",NOW); payment.markAuthorized(NOW); payments.add(payment);
        var authorization=new PaymentTransaction(payment,PaymentTransactionType.AUTHORIZE,200,0,"authorization-key",NOW.minusHours(1));
        ReflectionTestUtils.setField(authorization,"id",62L); authorization.markSucceeded("provider-id",NOW.minusHours(1)); transactions.add(authorization);
    }
    private PaymentTransaction setupShipped(boolean success) {
        setupNew();
        var capture=new PaymentTransaction(payment,PaymentTransactionType.CAPTURE,200,0,"capture-key",NOW,
                new PaymentTransactionInitiator(PaymentTransactionInitiatorType.ADMIN,9L,"shipper","note"));
        ReflectionTestUtils.setField(capture,"id",63L); transactions.add(capture);
        order.confirmCardShipment(NOW,9L,"shipper",52L);
        expected=PaymentOperationSnapshot.from(capture); // PENDING snapshot remains valid after result application.
        if(success) {
            capture.markSucceeded("provider-id",NOW.plusSeconds(1)); payment.markCaptured(NOW.plusSeconds(1));
            order.recordShipmentCaptureSucceeded(52L,NOW.plusSeconds(1));
        }
        return capture;
    }
    private CaptureShipmentPolicy.Decision classify() { return policy.classify(order,payments,transactions,expected); }
    private void recovery() { assertThat(classify().disposition()).isEqualTo(CaptureShipmentPolicy.Disposition.RECOVERY_REQUIRED); }
    @Test void newAuthorizationIsEligibleAndReturnsPersistedPaymentId() {
        setupNew(); var result=classify(); assertThat(result.disposition()).isEqualTo(CaptureShipmentPolicy.Disposition.NEW);
        assertThat(result.paymentId()).isEqualTo(52L); assertThat(result.captureTransactionId()).isNull();
    }
    @Test void decreasedAmountWithDifferentAuthorizationRevisionIsAllowed() {
        setupNew(); order.setTotalAmount(100); order.incrementContentRevision();
        assertThat(classify().disposition()).isEqualTo(CaptureShipmentPolicy.Disposition.NEW);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void decreasedShipmentKeepsAuthorizationRevisionCompatibility(boolean succeeded) {
        var capture=setupShipped(succeeded);
        order.setTotalAmount(100); order.incrementContentRevision();
        ReflectionTestUtils.setField(capture,"amount",100);
        ReflectionTestUtils.setField(capture,"orderContentRevision",1);
        expected=PaymentOperationSnapshot.from(capture);
        assertThat(classify().disposition()).isEqualTo(succeeded
                ? CaptureShipmentPolicy.Disposition.ALREADY_APPLIED : CaptureShipmentPolicy.Disposition.CONTINUE);
    }
    @ParameterizedTest @ValueSource(strings={"increase","revision","authorization","provider","wrong-provider","wrong-method","wrong-status","missing-authorization","duplicate-authorization","active-count"})
    void invalidNewPaymentRequiresRecovery(String change) {
        setupNew();
        switch(change) {
            case "increase" -> order.setTotalAmount(300);
            case "revision" -> order.incrementContentRevision();
            case "authorization" -> ReflectionTestUtils.setField(payment,"amount",300);
            case "provider" -> payment.setProviderPaymentId(" ",NOW);
            case "wrong-provider" -> ReflectionTestUtils.setField(payment,"provider",null);
            case "wrong-method" -> ReflectionTestUtils.setField(payment,"paymentMethod",null);
            case "wrong-status" -> ReflectionTestUtils.setField(payment,"status",PaymentStatus.PENDING);
            case "missing-authorization" -> transactions.clear();
            case "duplicate-authorization" -> { var tx=new PaymentTransaction(payment,PaymentTransactionType.AUTHORIZE,200,0,"other-auth",NOW); ReflectionTestUtils.setField(tx,"id",72L); tx.markSucceeded("provider-id",NOW); transactions.add(tx); }
            case "active-count" -> { Payment second=new Payment(order,PaymentProvider.PAYJP,PaymentMethod.CARD,200,NOW); ReflectionTestUtils.setField(second,"id",53L); payments.add(second); }
        }
        recovery();
    }
    @ParameterizedTest @ValueSource(ints={0,1,2,3,4,5,6})
    void incompletePreparationNeverClassifiesAsNew(int mask) {
        setupNew();
        if((mask&1)==0) ReflectionTestUtils.setField(order,"shippingPreparationStartedAt",null);
        if((mask&2)==0) ReflectionTestUtils.setField(order,"shippingPreparationStartedByAccountId",null);
        if((mask&4)==0) ReflectionTestUtils.setField(order,"shippingPreparationStartedByUsername",null);
        recovery();
    }
    @Test void continueUsesUniqueFixedCaptureWithoutMutation() {
        var capture=setupShipped(false); var before=snapshot();
        var result=classify(); assertThat(result.disposition()).isEqualTo(CaptureShipmentPolicy.Disposition.CONTINUE);
        assertThat(result.captureTransactionId()).isEqualTo(capture.getId()); assertThat(snapshot()).isEqualTo(before);
    }
    @Test void alreadyAppliedAcceptsOriginalPendingSnapshotAndDoesNotUpdateAnything() {
        setupShipped(true); var before=snapshot();
        assertThat(classify().disposition()).isEqualTo(CaptureShipmentPolicy.Disposition.ALREADY_APPLIED);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void failedCaptureRequiresManualRecovery() {
        var capture=setupShipped(false); capture.markFailed("provider-id","failed","safe test",NOW.plusSeconds(1)); recovery();
    }
    @ParameterizedTest @ValueSource(strings={"fixed-null","fixed-missing","foreign-payment","multiple-capture","snapshot-null","amount","revision","provider","paid-pending","captured-pending","missing-shipped-time","missing-shipped-actor","legacy-shipped"})
    void ambiguousOrInconsistentShipmentIsNeverAutomaticallyProcessed(String change) {
        var capture=setupShipped(false);
        switch(change) {
            case "fixed-null" -> ReflectionTestUtils.setField(order,"shipmentCapturePaymentId",null);
            case "fixed-missing" -> ReflectionTestUtils.setField(order,"shipmentCapturePaymentId",999L);
            case "foreign-payment" -> { Order foreign=new Order(); ReflectionTestUtils.setField(foreign,"id",999L); ReflectionTestUtils.setField(payment,"order",foreign); }
            case "multiple-capture" -> { var second=new PaymentTransaction(payment,PaymentTransactionType.CAPTURE,200,0,"other-key",NOW); ReflectionTestUtils.setField(second,"id",64L); transactions.add(second); }
            case "snapshot-null" -> expected=null;
            case "amount" -> order.setTotalAmount(100);
            case "revision" -> order.incrementContentRevision();
            case "provider" -> payment.setProviderPaymentId("changed-provider",NOW);
            case "paid-pending" -> ReflectionTestUtils.setField(order,"paidAt",NOW);
            case "captured-pending" -> payment.markCaptured(NOW);
            case "missing-shipped-time" -> ReflectionTestUtils.setField(order,"shippedAt",null);
            case "missing-shipped-actor" -> ReflectionTestUtils.setField(order,"shippedByAccountId",null);
            case "legacy-shipped" -> { ReflectionTestUtils.setField(order,"shippingPreparationStartedAt",null); ReflectionTestUtils.setField(order,"shippingPreparationStartedByAccountId",null); ReflectionTestUtils.setField(order,"shippingPreparationStartedByUsername",null); }
        }
        recovery();
    }
    @ParameterizedTest @ValueSource(strings={"key","order","payment","transaction","type","amount","revision","provider-id","provider","actor-type","actor-id","actor-name","note"})
    void mismatchedOperationSnapshotRequiresRecovery(String field) {
        setupShipped(false); var s=expected;
        expected=new PaymentOperationSnapshot(field.equals("order")?999L:s.orderId(),field.equals("payment")?999L:s.paymentId(),
                field.equals("transaction")?999L:s.transactionId(),field.equals("type")?PaymentTransactionType.CANCEL:s.type(),
                field.equals("key")?"different-key":s.idempotencyKey(),field.equals("amount")?999:s.amount(),
                field.equals("revision")?99:s.orderContentRevision(),field.equals("provider-id")?"other-provider":s.providerPaymentId(),
                field.equals("provider")?null:s.provider(),s.status(),field.equals("actor-type")?PaymentTransactionInitiatorType.USER:s.initiatorType(),
                field.equals("actor-id")?99L:s.initiatorId(),field.equals("actor-name")?"other-actor":s.initiatorUsername(),field.equals("note")?"other-note":s.internalNote());
        recovery();
    }
    @ParameterizedTest @EnumSource(PaymentTransactionType.class)
    void pendingOnHistoricalPaymentIsAlwaysConflict(PaymentTransactionType type) {
        setupNew(); Payment old=new Payment(order,PaymentProvider.PAYJP,PaymentMethod.CARD,200,NOW);
        ReflectionTestUtils.setField(old,"id",53L); old.markFailed(NOW); payments.add(old);
        var tx=new PaymentTransaction(old,type,200,0,"pending-old",NOW); ReflectionTestUtils.setField(tx,"id",73L); transactions.add(tx);
        recovery();
    }
    @Test void completedFailedAndCancelledPaymentsAreNotActive() {
        setupNew();
        for(PaymentStatus status:List.of(PaymentStatus.FAILED,PaymentStatus.CANCELLED)) {
            Payment historical=new Payment(order,PaymentProvider.PAYJP,PaymentMethod.CARD,200,NOW);
            ReflectionTestUtils.setField(historical,"id",status==PaymentStatus.FAILED?53L:54L);
            ReflectionTestUtils.setField(historical,"status",status); payments.add(historical);
        }
        assertThat(classify().disposition()).isEqualTo(CaptureShipmentPolicy.Disposition.NEW);
    }
    @Test void legacyOrderedPendingAndCompletedCaptureAreNotNew() {
        setupNew(); var capture=new PaymentTransaction(payment,PaymentTransactionType.CAPTURE,200,0,"old-key",NOW);
        ReflectionTestUtils.setField(capture,"id",63L); transactions.add(capture); recovery();
        capture.markFailed("provider-id","failed","failure",NOW); recovery();
    }
    @Test void terminalSuccessMustAgreeWithPaidAtAndProviderResult() {
        var capture=setupShipped(true); ReflectionTestUtils.setField(order,"paidAt",null); recovery();
        ReflectionTestUtils.setField(order,"paidAt",NOW.minusSeconds(1)); recovery();
        ReflectionTestUtils.setField(order,"paidAt",NOW.plusSeconds(1));
        ReflectionTestUtils.setField(capture,"providerTransactionId","other-result"); recovery();
    }
    @Test void nullAndIncompleteInputsFailClosed() {
        assertThat(policy.classify(null,List.of(),List.of(),null).disposition()).isEqualTo(CaptureShipmentPolicy.Disposition.RECOVERY_REQUIRED);
        setupNew(); assertThat(policy.classify(order,null,transactions,null).disposition()).isEqualTo(CaptureShipmentPolicy.Disposition.RECOVERY_REQUIRED);
        assertThat(policy.classify(order,payments,null,null).disposition()).isEqualTo(CaptureShipmentPolicy.Disposition.RECOVERY_REQUIRED);
    }
    private Object snapshot() { return List.of(order.getStatus(),order.getShippedAt(),order.getShippedByAccountId(),order.getShippedByUsername(),
            order.getShipmentCapturePaymentId(),order.getPaidAt()==null?"null":order.getPaidAt(),order.getTotalAmount(),order.getContentRevision(),
            payment.getStatus(),transactions.stream().map(t -> List.of(t.getId(),t.getStatus(),t.getAmount(),t.getOrderContentRevision(),t.getIdempotencyKey())).toList()); }
}
