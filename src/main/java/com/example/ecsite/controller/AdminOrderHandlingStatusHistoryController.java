package com.example.ecsite.controller;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.example.ecsite.entity.OrderHandlingStatus;
import com.example.ecsite.entity.OrderHandlingStatusHistory;
import com.example.ecsite.form.AdminOrderHandlingStatusHistorySearchForm;
import com.example.ecsite.service.OrderHandlingStatusHistoryService;

@Controller
@RequestMapping("/admin/order-handling-status-histories")
public class AdminOrderHandlingStatusHistoryController {

    private static final int MAX_PAGE_SIZE = 100;

    private final OrderHandlingStatusHistoryService orderHandlingStatusHistoryService;

    public AdminOrderHandlingStatusHistoryController(
            OrderHandlingStatusHistoryService orderHandlingStatusHistoryService) {

        this.orderHandlingStatusHistoryService = orderHandlingStatusHistoryService;
    }

    @GetMapping
    public String list(
            @ModelAttribute("searchForm")
            AdminOrderHandlingStatusHistorySearchForm searchForm,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model) {

        int safePage = Math.max(page, 0);

        int safeSize = Math.min(
                Math.max(size, 1),
                MAX_PAGE_SIZE);

        Page<OrderHandlingStatusHistory> historyPage =
                orderHandlingStatusHistoryService.search(
                        searchForm,
                        safePage,
                        safeSize);

        model.addAttribute(
                "histories",
                historyPage.getContent());

        model.addAttribute(
                "historyPage",
                historyPage);

        model.addAttribute(
                "statuses",
                OrderHandlingStatus.values());

        return "admin/order-handling-status-histories/list";
    }
}
