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
import org.springframework.http.HttpStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.util.UriComponentsBuilder;
import com.example.ecsite.exception.PaymentAuditNotificationNotFoundException;
import com.example.ecsite.util.AdminReturnUrlHelper;
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
    private final com.example.ecsite.service.AdminPaymentAuditNotificationHandlingService handlingService;
    private final com.example.ecsite.service.AdminPaymentAuditNotificationManualResendViewService resendViews;
    @org.springframework.beans.factory.annotation.Autowired
    public AdminPaymentAuditNotificationController(AdminPaymentAuditNotificationService service, @org.springframework.lang.Nullable com.example.ecsite.service.AdminPaymentAuditNotificationHandlingService handlingService, @org.springframework.lang.Nullable com.example.ecsite.service.AdminPaymentAuditNotificationManualResendViewService resendViews) {
        this.service = service; this.handlingService = handlingService; this.resendViews=resendViews;
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
        for (String name : new String[]{"notificationId","status","warningType","from","to","handlingStatus","actionableOnly","requiresRecheck"}) {
            values.put(name, request.getParameter(name) == null ? "" : request.getParameter(name));
        }
        var returnUrl = UriComponentsBuilder.fromPath("/admin/payment-audit-notifications");
        if (errors.isEmpty()) {
            values.forEach((name, value) -> { if (!value.isBlank()) returnUrl.queryParam(name, value); });
            returnUrl.queryParam("page", result.getNumber()).queryParam("size", result.getSize());
        }
        model.addAttribute("returnUrl", returnUrl.build().encode().toUriString());
        model.addAttribute("values", values);
        model.addAttribute("inputErrors", errors);
        model.addAttribute("notifications", result.getContent());
        model.addAttribute("page", result);
        model.addAttribute("handlingStatuses", com.example.ecsite.entity.PaymentAuditNotificationHandlingStatus.values());
        model.addAttribute("statuses", PaymentAuditNotificationStatus.values());
        model.addAttribute("warningTypes", PaymentAuditNotificationWarningType.values());
        return "admin/payment-audit-notifications/list";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, @RequestParam(required=false) String returnUrl, @org.springframework.security.core.annotation.AuthenticationPrincipal Object principal, Model model) {
        if (id <= 0) throw new PaymentAuditNotificationInvalidIdException();
        model.addAttribute("notification", principal instanceof com.example.ecsite.security.AdminUserDetails admin
            ? service.findById(id,admin.getId()) : service.findById(id));
        if(resendViews!=null && principal instanceof com.example.ecsite.security.AdminUserDetails admin) model.addAttribute("manualResend",resendViews.read(id,admin.getId(),false));
        model.addAttribute("handlingStatuses", com.example.ecsite.entity.PaymentAuditNotificationHandlingStatus.values());
        model.addAttribute("returnUrl", AdminReturnUrlHelper.resolvePaymentAuditNotificationListReturnUrl(returnUrl));
        return "admin/payment-audit-notifications/detail";
    }

    @PostMapping("/{id}/handling")
    public String handling(@PathVariable Long id,
        @Valid @ModelAttribute("handlingForm") com.example.ecsite.form.AdminPaymentAuditNotificationHandlingForm form,
        BindingResult binding, @RequestParam(required=false) String returnUrl,
        @org.springframework.security.core.annotation.AuthenticationPrincipal com.example.ecsite.security.AdminUserDetails admin,
        org.springframework.web.servlet.mvc.support.RedirectAttributes flash) {
        if(id<=0) throw new PaymentAuditNotificationInvalidIdException();
        String message;
        boolean success=false;
        if(binding.hasErrors()) message="入力内容を確認してください。詳細を再読み込みして再操作してください。";
        else if(admin==null || handlingService==null) message="現在は閲覧のみ可能です。";
        else try {
            boolean changed=handlingService.changeStatus(id,admin.getId(),admin.getUsername(),form.getSnapshotToken(),form.getStatus(),form.getComment());
            message=changed?"管理者対応状態を変更しました。":"管理者対応状態は変更されていません。"; success=true;
        } catch(com.example.ecsite.service.PaymentAuditNotificationHandlingToken.InvalidSnapshotException e) {
            message="署名トークンが不正または期限切れです。詳細を再読み込みしてください。";
        } catch(com.example.ecsite.service.PaymentAuditNotificationHandlingConflictException e) {
            message=e.isUnknownChanged()?"新しい結果不明の配送試行があります。詳細を再読み込みして確認してください。":"他の管理者により対応状態が更新されています。詳細を再読み込みしてください。";
        } catch(org.springframework.dao.OptimisticLockingFailureException e) {
            message="他の管理者により対応状態が更新されています。詳細を再読み込みしてください。";
        } catch(IllegalArgumentException e) {
            message="コメント・変更先状態・管理対象条件を確認して入力内容を修正してください。";
        } catch(IllegalStateException e) {
            message="対応操作を利用できません。管理者に設定の確認を依頼してください。";
        }
        flash.addFlashAttribute(success?"successMessage":"errorMessage",message);
        String safe=AdminReturnUrlHelper.resolvePaymentAuditNotificationListReturnUrl(returnUrl);
        return "redirect:"+UriComponentsBuilder.fromPath("/admin/payment-audit-notifications/"+id)
            .queryParam("returnUrl",safe).build().encode().toUriString();
    }

    @ExceptionHandler(PaymentAuditNotificationNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notificationNotFound() {
        return "error/not_found";
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, PaymentAuditNotificationInvalidIdException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String invalidId() {
        return "error/not_found";
    }

    private static final class PaymentAuditNotificationInvalidIdException extends RuntimeException {}

    private static int number(String value, String label, java.util.List<String> errors) {
        try {
            return new BigInteger(value).max(BigInteger.ZERO).min(BigInteger.valueOf(Integer.MAX_VALUE)).intValue();
        } catch (NumberFormatException e) {
            errors.add(label + "は整数で指定してください。");
            return 0;
        }
    }
}
