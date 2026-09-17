package com.example.ecsite.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import com.example.ecsite.entity.Announcement;
import com.example.ecsite.service.AnnouncementService;

@ExtendWith(MockitoExtension.class)
class AnnouncementControllerTest {

    @Mock
    private AnnouncementService announcementService;

    @Test
    void listAddsPublishedAnnouncementsToModel() {

        Announcement announcement = new Announcement();

        Page<Announcement> announcementPage =
                new PageImpl<>(List.of(announcement));

        when(announcementService.findPublished(2, 10))
                .thenReturn(announcementPage);

        AnnouncementController controller =
                new AnnouncementController(
                        announcementService);

        Model model = new ConcurrentModel();

        String view = controller.list(2, model);

        assertEquals("announcements/list", view);

        assertEquals(
                announcementPage.getContent(),
                model.getAttribute("announcements"));

        assertSame(
                announcementPage,
                model.getAttribute("announcementPage"));

        verify(announcementService)
                .findPublished(2, 10);
    }

    @Test
    void detailAddsPublishedAnnouncementToModel() {

        Announcement announcement = new Announcement();

        when(announcementService.findPublishedById(10L))
                .thenReturn(announcement);

        AnnouncementController controller =
                new AnnouncementController(
                        announcementService);

        Model model = new ConcurrentModel();

        String view = controller.detail(10L, model);

        assertEquals("announcements/detail", view);

        assertSame(
                announcement,
                model.getAttribute("announcement"));

        verify(announcementService)
                .findPublishedById(10L);
    }
}
