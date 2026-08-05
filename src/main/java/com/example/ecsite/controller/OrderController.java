package com.example.ecsite.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.OrderService;

@Controller
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/orders")
    public String list(
            @AuthenticationPrincipal CustomUserDetails loginUser,
            Model model) {

        model.addAttribute(
                "orders",
                orderService.findOrdersByUserId(
                        loginUser.getId()));

        return "orders/list";
    }

    @GetMapping("/orders/{id}")
    public String detail(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails loginUser,
            Model model) {

        model.addAttribute(
                "order",
                orderService.findOrderByIdAndUserId(
                        id,
                        loginUser.getId()));

        return "orders/detail";
    }
}