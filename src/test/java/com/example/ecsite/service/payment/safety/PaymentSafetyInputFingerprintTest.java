package com.example.ecsite.service.payment.safety;

import static org.assertj.core.api.Assertions.*;
import com.example.ecsite.service.payment.safety.PaymentSafetyReadFacts.*;
import java.lang.reflect.RecordComponent;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PaymentSafetyInputFingerprintTest {
    static <T> T row(Class<T> type, Object... fields) {
        try {
            var values = new HashMap<String,Object>();
            for (int i=0;i<fields.length;i+=2) values.put((String)fields[i],fields[i+1]);
            var components=type.getRecordComponents();
            var args=Arrays.stream(components).map(c->values.get(c.getName())).toArray();
            return type.getDeclaredConstructor(Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new)).newInstance(args);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    static PaymentSafetyReadFacts facts(List<PaymentRow> payments, List<TransactionRow> txs, List<CaptureRow> captures) {
        return new PaymentSafetyReadFacts(row(OrderRow.class,"id",1L),payments,txs,List.of(),captures,Map.of(),Map.of());
    }
    String hash(PaymentSafetyReadFacts f) { return PaymentSafetyInputFingerprint.sha256(f); }

    @Test void collectionPermutationAndMapInsertionOrderDoNotChangeCanonicalInput() {
        var a=row(PaymentRow.class,"id",1L,"status","AUTHORIZED");
        var b=row(PaymentRow.class,"id",2L,"status","FAILED");
        assertThat(hash(facts(List.of(a,b),List.of(),List.of())))
                .isEqualTo(hash(facts(List.of(b,a),List.of(),List.of()))).hasSize(64);
        var profiles=new LinkedHashMap<String,String>();profiles.put("z","v2");profiles.put("a","v1");
        var f=facts(List.of(),List.of(),List.of());
        assertThat(hash(new PaymentSafetyReadFacts(f.order(),f.payments(),f.transactions(),f.statusHistory(),f.captureRequests(),profiles,Map.of())))
                .isEqualTo(hash(new PaymentSafetyReadFacts(f.order(),f.payments(),f.transactions(),f.statusHistory(),f.captureRequests(),Map.of("a","v1","z","v2"),Map.of())));
    }
    @Test void duplicatesAreRetainedAndCollectionsCannotBeMutated() {
        var p=row(PaymentRow.class,"id",1L);var list=new ArrayList<>(List.of(p));
        var f=facts(list,List.of(),List.of());list.clear();
        assertThat(f.payments()).hasSize(1);
        assertThatThrownBy(()->f.payments().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(hash(f)).isNotEqualTo(hash(facts(List.of(p,p),List.of(),List.of())));
    }
    @Test void nullEmptyWhitespaceCaseAndUnicodeRemainDistinct() {
        var hashes=new HashSet<String>();
        for(String value:Arrays.asList(null,""," ","AUTHORIZE","authorize","é","e\u0301"))
            hashes.add(hash(facts(List.of(),List.of(row(TransactionRow.class,"purpose",value)),List.of())));
        assertThat(hashes).hasSize(7);
    }
    @Test void binaryValuesHaveDefensiveCopiesAndValueEquality() {
        byte[] bytes={0,1,2};var value=new Bytes(bytes);bytes[0]=9;
        byte[] copy=value.value();copy[1]=9;
        assertThat(value.value()).containsExactly(0,1,2);
        assertThat(value).isEqualTo(new Bytes(new byte[]{0,1,2}));
        var a=facts(List.of(),List.of(),List.of(row(CaptureRow.class,"requestBody",value)));
        assertThat(hash(a)).isNotEqualTo(hash(facts(List.of(),List.of(),List.of(row(CaptureRow.class,"requestBody",new Bytes(new byte[]{0,1,3}))))));
        assertThat(hash(a)).isEqualTo(hash(facts(List.of(),List.of(),List.of(row(CaptureRow.class,"requestBody",new Bytes(new byte[]{0,1,2}))))));
    }
    @Test void everyRawFieldParticipatesIncludingObservedActivePointerAndV54Bytes() {
        for(Class<?> type:List.of(OrderRow.class,PaymentRow.class,TransactionRow.class,HistoryRow.class,CaptureRow.class)) {
            for(var c:type.getRecordComponents()) {
                Object value=sample(c.getType());
                var empty=row(type);var changed=row(type,c.getName(),value);
                assertThat(hash(withRow(type,changed))).as(type.getSimpleName()+"."+c.getName()).isNotEqualTo(hash(withRow(type,empty)));
            }
        }
    }
    Object sample(Class<?> t) {
        if(t==String.class)return "raw";if(t==Long.class)return 1L;if(t==Integer.class)return 1;
        if(t==Short.class)return (short)1;if(t==Bytes.class)return new Bytes(new byte[]{1});
        if(t==LocalDateTime.class)return LocalDateTime.of(2026,1,2,3,4,5,123456000);
        if(t==Instant.class)return Instant.parse("2026-01-02T03:04:05.123456Z");
        throw new AssertionError(t);
    }
    PaymentSafetyReadFacts withRow(Class<?> type,Object row) {
        var f=facts(List.of(),List.of(),List.of());
        return new PaymentSafetyReadFacts(type==OrderRow.class?(OrderRow)row:f.order(),
            type==PaymentRow.class?List.of((PaymentRow)row):List.of(),type==TransactionRow.class?List.of((TransactionRow)row):List.of(),
            type==HistoryRow.class?List.of((HistoryRow)row):List.of(),type==CaptureRow.class?List.of((CaptureRow)row):List.of(),Map.of(),Map.of());
    }
    @Test void timestampPrecisionAndProfileEvidenceAffectFingerprint() {
        var a=facts(List.of(),List.of(),List.of(row(CaptureRow.class,"createdAt",Instant.parse("2026-01-01T00:00:00.000001Z"))));
        var b=facts(List.of(),List.of(),List.of(row(CaptureRow.class,"createdAt",Instant.parse("2026-01-01T00:00:00.000002Z"))));
        assertThat(hash(a)).isNotEqualTo(hash(b));
        assertThat(hash(a)).isNotEqualTo(hash(new PaymentSafetyReadFacts(a.order(),a.payments(),a.transactions(),a.statusHistory(),a.captureRequests(),Map.of("connection-manifest","approved-v1:sha256"),Map.of())));
        var ref=new com.example.ecsite.service.payment.PaymentSafetySnapshot.TimeReference(com.example.ecsite.service.payment.PaymentSafetySnapshot.TimeField.ORDER_PAID,1L);
        var origin=new com.example.ecsite.service.payment.PaymentSafetySnapshot.TimeOrigin(com.example.ecsite.service.payment.PaymentSafetySnapshot.ClockKind.DATABASE_WALL_CLOCK,"fixture-only","Asia/Tokyo");
        assertThat(hash(a)).isNotEqualTo(hash(new PaymentSafetyReadFacts(a.order(),a.payments(),a.transactions(),a.statusHistory(),a.captureRequests(),Map.of(),Map.of(ref,origin))));
    }
    @Test void malformedUnicodeCannotCollapseIntoReplacementBytes() {
        assertThatThrownBy(()->hash(facts(List.of(row(PaymentRow.class,"status","\uD800")),List.of(),List.of())))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
