package com.example.ecsite.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.service.OrderService;
import com.example.ecsite.service.ProductService;

@Controller
public class AdminController {

    private final OrderService orderService;
    private final ProductService productService;

    public AdminController(
            OrderService orderService,
            ProductService productService) {

        this.orderService = orderService;
        this.productService = productService;
    }

    @GetMapping("/admin")
    public String index(Model model) {

        model.addAttribute(
                "orderedCount",
                orderService.countOrdersByStatus(
                        OrderStatus.ORDERED));

        model.addAttribute(
                "paidCount",
                orderService.countOrdersByStatus(
                        OrderStatus.PAID));

        model.addAttribute(
                "shippedCount",
                orderService.countOrdersByStatus(
                        OrderStatus.SHIPPED));

        model.addAttribute(
                "cancelledCount",
                orderService.countOrdersByStatus(
                        OrderStatus.CANCELLED));

        int lowStockThreshold = 5;

        model.addAttribute(
                "lowStockProducts",
                productService.findLowStockProducts(
                        lowStockThreshold));

        model.addAttribute(
                "lowStockThreshold",
                lowStockThreshold);

        return "admin/index";
    }
}