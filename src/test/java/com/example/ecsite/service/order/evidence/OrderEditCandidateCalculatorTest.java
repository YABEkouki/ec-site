package com.example.ecsite.service.order.evidence;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static com.example.ecsite.service.order.evidence.OrderPaymentEvidenceValidatorTest.*;
import static com.example.ecsite.service.order.evidence.OrderEditCandidate.*;

class OrderEditCandidateCalculatorTest {
    final OrderEditCandidateCalculator calculator = new OrderEditCandidateCalculator();
    final LocalDateTime now = LocalDateTime.of(2026,10,30,13,59);
    Context context() { return new Context(facts(initial(),List.of()),2,"ORDERED",now.minusDays(1),now.plusMinutes(1),null,null,null,now); }
    Offer offer(long id,int price) { return new Offer(id,"新商品",4,"分類",price,5,"STANDARD","標準",1000,true); }
    Request request(Line... lines) { return new Request(1,0,List.of(lines)); }
    Line keep(int qty) { return new Line(3L,7,qty); }
    Result calculate(Line... lines) { return calculator.calculate(context(),request(lines),List.of(offer(7,9999),offer(8,1000))); }

    @Test void noOpPreservesHistoricalShippingAndPrice() {
        var r=calculate(keep(1));
        assertThat(r.route()).isEqualTo(OrderChangeRoutePolicy.Route.NO_CHANGE);
        assertThat(r.candidate().previewSnapshot()).isEqualTo(context().facts().current());
        assertThat(r.candidate().stockDeltas()).isEmpty();
    }
    @Test void additionUsesCurrentOfferAndDoesNotRepriceExistingLine() {
        var r=calculate(keep(1),new Line(null,8,2));
        assertThat(r.route()).isEqualTo(OrderChangeRoutePolicy.Route.REAUTHORIZE);
        assertThat(r.candidate().previewSnapshot().totalAmount()).isEqualTo(8000);
        assertThat(r.candidate().previewSnapshot().items()).extracting(OrderContentSnapshot.Item::unitPrice).contains(6000,1000);
        assertThat(r.candidate().stockDeltas()).containsExactly(new StockDelta(8,0,2));
        assertThat(r.candidate().newLineReferences()).hasSize(1);
    }
    @Test void replaceAndDeleteProduceAggregatedSignedDeltas() {
        var r=calculate(new Line(3L,8,2));
        assertThat(r.route()).isEqualTo(OrderChangeRoutePolicy.Route.RETAIN_AUTH);
        assertThat(r.candidate().previewSnapshot().totalAmount()).isEqualTo(2550);
        assertThat(r.candidate().stockDeltas()).containsExactly(new StockDelta(7,1,0),new StockDelta(8,0,2));
        assertThat(calculate(keep(0),new Line(null,8,2)).candidate().stockDeltas()).isEqualTo(r.candidate().stockDeltas());
    }
    @Test void duplicateAdditionsMergeAndInputOrderIsIrrelevant() {
        var a=calculate(keep(1),new Line(null,8,1),new Line(null,8,2));
        var b=calculate(new Line(null,8,3),keep(1));
        assertThat(a).isEqualTo(b);
    }
    @ParameterizedTest @ValueSource(strings={"duplicate","foreign","empty","negative","missing","overflow","revision","owner"})
    void rejectsInvalidInputs(String kind) {
        var req=switch(kind) {
            case "duplicate" -> request(keep(1),keep(2));
            case "foreign" -> request(new Line(99L,7,1));
            case "empty" -> request(keep(0));
            case "negative" -> request(keep(-1));
            case "missing" -> request(new Line(null,999,1));
            case "overflow" -> request(keep(Integer.MAX_VALUE));
            case "revision" -> new Request(1,1,List.of(keep(1)));
            default -> new Request(9,0,List.of(keep(1)));
        };
        assertThat(calculator.calculate(context(),req,List.of(offer(7,1))).route()).isEqualTo(OrderChangeRoutePolicy.Route.BLOCKED);
    }
    @Test void quantityIncreaseRetainsSavedPrice() {
        var r=calculate(keep(2));
        assertThat(r.candidate().previewSnapshot().totalAmount()).isEqualTo(12000);
        assertThat(r.candidate().stockDeltas()).containsExactly(new StockDelta(7,1,2));
    }
    @Test void changedNewOfferRequiresConfirmationAgain() {
        var req=request(keep(1),new Line(null,8,1));
        var original=calculator.calculate(context(),req,List.of(offer(7,1),offer(8,1000)));
        assertThat(calculator.revalidate(original.candidate(),context(),req,List.of(offer(7,1),offer(8,1100))).reason()).isEqualTo(Reason.CONFIRMATION_CHANGED);
        assertThat(calculator.revalidate(original.candidate(),context(),req,List.of(offer(7,5),offer(8,1000)))).isEqualTo(original);
    }
    @Test void invalidEvidenceBlocksEvenNoOp() {
        var c=context();var f=c.facts();
        var bad=copy(f,f.current(),f.activePaymentId(),f.payments(),f.authorizations(),f.epoch(),f.histories(),null,true,false);
        var blocked=new Context(bad,2,c.status(),c.orderedAt(),c.deadline(),null,null,null,now);
        assertThat(calculator.calculate(blocked,request(keep(1)),List.of()).route()).isEqualTo(OrderChangeRoutePolicy.Route.BLOCKED);
    }

