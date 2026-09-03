package com.example.ecsite.controller;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.entity.OrderStatusHistory;
import com.example.ecsite.entity.OrderStatusHistoryActorType;
import com.example.ecsite.form.AdminOrderStatusHistorySearchForm;
import com.example.ecsite.service.OrderStatusHistoryCsvService;
import com.example.ecsite.service.OrderStatusHistoryService;

@Controller
@RequestMapping("/admin/order-status-histories")
public class AdminOrderStatusHistoryController {

    private static final int MAX_PAGE_SIZE = 100;

    private final OrderStatusHistoryService orderStatusHistoryService;
    private final OrderStatusHistoryCsvService orderStatusHistoryCsvService;

    public AdminOrderStatusHistoryController(
            OrderStatusHistoryService orderStatusHistoryService,
            OrderStatusHistoryCsvService orderStatusHistoryCsvService) {

        this.orderStatusHistoryService = orderStatusHistoryService;
        this.orderStatusHistoryCsvService = orderStatusHistoryCsvService;
    }

    @GetMapping
    public String list(
            @ModelAttribute("searchForm") AdminOrderStatusHistorySearchForm searchForm,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model) {

        int safePage = Math.max(page, 0);
        int safeSize = Math.min(
                Math.max(size, 1),
                MAX_PAGE_SIZE);

        Page<OrderStatusHistory> historyPage = orderStatusHistoryService.search(
                searchForm,
                safePage,
                safeSize);

        model.addAttribute(
                "histories",
                historyPage.getContent());

        model.addAttribute(
                "historyPage",
                historyPage);

        model.addAttribute(
                "statuses",
                OrderStatus.values());

        model.addAttribute(
                "actorTypes",
                OrderStatusHistoryActorType.values());

        return "admin/order-status-histories/list";
    }

    @GetMapping("/csv")
    public ResponseEntity<byte[]> csv(
            @ModelAttribute("searchForm") AdminOrderStatusHistorySearchForm searchForm) {

        List<OrderStatusHistory> histories = orderStatusHistoryService.searchAll(searchForm);

        byte[] csvBytes = orderStatusHistoryCsvService.createCsv(histories);

        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_TYPE,
                        "text/csv;charset=UTF-8")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"order-status-histories.csv\"")
                .body(csvBytes);
    }

}
