package com.example.ecsite.service.order.history;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceBundle.*;

class OrderChangeEvidenceBundleTest {
    @Test void unverifiedRowsPreserveNullUnknownStateAndDuplicateLinks() {
        var fields=new LinkedHashMap<String,String>();fields.put("state","FUTURE_STATE");fields.put("history_id",null);
        var row=new EvidenceRow(fields,Map.of());
        var bundle=new OrderChangeEvidenceBundle(Status.COMPLETE,new OrderEvidence(1,2,0,null,null),
                Map.of(EvidenceSet.RESULTS,List.of(row,row)),new Coverage(2,20),List.of());
        fields.put("state","changed");
        assertThat(bundle.rows(EvidenceSet.RESULTS)).hasSize(2);
        assertThat(row.values()).containsEntry("state","FUTURE_STATE").containsEntry("history_id",null);
        assertThatThrownBy(()->row.values().put("state","tampered")).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void rawBinaryIsDefensivelyCopiedAndRedacted() {
        byte[] source={1,2,3};var bytes=new BinaryEvidence(source);source[0]=9;bytes.bytes()[1]=9;
        assertThat(bytes.bytes()).containsExactly(1,2,3);
        assertThat(bytes.toString()).doesNotContain("1, 2, 3");
        var row=new EvidenceRow(Map.of("provider_payment_id","secret-provider-id"),Map.of("start_snapshot",bytes));
        assertThat(row.toString()).doesNotContain("secret-provider-id");
    }
    @Test void limitedReadCannotMasqueradeAsCompleteEvidence() {
        assertThatThrownBy(()->new OrderChangeEvidenceBundle(Status.LIMIT_EXCEEDED,new OrderEvidence(1,2,0,null,null),
                Map.of(EvidenceSet.HISTORIES,List.of(new EvidenceRow(Map.of(),Map.of()))),new Coverage(1,0),List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
