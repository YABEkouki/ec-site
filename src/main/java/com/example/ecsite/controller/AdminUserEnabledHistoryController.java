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

import com.example.ecsite.entity.UserEnabledHistory;
import com.example.ecsite.form.AdminUserEnabledHistorySearchForm;
import com.example.ecsite.service.UserEnabledHistoryCsvService;
import com.example.ecsite.service.UserEnabledHistoryService;

@Controller
@RequestMapping("/admin/user-enabled-histories")
public class AdminUserEnabledHistoryController {

    private static final int MAX_PAGE_SIZE = 100;

    private final UserEnabledHistoryService userEnabledHistoryService;
    private final UserEnabledHistoryCsvService userEnabledHistoryCsvService;

    public AdminUserEnabledHistoryController(
            UserEnabledHistoryService userEnabledHistoryService,
            UserEnabledHistoryCsvService userEnabledHistoryCsvService) {

        this.userEnabledHistoryService = userEnabledHistoryService;
        this.userEnabledHistoryCsvService = userEnabledHistoryCsvService;
    }

    @GetMapping
    public String list(
            @ModelAttribute("searchForm") AdminUserEnabledHistorySearchForm searchForm,
            @RequestParam(required = false) String returnTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model) {

        int safePage = Math.max(page, 0);
        int safeSize = Math.min(
                Math.max(size, 1),
                MAX_PAGE_SIZE);

        Page<UserEnabledHistory> historyPage = userEnabledHistoryService.search(
                searchForm,
                safePage,
                safeSize);

        model.addAttribute("returnTo", returnTo);

        model.addAttribute(
                "histories",
                historyPage.getContent());

        model.addAttribute(
                "historyPage",
                historyPage);

        return "admin/user-enabled-histories/list";
    }

    @GetMapping("/csv")
    public ResponseEntity<byte[]> csv(
            @ModelAttribute("searchForm") AdminUserEnabledHistorySearchForm searchForm) {

        List<UserEnabledHistory> histories = userEnabledHistoryService.searchAll(searchForm);

        byte[] csvBytes = userEnabledHistoryCsvService.createCsv(histories);

        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_TYPE,
                        "text/csv;charset=UTF-8")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"user-enabled-histories.csv\"")
                .body(csvBytes);
    }
}
