package com.example.ecsite.controller;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.form.AdminCustomerNotificationResendForm;
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.notification.AdminCustomerNotificationQueries;
import com.example.ecsite.service.notification.AdminCustomerNotificationQueries.Filter;
import com.example.ecsite.service.notification.AdminCustomerNotificationResendService;
import com.example.ecsite.service.notification.CustomerOrderNotificationLabels;

/**
 * Feature133-C: customer order notification list, detail (attempts with the exact text sent) and manual resend.
 * A resend never changes the original notification; it records a request and a copy that the worker delivers.
 */
@Controller
public class AdminCustomerNotificationController {
    private static final String BASE = "/admin/customer-notifications";

    private final AdminCustomerNotificationQueries queries;
    private final AdminCustomerNotificationResendService resends;
    private final boolean deliveryEnabled;

    public AdminCustomerNotificationController(AdminCustomerNotificationQueries queries,
            AdminCustomerNotificationResendService resends,
            @Value("${app.customer-notification.enabled:false}") boolean deliveryEnabled) {
        this.queries = queries; this.resends = resends; this.deliveryEnabled = deliveryEnabled;
    }

    @GetMapping(BASE)
    public String list(@RequestParam(name = "filter", required = false) Filter filter,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "orderId", required = false) Long orderId,
            @RequestParam(name = "page", defaultValue = "0") int page, Model model) {
        var selected = filter == null ? Filter.ALL : filter;
        String eventType = type != null && CustomerOrderNotificationLabels.isEvent(type) ? type : null;
        model.addAttribute("result", queries.list(selected, eventType, orderId, page));
        model.addAttribute("filters", Filter.values());
        model.addAttribute("filter", selected);
        model.addAttribute("events", CustomerOrderNotificationLabels.EVENTS);
        model.addAttribute("type", eventType);
        model.addAttribute("orderId", orderId);
        model.addAttribute("deliveryEnabled", deliveryEnabled);
        return "admin/customer-notifications/list";
    }

    @GetMapping(BASE + "/{id}")
    public String detail(@PathVariable("id") long id, Model model) {
        var detail = queries.detail(id).orElse(null);
        if (detail == null) return "redirect:" + BASE;
        model.addAttribute("detail", detail);
        model.addAttribute("deliveryEnabled", deliveryEnabled);
        return "admin/customer-notifications/detail";
    }

    @GetMapping(BASE + "/{id}/resend")
    public String input(@PathVariable("id") long id, Model model) {
        var form = new AdminCustomerNotificationResendForm();
        form.setNonce(UUID.randomUUID().toString());
        return showInput(id, form, model);
    }

    @PostMapping(BASE + "/{id}/resend/back")
    public String back(@PathVariable("id") long id, @ModelAttribute("resendForm") AdminCustomerNotificationResendForm form, Model model) {
        return showInput(id, form, model);
    }

    @PostMapping(BASE + "/{id}/resend/confirm")
    public String confirm(@PathVariable("id") long id, @Valid @ModelAttribute("resendForm") AdminCustomerNotificationResendForm form,
            BindingResult binding, Model model) {
        var detail = queries.detail(id).orElse(null);
        if (detail == null || detail.notification().resend()) return "redirect:" + BASE;
        if (!detail.eligibility().allowed()) return showInput(id, form, model);
        if (detail.eligibility().riskAcknowledgementRequired() && !form.isRiskAcknowledged())
            binding.rejectValue("riskAcknowledged", "required", "お客様に2通届く可能性があることを確認してください。");
        if (binding.hasErrors()) return showInput(id, form, model);
        model.addAttribute("detail", detail);
        return "admin/customer-notifications/resend-confirm";
    }

    @PostMapping(BASE + "/{id}/resend")
    public String submit(@PathVariable("id") long id, @Valid @ModelAttribute("resendForm") AdminCustomerNotificationResendForm form,
            BindingResult binding, @AuthenticationPrincipal AdminUserDetails admin, Model model, RedirectAttributes redirect) {
        if (binding.hasErrors()) return showInput(id, form, model);
        AdminCustomerNotificationResendService.Result result;
        try {
            result = resends.request(id, admin.getId(), admin.getUsername(), UUID.fromString(form.getNonce()), form.getReason(),
                    form.isRiskAcknowledged(), form.getExpectedResendCount());
        } catch (DataAccessException refused) {
            // The database guard refused it (the chain changed between the checks); nothing was recorded.
            result = AdminCustomerNotificationResendService.Result.STALE;
        }
        switch (result) {
            case ACCEPTED -> redirect.addFlashAttribute("message", deliveryEnabled
                    ? "再送を受け付けました。まもなく送信されます。"
                    : "再送を受け付けました。送信（バックグラウンド）が無効のため、有効になるまで送信されません。");
            case ALREADY_ACCEPTED -> redirect.addFlashAttribute("message", "この再送はすでに受け付けています。");
            case NOT_FOUND -> { return "redirect:" + BASE; }
            case RISK_NOT_ACKNOWLEDGED -> redirect.addFlashAttribute("errorMessage", "お客様に2通届く可能性があることの確認が必要です。");
            case NOT_ALLOWED, STALE -> redirect.addFlashAttribute("errorMessage",
                    "通知の状態が変わったため、再送を受け付けませんでした。最新の状態を確認してください。");
        }
        return "redirect:" + BASE + "/" + id;
    }

    private String showInput(long id, AdminCustomerNotificationResendForm form, Model model) {
        var detail = queries.detail(id).orElse(null);
        if (detail == null || detail.notification().resend()) return "redirect:" + BASE;
        if (form.getNonce() == null) form.setNonce(UUID.randomUUID().toString());
        form.setExpectedResendCount(detail.eligibility().resendCount());
        model.addAttribute("detail", detail);
        model.addAttribute("resendForm", form);
        return "admin/customer-notifications/resend-input";
    }

    // Local handlers prevent the global catch-all advice from converting these to 500.
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Void> handleAccessDenied() {
        return ResponseEntity.status(403).build();
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Void> handleInvalidArgument() {
        return ResponseEntity.badRequest().build();
    }
}
