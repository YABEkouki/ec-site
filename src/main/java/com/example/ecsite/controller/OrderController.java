package com.example.ecsite.controller;

import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.example.ecsite.entity.Order;
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
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model) {

        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, 100);

        Page<Order> orderPage = orderService.findOrdersByUserId(
                loginUser.getId(),
                safePage,
                safeSize);

        model.addAttribute(
                "orders", orderPage.getContent());

        model.addAttribute(
                "orderPage", orderPage);

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