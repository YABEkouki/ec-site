package com.example.ecsite.service.orderchange;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.example.ecsite.service.orderchange.AdminOrderHoldViewService.Category;
import com.example.ecsite.service.orderchange.AdminOrderHoldViewService.Item;
import com.example.ecsite.service.orderchange.AdminOrderHoldViewService.Kind;
import com.example.ecsite.service.orderchange.AdminOrderHoldViewService.Summary;

class AdminOrderHoldViewServiceTest {

    private static Item admission(long order, Category category, String linked, long elapsed, boolean stale) {
        return new Item(order, "ORDERED", Kind.ADMISSION, UUID.randomUUID(), category, "QUEUED", null, null, null, null,
                "ACCEPTED", linked, "", elapsed, stale);
    }

    @Test
    void admissionBehindAManualReviewOperationNeedsAnAdministrator() {
        var blocked = admission(8, Category.EDIT_OPEN_OVERDUE, "MANUAL_REVIEW", 10, false);
        assertThat(blocked.manual()).isTrue();
        assertThat(blocked.note()).contains("MANUAL_REVIEW");

        var progressing = admission(9, Category.EDIT_RECOVERY_PENDING, "AUTHORIZING", 10, false);
        assertThat(progressing.manual()).isFalse();
        assertThat(progressing.note()).isNull();
    }

    @Test
    void abortedOperationWithOpenReviewAdmissionExplainsTheMissingClosure() {
        var item = admission(10, Category.EDIT_REVIEW_REQUIRED, "ABORTED", 10, false);
        assertThat(item.manual()).isTrue();
        assertThat(item.note()).contains("受付を終了する操作はありません");
    }

    @Test
    void summaryCountsDistinctOrdersAndOrderingPutsManualThenStaleFirst() {
        var automatic = admission(1, Category.EDIT_RECOVERY_PENDING, "AUTHORIZING", 5, false);
        var stale = admission(2, Category.EDIT_RECOVERY_PENDING, "AUTHORIZING", 90, true);
        var manual = admission(3, Category.EDIT_OPEN_OVERDUE, "MANUAL_REVIEW", 1, false);
        var sameOrder = admission(3, Category.EDIT_RECOVERY_PENDING, "AUTHORIZING", 1, false);
        var items = List.of(automatic, stale, manual, sameOrder);
        assertThat(AdminOrderHoldViewService.summarize(items)).isEqualTo(new Summary(3, 1, 1));
        assertThat(items.stream().sorted(AdminOrderHoldViewService.ORDER).toList())
                .startsWith(manual, stale);
    }
}
