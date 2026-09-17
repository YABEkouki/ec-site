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
import com.example.ecsite.entity.Product;
import com.example.ecsite.service.AnnouncementService;
import com.example.ecsite.service.ProductService;

@ExtendWith(MockitoExtension.class)
class HomeControllerTest {

    @Mock
    private AnnouncementService announcementService;

    @Mock
    private ProductService productService;

    @Test
    void indexAddsLatestAnnouncementsForAuthenticatedUser() {

        Announcement announcement = new Announcement();
        List<Announcement> announcements = List.of(announcement);

        Product product = new Product();
        List<Product> latestProducts = List.of(product);

        when(announcementService.findLatestPublished(5))
                .thenReturn(announcements);

        when(productService.findLatestAvailableProducts(5))
                .thenReturn(latestProducts);

        HomeController controller = new HomeController(
                announcementService,
                productService);

        UserDetails userDetails = User.withUsername("user1")
                .password("password")
                .roles("USER")
                .build();

        Model model = new ConcurrentModel();

        String view = controller.index(userDetails, model);

        assertEquals("index", view);

        assertEquals(
                "user1",
                model.getAttribute("username"));

        assertEquals(
                announcements,
                model.getAttribute("announcements"));

        assertEquals(
                latestProducts,
                model.getAttribute("latestProducts"));

        verify(announcementService)
                .findLatestPublished(5);

        verify(productService)
                .findLatestAvailableProducts(5);
    }

}
