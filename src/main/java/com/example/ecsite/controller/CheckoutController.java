package com.example.ecsite.controller;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.bind.support.SessionStatus;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.cart.Cart;
import com.example.ecsite.entity.Order;
import com.example.ecsite.exception.OrderValidationException;
import com.example.ecsite.form.CheckoutForm;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.OrderService;

import jakarta.servlet.http.HttpSession;
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
            RedirectAttributes redirectAttributes,
            Model model,
            HttpSession session) {

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

        String checkoutToken = UUID.randomUUID().toString();

        session.setAttribute(
                "checkoutToken",
                checkoutToken);

        model.addAttribute(
                "checkoutToken",
                checkoutToken);

        return "checkout/confirm";
    }

    @PostMapping("/checkout/order")
    public String placeOrder(
            @Valid @ModelAttribute("checkoutForm") CheckoutForm checkoutForm,
            BindingResult bindingResult,
            @ModelAttribute("cart") Cart cart,
            @AuthenticationPrincipal CustomUserDetails loginUser,
            @RequestParam(name = "checkoutToken", required = false) String checkoutToken,
            RedirectAttributes redirectAttributes,
            HttpSession session,
            SessionStatus sessionStatus) {

        if (!consumeCheckoutToken(
                session,
                checkoutToken)) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "注文処理が既に実行されたか、"
                            + "確認画面の有効期限が切れています。");

            return "redirect:/cart";
        }

        if (bindingResult.hasErrors()) {
            return "checkout/input";
        }

        try {
            Order order = orderService.createOrder(
                    loginUser.getId(),
                    cart,
                    checkoutForm);

            sessionStatus.setComplete();

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

    @GetMapping("/checkout/confirm")
    public String confirmByGet() {

        return "redirect:/checkout";
    }

    private boolean consumeCheckoutToken(
            HttpSession session,
            String submittedToken) {

        if (submittedToken == null) {
            return false;
        }

        synchronized (session) {

            Object sessionToken = session.getAttribute(
                    "checkoutToken");

            if (!(sessionToken instanceof String)
                    || !sessionToken.equals(
                            submittedToken)) {

                return false;
            }

            session.removeAttribute(
                    "checkoutToken");

            return true;
        }
    }

}