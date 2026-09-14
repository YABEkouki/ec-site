package com.example.ecsite.controller;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.example.ecsite.dto.AdminCustomerDetail;
import com.example.ecsite.dto.AdminCustomerListItem;
import com.example.ecsite.dto.AdminCustomerPurchaseSummary;
import com.example.ecsite.entity.Order;
import com.example.ecsite.form.AdminCustomerSearchForm;
import com.example.ecsite.service.AdminCustomerService;
import com.example.ecsite.service.OrderService;

@Controller
@RequestMapping("/admin/customers")
public class AdminCustomerController {

    private final AdminCustomerService adminCustomerService;
    private final OrderService orderService;

    public AdminCustomerController(
            AdminCustomerService adminCustomerService,
            OrderService orderService) {
        this.adminCustomerService = adminCustomerService;
        this.orderService = orderService;
    }

    @GetMapping
    public String list(
            @ModelAttribute("searchForm") AdminCustomerSearchForm searchForm,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model) {

        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, 100);

        Page<AdminCustomerListItem> customerPage = adminCustomerService.searchCustomers(
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

    @GetMapping("/{id}")
    public String detail(
            @PathVariable Long id,
            Model model) {

        AdminCustomerDetail customer = adminCustomerService.findCustomerDetail(id);
        AdminCustomerPurchaseSummary purchaseSummary = adminCustomerService.getPurchaseSummary(id);

        model.addAttribute("customer", customer);
        model.addAttribute("purchaseSummary", purchaseSummary);

        return "admin/customers/detail";
    }

    @GetMapping("/{id}/orders")
    public String orders(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model) {

        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, 100);

        AdminCustomerDetail customer = adminCustomerService.findCustomerDetail(id);
        Page<Order> orderPage = orderService.findOrdersByUserId(id, safePage, safeSize);

        model.addAttribute("customer", customer);
        model.addAttribute("orders", orderPage.getContent());
        model.addAttribute("orderPage", orderPage);

        return "admin/customers/orders";
    }

}
