package com.example.ecsite.service.order.evidence;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class OrderEditAdmissionGuardTest {
    @ParameterizedTest @CsvSource({
        "RECORDED_UNVERIFIED,NOT_READY,false,false,true",
        "ACCEPTED,QUEUED,false,false,true",
        "UNPROVEN,REVIEW_REQUIRED,false,false,true",
        "ACCEPTED,RECOVERY_PENDING,false,false,true",
        "ACCEPTED,COMPLETED,false,true,true",
        "ACCEPTED,COMPLETED,true,true,false",
        "ACCEPTED,COMPLETED,true,false,true",
        "RECORDED_UNVERIFIED,CLOSED_UNAPPLIED,true,false,false",
        "UNPROVEN,FAILED_UNAPPLIED,true,false,false",
        "ACCEPTED,CLOSED_UNAPPLIED,true,false,false",
        "REJECTED_LATE,NOT_READY,true,false,false",
        "REJECTED_LATE,QUEUED,true,false,true",
        "ACCEPTED,CLOSED_UNAPPLIED,true,true,true",
        "UNKNOWN,CLOSED_UNAPPLIED,true,false,true",
        "ACCEPTED,UNKNOWN,true,false,true",
        ",,true,false,true"
    })
    void onlyKnownConsistentTerminalShapesPass(String admission,String execution,boolean closed,boolean applied,boolean blocked) {
        assertThat(OrderEditAdmissionGuard.blocks(admission,execution,closed,applied)).isEqualTo(blocked);
    }
}
