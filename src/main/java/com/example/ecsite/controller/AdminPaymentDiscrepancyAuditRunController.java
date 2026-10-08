package com.example.ecsite.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.*;
import com.example.ecsite.entity.PaymentDiscrepancyAuditRunStatus;
import com.example.ecsite.form.AdminPaymentDiscrepancyAuditRunSearchForm;
import com.example.ecsite.service.AdminPaymentDiscrepancyAuditRunService;

@Controller
@RequestMapping("/admin/payment-discrepancy-audits")
public class AdminPaymentDiscrepancyAuditRunController {
    private final AdminPaymentDiscrepancyAuditRunService service;
    public AdminPaymentDiscrepancyAuditRunController(AdminPaymentDiscrepancyAuditRunService service) {
        this.service = service;
    }
    @ExceptionHandler({BindException.class, MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String invalidSearch() {
        return "admin/payment-discrepancy-audits/invalid-search";
    }

    @GetMapping
    public String list(@ModelAttribute("searchForm") AdminPaymentDiscrepancyAuditRunSearchForm form,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, Model model) {
        var result = service.search(form, Math.max(page, 0), Math.clamp(size, 1, 100));
        model.addAttribute("runs", result.getContent());
        model.addAttribute("page", result);
        model.addAttribute("summary", service.monitoringSummary());
        model.addAttribute("statuses", PaymentDiscrepancyAuditRunStatus.values());
        return "admin/payment-discrepancy-audits/list";
    }
}
