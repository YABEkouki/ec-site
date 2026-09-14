package com.example.ecsite.controller;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.example.ecsite.dto.AdminCustomerListItem;
import com.example.ecsite.form.AdminCustomerSearchForm;
import com.example.ecsite.service.AdminCustomerService;

@Controller
@RequestMapping("/admin/customers")
public class AdminCustomerController {

    private final AdminCustomerService adminCustomerService;

    public AdminCustomerController(
            AdminCustomerService adminCustomerService) {
        this.adminCustomerService = adminCustomerService;
    }

    @GetMapping
    public String list(
            @ModelAttribute("searchForm")
            AdminCustomerSearchForm searchForm,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model) {

        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, 100);

        Page<AdminCustomerListItem> customerPage =
                adminCustomerService.searchCustomers(
                        searchForm,
                        safePage,
                        safeSize);

        model.addAttribute(
                "customers",
                customerPage.getContent());

        model.addAttribute(
                "customerPage",
                customerPage);

        return "admin/customers/list";
    }
}
