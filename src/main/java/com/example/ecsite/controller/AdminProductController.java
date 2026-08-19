package com.example.ecsite.controller;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.entity.Product;
import com.example.ecsite.form.ProductForm;
import com.example.ecsite.mapper.ProductMapper;
import com.example.ecsite.service.CategoryService;
import com.example.ecsite.service.ProductService;

import jakarta.validation.Valid;

@Controller
@RequestMapping("/admin/products")
public class AdminProductController {

        private final ProductService productService;
        private final CategoryService categoryService;

        public AdminProductController(
                        ProductService productService,
                        CategoryService categoryService) {

                this.productService = productService;
                this.categoryService = categoryService;
        }

        @GetMapping
        public String list(
                        @RequestParam(name = "keyword", required = false) String keyword,
                        @RequestParam(name = "categoryId", required = false) Long categoryId,
                        @RequestParam(name = "page", defaultValue = "0") int page,
                        @RequestParam(name = "sort", defaultValue = "newest") String sort,
                        Model model) {

                int size = 10;

                Page<Product> productPage = productService.search(
                                keyword,
                                categoryId,
                                page,
                                size,
                                sort);

                model.addAttribute(
                                "products",
                                productPage.getContent());

                model.addAttribute(
                                "productPage",
                                productPage);

                model.addAttribute(
                                "keyword",
                                keyword);
                model.addAttribute(
                                "categoryId",
                                categoryId);
                model.addAttribute(
                                "categories",
                                categoryService.findAllCategories());
                model.addAttribute(
                                "sort",
                                sort);

                return "admin/products/list";
        }

        @GetMapping("/new")
        public String showCreateForm(Model model) {

                model.addAttribute(
                                "productForm",
                                new ProductForm());

                addCategories(model);

                return "admin/products/form";
        }

        @PostMapping
        public String create(
                        @Valid @ModelAttribute("productForm") ProductForm productForm,
                        BindingResult bindingResult,
                        Model model,
                        RedirectAttributes redirectAttributes) {

                if (bindingResult.hasErrors()) {
                        addCategories(model);
                        return "admin/products/form";
                }

                productService.create(productForm);

                redirectAttributes.addFlashAttribute(
                                "successMessage",
                                "商品を登録しました。");

                return "redirect:/admin/products";
        }

        @GetMapping("/{id}/edit")
        public String edit(
                        @PathVariable Long id,
                        Model model) {

                Product product = productService.findById(id);

                ProductForm productForm = ProductMapper.toForm(product);

                model.addAttribute(
                                "productForm",
                                productForm);

                model.addAttribute(
                                "productId",
                                id);

                model.addAttribute(
                                "categories",
                                categoryService
                                                .findCategoriesForProductEdit(
                                                                product.getCategory().getId()));

                return "admin/products/edit";
        }

        @PostMapping("/{id}/update")
        public String update(
                        @PathVariable Long id,
                        @Valid @ModelAttribute("productForm") ProductForm productForm,
                        BindingResult bindingResult,
                        Model model,
                        RedirectAttributes redirectAttributes) {

                if (bindingResult.hasErrors()) {

                        Product product = productService.findById(id);

                        model.addAttribute("productId", id);

                        model.addAttribute(
                                        "categories",
                                        categoryService
                                                        .findCategoriesForProductEdit(
                                                                        product.getCategory().getId()));

                        return "admin/products/edit";
                }

                productService.update(id, productForm);

                redirectAttributes.addFlashAttribute(
                                "message",
                                "商品を更新しました。");

                return "redirect:/admin/products";
        }

        @PostMapping("/{id}/delete")
        public String delete(
                        @PathVariable Long id,
                        RedirectAttributes redirectAttributes) {

                productService.delete(id);

                redirectAttributes.addFlashAttribute(
                                "message",
                                "商品を販売終了にしました。");

                return "redirect:/admin/products";
        }

        @GetMapping("/inactive")
        public String inactiveProducts(
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "10") int size,
                        Model model) {

                Page<Product> productPage = productService.findInactiveProducts(
                                page,
                                size);

                model.addAttribute(
                                "products",
                                productPage.getContent());

                model.addAttribute(
                                "productPage",
                                productPage);

                return "admin/products/inactive";
        }

        @PostMapping("/{id}/restore")
        public String restore(
                        @PathVariable Long id,
                        RedirectAttributes redirectAttributes) {

                productService.restore(id);

                redirectAttributes.addFlashAttribute(
                                "successMessage",
                                "商品を販売中に戻しました。");

                return "redirect:/admin/products/inactive";
        }

        private void addCategories(Model model) {

                model.addAttribute(
                                "categories",
                                categoryService.findActiveCategories());
        }
}