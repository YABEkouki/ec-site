package com.example.ecsite.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.entity.Favorite;
import com.example.ecsite.exception.FavoriteAlreadyExistsException;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.FavoriteService;

@ExtendWith(MockitoExtension.class)
class FavoriteControllerTest {

    @Mock
    private FavoriteService favoriteService;

    @Mock
    private Model model;

    private FavoriteController favoriteController;

    @BeforeEach
    void setUp() {
        favoriteController = new FavoriteController(favoriteService);
    }

    @Test
    void listDisplaysFavoritesForLoggedInUser() {

        CustomUserDetails userDetails = mock(CustomUserDetails.class);

        when(userDetails.getId())
                .thenReturn(10L);

        Favorite favorite = new Favorite();

        List<Favorite> favorites = List.of(favorite);

        when(favoriteService.findFavoritesByUserId(10L))
                .thenReturn(favorites);

        String viewName = favoriteController.list(
                userDetails,
                model);

        assertEquals("favorites/list", viewName);

        verify(favoriteService)
                .findFavoritesByUserId(10L);

        verify(model)
                .addAttribute("favorites", favorites);
    }

    @Test
    void addRegistersFavoriteAndRedirectsToProductDetail() {

        CustomUserDetails userDetails = mock(CustomUserDetails.class);
        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        when(userDetails.getId())
                .thenReturn(10L);

        String viewName = favoriteController.add(
                1L,
                userDetails,
                redirectAttributes);

        assertEquals(
                "redirect:/products/1",
                viewName);

        verify(favoriteService)
                .addFavorite(1L, 10L);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "successMessage",
                        "お気に入りに登録しました。");
    }

    @Test
    void removeDeletesFavoriteAndRedirectsToProductDetail() {

        CustomUserDetails userDetails = mock(CustomUserDetails.class);
        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        when(userDetails.getId())
                .thenReturn(10L);

        String viewName = favoriteController.remove(
                1L,
                userDetails,
                redirectAttributes);

        assertEquals(
                "redirect:/products/1",
                viewName);

        verify(favoriteService)
                .removeFavorite(1L, 10L);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "successMessage",
                        "お気に入りから解除しました。");
    }

    @Test
    void addDisplaysErrorWhenFavoriteAlreadyExists() {

        CustomUserDetails userDetails = mock(CustomUserDetails.class);
        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        when(userDetails.getId())
                .thenReturn(10L);

        FavoriteAlreadyExistsException exception = new FavoriteAlreadyExistsException();

        doThrow(exception)
                .when(favoriteService)
                .addFavorite(1L, 10L);

        String viewName = favoriteController.add(
                1L,
                userDetails,
                redirectAttributes);

        assertEquals(
                "redirect:/products/1",
                viewName);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "errorMessage",
                        exception.getMessage());
    }
}