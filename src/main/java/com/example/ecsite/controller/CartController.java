package com.example.ecsite.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.SessionAttributes;

import com.example.ecsite.cart.Cart;

@Controller
@SessionAttributes("cart")
public class CartController {

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
}