package com.example.ecsite.controller;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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
import com.example.ecsite.service.warehouse.WarehouseFileOutputStore;
import com.example.ecsite.service.warehouse.WarehouseFileOutputStore.Kind;
import com.example.ecsite.service.warehouse.WarehouseFileOutputStore.State;

/** Feature137-B: administrator re-output of a failed warehouse file output. */
@WebMvcTest(AdminWarehouseFileOutputController.class)
@Import(SecurityConfig.class)
class AdminWarehouseFileOutputControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private WarehouseFileOutputStore outputs;
    @MockitoBean private AdminUserDetailsService adminUserDetailsService;
    @MockitoBean private CustomUserDetailsService customUserDetailsService;
    @MockitoBean private AdminAuthenticationSuccessHandler adminAuthenticationSuccessHandler;
    @MockitoBean private CustomerAuthenticationSuccessHandler customerAuthenticationSuccessHandler;

    private static final LocalDate DAY = LocalDate.of(2026, 11, 16);

    static WarehouseFileOutputStore.Output output(long id, Kind kind, State state) {
        return new WarehouseFileOutputStore.Output(id, kind, kind == Kind.REGULAR ? 11L : null, kind == Kind.REGULAR ? null : 42L,
                kind == Kind.REGULAR ? "11" : "E3", DAY, "h.csv", "d.csv", 1, 1, state, 2, DAY.atTime(15, 0), DAY.atTime(14, 6),
                "x", null, null, null, DAY.atTime(14, 5));
    }

    @Test
    void reOutputOfABatchReturnsToTheBatch() throws Exception {
        when(outputs.find(5)).thenReturn(Optional.of(output(5, Kind.REGULAR, State.FAILED)));
        mockMvc.perform(post("/admin/warehouse-file-outputs/5/retry").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(redirectedUrl("/admin/warehouse-exports/11"))
                .andExpect(flash().attribute("successMessage", "倉庫ファイルの再出力を受け付けました。次の出力処理で書き出します。"));
        verify(outputs).retry(5);
    }

    @Test
    void reOutputOfAnEmergencyOutputReturnsToTheOrder() throws Exception {
        when(outputs.find(6)).thenReturn(Optional.of(output(6, Kind.EMERGENCY, State.FAILED)));
        mockMvc.perform(post("/admin/warehouse-file-outputs/6/retry").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(redirectedUrl("/admin/orders/42"));
        verify(outputs).retry(6);
    }

    @Test
    void aRefusedReOutputShowsTheReason() throws Exception {
        when(outputs.find(5)).thenReturn(Optional.of(output(5, Kind.REGULAR, State.WRITTEN)));
        when(outputs.retry(5)).thenThrow(new WarehouseFileOutputStore.NotRetryableException("出力に失敗した記録だけ再出力できます。"));
        mockMvc.perform(post("/admin/warehouse-file-outputs/5/retry").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(redirectedUrl("/admin/warehouse-exports/11"))
                .andExpect(flash().attribute("errorMessage", "出力に失敗した記録だけ再出力できます。"));
    }

    @Test
    void anUnknownOutputReturnsToTheBatchList() throws Exception {
        when(outputs.find(9)).thenReturn(Optional.empty());
        mockMvc.perform(post("/admin/warehouse-file-outputs/9/retry").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(redirectedUrl("/admin/warehouse-exports"))
                .andExpect(flash().attribute("errorMessage", "倉庫ファイルの出力記録が見つかりません。"));
        verify(outputs, never()).retry(9);
    }

    @Test
    void reOutputNeedsCsrfAPostAndAnAdministrator() throws Exception {
        mockMvc.perform(post("/admin/warehouse-file-outputs/5/retry").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/warehouse-file-outputs/5/retry").with(user("customer").roles("USER")).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/warehouse-file-outputs/5/retry").with(user("admin").roles("ADMIN")))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(post("/admin/warehouse-file-outputs/abc/retry").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isBadRequest());
        verify(outputs, never()).retry(5);
    }
}
