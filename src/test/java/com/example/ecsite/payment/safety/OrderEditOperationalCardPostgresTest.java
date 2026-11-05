package com.example.ecsite.payment.safety;

import java.util.*;
import org.junit.jupiter.api.TestInfo;
import static org.assertj.core.api.Assertions.*;

/** Runs the existing real-finance/Widget/STOP contract with explicit OPERATIONAL approval.
 * Inherits only the guarded private PostgreSQL container and mocked provider fixture. */
class OrderEditOperationalCardPostgresTest extends OrderEditCardPostgresTest {
    @Override void configureRuntime(TestInfo info){
        stopRuntime();var open=jdbc.queryForList("select distinct db_generation_id from order_edit_admissions where closed_at is null",UUID.class);
        assertThat(open).hasSizeLessThanOrEqualTo(1);
        UUID generation=open.isEmpty()?jdbc.queryForObject("select f125_runtime_new_generation((select version from order_edit_runtime_control where id=1),'OPERATIONAL')",UUID.class):open.getFirst();
        approve(generation);
    }
    void approve(UUID generation){
        jdbc.queryForObject("select f125_runtime_probe((select version from order_edit_runtime_control where id=1))",String.class);
        jdbc.queryForObject("select f125_runtime_enable((select version from order_edit_runtime_control where id=1),?,encode(sha256(convert_to(f125_operational_identity()::text,'UTF8')),'hex'),'isolated operator verified single writer')",Object.class,generation);
        assertThat(jdbc.queryForObject("select state from order_edit_runtime_control where id=1",String.class)).isEqualTo("RUNNING");
    }
    @Override void resumeTestRuntime(){approve(jdbc.queryForObject("select db_generation_id from order_edit_admissions where id=?",UUID.class,receipt));}
}
