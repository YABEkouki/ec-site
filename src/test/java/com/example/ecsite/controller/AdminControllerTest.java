package com.example.ecsite.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.ecsite.entity.OrderHandlingStatus;
import com.example.ecsite.entity.OrderStatus;
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
    void indexAddsHandlingStatusCountsToModel() throws Exception {

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

        mockMvc.perform(get("/admin"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/index"))
                .andExpect(model().attribute("normalHandlingCount", 10L))
                .andExpect(model().attribute("needsActionCount", 5L))
                .andExpect(model().attribute("inProgressCount", 3L))
                .andExpect(model().attribute("resolvedCount", 8L));
    }

}
