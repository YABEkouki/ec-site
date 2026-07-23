package com.example.ecsite.controller;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.example.ecsite.entity.Product;
import com.example.ecsite.service.ProductService;

@Controller
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping("/products")
    public String list(Model model) {

        List<Product> products = productService.findAll();

        model.addAttribute("products", products);

        return "products/list";
    }

    @GetMapping("/products/{id}")
    public String detail(@PathVariable Long id, Model model) {

        Product product = productService.findById(id);

        model.addAttribute("product", product);

        return "products/detail";
    }
}