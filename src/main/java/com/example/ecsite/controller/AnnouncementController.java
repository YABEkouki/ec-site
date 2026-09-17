package com.example.ecsite.controller;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.example.ecsite.entity.Announcement;
import com.example.ecsite.service.AnnouncementService;

@Controller
public class AnnouncementController {

    private final AnnouncementService announcementService;

    public AnnouncementController(
            AnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    @GetMapping("/announcements")
    public String list(
            @RequestParam(name = "page", defaultValue = "0") int page,
            Model model) {

        int size = 10;

        Page<Announcement> announcementPage =
                announcementService.findPublished(page, size);

        model.addAttribute(
                "announcements",
                announcementPage.getContent());
        model.addAttribute(
                "announcementPage",
                announcementPage);

        return "announcements/list";
    }

    @GetMapping("/announcements/{id}")
    public String detail(
            @PathVariable Long id,
            Model model) {

        Announcement announcement =
                announcementService.findPublishedById(id);

        model.addAttribute("announcement", announcement);

        return "announcements/detail";
    }
}
