package com.example.ecsite.controller;

import java.time.LocalDate;

import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
import com.example.ecsite.entity.StockMovement;
import com.example.ecsite.entity.StockMovementType;
import com.example.ecsite.exception.InvalidProductImageException;
import com.example.ecsite.exception.InvalidStockAdjustmentException;
import com.example.ecsite.form.ProductForm;
import com.example.ecsite.form.StockAdjustmentForm;
import com.example.ecsite.mapper.ProductMapper;
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.CategoryService;
import com.example.ecsite.service.InventoryService;
import com.example.ecsite.service.ProductService;
import com.example.ecsite.service.StockMovementService;

import jakarta.validation.Valid;

@Controller
@RequestMapping("/admin/products")
public class AdminProductController {

        private final ProductService productService;
        private final CategoryService categoryService;
        private final InventoryService inventoryService;
        private final StockMovementService stockMovementService;

        public AdminProductController(
                        ProductService productService,
                        CategoryService categoryService,
                        InventoryService inventoryService,
                        StockMovementService stockMovementService) {

                this.productService = productService;
                this.categoryService = categoryService;
                this.inventoryService = inventoryService;
                this.stockMovementService = stockMovementService;
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

                try {
                        productService.create(productForm);
                } catch (InvalidProductImageException e) {

                        bindingResult.rejectValue(
                                        "imageFile",
                                        "invalid",
                                        e.getMessage());

                        addCategories(model);

                        return "admin/products/form";
                }

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

                model.addAttribute("product", product);

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

                        model.addAttribute("product", product);

                        model.addAttribute(
                                        "categories",
                                        categoryService
                                                        .findCategoriesForProductEdit(
                                                                        product.getCategory().getId()));

                        return "admin/products/edit";
                }

                try {
                        productService.update(id, productForm);
                } catch (InvalidProductImageException e) {

                        bindingResult.rejectValue(
                                        "imageFile",
                                        "invalid",
                                        e.getMessage());

                        Product product = productService.findById(id);

                        model.addAttribute("productId", id);
                        model.addAttribute("product", product);

                        model.addAttribute(
                                        "categories",
                                        categoryService.findCategoriesForProductEdit(
                                                        product.getCategory().getId()));

                        return "admin/products/edit";
                }

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

        @GetMapping("/{id}/stock")
        public String stock(
                        @PathVariable Long id,
                        @RequestParam(required = false) String returnTo,
                        Model model) {

                Product product = productService.findById(id);

                model.addAttribute(
                                "product",
                                product);

                model.addAttribute(
                                "stockAdjustmentForm",
                                new StockAdjustmentForm());

                model.addAttribute(
                                "returnTo",
                                returnTo);

                return "admin/products/stock";
        }

        @PostMapping("/{id}/stock")
        public String adjustStock(
                        @PathVariable Long id,
                        @Valid @ModelAttribute("stockAdjustmentForm") StockAdjustmentForm stockAdjustmentForm,
                        BindingResult bindingResult,
                        Model model,
                        RedirectAttributes redirectAttributes,
                        @AuthenticationPrincipal AdminUserDetails userDetails,
                        @RequestParam(required = false) String returnTo) {

                if (bindingResult.hasErrors()) {

                        Product product = productService.findById(id);

                        model.addAttribute(
                                        "product",
                                        product);
                        model.addAttribute(
                                        "returnTo",
                                        returnTo);

                        return "admin/products/stock";
                }

                try {
                        inventoryService.adjustByAdmin(
                                        id,
                                        stockAdjustmentForm.getQuantity(),
                                        userDetails.getId(),
                                        userDetails.getUsername(),
                                        stockAdjustmentForm.getReason());
                } catch (InvalidStockAdjustmentException e) {

                        Product product = productService.findById(id);

                        model.addAttribute(
                                        "product",
                                        product);

                        model.addAttribute(
                                        "errorMessage",
                                        e.getMessage());

                        model.addAttribute(
                                        "returnTo",
                                        returnTo);

                        return "admin/products/stock";
                }

                redirectAttributes.addFlashAttribute(
                                "successMessage",
                                "在庫を調整しました。");

                if ("edit".equals(returnTo)) {
                        return "redirect:/admin/products/" + id + "/stock?returnTo=edit";
                }

                return "redirect:/admin/products/" + id + "/stock";
        }

        @GetMapping("/{id}/stock/history")
        public String stockHistory(
                        @PathVariable Long id,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                        @RequestParam(required = false) String username,
                        @RequestParam(required = false) StockMovementType movementType,
                        @RequestParam(defaultValue = "0") int page,
                        Model model) {

                int size = 20;

                Product product = productService.findById(id);

                Page<StockMovement> movementPage = stockMovementService.search(
                                id,
                                from,
                                to,
                                username,
                                movementType,
                                page,
                                size);

                model.addAttribute("product", product);
                model.addAttribute("movementPage", movementPage);
                model.addAttribute("movements", movementPage.getContent());
                model.addAttribute("from", from);
                model.addAttribute("to", to);
                model.addAttribute("username", username);
                model.addAttribute("movementType", movementType);

                return "admin/products/stock-history";
        }

        private void addCategories(Model model) {

                model.addAttribute(
                                "categories",
                                categoryService.findActiveCategories());
        }
}
