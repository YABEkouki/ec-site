package com.example.ecsite.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.Product;
import com.example.ecsite.entity.ProductViewHistory;
import com.example.ecsite.entity.User;
import com.example.ecsite.repository.ProductViewHistoryRepository;
import com.example.ecsite.repository.UserRepository;

@Service
public class ProductViewHistoryService {

    private static final int MAX_HISTORY_COUNT = 20;

    private final ProductViewHistoryRepository productViewHistoryRepository;
    private final UserRepository userRepository;

    public ProductViewHistoryService(
            ProductViewHistoryRepository productViewHistoryRepository,
            UserRepository userRepository) {
        this.productViewHistoryRepository = productViewHistoryRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public void recordView(Long userId, Product product) {

        LocalDateTime now = LocalDateTime.now();

        productViewHistoryRepository
                .findByUserIdAndProductId(
                        userId,
                        product.getId())
                .ifPresentOrElse(
                        history -> history.updateLastViewedAt(now),
                        () -> createHistory(
                                userId,
                                product,
                                now));

        trimOldHistories(userId);
    }

    @Transactional(readOnly = true)
    public List<Product> findRecentAvailableProducts(
            Long userId,
            int limit) {

        if (limit <= 0) {
            return List.of();
        }

        return productViewHistoryRepository
                .findRecentAvailableProducts(
                        userId,
                        PageRequest.of(0, limit));
    }

    private void createHistory(
            Long userId,
            Product product,
            LocalDateTime viewedAt) {

        User user = userRepository
                .findById(userId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "User not found: " + userId));

        productViewHistoryRepository.save(
                new ProductViewHistory(
                        user,
                        product,
                        viewedAt));
    }

    private void trimOldHistories(Long userId) {

        long count =
                productViewHistoryRepository.countByUserId(userId);

        if (count <= MAX_HISTORY_COUNT) {
            return;
        }

        List<ProductViewHistory> histories =
                productViewHistoryRepository
                        .findByUserIdOrderByLastViewedAtAsc(userId);

        int deleteCount =
                histories.size() - MAX_HISTORY_COUNT;

        productViewHistoryRepository.deleteAll(
                histories.subList(0, deleteCount));
    }
}
