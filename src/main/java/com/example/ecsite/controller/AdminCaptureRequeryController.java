package com.example.ecsite.controller;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.payment.PaymentGatewayException;
import com.example.ecsite.service.payment.*;
import com.example.ecsite.util.AdminReturnUrlHelper;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** POST adapter only. The service resolves the authenticated actor and the stored target. */
@Controller
public class AdminCaptureRequeryController {
    private final AdminCaptureRequeryService service;
    public AdminCaptureRequeryController(AdminCaptureRequeryService service) { this.service = service; }

    @PostMapping("/admin/orders/{id}/capture/requery")
    public String requery(@PathVariable("id") Long orderId, @RequestParam(required = false) String returnUrl,
            RedirectAttributes flash) {
        if (orderId == null || orderId <= 0) throw new InvalidOrderIdException();
        try {
            var result = service.requery(orderId);
            flash.addFlashAttribute(result == AdminCaptureRequeryService.Result.CONFIRMED ? "successMessage" : "errorMessage",
                    result == AdminCaptureRequeryService.Result.CONFIRMED
                            ? "売上確定済みであることを確認しました。" : "売上確定結果を引き続き確認中です。");
        } catch (CaptureRequeryRejectedException | InvalidOrderStatusException failure) {
            flash.addFlashAttribute("errorMessage", "決済状態を安全に確認できないため、自動処理を停止しています。管理者による確認が必要です。");
        } catch (PaymentGatewayException failure) {
            flash.addFlashAttribute("errorMessage", "決済結果を再照会できませんでした。決済失敗が確定したわけではありません。");
        }
        String safe = AdminReturnUrlHelper.resolveOrderListReturnUrl(returnUrl);
        return "redirect:/admin/orders/" + orderId + "?returnUrl=" + URLEncoder.encode(safe, StandardCharsets.UTF_8);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Void> denied() { return ResponseEntity.status(403).build(); }

    @ExceptionHandler({InvalidOrderIdException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Void> invalidId() { return ResponseEntity.badRequest().build(); }

    private static final class InvalidOrderIdException extends RuntimeException { }
}
