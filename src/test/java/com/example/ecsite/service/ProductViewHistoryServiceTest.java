package com.example.ecsite.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.ecsite.entity.Product;
import com.example.ecsite.entity.ProductViewHistory;
import com.example.ecsite.entity.User;
import com.example.ecsite.repository.ProductViewHistoryRepository;
import com.example.ecsite.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class ProductViewHistoryServiceTest {

    @Mock
    private ProductViewHistoryRepository productViewHistoryRepository;

    @Mock
    private UserRepository userRepository;

    private ProductViewHistoryService productViewHistoryService;

    @BeforeEach
    void setUp() {
        productViewHistoryService = new ProductViewHistoryService(
                productViewHistoryRepository,
                userRepository);
    }

    @Test
    void recordViewCreatesNewHistoryWhenHistoryDoesNotExist() {

        Long userId = 1L;

        User user = new User();
        Product product = createProduct(10L);

        when(productViewHistoryRepository
                .findByUserIdAndProductId(
                        userId,
                        product.getId()))
                .thenReturn(Optional.empty());

        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user));

        when(productViewHistoryRepository.countByUserId(userId))
                .thenReturn(1L);

        productViewHistoryService.recordView(userId, product);

        ArgumentCaptor<ProductViewHistory> captor = ArgumentCaptor.forClass(ProductViewHistory.class);

        verify(productViewHistoryRepository).save(captor.capture());

        ProductViewHistory saved = captor.getValue();

        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.getProduct()).isSameAs(product);
        assertThat(saved.getLastViewedAt()).isNotNull();
    }

    @Test
    void recordViewUpdatesExistingHistoryWithoutCreatingNewHistory() {

        Long userId = 1L;

        User user = new User();
        Product product = createProduct(10L);

        LocalDateTime oldViewedAt = LocalDateTime.now().minusDays(1);

        ProductViewHistory history = new ProductViewHistory(
                user,
                product,
                oldViewedAt);

        when(productViewHistoryRepository
                .findByUserIdAndProductId(
                        userId,
                        product.getId()))
                .thenReturn(Optional.of(history));

        when(productViewHistoryRepository.countByUserId(userId))
                .thenReturn(1L);

        productViewHistoryService.recordView(userId, product);

        assertThat(history.getLastViewedAt())
                .isAfter(oldViewedAt);

        verify(productViewHistoryRepository, never())
                .save(any(ProductViewHistory.class));

        verify(userRepository, never())
                .findById(any());
    }

    @Test
    void recordViewDoesNotDeleteHistoryWhenCountIsTwenty() {

        Long userId = 1L;

        User user = new User();
        Product product = createProduct(10L);

        ProductViewHistory history = new ProductViewHistory(
                user,
                product,
                LocalDateTime.now().minusDays(1));

        when(productViewHistoryRepository
                .findByUserIdAndProductId(
                        userId,
                        product.getId()))
                .thenReturn(Optional.of(history));

        when(productViewHistoryRepository.countByUserId(userId))
                .thenReturn(20L);

        productViewHistoryService.recordView(userId, product);

        verify(productViewHistoryRepository, never())
                .findByUserIdOrderByLastViewedAtAsc(userId);

        verify(productViewHistoryRepository, never())
                .deleteAll(any());
    }

    @Test
    void recordViewDeletesOldestHistoryWhenCountExceedsTwenty() {

        Long userId = 1L;

        User user = new User();
        Product newProduct = createProduct(100L);

        when(productViewHistoryRepository
                .findByUserIdAndProductId(
                        userId,
                        newProduct.getId()))
                .thenReturn(Optional.empty());

        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user));

        when(productViewHistoryRepository.countByUserId(userId))
                .thenReturn(21L);

        List<ProductViewHistory> histories = new ArrayList<>();

        ProductViewHistory oldest = null;

        for (int i = 0; i < 21; i++) {

            Product product = createProduct((long) i + 1);

            ProductViewHistory history = new ProductViewHistory(
                    user,
                    product,
                    LocalDateTime.of(
                            2026,
                            9,
                            1,
                            0,
                            0)
                            .plusMinutes(i));

            histories.add(history);

            if (i == 0) {
                oldest = history;
            }
        }

        when(productViewHistoryRepository
                .findByUserIdOrderByLastViewedAtAsc(userId))
                .thenReturn(histories);

        productViewHistoryService.recordView(
                userId,
                newProduct);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<ProductViewHistory>> captor = ArgumentCaptor.forClass(Iterable.class);

        verify(productViewHistoryRepository)
                .deleteAll(captor.capture());

        assertThat(captor.getValue())
                .containsExactly(oldest);
    }

    @Test
    void findRecentAvailableProductsReturnsRepositoryResult() {

        Long userId = 1L;

        Product product1 = createProduct(10L);
        Product product2 = createProduct(20L);

        when(productViewHistoryRepository
                .findRecentAvailableProducts(
                        userId,
                        org.springframework.data.domain.PageRequest.of(
                                0,
                                5)))
                .thenReturn(List.of(product1, product2));

        List<Product> result = productViewHistoryService
                .findRecentAvailableProducts(
                        userId,
                        5);

        assertThat(result)
                .containsExactly(product1, product2);
    }

    @Test
    void findRecentAvailableProductsReturnsEmptyWhenLimitIsZero() {

        List<Product> result = productViewHistoryService
                .findRecentAvailableProducts(
                        1L,
                        0);

        assertThat(result).isEmpty();

        verify(productViewHistoryRepository, never())
                .findRecentAvailableProducts(
                        any(),
                        any());
    }

    private Product createProduct(Long id) {

        Product product = new Product();
        product.setId(id);

        return product;
    }

}
