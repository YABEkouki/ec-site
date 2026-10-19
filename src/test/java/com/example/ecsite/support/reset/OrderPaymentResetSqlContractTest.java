package com.example.ecsite.support.reset;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

class OrderPaymentResetSqlContractTest {
    @Test void destructiveSqlIsManualOnlyAndRequiresAnExplicitTransactionGate() throws Exception {
        Path sql=Path.of("scripts/manual/reset-order-payment-data.sql");
        assertThat(sql).isRegularFile();
        String body=Files.readString(sql);
        assertThat(body).contains("pg_temp.order_payment_reset_gate", "current_database()", "backup_reference");
        assertThat(Path.of("src/main/resources/db/migration/reset-order-payment-data.sql")).doesNotExist();
        String wrapper=Files.readString(Path.of("scripts/manual/reset-order-payment-data.psql"));
        assertThat(wrapper).contains("ON_ERROR_STOP", "BEGIN;", "ROLLBACK;", "APPLY_ORDER_PAYMENT_RESET");
    }
}
