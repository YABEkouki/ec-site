package com.example.ecsite.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.cart.Cart;
import com.example.ecsite.cart.CartItem;
import com.example.ecsite.dto.ActionRequiredAgingSummary;
import com.example.ecsite.dto.AdminActionRequiredOrderDto;
import com.example.ecsite.dto.AdminAssigneeActionRequiredSummary;
import com.example.ecsite.dto.OrderItemChangePreview;
import com.example.ecsite.entity.AdminAccount;
import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderCharge;
import com.example.ecsite.entity.OrderChargeType;
import com.example.ecsite.entity.OrderContentChangeCharge;
import com.example.ecsite.entity.OrderContentChangeHistory;
import com.example.ecsite.entity.OrderContentChangeHistoryActorType;
import com.example.ecsite.entity.OrderContentChangeItem;
import com.example.ecsite.entity.OrderContentChangeSource;
import com.example.ecsite.entity.OrderHandlingStatus;
import com.example.ecsite.entity.OrderItem;
import com.example.ecsite.entity.OrderShippingAddressHistoryActorType;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.entity.OrderStatusHistoryActorType;
import com.example.ecsite.entity.Product;
import com.example.ecsite.entity.TaxCategory;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.exception.OrderNotFoundException;
import com.example.ecsite.exception.OrderValidationException;
import com.example.ecsite.exception.ProductNotFoundException;
import com.example.ecsite.form.ActionRequiredOrderSort;
import com.example.ecsite.form.AdminActionRequiredOrderSearchForm;
import com.example.ecsite.form.AdminOrderAssigneeFilter;
import com.example.ecsite.form.AdminOrderSearchForm;
import com.example.ecsite.form.CheckoutForm;
import com.example.ecsite.form.OrderItemChangeForm;
import com.example.ecsite.form.OrderShippingAddressForm;
import com.example.ecsite.repository.OrderContentChangeHistoryRepository;
import com.example.ecsite.repository.OrderRepository;
import com.example.ecsite.repository.projection.ActionRequiredAgingSummaryProjection;
import com.example.ecsite.repository.projection.AdminActionRequiredOrderSearchProjection;
import com.example.ecsite.service.order.OrderAmountSnapshot;
import com.example.ecsite.service.order.OrderChargeSnapshot;
import com.example.ecsite.service.order.OrderItemSnapshot;
import com.example.ecsite.service.pricing.ChargeTaxSnapshot;
import com.example.ecsite.service.pricing.OrderAmount;
import com.example.ecsite.service.pricing.OrderAmountCalculator;
import com.example.ecsite.service.pricing.OrderChargeAmount;
import com.example.ecsite.service.pricing.OrderPricingContext;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductService productService;
    private final InventoryService inventoryService;
    private final OrderStatusHistoryService orderStatusHistoryService;
    private final OrderHandlingStatusHistoryService orderHandlingStatusHistoryService;
    private final OrderShippingAddressHistoryService orderShippingAddressHistoryService;
    private final OrderAssigneeHistoryService orderAssigneeHistoryService;
    private final OrderContentChangeHistoryRepository orderContentChangeHistoryRepository;
    private final AdminAccountService adminAccountService;
    private final OrderDeadlineCalculator orderDeadlineCalculator;
    private final TaxCategoryService taxCategoryService;
    private final OrderAmountCalculator orderAmountCalculator;
    private final Clock clock;

    @Autowired
    public OrderService(
            OrderRepository orderRepository,
            ProductService productService,
            InventoryService inventoryService,
            OrderStatusHistoryService orderStatusHistoryService,
            OrderHandlingStatusHistoryService orderHandlingStatusHistoryService,
            OrderShippingAddressHistoryService orderShippingAddressHistoryService,
            OrderAssigneeHistoryService orderAssigneeHistoryService,
            OrderContentChangeHistoryRepository orderContentChangeHistoryRepository,
            AdminAccountService adminAccountService,
            OrderDeadlineCalculator orderDeadlineCalculator,
            TaxCategoryService taxCategoryService,
            OrderAmountCalculator orderAmountCalculator) {

        this(
                orderRepository,
                productService,
                inventoryService,
                orderStatusHistoryService,
                orderHandlingStatusHistoryService,
                orderShippingAddressHistoryService,
                orderAssigneeHistoryService,
                orderContentChangeHistoryRepository,
                adminAccountService,
                orderDeadlineCalculator,
                taxCategoryService,
                orderAmountCalculator,
                Clock.system(ZoneId.of("Asia/Tokyo")));
    }

    OrderService(
            OrderRepository orderRepository,
            ProductService productService,
            InventoryService inventoryService,
            OrderStatusHistoryService orderStatusHistoryService,
            OrderHandlingStatusHistoryService orderHandlingStatusHistoryService,
            OrderShippingAddressHistoryService orderShippingAddressHistoryService,
            OrderAssigneeHistoryService orderAssigneeHistoryService,
            OrderContentChangeHistoryRepository orderContentChangeHistoryRepository,
            AdminAccountService adminAccountService,
            OrderDeadlineCalculator orderDeadlineCalculator,
            TaxCategoryService taxCategoryService,
            OrderAmountCalculator orderAmountCalculator,
            Clock clock) {

        this.orderRepository = orderRepository;
        this.productService = productService;
        this.inventoryService = inventoryService;
        this.orderStatusHistoryService = orderStatusHistoryService;
        this.orderHandlingStatusHistoryService = orderHandlingStatusHistoryService;
        this.orderShippingAddressHistoryService = orderShippingAddressHistoryService;
        this.orderAssigneeHistoryService = orderAssigneeHistoryService;
        this.orderContentChangeHistoryRepository = orderContentChangeHistoryRepository;
        this.adminAccountService = adminAccountService;
        this.orderDeadlineCalculator = orderDeadlineCalculator;
        this.taxCategoryService = taxCategoryService;
        this.orderAmountCalculator = orderAmountCalculator;
        this.clock = clock;
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

        LocalDateTime orderedAt = LocalDateTime.now(clock);

        LocalDateTime changeDeadlineAt = orderDeadlineCalculator.calculate(orderedAt);

        Order order = new Order(
                userId,
                0,
                orderedAt,
                changeDeadlineAt);

        OrderPricingContext pricingContext = new OrderPricingContext();

        order.setShippingAddress(
                checkoutForm.getShippingName().trim(),
                checkoutForm.getShippingPostalCode().trim(),
                checkoutForm.getShippingPrefecture().trim(),
                checkoutForm.getShippingCity().trim(),
                checkoutForm.getShippingAddressLine().trim(),
                checkoutForm.getShippingPhone().trim());

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

            TaxCategory taxCategory = product.getTaxCategory();

            OrderItem orderItem = new OrderItem(
                    product.getId(),
                    product.getName(),
                    product.getCategory().getId(),
                    product.getCategory().getName(),
                    taxCategory.getId(),
                    taxCategory.getCode(),
                    taxCategory.getName(),
                    taxCategory.getTaxRate(),
                    product.getPrice(),
                    cartItem.getQuantity());

            order.addItem(orderItem);

            pricingContext.addItem(
                    product.getPrice(),
                    cartItem.getQuantity(),
                    taxCategory.getId(),
                    taxCategory.getCode(),
                    taxCategory.getName(),
                    taxCategory.getTaxRate());
        }

        TaxCategory shippingTaxCategory = taxCategoryService.findStandardTaxCategory();

        OrderAmount orderAmount = orderAmountCalculator.calculate(
                pricingContext,
                shippingTaxCategory);

        order.applyAmount(orderAmount);

        for (OrderChargeAmount chargeAmount : orderAmount.charges()) {

            OrderCharge orderCharge = new OrderCharge(
                    chargeAmount.chargeType(),
                    chargeAmount.name(),
                    chargeAmount.amount(),
                    chargeAmount.taxCategoryId(),
                    chargeAmount.taxCategoryCode(),
                    chargeAmount.taxCategoryName(),
                    chargeAmount.taxRate(),
                    chargeAmount.displayOrder());

            order.addCharge(orderCharge);
        }

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
    public OrderAmount calculateOrderAmount(Cart cart) {

        OrderPricingContext pricingContext = new OrderPricingContext();

        for (CartItem cartItem : cart.getItems()) {

            Product product = productService.findById(
                    cartItem.getProductId());

            TaxCategory taxCategory = product.getTaxCategory();

            pricingContext.addItem(
                    product.getPrice(),
                    cartItem.getQuantity(),
                    taxCategory.getId(),
                    taxCategory.getCode(),
                    taxCategory.getName(),
                    taxCategory.getTaxRate());
        }

        TaxCategory shippingTaxCategory = taxCategoryService.findStandardTaxCategory();

        return orderAmountCalculator.calculate(
                pricingContext,
                shippingTaxCategory);
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

        Order order = orderRepository
                .findByIdWithItems(id)
                .orElseThrow(() -> new OrderNotFoundException(id));

        orderRepository
                .findByIdWithCharges(id)
                .orElseThrow(() -> new OrderNotFoundException(id));

        return order;
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
                internalNote,
                LocalDateTime.now(clock));
    }

    @Transactional
    public boolean changeHandlingStatus(
            Long id,
            OrderHandlingStatus handlingStatus,
            Long assignedAdminAccountId,
            Long changedByAccountId,
            String changedByUsername) {

        Order order = findOrderForUpdate(id);

        OrderHandlingStatus fromStatus = order.getHandlingStatus();

        AdminAccount currentAssignedAdmin = order.getAssignedAdminAccount();

        Long currentAssignedAdminId = currentAssignedAdmin == null
                ? null
                : currentAssignedAdmin.getId();

        boolean handlingStatusChanged = fromStatus != handlingStatus;

        boolean assignedAdminChanged = !java.util.Objects.equals(
                currentAssignedAdminId,
                assignedAdminAccountId);

        if (!handlingStatusChanged
                && !assignedAdminChanged) {
            return false;
        }

        AdminAccount newAssignedAdmin = currentAssignedAdmin;

        if (assignedAdminChanged) {

            newAssignedAdmin = null;

            if (assignedAdminAccountId != null) {

                if (handlingStatus != OrderHandlingStatus.NEEDS_ACTION
                        && handlingStatus != OrderHandlingStatus.IN_PROGRESS) {

                    throw new IllegalArgumentException(
                            "担当管理者を設定できるのは要対応または対応中の注文のみです。");
                }

                newAssignedAdmin = adminAccountService.findById(
                        assignedAdminAccountId);

                if (!newAssignedAdmin.isEnabled()) {
                    throw new IllegalArgumentException(
                            "無効な管理者アカウントを担当者に設定することはできません。");
                }
            }
        }

        UUID changeEventId = UUID.randomUUID();

        if (assignedAdminChanged) {

            order.changeAssignedAdminAccount(
                    newAssignedAdmin);

            orderAssigneeHistoryService.record(
                    order,
                    currentAssignedAdmin == null
                            ? null
                            : currentAssignedAdmin.getId(),
                    currentAssignedAdmin == null
                            ? null
                            : currentAssignedAdmin.getUsername(),
                    newAssignedAdmin == null
                            ? null
                            : newAssignedAdmin.getId(),
                    newAssignedAdmin == null
                            ? null
                            : newAssignedAdmin.getUsername(),
                    changedByAccountId,
                    changedByUsername,
                    changeEventId);
        }

        if (handlingStatusChanged) {

            order.changeHandlingStatus(
                    handlingStatus);

            orderHandlingStatusHistoryService.record(
                    order,
                    fromStatus,
                    handlingStatus,
                    changedByAccountId,
                    changedByUsername,
                    changeEventId);
        }

        return true;
    }

    @Transactional(readOnly = true)
    public Page<Order> searchOrders(
            AdminOrderSearchForm searchForm,
            Long loginAdminAccountId,
            int page,
            int size) {

        List<OrderHandlingStatus> handlingStatuses = searchForm.getHandlingStatus() == null
                ? Arrays.asList(OrderHandlingStatus.values())
                : List.of(searchForm.getHandlingStatus());

        return searchOrders(
                searchForm,
                handlingStatuses,
                loginAdminAccountId,
                page,
                size);
    }

    @Transactional(readOnly = true)
    public Page<AdminActionRequiredOrderDto> searchActionRequiredOrderDetails(
            AdminActionRequiredOrderSearchForm searchForm,
            Long loginAdminAccountId,
            int page,
            int size) {

        AdminOrderAssigneeFilter assigneeFilter = searchForm.getAssigneeFilter();
        if (assigneeFilter == null) {
            assigneeFilter = AdminOrderAssigneeFilter.ALL;
        }

        Long assignedAdminAccountId = switch (assigneeFilter) {
            case ALL, UNASSIGNED -> null;
            case ME -> loginAdminAccountId;
            case SPECIFIC -> searchForm.getAssignedAdminAccountId();
        };

        return searchActionRequiredOrderDetails(
                searchForm,
                assigneeFilter,
                assignedAdminAccountId,
                page,
                size);
    }

    @Transactional(readOnly = true)
    public Page<AdminActionRequiredOrderDto> searchMyAssignedOrderDetails(
            AdminActionRequiredOrderSearchForm searchForm,
            Long loginAdminAccountId,
            int page,
            int size) {

        return searchActionRequiredOrderDetails(
                searchForm,
                AdminOrderAssigneeFilter.ME,
                loginAdminAccountId,
                page,
                size);
    }

    @Transactional(readOnly = true)
    public Page<AdminActionRequiredOrderDto> searchUnassignedOrderDetails(
            AdminActionRequiredOrderSearchForm searchForm,
            int page,
            int size) {

        return searchActionRequiredOrderDetails(
                searchForm,
                AdminOrderAssigneeFilter.UNASSIGNED,
                null,
                page,
                size);
    }

    private Page<AdminActionRequiredOrderDto> searchActionRequiredOrderDetails(
            AdminActionRequiredOrderSearchForm searchForm,
            AdminOrderAssigneeFilter assigneeFilter,
            Long assignedAdminAccountId,
            int page,
            int size) {

        LocalDateTime from = resolveFrom(searchForm.getFrom());
        LocalDateTime toExclusive = resolveToExclusive(searchForm.getTo());

        List<OrderHandlingStatus> handlingStatuses = resolveActionRequiredHandlingStatuses(
                searchForm.getHandlingStatus());

        LocalDate today = LocalDate.now();

        LocalDateTime elapsedCutoffExclusive = resolveElapsedCutoffExclusive(
                searchForm.getMinElapsedDays(),
                today);

        ActionRequiredOrderSort sort = searchForm.getSort() == null
                ? ActionRequiredOrderSort.OLDEST
                : searchForm.getSort();

        Pageable pageable = PageRequest.of(page, size);

        Page<AdminActionRequiredOrderSearchProjection> projectionPage = orderRepository.searchActionRequiredOrders(
                searchForm.getOrderId(),
                searchForm.getUserId(),
                from,
                toExclusive,
                searchForm.getStatus() == null
                        ? null
                        : searchForm.getStatus().name(),
                handlingStatuses.stream()
                        .map(Enum::name)
                        .toList(),
                elapsedCutoffExclusive,
                assigneeFilter.name(),
                assignedAdminAccountId,
                sort.name(),
                pageable);

        List<Long> orderIds = projectionPage.getContent()
                .stream()
                .map(AdminActionRequiredOrderSearchProjection::getOrderId)
                .toList();

        Map<Long, Order> orderMap = orderIds.isEmpty()
                ? Map.of()
                : orderRepository
                        .findAllWithAssignedAdminByIdIn(orderIds)
                        .stream()
                        .collect(Collectors.toMap(
                                Order::getId,
                                Function.identity()));

        return projectionPage.map(projection -> {

            Order order = orderMap.get(projection.getOrderId());

            LocalDateTime updatedAt = projection.getHandlingStatusUpdatedAt();

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

    @Transactional(readOnly = true)
    public long countMyAssignedActionRequiredOrders(Long loginAdminAccountId) {
        return orderRepository.countByAssignedAdminAccount_IdAndHandlingStatusIn(
                loginAdminAccountId,
                List.of(
                        OrderHandlingStatus.NEEDS_ACTION,
                        OrderHandlingStatus.IN_PROGRESS));
    }

    @Transactional(readOnly = true)
    public long countUnassignedActionRequiredOrders() {

        return orderRepository
                .countByAssignedAdminAccountIsNullAndHandlingStatusIn(
                        List.of(
                                OrderHandlingStatus.NEEDS_ACTION,
                                OrderHandlingStatus.IN_PROGRESS));
    }

    @Transactional(readOnly = true)
    public ActionRequiredAgingSummary getActionRequiredAgingSummary() {
        return getActionRequiredAgingSummary(
                AdminOrderAssigneeFilter.ALL,
                null);
    }

    @Transactional(readOnly = true)
    public ActionRequiredAgingSummary getMyAssignedActionRequiredAgingSummary(
            Long loginAdminAccountId) {

        return getActionRequiredAgingSummary(
                AdminOrderAssigneeFilter.ME,
                loginAdminAccountId);
    }

    @Transactional(readOnly = true)
    public ActionRequiredAgingSummary getUnassignedActionRequiredAgingSummary() {
        return getActionRequiredAgingSummary(
                AdminOrderAssigneeFilter.UNASSIGNED,
                null);
    }

    @Transactional(readOnly = true)
    public OrderItemChangePreview previewItemChangeForUser(
            Long orderId,
            Long userId,
            OrderItemChangeForm form) {

        Order order = findOrderByIdAndUserId(orderId, userId);

        LocalDateTime now = LocalDateTime.now(clock);

        validateItemChangeOrder(order, form, now);

        return buildItemChangePreview(order, form);
    }

    private ActionRequiredAgingSummary getActionRequiredAgingSummary(
            AdminOrderAssigneeFilter assigneeFilter,
            Long assignedAdminAccountId) {

        LocalDate today = LocalDate.now();

        LocalDateTime threeDaysCutoffExclusive = resolveElapsedCutoffExclusive(3, today);

        LocalDateTime sevenDaysCutoffExclusive = resolveElapsedCutoffExclusive(7, today);

        ActionRequiredAgingSummaryProjection projection = orderRepository.findActionRequiredAgingSummary(
                threeDaysCutoffExclusive,
                sevenDaysCutoffExclusive,
                assigneeFilter.name(),
                assignedAdminAccountId);

        return new ActionRequiredAgingSummary(
                projection.getThreeDaysOrMoreCount(),
                projection.getSevenDaysOrMoreCount());
    }

    private LocalDateTime resolveFrom(LocalDate from) {
        return from == null
                ? LocalDateTime.of(1970, 1, 1, 0, 0)
                : from.atStartOfDay();
    }

    private LocalDateTime resolveToExclusive(LocalDate to) {
        return to == null
                ? LocalDateTime.of(9999, 12, 31, 0, 0)
                : to.plusDays(1).atStartOfDay();
    }

    private List<OrderHandlingStatus> resolveActionRequiredHandlingStatuses(
            OrderHandlingStatus handlingStatus) {

        if (handlingStatus == OrderHandlingStatus.NEEDS_ACTION
                || handlingStatus == OrderHandlingStatus.IN_PROGRESS) {

            return List.of(handlingStatus);
        }

        return List.of(
                OrderHandlingStatus.NEEDS_ACTION,
                OrderHandlingStatus.IN_PROGRESS);
    }

    private LocalDateTime resolveElapsedCutoffExclusive(
            Integer minElapsedDays,
            LocalDate today) {

        if (minElapsedDays == null) {
            return null;
        }

        if (minElapsedDays != 3 && minElapsedDays != 7) {
            return null;
        }

        return today
                .minusDays(minElapsedDays - 1L)
                .atStartOfDay();
    }

    private Page<Order> searchOrders(
            AdminOrderSearchForm searchForm,
            List<OrderHandlingStatus> handlingStatuses,
            Long loginAdminAccountId,
            int page,
            int size) {

        LocalDateTime from = resolveFrom(searchForm);
        LocalDateTime toExclusive = resolveToExclusive(searchForm);

        AdminOrderAssigneeFilter assigneeFilter = searchForm.getAssigneeFilter();

        if (assigneeFilter == null) {
            assigneeFilter = AdminOrderAssigneeFilter.ALL;
        }

        Long assignedAdminAccountId = resolveAssignedAdminAccountId(
                assigneeFilter,
                searchForm.getAssignedAdminAccountId(),
                loginAdminAccountId);

        Pageable pageable = PageRequest.of(page, size);

        return orderRepository.search(
                searchForm.getOrderId(),
                searchForm.getUserId(),
                from,
                toExclusive,
                searchForm.getStatus(),
                handlingStatuses,
                assigneeFilter.name(),
                assignedAdminAccountId,
                pageable);
    }

    @Transactional(readOnly = true)
    public List<Order> searchAllOrders(
            AdminOrderSearchForm searchForm,
            Long loginAdminAccountId) {

        LocalDateTime from = resolveFrom(searchForm);
        LocalDateTime toExclusive = resolveToExclusive(searchForm);

        List<OrderHandlingStatus> handlingStatuses = searchForm.getHandlingStatus() == null
                ? Arrays.asList(OrderHandlingStatus.values())
                : List.of(searchForm.getHandlingStatus());

        AdminOrderAssigneeFilter assigneeFilter = searchForm.getAssigneeFilter();

        if (assigneeFilter == null) {
            assigneeFilter = AdminOrderAssigneeFilter.ALL;
        }

        Long assignedAdminAccountId = resolveAssignedAdminAccountId(
                assigneeFilter,
                searchForm.getAssignedAdminAccountId(),
                loginAdminAccountId);

        Page<Order> orderPage = orderRepository.search(
                searchForm.getOrderId(),
                searchForm.getUserId(),
                from,
                toExclusive,
                searchForm.getStatus(),
                handlingStatuses,
                assigneeFilter.name(),
                assignedAdminAccountId,
                Pageable.unpaged());

        return orderPage.getContent();
    }

    private void cancelAndRestoreStock(
            Order order,
            OrderStatusHistoryActorType changedByType,
            Long accountId,
            String username,
            String internalNote,
            LocalDateTime cancelledAt) {

        OrderStatus fromStatus = order.getStatus();

        // 不正な状態なら、在庫を変更する前に例外になる
        order.cancel(cancelledAt);

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

        Order order = orderRepository
                .findByIdAndUserIdWithItems(
                        orderId,
                        userId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        orderRepository
                .findByIdAndUserIdWithCharges(
                        orderId,
                        userId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        return order;
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

        LocalDateTime now = LocalDateTime.now(clock);

        if (!order.canCancel()) {
            throw new InvalidOrderStatusException(
                    "注文受付中の注文だけを"
                            + "キャンセルできます。");
        }

        if (!order.isWithinModificationPeriod(now)) {
            throw new InvalidOrderStatusException(
                    "この注文の変更受付は終了しています。");
        }

        cancelAndRestoreStock(
                order,
                OrderStatusHistoryActorType.USER,
                userId,
                username,
                null,
                now);
    }

    @Transactional
    public boolean changeShippingAddressForUser(
            Long orderId,
            Long userId,
            String username,
            OrderShippingAddressForm form) {

        Order order = orderRepository
                .findByIdAndUserIdForUpdate(
                        orderId,
                        userId)
                .orElseThrow(() -> new OrderNotFoundException(
                        orderId));

        LocalDateTime now = LocalDateTime.now(clock);

        if (order.getStatus() != OrderStatus.ORDERED
                && order.getStatus() != OrderStatus.PAID) {

            throw new InvalidOrderStatusException(
                    "現在の注文状態では配送先を変更できません。");
        }

        if (!order.isWithinModificationPeriod(now)) {

            throw new InvalidOrderStatusException(
                    "この注文の変更受付は終了しています。");
        }

        String newShippingName = form.getShippingName().trim();
        String newShippingPostalCode = form.getShippingPostalCode().trim();
        String newShippingPrefecture = form.getShippingPrefecture().trim();
        String newShippingCity = form.getShippingCity().trim();
        String newShippingAddressLine = form.getShippingAddressLine().trim();
        String newShippingPhone = form.getShippingPhone().trim();

        boolean changed = !java.util.Objects.equals(
                order.getShippingName(),
                newShippingName)
                || !java.util.Objects.equals(
                        order.getShippingPostalCode(),
                        newShippingPostalCode)
                || !java.util.Objects.equals(
                        order.getShippingPrefecture(),
                        newShippingPrefecture)
                || !java.util.Objects.equals(
                        order.getShippingCity(),
                        newShippingCity)
                || !java.util.Objects.equals(
                        order.getShippingAddressLine(),
                        newShippingAddressLine)
                || !java.util.Objects.equals(
                        order.getShippingPhone(),
                        newShippingPhone);

        if (!changed) {
            return false;
        }

        orderShippingAddressHistoryService.record(
                order,
                OrderShippingAddressHistoryActorType.USER,
                userId,
                username,
                order.getShippingName(),
                order.getShippingPostalCode(),
                order.getShippingPrefecture(),
                order.getShippingCity(),
                order.getShippingAddressLine(),
                order.getShippingPhone(),
                newShippingName,
                newShippingPostalCode,
                newShippingPrefecture,
                newShippingCity,
                newShippingAddressLine,
                newShippingPhone);

        order.setShippingAddress(
                newShippingName,
                newShippingPostalCode,
                newShippingPrefecture,
                newShippingCity,
                newShippingAddressLine,
                newShippingPhone);

        return true;
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

    @Transactional
    public boolean changeItemsForUser(
            Long orderId,
            Long userId,
            String username,
            OrderItemChangeForm form) {

        Order order = orderRepository
                .findByIdAndUserIdForUpdate(
                        orderId,
                        userId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        // items / charges をトランザクション内で読み込む
        order.getItems().size();
        order.getCharges().size();

        LocalDateTime now = LocalDateTime.now(clock);

        validateItemChangeOrder(
                order,
                form,
                now);

        Map<Long, Integer> newQuantities = toItemQuantityMap(form);

        boolean changed = order.getItems()
                .stream()
                .anyMatch(item -> item.getQuantity() != newQuantities.get(item.getId()));

        if (!changed) {
            return false;
        }

        List<OrderItemSnapshot> beforeItems = order.getItems()
                .stream()
                .map(OrderItemSnapshot::from)
                .toList();

        List<OrderChargeSnapshot> beforeCharges = order.getCharges()
                .stream()
                .map(OrderChargeSnapshot::from)
                .toList();

        OrderAmountSnapshot beforeAmount = OrderAmountSnapshot.from(order);

        OrderCharge shippingCharge = order.getCharges()
                .stream()
                .filter(charge -> charge.getChargeType() == OrderChargeType.SHIPPING)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "注文の送料情報が見つかりません。"));

        ChargeTaxSnapshot shippingTaxSnapshot = ChargeTaxSnapshot.from(shippingCharge);

        applyItemQuantityChanges(
                order,
                newQuantities);

        OrderPricingContext pricingContext = createPricingContext(order);

        OrderAmount newAmount = orderAmountCalculator.calculate(
                pricingContext,
                shippingTaxSnapshot);

        order.applyAmount(newAmount);

        updateOrderCharges(
                order,
                newAmount);

        List<OrderItemSnapshot> afterItems = order.getItems()
                .stream()
                .map(OrderItemSnapshot::from)
                .toList();

        List<OrderChargeSnapshot> afterCharges = order.getCharges()
                .stream()
                .map(OrderChargeSnapshot::from)
                .toList();

        OrderAmountSnapshot afterAmount = OrderAmountSnapshot.from(order);

        restoreChangedItemStock(
                order,
                beforeItems,
                afterItems);

        OrderContentChangeHistory history = createContentChangeHistory(
                order,
                userId,
                username,
                now,
                beforeItems,
                afterItems,
                beforeCharges,
                afterCharges,
                beforeAmount,
                afterAmount);

        orderContentChangeHistoryRepository.save(history);

        order.incrementContentRevision();

        return true;
    }

    @Transactional
    public void cancelOrderForPaymentFailure(
            Long orderId) {

        Order order = findOrderForUpdate(orderId);

        if (order.getStatus() == OrderStatus.CANCELLED) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);

        cancelAndRestoreStock(
                order,
                OrderStatusHistoryActorType.SYSTEM,
                null,
                null,
                "カード与信失敗による注文キャンセル",
                now);
    }

    public List<AdminAssigneeActionRequiredSummary> getActionRequiredOrderCountsByAssignee() {

        LocalDate today = LocalDate.now();

        LocalDateTime threeDaysCutoffExclusive = resolveElapsedCutoffExclusive(3, today);

        LocalDateTime sevenDaysCutoffExclusive = resolveElapsedCutoffExclusive(7, today);

        return orderRepository
                .findActionRequiredOrderCountsByAssignee(
                        threeDaysCutoffExclusive,
                        sevenDaysCutoffExclusive)
                .stream()
                .map(projection -> new AdminAssigneeActionRequiredSummary(
                        projection.getAdminAccountId(),
                        projection.getUsername(),
                        projection.getEnabled(),
                        projection.getOrderCount(),
                        projection.getThreeDaysOrMoreCount(),
                        projection.getSevenDaysOrMoreCount()))
                .toList();
    }

    public boolean isWithinModificationPeriod(Order order) {
        return order.isWithinModificationPeriod(
                LocalDateTime.now(clock));
    }

    public boolean canCancelByUser(Order order) {
        return order.canCancelByUser(
                LocalDateTime.now(clock));
    }

    public boolean canChangeShippingAddress(Order order) {
        return order.canChangeShippingAddress(
                LocalDateTime.now(clock));
    }

    public boolean canChangeItemsByUser(Order order) {
        return order.canChangeItemsByUser(
                LocalDateTime.now(clock));
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

    private Long resolveAssignedAdminAccountId(
            AdminOrderAssigneeFilter assigneeFilter,
            Long selectedAdminAccountId,
            Long loginAdminAccountId) {

        return switch (assigneeFilter) {
            case ALL, UNASSIGNED -> null;
            case ME -> loginAdminAccountId;
            case SPECIFIC -> selectedAdminAccountId;
        };
    }

    private void validateItemChangeOrder(
            Order order,
            OrderItemChangeForm form,
            LocalDateTime now) {

        if (!order.canChangeItemsByUser(now)) {
            throw new InvalidOrderStatusException(
                    "この注文は現在、注文内容を変更できません。");
        }

        if (form.getContentRevision() == null
                || form.getContentRevision() != order.getContentRevision()) {

            throw new IllegalStateException(
                    "注文内容が変更されています。最新の注文内容を確認して、もう一度操作してください。");
        }

        if (form.getItems() == null
                || form.getItems().size() != order.getItems().size()) {

            throw new IllegalArgumentException(
                    "注文商品の指定が正しくありません。");
        }

        Map<Long, OrderItem> orderItemsById = new HashMap<>();

        for (OrderItem item : order.getItems()) {
            orderItemsById.put(item.getId(), item);
        }

        Set<Long> submittedIds = new HashSet<>();
        int totalQuantity = 0;

        for (OrderItemChangeForm.Item submittedItem : form.getItems()) {

            if (submittedItem == null
                    || submittedItem.getOrderItemId() == null
                    || submittedItem.getQuantity() == null) {

                throw new IllegalArgumentException(
                        "注文商品の指定が正しくありません。");
            }

            Long orderItemId = submittedItem.getOrderItemId();

            if (!submittedIds.add(orderItemId)) {
                throw new IllegalArgumentException(
                        "同じ注文商品が重複して指定されています。");
            }

            OrderItem currentItem = orderItemsById.get(orderItemId);

            if (currentItem == null) {
                throw new IllegalArgumentException(
                        "注文商品の指定が正しくありません。");
            }

            int newQuantity = submittedItem.getQuantity();

            if (newQuantity < 0) {
                throw new IllegalArgumentException(
                        "数量は0以上で指定してください。");
            }

            if (newQuantity > currentItem.getQuantity()) {
                throw new IllegalArgumentException(
                        "現在の注文数量を超える数量には変更できません。");
            }

            totalQuantity += newQuantity;
        }

        if (totalQuantity == 0) {
            throw new IllegalArgumentException(
                    "注文には1点以上の商品が必要です。"
                            + "注文全体を取り消す場合は注文キャンセルを選択してください。");
        }
    }

    private OrderItemChangePreview buildItemChangePreview(
            Order order,
            OrderItemChangeForm form) {

        Map<Long, Integer> quantities = new HashMap<>();

        for (OrderItemChangeForm.Item item : form.getItems()) {
            quantities.put(
                    item.getOrderItemId(),
                    item.getQuantity());
        }

        OrderPricingContext context = new OrderPricingContext();

        List<OrderItemChangePreview.Item> previewItems = new ArrayList<>();

        for (OrderItem item : order.getItems()) {

            int newQuantity = quantities.get(item.getId());

            previewItems.add(
                    new OrderItemChangePreview.Item(
                            item.getId(),
                            item.getProductName(),
                            item.getPrice(),
                            item.getQuantity(),
                            newQuantity,
                            item.getSubtotal(),
                            item.getPrice() * newQuantity));

            if (newQuantity == 0) {
                continue;
            }

            context.addItem(
                    item.getPrice(),
                    newQuantity,
                    item.getTaxCategoryId(),
                    item.getTaxCategoryCode(),
                    item.getTaxCategoryName(),
                    item.getTaxRate());
        }

        OrderCharge shippingCharge = order.getCharges()
                .stream()
                .filter(charge -> charge.getChargeType() == OrderChargeType.SHIPPING)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "注文の送料情報が見つかりません。"));

        OrderAmount newAmount = orderAmountCalculator.calculate(
                context,
                ChargeTaxSnapshot.from(shippingCharge));

        return new OrderItemChangePreview(
                previewItems,
                order.getItemSubtotal(),
                newAmount.itemSubtotal(),
                order.getChargeTotal(),
                newAmount.chargeTotal(),
                order.getTaxAmount(),
                newAmount.taxAmount(),
                order.getTotalAmount(),
                newAmount.totalAmount());
    }

    private Map<Long, Integer> toItemQuantityMap(
            OrderItemChangeForm form) {

        Map<Long, Integer> quantities = new HashMap<>();

        for (OrderItemChangeForm.Item item : form.getItems()) {
            quantities.put(
                    item.getOrderItemId(),
                    item.getQuantity());
        }

        return quantities;
    }

    private void applyItemQuantityChanges(
            Order order,
            Map<Long, Integer> newQuantities) {

        List<OrderItem> currentItems = new ArrayList<>(order.getItems());

        for (OrderItem item : currentItems) {

            int newQuantity = newQuantities.get(item.getId());

            if (newQuantity == 0) {
                order.removeItem(item);
                continue;
            }

            if (newQuantity != item.getQuantity()) {
                item.setQuantity(newQuantity);
            }
        }
    }

    private OrderPricingContext createPricingContext(
            Order order) {

        OrderPricingContext context = new OrderPricingContext();

        for (OrderItem item : order.getItems()) {

            context.addItem(
                    item.getPrice(),
                    item.getQuantity(),
                    item.getTaxCategoryId(),
                    item.getTaxCategoryCode(),
                    item.getTaxCategoryName(),
                    item.getTaxRate());
        }

        return context;
    }

    private void updateOrderCharges(
            Order order,
            OrderAmount amount) {

        for (OrderChargeAmount calculatedCharge : amount.charges()) {

            OrderCharge existingCharge = order.getCharges()
                    .stream()
                    .filter(charge -> charge.getChargeType() == calculatedCharge.chargeType())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "注文の付帯料金情報が見つかりません。"));

            existingCharge.updateAmount(
                    calculatedCharge.amount());
        }
    }

    private void restoreChangedItemStock(
            Order order,
            List<OrderItemSnapshot> beforeItems,
            List<OrderItemSnapshot> afterItems) {

        Map<Long, OrderItemSnapshot> afterByOrderItemId = afterItems.stream()
                .collect(Collectors.toMap(
                        OrderItemSnapshot::orderItemId,
                        Function.identity()));

        List<OrderItemSnapshot> sortedBeforeItems = beforeItems.stream()
                .sorted(Comparator
                        .comparing(OrderItemSnapshot::productId)
                        .thenComparing(OrderItemSnapshot::orderItemId))
                .toList();

        for (OrderItemSnapshot before : sortedBeforeItems) {

            OrderItemSnapshot after = afterByOrderItemId.get(
                    before.orderItemId());

            int newQuantity = after == null
                    ? 0
                    : after.quantity();

            int restoreQuantity = before.quantity() - newQuantity;

            if (restoreQuantity <= 0) {
                continue;
            }

            inventoryService.restoreForOrderItemChange(
                    before.productId(),
                    restoreQuantity,
                    order.getId());
        }
    }

    private OrderContentChangeHistory createContentChangeHistory(
            Order order,
            Long userId,
            String username,
            LocalDateTime changedAt,
            List<OrderItemSnapshot> beforeItems,
            List<OrderItemSnapshot> afterItems,
            List<OrderChargeSnapshot> beforeCharges,
            List<OrderChargeSnapshot> afterCharges,
            OrderAmountSnapshot beforeAmount,
            OrderAmountSnapshot afterAmount) {

        OrderContentChangeHistory history = new OrderContentChangeHistory(
                order,
                OrderContentChangeSource.CUSTOMER,
                null,
                OrderContentChangeHistoryActorType.USER,
                userId,
                username,
                beforeAmount.itemSubtotal(),
                afterAmount.itemSubtotal(),
                beforeAmount.chargeTotal(),
                afterAmount.chargeTotal(),
                beforeAmount.taxAmount(),
                afterAmount.taxAmount(),
                beforeAmount.totalAmount(),
                afterAmount.totalAmount(),
                changedAt);

        addItemChangeHistories(
                history,
                beforeItems,
                afterItems);

        addChargeChangeHistories(
                history,
                beforeCharges,
                afterCharges);

        return history;
    }

    private void addItemChangeHistories(
            OrderContentChangeHistory history,
            List<OrderItemSnapshot> beforeItems,
            List<OrderItemSnapshot> afterItems) {

        Map<Long, OrderItemSnapshot> afterById = afterItems.stream()
                .collect(Collectors.toMap(
                        OrderItemSnapshot::orderItemId,
                        Function.identity()));

        for (OrderItemSnapshot before : beforeItems) {

            OrderItemSnapshot after = afterById.get(before.orderItemId());

            if (after == null) {

                history.addItem(
                        OrderContentChangeItem.removed(
                                before.orderItemId(),
                                before.productId(),
                                before.productName(),
                                before.price(),
                                before.quantity(),
                                before.subtotal(),
                                before.taxCategoryId(),
                                before.taxCategoryCode(),
                                before.taxCategoryName(),
                                before.taxRate()));

                continue;
            }

            if (before.quantity() == after.quantity()) {
                continue;
            }

            history.addItem(
                    OrderContentChangeItem.updated(
                            before.orderItemId(),
                            before.productId(),
                            before.productName(),
                            before.price(),
                            before.quantity(),
                            after.quantity(),
                            before.subtotal(),
                            after.subtotal(),
                            before.taxCategoryId(),
                            before.taxCategoryCode(),
                            before.taxCategoryName(),
                            before.taxRate()));
        }
    }

    private void addChargeChangeHistories(
            OrderContentChangeHistory history,
            List<OrderChargeSnapshot> beforeCharges,
            List<OrderChargeSnapshot> afterCharges) {

        Map<OrderChargeType, OrderChargeSnapshot> afterByType = afterCharges.stream()
                .collect(Collectors.toMap(
                        OrderChargeSnapshot::chargeType,
                        Function.identity()));

        for (OrderChargeSnapshot before : beforeCharges) {

            OrderChargeSnapshot after = afterByType.get(before.chargeType());

            if (after == null) {
                continue;
            }

            boolean changed = !Objects.equals(
                    before.name(),
                    after.name())
                    || before.amount() != after.amount()
                    || !Objects.equals(
                            before.taxCategoryId(),
                            after.taxCategoryId())
                    || !Objects.equals(
                            before.taxCategoryCode(),
                            after.taxCategoryCode())
                    || !Objects.equals(
                            before.taxCategoryName(),
                            after.taxCategoryName())
                    || !Objects.equals(
                            before.taxRate(),
                            after.taxRate())
                    || before.displayOrder() != after.displayOrder();

            if (!changed) {
                continue;
            }

            history.addCharge(
                    OrderContentChangeCharge.updated(
                            before.orderChargeId(),
                            before.chargeType(),
                            before.name(),
                            after.name(),
                            before.amount(),
                            after.amount(),
                            before.taxCategoryId(),
                            after.taxCategoryId(),
                            before.taxCategoryCode(),
                            after.taxCategoryCode(),
                            before.taxCategoryName(),
                            after.taxCategoryName(),
                            before.taxRate(),
                            after.taxRate(),
                            before.displayOrder(),
                            after.displayOrder()));
        }
    }

}
