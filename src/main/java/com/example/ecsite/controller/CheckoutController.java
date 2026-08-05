package com.example.ecsite.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.cart.Cart;
import com.example.ecsite.entity.Order;
import com.example.ecsite.exception.OrderValidationException;
import com.example.ecsite.form.CheckoutForm;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.OrderService;

import jakarta.validation.Valid;

@Controller
@SessionAttributes({ "cart", "checkoutForm" })
public class CheckoutController {

    private final OrderService orderService;

    public CheckoutController(OrderService orderService) {
        this.orderService = orderService;
    }

    @ModelAttribute("cart")
    public Cart cart() {
        return new Cart();
    }

    @ModelAttribute("checkoutForm")
    public CheckoutForm checkoutForm() {
        return new CheckoutForm();
    }

    @GetMapping("/checkout")
    public String input(
            @ModelAttribute("cart") Cart cart,
            RedirectAttributes redirectAttributes) {

        try {
            orderService.validateCart(cart);

        } catch (OrderValidationException e) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    e.getMessage());

            return "redirect:/cart";
        }

        return "checkout/input";
    }

    @PostMapping("/checkout/confirm")
    public String confirm(
            @Valid @ModelAttribute("checkoutForm") CheckoutForm checkoutForm,
            BindingResult bindingResult,
            @ModelAttribute("cart") Cart cart,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            return "checkout/input";
        }

        try {
            orderService.validateCart(cart);

        } catch (OrderValidationException e) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    e.getMessage());

            return "redirect:/cart";
        }

        return "checkout/confirm";
    }

    @PostMapping("/checkout/order")
    public String placeOrder(
            @Valid 
            @ModelAttribute("checkoutForm") CheckoutForm checkoutForm,
            BindingResult bindingResult,
            @ModelAttribute("cart") Cart cart,
            @AuthenticationPrincipal CustomUserDetails loginUser,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            return "checkout/input";
        }

        try {
            Order order = orderService.createOrder(
                    loginUser.getId(),
                    cart,
                    checkoutForm);

            cart.clear();

            redirectAttributes.addFlashAttribute(
                    "orderId",
                    order.getId());

            return "redirect:/checkout/complete";

        } catch (OrderValidationException e) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    e.getMessage());

            return "redirect:/cart";
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