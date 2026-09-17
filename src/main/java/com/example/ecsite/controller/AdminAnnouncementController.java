package com.example.ecsite.controller;

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

import com.example.ecsite.entity.AnnouncementImportance;
import com.example.ecsite.entity.AnnouncementType;
import com.example.ecsite.form.AdminAnnouncementForm;
import com.example.ecsite.service.AnnouncementService;

import jakarta.validation.Valid;

@Controller
@RequestMapping("/admin/announcements")
public class AdminAnnouncementController {

    private static final int PAGE_SIZE = 20;

    private final AnnouncementService announcementService;

    public AdminAnnouncementController(
            AnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    @ModelAttribute("announcementTypes")
    public AnnouncementType[] announcementTypes() {
        return AnnouncementType.values();
    }

    @ModelAttribute("announcementImportances")
    public AnnouncementImportance[] announcementImportances() {
        return AnnouncementImportance.values();
    }

    @GetMapping
    public String list(
            @RequestParam(defaultValue = "0") int page,
            Model model) {

        model.addAttribute(
                "announcementPage",
                announcementService.findAll(page, PAGE_SIZE));

        return "admin/announcements/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute(
                "adminAnnouncementForm",
                new AdminAnnouncementForm());

        return "admin/announcements/form";
    }

    @PostMapping
    public String create(
            @Valid @ModelAttribute("adminAnnouncementForm")
                    AdminAnnouncementForm form,
            BindingResult bindingResult,
            RedirectAttributes redirectAttributes) {

        validatePublishedAt(form, bindingResult);

        if (bindingResult.hasErrors()) {
            return "admin/announcements/form";
        }

        announcementService.create(form);

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "お知らせを登録しました");

        return "redirect:/admin/announcements";
    }

    @GetMapping("/{id}/edit")
    public String editForm(
            @PathVariable Long id,
            Model model) {

        model.addAttribute(
                "adminAnnouncementForm",
                announcementService.createEditForm(id));
        model.addAttribute("announcementId", id);

        return "admin/announcements/form";
    }

    @PostMapping("/{id}")
    public String update(
            @PathVariable Long id,
            @Valid @ModelAttribute("adminAnnouncementForm")
                    AdminAnnouncementForm form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {

        validatePublishedAt(form, bindingResult);

        if (bindingResult.hasErrors()) {
            model.addAttribute("announcementId", id);
            return "admin/announcements/form";
        }

        announcementService.update(id, form);

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "お知らせを更新しました");

        return "redirect:/admin/announcements";
    }

    private void validatePublishedAt(
            AdminAnnouncementForm form,
            BindingResult bindingResult) {

        if (form.isPublished() && form.getPublishedAt() == null) {
            bindingResult.rejectValue(
                    "publishedAt",
                    "publishedAt.required",
                    "公開する場合は公開日時を入力してください");
        }
    }
}
