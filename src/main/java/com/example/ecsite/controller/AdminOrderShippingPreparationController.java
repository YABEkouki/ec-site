package com.example.ecsite.controller;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.service.OrderShippingPreparationService;
import com.example.ecsite.service.ShippingPreparationStartResult;
import com.example.ecsite.util.AdminReturnUrlHelper;

/** POST adapter; authentication and transactional business decisions belong to the service. */
@Controller
public class AdminOrderShippingPreparationController {

    private final OrderShippingPreparationService shippingPreparationService;

    public AdminOrderShippingPreparationController(OrderShippingPreparationService shippingPreparationService) {
        this.shippingPreparationService = shippingPreparationService;
    }

    @PostMapping("/admin/orders/{id}/start-shipping-preparation")
    public String startShippingPreparation(
            @PathVariable("id") Long orderId,
            @RequestParam(required = false) String returnUrl,
            RedirectAttributes redirectAttributes) {
        if (orderId == null || orderId <= 0) {
            throw new InvalidOrderIdException();
        }

        ShippingPreparationStartResult result;
        try {
            result = shippingPreparationService.start(orderId);
        } catch (IllegalStateException | InvalidOrderStatusException e) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "発送準備を開始できません。変更期限・注文状態・決済状況をご確認ください。");
            return redirectToDetail(orderId, returnUrl);
        }
        String message = switch (result.status()) {
            case STARTED -> "発送準備を開始しました。";
            case ALREADY_STARTED -> "この注文は既に発送準備を開始しています。";
        };
        redirectAttributes.addFlashAttribute("successMessage", message);
        return redirectToDetail(orderId, returnUrl);
    }

    // Local handlers prevent the global catch-all advice from converting these to 500.
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Void> handleAccessDenied() {
        return ResponseEntity.status(403).build();
    }

    @ExceptionHandler({InvalidOrderIdException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Void> handleInvalidOrderId() {
        return ResponseEntity.badRequest().build();
    }

    private String redirectToDetail(Long orderId, String returnUrl) {
        String safeReturnUrl = AdminReturnUrlHelper.resolveOrderListReturnUrl(returnUrl);
        return "redirect:/admin/orders/" + orderId + "?returnUrl="
                + URLEncoder.encode(safeReturnUrl, StandardCharsets.UTF_8);
    }

    private static final class InvalidOrderIdException extends RuntimeException {
    }
}
