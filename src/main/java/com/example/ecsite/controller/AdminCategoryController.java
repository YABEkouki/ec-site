package com.example.ecsite.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.entity.Category;
import com.example.ecsite.exception.CategoryAlreadyExistsException;
import com.example.ecsite.exception.ProtectedCategoryException;
import com.example.ecsite.form.CategoryForm;
import com.example.ecsite.service.CategoryService;

import jakarta.validation.Valid;

@Controller
@RequestMapping("/admin/categories")
public class AdminCategoryController {

        private final CategoryService categoryService;

        public AdminCategoryController(
                        CategoryService categoryService) {

                this.categoryService = categoryService;
        }

        @GetMapping
        public String list(Model model) {

                if (!model.containsAttribute(
                                "categoryForm")) {

                        model.addAttribute(
                                        "categoryForm",
                                        new CategoryForm());
                }

                addCategories(model);

                return "admin/categories/list";
        }

        @GetMapping("/{id}/edit")
        public String edit(
                        @PathVariable Long id,
                        Model model) {

                Category category = categoryService.findById(id);

                CategoryForm categoryForm = new CategoryForm();

                categoryForm.setName(category.getName());

                categoryForm.setDisplayOrder(category.getDisplayOrder());

                model.addAttribute(
                                "category",
                                category);

                model.addAttribute(
                                "categoryForm",
                                categoryForm);

                return "admin/categories/edit";
        }

        @PostMapping("/{id}/update")
        public String update(
                        @PathVariable Long id,
                        @Valid @ModelAttribute("categoryForm") CategoryForm categoryForm,
                        BindingResult bindingResult,
                        Model model,
                        RedirectAttributes redirectAttributes) {

                if (bindingResult.hasErrors()) {

                        model.addAttribute(
                                        "category",
                                        categoryService.findById(id));

                        return "admin/categories/edit";
                }

                try {
                        categoryService.update(
                                        id,
                                        categoryForm);

                } catch (CategoryAlreadyExistsException e) {

                        bindingResult.rejectValue(
                                        "name",
                                        "category.duplicate",
                                        "このカテゴリ名は既に使用されています。");

                        model.addAttribute(
                                        "category",
                                        categoryService.findById(id));

                        return "admin/categories/edit";
                } catch (ProtectedCategoryException e) {

                        bindingResult.rejectValue(
                                        "name",
                                        "category.protected",
                                        "「未分類」カテゴリの名前は変更できません。");

                        model.addAttribute(
                                        "category",
                                        categoryService.findById(id));

                        return "admin/categories/edit";
                }

                redirectAttributes.addFlashAttribute("successMessage", "カテゴリを更新しました。");

                return "redirect:/admin/categories";

        }

        @PostMapping
        public String create(
                        @Valid @ModelAttribute("categoryForm") CategoryForm categoryForm,
                        BindingResult bindingResult,
                        Model model,
                        RedirectAttributes redirectAttributes) {

                if (bindingResult.hasErrors()) {
                        addCategories(model);

                        return "admin/categories/list";
                }

                try {
                        categoryService.create(categoryForm);

                } catch (CategoryAlreadyExistsException e) {

                        bindingResult.rejectValue(
                                        "name",
                                        "category.duplicate",
                                        "このカテゴリ名は既に使用されています。");

                        addCategories(model);

                        return "admin/categories/list";
                }

                redirectAttributes.addFlashAttribute(
                                "successMessage",
                                "カテゴリを登録しました。");

                return "redirect:/admin/categories";
        }

        private void addCategories(Model model) {

                model.addAttribute(
                                "categories",
                                categoryService.findAllCategories());
        }

        @PostMapping("/{id}/deactivate")
        public String deactivate(
                        @PathVariable Long id,
                        RedirectAttributes redirectAttributes) {

                try {
                        categoryService.deactivate(id);

                } catch (ProtectedCategoryException e) {

                        redirectAttributes.addFlashAttribute(
                                        "errorMessage",
                                        "「未分類」カテゴリは無効化できません。");

                        return "redirect:/admin/categories";
                }

                redirectAttributes.addFlashAttribute(
                                "successMessage",
                                "カテゴリを無効化しました。");

                return "redirect:/admin/categories";
        }

        @PostMapping("/{id}/activate")
        public String activate(
                        @PathVariable Long id,
                        RedirectAttributes redirectAttributes) {

                categoryService.activate(id);

                redirectAttributes.addFlashAttribute(
                                "successMessage",
                                "カテゴリを有効化しました。");

                return "redirect:/admin/categories";
        }
}