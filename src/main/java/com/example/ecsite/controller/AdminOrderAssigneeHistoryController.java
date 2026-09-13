package com.example.ecsite.controller;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.example.ecsite.entity.AdminAccount;
import com.example.ecsite.entity.OrderAssigneeHistory;
import com.example.ecsite.form.AdminOrderAssigneeHistoryFilter;
import com.example.ecsite.form.AdminOrderAssigneeHistorySearchForm;
import com.example.ecsite.service.AdminAccountService;
import com.example.ecsite.service.OrderAssigneeHistoryService;

@Controller
@RequestMapping("/admin/order-assignee-histories")
public class AdminOrderAssigneeHistoryController {

    private static final int MAX_PAGE_SIZE = 100;

    private final OrderAssigneeHistoryService orderAssigneeHistoryService;
    private final AdminAccountService adminAccountService;

    public AdminOrderAssigneeHistoryController(
            OrderAssigneeHistoryService orderAssigneeHistoryService,
            AdminAccountService adminAccountService) {

        this.orderAssigneeHistoryService = orderAssigneeHistoryService;
        this.adminAccountService = adminAccountService;
    }

    @GetMapping("/list")
    public String list(
            @ModelAttribute("searchForm")
            AdminOrderAssigneeHistorySearchForm searchForm,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model) {

        int safePage = Math.max(page, 0);

        int safeSize = Math.min(
                Math.max(size, 1),
                MAX_PAGE_SIZE);

        Page<OrderAssigneeHistory> historyPage =
                orderAssigneeHistoryService.search(
                        searchForm,
                        safePage,
                        safeSize);

        List<AdminAccount> adminAccounts =
                adminAccountService.findAllOrderByUsernameAsc();

        model.addAttribute(
                "histories",
                historyPage.getContent());

        model.addAttribute(
                "historyPage",
                historyPage);

        model.addAttribute(
                "adminAccounts",
                adminAccounts);

        model.addAttribute(
                "assigneeFilters",
                AdminOrderAssigneeHistoryFilter.values());

        return "admin/order-assignee-histories/list";
    }
}
