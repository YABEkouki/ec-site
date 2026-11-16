package com.example.ecsite.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.nio.charset.StandardCharsets;
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
import com.example.ecsite.service.warehouse.WarehouseExportCsvService;
import com.example.ecsite.service.warehouse.WarehouseExportRunner;

@WebMvcTest(AdminWarehouseExportController.class)
@Import(SecurityConfig.class)
class AdminWarehouseExportControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private WarehouseExportBatchStore batches;
    @MockitoBean private WarehouseExportCsvService csv;
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

    @Test
    void listShowsBatchesAndOnlySealedOnesOfferTheDownload() throws Exception {
        when(runner.startDate()).thenReturn(DAY);
        when(batches.list(60)).thenReturn(List.of(
                new WarehouseExportBatchStore.Listed(batch(11, State.SEALED), 1, 2, DAY.atTime(15, 0)),
                new WarehouseExportBatchStore.Listed(batch(12, State.OPEN), 3, 0, null)));

        mockMvc.perform(get("/admin/warehouse-exports").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/warehouse-exports/list"))
                .andExpect(content().string(containsString("/admin/warehouse-exports/11/csv")))
                .andExpect(content().string(not(containsString("/admin/warehouse-exports/12/csv"))))
                .andExpect(content().string(containsString("出力処理中")))
                .andExpect(content().string(containsString("2回")))
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
    void detailEscapesLineValues() throws Exception {
        when(batches.find(11)).thenReturn(Optional.of(batch(11, State.SEALED)));
        when(batches.lines(11)).thenReturn(List.of(new WarehouseExportBatchStore.Line(42, "ORDERED", 0, 750, 5L,
                DAY.atTime(14, 0), DAY.atTime(14, 5, 4), "<b>山田</b>", 1)));
        when(batches.downloads(11)).thenReturn(List.of(new WarehouseExportBatchStore.DownloadRecord(1, 7, "admin",
                LocalDateTime.of(2026, 11, 16, 15, 0), 1, 1, "ab".repeat(32))));

        mockMvc.perform(get("/admin/warehouse-exports/11").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("&lt;b&gt;山田&lt;/b&gt;")))
                .andExpect(content().string(not(containsString("<b>山田</b>"))))
                .andExpect(content().string(containsString("/admin/orders/42")))
                .andExpect(content().string(containsString("ab".repeat(32))));
    }

    @Test
    void downloadReturnsTheRecordedCsv() throws Exception {
        byte[] body = "csv".getBytes(StandardCharsets.UTF_8);
        when(csv.download(11)).thenReturn(new WarehouseExportCsvService.Download("warehouse-export-20261116-11.csv", body, 1, 1));

        mockMvc.perform(post("/admin/warehouse-exports/11/csv").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("warehouse-export-20261116-11.csv")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().bytes(body));
        verify(csv).download(11);
    }

    @Test
    void refusedDownloadRedirectsWithTheReason() throws Exception {
        when(csv.download(12)).thenThrow(new WarehouseExportCsvService.NotDownloadableException("出力処理が完了していないバッチはダウンロードできません。"));
        mockMvc.perform(post("/admin/warehouse-exports/12/csv").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(redirectedUrl("/admin/warehouse-exports"))
                .andExpect(flash().attribute("errorMessage", "出力処理が完了していないバッチはダウンロードできません。"));
    }

    @Test
    void downloadNeedsCsrfAndAnAdministrator() throws Exception {
        mockMvc.perform(post("/admin/warehouse-exports/11/csv").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/warehouse-exports/11/csv").with(user("customer").roles("USER")).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/warehouse-exports"))
                .andExpect(redirectedUrl("/admin/login"));
        mockMvc.perform(get("/admin/warehouse-exports/11/csv").with(user("admin").roles("ADMIN")))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(csv);
    }

    @Test
    void invalidIdIsABadRequest() throws Exception {
        mockMvc.perform(get("/admin/warehouse-exports/abc").with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());
        verify(batches, org.mockito.Mockito.never()).find(anyLong());
    }
}
