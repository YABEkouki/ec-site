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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.data.domain.Page;
 
import com.example.ecsite.entity.Product;
import com.example.ecsite.service.ProductService;
import com.example.ecsite.form.ProductForm;
import com.example.ecsite.mapper.ProductMapper;

@Controller
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping("/products")
    public String list(
        @RequestParam(name = "keyword", required = false) String keyword,
        @RequestParam(name = "page", defaultValue = "0") int page,
        @RequestParam(name = "sort", defaultValue = "newest") String sort,
        Model model) {

        int size = 10; // Number of products per page   

        Page<Product> productPage = productService.search(keyword, page, size, sort);

        model.addAttribute("products", productPage.getContent());
        model.addAttribute("productPage", productPage);
        model.addAttribute("keyword", keyword);
        model.addAttribute("sort", sort);

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
        model.addAttribute("productForm", new ProductForm());

        return "products/form";
    }

    @PostMapping("/products")
    public String create(
            @Valid @ModelAttribute("productForm") ProductForm productForm,
            BindingResult bindingResult,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            return "products/form";
        }

        productService.create(productForm);

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "商品を登録しました。");

        return "redirect:/products";
    }

    @GetMapping("/products/{id}/edit")
    public String edit(
            @PathVariable Long id,
            Model model) {

        Product product = productService.findById(id);

        ProductForm productForm = ProductMapper.toForm(product);

        model.addAttribute("productForm", productForm);
        model.addAttribute("productId", id);

        return "products/edit";
    }

    @PostMapping("/products/{id}/update")
    public String update(
            @PathVariable Long id,
            @Valid @ModelAttribute("productForm") ProductForm productForm,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            model.addAttribute("productId", id);

            return "products/edit";
        }

        productService.update(id, productForm);

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

