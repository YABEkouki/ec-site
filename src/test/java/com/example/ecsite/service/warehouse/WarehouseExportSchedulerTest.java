package com.example.ecsite.service.warehouse;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class WarehouseExportSchedulerTest {

    @Test
    void enablingWithoutAStartDateFailsStartup() {
        var runner = mock(WarehouseExportRunner.class);
        assertThatThrownBy(() -> new WarehouseExportScheduler(runner))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("start-date");
        verify(runner, never()).runDue();
    }

    @Test
    void aFailedRunIsDeferredToTheNextTick() {
        var runner = mock(WarehouseExportRunner.class);
        when(runner.startDate()).thenReturn(LocalDate.of(2026, 11, 17));
        when(runner.runDue()).thenThrow(new IllegalStateException("database unavailable"));
        var scheduler = new WarehouseExportScheduler(runner);
        assertThatCode(scheduler::exportDue).doesNotThrowAnyException();
        verify(runner).runDue();
    }

    @Test
    void theRunnerRejectsAnInvalidPageSize() {
        assertThatThrownBy(() -> new WarehouseExportRunner(null, null, null, null, "2026-11-17", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new WarehouseExportRunner(null, null, null, null, " ", 1).startDate()).isNull();
    }
}
