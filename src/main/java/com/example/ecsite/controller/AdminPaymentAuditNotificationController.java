package com.example.ecsite.controller;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import com.example.ecsite.entity.PaymentAuditNotificationStatus;
import com.example.ecsite.entity.PaymentAuditNotificationWarningType;
import com.example.ecsite.form.AdminPaymentAuditNotificationSearchForm;
import com.example.ecsite.service.AdminPaymentAuditNotificationService;

@Controller
@RequestMapping("/admin/payment-audit-notifications")
public class AdminPaymentAuditNotificationController {
    private final AdminPaymentAuditNotificationService service;
    public AdminPaymentAuditNotificationController(AdminPaymentAuditNotificationService service) {
        this.service = service;
    }

    @GetMapping
    public String list(@Valid @ModelAttribute("searchForm") AdminPaymentAuditNotificationSearchForm form,
            BindingResult binding, @RequestParam(defaultValue="0") String page,
            @RequestParam(defaultValue="20") String size, HttpServletRequest request, Model model) {
        var errors = new ArrayList<String>();
        for (var error : binding.getAllErrors()) {
            errors.add(error instanceof org.springframework.validation.FieldError fieldError && fieldError.isBindingFailure()
                ? "通知ID・ステータス・警告種別・日付の入力形式を確認してください。"
                : error.getDefaultMessage());
        }
        int requestedPage = number(page, "ページ番号", errors);
        int requestedSize = number(size, "表示件数", errors);
        int safeSize = requestedSize >= 100 ? 100 : requestedSize == 50 ? 50 : 20;
        var result = errors.isEmpty() ? service.search(form, requestedPage, safeSize)
            : Page.empty(PageRequest.of(0, safeSize));
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : new String[]{"notificationId","status","warningType","from","to"}) {
            values.put(name, request.getParameter(name) == null ? "" : request.getParameter(name));
        }
        model.addAttribute("values", values);
        model.addAttribute("inputErrors", errors);
        model.addAttribute("notifications", result.getContent());
        model.addAttribute("page", result);
        model.addAttribute("statuses", PaymentAuditNotificationStatus.values());
        model.addAttribute("warningTypes", PaymentAuditNotificationWarningType.values());
        return "admin/payment-audit-notifications/list";
    }

    private static int number(String value, String label, java.util.List<String> errors) {
        try {
            return new BigInteger(value).max(BigInteger.ZERO).min(BigInteger.valueOf(Integer.MAX_VALUE)).intValue();
        } catch (NumberFormatException e) {
            errors.add(label + "は整数で指定してください。");
            return 0;
        }
    }
}
