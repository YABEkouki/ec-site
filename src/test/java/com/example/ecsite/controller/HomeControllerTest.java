package com.example.ecsite.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import com.example.ecsite.entity.Announcement;
import com.example.ecsite.service.AnnouncementService;

@ExtendWith(MockitoExtension.class)
class HomeControllerTest {

    @Mock
    private AnnouncementService announcementService;

    @Test
    void indexAddsLatestAnnouncementsForAuthenticatedUser() {

        Announcement announcement = new Announcement();
        List<Announcement> announcements =
                List.of(announcement);

        when(announcementService.findLatestPublished(5))
                .thenReturn(announcements);

        HomeController controller =
                new HomeController(announcementService);

        UserDetails userDetails =
                User.withUsername("user1")
                        .password("password")
                        .roles("USER")
                        .build();

        Model model = new ConcurrentModel();

        String view =
                controller.index(userDetails, model);

        assertEquals("index", view);
        assertEquals(
                "user1",
                model.getAttribute("username"));
        assertEquals(
                announcements,
                model.getAttribute("announcements"));

        verify(announcementService)
                .findLatestPublished(5);
    }

}
