package com.example.ecsite.controller;

import org.springframework.data.domain.Page;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.dto.AdminCustomerDetail;
import com.example.ecsite.dto.AdminCustomerListItem;
import com.example.ecsite.dto.AdminCustomerPurchaseSummary;
import com.example.ecsite.entity.Order;
import com.example.ecsite.form.AdminCustomerSearchForm;
import com.example.ecsite.service.AdminCustomerService;
import com.example.ecsite.service.MailService;
import com.example.ecsite.service.OrderService;
import com.example.ecsite.service.PasswordResetService;

@Controller
@RequestMapping("/admin/customers")
public class AdminCustomerController {

    private final AdminCustomerService adminCustomerService;
    private final OrderService orderService;
    private final PasswordResetService passwordResetService;
    private final MailService mailService;

    public AdminCustomerController(
            AdminCustomerService adminCustomerService,
            OrderService orderService,
            PasswordResetService passwordResetService,
            MailService mailService) {

        this.adminCustomerService = adminCustomerService;
        this.orderService = orderService;
        this.passwordResetService = passwordResetService;
        this.mailService = mailService;
    }

    @GetMapping
    public String list(
            @ModelAttribute("searchForm") AdminCustomerSearchForm searchForm,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model) {

        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, 100);

        Page<AdminCustomerListItem> customerPage = adminCustomerService.searchCustomers(
                searchForm,
                safePage,
                safeSize);

        model.addAttribute(
                "customers",
                customerPage.getContent());

        model.addAttribute(
                "customerPage",
                customerPage);

        return "admin/customers/list";
    }

    @GetMapping("/{id}")
    public String detail(
            @PathVariable Long id,
            Model model) {

        AdminCustomerDetail customer = adminCustomerService.findCustomerDetail(id);
        AdminCustomerPurchaseSummary purchaseSummary = adminCustomerService.getPurchaseSummary(id);

        model.addAttribute("customer", customer);
        model.addAttribute("purchaseSummary", purchaseSummary);

        return "admin/customers/detail";
    }

    @GetMapping("/{id}/password-reset")
    public String passwordResetConfirmation(
            @PathVariable Long id,
            Model model) {

        AdminCustomerDetail customer = adminCustomerService.findCustomerDetail(id);

        model.addAttribute("customer", customer);

        return "admin/customers/password-reset";
    }

    @PostMapping("/{id}/password-reset")
    public String sendPasswordReset(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes) {

        AdminCustomerDetail customer = adminCustomerService.findCustomerDetail(id);

        if (customer.email() == null || customer.email().isBlank()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "メールアドレスが登録されていないため、パスワード再設定メールを送信できません。");

            return "redirect:/admin/customers/" + id;
        }

        String rawToken = passwordResetService.issueToken(customer.email());

        if (rawToken == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "パスワード再設定メールを送信できませんでした。しばらく待ってから再度お試しください。");

            return "redirect:/admin/customers/" + id;
        }

        try {
            mailService.sendPasswordReset(customer.email(), rawToken);
        } catch (MailException e) {
            passwordResetService.invalidateToken(rawToken);

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "パスワード再設定メールの送信に失敗しました。");

            return "redirect:/admin/customers/" + id;
        }

        redirectAttributes.addFlashAttribute(
                "message",
                "パスワード再設定メールを送信しました。");

        return "redirect:/admin/customers/" + id;
    }

    @GetMapping("/{id}/orders")
    public String orders(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model) {

        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, 100);

        AdminCustomerDetail customer = adminCustomerService.findCustomerDetail(id);
        Page<Order> orderPage = orderService.findOrdersByUserId(id, safePage, safeSize);

        model.addAttribute("customer", customer);
        model.addAttribute("orders", orderPage.getContent());
        model.addAttribute("orderPage", orderPage);

        return "admin/customers/orders";
    }

}
