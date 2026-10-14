package com.example.ecsite.controller;

import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;
import com.example.ecsite.form.AdminPaymentAuditNotificationManualResendForm;
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.*;
import com.example.ecsite.util.AdminReturnUrlHelper;
import com.example.ecsite.exception.PaymentAuditNotificationNotFoundException;

@Controller
@RequestMapping("/admin/payment-audit-notifications/{id}/manual-resend")
public class AdminPaymentAuditNotificationManualResendController {
    private final AdminPaymentAuditNotificationManualResendViewService views;
    private final AdminPaymentAuditNotificationManualResendService acceptance;
    private final com.example.ecsite.web.AdminPaymentAuditNotificationManualResendDisplay display;
    public AdminPaymentAuditNotificationManualResendController(AdminPaymentAuditNotificationManualResendViewService views,AdminPaymentAuditNotificationManualResendService acceptance,com.example.ecsite.web.AdminPaymentAuditNotificationManualResendDisplay display){this.views=views;this.acceptance=acceptance;this.display=display;}
    @GetMapping
    public String input(@PathVariable long id,@AuthenticationPrincipal AdminUserDetails admin,@ModelAttribute("resendForm") AdminPaymentAuditNotificationManualResendForm form,@RequestParam(required=false) String returnUrl,Model model){load(id,admin,false,returnUrl,model);return "admin/payment-audit-notifications/manual-resend-input";}
    @PostMapping("/back")
    public String back(@PathVariable long id,@AuthenticationPrincipal AdminUserDetails admin,@ModelAttribute("resendForm") AdminPaymentAuditNotificationManualResendForm form,@RequestParam(required=false) String returnUrl,Model model){return input(id,admin,form,returnUrl,model);}
    @PostMapping("/confirm")
    public String confirm(@PathVariable long id,@AuthenticationPrincipal AdminUserDetails admin,@Valid @ModelAttribute("resendForm") AdminPaymentAuditNotificationManualResendForm form,BindingResult binding,@RequestParam(required=false) String returnUrl,Model model){
        var view=load(id,admin,!binding.hasErrors(),returnUrl,model);
        if(binding.hasErrors())return "admin/payment-audit-notifications/manual-resend-input";
        if(!view.eligibility().eligible() || !view.eligibility().signingAvailable()){model.addAttribute("errorMessage","現在は再送できません。詳細を再読み込みしてください。");return "admin/payment-audit-notifications/manual-resend-input";}
        if(view.eligibility().riskAcknowledgementRequired() && !form.isRiskAcknowledged()){model.addAttribute("errorMessage","重複配信リスクを確認してください。");return "admin/payment-audit-notifications/manual-resend-input";}
        form.setSnapshotToken(view.eligibility().snapshotToken());return "admin/payment-audit-notifications/manual-resend-confirm";
    }
    @PostMapping("/submit")
    public String submit(@PathVariable long id,@AuthenticationPrincipal AdminUserDetails admin,@Valid @ModelAttribute("resendForm") AdminPaymentAuditNotificationManualResendForm form,BindingResult binding,@RequestParam(required=false) String returnUrl,RedirectAttributes flash){
        check(id,admin);String message;boolean success=false;
        if(binding.hasErrors())message="再送理由を確認してください。";
        else try{acceptance.request(id,admin.getId(),admin.getUsername(),form.getSnapshotToken(),form.getReason(),form.isRiskAcknowledged());message="再送要求を受け付けました";success=true;}
        catch(PaymentAuditNotificationManualResendRejectedException e){message=display.rejection(e.getReason());}
        catch(IllegalArgumentException e){message="署名が不正・期限切れ、または入力が不正です。詳細を再読み込みしてください。";}
        catch(IllegalStateException e){message="署名設定を確認してください。現在は操作できません。";}
        flash.addFlashAttribute(success?"successMessage":"errorMessage",message);
        return "redirect:"+UriComponentsBuilder.fromPath("/admin/payment-audit-notifications/"+id).queryParam("returnUrl",safe(returnUrl)).build().encode().toUriString();
    }
    private AdminPaymentAuditNotificationManualResendViewService.View load(long id,AdminUserDetails admin,boolean confirmation,String url,Model model){check(id,admin);var v=views.read(id,admin.getId(),confirmation);model.addAttribute("manualResend",v);model.addAttribute("returnUrl",safe(url));return v;}
    private static void check(long id,AdminUserDetails admin){if(id<=0)throw new IllegalArgumentException("Invalid notification ID");if(admin==null)throw new org.springframework.security.access.AccessDeniedException("Administrator identity required");}
    private static String safe(String url){return AdminReturnUrlHelper.resolvePaymentAuditNotificationListReturnUrl(url);}
    @ExceptionHandler(PaymentAuditNotificationNotFoundException.class) @ResponseStatus(HttpStatus.NOT_FOUND) public String missing(){return "error/not_found";}
    @ExceptionHandler({MethodArgumentTypeMismatchException.class,IllegalArgumentException.class}) @ResponseStatus(HttpStatus.BAD_REQUEST) public String invalid(){return "error/not_found";}
}
