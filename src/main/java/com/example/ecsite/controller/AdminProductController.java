package com.example.ecsite.controller;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
import org.springframework.web.util.UriComponentsBuilder;

import com.example.ecsite.entity.Product;
import com.example.ecsite.entity.StockMovement;
import com.example.ecsite.entity.StockMovementType;
import com.example.ecsite.exception.InvalidProductImageException;
import com.example.ecsite.exception.InvalidProductSearchKeywordException;
import com.example.ecsite.exception.InvalidStockAdjustmentException;
import com.example.ecsite.form.ProductForm;
import com.example.ecsite.form.ProductSearchForm;
import com.example.ecsite.form.StockAdjustmentForm;
import com.example.ecsite.mapper.ProductMapper;
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.CategoryService;
import com.example.ecsite.service.InventoryService;
import com.example.ecsite.service.ProductService;
import com.example.ecsite.service.StockMovementService;
import com.example.ecsite.util.AdminReturnUrlHelper;

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
            @Valid @ModelAttribute("searchForm") ProductSearchForm searchForm,
            BindingResult bindingResult,
            @RequestParam(name = "page", defaultValue = "0") int page,
            Model model) {

        int size = 10;

        Page<Product> productPage;

        if (bindingResult.hasErrors()) {

            productPage = Page.empty(
                    PageRequest.of(page, size));

        } else {

            productPage = productService.searchForUser(
                    searchForm,
                    page,
                    size);
        }

        model.addAttribute(
                "products",
                productPage.getContent());

        model.addAttribute(
                "productPage",
                productPage);

        String returnUrl = buildProductListReturnUrl(searchForm, page);
        model.addAttribute(
                "returnUrl",
                returnUrl);

        model.addAttribute(
                "categories",
                categoryService.findActiveCategories());

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
        } catch (InvalidProductSearchKeywordException e) {

            bindingResult.reject(
                    "invalidSearchKeywords",
                    e.getMessage());

            addCategories(model);

            return "admin/products/form";

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
            @RequestParam(required = false) String returnUrl,
            Model model) {

        Product product = productService.findById(id);

        ProductForm productForm = ProductMapper.toForm(product);

        productForm.setSearchKeywords(productService.findSearchKeywords(id));

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

        model.addAttribute("returnUrl", AdminReturnUrlHelper.resolveProductListReturnUrl(returnUrl));

        return "admin/products/edit";
    }

    @PostMapping("/{id}/update")
    public String update(
            @PathVariable Long id,
            @Valid @ModelAttribute("productForm") ProductForm productForm,
            BindingResult bindingResult,
            @RequestParam(required = false) String returnUrl,
            Model model,
            RedirectAttributes redirectAttributes) {

        String resolvedReturnUrl = AdminReturnUrlHelper.resolveProductListReturnUrl(returnUrl);

        if (bindingResult.hasErrors()) {

            Product product = productService.findById(id);

            model.addAttribute("productId", id);

            model.addAttribute("product", product);

            model.addAttribute(
                    "categories",
                    categoryService
                            .findCategoriesForProductEdit(
                                    product.getCategory().getId()));

            model.addAttribute("returnUrl", resolvedReturnUrl);

            return "admin/products/edit";
        }

        try {
            productService.update(id, productForm);
        } catch (InvalidProductSearchKeywordException e) {

            bindingResult.reject(
                    "invalidSearchKeywords",
                    e.getMessage());

            Product product = productService.findById(id);

            model.addAttribute("productId", id);
            model.addAttribute("product", product);

            model.addAttribute(
                    "categories",
                    categoryService.findCategoriesForProductEdit(
                            product.getCategory().getId()));

            model.addAttribute("returnUrl", resolvedReturnUrl);

            return "admin/products/edit";

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

            model.addAttribute("returnUrl", resolvedReturnUrl);

            return "admin/products/edit";
        }

        redirectAttributes.addFlashAttribute(
                "message",
                "商品を更新しました。");

        return "redirect:" + resolvedReturnUrl;

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
            @RequestParam(required = false) String returnUrl,
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

        String resolvedReturnUrl = AdminReturnUrlHelper.resolveProductListReturnUrl(returnUrl);
        model.addAttribute("returnUrl", resolvedReturnUrl);

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
            @RequestParam(required = false) String returnTo,
            @RequestParam(required = false) String returnUrl) {

        String resolvedReturnUrl = AdminReturnUrlHelper.resolveProductListReturnUrl(returnUrl);

        if (bindingResult.hasErrors()) {

            Product product = productService.findById(id);

            model.addAttribute(
                    "product",
                    product);
            model.addAttribute(
                    "returnTo",
                    returnTo);

            model.addAttribute("returnUrl", resolvedReturnUrl);

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

            model.addAttribute("returnUrl", resolvedReturnUrl);

            return "admin/products/stock";
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "在庫を調整しました。");

        String encodedReturnUrl = URLEncoder.encode(
                resolvedReturnUrl,
                StandardCharsets.UTF_8);

        String redirectUrl = "/admin/products/" + id
                + "/stock?returnUrl=" + encodedReturnUrl;

        if ("edit".equals(returnTo)) {
            redirectUrl += "&returnTo=edit";
        }

        return "redirect:" + redirectUrl;
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

    private String buildProductListReturnUrl(ProductSearchForm searchForm, int page) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromPath("/admin/products");

        if (searchForm.getKeyword() != null && !searchForm.getKeyword().isBlank()) {
            builder.queryParam("keyword", searchForm.getKeyword());
        }

        if (searchForm.getCategoryId() != null) {
            builder.queryParam("categoryId", searchForm.getCategoryId());
        }

        if (searchForm.getMinPrice() != null) {
            builder.queryParam("minPrice", searchForm.getMinPrice());
        }

        if (searchForm.getMaxPrice() != null) {
            builder.queryParam("maxPrice", searchForm.getMaxPrice());
        }

        if (searchForm.isInStockOnly()) {
            builder.queryParam("inStockOnly", true);
        }

        if (searchForm.getSort() != null && !searchForm.getSort().isBlank()) {
            builder.queryParam("sort", searchForm.getSort());
        }

        if (page > 0) {
            builder.queryParam("page", page);
        }

        return builder
                .build()
                .encode()
                .toUriString();
    }

}
