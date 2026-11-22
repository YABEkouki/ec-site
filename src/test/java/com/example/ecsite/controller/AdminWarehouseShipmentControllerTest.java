package com.example.ecsite.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.ecsite.config.SecurityConfig;
import com.example.ecsite.security.AdminAuthenticationSuccessHandler;
import com.example.ecsite.security.CustomerAuthenticationSuccessHandler;
import com.example.ecsite.service.AdminUserDetailsService;
import com.example.ecsite.service.CustomUserDetailsService;
import com.example.ecsite.service.warehouse.WarehouseShipmentCsvParser;
import com.example.ecsite.service.warehouse.WarehouseShipmentImportLineState;
import com.example.ecsite.service.warehouse.WarehouseShipmentImportQueries;
import com.example.ecsite.service.warehouse.WarehouseShipmentImportReason;
import com.example.ecsite.service.warehouse.WarehouseShipmentImportService;

@WebMvcTest(AdminWarehouseShipmentController.class)
@Import(SecurityConfig.class)
class AdminWarehouseShipmentControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private WarehouseShipmentImportService imports;
    @MockitoBean private WarehouseShipmentImportQueries queries;
    @MockitoBean private AdminUserDetailsService adminUserDetailsService;
    @MockitoBean private CustomUserDetailsService customUserDetailsService;
    @MockitoBean private AdminAuthenticationSuccessHandler adminAuthenticationSuccessHandler;
    @MockitoBean private CustomerAuthenticationSuccessHandler customerAuthenticationSuccessHandler;

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 17, 15, 0);
    private static final WarehouseShipmentImportQueries.Import IMPORT =
            new WarehouseShipmentImportQueries.Import(3, "shipped.csv", 2, 5, "uploader", AT);

    private static MockMultipartFile file(String content) {
        return new MockMultipartFile("file", "shipped.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void listShowsImportsAndWarnsThatTheWorkerIsDisabled() throws Exception {
        when(queries.list(60)).thenReturn(List.of(new WarehouseShipmentImportQueries.Listed(IMPORT, 1, 0, 0, 0, 1)));
        mockMvc.perform(get("/admin/warehouse-shipments").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/warehouse-shipments/list"))
                .andExpect(content().string(containsString("/admin/warehouse-shipments/3")))
                .andExpect(content().string(containsString("発送処理（バックグラウンド）が無効")))
                .andExpect(content().string(containsString(WarehouseShipmentCsvParser.HEADER)));
    }

    @Test
    void detailShowsEachLineWithItsOutcome() throws Exception {
        when(queries.find(3)).thenReturn(Optional.of(IMPORT));
        when(queries.lines(3)).thenReturn(List.of(
                new WarehouseShipmentImportQueries.Line(1, 2, "12,1043,2026-11-17,ヤマト運輸,1234", 12L, null, 1043L,
                        LocalDate.of(2026, 11, 17), "ヤマト運輸", "1234", WarehouseShipmentImportLineState.SHIPPED, null, 1, AT),
                new WarehouseShipmentImportQueries.Line(2, 3, "<b>x</b>", null, null, null, null, null, null,
                        WarehouseShipmentImportLineState.REJECTED, WarehouseShipmentImportReason.INVALID_FORMAT, 0, AT),
                // Feature138-B: an emergency row shows its batch id as the warehouse files carry it.
                new WarehouseShipmentImportQueries.Line(3, 4, "E7,2044,2026-11-17,佐川急便,99", null, 7L, 2044L,
                        LocalDate.of(2026, 11, 17), "佐川急便", "99", WarehouseShipmentImportLineState.REJECTED,
                        WarehouseShipmentImportReason.FILE_NOT_WRITTEN, 0, AT)));
        mockMvc.perform(get("/admin/warehouse-shipments/3").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/admin/orders/1043")))
                .andExpect(content().string(containsString("<td>12</td>")))
                .andExpect(content().string(containsString("<td>E7</td>")))
                .andExpect(content().string(containsString(WarehouseShipmentImportReason.FILE_NOT_WRITTEN.label())))
                .andExpect(content().string(containsString("発送完了")))
                .andExpect(content().string(containsString(WarehouseShipmentImportReason.INVALID_FORMAT.label())))
                .andExpect(content().string(containsString("&lt;b&gt;x&lt;/b&gt;")))
                .andExpect(content().string(not(containsString("<b>x</b>"))));
        mockMvc.perform(get("/admin/warehouse-shipments/99").with(user("admin").roles("ADMIN")))
                .andExpect(redirectedUrl("/admin/warehouse-shipments"));
    }

    @Test
    void uploadRedirectsToTheImport() throws Exception {
        when(imports.upload(eq("shipped.csv"), any())).thenReturn(new WarehouseShipmentImportService.Accepted(3, false));
        mockMvc.perform(multipart("/admin/warehouse-shipments").file(file("x")).with(csrf()).with(user("admin").roles("ADMIN")))
                .andExpect(redirectedUrl("/admin/warehouse-shipments/3"))
                .andExpect(flash().attribute("message", containsString("取り込みました")));
        when(imports.upload(eq("shipped.csv"), any())).thenReturn(new WarehouseShipmentImportService.Accepted(3, true));
        mockMvc.perform(multipart("/admin/warehouse-shipments").file(file("x")).with(csrf()).with(user("admin").roles("ADMIN")))
                .andExpect(flash().attribute("message", containsString("取込み済み")));
    }

    @Test
    void anUnreadableFileIsReportedOnTheList() throws Exception {
        when(imports.upload(any(), any())).thenThrow(new WarehouseShipmentCsvParser.InvalidFileException("見出しが違います。"));
        mockMvc.perform(multipart("/admin/warehouse-shipments").file(file("x")).with(csrf()).with(user("admin").roles("ADMIN")))
                .andExpect(redirectedUrl("/admin/warehouse-shipments"))
                .andExpect(flash().attribute("errorMessage", containsString("見出しが違います。")));
        mockMvc.perform(multipart("/admin/warehouse-shipments").file(file("")).with(csrf()).with(user("admin").roles("ADMIN")))
                .andExpect(flash().attribute("errorMessage", containsString("選択してください")));
    }

    @Test
    void uploadRequiresAnAdministratorAndCsrf() throws Exception {
        mockMvc.perform(multipart("/admin/warehouse-shipments").file(file("x")).with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart("/admin/warehouse-shipments").file(file("x")).with(csrf()).with(user("u").roles("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/warehouse-shipments"))
                .andExpect(status().is3xxRedirection());
        verifyNoInteractions(imports);
    }
}
