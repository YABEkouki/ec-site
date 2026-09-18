package com.example.ecsite.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;

import com.example.ecsite.entity.Category;
import com.example.ecsite.entity.Product;
import com.example.ecsite.entity.ProductViewHistory;
import com.example.ecsite.entity.User;

import jakarta.persistence.EntityManager;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ProductViewHistoryRepositoryTest {

    @Autowired
    private ProductViewHistoryRepository productViewHistoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void findByUserIdAndProductIdReturnsTargetHistory() {

        User user = createUser("view-history-find-user");
        Category category = createCategory("閲覧履歴検索カテゴリ");
        Product product = createProduct(
                "閲覧履歴検索商品",
                1000,
                10,
                true,
                category);

        LocalDateTime viewedAt =
                LocalDateTime.of(2026, 9, 18, 10, 0);

        createHistory(user, product, viewedAt);

        entityManager.flush();
        entityManager.clear();

        ProductViewHistory result = productViewHistoryRepository
                .findByUserIdAndProductId(
                        user.getId(),
                        product.getId())
                .orElseThrow();

        assertThat(result.getUser().getId())
                .isEqualTo(user.getId());
        assertThat(result.getProduct().getId())
                .isEqualTo(product.getId());
        assertThat(result.getLastViewedAt())
                .isEqualTo(viewedAt);
    }

    @Test
    void countByUserIdCountsOnlyTargetUser() {

        User targetUser = createUser("view-history-count-target");
        User otherUser = createUser("view-history-count-other");

        Category category = createCategory("閲覧履歴件数カテゴリ");

        Product product1 = createProduct(
                "閲覧履歴件数商品1", 1000, 10, true, category);
        Product product2 = createProduct(
                "閲覧履歴件数商品2", 2000, 10, true, category);
        Product product3 = createProduct(
                "閲覧履歴件数商品3", 3000, 10, true, category);

        createHistory(
                targetUser,
                product1,
                LocalDateTime.of(2026, 9, 18, 10, 0));

        createHistory(
                targetUser,
                product2,
                LocalDateTime.of(2026, 9, 18, 11, 0));

        createHistory(
                otherUser,
                product3,
                LocalDateTime.of(2026, 9, 18, 12, 0));

        assertThat(
                productViewHistoryRepository
                        .countByUserId(targetUser.getId()))
                .isEqualTo(2);
    }

    @Test
    void findByUserIdOrderByLastViewedAtAscReturnsOldestFirst() {

        User user = createUser("view-history-order-user");
        Category category = createCategory("閲覧履歴並び順カテゴリ");

        Product oldest = createProduct(
                "最古商品", 1000, 10, true, category);
        Product middle = createProduct(
                "中間商品", 2000, 10, true, category);
        Product newest = createProduct(
                "最新商品", 3000, 10, true, category);

        createHistory(
                user,
                newest,
                LocalDateTime.of(2026, 9, 18, 12, 0));

        createHistory(
                user,
                oldest,
                LocalDateTime.of(2026, 9, 18, 10, 0));

        createHistory(
                user,
                middle,
                LocalDateTime.of(2026, 9, 18, 11, 0));

        entityManager.flush();
        entityManager.clear();

        List<ProductViewHistory> result =
                productViewHistoryRepository
                        .findByUserIdOrderByLastViewedAtAsc(
                                user.getId());

        assertThat(result)
                .extracting(history -> history.getProduct().getId())
                .containsExactly(
                        oldest.getId(),
                        middle.getId(),
                        newest.getId());
    }

    @Test
    void findRecentAvailableProductsUsesOnlyTargetUsersAvailableProducts() {

        User targetUser =
                createUser("view-history-available-target");
        User otherUser =
                createUser("view-history-available-other");

        Category category =
                createCategory("閲覧履歴販売条件カテゴリ");

        Product olderAvailable = createProduct(
                "古い販売可能商品",
                1000,
                10,
                true,
                category);

        Product newerAvailable = createProduct(
                "新しい販売可能商品",
                2000,
                10,
                true,
                category);

        Product inactive = createProduct(
                "非公開商品",
                3000,
                10,
                false,
                category);

        Product outOfStock = createProduct(
                "在庫なし商品",
                4000,
                0,
                true,
                category);

        Product otherUsersProduct = createProduct(
                "他ユーザー商品",
                5000,
                10,
                true,
                category);

        createHistory(
                targetUser,
                olderAvailable,
                LocalDateTime.of(2026, 9, 18, 10, 0));

        createHistory(
                targetUser,
                inactive,
                LocalDateTime.of(2026, 9, 18, 11, 0));

        createHistory(
                targetUser,
                outOfStock,
                LocalDateTime.of(2026, 9, 18, 12, 0));

        createHistory(
                targetUser,
                newerAvailable,
                LocalDateTime.of(2026, 9, 18, 13, 0));

        createHistory(
                otherUser,
                otherUsersProduct,
                LocalDateTime.of(2026, 9, 18, 14, 0));

        entityManager.flush();
        entityManager.clear();

        List<Product> result =
                productViewHistoryRepository
                        .findRecentAvailableProducts(
                                targetUser.getId(),
                                PageRequest.of(0, 5));

        assertThat(result)
                .extracting(Product::getId)
                .containsExactly(
                        newerAvailable.getId(),
                        olderAvailable.getId());
    }

    @Test
    void findRecentAvailableProductsLimitsResultsByPageable() {

        User user = createUser("view-history-limit-user");
        Category category =
                createCategory("閲覧履歴件数制限カテゴリ");

        for (int i = 1; i <= 6; i++) {

            Product product = createProduct(
                    "閲覧履歴商品" + i,
                    1000 * i,
                    10,
                    true,
                    category);

            createHistory(
                    user,
                    product,
                    LocalDateTime.of(
                            2026,
                            9,
                            18,
                            10,
                            i));
        }

        entityManager.flush();
        entityManager.clear();

        List<Product> result =
                productViewHistoryRepository
                        .findRecentAvailableProducts(
                                user.getId(),
                                PageRequest.of(0, 5));

        assertThat(result).hasSize(5);
    }

    private ProductViewHistory createHistory(
            User user,
            Product product,
            LocalDateTime lastViewedAt) {

        return productViewHistoryRepository.save(
                new ProductViewHistory(
                        user,
                        product,
                        lastViewedAt));
    }

    private Category createCategory(String name) {

        Category category = new Category(name);
        category.setActive(true);

        return categoryRepository.save(category);
    }

    private Product createProduct(
            String name,
            int price,
            int stock,
            boolean active,
            Category category) {

        Product product = new Product();
        product.setName(name);
        product.setPrice(price);
        product.setStock(stock);
        product.setDescription("商品説明");
        product.setActive(active);
        product.setCategory(category);

        return productRepository.save(product);
    }

    private User createUser(String username) {

        User user = new User();
        user.setUsername(username);
        user.setPassword("password");
        user.setEnabled(true);

        LocalDateTime now = LocalDateTime.now();

        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        User saved = userRepository.save(user);
        entityManager.flush();

        return saved;
    }
}
