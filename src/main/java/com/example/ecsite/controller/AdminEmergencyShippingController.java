package com.example.ecsite.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.example.ecsite.service.emergency.AdminEmergencyShippingQueries;
import com.example.ecsite.service.emergency.AdminEmergencyShippingQueries.FileOutput;
import com.example.ecsite.service.emergency.AdminEmergencyShippingQueries.Shipment;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Feature136-C3: emergency shipping list/audit screen, with the current permission holders and the permission
 * history. Read only: permissions are changed by operator SQL (V97). Opening the screen without any parameter
 * shows the last 30 days; an empty period means all days (the dashboard links use that).
 */
@Controller
public class AdminEmergencyShippingController {
    static final int DEFAULT_DAYS = 30;

    private final AdminEmergencyShippingQueries queries;

    public AdminEmergencyShippingController(AdminEmergencyShippingQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/admin/emergency-shipments")
    public String list(@RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "shipment", required = false) Shipment shipment,
            @RequestParam(name = "file", required = false) FileOutput file,
            @RequestParam(name = "page", defaultValue = "0") int page,
            HttpServletRequest request, Model model) {
        if (request.getParameterMap().isEmpty()) {
            to = queries.today();
            from = to.minusDays(DEFAULT_DAYS - 1);
        }
        Shipment selectedShipment = shipment == null ? Shipment.ALL : shipment;
        FileOutput selectedFile = file == null ? FileOutput.ALL : file;
        if (from != null && to != null && from.isAfter(to)) {
            model.addAttribute("errorMessage", "期間の開始日が終了日より後になっています。");
            model.addAttribute("result", new AdminEmergencyShippingQueries.Result(java.util.List.of(), 0, 0, false));
        } else {
            model.addAttribute("result", queries.list(from, to, selectedShipment, selectedFile, page));
        }
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("shipment", selectedShipment);
        model.addAttribute("file", selectedFile);
        model.addAttribute("shipments", Shipment.values());
        model.addAttribute("files", FileOutput.values());
        model.addAttribute("permittedAdmins", queries.permittedAdmins());
        model.addAttribute("permissionHistories", queries.permissionHistories());
        model.addAttribute("permissionHistoryLimit", AdminEmergencyShippingQueries.PERMISSION_HISTORY_LIMIT);
        return "admin/emergency-shipments/list";
    }

    // Local handler prevents the global catch-all advice from converting a malformed filter to 500.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Void> handleInvalidParameter() {
        return ResponseEntity.badRequest().build();
    }
}
