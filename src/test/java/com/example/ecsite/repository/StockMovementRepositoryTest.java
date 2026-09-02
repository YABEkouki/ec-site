package com.example.ecsite.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import com.example.ecsite.entity.Category;
import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.Product;
import com.example.ecsite.entity.StockMovement;
import com.example.ecsite.entity.StockMovementType;
import com.example.ecsite.entity.User;

import jakarta.persistence.EntityManager;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class StockMovementRepositoryTest {

    private static final LocalDateTime SEARCH_FROM = LocalDateTime.of(1970, 1, 1, 0, 0);

    private static final LocalDateTime SEARCH_TO = LocalDateTime.of(9999, 12, 31, 0, 0);

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void findByProductIdReturnsMovementsNewestFirst() {

        Product product = createProduct("stock-movement-test-product");
        User user = createTestUser();

        int stockBefore = product.getStock();

        StockMovement first = StockMovement.createAdminAdjustment(
                product,
                stockBefore,
                stockBefore + 5,
                5,
                user.getId(),
                user.getUsername(),
                "1回目");

        stockMovementRepository.save(first);
        entityManager.flush();

        StockMovement second = StockMovement.createAdminAdjustment(
                product,
                stockBefore + 5,
                stockBefore + 8,
                3,
                user.getId(),
                user.getUsername(),
                "2回目");

        stockMovementRepository.save(second);
        entityManager.flush();

        Page<StockMovement> result = stockMovementRepository
                .findByProductIdOrderByChangedAtDescIdDesc(
                        product.getId(),
                        PageRequest.of(0, 20));

        assertEquals(2, result.getTotalElements());

        List<StockMovement> movements = result.getContent();

        assertEquals("2回目", movements.get(0).getReason());
        assertEquals("1回目", movements.get(1).getReason());
    }

    @Test
    void searchFiltersByChangedByUsername() {

        Product product = createProduct("stock-movement-test-product");
        User user = createTestUser();
        int stockBefore = product.getStock();

        stockMovementRepository.save(
                StockMovement.createAdminAdjustment(
                        product,
                        stockBefore,
                        stockBefore + 5,
                        5,
                        user.getId(),
                        user.getUsername(),
                        "検索テスト"));

        entityManager.flush();

        Page<StockMovement> result = stockMovementRepository.searchByProduct(
                product.getId(),
                SEARCH_FROM,
                SEARCH_TO,
                user.getUsername(),
                null,
                PageRequest.of(0, 20));

        assertEquals(1, result.getTotalElements());
        assertEquals(
                user.getUsername(),
                result.getContent()
                        .get(0)
                        .getChangedByUsername());

        Page<StockMovement> notFound = stockMovementRepository.searchByProduct(
                product.getId(),
                SEARCH_FROM,
                SEARCH_TO,
                "存在しないユーザー名",
                null,
                PageRequest.of(0, 20));

        assertEquals(0, notFound.getTotalElements());
    }

    @Test
    void searchWithNoConditionsReturnsProductMovements() {

        Product product = createProduct("stock-movement-test-product");
        User user = createTestUser();
        int stockBefore = product.getStock();

        stockMovementRepository.save(
                StockMovement.createAdminAdjustment(
                        product,
                        stockBefore,
                        stockBefore + 2,
                        2,
                        user.getId(),
                        user.getUsername(),
                        "条件なし検索"));

        entityManager.flush();

        Page<StockMovement> result = stockMovementRepository.searchByProduct(
                product.getId(),
                SEARCH_FROM,
                SEARCH_TO,
                "",
                null,
                PageRequest.of(0, 20));

        assertEquals(1, result.getTotalElements());
    }

    @Test
    void searchByProductFiltersByMovementType() {

        Product product = createProduct("stock-movement-test-product");
        User user = createTestUser();
        int stockBefore = product.getStock();

        Order order = orderRepository.save(
                new Order(user.getId(), 1000));

        stockMovementRepository.save(
                StockMovement.createAdminAdjustment(
                        product,
                        stockBefore,
                        stockBefore + 5,
                        5,
                        user.getId(),
                        user.getUsername(),
                        "管理者調整"));

        stockMovementRepository.save(
                StockMovement.createOrderPlacement(
                        product,
                        stockBefore + 5,
                        stockBefore + 3,
                        -2,
                        order.getId()));

        entityManager.flush();

        Page<StockMovement> result = stockMovementRepository.searchByProduct(
                product.getId(),
                SEARCH_FROM,
                SEARCH_TO,
                "",
                StockMovementType.ORDER_PLACEMENT,
                PageRequest.of(0, 20));

        assertEquals(1, result.getTotalElements());

        assertEquals(
                StockMovementType.ORDER_PLACEMENT,
                result.getContent()
                        .get(0)
                        .getMovementType());

        assertEquals(
                order.getId(),
                result.getContent()
                        .get(0)
                        .getOrderId());
    }

    private User createUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("password");
        user.setRole("ROLE_ADMIN");
        user.setEnabled(true);

        return userRepository.save(user);
    }

    private Product createProduct(String name) {
        Category category = categoryRepository.save(
                new Category(name + "-category"));

        Product product = new Product();
        product.setName(name);
        product.setPrice(1000);
        product.setStock(10);
        product.setDescription("Repository test product");
        product.setCategory(category);
        product.setActive(true);

        return productRepository.save(product);
    }

    private User createTestUser() {
        return createUser("stock-movement-test-user");
    }
}
