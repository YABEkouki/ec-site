package com.example.ecsite.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.service.OrderService;

@Controller
public class AdminController {

    private final OrderService orderService;

    public AdminController(
            OrderService orderService) {

        this.orderService = orderService;
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

        return "admin/index";
    }
}