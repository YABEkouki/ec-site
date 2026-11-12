package com.example.ecsite.service.order.history;

import java.util.*;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceBundle.*;
import static com.example.ecsite.service.order.history.Feature125EvidenceFixture.*;

/** Web tests reuse saved evidence, never the current catalogue or a database. */
public final class CustomerOrderChangeHistoryWebFixture {
    public static final String SCRIPT="<script>alert('history-xss')</script>";
    public static OrderChangeEvidenceBundle bundle(String scenario) {
        return switch(scenario) {
            case "retained" -> new Feature125EvidenceFixture(false).bundle();
            case "reauthorized" -> new Feature125EvidenceFixture(true).bundle();
            case "replacement" -> new Feature125EvidenceFixture(false,true).bundle();
            case "decrease" -> new LegacyOrderChangeFixture(false,false,true).bundle();
            case "removed" -> new LegacyOrderChangeFixture(true,true,false).bundle();
            case "oldReauthorization" -> new LegacyReauthorizationFixture().bundle();
            case "mixed" -> mixed();
            case "partial" -> CustomerOrderChangeHistoryViewTest.legacyMany(22,true);
            case "many" -> CustomerOrderChangeHistoryViewTest.legacyMany(41,false);
            case "empty" -> OrderChangeHistoryVerifierTest.empty();
            case "none" -> incomplete();
            case "limit" -> limit();
            case "xss" -> xss();
            default -> throw new IllegalArgumentException("Unknown fixture");
        };
    }
    private static OrderChangeEvidenceBundle mixed() {
        var f=new Feature125EvidenceFixture(false);var old=new LegacyOrderChangeFixture(false,false,false);
        f.add(EvidenceSet.HISTORIES,scalar(old.sets.get(EvidenceSet.HISTORIES).getFirst(),"id",90));
        f.add(EvidenceSet.ITEM_CHANGES,scalar(scalar(old.sets.get(EvidenceSet.ITEM_CHANGES).getFirst(),"id",91),"history_id",90));
        return f.bundle();
    }
    private static OrderChangeEvidenceBundle incomplete() {
        var b=OrderChangeHistoryVerifierTest.empty();return new OrderChangeEvidenceBundle(b.status(),b.order(),Map.of(),b.coverage(),List.of());
    }
    private static OrderChangeEvidenceBundle limit() {
        var b=OrderChangeHistoryVerifierTest.empty();return new OrderChangeEvidenceBundle(Status.LIMIT_EXCEEDED,b.order(),Map.of(),b.coverage(),List.of());
    }
    private static OrderChangeEvidenceBundle xss() {
        var f=new LegacyOrderChangeFixture(false,true,false);
        f.edit(EvidenceSet.ITEM_CHANGES,0,"product_name",SCRIPT);
        f.edit(EvidenceSet.CHARGE_CHANGES,0,"old_name",SCRIPT+"old-charge");
        f.edit(EvidenceSet.CHARGE_CHANGES,0,"new_name",SCRIPT+"new-charge");
        return f.bundle();
    }
}
