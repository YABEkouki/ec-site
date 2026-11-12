package com.example.ecsite.service.order.history;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceBundle.*;

class OrderChangeEvidenceReferencesTest {
    @Test void syntheticDanglingAndForeignLinksRemainDistinctWithoutForeignPayload(){
        var queries=mock(OrderChangeEvidenceQueries.class);
        when(queries.existingReferenceIds(EvidenceSet.HISTORIES,Set.of("999"))).thenReturn(Set.of("999"));
        when(queries.existingReferenceIds(EvidenceSet.OPERATIONS,Set.of("missing-operation"))).thenReturn(Set.of());
        var row=new EvidenceRow(Map.of("id","result","application_history_id","999","application_operation_id","missing-operation","disposition","FUTURE"),Map.of());
        var evidence=Map.of(EvidenceSet.RESULTS,List.of(row,row));
        var diagnostics=OrderChangeEvidenceReferences.inspect(evidence,queries);
        assertThat(diagnostics).hasSize(4);
        assertThat(diagnostics).filteredOn(d->d.code().equals("OUT_OF_SCOPE_REFERENCE")).hasSize(2);
        assertThat(diagnostics).filteredOn(d->d.code().equals("MISSING_REFERENCE")).hasSize(2);
        assertThat(evidence.get(EvidenceSet.RESULTS)).hasSize(2);assertThat(row.value("disposition")).isEqualTo("FUTURE");
    }
    @Test void danglingOrderHeadIsReportedWithoutLoadingAnotherOrdersHistory(){
        var queries=mock(OrderChangeEvidenceQueries.class);
        when(queries.existingReferenceIds(EvidenceSet.HISTORIES,Set.of("99"))).thenReturn(Set.of("99"));
        var diagnostics=OrderChangeEvidenceReferences.inspect(new OrderEvidence(1,2,3,null,99L),Map.of(),queries);
        assertThat(diagnostics).singleElement().satisfies(d->{assertThat(d.code()).isEqualTo("OUT_OF_SCOPE_REFERENCE");assertThat(d.field()).isEqualTo("evidence_head_history_id");});
    }
}
