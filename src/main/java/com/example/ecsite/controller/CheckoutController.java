package com.example.ecsite.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.cart.Cart;
import com.example.ecsite.entity.Order;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.OrderService;

@Controller
@SessionAttributes("cart")
public class CheckoutController {

    private final OrderService orderService;

    public CheckoutController(OrderService orderService) {
        this.orderService = orderService;
    }

    @ModelAttribute("cart")
    public Cart cart() {
        return new Cart();
    }

    @GetMapping("/checkout/confirm")
    public String confirm(
            @ModelAttribute("cart") Cart cart,
            RedirectAttributes redirectAttributes) {

        if (cart.getItems().isEmpty()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "カートに商品がありません。");

            return "redirect:/cart";
        }

        return "checkout/confirm";
    }

    @PostMapping("/checkout/order")
    public String placeOrder(
            @ModelAttribute("cart") Cart cart,
            @AuthenticationPrincipal CustomUserDetails loginUser,
            RedirectAttributes redirectAttributes) {

        try {
            Order order = orderService.createOrder(
                    loginUser.getId(),
                    cart);

            cart.clear();

            redirectAttributes.addFlashAttribute(
                    "orderId",
                    order.getId());

            return "redirect:/checkout/complete";

        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    e.getMessage());

            return "redirect:/checkout/confirm";
        }
    }

    @GetMapping("/checkout/complete")
    public String complete(Model model) {

        if (!model.containsAttribute("orderId")) {
            return "redirect:/";
        }

        return "checkout/complete";
    }
}