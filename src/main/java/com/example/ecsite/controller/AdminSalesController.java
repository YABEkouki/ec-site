package com.example.ecsite.controller;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.example.ecsite.dto.DailySalesSummary;
import com.example.ecsite.dto.SalesDashboardSummary;
import com.example.ecsite.form.SalesDashboardForm;
import com.example.ecsite.service.SalesDashboardService;

@Controller
@RequestMapping("/admin/sales")
public class AdminSalesController {

    private final SalesDashboardService salesDashboardService;

    public AdminSalesController(
            SalesDashboardService salesDashboardService) {

        this.salesDashboardService = salesDashboardService;
    }

    @GetMapping
    public String index(
            @ModelAttribute("searchForm") SalesDashboardForm form,
            @RequestParam(name = "search", defaultValue = "false") boolean search,
            Model model) {

        if (!search) {
            salesDashboardService
                    .initializePeriod(form);
        }

        try {

            SalesDashboardSummary dashboard = salesDashboardService
                    .getDashboardSummary(form);

            List<DailySalesSummary> dailySales = salesDashboardService
                    .getDailySales(form);

            model.addAttribute(
                    "summary",
                    dashboard.current());

            model.addAttribute(
                    "comparison",
                    dashboard);

            model.addAttribute(
                    "dailySales",
                    dailySales);

        } catch (IllegalArgumentException e) {

            model.addAttribute(
                    "errorMessage",
                    e.getMessage());
        }

        return "admin/sales/index";
    }
}
