package com.example.ecsite.controller;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import com.example.ecsite.entity.PaymentDiscrepancy;
import com.example.ecsite.entity.PaymentDiscrepancyHandlingStatus;
import com.example.ecsite.entity.PaymentStatus;
import com.example.ecsite.form.AdminPaymentDiscrepancyHandlingStatusForm;
import com.example.ecsite.form.AdminPaymentDiscrepancySearchForm;
import com.example.ecsite.payment.PaymentFlowStatus;
import com.example.ecsite.repository.projection.AdminPaymentDiscrepancyListProjection;
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.AdminPaymentDiscrepancyService;
import com.example.ecsite.service.AdminPaymentDiscrepancyReconciliationService;
import com.example.ecsite.service.payment.PaymentDiscrepancyAuditResult;
import com.example.ecsite.util.AdminReturnUrlHelper;

@Controller
@RequestMapping("/admin/payment-discrepancies")
public class AdminPaymentDiscrepancyController {

    private static final Logger logger = LoggerFactory.getLogger(AdminPaymentDiscrepancyController.class);

    private final AdminPaymentDiscrepancyService service;
    private final AdminPaymentDiscrepancyReconciliationService reconciliationService;

    public AdminPaymentDiscrepancyController(
            AdminPaymentDiscrepancyService service,
            AdminPaymentDiscrepancyReconciliationService reconciliationService) {
        this.service = service;
        this.reconciliationService = reconciliationService;
    }

    @GetMapping
    public String list(
            @ModelAttribute("searchForm") AdminPaymentDiscrepancySearchForm searchForm,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model) {

        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, 100);

        Page<AdminPaymentDiscrepancyListProjection> result = service.search(
                searchForm,
                safePage,
                safeSize);

        UriComponentsBuilder returnUrlBuilder = UriComponentsBuilder.fromPath(
                "/admin/payment-discrepancies");

        if (searchForm.getOrderId() != null) {
            returnUrlBuilder.queryParam(
                    "orderId",
                    searchForm.getOrderId());
        }

        if (searchForm.getUserId() != null) {
            returnUrlBuilder.queryParam(
                    "userId",
                    searchForm.getUserId());
        }

        if (searchForm.getLocalStatus() != null) {
            returnUrlBuilder.queryParam(
                    "localStatus",
                    searchForm.getLocalStatus());
        }

        if (searchForm.getProviderStatus() != null) {
            returnUrlBuilder.queryParam(
                    "providerStatus",
                    searchForm.getProviderStatus());
        }

        if (searchForm.getHandlingStatus() != null) {
            returnUrlBuilder.queryParam(
                    "handlingStatus",
                    searchForm.getHandlingStatus());
        }

        if (safePage > 0) {
            returnUrlBuilder.queryParam(
                    "page",
                    safePage);
        }

        returnUrlBuilder.queryParam(
                "size",
                safeSize);

        String returnUrl = returnUrlBuilder
                .build()
                .encode()
                .toUriString();

        model.addAttribute(
                "discrepancies",
                result.getContent());

        model.addAttribute(
                "page",
                result);

        model.addAttribute(
                "paymentStatuses",
                PaymentStatus.values());

        model.addAttribute(
                "paymentFlowStatuses",
                PaymentFlowStatus.values());

        model.addAttribute(
                "paymentDiscrepancyHandlingStatuses",
                PaymentDiscrepancyHandlingStatus.values());

        model.addAttribute(
                "returnUrl",
                returnUrl);

