package com.example.ecsite.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
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
import com.example.ecsite.entity.OrderHandlingStatus;
import com.example.ecsite.entity.OrderItem;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.entity.Product;
import com.example.ecsite.entity.User;
import com.example.ecsite.repository.projection.CategorySalesRankingProjection;
import com.example.ecsite.repository.projection.CustomerSalesRankingProjection;
import com.example.ecsite.repository.projection.DailySalesProjection;
import com.example.ecsite.repository.projection.ProductSalesRankingProjection;

import jakarta.persistence.EntityManager;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderRepositoryTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private UserRepository userRepository;

    private static final LocalDateTime SEARCH_FROM = LocalDateTime.of(1970, 1, 1, 0, 0);

    private static final LocalDateTime SEARCH_TO = LocalDateTime.of(9999, 12, 31, 0, 0);

    private static final List<OrderHandlingStatus> ALL_HANDLING_STATUSES = List.of(OrderHandlingStatus.values());

    @Test
    void searchFiltersByUserId() {

        User firstUser = createUser("order-search-user-1");
        User secondUser = createUser("order-search-user-2");

        createOrder(
                firstUser.getId(),
                LocalDateTime.of(2026, 8, 10, 10, 0));

        Order target = createOrder(
                secondUser.getId(),
                LocalDateTime.of(2026, 8, 11, 10, 0));

        Page<Order> result = orderRepository.search(
                null,
                secondUser.getId(),
                SEARCH_FROM,
                SEARCH_TO,
                null,
                ALL_HANDLING_STATUSES,
                PageRequest.of(0, 20));

        assertEquals(1, result.getTotalElements());
        assertEquals(target.getId(), result.getContent().get(0).getId());
    }

    @Test
    void searchFiltersByOrderId() {

        User user = createUser("order-search-user-3");

        Order target = createOrder(
                user.getId(),
                LocalDateTime.of(2026, 8, 11, 10, 0));

        Page<Order> result = orderRepository.search(
                target.getId(),
                null,
                SEARCH_FROM,
                SEARCH_TO,
                null,
                ALL_HANDLING_STATUSES,
                PageRequest.of(0, 20));

        assertEquals(1, result.getTotalElements());
        assertEquals(target.getId(), result.getContent().get(0).getId());
    }

    @Test
    void searchFiltersByOrderedAtRange() {

        User user = createUser("order-search-range-user");

        createOrder(
                user.getId(),
                LocalDateTime.of(2026, 8, 9, 23, 59));

        Order firstInRange = createOrder(
                user.getId(),
                LocalDateTime.of(2026, 8, 10, 0, 0));

        Order lastInRange = createOrder(
                user.getId(),
                LocalDateTime.of(2026, 8, 20, 23, 59));

        createOrder(
                user.getId(),
                LocalDateTime.of(2026, 8, 21, 0, 0));

        Page<Order> result = orderRepository.search(
                null,
                user.getId(),
                LocalDateTime.of(2026, 8, 10, 0, 0),
                LocalDateTime.of(2026, 8, 21, 0, 0),
                null,
                ALL_HANDLING_STATUSES,
                PageRequest.of(0, 20));

        assertEquals(2, result.getTotalElements());
        assertEquals(lastInRange.getId(), result.getContent().get(0).getId());
        assertEquals(firstInRange.getId(), result.getContent().get(1).getId());
    }

    @Test
    void searchFiltersByStatus() {

        User user = createUser("order-search-user-5");

        Order paidOrder = createOrder(
                user.getId(),
                LocalDateTime.of(2026, 8, 11, 10, 0));

        paidOrder.markAsPaid();
        entityManager.flush();

        Page<Order> result = orderRepository.search(
                null,
                user.getId(),
                SEARCH_FROM,
                SEARCH_TO,
                OrderStatus.PAID,
                ALL_HANDLING_STATUSES,
                PageRequest.of(0, 20));

        assertEquals(1, result.getTotalElements());
        assertEquals(paidOrder.getId(), result.getContent().get(0).getId());
    }

    @Test
    void searchCombinesConditionsWithAnd() {

        User targetUser = createUser("order-search-target");
        User otherUser = createUser("order-search-other");

        Order target = createOrder(
                targetUser.getId(),
                LocalDateTime.of(2026, 8, 15, 11, 0));

        Order paidOtherUser = createOrder(
                otherUser.getId(),
                LocalDateTime.of(2026, 8, 15, 12, 0));

        target.markAsPaid();
        paidOtherUser.markAsPaid();
        entityManager.flush();

        Page<Order> result = orderRepository.search(
                null,
                targetUser.getId(),
                LocalDateTime.of(2026, 8, 15, 0, 0),
                LocalDateTime.of(2026, 8, 16, 0, 0),
                OrderStatus.PAID,
                ALL_HANDLING_STATUSES,
                PageRequest.of(0, 20));

        assertEquals(1, result.getTotalElements());
        assertEquals(target.getId(), result.getContent().get(0).getId());
    }

    @Test
    void countSalesOrdersCountsOnlyPaidAndShippedWithinRange() {

        User user = createUser("sales-dashboard-user");

        createOrder(
                user.getId(),
                LocalDateTime.of(2026, 8, 9, 23, 59));

        createOrder(
                user.getId(),
                LocalDateTime.of(2026, 8, 10, 10, 0));

        Order paidOrder = createOrder(
                user.getId(),
                LocalDateTime.of(2026, 8, 11, 10, 0));

        Order shippedOrder = createOrder(
                user.getId(),
                LocalDateTime.of(2026, 8, 12, 10, 0));

        Order cancelledOrder = createOrder(
                user.getId(),
                LocalDateTime.of(2026, 8, 13, 10, 0));

        createOrder(
                user.getId(),
                LocalDateTime.of(2026, 8, 14, 0, 0));

        paidOrder.markAsPaid();

        shippedOrder.markAsPaid();
        shippedOrder.markAsShipped();

        cancelledOrder.cancel();

        entityManager.flush();

        long result = orderRepository.countSalesOrders(
                LocalDateTime.of(2026, 8, 10, 0, 0),
                LocalDateTime.of(2026, 8, 14, 0, 0));

        assertEquals(2, result);
    }

    @Test
    void sumSalesAmountSumsOnlyPaidAndShippedWithinRange() {

        User user = createUser("sales-amount-user");

        createOrder(
                user.getId(),
                LocalDateTime.of(2026, 8, 10, 10, 0),
                1000);

        Order paidOrder = createOrder(
                user.getId(),
                LocalDateTime.of(2026, 8, 11, 10, 0),
                2000);

        Order shippedOrder = createOrder(
                user.getId(),
                LocalDateTime.of(2026, 8, 12, 10, 0),
                3000);

        Order cancelledOrder = createOrder(
                user.getId(),
                LocalDateTime.of(2026, 8, 13, 10, 0),
                4000);

        paidOrder.markAsPaid();

        shippedOrder.markAsPaid();
        shippedOrder.markAsShipped();

        cancelledOrder.cancel();

        entityManager.flush();

        long result = orderRepository.sumSalesAmount(
                LocalDateTime.of(2026, 8, 10, 0, 0),
                LocalDateTime.of(2026, 8, 14, 0, 0));

        assertEquals(5000, result);
    }

    @Test
    void countByStatusWithinRangeCountsOnlyMatchingStatusAndPeriod() {

        User user = createUser("sales-status-count-user");

        createOrder(
                user.getId(),
                LocalDateTime.of(2026, 8, 9, 23, 59));

        createOrder(
                user.getId(),
                LocalDateTime.of(2026, 8, 10, 10, 0));

        Order firstPaidOrder = createOrder(
                user.getId(),
                LocalDateTime.of(2026, 8, 11, 10, 0));

        Order secondPaidOrder = createOrder(
                user.getId(),
                LocalDateTime.of(2026, 8, 12, 10, 0));

        Order shippedOrder = createOrder(
                user.getId(),
                LocalDateTime.of(2026, 8, 13, 10, 0));

        Order outsideRangeOrder = createOrder(
                user.getId(),
                LocalDateTime.of(2026, 8, 14, 0, 0));

        firstPaidOrder.markAsPaid();
        secondPaidOrder.markAsPaid();

        shippedOrder.markAsPaid();
        shippedOrder.markAsShipped();

        outsideRangeOrder.markAsPaid();

        entityManager.flush();

        long result = orderRepository.countByStatusAndOrderedAtRange(
                OrderStatus.PAID,
                LocalDateTime.of(2026, 8, 10, 0, 0),
                LocalDateTime.of(2026, 8, 14, 0, 0));

        assertEquals(2, result);
    }

    @Test
    void findDailySalesAggregatesOrdersByDate() {

        User user = createUser("daily-sales-user");

        Order paidOrder = createOrder(
                user.getId(),
                LocalDateTime.of(2026, 8, 10, 10, 0),
                2000);

        createOrder(
                user.getId(),
                LocalDateTime.of(2026, 8, 10, 15, 0),
                1000);

        Order shippedOrder = createOrder(
                user.getId(),
                LocalDateTime.of(2026, 8, 11, 9, 0),
                3000);

        Order cancelledOrder = createOrder(
                user.getId(),
                LocalDateTime.of(2026, 8, 11, 18, 0),
                4000);

        paidOrder.markAsPaid();

        shippedOrder.markAsPaid();
        shippedOrder.markAsShipped();

        cancelledOrder.cancel();

        entityManager.flush();

        List<DailySalesProjection> result = orderRepository.findDailySales(
                LocalDateTime.of(2026, 8, 10, 0, 0),
                LocalDateTime.of(2026, 8, 12, 0, 0));

        assertEquals(2, result.size());

        DailySalesProjection firstDay = result.get(0);

        assertEquals(LocalDate.of(2026, 8, 10), firstDay.getDate());
        assertEquals(2, firstDay.getOrderCount());
        assertEquals(1, firstDay.getSalesOrderCount());
        assertEquals(2000, firstDay.getSalesAmount());

        DailySalesProjection secondDay = result.get(1);

        assertEquals(LocalDate.of(2026, 8, 11), secondDay.getDate());
        assertEquals(2, secondDay.getOrderCount());
        assertEquals(1, secondDay.getSalesOrderCount());
        assertEquals(3000, secondDay.getSalesAmount());
    }

    @Test
    void findProductSalesRankingAggregatesOnlyPaidAndShippedOrders() {

        User user = createUser("product-sales-ranking-user");

        Product product = createProduct(
                "現在の商品名",
                9999);

        Order firstPaidOrder = createOrderWithItem(
                user.getId(),
                LocalDateTime.of(2026, 8, 10, 10, 0),
                product,
                "注文時の商品名",
                1000,
                2);

        Order secondPaidOrder = createOrderWithItem(
                user.getId(),
                LocalDateTime.of(2026, 8, 11, 10, 0),
                product,
                "注文時の商品名",
                1000,
                3);

        Order shippedOrder = createOrderWithItem(
                user.getId(),
                LocalDateTime.of(2026, 8, 12, 10, 0),
                product,
                "注文時の商品名",
                1200,
                1);

        Order cancelledOrder = createOrderWithItem(
                user.getId(),
                LocalDateTime.of(2026, 8, 14, 10, 0),
                product,
                "注文時の商品名",
                1000,
                20);

        firstPaidOrder.markAsPaid();
        secondPaidOrder.markAsPaid();

        shippedOrder.markAsPaid();
        shippedOrder.markAsShipped();

        cancelledOrder.cancel();

        entityManager.flush();

        List<ProductSalesRankingProjection> result = orderRepository.findProductSalesRanking(
                LocalDateTime.of(2026, 8, 10, 0, 0),
                LocalDateTime.of(2026, 8, 15, 0, 0),
                PageRequest.of(0, 10));

        assertEquals(1, result.size());

        ProductSalesRankingProjection ranking = result.get(0);

        assertEquals(product.getId(), ranking.getProductId());
        assertEquals("注文時の商品名", ranking.getProductName());
        assertEquals(6, ranking.getQuantity());
        assertEquals(3, ranking.getOrderCount());
        assertEquals(6200, ranking.getSalesAmount());
    }

    private Order createOrder(
            Long userId,
            LocalDateTime orderedAt) {

        return createOrder(userId, orderedAt, 1000);
    }

    @Test
    void findCustomerSalesRankingAggregatesOnlyPaidAndShippedOrders() {

        User firstUser = createUser(
                "customer-sales-ranking-user-1");

        User secondUser = createUser(
                "customer-sales-ranking-user-2");

        Product product = createProduct(
                "顧客ランキング商品",
                1000);

        Order firstPaidOrder = createOrderWithItem(
                firstUser.getId(),
                LocalDateTime.of(2026, 8, 10, 10, 0),
                product,
                "顧客ランキング商品",
                1000,
                2);

        Order shippedOrder = createOrderWithItem(
                firstUser.getId(),
                LocalDateTime.of(2026, 8, 11, 10, 0),
                product,
                "顧客ランキング商品",
                1000,
                3);

        Order secondUserPaidOrder = createOrderWithItem(
                secondUser.getId(),
                LocalDateTime.of(2026, 8, 12, 10, 0),
                product,
                "顧客ランキング商品",
                1000,
                3);

        createOrderWithItem(
                firstUser.getId(),
                LocalDateTime.of(2026, 8, 13, 10, 0),
                product,
                "顧客ランキング商品",
                1000,
                10);

        Order cancelledOrder = createOrderWithItem(
                firstUser.getId(),
                LocalDateTime.of(2026, 8, 14, 10, 0),
                product,
                "顧客ランキング商品",
                1000,
                20);

        firstPaidOrder.markAsPaid();

        shippedOrder.markAsPaid();
        shippedOrder.markAsShipped();

        secondUserPaidOrder.markAsPaid();

        cancelledOrder.cancel();

        entityManager.flush();

        List<CustomerSalesRankingProjection> result = orderRepository.findCustomerSalesRanking(
                LocalDateTime.of(
                        2026, 8, 10, 0, 0),
                LocalDateTime.of(
                        2026, 8, 15, 0, 0),
                PageRequest.of(0, 10));

        assertEquals(2, result.size());

        CustomerSalesRankingProjection first = result.get(0);

        assertEquals(
                firstUser.getId(),
                first.getUserId());

        assertEquals(
                "customer-sales-ranking-user-1",
                first.getUsername());

        assertEquals(
                2,
                first.getOrderCount());

        assertEquals(
                5,
                first.getQuantity());

        assertEquals(
                5000,
                first.getSalesAmount());

        CustomerSalesRankingProjection second = result.get(1);

        assertEquals(
                secondUser.getId(),
                second.getUserId());

        assertEquals(
                "customer-sales-ranking-user-2",
                second.getUsername());

        assertEquals(
                1,
                second.getOrderCount());

        assertEquals(
                3,
                second.getQuantity());

        assertEquals(
                3000,
                second.getSalesAmount());
    }

    @Test
    void findCategorySalesRankingUsesOrderTimeCategorySnapshot() {

        User user = createUser(
                "category-sales-ranking-user");

        Product firstProduct = createProduct(
                "カテゴリランキング商品1",
                1000);

        Category orderTimeCategory = firstProduct.getCategory();

        Product secondProduct = createProduct(
                "カテゴリランキング商品2",
                2000);

        secondProduct.setCategory(orderTimeCategory);
        entityManager.flush();

        Order firstPaidOrder = createOrderWithItem(
                user.getId(),
                LocalDateTime.of(2026, 8, 10, 10, 0),
                firstProduct,
                "カテゴリランキング商品1",
                1000,
                2);

        Order shippedOrder = createOrderWithItem(
                user.getId(),
                LocalDateTime.of(2026, 8, 11, 10, 0),
                secondProduct,
                "カテゴリランキング商品2",
                2000,
                3);

        createOrderWithItem(
                user.getId(),
                LocalDateTime.of(2026, 8, 12, 10, 0),
                firstProduct,
                "カテゴリランキング商品1",
                1000,
                10);

        Order cancelledOrder = createOrderWithItem(
                user.getId(),
                LocalDateTime.of(2026, 8, 13, 10, 0),
                firstProduct,
                "カテゴリランキング商品1",
                1000,
                20);

        firstPaidOrder.markAsPaid();

        shippedOrder.markAsPaid();
        shippedOrder.markAsShipped();

        cancelledOrder.cancel();

        Category changedCategory = new Category(
                "changed-category-" + System.nanoTime());

        entityManager.persist(changedCategory);

        firstProduct.setCategory(changedCategory);
        secondProduct.setCategory(changedCategory);

        entityManager.flush();

        List<CategorySalesRankingProjection> result = orderRepository.findCategorySalesRanking(
                LocalDateTime.of(2026, 8, 10, 0, 0),
                LocalDateTime.of(2026, 8, 14, 0, 0),
                PageRequest.of(0, 10));

        assertEquals(1, result.size());

        CategorySalesRankingProjection ranking = result.get(0);

        assertEquals(
                orderTimeCategory.getId(),
                ranking.getCategoryId());

        assertEquals(
                orderTimeCategory.getName(),
                ranking.getCategoryName());

        assertEquals(
                5,
                ranking.getQuantity());

        assertEquals(
                2,
                ranking.getOrderCount());

        assertEquals(
                8000,
                ranking.getSalesAmount());
    }

    private Order createOrder(
            Long userId,
            LocalDateTime orderedAt,
            int totalAmount) {

        Order order = new Order(userId, totalAmount);
        order.setOrderedAt(orderedAt);

        Order saved = orderRepository.save(order);
        entityManager.flush();

        return saved;
    }

    private Product createProduct(
            String name,
            int price) {

        Category category = new Category(
                "ranking-category-" + System.nanoTime());

        entityManager.persist(category);

        Product product = new Product();
        product.setName(name);
        product.setPrice(price);
        product.setStock(100);
        product.setCategory(category);

        entityManager.persist(product);
        entityManager.flush();

        return product;
    }

    private Order createOrderWithItem(
            Long userId,
            LocalDateTime orderedAt,
            Product product,
            String productName,
            int price,
            int quantity) {

        OrderItem item = new OrderItem(
                product.getId(),
                productName,
                product.getCategory().getId(),
                product.getCategory().getName(),
                price,
                quantity);

        Order order = new Order(
                userId,
                item.getSubtotal());

        order.setOrderedAt(orderedAt);
        order.addItem(item);

        Order saved = orderRepository.save(order);
        entityManager.flush();

        return saved;
    }

    private User createUser(String username) {

        User user = new User();
        user.setUsername(username);
        user.setPassword("password");
        user.setRole("ROLE_USER");
        user.setEnabled(true);

        User saved = userRepository.save(user);
        entityManager.flush();

        return saved;
    }

    @Test
    void searchFiltersByHandlingStatus() {

        User user = createUser("order-handling-status-user");

        Order needsActionOrder = createOrder(
                user.getId(),
                LocalDateTime.of(2026, 8, 11, 10, 0));

        createOrder(
                user.getId(),
                LocalDateTime.of(2026, 8, 12, 10, 0));

        needsActionOrder.changeHandlingStatus(
                OrderHandlingStatus.NEEDS_ACTION);

        entityManager.flush();

        Page<Order> result = orderRepository.search(
                null,
                user.getId(),
                SEARCH_FROM,
                SEARCH_TO,
                null,
                List.of(OrderHandlingStatus.NEEDS_ACTION),
                PageRequest.of(0, 20));

        assertEquals(1, result.getTotalElements());
        assertEquals(
                needsActionOrder.getId(),
                result.getContent().get(0).getId());
    }

    @Test
    void countByHandlingStatusCountsOnlyMatchingOrders() {

        User user = createUser("handling-status-summary-user");

        createOrder(
                user.getId(),
                LocalDateTime.of(2026, 9, 1, 10, 0));

        Order needsActionOrder1 = createOrder(
                user.getId(),
                LocalDateTime.of(2026, 9, 2, 10, 0));

        Order needsActionOrder2 = createOrder(
                user.getId(),
                LocalDateTime.of(2026, 9, 3, 10, 0));

        Order inProgressOrder = createOrder(
                user.getId(),
                LocalDateTime.of(2026, 9, 4, 10, 0));

        Order resolvedOrder = createOrder(
                user.getId(),
                LocalDateTime.of(2026, 9, 5, 10, 0));

        needsActionOrder1.changeHandlingStatus(
                OrderHandlingStatus.NEEDS_ACTION);

        needsActionOrder2.changeHandlingStatus(
                OrderHandlingStatus.NEEDS_ACTION);

        inProgressOrder.changeHandlingStatus(
                OrderHandlingStatus.IN_PROGRESS);

        resolvedOrder.changeHandlingStatus(
                OrderHandlingStatus.RESOLVED);

        entityManager.flush();

        assertEquals(
                1,
                orderRepository.countByHandlingStatus(
                        OrderHandlingStatus.NONE));

        assertEquals(
                2,
                orderRepository.countByHandlingStatus(
                        OrderHandlingStatus.NEEDS_ACTION));

        assertEquals(
                1,
                orderRepository.countByHandlingStatus(
                        OrderHandlingStatus.IN_PROGRESS));

        assertEquals(
                1,
                orderRepository.countByHandlingStatus(
                        OrderHandlingStatus.RESOLVED));
    }

    @Test
    void searchFiltersByMultipleHandlingStatuses() {

        User user = createUser("order-search-handling-status-user");

        createOrder(
                user.getId(),
                LocalDateTime.of(2026, 9, 1, 10, 0));

        Order needsActionOrder = createOrder(
                user.getId(),
                LocalDateTime.of(2026, 9, 2, 10, 0));

        Order inProgressOrder = createOrder(
                user.getId(),
                LocalDateTime.of(2026, 9, 3, 10, 0));

        Order resolvedOrder = createOrder(
                user.getId(),
                LocalDateTime.of(2026, 9, 4, 10, 0));

        needsActionOrder.changeHandlingStatus(
                OrderHandlingStatus.NEEDS_ACTION);

        inProgressOrder.changeHandlingStatus(
                OrderHandlingStatus.IN_PROGRESS);

        resolvedOrder.changeHandlingStatus(
                OrderHandlingStatus.RESOLVED);

        entityManager.flush();

        Page<Order> result = orderRepository.search(
                null,
                user.getId(),
                SEARCH_FROM,
                SEARCH_TO,
                null,
                List.of(
                        OrderHandlingStatus.NEEDS_ACTION,
                        OrderHandlingStatus.IN_PROGRESS),
                PageRequest.of(0, 20));

        assertEquals(2, result.getTotalElements());

        assertEquals(
                inProgressOrder.getId(),
                result.getContent().get(0).getId());

        assertEquals(
                needsActionOrder.getId(),
                result.getContent().get(1).getId());
    }

}
