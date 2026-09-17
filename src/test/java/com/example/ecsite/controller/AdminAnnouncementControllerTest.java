package com.example.ecsite.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.entity.Announcement;
import com.example.ecsite.entity.AnnouncementImportance;
import com.example.ecsite.entity.AnnouncementType;
import com.example.ecsite.form.AdminAnnouncementForm;
import com.example.ecsite.service.AnnouncementService;

@ExtendWith(MockitoExtension.class)
class AdminAnnouncementControllerTest {

    @Mock
    private AnnouncementService service;

    @Mock
    private Model model;

    @Mock
    private BindingResult bindingResult;

    @Mock
    private RedirectAttributes redirectAttributes;

    private AdminAnnouncementController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminAnnouncementController(service);
    }

    @Test
    void listDisplaysAnnouncementPage() {
        Announcement announcement = new Announcement();

        Page<Announcement> announcementPage =
                new PageImpl<>(List.of(announcement));

        when(service.findAll(0, 20))
                .thenReturn(announcementPage);

        String viewName = controller.list(0, model);

        assertEquals(
                "admin/announcements/list",
                viewName);

        verify(service).findAll(0, 20);
        verify(model).addAttribute(
                "announcementPage",
                announcementPage);
    }

    @Test
    void newFormDisplaysEmptyForm() {
        String viewName = controller.newForm(model);

        assertEquals(
                "admin/announcements/form",
                viewName);

        verify(model).addAttribute(
                org.mockito.ArgumentMatchers.eq(
                        "adminAnnouncementForm"),
                org.mockito.ArgumentMatchers.any(
                        AdminAnnouncementForm.class));
    }

    @Test
    void createSavesAnnouncementAndRedirects() {
        AdminAnnouncementForm form = createForm();

        when(bindingResult.hasErrors())
                .thenReturn(false);

        String viewName = controller.create(
                form,
                bindingResult,
                redirectAttributes);

        assertEquals(
                "redirect:/admin/announcements",
                viewName);

        verify(service).create(form);

        verify(redirectAttributes).addFlashAttribute(
                "successMessage",
                "お知らせを登録しました");
    }

    @Test
    void createReturnsFormWhenBindingHasErrors() {
        AdminAnnouncementForm form = createForm();

        when(bindingResult.hasErrors())
                .thenReturn(true);

        String viewName = controller.create(
                form,
                bindingResult,
                redirectAttributes);

        assertEquals(
                "admin/announcements/form",
                viewName);

        verify(service, never()).create(form);
    }

    @Test
    void createRejectsPublishedWithoutPublishedAt() {
        AdminAnnouncementForm form = createForm();

        form.setPublished(true);
        form.setPublishedAt(null);

        when(bindingResult.hasErrors())
                .thenReturn(true);

        String viewName = controller.create(
                form,
                bindingResult,
                redirectAttributes);

        assertEquals(
                "admin/announcements/form",
                viewName);

        verify(bindingResult).rejectValue(
                "publishedAt",
                "publishedAt.required",
                "公開する場合は公開日時を入力してください");

        verify(service, never()).create(form);
    }

    @Test
    void editFormDisplaysExistingAnnouncement() {
        AdminAnnouncementForm form = createForm();

        when(service.createEditForm(10L))
                .thenReturn(form);

        String viewName = controller.editForm(
                10L,
                model);

        assertEquals(
                "admin/announcements/form",
                viewName);

        verify(model).addAttribute(
                "adminAnnouncementForm",
                form);

        verify(model).addAttribute(
                "announcementId",
                10L);
    }

    @Test
    void updateChangesAnnouncementAndRedirects() {
        AdminAnnouncementForm form = createForm();

        when(bindingResult.hasErrors())
                .thenReturn(false);

        String viewName = controller.update(
                10L,
                form,
                bindingResult,
                model,
                redirectAttributes);

        assertEquals(
                "redirect:/admin/announcements",
                viewName);

        verify(service).update(10L, form);

        verify(redirectAttributes).addFlashAttribute(
                "successMessage",
                "お知らせを更新しました");
    }

    @Test
    void updateReturnsFormAndKeepsIdWhenBindingHasErrors() {
        AdminAnnouncementForm form = createForm();

        when(bindingResult.hasErrors())
                .thenReturn(true);

        String viewName = controller.update(
                10L,
                form,
                bindingResult,
                model,
                redirectAttributes);

        assertEquals(
                "admin/announcements/form",
                viewName);

        verify(model).addAttribute(
                "announcementId",
                10L);

        verify(service, never()).update(10L, form);
    }

    @Test
    void updateRejectsPublishedWithoutPublishedAt() {
        AdminAnnouncementForm form = createForm();

        form.setPublished(true);
        form.setPublishedAt(null);

        when(bindingResult.hasErrors())
                .thenReturn(true);

        String viewName = controller.update(
                10L,
                form,
                bindingResult,
                model,
                redirectAttributes);

        assertEquals(
                "admin/announcements/form",
                viewName);

        verify(bindingResult).rejectValue(
                "publishedAt",
                "publishedAt.required",
                "公開する場合は公開日時を入力してください");

        verify(model).addAttribute(
                "announcementId",
                10L);

        verify(service, never()).update(10L, form);
    }

    @Test
    void enumModelAttributesReturnAllValues() {
        assertEquals(
                AnnouncementType.values().length,
                controller.announcementTypes().length);

        assertEquals(
                AnnouncementImportance.values().length,
                controller.announcementImportances().length);
    }

    private AdminAnnouncementForm createForm() {
        AdminAnnouncementForm form =
                new AdminAnnouncementForm();

        form.setType(AnnouncementType.GENERAL);
        form.setImportance(AnnouncementImportance.NORMAL);
        form.setTitle("テストタイトル");
        form.setContent("テスト本文");
        form.setPublished(false);
        form.setPublishedAt(
                LocalDateTime.of(2026, 9, 20, 10, 0));

        return form;
    }
}