        return "admin/payment-discrepancies/list";
    }

    @GetMapping("/{id}")
    public String detail(
            @PathVariable Long id,
            @RequestParam(required = false) String returnUrl,
            Model model) {

        String safeReturnUrl = AdminReturnUrlHelper.resolvePaymentDiscrepancyListReturnUrl(
                returnUrl);

        PaymentDiscrepancy discrepancy = service.findById(id);

        AdminPaymentDiscrepancyHandlingStatusForm handlingStatusForm = new AdminPaymentDiscrepancyHandlingStatusForm();

        handlingStatusForm.setHandlingStatus(
                discrepancy.getHandlingStatus());

        model.addAttribute(
                "discrepancy",
                discrepancy);

        model.addAttribute(
                "handlingStatusForm",
                handlingStatusForm);

        model.addAttribute(
                "handlingStatuses",
                PaymentDiscrepancyHandlingStatus.values());

        model.addAttribute(
                "handlingStatusHistories",
                service.findHandlingStatusHistories(id));

        model.addAttribute(
                "returnUrl",
                safeReturnUrl);

        return "admin/payment-discrepancies/detail";
    }

    @PostMapping("/{id}/handling-status")
    public String changeHandlingStatus(
            @PathVariable Long id,
            @ModelAttribute AdminPaymentDiscrepancyHandlingStatusForm form,
            @RequestParam(required = false) String returnUrl,
            @AuthenticationPrincipal AdminUserDetails loginUser,
            RedirectAttributes redirectAttributes) {

        try {
            boolean changed = service.changeHandlingStatus(
                    id,
                    form.getHandlingStatus(),
                    loginUser.getId(),
                    loginUser.getUsername());

            if (changed) {
                redirectAttributes.addFlashAttribute(
                        "successMessage",
                        "管理者対応状態を変更しました。");
            } else {
                redirectAttributes.addFlashAttribute(
                        "successMessage",
                        "管理者対応状態は変更されていません。");
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

    @PostMapping("/{id}/recheck")
    public String recheck(
            @PathVariable Long id,
            @RequestParam(required = false) String returnUrl,
            RedirectAttributes redirectAttributes) {

        try {
            try {
                service.findById(id);
            } catch (IllegalArgumentException e) {
                redirectAttributes.addFlashAttribute(
                        "errorMessage", "対象の決済不整合が見つからないため、再照合できませんでした。");
                return "redirect:" + AdminReturnUrlHelper.resolvePaymentDiscrepancyListReturnUrl(returnUrl);
            }

            PaymentDiscrepancyAuditResult result = reconciliationService.reconcile(id);
            String message = switch (result.status()) {
                case CONSISTENT -> "PAY.JPの最新状態との整合を確認しました。管理者対応状態は変更していません。";
                case INCONSISTENT -> "PAY.JPの最新状態を確認しましたが、不整合が継続しています。";
                case IN_PROGRESS -> "PAY.JPの決済処理が進行中のため、判定を保留しました。";
                case SKIPPED -> switch (result.skipReason()) {
                    case PAYMENT_NOT_FOUND -> "対象の決済が見つからないため、再照合できませんでした。";
                    case UNSUPPORTED_PAYMENT -> "PAY.JPのカード決済ではないため、再照合できませんでした。";
                    case MISSING_PROVIDER_PAYMENT_ID -> "PAY.JP決済IDが未設定のため、再照合できませんでした。";
                    case PENDING_TRANSACTION -> "処理中の決済取引があるため、再照合できませんでした。";
                };
            };
            redirectAttributes.addFlashAttribute("recheckMessage", message);
            redirectAttributes.addFlashAttribute("recheckResult", result);
        } catch (Exception e) {
            logger.error("Manual payment discrepancy recheck failed. discrepancyId={}", id, e);
            redirectAttributes.addFlashAttribute(
                    "errorMessage", "再照合できませんでした。時間をおいて再度お試しください。");
        }

        return redirectToDetail(id, returnUrl);
    }

    private String redirectToDetail(
            Long discrepancyId,
            String returnUrl) {

        String safeReturnUrl = AdminReturnUrlHelper.resolvePaymentDiscrepancyListReturnUrl(
                returnUrl);

        String encodedReturnUrl = URLEncoder.encode(
                safeReturnUrl,
                StandardCharsets.UTF_8);

        return "redirect:/admin/payment-discrepancies/"
                + discrepancyId
                + "?returnUrl="
                + encodedReturnUrl;
    }

}
