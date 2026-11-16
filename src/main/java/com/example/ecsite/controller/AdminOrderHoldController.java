package com.example.ecsite.controller;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.example.ecsite.service.orderchange.AdminOrderHoldViewService;

/** Feature130-A: read-only list of orders whose shipping is held by unresolved order change work. */
@Controller
public class AdminOrderHoldController {

    private final AdminOrderHoldViewService holds;

    public AdminOrderHoldController(AdminOrderHoldViewService holds) {
        this.holds = holds;
    }

    @GetMapping("/admin/order-holds")
    public String list(Model model) {
        model.addAttribute("staleAfterMinutes", holds.staleAfterMinutes());
        try {
            var items = holds.list();
            model.addAttribute("holdItems", items);
            model.addAttribute("holdSummary", AdminOrderHoldViewService.summarize(items));
        } catch (DataAccessException unavailable) {
            model.addAttribute("holdItems", java.util.List.of());
            model.addAttribute("errorMessage", "保留注文の情報を取得できませんでした。時間をおいて再度表示してください。");
        }
        return "admin/order-holds/list";
    }
}
