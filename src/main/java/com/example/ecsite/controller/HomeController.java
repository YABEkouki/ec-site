package com.example.ecsite.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.example.ecsite.service.AnnouncementService;
import com.example.ecsite.service.ProductService;

@Controller
public class HomeController {

    private final AnnouncementService announcementService;
    private final ProductService productService;

    public HomeController(
            AnnouncementService announcementService,
            ProductService productService) {

        this.announcementService = announcementService;
        this.productService = productService;
    }

    @GetMapping("/")
    public String index(
            @AuthenticationPrincipal UserDetails userDetails,
            Model model) {

        model.addAttribute(
                "username",
                userDetails.getUsername());

        model.addAttribute(
                "announcements",
                announcementService.findLatestPublished(5));

        model.addAttribute(
                "latestProducts",
                productService.findLatestAvailableProducts(5));

        return "index";
    }
}
