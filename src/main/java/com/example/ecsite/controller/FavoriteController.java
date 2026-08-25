package com.example.ecsite.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.exception.FavoriteAlreadyExistsException;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.FavoriteService;

@Controller
public class FavoriteController {

    private final FavoriteService favoriteService;

    public FavoriteController(
            FavoriteService favoriteService) {

        this.favoriteService = favoriteService;
    }

    @GetMapping("/favorites")
    public String list(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            Model model) {

        model.addAttribute(
                "favorites",
                favoriteService.findFavoritesByUserId(
                        userDetails.getId()));

        return "favorites/list";
    }

    @PostMapping("/products/{productId}/favorites")
    public String add(
            @PathVariable Long productId,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            RedirectAttributes redirectAttributes) {

        try {

            favoriteService.addFavorite(
                    productId,
                    userDetails.getId());

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "お気に入りに登録しました。");

        } catch (FavoriteAlreadyExistsException e) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    e.getMessage());
        }

        return "redirect:/products/" + productId;
    }

    @PostMapping("/products/{productId}/favorites/delete")
    public String remove(
            @PathVariable Long productId,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            RedirectAttributes redirectAttributes) {

        favoriteService.removeFavorite(
                productId,
                userDetails.getId());

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "お気に入りから解除しました。");

        return "redirect:/products/" + productId;
    }
}