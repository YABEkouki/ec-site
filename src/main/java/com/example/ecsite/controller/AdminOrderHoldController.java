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
    private final com.example.ecsite.service.warehouse.WarehouseExportHoldStore warehouseHolds;

    public AdminOrderHoldController(AdminOrderHoldViewService holds,
            com.example.ecsite.service.warehouse.WarehouseExportHoldStore warehouseHolds) {
        this.holds = holds;
        this.warehouseHolds = warehouseHolds;
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
        // Feature131 C-3: orders the 14:05 warehouse export held, independent of the order change holds above.
        try {
            model.addAttribute("warehouseHolds", warehouseHolds.pending(null));
        } catch (DataAccessException unavailable) {
            model.addAttribute("warehouseHolds", java.util.List.of());
            model.addAttribute("warehouseHoldError", "倉庫出力の保留情報を取得できませんでした。時間をおいて再度表示してください。");
        }
        return "admin/order-holds/list";
    }
}
