package com.example.ecsite.controller;

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
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.AdminAccountService;
import com.example.ecsite.service.OrderCsvService;
import com.example.ecsite.service.OrderHandlingStatusHistoryService;
import com.example.ecsite.service.OrderNoteService;
import com.example.ecsite.service.OrderService;
import com.example.ecsite.service.OrderStatusHistoryService;

import jakarta.validation.Valid;

@Controller
@RequestMapping("/admin/orders")
public class AdminOrderController {

    private final OrderService orderService;
    private final OrderCsvService orderCsvService;
    private final OrderStatusHistoryService orderStatusHistoryService;
    private final OrderNoteService orderNoteService;
    private final OrderHandlingStatusHistoryService orderHandlingStatusHistoryService;
    private final AdminAccountService adminAccountService;

    public AdminOrderController(
            OrderService orderService,
            OrderCsvService orderCsvService,
            OrderStatusHistoryService orderStatusHistoryService,
            OrderNoteService orderNoteService,
            OrderHandlingStatusHistoryService orderHandlingStatusHistoryService,
            AdminAccountService adminAccountService) {

        this.orderService = orderService;
        this.orderCsvService = orderCsvService;
        this.orderStatusHistoryService = orderStatusHistoryService;
        this.orderNoteService = orderNoteService;
        this.orderHandlingStatusHistoryService = orderHandlingStatusHistoryService;
        this.adminAccountService = adminAccountService;
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

        return "admin/orders/list";
    }

    @GetMapping("/{id}")
    public String detail(
            @PathVariable Long id,
            Model model) {

        Order order = orderService.findOrderWithItems(id);

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

    @PostMapping("/{id}/pay")
    public String markAsPaid(
            @PathVariable Long id,
            @Valid @ModelAttribute AdminOrderStatusChangeForm form,
            BindingResult bindingResult,
            @AuthenticationPrincipal AdminUserDetails loginUser,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "変更理由・備考は500文字以内で入力してください。");

            return "redirect:/admin/orders/" + id;
        }

        try {
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

        return "redirect:/admin/orders/" + id;
    }

    @PostMapping("/{id}/ship")
    public String markAsShipped(
            @PathVariable Long id,
            @Valid @ModelAttribute AdminOrderStatusChangeForm form,
            BindingResult bindingResult,
            @AuthenticationPrincipal AdminUserDetails loginUser,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "変更理由・備考は500文字以内で入力してください。");

            return "redirect:/admin/orders/" + id;
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

        return "redirect:/admin/orders/" + id;
    }

    @PostMapping("/{id}/cancel")
    public String cancel(
            @PathVariable Long id,
            @Valid @ModelAttribute AdminOrderStatusChangeForm form,
            BindingResult bindingResult,
            @AuthenticationPrincipal AdminUserDetails loginUser,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "変更理由・備考は500文字以内で入力してください。");

            return "redirect:/admin/orders/" + id;
        }

        try {
            orderService.cancelOrder(
                    id,
                    loginUser.getId(),
                    loginUser.getUsername(),
                    form.getInternalNote());

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "注文をキャンセルしました。");

        } catch (InvalidOrderStatusException e) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    e.getMessage());
        }

        return "redirect:/admin/orders/" + id;
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
            @AuthenticationPrincipal AdminUserDetails loginUser,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {

            String errorMessage = bindingResult.getFieldError("note") != null
                    ? bindingResult.getFieldError("note").getDefaultMessage()
                    : "メモの入力内容を確認してください。";

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    errorMessage);

            return "redirect:/admin/orders/" + id;
        }

        orderNoteService.addNote(
                id,
                form.getNote(),
                loginUser.getId(),
                loginUser.getUsername());

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "注文メモを登録しました。");

        return "redirect:/admin/orders/" + id;
    }

    @PostMapping("/{id}/handling-status")
    public String changeHandlingStatus(
            @PathVariable Long id,
            @Valid @ModelAttribute AdminOrderHandlingStatusForm form,
            BindingResult bindingResult,
            @AuthenticationPrincipal AdminUserDetails loginUser,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "対応状況を選択してください。");

            return "redirect:/admin/orders/" + id;
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

        return "redirect:/admin/orders/" + id;
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

}
