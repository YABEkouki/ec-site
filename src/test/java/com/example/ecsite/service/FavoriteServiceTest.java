package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.ecsite.entity.Favorite;
import com.example.ecsite.entity.Product;
import com.example.ecsite.entity.User;
import com.example.ecsite.exception.FavoriteAlreadyExistsException;
import com.example.ecsite.repository.FavoriteRepository;

@ExtendWith(MockitoExtension.class)
class FavoriteServiceTest {

    @Mock
    private FavoriteRepository favoriteRepository;

    @Mock
    private ProductService productService;

    @Mock
    private UserService userService;

    private FavoriteService favoriteService;

    @BeforeEach
    void setUp() {
        favoriteService = new FavoriteService(
                favoriteRepository,
                productService,
                userService);
    }

    @Test
    void addFavoriteRegistersProductForUser() {

        Long productId = 1L;
        Long userId = 10L;

        Product product = new Product();
        User user = new User();

        Favorite savedFavorite = new Favorite();

        when(favoriteRepository.existsByUserIdAndProductId(
                userId,
                productId))
                .thenReturn(false);

        when(productService.findById(productId))
                .thenReturn(product);

        when(userService.findById(userId))
                .thenReturn(user);

        when(favoriteRepository.save(
                org.mockito.ArgumentMatchers.any(Favorite.class)))
                .thenReturn(savedFavorite);

        Favorite result = favoriteService.addFavorite(
                productId,
                userId);

        assertSame(savedFavorite, result);

        verify(productService).findById(productId);
        verify(userService).findById(userId);
    }

    @Test
    void addFavoriteRejectsDuplicateRegistration() {

        Long productId = 1L;
        Long userId = 10L;

        when(favoriteRepository.existsByUserIdAndProductId(
                userId,
                productId))
                .thenReturn(true);

        assertThrows(
                FavoriteAlreadyExistsException.class,
                () -> favoriteService.addFavorite(
                        productId,
                        userId));
    }

    @Test
    void removeFavoriteDeletesExistingFavorite() {

        Long productId = 1L;
        Long userId = 10L;

        Favorite favorite = new Favorite();

        when(favoriteRepository.findByUserIdAndProductId(
                userId,
                productId))
                .thenReturn(Optional.of(favorite));

        favoriteService.removeFavorite(
                productId,
                userId);

        verify(favoriteRepository).delete(favorite);
    }

    @Test
    void isFavoriteReturnsRegistrationStatus() {

        Long productId = 1L;
        Long userId = 10L;

        when(favoriteRepository.existsByUserIdAndProductId(
                userId,
                productId))
                .thenReturn(true);

        assertTrue(
                favoriteService.isFavorite(
                        productId,
                        userId));

        when(favoriteRepository.existsByUserIdAndProductId(
                userId,
                productId))
                .thenReturn(false);

        assertFalse(
                favoriteService.isFavorite(
                        productId,
                        userId));
    }

    @Test
    void findFavoritesByUserIdReturnsActiveProductFavorites() {

        Long userId = 10L;

        Favorite favorite = new Favorite();

        List<Favorite> favorites = List.of(favorite);

        when(favoriteRepository
                .findByUserIdAndProductActiveTrueOrderByCreatedAtDesc(
                        userId))
                .thenReturn(favorites);

        List<Favorite> result =
                favoriteService.findFavoritesByUserId(userId);

        assertSame(favorites, result);
    }
}