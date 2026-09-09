package com.example.ecsite.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.cart.Cart;
import com.example.ecsite.dto.AdminActionRequiredOrderDto;
import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderHandlingStatus;
import com.example.ecsite.entity.OrderItem;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.entity.OrderStatusHistoryActorType;
import com.example.ecsite.entity.Product;
import com.example.ecsite.exception.OrderNotFoundException;
import com.example.ecsite.exception.OrderValidationException;
import com.example.ecsite.exception.ProductNotFoundException;
import com.example.ecsite.form.AdminOrderSearchForm;
import com.example.ecsite.form.CheckoutForm;
import com.example.ecsite.repository.OrderRepository;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductService productService;
    private final InventoryService inventoryService;
    private final OrderStatusHistoryService orderStatusHistoryService;
    private final OrderHandlingStatusHistoryService orderHandlingStatusHistoryService;

    public OrderService(
            OrderRepository orderRepository,
            ProductService productService,
            InventoryService inventoryService,
            OrderStatusHistoryService orderStatusHistoryService,
            OrderHandlingStatusHistoryService orderHandlingStatusHistoryService) {

        this.orderRepository = orderRepository;
        this.productService = productService;
        this.inventoryService = inventoryService;
        this.orderStatusHistoryService = orderStatusHistoryService;
        this.orderHandlingStatusHistoryService = orderHandlingStatusHistoryService;
    }

    @Transactional
    public Order createOrder(
            Long userId,
            String username,
            Cart cart,
            CheckoutForm checkoutForm) {

        if (cart.getItems().isEmpty()) {
            throw new OrderValidationException(
                    "カートに商品がありません。");
        }

        Order order = new Order(userId, 0);

        order.setShippingAddress(
                checkoutForm.getShippingName().trim(),
                checkoutForm.getShippingPostalCode().trim(),
                checkoutForm.getShippingPrefecture().trim(),
                checkoutForm.getShippingCity().trim(),
                checkoutForm.getShippingAddressLine().trim(),
                checkoutForm.getShippingPhone().trim());

        int totalAmount = 0;

        for (com.example.ecsite.cart.CartItem cartItem : cart.getItems()) {

            Product product;

            try {
                product = productService.findByIdForUpdate(
                        cartItem.getProductId());

            } catch (ProductNotFoundException e) {
                throw new OrderValidationException(
                        cartItem.getProductName()
                                + "は現在購入できません。",
                        e);
            }

            if (product.getPrice() != cartItem.getPrice()) {

                int oldPrice = cartItem.getPrice();
                int newPrice = product.getPrice();

                cart.refreshPrice(
                        cartItem.getProductId(),
                        newPrice);

                throw new OrderValidationException(
                        product.getName()
                                + "の価格が"
                                + oldPrice
                                + "円から"
                                + newPrice
                                + "円に変更されました。"
                                + "カートを確認してください。");
            }

            if (product.getStock() < cartItem.getQuantity()) {
                throw new OrderValidationException(
                        product.getName()
                                + "の在庫が不足しています。");
            }

            OrderItem orderItem = new OrderItem(
                    product.getId(),
                    product.getName(),
                    product.getCategory().getId(),
                    product.getCategory().getName(),
                    product.getPrice(),
                    cartItem.getQuantity());

            order.addItem(orderItem);

            totalAmount += orderItem.getSubtotal();
        }

        order.setTotalAmount(totalAmount);

        Order savedOrder = orderRepository.save(order);

        orderStatusHistoryService.record(
                savedOrder,
                null,
                OrderStatus.ORDERED,
                OrderStatusHistoryActorType.USER,
                userId,
                username);

        for (OrderItem item : savedOrder.getItems()) {

            inventoryService.decreaseForOrder(
                    item.getProductId(),
                    item.getQuantity(),
                    savedOrder.getId());
        }

        return savedOrder;
    }

    @Transactional(readOnly = true)
    public Page<Order> findOrdersByUserId(Long userId, int page, int size) {

        Pageable pageable = PageRequest.of(page, size);

        return orderRepository.findByUserIdOrderByOrderedAtDesc(userId, pageable);
    }

    @Transactional(readOnly = true)
    public void validateCart(Cart cart) {

        if (cart.getItems().isEmpty()) {
            throw new OrderValidationException(
                    "カートに商品がありません。");
        }

        List<String> messages = new ArrayList<>();

        for (com.example.ecsite.cart.CartItem cartItem : cart.getItems()) {

            Product product;

            try {
                product = productService.findById(
                        cartItem.getProductId());

            } catch (ProductNotFoundException e) {
                messages.add(
                        cartItem.getProductName()
                                + "は現在購入できません。");
                continue;
            }

            if (product.getPrice() != cartItem.getPrice()) {

                int oldPrice = cartItem.getPrice();
                int newPrice = product.getPrice();

                cart.refreshPrice(
                        cartItem.getProductId(),
                        newPrice);

                messages.add(
                        product.getName()
                                + "の価格が"
                                + oldPrice
                                + "円から"
                                + newPrice
                                + "円に変更されました。");
            }

            if (product.getStock() < cartItem.getQuantity()) {

                messages.add(
                        product.getName()
                                + "の在庫が不足しています。");
            }
        }

        if (!messages.isEmpty()) {
            throw new OrderValidationException(
                    String.join(" ", messages));
        }
    }

    @Transactional(readOnly = true)
    public Page<Order> findAllOrders(
            OrderStatus status,
            int page,
            int size) {

        Pageable pageable = PageRequest.of(
                page,
                size);

        if (status == null) {
            return orderRepository.findAllByOrderByOrderedAtDesc(pageable);
        }

        return orderRepository.findByStatusOrderByOrderedAtDesc(status, pageable);
    }

    @Transactional(readOnly = true)
    public Order findOrderWithItems(Long id) {

        return orderRepository.findByIdWithItems(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    @Transactional
    public void markAsPaid(
            Long id,
            Long accountId,
            String username,
            String internalNote) {

        Order order = findOrderForUpdate(id);

        OrderStatus fromStatus = order.getStatus();

        order.markAsPaid();

        orderStatusHistoryService.record(
                order,
                fromStatus,
                order.getStatus(),
                OrderStatusHistoryActorType.ADMIN,
                accountId,
                username,
                internalNote);
    }

    @Transactional
    public void markAsShipped(
            Long id,
            Long accountId,
            String username,
            String internalNote) {

        Order order = findOrderForUpdate(id);

        OrderStatus fromStatus = order.getStatus();

        order.markAsShipped();

        orderStatusHistoryService.record(
                order,
                fromStatus,
                order.getStatus(),
                OrderStatusHistoryActorType.ADMIN,
                accountId,
                username,
                internalNote);
    }

    @Transactional
    public void cancelOrder(
            Long id,
            Long accountId,
            String username,
            String internalNote) {

        Order order = findOrderForUpdate(id);

        cancelAndRestoreStock(
                order,
                OrderStatusHistoryActorType.ADMIN,
                accountId,
                username,
                internalNote);
    }

    @Transactional
    public boolean changeHandlingStatus(
            Long id,
            OrderHandlingStatus handlingStatus,
            Long changedByAccountId,
            String changedByUsername) {

        Order order = findOrderForUpdate(id);

        OrderHandlingStatus fromStatus = order.getHandlingStatus();

        if (fromStatus == handlingStatus) {
            return false;
        }

        order.changeHandlingStatus(handlingStatus);

        orderHandlingStatusHistoryService.record(
                order,
                fromStatus,
                handlingStatus,
                changedByAccountId,
                changedByUsername);

        return true;
    }

    @Transactional(readOnly = true)
    public Page<Order> searchOrders(
            AdminOrderSearchForm searchForm,
            int page,
            int size) {

        List<OrderHandlingStatus> handlingStatuses = searchForm.getHandlingStatus() == null
                ? Arrays.asList(OrderHandlingStatus.values())
                : List.of(searchForm.getHandlingStatus());

        return searchOrders(
                searchForm,
                handlingStatuses,
                page,
                size);
    }

    @Transactional(readOnly = true)
    public Page<Order> searchActionRequiredOrders(
            AdminOrderSearchForm searchForm,
            int page,
            int size) {

        OrderHandlingStatus handlingStatus = searchForm.getHandlingStatus();

        List<OrderHandlingStatus> handlingStatuses;

        if (handlingStatus == OrderHandlingStatus.NEEDS_ACTION
                || handlingStatus == OrderHandlingStatus.IN_PROGRESS) {
            handlingStatuses = List.of(handlingStatus);
        } else {
            handlingStatuses = List.of(
                    OrderHandlingStatus.NEEDS_ACTION,
                    OrderHandlingStatus.IN_PROGRESS);
        }

        return searchOrders(
                searchForm,
                handlingStatuses,
                page,
                size);
    }

    @Transactional(readOnly = true)
    public Page<AdminActionRequiredOrderDto> searchActionRequiredOrderDetails(
            AdminOrderSearchForm searchForm,
            int page,
            int size) {

        Page<Order> orderPage = searchActionRequiredOrders(
                searchForm,
                page,
                size);

        List<Long> orderIds = orderPage.getContent()
                .stream()
                .map(Order::getId)
                .toList();

        Map<Long, LocalDateTime> updatedAtMap = orderHandlingStatusHistoryService
                .findLatestUpdatedAtByOrderIds(orderIds);

        LocalDate today = LocalDate.now();

        return orderPage.map(order -> {

            LocalDateTime updatedAt = updatedAtMap.get(order.getId());

            Long elapsedDays = updatedAt == null
                    ? null
                    : ChronoUnit.DAYS.between(
                            updatedAt.toLocalDate(),
                            today);

            return new AdminActionRequiredOrderDto(
                    order,
                    updatedAt,
                    elapsedDays);
        });
    }

    private Page<Order> searchOrders(
            AdminOrderSearchForm searchForm,
            List<OrderHandlingStatus> handlingStatuses,
            int page,
            int size) {

        LocalDateTime from = resolveFrom(searchForm);
        LocalDateTime toExclusive = resolveToExclusive(searchForm);

        Pageable pageable = PageRequest.of(page, size);

        return orderRepository.search(
                searchForm.getOrderId(),
                searchForm.getUserId(),
                from,
                toExclusive,
                searchForm.getStatus(),
                handlingStatuses,
                pageable);
    }

    @Transactional(readOnly = true)
    public List<Order> searchAllOrders(
            AdminOrderSearchForm searchForm) {

        LocalDateTime from = resolveFrom(searchForm);
        LocalDateTime toExclusive = resolveToExclusive(searchForm);

        List<OrderHandlingStatus> handlingStatuses = searchForm.getHandlingStatus() == null
                ? Arrays.asList(OrderHandlingStatus.values())
                : List.of(searchForm.getHandlingStatus());

        Page<Order> orderPage = orderRepository.search(
                searchForm.getOrderId(),
                searchForm.getUserId(),
                from,
                toExclusive,
                searchForm.getStatus(),
                handlingStatuses,
                Pageable.unpaged());

        return orderPage.getContent();
    }

    private void cancelAndRestoreStock(
            Order order,
            OrderStatusHistoryActorType changedByType,
            Long accountId,
            String username,
            String internalNote) {

        OrderStatus fromStatus = order.getStatus();

        // 不正な状態なら、在庫を変更する前に例外になる
        order.cancel();

        orderStatusHistoryService.record(
                order,
                fromStatus,
                order.getStatus(),
                changedByType,
                accountId,
                username,
                internalNote);

        for (OrderItem item : order.getItems()) {

            inventoryService.restoreForOrderCancellation(
                    item.getProductId(),
                    item.getQuantity(),
                    order.getId());
        }
    }

    private Order findOrderForUpdate(Long id) {

        return orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Order findOrderByIdAndUserId(
            Long orderId,
            Long userId) {

        return orderRepository
                .findByIdAndUserIdWithItems(
                        orderId,
                        userId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    @Transactional
    public void cancelOrderForUser(
            Long orderId,
            Long userId,
            String username) {

        Order order = orderRepository
                .findByIdAndUserIdForUpdate(
                        orderId,
                        userId)
                .orElseThrow(() -> new OrderNotFoundException(
                        orderId));

        cancelAndRestoreStock(
                order,
                OrderStatusHistoryActorType.USER,
                userId,
                username,
                null);
    }

    @Transactional(readOnly = true)
    public long countOrdersByStatus(
            OrderStatus status) {

        return orderRepository.countByStatus(status);
    }

    @Transactional(readOnly = true)
    public long countOrdersByHandlingStatus(OrderHandlingStatus handlingStatus) {
        return orderRepository.countByHandlingStatus(handlingStatus);
    }

    private LocalDateTime resolveFrom(
            AdminOrderSearchForm searchForm) {

        return searchForm.getFrom() == null
                ? LocalDateTime.of(1970, 1, 1, 0, 0)
                : searchForm.getFrom().atStartOfDay();
    }

    private LocalDateTime resolveToExclusive(
            AdminOrderSearchForm searchForm) {

        return searchForm.getTo() == null
                ? LocalDateTime.of(9999, 12, 31, 0, 0)
                : searchForm.getTo()
                        .plusDays(1)
                        .atStartOfDay();
    }
}
