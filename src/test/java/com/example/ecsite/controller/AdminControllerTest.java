package com.example.ecsite.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.ecsite.dto.ActionRequiredAgingSummary;
import com.example.ecsite.dto.AdminAssigneeActionRequiredSummary;
import com.example.ecsite.entity.OrderHandlingStatus;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.OrderService;
import com.example.ecsite.service.ProductService;

@WebMvcTest(AdminController.class)
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private ProductService productService;

    @Test
    void indexAddsOrderSummariesToModel() throws Exception {

        when(orderService.countOrdersByStatus(OrderStatus.ORDERED))
                .thenReturn(1L);
        when(orderService.countOrdersByStatus(OrderStatus.PAID))
                .thenReturn(2L);
        when(orderService.countOrdersByStatus(OrderStatus.SHIPPED))
                .thenReturn(3L);
        when(orderService.countOrdersByStatus(OrderStatus.CANCELLED))
                .thenReturn(4L);

        when(orderService.countOrdersByHandlingStatus(OrderHandlingStatus.NONE))
                .thenReturn(10L);
        when(orderService.countOrdersByHandlingStatus(OrderHandlingStatus.NEEDS_ACTION))
                .thenReturn(5L);
        when(orderService.countOrdersByHandlingStatus(OrderHandlingStatus.IN_PROGRESS))
                .thenReturn(3L);
        when(orderService.countOrdersByHandlingStatus(OrderHandlingStatus.RESOLVED))
                .thenReturn(8L);

        ActionRequiredAgingSummary agingSummary = new ActionRequiredAgingSummary(4L, 2L);

        when(orderService.getActionRequiredAgingSummary())
                .thenReturn(agingSummary);

        Long adminId = 20L;

        AdminUserDetails adminUser = new AdminUserDetails(
                adminId,
                "admin",
                "password",
                true,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        ActionRequiredAgingSummary myAssignedAgingSummary = new ActionRequiredAgingSummary(
                3L,
                1L);

        when(orderService.getMyAssignedActionRequiredAgingSummary(
                adminId))
                .thenReturn(myAssignedAgingSummary);

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                adminUser,
                adminUser.getPassword(),
                adminUser.getAuthorities());

        when(orderService.countMyAssignedActionRequiredOrders(adminId))
                .thenReturn(2L);

        when(orderService.countUnassignedActionRequiredOrders())
                .thenReturn(4L);

        ActionRequiredAgingSummary unassignedAgingSummary = new ActionRequiredAgingSummary(5L, 2L);

        when(orderService.getUnassignedActionRequiredAgingSummary())
                .thenReturn(unassignedAgingSummary);

        List<AdminAssigneeActionRequiredSummary> assigneeSummaries = List.of(
                new AdminAssigneeActionRequiredSummary(
                        10L,
                        "admin01",
                        true,
                        5L),
                new AdminAssigneeActionRequiredSummary(
                        20L,
                        "admin02",
                        false,
                        2L));

        when(orderService.getActionRequiredOrderCountsByAssignee())
                .thenReturn(assigneeSummaries);

        mockMvc.perform(
                get("/admin").with(authentication(authentication)))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/index"))
                .andExpect(model().attribute("normalHandlingCount", 10L))
                .andExpect(model().attribute("needsActionCount", 5L))
                .andExpect(model().attribute("inProgressCount", 3L))
                .andExpect(model().attribute("resolvedCount", 8L))
                .andExpect(model().attribute("actionRequiredAgingSummary", agingSummary))
                .andExpect(model().attribute("myAssignedOrderCount", 2L))
                .andExpect(model().attribute("myAssignedActionRequiredAgingSummary", myAssignedAgingSummary))
                .andExpect(model().attribute("unassignedActionRequiredOrderCount", 4L))
                .andExpect(model().attribute("unassignedActionRequiredAgingSummary", unassignedAgingSummary))
                .andExpect(model().attribute("assigneeActionRequiredSummaries", assigneeSummaries));

        verify(orderService)
                .getMyAssignedActionRequiredAgingSummary(adminId);

        verify(orderService)
                .countUnassignedActionRequiredOrders();

        verify(orderService)
                .getUnassignedActionRequiredAgingSummary();

        verify(orderService)
                .getActionRequiredOrderCountsByAssignee();
    }

}
