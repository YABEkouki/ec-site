package com.example.ecsite.controller;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import com.example.ecsite.dto.AdminActionRequiredOrderDto;
import com.example.ecsite.entity.AdminAccount;
import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderHandlingStatus;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.form.ActionRequiredOrderSort;
import com.example.ecsite.form.AdminActionRequiredOrderSearchForm;
import com.example.ecsite.form.AdminOrderAssigneeFilter;
import com.example.ecsite.form.AdminOrderHandlingStatusForm;
import com.example.ecsite.form.AdminOrderNoteForm;
import com.example.ecsite.form.AdminOrderSearchForm;
import com.example.ecsite.form.AdminOrderStatusChangeForm;
import com.example.ecsite.payment.CancellationResult;
import com.example.ecsite.payment.CancellationResultStatus;
import com.example.ecsite.payment.CaptureResult;
import com.example.ecsite.payment.CaptureResultStatus;
import com.example.ecsite.payment.PaymentGatewayException;
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.AdminAccountService;
import com.example.ecsite.service.OrderAssigneeHistoryService;
import com.example.ecsite.service.OrderContentChangeHistoryService;
import com.example.ecsite.service.OrderCsvService;
import com.example.ecsite.service.OrderHandlingStatusHistoryService;
import com.example.ecsite.service.OrderNoteService;
import com.example.ecsite.service.OrderService;
import com.example.ecsite.service.OrderShippingAddressHistoryService;
import com.example.ecsite.service.OrderStatusHistoryService;
import com.example.ecsite.service.payment.AdminPaymentCancellationService;
import com.example.ecsite.service.payment.PaymentCaptureService;
import com.example.ecsite.service.payment.PaymentService;
import com.example.ecsite.util.AdminReturnUrlHelper;

import jakarta.validation.Valid;

@Controller
@RequestMapping("/admin/orders")
public class AdminOrderController {

    private final OrderService orderService;
    private final OrderCsvService orderCsvService;
    private final OrderStatusHistoryService orderStatusHistoryService;
    private final OrderNoteService orderNoteService;
    private final OrderHandlingStatusHistoryService orderHandlingStatusHistoryService;
    private final OrderShippingAddressHistoryService orderShippingAddressHistoryService;
    private final OrderAssigneeHistoryService orderAssigneeHistoryService;
    private final OrderContentChangeHistoryService orderContentChangeHistoryService;
    private final AdminAccountService adminAccountService;
    private final PaymentService paymentService;
    private final PaymentCaptureService paymentCaptureService;
    private final AdminPaymentCancellationService adminPaymentCancellationService;

    public AdminOrderController(
            OrderService orderService,
            OrderCsvService orderCsvService,
            OrderStatusHistoryService orderStatusHistoryService,
            OrderNoteService orderNoteService,
            OrderHandlingStatusHistoryService orderHandlingStatusHistoryService,
            OrderShippingAddressHistoryService orderShippingAddressHistoryService,
            OrderAssigneeHistoryService orderAssigneeHistoryService,
            OrderContentChangeHistoryService orderContentChangeHistoryService,
            AdminAccountService adminAccountService,
            PaymentService paymentService,
            PaymentCaptureService paymentCaptureService,
            AdminPaymentCancellationService adminPaymentCancellationService) {

        this.orderService = orderService;
        this.orderCsvService = orderCsvService;
        this.orderStatusHistoryService = orderStatusHistoryService;
        this.orderNoteService = orderNoteService;
        this.orderHandlingStatusHistoryService = orderHandlingStatusHistoryService;
        this.orderShippingAddressHistoryService = orderShippingAddressHistoryService;
        this.orderAssigneeHistoryService = orderAssigneeHistoryService;
        this.orderContentChangeHistoryService = orderContentChangeHistoryService;
        this.adminAccountService = adminAccountService;
        this.paymentService = paymentService;
        this.paymentCaptureService = paymentCaptureService;
        this.adminPaymentCancellationService = adminPaymentCancellationService;
    }

    @GetMapping
    public String list(
            @ModelAttribute("searchForm") AdminOrderSearchForm searchForm,
            @AuthenticationPrincipal AdminUserDetails loginUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model) {

        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, 100);