    @ParameterizedTest @ValueSource(strings={"deadline","status","owner","preparationTime","preparationAccount","preparationName","missingTime"})
    void rejectsAdmissionFailures(String kind) {
        var c=context();
        var bad=new Context(c.facts(),kind.equals("owner")?3:2,kind.equals("status")?"PAID":"ORDERED",c.orderedAt(),c.deadline(),
            kind.equals("preparationTime")?now:null,kind.equals("preparationAccount")?9L:null,
            kind.equals("preparationName")?"admin":null,kind.equals("missingTime")?null:kind.equals("deadline")?c.deadline():now);
        assertThat(calculator.calculate(bad,request(keep(1)),List.of()).reason()).isEqualTo(Reason.ADMISSION_DENIED);
    }
    @ParameterizedTest @ValueSource(ints={4999,5000})
    void shippingBoundaryAndInclusiveTax(int price) {
        var r=calculator.calculate(context(),request(new Line(null,8,1)),List.of(offer(8,price)));
        var s=r.candidate().previewSnapshot();
        assertThat(s.chargeTotal()).isEqualTo(price==4999?550:0);
        assertThat(s.taxAmount()).isEqualTo(price==4999?504:454);
    }
    @Test void mixedTaxRatesAreGroupedWithShippingAtTenPercent() {
        var eight=new Offer(8,"食品",4,"分類",1080,9,"REDUCED","軽減",800,true);
        var r=calculator.calculate(context(),request(new Line(null,8,1),new Line(null,9,1)),List.of(eight,offer(9,1100)));
        assertThat(r.candidate().previewSnapshot().taxAmount()).isEqualTo(80+150);
        assertThat(r.candidate().previewSnapshot().totalAmount()).isEqualTo(2730);
    }
    @ParameterizedTest @ValueSource(strings={"subtotal","quantitySum","negativePrice","rate","inactive","duplicateCatalog"})
    void invalidCatalogOrArithmeticCannotProduceCandidate(String kind) {
        var o=kind.equals("negativePrice")?offer(8,-1):kind.equals("rate")?new Offer(8,"商品",4,"分類",1000,5,"STD","税",-10000,true):
            kind.equals("inactive")?new Offer(8,"商品",4,"分類",1000,5,"STD","税",1000,false):offer(8,Integer.MAX_VALUE);
        var lines=kind.equals("quantitySum")?request(new Line(null,8,Integer.MAX_VALUE),new Line(null,8,1)):
            kind.equals("subtotal")?request(keep(1),new Line(null,8,1)):request(new Line(null,8,1));
        assertThat(calculator.calculate(context(),lines,kind.equals("duplicateCatalog")?List.of(o,o):List.of(o)).route()).isEqualTo(OrderChangeRoutePolicy.Route.BLOCKED);
    }
    @Test void equalAmountReplacementRetainsAuthorizationAndInactiveIncreaseIsBlocked() {
        assertThat(calculator.calculate(context(),request(new Line(null,8,1)),List.of(offer(8,6000))).route()).isEqualTo(OrderChangeRoutePolicy.Route.RETAIN_AUTH);
        assertThat(calculator.calculate(context(),request(keep(2)),List.of()).reason()).isEqualTo(Reason.UNAVAILABLE_PRODUCT);
    }
    @Test void multipleSavedOfferingsOfSameProductRemainDistinctAndAggregateStock() {
        var base=context().facts().current();
        var a=new OrderContentSnapshot.Item(3,7,"旧商品",4,"旧分類",2000,2,4000,5,"STANDARD","標準",1000);
        var b=new OrderContentSnapshot.Item(4,7,"別条件",4,"分類",2000,1,2000,5,"STANDARD","標準",1000);
        var start=new OrderContentSnapshot(1,1,2,0,List.of(a,b),base.charges(),6000,0,545,6000);
        var e=signed(new OrderPaymentEvidence.Epoch(1,EPOCH,1,10,20,6000,0,OrderPaymentEvidence.Origin.INITIAL_AUTH,start,null,null,ZERO));
        var c=new Context(facts(e,List.of()),2,"ORDERED",now.minusDays(1),now.plusMinutes(1),null,null,null,now);
        var r=calculator.calculate(c,request(new Line(3L,7,1),new Line(4L,7,2),new Line(null,8,1)),List.of(offer(7,9999),offer(8,1000)));
        assertThat(r.candidate().previewSnapshot().items()).extracting(OrderContentSnapshot.Item::productName).contains("旧商品","別条件");
        assertThat(r.candidate().stockDeltas()).containsExactly(new StockDelta(8,0,1));
        var reduced=calculator.calculate(c,request(new Line(3L,7,1),new Line(4L,7,1)),List.of());
        assertThat(reduced.candidate().stockDeltas()).containsExactly(new StockDelta(7,3,2));
        assertThat(reduced.candidate().previewSnapshot().chargeTotal()).isEqualTo(550);
    }
    @Test void sourcesRejectIncompleteReadAndDetachCurrentProduct() {
        assertThatThrownBy(()->OrderEditCandidateSources.context(null,2,now)).isInstanceOf(IllegalArgumentException.class);
        var p=new com.example.ecsite.entity.Product();
        assertThatThrownBy(()->OrderEditCandidateSources.offer(p)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void sourcesReadSavedOrderAndFreezeProductFacts() {
        var row=tools.jackson.databind.json.JsonMapper.builder().build().readTree("""
            {"id":1,"user_id":2,"content_revision":0,"status":"ORDERED",
             "ordered_at":"2026-10-29T13:59:00","change_deadline_at":"2026-10-30T14:00:00",
             "shipping_preparation_started_at":null,"shipping_preparation_started_by_account_id":null,
             "shipping_preparation_started_by_username":null}
            """);
        var read=new JdbcOrderPaymentEvidenceReader.Read(JdbcOrderPaymentEvidenceReader.State.READ,context().facts(),null,null,null,row,null);
        assertThat(OrderEditCandidateSources.context(read,2,now)).isEqualTo(context());
        var p=new com.example.ecsite.entity.Product();var category=new com.example.ecsite.entity.Category("分類");var tax=new com.example.ecsite.entity.TaxCategory();
        org.springframework.test.util.ReflectionTestUtils.setField(category,"id",4L);
        org.springframework.test.util.ReflectionTestUtils.setField(tax,"id",5L);
        org.springframework.test.util.ReflectionTestUtils.setField(tax,"code","STANDARD");
        org.springframework.test.util.ReflectionTestUtils.setField(tax,"name","標準");
        org.springframework.test.util.ReflectionTestUtils.setField(tax,"taxRate",new java.math.BigDecimal("10.00"));
        org.springframework.test.util.ReflectionTestUtils.setField(p,"id",8L);
        org.springframework.test.util.ReflectionTestUtils.setField(p,"category",category);
        org.springframework.test.util.ReflectionTestUtils.setField(p,"taxCategory",tax);
        org.springframework.test.util.ReflectionTestUtils.setField(p,"name","新商品");
        org.springframework.test.util.ReflectionTestUtils.setField(p,"price",1000);
        var frozen=OrderEditCandidateSources.offer(p);
        org.springframework.test.util.ReflectionTestUtils.setField(p,"price",9999);
        assertThat(frozen).isEqualTo(offer(8,1000));
    }
    @Test void provisionalKeysNeverCollideWithSavedKeysAndCannotExhaustLongRange() {
        var r=calculate(new Line(null,8,1),keep(1));
        assertThat(r.candidate().newLineReferences()).containsExactly(new NewLineReference(1,8));
        assertThat(r.candidate().previewSnapshot().contentRevision()).isZero();
        assertThat(context().facts().current().items()).hasSize(1);
    }
    @Test void authorizationBoundaryAndPreviewRevalidationAreExact() {
        assertThat(calculator.calculate(context(),request(new Line(null,8,1)),List.of(offer(8,6000))).route()).isEqualTo(OrderChangeRoutePolicy.Route.RETAIN_AUTH);
        assertThat(calculator.calculate(context(),request(new Line(null,8,1)),List.of(offer(8,6001))).route()).isEqualTo(OrderChangeRoutePolicy.Route.REAUTHORIZE);
        var original=calculate(keep(1),new Line(null,8,1));var c=context();
        var expired=new Context(c.facts(),2,c.status(),c.orderedAt(),c.deadline(),null,null,null,c.deadline());
        assertThat(calculator.revalidate(original.candidate(),expired,request(keep(1),new Line(null,8,1)),List.of(offer(8,1000))).reason()).isEqualTo(Reason.ADMISSION_DENIED);
    }
}
