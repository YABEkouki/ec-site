package com.example.ecsite.service.order.history;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import com.example.ecsite.service.order.evidence.OrderContentSnapshot;

/** Internal verified content; a display mapper must explicitly select fields. Legacy is not a full snapshot. */
public record VerifiedOrderChange(long historyId,String route,Instant changedAt,
        OrderContentSnapshot before,OrderContentSnapshot after,LocalDateTime legacyChangedAt,LegacyOrderChangeEvidence legacy) {
    /** Preserves Feature125's existing constructor and snapshot/time contract. */
    public VerifiedOrderChange(long historyId,String route,Instant changedAt,OrderContentSnapshot before,OrderContentSnapshot after){
        this(historyId,route,changedAt,before,after,null,null);
    }
    public VerifiedOrderChange {
        Objects.requireNonNull(route);
        if(legacy==null){Objects.requireNonNull(changedAt);Objects.requireNonNull(before);Objects.requireNonNull(after);if(legacyChangedAt!=null)throw new IllegalArgumentException("Mixed evidence");}
        else if(legacyChangedAt==null||changedAt!=null||before!=null||after!=null)throw new IllegalArgumentException("Legacy requires saved local time and deltas only");
    }
    static VerifiedOrderChange legacy(long id,LocalDateTime time,LegacyOrderChangeEvidence evidence){return new VerifiedOrderChange(id,evidence.kind().name(),null,null,null,time,evidence);}
    /** Asia/Tokyo is an explicit display-order convention; the saved LocalDateTime stays unchanged. */
    public Instant sortInstant(){return changedAt!=null?changedAt:legacyChangedAt.atZone(ZoneId.of("Asia/Tokyo")).toInstant();}
    @Override public String toString(){return "VerifiedOrderChange[redacted]";}
}
