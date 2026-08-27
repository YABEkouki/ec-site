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

import com.example.ecsite.entity.Product;
import com.example.ecsite.entity.StockMovement;

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
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void findByProductIdReturnsMovementsNewestFirst() {

        Product product = productRepository.findAll()
                .stream()
                .findFirst()
                .orElseThrow();

        var user = userRepository.findAll()
                .stream()
                .findFirst()
                .orElseThrow();

        int stockBefore = product.getStock();

        StockMovement first = StockMovement.createAdminAdjustment(
                product,
                stockBefore,
                stockBefore + 5,
                5,
                user,
                "1回目");

        stockMovementRepository.save(first);
        entityManager.flush();

        StockMovement second = StockMovement.createAdminAdjustment(
                product,
                stockBefore + 5,
                stockBefore + 8,
                3,
                user,
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

        Product product = productRepository.findAll()
                .stream()
                .findFirst()
                .orElseThrow();

        var user = userRepository.findAll()
                .stream()
                .findFirst()
                .orElseThrow();

        int stockBefore = product.getStock();

        stockMovementRepository.save(
                StockMovement.createAdminAdjustment(
                        product,
                        stockBefore,
                        stockBefore + 5,
                        5,
                        user,
                        "検索テスト"));

        entityManager.flush();

        Page<StockMovement> result = stockMovementRepository.searchByProduct(
                product.getId(),
                SEARCH_FROM,
                SEARCH_TO,
                user.getUsername(),
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
                PageRequest.of(0, 20));

        assertEquals(0, notFound.getTotalElements());
    }

    @Test
    void searchWithNoConditionsReturnsProductMovements() {

        Product product = productRepository.findAll()
                .stream()
                .findFirst()
                .orElseThrow();

        var user = userRepository.findAll()
                .stream()
                .findFirst()
                .orElseThrow();

        int stockBefore = product.getStock();

        stockMovementRepository.save(
                StockMovement.createAdminAdjustment(
                        product,
                        stockBefore,
                        stockBefore + 2,
                        2,
                        user,
                        "条件なし検索"));

        entityManager.flush();

        Page<StockMovement> result = stockMovementRepository.searchByProduct(
                product.getId(),
                SEARCH_FROM,
                SEARCH_TO,
                "",
                PageRequest.of(0, 20));

        assertEquals(1, result.getTotalElements());
    }
}