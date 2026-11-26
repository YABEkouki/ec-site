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
import com.example.ecsite.payment.PaymentSafetyMessages;
import com.example.ecsite.service.payment.PaymentSafetyStopException;
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

    private final OrderShippingPreparationViewFactory shippingPreparationViewFactory;
    private final OrderPaymentViewReadService paymentViewReader;
    private final com.example.ecsite.service.orderchange.AdminOldCancelRecoveryViewService recoveryViews;
    private final com.example.ecsite.service.orderchange.AdminOrderHoldViewService orderHoldViews;

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
            AdminPaymentCancellationService adminPaymentCancellationService,
            OrderShippingPreparationViewFactory shippingPreparationViewFactory,
            OrderPaymentViewReadService paymentViewReader, com.example.ecsite.service.orderchange.AdminOldCancelRecoveryViewService recoveryViews,
            com.example.ecsite.service.orderchange.AdminOrderHoldViewService orderHoldViews) {

        this.paymentViewReader = paymentViewReader;
        this.recoveryViews = recoveryViews;
        this.orderHoldViews = orderHoldViews;

        this.shippingPreparationViewFactory = shippingPreparationViewFactory;
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

    private com.example.ecsite.service.warehouse.OrderShipmentTrackingReader shipmentTracking;

    /** Feature132-C. Spring always injects it; hand-built unit fixtures predate it and show no tracking. */
    @org.springframework.beans.factory.annotation.Autowired
    void setShipmentTracking(com.example.ecsite.service.warehouse.OrderShipmentTrackingReader shipmentTracking) {
        this.shipmentTracking = shipmentTracking;
    }

    private com.example.ecsite.service.notification.AdminCustomerNotificationQueries customerNotifications;

    /** Feature133-C. Spring always injects it; hand-built unit fixtures predate it and show no notifications. */
    @org.springframework.beans.factory.annotation.Autowired
    void setCustomerNotifications(com.example.ecsite.service.notification.AdminCustomerNotificationQueries customerNotifications) {
        this.customerNotifications = customerNotifications;
    }

    private List<com.example.ecsite.service.notification.AdminCustomerNotificationQueries.Row> customerNotifications(Order order) {
        return customerNotifications == null ? List.of() : customerNotifications.byOrder(order.getId());
    }

    private com.example.ecsite.service.emergency.EmergencyShippingStore emergencyShipping;

    /** Feature136-A. Spring always injects it; hand-built unit fixtures predate it and show no emergency shipping. */
    @org.springframework.beans.factory.annotation.Autowired
    void setEmergencyShipping(com.example.ecsite.service.emergency.EmergencyShippingStore emergencyShipping) {
        this.emergencyShipping = emergencyShipping;
    }

    private com.example.ecsite.service.emergency.EmergencyShippingCsvService emergencyShippingCsv;

    /** Feature136-C2. Spring always injects it; hand-built unit fixtures predate it and show no CSV downloads. */
    @org.springframework.beans.factory.annotation.Autowired
    void setEmergencyShippingCsv(com.example.ecsite.service.emergency.EmergencyShippingCsvService emergencyShippingCsv) {
        this.emergencyShippingCsv = emergencyShippingCsv;
    }

    private com.example.ecsite.service.warehouse.WarehouseFileOutputStore warehouseFileOutputs;

    /** Feature137-C: the warehouse file output of an emergency-shipped order. Hand-built fixtures show none. */
    @org.springframework.beans.factory.annotation.Autowired
    void setWarehouseFileOutputs(com.example.ecsite.service.warehouse.WarehouseFileOutputStore warehouseFileOutputs) {
        this.warehouseFileOutputs = warehouseFileOutputs;
    }

    private com.example.ecsite.service.payment.AdminAuthorizationExpiryQueries authorizationExpiries;
    /** Feature135-A2. Spring always injects it; hand-built unit fixtures predate it and show no expiry. */
    @org.springframework.beans.factory.annotation.Autowired
    void setAuthorizationExpiries(com.example.ecsite.service.payment.AdminAuthorizationExpiryQueries authorizationExpiries) {
        this.authorizationExpiries = authorizationExpiries;
    }

    private static Long currentAdminId() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AdminUserDetails admin ? admin.getId() : null;
    }

    private com.example.ecsite.service.warehouse.OrderShipmentTrackingReader.Tracking shipmentTracking(Order order) {
        return shipmentTracking == null ? null : shipmentTracking.find(order.getId()).orElse(null);
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

        if (searchForm.getAuthorizationExpiry() != null) {
            returnUrlBuilder.queryParam("authorizationExpiry", searchForm.getAuthorizationExpiry());
        }

        if (searchForm.getShippingPreparation() != null) {
            returnUrlBuilder.queryParam("shippingPreparation", searchForm.getShippingPreparation());
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

        model.addAttribute("authorizationExpiryFilters",
                com.example.ecsite.form.AdminAuthorizationExpiryFilter.values());
        model.addAttribute("shippingPreparationFilters",
                com.example.ecsite.form.AdminShippingPreparationFilter.values());
        model.addAttribute("expiryBadges", authorizationExpiries == null ? java.util.Map.of()
                : authorizationExpiries.badges(orderPage.getContent().stream().map(Order::getId).toList()));

        return "admin/orders/list";
    }

    @GetMapping("/{id}")
    public String detail(
            @PathVariable Long id,
            @RequestParam(required = false) String returnUrl,
            Model model) {

        String safeReturnUrl = AdminReturnUrlHelper.resolveOrderListReturnUrl(returnUrl);

        Order order = orderService.findOrderWithItems(id);
        model.addAttribute("shippingPreparationView", shippingPreparationViewFactory.create(order));
        model.addAttribute("emergencyShipping", emergencyShipping == null ? null
                : emergencyShipping.find(order.getId()).orElse(null));
        model.addAttribute("emergencyShippingCsvDownloads", emergencyShippingCsv == null ? List.of()
                : emergencyShippingCsv.downloads(order.getId()));
        var emergencyFileOutput = warehouseFileOutputs == null ? null
                : warehouseFileOutputs.forOrder(order.getId()).orElse(null);
        model.addAttribute("emergencyFileOutput", emergencyFileOutput);
        model.addAttribute("emergencyFileOutputRetries", emergencyFileOutput == null ? List.of()
                : warehouseFileOutputs.retries(emergencyFileOutput.id()));
        model.addAttribute("emergencyShippingAllowed", emergencyShipping != null
                && emergencyShipping.isAllowed(currentAdminId()));

        var paymentView = paymentViewReader.read(order);
        model.addAttribute("paymentView", paymentView);
        boolean canCaptureForShipment = paymentView.canCapture();

        model.addAttribute(
                "canCaptureForShipment",
                canCaptureForShipment);

        model.addAttribute(
                "order",
                order);
        model.addAttribute("shipmentTracking", shipmentTracking(order));
        model.addAttribute("authorizationExpiry", authorizationExpiries == null ? null : authorizationExpiries.forOrder(order.getId()));
        model.addAttribute("canCancelExpiredAuthorization", expiredCancellations != null && expiredCancellations.available(order.getId()));
        model.addAttribute("customerNotifications", customerNotifications(order));
        addUnpaidClosure(id, model);
        addChangeClosure(id, model);

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

        model.addAttribute("recoveryTargets", recoveryViews.eligible(id));
        try {
            model.addAttribute("orderHoldItems", orderHoldViews.listForOrder(id));
        } catch (org.springframework.dao.DataAccessException unavailable) {
            model.addAttribute("orderHoldUnavailable", true);
        }
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
            if (paymentService.hasAuthorizedCardPayment(id)) {
                throw new InvalidOrderStatusException(
                        "カード与信済みの注文は、発送準備後に発送・売上確定操作を実行してください。");
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
                        "発送処理と売上確定が完了しました。");

            } else if (result.status() == CaptureResultStatus.FAILED) {

                redirectAttributes.addFlashAttribute(
                        "errorMessage",
                        "発送済みです。売上確定に失敗しました。決済状況を確認してください。");

            } else {

                redirectAttributes.addFlashAttribute(
                        "errorMessage",
                        "発送済みです。売上確定結果を確認中です。");
            }

        } catch (PaymentSafetyStopException e) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "決済状態を安全に確認できないため、自動処理を停止しています。管理者による確認が必要です。");
        } catch (com.example.ecsite.service.payment.AuthorizationExpiryException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        } catch (InvalidOrderStatusException | IllegalStateException e) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "発送・売上確定を実行できません。発送準備・注文状態・決済状況をご確認ください。");
        } catch (PaymentGatewayException e) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "決済結果を確認できませんでした。失敗確定ではありません。最新の発送・決済状態をご確認ください。");
        }

        return redirectToDetail(
                id,
                returnUrl);
    }

    private com.example.ecsite.service.changeclosure.ChangeFlowClosureStore changeClosures;
    private com.example.ecsite.service.changeclosure.ChangeFlowClosureService changeClosureService;
    /** Feature143-A. Spring always injects them; hand-built unit fixtures predate them and never offer it. */
    @org.springframework.beans.factory.annotation.Autowired
    void setChangeClosures(com.example.ecsite.service.changeclosure.ChangeFlowClosureStore changeClosures, com.example.ecsite.service.changeclosure.ChangeFlowClosureService changeClosureService) {
        this.changeClosures = changeClosures;
        this.changeClosureService = changeClosureService;
    }

    public record ChangeClosureRow(com.example.ecsite.service.changeclosure.ChangeFlowClosureStore.Closure closure, List<com.example.ecsite.service.changeclosure.ChangeFlowClosureStore.Send> sends) {}

    private void addChangeClosure(long orderId, Model model) {
        if (changeClosures == null) return;
        var closures = changeClosures.forOrder(orderId).stream()
                .map(c -> new ChangeClosureRow(c, changeClosures.sends(c.id()))).toList();
        boolean open = closures.stream().anyMatch(r -> r.closure().open());
        model.addAttribute("changeClosures", closures);
        model.addAttribute("changeClosureOpen", open);
        model.addAttribute("canCloseChange", open || changeClosures.closableOperation(orderId).isPresent());
    }

    @PostMapping("/{id}/close-unfinished-change")
    public String closeUnfinishedChange(
            @PathVariable Long id,
            @Valid @ModelAttribute AdminOrderStatusChangeForm form,
            BindingResult bindingResult,
            @RequestParam(required = false) String returnUrl,
            @AuthenticationPrincipal AdminUserDetails loginUser,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", "変更理由・備考は500文字以内で入力してください。");
            return redirectToDetail(id, returnUrl);
        }
        if (changeClosureService == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "この操作は利用できません。");
            return redirectToDetail(id, returnUrl);
        }
        String note = form.getInternalNote() == null || form.getInternalNote().isBlank() ? null : form.getInternalNote().trim();
        try {
            var outcome = changeClosureService.close(id, new com.example.ecsite.service.changeclosure.ChangeFlowClosureStore.Actor(
                    loginUser.getId(), loginUser.getUsername(), note));
            boolean success = outcome == com.example.ecsite.service.changeclosure.ChangeFlowClosureService.Outcome.CLOSED;
            redirectAttributes.addFlashAttribute(success ? "successMessage" : "errorMessage", switch (outcome) {
                case CLOSED -> "完了しなかった注文変更を終了しました。新しいカード決済は取り消され、確保していた在庫を戻しました。注文は変更前の内容のままです。";
                case AUTHORIZED -> "PAY.JPで新しいカード決済の与信が成立しているため、終了しませんでした。";
                case PROCESSING -> "PAY.JPで決済を処理中のため、終了しませんでした。時間をおいて再度お試しください。";
                case UNREADABLE -> "PAY.JPで決済の状態を確認できませんでした。注文変更は変わっていません。時間をおいて再度お試しください。";
                case CHANGED -> "確認中に注文変更または決済の状態が変わったため、終了しませんでした。最新の状態をご確認ください。";
                case REJECTED -> "PAY.JPが決済の取消を受け付けなかったため、終了しませんでした。最新の状態をご確認ください。";
                case UNCONFIRMED -> "決済の取消結果を確認中です。注文変更はまだ終了していません。自動で確認を続けます。";
            });
        } catch (com.example.ecsite.service.changeclosure.ChangeFlowClosureException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        } catch (org.springframework.dao.DataAccessException | IllegalStateException e) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "終了処理の結果を確認できませんでした。最新の注文変更・決済の状態をご確認ください。");
        }
        return redirectToDetail(id, returnUrl);
    }

    private com.example.ecsite.service.unpaidclosure.UnpaidOrderClosureStore unpaidClosures;
    private com.example.ecsite.service.unpaidclosure.UnpaidOrderClosureService unpaidClosureService;
    /** Feature142-A. Spring always injects them; hand-built unit fixtures predate them and never offer it. */
    @org.springframework.beans.factory.annotation.Autowired
    void setUnpaidClosures(com.example.ecsite.service.unpaidclosure.UnpaidOrderClosureStore unpaidClosures,
            com.example.ecsite.service.unpaidclosure.UnpaidOrderClosureService unpaidClosureService) {
        this.unpaidClosures = unpaidClosures;
        this.unpaidClosureService = unpaidClosureService;
    }

    public record UnpaidClosureRow(com.example.ecsite.service.unpaidclosure.UnpaidOrderClosureStore.Closure closure,
            List<com.example.ecsite.service.unpaidclosure.UnpaidOrderClosureStore.Send> sends) {}

    private void addUnpaidClosure(long orderId, Model model) {
        if (unpaidClosures == null) return;
        var closures = unpaidClosures.forOrder(orderId).stream()
                .map(c -> new UnpaidClosureRow(c, unpaidClosures.sends(c.id()))).toList();
        boolean open = closures.stream().anyMatch(r -> r.closure().open());
        model.addAttribute("unpaidClosures", closures);
        model.addAttribute("unpaidClosureOpen", open);
        model.addAttribute("canCloseUnpaid", open || unpaidClosures.closable(orderId));
    }

    @PostMapping("/{id}/close-unpaid")
    public String closeUnpaid(
            @PathVariable Long id,
            @Valid @ModelAttribute AdminOrderStatusChangeForm form,
            BindingResult bindingResult,
            @RequestParam(required = false) String returnUrl,
            @AuthenticationPrincipal AdminUserDetails loginUser,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", "変更理由・備考は500文字以内で入力してください。");
            return redirectToDetail(id, returnUrl);
        }
        if (unpaidClosureService == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "この操作は利用できません。");
            return redirectToDetail(id, returnUrl);
        }
        String note = form.getInternalNote() == null || form.getInternalNote().isBlank() ? null : form.getInternalNote().trim();
        try {
            var outcome = unpaidClosureService.close(id, new com.example.ecsite.service.unpaidclosure.UnpaidOrderClosureStore.Actor(
                    loginUser.getId(), loginUser.getUsername(), note));
            boolean success = outcome == com.example.ecsite.service.unpaidclosure.UnpaidOrderClosureService.Outcome.CLOSED;
            redirectAttributes.addFlashAttribute(success ? "successMessage" : "errorMessage", switch (outcome) {
                case CLOSED -> "決済未完了の注文を終了しました。PAY.JPの決済は取り消され、在庫を戻しました。";
                case ACCEPTED -> "カードの与信が完了していたため、注文を終了せず正式受注にしました。";
                case PROCESSING -> "PAY.JPで決済を処理中のため、終了しませんでした。時間をおいて再度お試しください。";
                case UNREADABLE -> "PAY.JPで決済の状態を確認できませんでした。注文は変更していません。時間をおいて再度お試しください。";
                case CHANGED -> "確認中に注文または決済の状態が変わったため、終了しませんでした。最新の状態をご確認ください。";
                case REJECTED -> "PAY.JPが決済の取消を受け付けなかったため、終了しませんでした。最新の状態をご確認ください。";
                case UNCONFIRMED -> "決済の取消結果を確認中です。注文はまだ終了していません。自動で確認を続けます。";
            });
        } catch (com.example.ecsite.service.unpaidclosure.UnpaidOrderClosureException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        } catch (org.springframework.dao.DataAccessException | IllegalStateException e) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "終了処理の結果を確認できませんでした。最新の注文・決済の状態をご確認ください。");
        }
        return redirectToDetail(id, returnUrl);
    }

    private com.example.ecsite.service.payment.ExpiredAuthorizationCancellationService expiredCancellations;
    /** Feature135-B1. Spring always injects it; hand-built unit fixtures predate it and never offer it. */
    @org.springframework.beans.factory.annotation.Autowired
    void setExpiredCancellations(com.example.ecsite.service.payment.ExpiredAuthorizationCancellationService expiredCancellations) {
        this.expiredCancellations = expiredCancellations;
    }

    @PostMapping("/{id}/cancel-expired-authorization")
    public String cancelExpiredAuthorization(
            @PathVariable Long id,
            @Valid @ModelAttribute AdminOrderStatusChangeForm form,
            BindingResult bindingResult,
            @RequestParam(required = false) String returnUrl,
            @AuthenticationPrincipal AdminUserDetails loginUser,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", "変更理由・備考は500文字以内で入力してください。");
            return redirectToDetail(id, returnUrl);
        }
        if (expiredCancellations == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "この操作は利用できません。");
            return redirectToDetail(id, returnUrl);
        }
        try {
            var outcome = expiredCancellations.cancel(id, loginUser.getId(), loginUser.getUsername(), form.getInternalNote());
            redirectAttributes.addFlashAttribute("successMessage",
                    outcome == com.example.ecsite.service.payment.ExpiredAuthorizationCancellationService.Outcome.CANCELLED
                            ? "与信期限切れのため、注文を取り消しました（PAY.JPでは与信は取消済みです）。"
                            : "この注文は既に取り消されています。");
        } catch (com.example.ecsite.service.payment.AuthorizationExpiryException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        } catch (PaymentGatewayException e) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "PAY.JPで与信の状態を確認できませんでした。注文は変更していません。時間をおいて再度お試しください。");
        } catch (InvalidOrderStatusException | IllegalStateException e) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "取消できません。注文変更・決済処理の状態をご確認ください。注文は変更していません。");
        }
        return redirectToDetail(id, returnUrl);
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
            CancellationResult result = adminPaymentCancellationService.cancel(
                    id, loginUser.getId(), loginUser.getUsername(), form.getInternalNote());
            if (result.status() == CancellationResultStatus.CANCELLED) {
                redirectAttributes.addFlashAttribute("successMessage",
                        result.providerTransactionId() == null ? "注文をキャンセルしました。"
                                : "決済与信を取り消し、注文をキャンセルしました。");
            } else {
                redirectAttributes.addFlashAttribute("errorMessage",
                        "決済取消結果を確認中です。注文はまだキャンセルしていません。");
            }

        } catch (PaymentSafetyStopException e) {
            redirectAttributes.addFlashAttribute("errorMessage", PaymentSafetyMessages.administrator(id, e));
        } catch (InvalidOrderStatusException
                | IllegalStateException e) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    e.getMessage());
        } catch (PaymentGatewayException e) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    PaymentSafetyMessages.administrator(id, e));
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
