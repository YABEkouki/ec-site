package com.example.ecsite.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.validation.BindingResult;

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

    @GetMapping("/products/new")
    public String showCreateForm(Model model) {
        model.addAttribute("product", new Product());

        return "products/form";
    }

    @PostMapping("/products")
    public String create(
            @Valid @ModelAttribute("product") Product product,
            BindingResult bindingResult,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            return "products/form";
        }

        productService.create(product);

        redirectAttributes.addFlashAttribute(
                "message",
                "商品を登録しました。");

        return "redirect:/products";
    }

    @GetMapping("/products/{id}/edit")
    public String showEditForm(
            @PathVariable Long id,
            Model model) {

        Product product = productService.findById(id);
        model.addAttribute("product", product);

        return "products/edit";
    }

    @PostMapping("/products/{id}/update")
    public String update(
            @PathVariable Long id,
            @Valid @ModelAttribute("product") Product product,
            BindingResult bindingResult,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            return "products/edit";
        }

        productService.update(id, product);

        redirectAttributes.addFlashAttribute(
                "message",
                "商品を更新しました。");

        return "redirect:/products";
    }

    @PostMapping("/products/{id}/delete")
    public String delete(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes) {

        productService.delete(id);

        redirectAttributes.addFlashAttribute(
                "message",
                "商品を削除しました。");

        return "redirect:/products";
    }
}