        Page<Order> orderPage = orderService.searchOrders(
                searchForm,
                loginUser.getId(),
                safePage,
                safeSize);

        UriComponentsBuilder returnUrlBuilder = UriComponentsBuilder.fromPath("/admin/orders");

        if (searchForm.getOrderId() != null) {
            returnUrlBuilder.queryParam("orderId", searchForm.getOrderId());
        }

        if (searchForm.getUserId() != null) {
            returnUrlBuilder.queryParam("userId", searchForm.getUserId());
        }

        if (searchForm.getFrom() != null) {
            returnUrlBuilder.queryParam("from", searchForm.getFrom());
        }

        if (searchForm.getTo() != null) {
            returnUrlBuilder.queryParam("to", searchForm.getTo());
        }

        if (searchForm.getStatus() != null) {
            returnUrlBuilder.queryParam("status", searchForm.getStatus());
        }

        if (searchForm.getHandlingStatus() != null) {
            returnUrlBuilder.queryParam(
                    "handlingStatus",
                    searchForm.getHandlingStatus());
        }

        if (searchForm.getAssigneeFilter() != null) {
            returnUrlBuilder.queryParam(
                    "assigneeFilter",
                    searchForm.getAssigneeFilter());
        }

        if (searchForm.getAssignedAdminAccountId() != null) {
            returnUrlBuilder.queryParam(
                    "assignedAdminAccountId",
                    searchForm.getAssignedAdminAccountId());
        }

        if (safePage > 0) {
            returnUrlBuilder.queryParam("page", safePage);
        }

        returnUrlBuilder.queryParam("size", safeSize);

        String returnUrl = returnUrlBuilder
                .build()
                .encode()
                .toUriString();

        model.addAttribute(
                "orders",
                orderPage.getContent());

        model.addAttribute(
                "orderPage",
                orderPage);

        model.addAttribute(
                "statuses",
                OrderStatus.values());

        model.addAttribute(
                "handlingStatuses",
                OrderHandlingStatus.values());

        model.addAttribute(
                "assigneeFilters",
                AdminOrderAssigneeFilter.values());

        model.addAttribute(
                "adminAccounts",
                adminAccountService.findAll());

        model.addAttribute(
                "returnUrl",
                returnUrl);

