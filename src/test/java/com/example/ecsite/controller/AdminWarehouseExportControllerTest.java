package com.example.ecsite.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.ecsite.config.SecurityConfig;
import com.example.ecsite.security.AdminAuthenticationSuccessHandler;
import com.example.ecsite.security.CustomerAuthenticationSuccessHandler;
import com.example.ecsite.service.AdminUserDetailsService;
import com.example.ecsite.service.CustomUserDetailsService;
import com.example.ecsite.service.warehouse.WarehouseExportBatchStore;
import com.example.ecsite.service.warehouse.WarehouseExportBatchStore.Batch;
import com.example.ecsite.service.warehouse.WarehouseExportBatchStore.State;
import com.example.ecsite.service.warehouse.WarehouseFileOutputStore;
import com.example.ecsite.service.warehouse.WarehouseFileOutputStore.Kind;
import com.example.ecsite.service.warehouse.WarehouseExportRunner;

@WebMvcTest(AdminWarehouseExportController.class)
@Import(SecurityConfig.class)
class AdminWarehouseExportControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private WarehouseExportBatchStore batches;
    @MockitoBean private WarehouseFileOutputStore fileOutputs;
    @MockitoBean private WarehouseExportRunner runner;
    @MockitoBean private AdminUserDetailsService adminUserDetailsService;
    @MockitoBean private CustomUserDetailsService customUserDetailsService;
    @MockitoBean private AdminAuthenticationSuccessHandler adminAuthenticationSuccessHandler;
    @MockitoBean private CustomerAuthenticationSuccessHandler customerAuthenticationSuccessHandler;

    private static final LocalDate DAY = LocalDate.of(2026, 11, 16);

    private static Batch batch(long id, State state) {
        return new Batch(id, DAY, state, DAY.atTime(14, 5, 3),
                state == State.SEALED ? DAY.atTime(14, 5, 9) : null, state == State.SEALED ? 1 : null);
    }

    static WarehouseFileOutputStore.Output output(long id, WarehouseFileOutputStore.State state, String error) {
        boolean written = state == WarehouseFileOutputStore.State.WRITTEN;
        return new WarehouseFileOutputStore.Output(id, Kind.REGULAR, 11L, null, "11", DAY, "warehouse-header-20261116-11.csv",
                "warehouse-detail-20261116-11.csv", 1, 2, state, state == WarehouseFileOutputStore.State.PENDING ? 0 : 2,
                DAY.atTime(14, 10), DAY.atTime(14, 6), error, written ? "aa".repeat(32) : null, written ? "bb".repeat(32) : null,
                written ? DAY.atTime(14, 6) : null, DAY.atTime(14, 5, 9));
    }

    @Test
    void listShowsBatchesWithTheirFileOutputAndNoDownload() throws Exception {
        when(runner.startDate()).thenReturn(DAY);
        when(batches.list(60)).thenReturn(List.of(
                new WarehouseExportBatchStore.Listed(batch(11, State.SEALED), 1, WarehouseFileOutputStore.State.FAILED),
                new WarehouseExportBatchStore.Listed(batch(12, State.OPEN), 3, null)));

        mockMvc.perform(get("/admin/warehouse-exports").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/warehouse-exports/list"))
                .andExpect(content().string(not(containsString("/csv"))))
                .andExpect(content().string(not(containsString("ダウンロード"))))
                .andExpect(content().string(containsString("出力失敗")))
                .andExpect(content().string(containsString("出力処理中")))
                .andExpect(content().string(containsString("2026/11/16</span> 以降")));
    }

    @Test
    void listWarnsWhenTheStartDateIsMissing() throws Exception {
        when(batches.list(60)).thenReturn(List.of());
        mockMvc.perform(get("/admin/warehouse-exports").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("自動出力の開始日が設定されていない")));
    }

    @Test
    void detailShowsAFailedOutputWithItsReasonAndTheReOutputAndEscapesValues() throws Exception {
        when(batches.find(11)).thenReturn(Optional.of(batch(11, State.SEALED)));
        when(batches.lines(11)).thenReturn(List.of(new WarehouseExportBatchStore.Line(42, "ORDERED", 0, 750, 5L,
                DAY.atTime(14, 0), DAY.atTime(14, 5, 4), "<b>山田</b>", 1)));
        when(batches.downloads(11)).thenReturn(List.of(new WarehouseExportBatchStore.DownloadRecord(1, 7, "admin",
                LocalDateTime.of(2026, 11, 16, 15, 0), 1, 1, "ab".repeat(32))));
        when(fileOutputs.forBatch(11)).thenReturn(Optional.of(output(5, WarehouseFileOutputStore.State.FAILED, "<i>上書きしません</i>")));
        when(fileOutputs.retries(5)).thenReturn(List.of(new WarehouseFileOutputStore.Retry(1, "<u>first</u>", DAY.atTime(15, 0), 12, "x")));

        mockMvc.perform(get("/admin/warehouse-exports/11").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("&lt;b&gt;山田&lt;/b&gt;")))
                .andExpect(content().string(not(containsString("<b>山田</b>"))))
                .andExpect(content().string(containsString("/admin/orders/42")))
                .andExpect(content().string(containsString("warehouse-header-20261116-11.csv")))
                .andExpect(content().string(containsString("warehouse-detail-20261116-11.csv")))
                .andExpect(content().string(containsString("出力失敗")))
                .andExpect(content().string(containsString("&lt;i&gt;上書きしません&lt;/i&gt;")))
                .andExpect(content().string(containsString("&lt;u&gt;first&lt;/u&gt;")))
                .andExpect(content().string(containsString("/admin/warehouse-file-outputs/5/retry")))
                .andExpect(content().string(not(containsString("/admin/warehouse-exports/11/csv"))))
                .andExpect(content().string(containsString("ab".repeat(32))));
    }

    @Test
    void aWrittenOutputShowsItsDigestsAndNoReOutput() throws Exception {
        when(batches.find(11)).thenReturn(Optional.of(batch(11, State.SEALED)));
        when(fileOutputs.forBatch(11)).thenReturn(Optional.of(output(5, WarehouseFileOutputStore.State.WRITTEN, null)));
        mockMvc.perform(get("/admin/warehouse-exports/11").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("出力済み")))
                .andExpect(content().string(containsString("aa".repeat(32))))
                .andExpect(content().string(not(containsString("/retry"))));
    }

    @Test
    void aBatchWithoutOutputExplainsWhy() throws Exception {
        when(batches.find(11)).thenReturn(Optional.of(batch(11, State.SEALED)));
        when(batches.find(12)).thenReturn(Optional.of(batch(12, State.OPEN)));
        mockMvc.perform(get("/admin/warehouse-exports/11").with(user("admin").roles("ADMIN")))
                .andExpect(content().string(containsString("ファイル出力を導入する前のバッチ")));
        mockMvc.perform(get("/admin/warehouse-exports/12").with(user("admin").roles("ADMIN")))
                .andExpect(content().string(containsString("出力処理が完了（締め）すると")));
    }

    @Test
    void theCsvDownloadIsGone() throws Exception {
        mockMvc.perform(get("/admin/warehouse-exports/11/csv").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidIdIsABadRequest() throws Exception {
        mockMvc.perform(get("/admin/warehouse-exports/abc").with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());
        verify(batches, org.mockito.Mockito.never()).find(anyLong());
    }
}
