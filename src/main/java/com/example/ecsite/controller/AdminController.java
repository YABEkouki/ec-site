package com.example.ecsite.controller;

import org.springframework.dao.DataAccessException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.example.ecsite.dto.ActionRequiredAgingSummary;
import com.example.ecsite.entity.OrderHandlingStatus;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.AdminPaymentDiscrepancyService;
import com.example.ecsite.service.OrderService;
import com.example.ecsite.service.ProductService;
import com.example.ecsite.service.orderchange.AdminOrderHoldViewService;

@Controller
public class AdminController {

    private final OrderService orderService;
    private final ProductService productService;
    private final AdminPaymentDiscrepancyService adminPaymentDiscrepancyService;
    private final AdminOrderHoldViewService orderHoldViewService;

    public AdminController(
            OrderService orderService,
            ProductService productService,
            AdminPaymentDiscrepancyService adminPaymentDiscrepancyService,
            AdminOrderHoldViewService orderHoldViewService) {

        this.orderService = orderService;
        this.productService = productService;
        this.adminPaymentDiscrepancyService = adminPaymentDiscrepancyService;
        this.orderHoldViewService = orderHoldViewService;
    }

    @GetMapping("/admin")
    public String index(
            @AuthenticationPrincipal AdminUserDetails loginUser,
            Model model) {

        model.addAttribute("orderedCount",
                orderService.countOrdersByStatus(OrderStatus.ORDERED));

        model.addAttribute("paidCount",
                orderService.countOrdersByStatus(OrderStatus.PAID));

        model.addAttribute("shippedCount",
                orderService.countOrdersByStatus(OrderStatus.SHIPPED));

        model.addAttribute("cancelledCount",
                orderService.countOrdersByStatus(OrderStatus.CANCELLED));

        model.addAttribute("normalHandlingCount",
                orderService.countOrdersByHandlingStatus(OrderHandlingStatus.NONE));

        model.addAttribute("needsActionCount",
                orderService.countOrdersByHandlingStatus(OrderHandlingStatus.NEEDS_ACTION));

        model.addAttribute("inProgressCount",
                orderService.countOrdersByHandlingStatus(OrderHandlingStatus.IN_PROGRESS));

        model.addAttribute("resolvedCount",
                orderService.countOrdersByHandlingStatus(OrderHandlingStatus.RESOLVED));

        long myAssignedOrderCount = orderService.countMyAssignedActionRequiredOrders(loginUser.getId());

        model.addAttribute(
                "myAssignedOrderCount",
                myAssignedOrderCount);

        long unassignedActionRequiredOrderCount = orderService.countUnassignedActionRequiredOrders();

        model.addAttribute(
                "unassignedActionRequiredOrderCount",
                unassignedActionRequiredOrderCount);

        ActionRequiredAgingSummary unassignedAgingSummary = orderService.getUnassignedActionRequiredAgingSummary();

        model.addAttribute(
                "unassignedActionRequiredAgingSummary",
                unassignedAgingSummary);

        ActionRequiredAgingSummary myAssignedAgingSummary = orderService.getMyAssignedActionRequiredAgingSummary(
                loginUser.getId());

        model.addAttribute(
                "myAssignedActionRequiredAgingSummary",
                myAssignedAgingSummary);

        ActionRequiredAgingSummary agingSummary = orderService.getActionRequiredAgingSummary();

        model.addAttribute(
                "actionRequiredAgingSummary",
                agingSummary);

        model.addAttribute(
                "assigneeActionRequiredSummaries",
                orderService.getActionRequiredOrderCountsByAssignee());

        int lowStockThreshold = 5;

        model.addAttribute(
                "lowStockProducts",
                productService.findLowStockProducts(
                        lowStockThreshold));

        model.addAttribute(
                "lowStockThreshold",
                lowStockThreshold);

        model.addAttribute(
                "openPaymentDiscrepancyCount",
                adminPaymentDiscrepancyService.countOpen());

        // The hold summary must never prevent the dashboard itself from rendering.
        AdminOrderHoldViewService.Summary orderHoldSummary = null;
        try {
            orderHoldSummary = orderHoldViewService.summary();
        } catch (DataAccessException unavailable) {
            // Rendered as "unavailable"; the dedicated hold list reports the failure itself.
        }
        model.addAttribute("orderHoldSummary", orderHoldSummary);

        // Feature133-C: like the hold summary, an unavailable count must not break the dashboard.
        Long customerNotificationActionCount = null;
        try {
            if (customerNotifications != null) customerNotificationActionCount = customerNotifications.countActionRequired();
        } catch (DataAccessException unavailable) {
            // Rendered as "unavailable".
        }
        model.addAttribute("customerNotificationActionCount", customerNotificationActionCount);

        // Feature135-C1: card authorization expiry counts; unavailable counts must not break the dashboard.
        com.example.ecsite.service.payment.AdminAuthorizationExpiryQueries.Summary authorizationExpirySummary = null;
        try {
            if (authorizationExpiries != null) authorizationExpirySummary = authorizationExpiries.summary();
        } catch (DataAccessException unavailable) {
            // Rendered as "unavailable".
        }
        model.addAttribute("authorizationExpirySummary", authorizationExpirySummary);

        // Feature136-C3: emergency shipping counts; unavailable counts must not break the dashboard.
        com.example.ecsite.service.emergency.AdminEmergencyShippingQueries.Summary emergencyShippingSummary = null;
        try {
            if (emergencyShippings != null) emergencyShippingSummary = emergencyShippings.summary();
        } catch (DataAccessException unavailable) {
            // Rendered as "unavailable".
        }
        model.addAttribute("emergencyShippingSummary", emergencyShippingSummary);

        return "admin/index";
    }

    private com.example.ecsite.service.emergency.AdminEmergencyShippingQueries emergencyShippings;

    /** Feature136-C3. Spring always injects it; hand-built unit fixtures predate it. */
    @org.springframework.beans.factory.annotation.Autowired
    void setEmergencyShippings(com.example.ecsite.service.emergency.AdminEmergencyShippingQueries emergencyShippings) {
        this.emergencyShippings = emergencyShippings;
    }

    private com.example.ecsite.service.payment.AdminAuthorizationExpiryQueries authorizationExpiries;

    /** Feature135-C1. Spring always injects it; hand-built unit fixtures predate it. */
    @org.springframework.beans.factory.annotation.Autowired
    void setAuthorizationExpiries(com.example.ecsite.service.payment.AdminAuthorizationExpiryQueries authorizationExpiries) {
        this.authorizationExpiries = authorizationExpiries;
    }

    private com.example.ecsite.service.notification.AdminCustomerNotificationQueries customerNotifications;

    /** Feature133-C. Spring always injects it; hand-built unit fixtures predate it. */
    @org.springframework.beans.factory.annotation.Autowired
    void setCustomerNotifications(com.example.ecsite.service.notification.AdminCustomerNotificationQueries customerNotifications) {
        this.customerNotifications = customerNotifications;
    }
}