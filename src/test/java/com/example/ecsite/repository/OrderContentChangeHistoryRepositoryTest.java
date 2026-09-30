package com.example.ecsite.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderChargeType;
import com.example.ecsite.entity.OrderContentChangeCharge;
import com.example.ecsite.entity.OrderContentChangeHistory;
import com.example.ecsite.entity.OrderContentChangeHistoryActorType;
import com.example.ecsite.entity.OrderContentChangeItem;
import com.example.ecsite.entity.OrderContentChangeSource;
import com.example.ecsite.entity.OrderContentChangeType;
import com.example.ecsite.entity.User;

import jakarta.persistence.EntityManager;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderContentChangeHistoryRepositoryTest {

    @Autowired
    private OrderContentChangeHistoryRepository orderContentChangeHistoryRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void savePersistsHistoryWithItemAndChargeChanges() {

        User user = createUser(
                "order-content-change-history-user");

        Order order = orderRepository.save(
                new Order(
                        user.getId(),
                        5500,
                        LocalDateTime.of(2026, 9, 28, 10, 0),
                        LocalDateTime.of(2026, 9, 28, 14, 0)));

        LocalDateTime changedAt = LocalDateTime.of(2026, 9, 28, 11, 0);

        OrderContentChangeHistory history = new OrderContentChangeHistory(
                order,
                OrderContentChangeSource.CUSTOMER,
                null,
                OrderContentChangeHistoryActorType.USER,
                user.getId(),
                user.getUsername(),
                5500,
                3500,
                0,
                550,
                500,
                368,
                5500,
                4050,
                changedAt);

        history.addItem(
                OrderContentChangeItem.updated(
                        1001L,
                        10L,
                        "商品A",
                        2000,
                        2,
                        1,
                        4000,
                        2000,
                        200L,
                        "STANDARD",
                        "標準税率",
                        new BigDecimal("10.00")));

        history.addCharge(
                OrderContentChangeCharge.updated(
                        3001L,
                        OrderChargeType.SHIPPING,
                        "送料・梱包料",
                        "送料・梱包料",
                        0,
                        550,
                        300L,
                        300L,
                        "STANDARD",
                        "STANDARD",
                        "標準税率",
                        "標準税率",
                        new BigDecimal("10.00"),
                        new BigDecimal("10.00"),
                        10,
                        10));

        OrderContentChangeHistory saved = orderContentChangeHistoryRepository.save(history);

        entityManager.flush();
        entityManager.clear();

        OrderContentChangeHistory loaded = orderContentChangeHistoryRepository
                .findById(saved.getId())
                .orElseThrow();

        assertNotNull(loaded.getId());

        assertEquals(
                order.getId(),
                loaded.getOrder().getId());

        assertEquals(
                OrderContentChangeSource.CUSTOMER,
                loaded.getChangeSource());

        assertEquals(
                OrderContentChangeHistoryActorType.USER,
                loaded.getChangedByType());

        assertEquals(
                user.getId(),
                loaded.getChangedByAccountId());

        assertEquals(
                user.getUsername(),
                loaded.getChangedByUsername());

        assertEquals(
                5500,
                loaded.getOldItemSubtotal());

        assertEquals(
                3500,
                loaded.getNewItemSubtotal());

        assertEquals(
                0,
                loaded.getOldChargeTotal());

        assertEquals(
                550,
                loaded.getNewChargeTotal());

        assertEquals(
                500,
                loaded.getOldTaxAmount());

        assertEquals(
                368,
                loaded.getNewTaxAmount());

        assertEquals(
                5500,
                loaded.getOldTotalAmount());

        assertEquals(
                4050,
                loaded.getNewTotalAmount());

        assertEquals(
                changedAt,
                loaded.getChangedAt());

        assertEquals(
                1,
                loaded.getItems().size());

        OrderContentChangeItem item = loaded.getItems().get(0);

        assertEquals(
                OrderContentChangeType.UPDATED,
                item.getChangeType());

        assertEquals(
                1001L,
                item.getOrderItemId());

        assertEquals(
                10L,
                item.getProductId());

        assertEquals(
                "商品A",
                item.getProductName());

        assertEquals(
                2,
                item.getOldQuantity());

        assertEquals(
                1,
                item.getNewQuantity());

        assertEquals(
                4000,
                item.getOldSubtotal());

        assertEquals(
                2000,
                item.getNewSubtotal());

        assertEquals(
                1,
                loaded.getCharges().size());

        OrderContentChangeCharge charge = loaded.getCharges().get(0);

        assertEquals(
                OrderContentChangeType.UPDATED,
                charge.getChangeType());

        assertEquals(
                OrderChargeType.SHIPPING,
                charge.getChargeType());

        assertEquals(
                3001L,
                charge.getOrderChargeId());

        assertEquals(
                0,
                charge.getOldAmount());

        assertEquals(
                550,
                charge.getNewAmount());

        assertEquals(
                new BigDecimal("10.00"),
                charge.getOldTaxRate());

        assertEquals(
                new BigDecimal("10.00"),
                charge.getNewTaxRate());
    }

    @Test
void savePersistsRemovedItemWithNullNewSnapshots() {

    User user = createUser(
            "order-content-change-removed-item-user");

    Order order = orderRepository.save(
            new Order(
                    user.getId(),
                    5500,
                    LocalDateTime.of(2026, 9, 28, 10, 0),
                    LocalDateTime.of(2026, 9, 28, 14, 0)));

    OrderContentChangeHistory history =
            new OrderContentChangeHistory(
                    order,
                    OrderContentChangeSource.CUSTOMER,
                    null,
                    OrderContentChangeHistoryActorType.USER,
                    user.getId(),
                    user.getUsername(),
                    5500,
                    4000,
                    0,
                    550,
                    500,
                    414,
                    5500,
                    4550,
                    LocalDateTime.of(2026, 9, 28, 11, 0));

    history.addItem(
            OrderContentChangeItem.removed(
                    1002L,
                    20L,
                    "商品B",
                    1500,
                    1,
                    1500,
                    200L,
                    "STANDARD",
                    "標準税率",
                    new BigDecimal("10.00")));

    OrderContentChangeHistory saved =
            orderContentChangeHistoryRepository.save(history);

    entityManager.flush();
    entityManager.clear();

    OrderContentChangeHistory loaded =
            orderContentChangeHistoryRepository
                    .findById(saved.getId())
                    .orElseThrow();

    assertEquals(
            1,
            loaded.getItems().size());

    OrderContentChangeItem item =
            loaded.getItems().get(0);

    assertEquals(
            OrderContentChangeType.REMOVED,
            item.getChangeType());

    assertEquals(
            1002L,
            item.getOrderItemId());

    assertEquals(
            20L,
            item.getProductId());

    assertEquals(
            "商品B",
            item.getProductName());

    assertEquals(
            1500,
            item.getOldPrice());

    assertEquals(
            1,
            item.getOldQuantity());

    assertEquals(
            1500,
            item.getOldSubtotal());

    assertEquals(
            200L,
            item.getOldTaxCategoryId());

    assertEquals(
            "STANDARD",
            item.getOldTaxCategoryCode());

    assertEquals(
            "標準税率",
            item.getOldTaxCategoryName());

    assertEquals(
            new BigDecimal("10.00"),
            item.getOldTaxRate());

    assertNull(item.getNewPrice());
    assertNull(item.getNewQuantity());
    assertNull(item.getNewSubtotal());
    assertNull(item.getNewTaxCategoryId());
    assertNull(item.getNewTaxCategoryCode());
    assertNull(item.getNewTaxCategoryName());
    assertNull(item.getNewTaxRate());
}

@Test
void orderContentRevisionIsPersisted() {

    User user = createUser(
            "order-content-revision-user");

    Order order = orderRepository.save(
            new Order(
                    user.getId(),
                    1000,
                    LocalDateTime.of(2026, 9, 28, 10, 0),
                    LocalDateTime.of(2026, 9, 28, 14, 0)));

    assertEquals(
            0,
            order.getContentRevision());

    order.incrementContentRevision();

    orderRepository.save(order);

    entityManager.flush();
    entityManager.clear();

    Order loaded =
            orderRepository.findById(order.getId())
                    .orElseThrow();

    assertEquals(
            1,
            loaded.getContentRevision());
}

    private User createUser(String username) {

        User user = new User();
        user.setUsername(username);
        user.setPassword("password");
        user.setEnabled(true);

        LocalDateTime now = LocalDateTime.now();

        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        return userRepository.save(user);
    }
}
