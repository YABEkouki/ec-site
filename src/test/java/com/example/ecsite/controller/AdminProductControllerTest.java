package com.example.ecsite.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.entity.Product;
import com.example.ecsite.exception.InvalidStockAdjustmentException;
import com.example.ecsite.form.StockAdjustmentForm;
import com.example.ecsite.service.CategoryService;
import com.example.ecsite.service.ProductService;

@ExtendWith(MockitoExtension.class)
class AdminProductControllerTest {

        @Mock
        private ProductService productService;

        @Mock
        private CategoryService categoryService;

        @Mock
        private Model model;

        private AdminProductController adminProductController;

        @BeforeEach
        void setUp() {

                adminProductController = new AdminProductController(
                                productService,
                                categoryService);
        }

        @Test
        void stockDisplaysStockAdjustmentForm() {

                Long productId = 1L;

                Product product = new Product();
                product.setId(productId);
                product.setName("テスト商品");
                product.setStock(10);

                when(productService.findById(productId))
                                .thenReturn(product);

                String viewName = adminProductController.stock(
                                productId,
                                null,
                                model);

                assertEquals(
                                "admin/products/stock",
                                viewName);

                verify(productService)
                                .findById(productId);

                verify(model)
                                .addAttribute(
                                                "product",
                                                product);

                verify(model)
                                .addAttribute(
                                                org.mockito.ArgumentMatchers.eq("stockAdjustmentForm"),
                                                any(StockAdjustmentForm.class));
        }

        @Test
        void adjustStockUpdatesStockAndRedirectsToStockPage() {

                Long productId = 1L;

                StockAdjustmentForm form = new StockAdjustmentForm();
                form.setQuantity(-3);

                RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

                BindingResult bindingResult = mock(BindingResult.class);

                when(bindingResult.hasErrors())
                                .thenReturn(false);

                String viewName = adminProductController.adjustStock(
                                productId,
                                form,
                                bindingResult,
                                model,
                                redirectAttributes,
                                null);

                assertEquals(
                                "redirect:/admin/products/" + productId + "/stock",
                                viewName);

                verify(productService)
                                .adjustStock(
                                                productId,
                                                -3);

                verify(redirectAttributes)
                                .addFlashAttribute(
                                                "successMessage",
                                                "在庫を調整しました。");
        }

        @Test
        void adjustStockReturnsStockPageWhenValidationFails() {

                Long productId = 1L;

                Product product = new Product();
                product.setId(productId);
                product.setName("テスト商品");
                product.setStock(10);

                StockAdjustmentForm form = new StockAdjustmentForm();

                BindingResult bindingResult = mock(BindingResult.class);

                when(bindingResult.hasErrors())
                                .thenReturn(true);

                when(productService.findById(productId))
                                .thenReturn(product);

                String viewName = adminProductController.adjustStock(
                                productId,
                                form,
                                bindingResult,
                                model,
                                mock(RedirectAttributes.class),
                                null);

                assertEquals(
                                "admin/products/stock",
                                viewName);

                verify(productService, never())
                                .adjustStock(
                                                any(),
                                                anyInt());

                verify(productService)
                                .findById(productId);

                verify(model)
                                .addAttribute(
                                                "product",
                                                product);
        }

        @Test
        void adjustStockDisplaysErrorWhenAdjustmentIsInvalid() {

                Long productId = 1L;

                Product product = new Product();
                product.setId(productId);
                product.setName("テスト商品");
                product.setStock(2);

                StockAdjustmentForm form = new StockAdjustmentForm();
                form.setQuantity(-3);

                BindingResult bindingResult = mock(BindingResult.class);

                when(bindingResult.hasErrors())
                                .thenReturn(false);

                when(productService.findById(productId))
                                .thenReturn(product);

                doThrow(new InvalidStockAdjustmentException(
                                "在庫数を0未満にはできません。"))
                                .when(productService)
                                .adjustStock(
                                                productId,
                                                -3);

                String viewName = adminProductController.adjustStock(
                                productId,
                                form,
                                bindingResult,
                                model,
                                mock(RedirectAttributes.class),
                                null);

                assertEquals(
                                "admin/products/stock",
                                viewName);

                verify(productService)
                                .adjustStock(
                                                productId,
                                                -3);

                verify(productService)
                                .findById(productId);

                verify(model)
                                .addAttribute(
                                                "product",
                                                product);

                verify(model)
                                .addAttribute(
                                                "errorMessage",
                                                "在庫数を0未満にはできません。");
        }

        @Test
        void adjustStockReturnsToStockPageAndKeepsEditReturnDestination() {

                Long productId = 1L;

                StockAdjustmentForm form = new StockAdjustmentForm();
                form.setQuantity(5);

                BindingResult bindingResult = mock(BindingResult.class);
                RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

                when(bindingResult.hasErrors())
                                .thenReturn(false);

                String viewName = adminProductController.adjustStock(
                                productId,
                                form,
                                bindingResult,
                                model,
                                redirectAttributes,
                                "edit");

                assertEquals(
                                "redirect:/admin/products/" + productId + "/stock?returnTo=edit",
                                viewName);

                verify(productService)
                                .adjustStock(
                                                productId,
                                                5);

                verify(redirectAttributes)
                                .addFlashAttribute(
                                                "successMessage",
                                                "在庫を調整しました。");
        }
}