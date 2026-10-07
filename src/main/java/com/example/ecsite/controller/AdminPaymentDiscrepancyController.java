package com.example.ecsite.controller;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

import com.example.ecsite.entity.PaymentStatus;
import com.example.ecsite.form.AdminPaymentDiscrepancySearchForm;
import com.example.ecsite.payment.PaymentFlowStatus;
import com.example.ecsite.repository.projection.AdminPaymentDiscrepancyListProjection;
import com.example.ecsite.service.AdminPaymentDiscrepancyService;

@Controller
@RequestMapping("/admin/payment-discrepancies")
public class AdminPaymentDiscrepancyController {

    private final AdminPaymentDiscrepancyService service;

    public AdminPaymentDiscrepancyController(
            AdminPaymentDiscrepancyService service) {
        this.service = service;
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
                "returnUrl",
                returnUrl);

        return "admin/payment-discrepancies/list";
    }

}
