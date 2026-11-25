package com.example.ecsite.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.example.ecsite.config.UnpaidOrderClosureProperties;
import com.example.ecsite.service.unpaidclosure.AdminUnpaidOrderQueries;
import com.example.ecsite.service.unpaidclosure.AdminUnpaidOrderQueries.View;

/** Feature142-B: orders whose checkout payment was never completed. Read only: an order is closed from its detail. */
@Controller
public class AdminUnpaidOrderController {
    private final AdminUnpaidOrderQueries queries;
    private final UnpaidOrderClosureProperties properties;

    public AdminUnpaidOrderController(AdminUnpaidOrderQueries queries, UnpaidOrderClosureProperties properties) {
        this.queries = queries;
        this.properties = properties;
    }

    @GetMapping("/admin/unpaid-orders")
    public String list(@RequestParam(name = "view", defaultValue = "ALL") View view,
            @RequestParam(name = "page", defaultValue = "0") int page, Model model) {
        model.addAttribute("result", queries.list(view, page));
        model.addAttribute("view", view);
        model.addAttribute("views", View.values());
        model.addAttribute("automatic", properties.enabled());
        long minutes = properties.after().toMinutes();
        model.addAttribute("automaticAfter", minutes % 60 == 0 ? minutes / 60 + "時間" : minutes + "分");
        return "admin/unpaid-orders/list";
    }

    // Local handler prevents the global catch-all advice from converting a malformed filter to 500.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Void> handleInvalidParameter() {
        return ResponseEntity.badRequest().build();
    }
}
