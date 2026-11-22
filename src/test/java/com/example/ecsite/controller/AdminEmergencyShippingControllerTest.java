package com.example.ecsite.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.ecsite.config.SecurityConfig;
import com.example.ecsite.entity.Order;
import com.example.ecsite.security.AdminAuthenticationSuccessHandler;
import com.example.ecsite.security.CustomerAuthenticationSuccessHandler;
import com.example.ecsite.service.AdminUserDetailsService;
import com.example.ecsite.service.CustomUserDetailsService;
import com.example.ecsite.service.emergency.AdminEmergencyShippingQueries;
import com.example.ecsite.service.emergency.EmergencyShippingMode;

/** Feature138-B: the emergency shipping list shows who shipped each order (an administrator or the warehouse import). */
@WebMvcTest(AdminEmergencyShippingController.class)
@Import(SecurityConfig.class)
class AdminEmergencyShippingControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AdminEmergencyShippingQueries queries;
    @MockitoBean private AdminUserDetailsService adminUserDetailsService;
    @MockitoBean private CustomUserDetailsService customUserDetailsService;
    @MockitoBean private AdminAuthenticationSuccessHandler adminAuthenticationSuccessHandler;
    @MockitoBean private CustomerAuthenticationSuccessHandler customerAuthenticationSuccessHandler;

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 21, 23, 42);

    @Test
    void shippedRowsShowTheShipper() throws Exception {
        when(queries.today()).thenReturn(LocalDate.of(2026, 11, 22));
        when(queries.list(any(), any(), any(), any(), anyInt())).thenReturn(new AdminEmergencyShippingQueries.Result(List.of(
                new AdminEmergencyShippingQueries.Row(27, EmergencyShippingMode.SAME_DAY, AT, "admin001", "急ぎ", "SHIPPED",
                        AT.plusHours(10), null, Order.WAREHOUSE_SHIPMENT_IMPORTER, "WRITTEN", AT, 0),
                new AdminEmergencyShippingQueries.Row(24, EmergencyShippingMode.AFTER_DEADLINE, AT, "admin001", "急ぎ", "SHIPPED",
                        AT.plusHours(11), 1L, "admin002", null, null, 1),
                new AdminEmergencyShippingQueries.Row(28, EmergencyShippingMode.SAME_DAY, AT, "admin001", "急ぎ", "ORDERED",
                        null, null, null, "WRITTEN", AT, 0)), 0, 3, false));
        when(queries.permittedAdmins()).thenReturn(List.of());
        when(queries.permissionHistories()).thenReturn(List.of());

        mockMvc.perform(get("/admin/emergency-shipments").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("出荷済み（2026/11/22 09:42・倉庫出荷取込み）")))
                .andExpect(content().string(containsString("出荷済み（2026/11/22 10:42・admin002）")))
                .andExpect(content().string(containsString("未出荷")));
    }
}
