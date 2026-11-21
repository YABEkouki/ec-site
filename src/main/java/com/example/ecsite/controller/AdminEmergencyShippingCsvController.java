package com.example.ecsite.controller;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.service.emergency.EmergencyShippingCsvService;
import com.example.ecsite.service.warehouse.WarehouseExportCsvService;

/**
 * Feature136-C2: the per-order warehouse CSV of an emergency-shipped order. A POST because every download is
 * recorded as an audit row (V100).
 */
@Controller
public class AdminEmergencyShippingCsvController {

    private final EmergencyShippingCsvService csv;

    public AdminEmergencyShippingCsvController(EmergencyShippingCsvService csv) {
        this.csv = csv;
    }

    @PostMapping("/admin/orders/{id}/emergency-shipping/csv")
    public Object download(@PathVariable("id") long id, RedirectAttributes redirect) {
        EmergencyShippingCsvService.Download file;
        try {
            file = csv.download(id);
        } catch (WarehouseExportCsvService.NotDownloadableException refused) {
            redirect.addFlashAttribute("errorMessage", refused.getMessage());
            return "redirect:/admin/orders/" + id;
        }
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename()
                        + "\"; filename*=UTF-8''" + URLEncoder.encode(file.filename(), StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(file.content());
    }

    // Local handlers prevent the global catch-all advice from converting these to 500.
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Void> handleAccessDenied() {
        return ResponseEntity.status(403).build();
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Void> handleInvalidId() {
        return ResponseEntity.badRequest().build();
    }
}
