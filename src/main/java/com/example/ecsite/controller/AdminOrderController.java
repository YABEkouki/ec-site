package com.example.ecsite.controller;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.service.OrderService;

@Controller
@RequestMapping("/admin/orders")
public class AdminOrderController {

    private final OrderService orderService;

    public AdminOrderController(
            OrderService orderService) {

        this.orderService = orderService;
    }

    @GetMapping
    public String list(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model) {

        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, 100);

        Page<Order> orderPage = orderService.findAllOrders(
                status,
                safePage,
                safeSize);

        model.addAttribute(
                "orders",
                orderPage.getContent());

        model.addAttribute(
                "orderPage",
                orderPage);

        model.addAttribute(
                "statuses",
                OrderStatus.values());

        model.addAttribute(
                "selectedStatus",
                status);

        return "admin/orders/list";
    }

    @GetMapping("/{id}")
    public String detail(
            @PathVariable Long id,
            Model model) {

        model.addAttribute(
                "order",
                orderService.findOrderWithItems(id));

        return "admin/orders/detail";
    }

    @PostMapping("/{id}/pay")
    public String markAsPaid(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes) {

        try {
            orderService.markAsPaid(id);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "注文を支払済みに変更しました。");

        } catch (InvalidOrderStatusException e) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    e.getMessage());
        }

        return "redirect:/admin/orders/" + id;
    }

    @PostMapping("/{id}/ship")
    public String markAsShipped(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes) {

        try {
            orderService.markAsShipped(id);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "注文を発送済みに変更しました。");

        } catch (InvalidOrderStatusException e) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    e.getMessage());
        }

        return "redirect:/admin/orders/" + id;
    }

    @PostMapping("/{id}/cancel")
    public String cancel(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes) {

        try {
            orderService.cancelOrder(id);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "注文をキャンセルしました。");

        } catch (InvalidOrderStatusException e) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    e.getMessage());
        }

        return "redirect:/admin/orders/" + id;
    }

}