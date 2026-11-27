package com.example.ecsite.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.example.ecsite.service.operations.OperationsStatusService;

/** Feature145-B: read only. Processes are enabled by environment variables and a restart, never from this screen. */
@Controller
public class AdminOperationsStatusController {
    private final OperationsStatusService status;

    public AdminOperationsStatusController(OperationsStatusService status) {
        this.status = status;
    }

    @GetMapping("/admin/operations")
    public String show(Model model) {
        model.addAttribute("status", status.read());
        return "admin/operations/status";
    }
}
