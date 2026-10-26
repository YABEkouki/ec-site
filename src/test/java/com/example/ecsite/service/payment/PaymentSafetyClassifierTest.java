package com.example.ecsite.service.payment;

import static org.junit.jupiter.api.Assertions.*;
import static com.example.ecsite.entity.PaymentTransactionPurpose.*;
import static com.example.ecsite.service.payment.PaymentSafetyClassification.*;
import static com.example.ecsite.service.payment.PaymentSafetySnapshot.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import static com.example.ecsite.service.payment.PaymentSafetySnapshot.TimeField.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

/** Plain JUnit only: no Spring context, database, Testcontainers, gateway or clock. */
class PaymentSafetyClassifierTest {
    private static final LocalDateTime T = LocalDateTime.of(2026, 10, 1, 10, 0);
    private final PaymentSafetyClassifier classifier = new PaymentSafetyClassifier();

    @Test
    void selectsUniqueInitialAuthorizationWithoutGrantingFinancialPermission() {
        var result = classify(initial());
        assertEquals(ActivePaymentDisposition.SELECTED, result.disposition());
        assertEquals(10L, result.activePaymentId());
        assertEquals(INITIAL_ORDER_AUTHORIZATION, result.transactionPurposes().getFirst().purpose());
        assertEquals(FinancialResumption.NOT_EVALUATED, result.financialResumption());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "REQUIRES_ACTION"})
    void selectsUniqueInitialPendingAuthorization(String paymentStatus) {
        var f = initial();
        f.payments.set(0, payment(10L, paymentStatus, 5500, "pfw_10"));
        f.transactions.set(0, transaction(100L, 10L, "AUTHORIZE", "PENDING", 5500, 0, null));
        assertEquals(ActivePaymentDisposition.SELECTED, classify(f).disposition());
    }

    @Test
    void pendingAuthorizationRequiresExactAmountAndRevision() {
        var f = initial();
        f.payments.set(0, payment(10L, "PENDING", 5500, "pfw_10"));
        f.transactions.set(0, transaction(100L, 10L, "AUTHORIZE", "PENDING", 5500, 0, null));
        f.order = order("ORDERED", 4500, 1);
        review(f);
    }

    @Test
    void permitsRevisionDifferenceAfterDecreaseButNotAuthorizationAmountMismatch() {
        var f = initial();
        f.order = order("ORDERED", 4500, 1);
        assertEquals(ActivePaymentDisposition.SELECTED, classify(f).disposition());
        f.transactions.set(0, transaction(100L, 10L, "AUTHORIZE", "SUCCEEDED", 5000, 0, null));
        review(f);
    }

    @Test
    void rejectsEqualAmountWithDifferentRevisionAndIncreasedAmount() {
        var f = initial();
        f.order = order("ORDERED", 5500, 1);
        review(f);
        f.order = order("ORDERED", 6000, 1);
        review(f);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "SUCCEEDED"})
    void shippedUsesFixedPaymentAndVerifiedImmutableRequest(String captureStatus) {
        var f = shipped(captureStatus);
        var result = classify(f);
        assertEquals(ActivePaymentDisposition.SELECTED, result.disposition());
        assertEquals(10L, result.activePaymentId());
        assertEquals(SHIPMENT_CAPTURE, result.transactionPurposes().get(1).purpose());
        assertEquals(FinancialResumption.NOT_EVALUATED, result.financialResumption());
    }

    @Test
    void shippedDecreaseKeepsOriginalAuthorizationAndCapturesReducedAmount() {
        var f = shipped("PENDING");
        f.order = shippedOrder("PENDING", 4500, 1, 10L);
        f.transactions.set(1, transaction(101L, 10L, "CAPTURE", "PENDING", 4500, 1, null));
        f.evidence = new ArrayList<>(List.of(evidence(4500, 1, CaptureVerification.VERIFIED)));
        assertEquals(ActivePaymentDisposition.SELECTED, classify(f).disposition());
    }

    @Test
    void cancellationWithResolvedAuthorizationHasNormalNullActivePayment() {
        var f = cancelled();
        var result = classify(f);
        assertEquals(ActivePaymentDisposition.NORMAL_NULL, result.disposition());
        assertNull(result.activePaymentId());
        assertEquals(ORDER_CANCELLATION, result.transactionPurposes().get(1).purpose());
    }

    @Test
    void cancelledWithUnresolvedAuthorizationOrPendingCancellationNeedsReview() {
        var f = cancelled();
        f.payments.set(0, payment(10L, "AUTHORIZED", 5500, "pfw_10"));
        review(f);
        f.payments.set(0, payment(10L, "CANCELLED", 5500, "pfw_10"));
        f.transactions.set(1, transaction(101L, 10L, "CANCEL", "PENDING", 5500, 0, null));
        review(f);
    }

    @Test
    void multipleLivePaymentsAreNeverResolvedByLatestId() {
        var f = initial();
        f.payments.add(payment(20L, "AUTHORIZED", 5500, "pfw_20"));
        f.transactions.add(transaction(200L, 20L, "AUTHORIZE", "SUCCEEDED", 5500, 0, null));
        review(f);
        Collections.reverse(f.payments);
        review(f);
    }

    @Test
    void failedPriorPaymentDoesNotHideUniqueValidPayment() {
        var f = initial();
        f.payments.add(payment(20L, "FAILED", 5500, "pfw_20"));
        f.transactions.add(transaction(200L, 20L, "AUTHORIZE", "FAILED", 5500, 0, null));
        assertEquals(10L, classify(f).activePaymentId());
    }

    @Test
    void inactivePaymentWithSuccessfulAuthorizationCannotBeIgnored() {
        var f = initial();
        f.payments.add(payment(20L, "FAILED", 5500, "pfw_20"));
        f.transactions.add(transaction(200L, 20L, "AUTHORIZE", "SUCCEEDED", 5500, 0, null));
        review(f);
    }

    @Test
    void duplicateSuccessfulAuthorizationsNeedReview() {
        var f = initial();
        f.transactions.add(transaction(101L, 10L, "AUTHORIZE", "SUCCEEDED", 5500, 0, null));
        review(f);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void missingProviderIdentityNeedsReview(String id) {
        var f = initial();
        f.payments.set(0, payment(10L, "AUTHORIZED", 5500, id));
        review(f);
    }

    @Test
    void nullProviderIdentityNeedsReview() {
        var f = initial();
        f.payments.set(0, payment(10L, "AUTHORIZED", 5500, null));
        review(f);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REFUND", "FUTURE_TYPE"})
    void unknownTransactionIsNotReinterpretedAsInitialAuthorization(String type) {
        var f = initial();
        f.transactions.set(0, transaction(100L, 10L, type, "SUCCEEDED", 5500, 0, null));
        var result = classify(f);
        assertEquals(ActivePaymentDisposition.REVIEW, result.disposition());
        assertEquals(LEGACY_UNKNOWN, result.transactionPurposes().getFirst().purpose());
    }

    @ParameterizedTest
    @ValueSource(strings = {"LEGACY_UNKNOWN", "ORDER_CHANGE_AUTHORIZATION", "FUTURE_PURPOSE", "SHIPMENT_CAPTURE"})
    void declaredUnsupportedPurposeIsNotOverwritten(String purpose) {
        var f = initial();
        f.transactions.set(0, transaction(100L, 10L, "AUTHORIZE", "SUCCEEDED", 5500, 0, purpose));
        review(f);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "MISMATCH", "UNVERIFIED"})
    void captureWithoutVerifiedV54IsNeverRecoverySafe(String verification) {
        var f = shipped("PENDING");
        f.evidence.set(0, evidence(5500, 0, CaptureVerification.valueOf(verification)));
        var result = classify(f);
        assertEquals(ActivePaymentDisposition.REVIEW, result.disposition());
        assertEquals(SHIPMENT_CAPTURE, result.transactionPurposes().get(1).purpose());
        assertEquals(FinancialResumption.NOT_EVALUATED, result.financialResumption());
    }

    @Test
    void missingOrWrongV54IdentityNeedsReview() {
        var f = shipped("PENDING");
        f.evidence.clear();
        review(f);
        f.evidence.add(evidence(4500, 0, CaptureVerification.VERIFIED));
        review(f);
    }

    @Test
    void wrongFixedShipmentPaymentAndFailedCaptureNeedReview() {
        var f = shipped("PENDING");
        f.order = shippedOrder("PENDING", 5500, 0, 20L);
        review(f);
        f = shipped("FAILED");
        review(f);
    }

    @Test
    void shippedPendingMustNotHavePaidTimestamp() {
        var f = shipped("PENDING");
        f.order = shippedOrder("SUCCEEDED", 5500, 0, 10L);
        review(f);
    }

    @Test
    void manualPaidAndManualShippedAllowNormalNullWithHistory() {
        var f = manual("PAID");
        assertEquals(ActivePaymentDisposition.NORMAL_NULL, classify(f).disposition());
        assertEquals(ActivePaymentDisposition.NORMAL_NULL, classify(manual("SHIPPED")).disposition());
    }

    @Test
    void orderWithoutPaymentHasNormalNullButIncompleteInputNeedsReview() {
        var f = initial();
        f.payments.clear();
        f.transactions.clear();
        assertEquals(ActivePaymentDisposition.NORMAL_NULL, classify(f).disposition());
        f.complete = false;
        review(f);
        assertEquals(ActivePaymentDisposition.REVIEW, classifier.classify(null).disposition());
    }

    @Test
    void orderHistoryAndForeignOrDuplicateRowsMustNotBeTrusted() {
        var f = initial();
        f.history.clear();
        review(f);
        f = initial();
        f.payments.add(f.payments.getFirst());
        review(f);
        f = initial();
        f.transactions.add(transaction(200L, 99L, "AUTHORIZE", "PENDING", 5500, 0, null));
        review(f);
        f = initial();
        f.history.add(history(2L, "ORDERED", "CANCELLED", T.plusMinutes(2)));
        review(f);
    }

    @Test
    void captureOnUnshippedOrderOrPendingOtherTransactionNeedsReview() {
        var f = initial();
        f.transactions.add(transaction(101L, 10L, "CAPTURE", "PENDING", 5500, 0, null));
        review(f);
        f = shipped("PENDING");
        f.transactions.add(transaction(102L, 10L, "CANCEL", "PENDING", 5500, 0, null));
        review(f);
    }

    @Test
    void duplicateProviderFlowAcrossPaymentsNeedsReview() {
        var f = initial();
        f.payments.add(payment(20L, "FAILED", 5500, "pfw_10"));
        f.transactions.add(transaction(200L, 20L, "AUTHORIZE", "FAILED", 5500, 0, null));
        review(f);
    }

    @Test
    void repeatedClassificationIsIdenticalAndSnapshotIsDefensivelyCopied() {
        var f = initial();
        var snapshot = f.snapshot();
        var first = classifier.classify(snapshot);
        f.payments.clear();
        assertEquals(first, classifier.classify(snapshot));
        assertEquals(10L, first.activePaymentId());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.payments().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.transactionPurposes().clear());
    }

    @Test
    void pendingCancellationRetainsBusinessIntentWithoutGrantingRecovery() {
        var f = initial();
        f.transactions.add(transaction(101L, 10L, "CANCEL", "PENDING", 5500, 0, null));
        var result = classify(f);
        assertEquals(ORDER_CANCELLATION, result.transactionPurposes().get(1).purpose());
        assertEquals(ActivePaymentDisposition.REVIEW, result.disposition());
        assertEquals(FinancialResumption.NOT_EVALUATED, result.financialResumption());
    }

    @Test
    void nullProviderIsReviewedWithoutThrowing() {
        var f = initial();
        f.payments.set(0, new PaymentFacts(10L, 1L, null, "CARD", "AUTHORIZED", 5500, "pfw_10"));
        review(f);
    }

    @Test
    void cancellationActorMustAgreeWithLifecycleHistory() {
        var f = cancelled();
        f.history.set(1, new StatusHistory(2L, 1L, "ORDERED", "CANCELLED", T.plusMinutes(2), "USER", 9L, "customer"));
        review(f);
    }

    @ParameterizedTest
    @CsvSource({"5500,5500,5500,0,0", "4500,5500,5500,1,0", "5500,5500,5500,1,0",
            "6000,5500,5500,1,0", "4500,5500,5000,1,0", "5500,5500,5000,0,0"})
    void amountPolicyAgreesWithExistingEvaluator(int orderAmount, int paymentAmount, int authAmount, int revision, int authRevision) {
        var orderEntity = new com.example.ecsite.entity.Order(1L, orderAmount, T, T.plusHours(4));
        org.springframework.test.util.ReflectionTestUtils.setField(orderEntity, "contentRevision", revision);
        var paymentEntity = new com.example.ecsite.entity.Payment(orderEntity,
                com.example.ecsite.entity.PaymentProvider.PAYJP, com.example.ecsite.entity.PaymentMethod.CARD, paymentAmount, T);
        var authEntity = new com.example.ecsite.entity.PaymentTransaction(paymentEntity,
                com.example.ecsite.entity.PaymentTransactionType.AUTHORIZE, authAmount, authRevision, "auth-key", T);
        boolean expected = new PaymentConsistencyEvaluator().evaluate(orderEntity, paymentEntity, authEntity).canCapture();
        var f = initial();
        f.order = order("ORDERED", orderAmount, revision);
        f.payments.set(0, payment(10L, "AUTHORIZED", paymentAmount, "pfw_10"));
        f.transactions.set(0, transaction(100L, 10L, "AUTHORIZE", "SUCCEEDED", authAmount, authRevision, null));
        assertEquals(expected, classify(f).disposition() == ActivePaymentDisposition.SELECTED);
    }

    @Test
    void cancelledOrderWithPaidTimestampIsContradictoryEvenWithoutPayments() {
        var f = cancelled();
        f.order = new OrderFacts(1L, "CANCELLED", 5500, 0, T.plusMinutes(1), T.plusMinutes(2), null, preparation(false), null, null, null);
        review(f);
        f.payments.clear();
        f.transactions.clear();
        review(f);
    }

    @Test
    void successfulCancellationMustMatchCurrentContentRevision() {
        var f = cancelled();
        f.transactions.set(1, transaction(101L, 10L, "CANCEL", "SUCCEEDED", 5500, 999, null));
        review(f);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCEL", "CAPTURE"})
    void dependentFinancialOperationCannotPrecedeSuccessfulAuthorization(String type) {
        var f = type.equals("CANCEL") ? cancelled() : shipped("PENDING");
        var auth = f.transactions.getFirst();
        f.transactions.set(0, withTimes(auth, T, T.plusMinutes(4)));
        review(f);
    }

    @Test
    void cancellationCompletionCannotFollowOrderCancellation() {
        var f = cancelled();
        f.transactions.set(1, withTimes(f.transactions.get(1), T.plusMinutes(1), T.plusMinutes(3)));
        review(f);
    }

    @Test
    void authorizationMustAlreadyBeCompleteWhenShipmentWasCommitted() {
        var f = shipped("PENDING");
        f.transactions.set(0, withTimes(f.transactions.getFirst(), T, T.plusMinutes(3)));
        f.transactions.set(1, withTimes(f.transactions.get(1), T.plusMinutes(4), null));
        review(f);
    }

    @Test
    void shippedWithoutShipmentTimestampIsReviewedWithoutThrowing() {
        var f = shipped("PENDING");
        f.order = new OrderFacts(1L, "SHIPPED", 5500, 0, null, null, null, preparation(true), 10L, 5L, "admin");
        review(f);
    }

    @Test
    void cancellationAfterDecreaseKeepsAuthorizationAmountAndCurrentCancelRevision() {
        var f = cancelled();
        f.order = order("CANCELLED", 4500, 1);
        f.transactions.set(1, transaction(101L, 10L, "CANCEL", "SUCCEEDED", 5500, 1, null));
        assertEquals(ActivePaymentDisposition.NORMAL_NULL, classify(f).disposition());
    }

    @Test
    void transactionStartHistoryBeforeShipmentDoesNotRejectNormalShipment() {
        var f = shipped("PENDING");
        f.history.set(1, history(2L, "ORDERED", "SHIPPED", T.plusMinutes(1)));
        f.origins.put(ref(HISTORY_CHANGED, 2L), TRANSACTION_START);
        assertEquals(ActivePaymentDisposition.SELECTED, classify(f).disposition());
    }

    @Test
    void transactionStartHistoryBeforeCancellationDoesNotRejectNormalCancellation() {
        var f = cancelled();
        f.history.set(1, history(2L, "ORDERED", "CANCELLED", T.plusMinutes(1)));
        f.origins.put(ref(HISTORY_CHANGED, 2L), TRANSACTION_START);
        assertEquals(ActivePaymentDisposition.NORMAL_NULL, classify(f).disposition());
    }

    @Test
    void missingLifecycleHistoryMustNotConfirmInitialPurpose() {
        var f = initial();
        f.history.clear();
        var result = classify(f);
        assertEquals(1, result.transactionPurposes().size());
        assertEquals(LEGACY_UNKNOWN, result.transactionPurposes().getFirst().purpose());
        assertEquals(ActivePaymentDisposition.REVIEW, result.disposition());
    }

    @Test
    void structuralFailureStillClassifiesEverySuppliedTransactionAsUnknown() {
        var f = initial();
        f.payments.set(0, new PaymentFacts(10L, 99L, "PAYJP", "CARD", "AUTHORIZED", 5500, "pfw_10"));
        var result = classify(f);
        assertEquals(ActivePaymentDisposition.REVIEW, result.disposition());
        assertEquals(1, result.transactionPurposes().size());
        assertEquals(LEGACY_UNKNOWN, result.transactionPurposes().getFirst().purpose());
    }

    @Test
    void zeroAmountOrderRemainsReviewUntilBusinessPolicyIsDefined() {
        var f = initial();
        f.order = order("ORDERED", 0, 0);
        f.payments.clear(); f.transactions.clear();
        review(f);
    }

    @Test
    void sameWallClockHistoryInversionStillRequiresReview() {
        var f = shipped("PENDING");
        f.history.set(1, history(2L, "ORDERED", "SHIPPED", T.plusMinutes(1)));
        var result = classify(f);
        assertEquals(ActivePaymentDisposition.REVIEW, result.disposition());
        assertTrue(result.evidence().timeComparisons().stream().anyMatch(c ->
                c.earlier().field() == ORDER_SHIPPED && c.later().field() == HISTORY_CHANGED
                && c.outcome() == TimeComparisonOutcome.INVERTED));
    }

    @Test
    void transactionStartComparisonKeepsBothValuesAndReason() {
        var f = shipped("PENDING");
        f.history.set(1, history(2L, "ORDERED", "SHIPPED", T.plusMinutes(1)));
        f.origins.put(ref(HISTORY_CHANGED, 2L), TRANSACTION_START);
        var result = classify(f);
        var comparison = result.evidence().timeComparisons().stream()
                .filter(c -> c.earlier().field() == ORDER_SHIPPED && c.later().field() == HISTORY_CHANGED).findFirst().orElseThrow();
        assertEquals(T.plusMinutes(2), comparison.earlierValue());
        assertEquals(T.plusMinutes(1), comparison.laterValue());
        assertEquals(TimeComparisonOutcome.NOT_COMPARABLE, comparison.outcome());
        assertEquals(TimeComparisonReason.TRANSACTION_START_IS_NOT_EVENT_TIME, comparison.reason());
        assertEquals(TRANSACTION_START, comparison.laterOrigin());
        assertEquals(ActivePaymentDisposition.SELECTED, result.disposition());
        assertEquals(FinancialResumption.NOT_EVALUATED, result.financialResumption());
    }

    @Test
    void unknownTimestampOriginsAreExplicitRatherThanAssumedComparable() {
        var f = shipped("PENDING");
        var snapshot = new PaymentSafetySnapshot(f.order, f.payments, f.transactions, f.history, f.evidence, true);
        var result = classifier.classify(snapshot);
        assertEquals(ActivePaymentDisposition.SELECTED, result.disposition());
        assertFalse(result.evidence().timeComparisons().isEmpty());
        assertTrue(result.evidence().timeComparisons().stream().allMatch(c ->
                c.outcome() == TimeComparisonOutcome.NOT_COMPARABLE && c.reason() == TimeComparisonReason.UNKNOWN_CLOCK));
    }

    @ParameterizedTest
    @ValueSource(strings = {"domain", "zone", "kind", "invalid-zone"})
    void differentOrInvalidClockMetadataIsNotSilentlyCompared(String difference) {
        var f = initial();
        f.transactions.set(0, withTimes(f.transactions.getFirst(), T.plusMinutes(2), T.plusMinutes(1)));
        f.origins.put(ref(TRANSACTION_COMPLETED, 100L), switch (difference) {
            case "domain" -> new TimeOrigin(ClockKind.APPLICATION_WALL_CLOCK, "other-jvm", "Asia/Tokyo");
            case "zone" -> new TimeOrigin(ClockKind.APPLICATION_WALL_CLOCK, "unit-clock", "UTC");
            case "kind" -> new TimeOrigin(ClockKind.DATABASE_WALL_CLOCK, "unit-clock", "Asia/Tokyo");
            default -> new TimeOrigin(ClockKind.APPLICATION_WALL_CLOCK, "unit-clock", "invalid-zone");
        });
        var result = classify(f);
        assertEquals(ActivePaymentDisposition.SELECTED, result.disposition());
        assertTrue(result.evidence().timeComparisons().stream().allMatch(c -> c.outcome() == TimeComparisonOutcome.NOT_COMPARABLE));
    }

    @Test
    void sameWallClockTransactionCompletionBeforeCreationIsContradictory() {
        var f = initial();
        f.transactions.set(0, withTimes(f.transactions.getFirst(), T.plusMinutes(2), T.plusMinutes(1)));
        var result = classify(f);
        assertEquals(ActivePaymentDisposition.REVIEW, result.disposition());
        assertTrue(result.reviewIssues().stream().anyMatch(i -> i.reason() == Reason.TEMPORAL_CONTRADICTION));
    }

    @Test
    void lifecycleOrderDoesNotDependOnTransactionStartTimestampOrder() {
        var f = manual("SHIPPED");
        f.order = new OrderFacts(1L, "SHIPPED", 5500, 0, T.plusMinutes(5), null, T.plusMinutes(6), preparation(true), null, 5L, "admin");
        f.history.set(1, history(2L, "ORDERED", "PAID", T.plusMinutes(5)));
        f.history.set(2, history(3L, "PAID", "SHIPPED", T.plusMinutes(4)));
        for (var h : f.history) f.origins.put(ref(HISTORY_CHANGED, h.id()), TRANSACTION_START);
        assertEquals(ActivePaymentDisposition.NORMAL_NULL, classify(f).disposition());
    }

    @Test
    void lifecycleWallClockInversionIsNotHiddenByLogicalChain() {
        var f = manual("SHIPPED");
        f.history.set(1, history(2L, "ORDERED", "PAID", T.plusMinutes(5)));
        f.history.set(2, history(3L, "PAID", "SHIPPED", T.plusMinutes(4)));
        review(f);
    }

    @Test
    void rawShippingPreparationIsRetainedAndItsInversionIsChecked() {
        var f = shipped("PENDING");
        var result = classify(f);
        assertEquals(new ShippingPreparationFacts(T.plusMinutes(1), 4L, "preparer"), result.evidence().shippingPreparation());
        f.order = new OrderFacts(1L, "SHIPPED", 5500, 0, null, null, T.plusMinutes(2),
                new ShippingPreparationFacts(T.plusMinutes(3), 4L, "preparer"), 10L, 5L, "admin");
        review(f);
    }

    @Test
    void incompletePreparationFactsAreNotReplacedWithSyntheticEvidence() {
        var f = shipped("PENDING");
        f.order = new OrderFacts(1L, "SHIPPED", 5500, 0, null, null, T.plusMinutes(2),
                new ShippingPreparationFacts(T.plusMinutes(1), null, "preparer"), 10L, 5L, "admin");
        review(f);
    }

    @Test
    void purposeConflictCannotExposeUsablePurposeAndKeepsOriginalDeclaration() {
        var f = initial();
        f.transactions.set(0, transaction(100L, 10L, "AUTHORIZE", "SUCCEEDED", 5500, 0, "SHIPMENT_CAPTURE"));
        var purpose = classify(f).transactionPurposes().getFirst();
        assertEquals(PurposeDisposition.CONFLICT, purpose.disposition());
        assertEquals(LEGACY_UNKNOWN, purpose.purpose());
        assertEquals("SHIPMENT_CAPTURE", purpose.declaredPurpose());
    }

    @ParameterizedTest
    @ValueSource(strings = {"LEGACY_UNKNOWN", "FUTURE_PURPOSE", "ORDER_CHANGE_AUTHORIZATION"})
    void unknownAndDisabledPurposeNeverExposesAnEnabledPurpose(String declaration) {
        var f = initial();
        f.transactions.set(0, transaction(100L, 10L, "AUTHORIZE", "SUCCEEDED", 5500, 0, declaration));
        var result = classify(f);
        var purpose = result.transactionPurposes().getFirst();
        assertEquals(PurposeDisposition.UNKNOWN, purpose.disposition());
        assertEquals(LEGACY_UNKNOWN, purpose.purpose());
        assertEquals(declaration, purpose.declaredPurpose());
        assertEquals(FinancialResumption.NOT_EVALUATED, result.financialResumption());
    }

    @Test
    void incompleteRowSetsCannotConfirmEvenAnExplicitInitialPurpose() {
        var f = initial();
        f.complete = false;
        f.transactions.set(0, transaction(100L, 10L, "AUTHORIZE", "SUCCEEDED", 5500, 0, "INITIAL_ORDER_AUTHORIZATION"));
        var purpose = classify(f).transactionPurposes().getFirst();
        assertEquals(PurposeDisposition.UNKNOWN, purpose.disposition());
        assertEquals(LEGACY_UNKNOWN, purpose.purpose());
    }

    @Test
    void duplicateAndNullTransactionIdsStillHaveOneUnknownResultPerInputRow() {
        var f = initial();
        f.transactions.add(f.transactions.getFirst());
        f.transactions.add(transaction(null, 10L, "AUTHORIZE", "SUCCEEDED", 5500, 0, null));
        var result = classify(f);
        assertEquals(3, result.transactionPurposes().size());
        assertEquals(List.of(0, 1, 2), result.transactionPurposes().stream().map(TransactionPurpose::inputIndex).toList());
        assertTrue(result.transactionPurposes().stream().allMatch(p ->
                p.disposition() == PurposeDisposition.UNKNOWN && p.purpose() == LEGACY_UNKNOWN));
    }

    @Test
    void missingOrderDoesNotDropTransactionResults() {
        var f = initial();
        f.order = null;
        var result = classify(f);
        assertEquals(1, result.transactionPurposes().size());
        assertEquals(PurposeDisposition.UNKNOWN, result.transactionPurposes().getFirst().disposition());
    }

    @Test
    void normalPurposeIsConfirmedIndependentlyOfV54RecoveryEvidence() {
        var f = shipped("PENDING");
        f.evidence.set(0, evidence(5500, 0, CaptureVerification.MISMATCH));
        var result = classify(f);
        assertEquals(ActivePaymentDisposition.REVIEW, result.disposition());
        assertTrue(result.transactionPurposes().stream().allMatch(p -> p.disposition() == PurposeDisposition.CONFIRMED));
        assertEquals(FinancialResumption.NOT_EVALUATED, result.financialResumption());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-01T01:45", "2026-03-08T02:45"})
    void dstOverlapAndGapReadingsAreNotAssumedToBeUniqueInstants(String reading) {
        var f = initial();
        var at = LocalDateTime.parse(reading);
        f.transactions.set(0, withTimes(f.transactions.getFirst(), at, at.minusMinutes(30)));
        var clock = new TimeOrigin(ClockKind.APPLICATION_WALL_CLOCK, "dst-clock", "America/New_York");
        f.origins.put(ref(TRANSACTION_CREATED, 100L), clock);
        f.origins.put(ref(TRANSACTION_COMPLETED, 100L), clock);
        var result = classify(f);
        assertEquals(ActivePaymentDisposition.SELECTED, result.disposition());
        var comparison = result.evidence().timeComparisons().getFirst();
        assertEquals(TimeComparisonOutcome.NOT_COMPARABLE, comparison.outcome());
        assertEquals("LOCAL_TIME_NOT_UNIQUE", comparison.reason().name());
    }

    @Test
    void provenanceMapAndReturnedComparisonEvidenceCannotBeMutated() {
        var f = initial();
        f.transactions.set(0, withTimes(f.transactions.getFirst(), T.plusMinutes(2), T.plusMinutes(1)));
        var origins = new HashMap<>(f.snapshot().timeOrigins());
        var snapshot = new PaymentSafetySnapshot(f.order, f.payments, f.transactions, f.history, f.evidence, true, origins);
        var result = classifier.classify(snapshot);
        origins.clear();
        assertEquals(ActivePaymentDisposition.REVIEW, result.disposition());
        assertEquals(result, classifier.classify(snapshot));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.timeOrigins().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.evidence().timeComparisons().clear());
    }

    private static ShippingPreparationFacts preparation(boolean prepared) {
        return prepared ? new ShippingPreparationFacts(T.plusMinutes(1), 4L, "preparer")
                : new ShippingPreparationFacts(null, null, null);
    }
    private static TimeReference ref(TimeField field, Long id) { return new TimeReference(field, id); }
    private static final TimeOrigin WALL = new TimeOrigin(ClockKind.APPLICATION_WALL_CLOCK, "unit-clock", "Asia/Tokyo");
    private static final TimeOrigin TRANSACTION_START = new TimeOrigin(ClockKind.DATABASE_TRANSACTION_START, "unit-db", "Asia/Tokyo");

    private static TransactionFacts withTimes(TransactionFacts t, LocalDateTime created, LocalDateTime completed) {
        return new TransactionFacts(t.id(), t.paymentId(), t.type(), t.status(), t.amount(), t.contentRevision(),
                t.idempotencyKey(), t.providerTransactionId(), created, completed, t.initiatorType(),
                t.initiatorId(), t.initiatorUsername(), t.declaredPurpose());
    }

    private PaymentSafetyClassification classify(Fixture f) { return classifier.classify(f.snapshot()); }
    private void review(Fixture f) {
        var result = classify(f);
        assertEquals(ActivePaymentDisposition.REVIEW, result.disposition(), result.toString());
        assertNull(result.activePaymentId());
        assertFalse(result.reviewIssues().isEmpty());
    }

    private static Fixture initial() {
        var f = new Fixture();
        f.order = order("ORDERED", 5500, 0);
        f.payments.add(payment(10L, "AUTHORIZED", 5500, "pfw_10"));
        f.transactions.add(transaction(100L, 10L, "AUTHORIZE", "SUCCEEDED", 5500, 0, null));
        f.history.add(history(1L, null, "ORDERED", T));
        return f;
    }
    private static Fixture cancelled() {
        var f = initial();
        f.order = order("CANCELLED", 5500, 0);
        f.payments.set(0, payment(10L, "CANCELLED", 5500, "pfw_10"));
        f.transactions.add(transaction(101L, 10L, "CANCEL", "SUCCEEDED", 5500, 0, null));
        f.history.add(history(2L, "ORDERED", "CANCELLED", T.plusMinutes(2)));
        return f;
    }
    private static Fixture shipped(String status) {
        var f = initial();
        f.order = shippedOrder(status, 5500, 0, 10L);
        f.payments.set(0, payment(10L, status.equals("SUCCEEDED") ? "CAPTURED" : "AUTHORIZED", 5500, "pfw_10"));
        f.transactions.add(transaction(101L, 10L, "CAPTURE", status, 5500, 0, null));
        f.history.add(history(2L, "ORDERED", "SHIPPED", T.plusMinutes(2)));
        f.evidence.add(evidence(5500, 0, CaptureVerification.VERIFIED));
        return f;
    }
    private static Fixture manual(String status) {
        var f = initial();
        f.payments.clear(); f.transactions.clear();
        f.history.add(history(2L, "ORDERED", "PAID", T.plusMinutes(1)));
        if (status.equals("SHIPPED")) f.history.add(history(3L, "PAID", "SHIPPED", T.plusMinutes(2)));
        f.order = new OrderFacts(1L, status, 5500, 0, T.plusMinutes(1), null,
                status.equals("SHIPPED") ? T.plusMinutes(2) : null, preparation(true), null,
                status.equals("SHIPPED") ? 5L : null, status.equals("SHIPPED") ? "admin" : null);
        return f;
    }
    private static OrderFacts order(String status, int amount, int revision) {
        return new OrderFacts(1L, status, amount, revision, null,
                status.equals("CANCELLED") ? T.plusMinutes(2) : null, null, preparation(false), null, null, null);
    }
    private static OrderFacts shippedOrder(String captureStatus, int amount, int revision, Long fixedId) {
        return new OrderFacts(1L, "SHIPPED", amount, revision,
                captureStatus.equals("SUCCEEDED") ? T.plusMinutes(3) : null, null, T.plusMinutes(2), preparation(true), fixedId, 5L, "admin");
    }
    private static PaymentFacts payment(Long id, String status, int amount, String flowId) {
        return new PaymentFacts(id, 1L, "PAYJP", "CARD", status, amount, flowId);
    }
    private static TransactionFacts transaction(Long id, Long paymentId, String type, String status,
            int amount, int revision, String purpose) {
        boolean capture = type.equals("CAPTURE");
        boolean cancel = type.equals("CANCEL");
        return new TransactionFacts(id, paymentId, type, status, amount, revision, "key-" + id,
                status.equals("SUCCEEDED") ? "pfw_" + paymentId : null,
                capture ? T.plusMinutes(2) : (cancel ? T.plusMinutes(1) : T),
                status.equals("PENDING") ? null : (capture ? T.plusMinutes(3) : (cancel ? T.plusMinutes(2) : T.plusMinutes(1))),
                "ADMIN", 5L, "admin", purpose);
    }
    private static StatusHistory history(Long id, String from, String to, LocalDateTime at) {
        return new StatusHistory(id, 1L, from, to, at, "ADMIN", 5L, "admin");
    }
    private static CaptureEvidence evidence(int amount, int revision, CaptureVerification verification) {
        return new CaptureEvidence(101L, 10L, "pfw_10", amount, revision, "key-101", verification);
    }
    private static class Fixture {
        OrderFacts order;
        List<PaymentFacts> payments = new ArrayList<>();
        List<TransactionFacts> transactions = new ArrayList<>();
        List<StatusHistory> history = new ArrayList<>();
        List<CaptureEvidence> evidence = new ArrayList<>();
        boolean complete = true;
        Map<TimeReference, TimeOrigin> origins = new HashMap<>();
        PaymentSafetySnapshot snapshot() {
            // Fixture timestamps come from one explicit synthetic clock; production has no such default.
            var readings = new HashMap<TimeReference, TimeOrigin>();
            if (order != null) for (var field : List.of(ORDER_PAID, ORDER_CANCELLED, ORDER_SHIPPED, PREPARATION_STARTED))
                readings.put(ref(field, order.id()), WALL);
            for (var t : transactions) {
                readings.put(ref(TRANSACTION_CREATED, t.id()), WALL);
                readings.put(ref(TRANSACTION_COMPLETED, t.id()), WALL);
            }
            for (var h : history) readings.put(ref(HISTORY_CHANGED, h.id()), WALL);
            readings.putAll(origins);
            return new PaymentSafetySnapshot(order, payments, transactions, history, evidence, complete, readings);
        }
    }
}