        return "admin/orders/list";
    }

    @GetMapping("/{id}")
    public String detail(
            @PathVariable Long id,
            @RequestParam(required = false) String returnUrl,
            Model model) {

        String safeReturnUrl = AdminReturnUrlHelper.resolveOrderListReturnUrl(returnUrl);

        Order order = orderService.findOrderWithItems(id);

        boolean canCaptureForShipment = order.getStatus() == OrderStatus.ORDERED
                && paymentService.canCaptureForShipment(id);

        model.addAttribute(
                "canCaptureForShipment",
                canCaptureForShipment);

        model.addAttribute(
                "order",
                order);

        model.addAttribute(
                "statusHistories",
                orderStatusHistoryService.findByOrderId(id));

        model.addAttribute(
                "orderNotes",
                orderNoteService.findByOrderId(id));

        model.addAttribute(
                "orderNoteForm",
                new AdminOrderNoteForm());

        model.addAttribute(
                "handlingStatuses",
                OrderHandlingStatus.values());

        model.addAttribute(
                "handlingStatusHistories",
                orderHandlingStatusHistoryService.findByOrderId(id));

        model.addAttribute(
                "shippingAddressHistories",
                orderShippingAddressHistoryService.findByOrderId(id));

        model.addAttribute(
                "assigneeHistories",
                orderAssigneeHistoryService.findByOrderId(id));

        model.addAttribute(
                "returnUrl",
                safeReturnUrl);

        AdminOrderHandlingStatusForm handlingStatusForm = new AdminOrderHandlingStatusForm();

        handlingStatusForm.setHandlingStatus(
                order.getHandlingStatus());

        if (order.getAssignedAdminAccount() != null) {
            handlingStatusForm.setAssignedAdminAccountId(
                    order.getAssignedAdminAccount().getId());
        }

        List<AdminAccount> assignableAdmins = new java.util.ArrayList<>(
                adminAccountService.findAllEnabled());

        AdminAccount currentAssignedAdmin = order.getAssignedAdminAccount();

        if (currentAssignedAdmin != null
                && !currentAssignedAdmin.isEnabled()
                && assignableAdmins.stream()
                        .noneMatch(admin -> admin.getId().equals(
                                currentAssignedAdmin.getId()))) {

            assignableAdmins.add(currentAssignedAdmin);
        }

        model.addAttribute(
                "handlingStatusForm",
                handlingStatusForm);

        model.addAttribute(
                "assignableAdmins",
                assignableAdmins);

        return "admin/orders/detail";
    }

    @GetMapping("/{id}/content-changes")
    public String contentChangeHistory(
            @PathVariable Long id,
            @RequestParam(required = false) String returnUrl,
            Model model) {

        String safeReturnUrl = AdminReturnUrlHelper.resolveOrderListReturnUrl(returnUrl);

        Order order = orderService.findOrderWithItems(id);

        model.addAttribute(
                "order",
                order);

        model.addAttribute(
                "contentChangeHistories",
                orderContentChangeHistoryService.findByOrderId(id));

        model.addAttribute(
                "returnUrl",
                safeReturnUrl);

        return "admin/orders/content-change-history";
    }

    @PostMapping("/{id}/pay")
    public String markAsPaid(
            @PathVariable Long id,
            @Valid @ModelAttribute AdminOrderStatusChangeForm form,
            BindingResult bindingResult,
            @RequestParam(required = false) String returnUrl,
            @AuthenticationPrincipal AdminUserDetails loginUser,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "変更理由・備考は500文字以内で入力してください。");

            return redirectToDetail(
                    id,
                    returnUrl);
        }

        try {
            if (paymentService.canCaptureForShipment(id)) {
                throw new InvalidOrderStatusException(
                        "カード与信済みの注文は、売上確定して発送してください。");
            }

            orderService.markAsPaid(
                    id,
                    loginUser.getId(),
                    loginUser.getUsername(),
                    form.getInternalNote());

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "注文を支払済みに変更しました。");

        } catch (InvalidOrderStatusException e) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    e.getMessage());
        }

        return redirectToDetail(
                id,
                returnUrl);
    }

    @PostMapping("/{id}/ship")
    public String markAsShipped(
            @PathVariable Long id,
            @Valid @ModelAttribute AdminOrderStatusChangeForm form,
            BindingResult bindingResult,
            @RequestParam(required = false) String returnUrl,
            @AuthenticationPrincipal AdminUserDetails loginUser,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "変更理由・備考は500文字以内で入力してください。");

            return redirectToDetail(
                    id,
                    returnUrl);
        }

        try {
            orderService.markAsShipped(
                    id,
                    loginUser.getId(),
                    loginUser.getUsername(),
                    form.getInternalNote());

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "注文を発送済みに変更しました。");

        } catch (InvalidOrderStatusException e) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    e.getMessage());
        }

        return redirectToDetail(
                id,
                returnUrl);
    }

    @PostMapping("/{id}/capture-and-ship")
    public String captureAndShip(
            @PathVariable Long id,
            @Valid @ModelAttribute AdminOrderStatusChangeForm form,
            BindingResult bindingResult,
            @RequestParam(required = false) String returnUrl,
            @AuthenticationPrincipal AdminUserDetails loginUser,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "変更理由・備考は500文字以内で入力してください。");

            return redirectToDetail(
                    id,
                    returnUrl);
        }

        try {
            CaptureResult result = paymentCaptureService.captureForShipment(
                    id,
                    loginUser.getId(),
                    loginUser.getUsername(),
                    form.getInternalNote());

            if (result.status() == CaptureResultStatus.CAPTURED) {

                redirectAttributes.addFlashAttribute(
                        "successMessage",
                        "売上を確定し、注文を発送済みに変更しました。");

            } else if (result.status() == CaptureResultStatus.FAILED) {

                redirectAttributes.addFlashAttribute(
                        "errorMessage",
                        "売上確定に失敗したため、注文は発送済みに変更していません。");

            } else {

                redirectAttributes.addFlashAttribute(
                        "errorMessage",
                        "売上確定結果を確認中です。"
                                + "注文はまだ発送済みに変更していません。");
            }

        } catch (InvalidOrderStatusException
                | IllegalStateException e) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    e.getMessage());

        } catch (PaymentGatewayException e) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "売上確定結果を確認できませんでした。"
                            + "注文はまだ発送済みに変更していません。");
        }

        return redirectToDetail(
                id,
                returnUrl);
    }

    @PostMapping("/{id}/cancel")
    public String cancel(
            @PathVariable Long id,
            @Valid @ModelAttribute AdminOrderStatusChangeForm form,
            BindingResult bindingResult,
            @RequestParam(required = false) String returnUrl,
            @AuthenticationPrincipal AdminUserDetails loginUser,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "変更理由・備考は500文字以内で入力してください。");

            return redirectToDetail(
                    id,
                    returnUrl);
        }

        try {
            if (paymentService.requiresAuthorizationCancellation(id)) {

                CancellationResult result = adminPaymentCancellationService.cancel(
                        id,
                        loginUser.getId(),
                        loginUser.getUsername(),
                        form.getInternalNote());

                if (result.status() == CancellationResultStatus.CANCELLED) {

                    redirectAttributes.addFlashAttribute(
                            "successMessage",
                            "決済与信を取り消し、注文をキャンセルしました。");

                } else {

                    redirectAttributes.addFlashAttribute(
                            "errorMessage",
                            "決済取消結果を確認中です。"
                                    + "注文はまだキャンセルしていません。");
                }

            } else {
                orderService.cancelOrder(
                        id,
                        loginUser.getId(),
                        loginUser.getUsername(),
                        form.getInternalNote());

                redirectAttributes.addFlashAttribute(
                        "successMessage",
                        "注文をキャンセルしました。");
            }

        } catch (InvalidOrderStatusException
                | IllegalStateException e) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    e.getMessage());
        } catch (PaymentGatewayException e) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "決済取消結果を確認できませんでした。"
                            + "注文はまだキャンセルしていません。");
        }

        return redirectToDetail(
                id,
                returnUrl);
    }

    @GetMapping("/csv")
    public ResponseEntity<byte[]> csv(
            @ModelAttribute("searchForm") AdminOrderSearchForm searchForm,
            @AuthenticationPrincipal AdminUserDetails loginUser) {

        List<Order> orders = orderService.searchAllOrders(
                searchForm,
                loginUser.getId());

        byte[] csvBytes = orderCsvService.createCsv(orders);

        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_TYPE,
                        "text/csv;charset=UTF-8")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"orders.csv\"")
                .body(csvBytes);
    }

    @PostMapping("/{id}/notes")
    public String addNote(
            @PathVariable Long id,
            @Valid @ModelAttribute AdminOrderNoteForm form,
            BindingResult bindingResult,
            @RequestParam(required = false) String returnUrl,
            @AuthenticationPrincipal AdminUserDetails loginUser,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {

            String errorMessage = bindingResult.getFieldError("note") != null
                    ? bindingResult.getFieldError("note").getDefaultMessage()
                    : "メモの入力内容を確認してください。";

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    errorMessage);

            return redirectToDetail(
                    id,
                    returnUrl);
        }

        orderNoteService.addNote(
                id,
                form.getNote(),
                loginUser.getId(),
                loginUser.getUsername());

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "注文メモを登録しました。");

        return redirectToDetail(
                id,
                returnUrl);
    }

    @PostMapping("/{id}/handling-status")
    public String changeHandlingStatus(
            @PathVariable Long id,
            @Valid @ModelAttribute AdminOrderHandlingStatusForm form,
            BindingResult bindingResult,
            @RequestParam(required = false) String returnUrl,
            @AuthenticationPrincipal AdminUserDetails loginUser,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "対応状況を選択してください。");

            return redirectToDetail(
                    id,
                    returnUrl);
        }

        try {

            boolean changed = orderService.changeHandlingStatus(
                    id,
                    form.getHandlingStatus(),
                    form.getAssignedAdminAccountId(),
                    loginUser.getId(),
                    loginUser.getUsername());

            if (changed) {
                redirectAttributes.addFlashAttribute(
                        "successMessage",
                        "対応状況・担当者を変更しました。");
            } else {
                redirectAttributes.addFlashAttribute(
                        "successMessage",
                        "対応状況・担当者は変更されていません。");
            }

        } catch (IllegalArgumentException e) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    e.getMessage());
        }

        return redirectToDetail(
                id,
                returnUrl);
    }

    @GetMapping("/action-required")
    public String actionRequiredOrders(
            @ModelAttribute("searchForm") AdminActionRequiredOrderSearchForm searchForm,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal AdminUserDetails loginUser,
            Model model) {

        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, 100);

        Page<AdminActionRequiredOrderDto> orderPage = orderService.searchActionRequiredOrderDetails(
                searchForm,
                loginUser.getId(),
                safePage,
                safeSize);

        model.addAttribute("orders", orderPage.getContent());
        model.addAttribute("orderPage", orderPage);
        model.addAttribute("statuses", OrderStatus.values());
        model.addAttribute(
                "handlingStatuses",
                List.of(
                        OrderHandlingStatus.NEEDS_ACTION,
                        OrderHandlingStatus.IN_PROGRESS));
        model.addAttribute(
                "actionRequiredOrderSorts",
                ActionRequiredOrderSort.values());
        model.addAttribute(
                "assigneeFilters",
                AdminOrderAssigneeFilter.values());

        model.addAttribute(
                "adminAccounts",
                adminAccountService.findAll());

        return "admin/orders/action-required";
    }

    @GetMapping("/my-assigned")
    public String myAssignedOrders(
            @ModelAttribute("searchForm") AdminActionRequiredOrderSearchForm searchForm,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal AdminUserDetails loginUser,
            Model model) {

        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, 100);

        Page<AdminActionRequiredOrderDto> orderPage = orderService.searchMyAssignedOrderDetails(
                searchForm,
                loginUser.getId(),
                safePage,
                safeSize);

        model.addAttribute(
                "orders",
                orderPage.getContent());

        model.addAttribute(
                "orderPage",
                orderPage);

        model.addAttribute(
                "statuses",
                OrderStatus.values());

        model.addAttribute(
                "handlingStatuses",
                List.of(
                        OrderHandlingStatus.NEEDS_ACTION,
                        OrderHandlingStatus.IN_PROGRESS));

        model.addAttribute(
                "actionRequiredOrderSorts",
                ActionRequiredOrderSort.values());

        return "admin/orders/my-assigned";
    }

    @GetMapping("/unassigned")
    public String unassignedOrders(
            @ModelAttribute("searchForm") AdminActionRequiredOrderSearchForm searchForm,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model) {

        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, 100);

        Page<AdminActionRequiredOrderDto> orderPage = orderService.searchUnassignedOrderDetails(
                searchForm,
                safePage,
                safeSize);

        model.addAttribute(
                "orders",
                orderPage.getContent());

        model.addAttribute(
                "orderPage",
                orderPage);

        model.addAttribute(
                "statuses",
                OrderStatus.values());

        model.addAttribute(
                "handlingStatuses",
                List.of(
                        OrderHandlingStatus.NEEDS_ACTION,
                        OrderHandlingStatus.IN_PROGRESS));

        model.addAttribute(
                "actionRequiredOrderSorts",
                ActionRequiredOrderSort.values());

        return "admin/orders/unassigned";
    }

    private String redirectToDetail(
            Long orderId,
            String returnUrl) {

        String safeReturnUrl = AdminReturnUrlHelper.resolveOrderListReturnUrl(returnUrl);

        String encodedReturnUrl = URLEncoder.encode(
                safeReturnUrl,
                StandardCharsets.UTF_8);

        return "redirect:/admin/orders/"
                + orderId
                + "?returnUrl="
                + encodedReturnUrl;
    }

}
