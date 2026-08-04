package com.example.ecsite.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.cart.Cart;
import com.example.ecsite.service.CartService;

@Controller
@SessionAttributes("cart")
public class CartController {

    private final CartService  CartService;

    public CartController(CartService cartService) {
        this.CartService = cartService;
    }

    @ModelAttribute("cart")
    public Cart cart() {
        return new Cart();
    }

    @GetMapping("/cart")
    public String showCart(
            @ModelAttribute("cart") Cart cart,
            Model model) {

        model.addAttribute("cart", cart);

        return "cart/index";
    }

    @PostMapping("/cart/add")
    public String addToCart(
            @RequestParam("productId") Long productId,
            @RequestParam(value = "quantity", defaultValue = "1") int quantity,
            @ModelAttribute("cart") Cart cart,
            RedirectAttributes redirectAttributes) {

        try {
            CartService.addItem(cart, productId, quantity);
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    e.getMessage());

            return "redirect:/products/" + productId;
        }

        return "redirect:/cart";
    }

    @PostMapping("/cart/update")
    public String updateCart(
            @RequestParam Long productId,
            @RequestParam int quantity,
            @ModelAttribute("cart") Cart cart,
            RedirectAttributes redirectAttributes) {

        try {
            cart.updateQuantity(productId, quantity);
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    e.getMessage());
        }

        return "redirect:/cart";
    }

    @PostMapping("/cart/remove")
    public String removeFromCart(
            @RequestParam Long productId,
            @ModelAttribute("cart") Cart cart) {

        cart.removeItem(productId);

        return "redirect:/cart";
    }

    @PostMapping("/cart/clear")
    public String clearCart(
            @ModelAttribute("cart") Cart cart) {

        cart.clear();

        return "redirect:/cart";
    }

}