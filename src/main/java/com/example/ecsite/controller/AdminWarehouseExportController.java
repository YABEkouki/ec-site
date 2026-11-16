package com.example.ecsite.controller;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.service.warehouse.WarehouseExportBatchStore;
import com.example.ecsite.service.warehouse.WarehouseExportCsvService;
import com.example.ecsite.service.warehouse.WarehouseExportRunner;

/**
 * Feature131 C-2: warehouse export batches. Viewing is read-only; the CSV download is a POST because every
 * download is recorded as an audit row. Batches are created only by the 14:05 export, never from this screen.
 */
@Controller
public class AdminWarehouseExportController {

    private final WarehouseExportBatchStore batches;
    private final WarehouseExportCsvService csv;
    private final WarehouseExportRunner runner;

    public AdminWarehouseExportController(WarehouseExportBatchStore batches, WarehouseExportCsvService csv,
            WarehouseExportRunner runner) {
        this.batches = batches;
        this.csv = csv;
        this.runner = runner;
    }

    @GetMapping("/admin/warehouse-exports")
    public String list(Model model) {
        model.addAttribute("batches", batches.list(60));
        model.addAttribute("startDate", runner.startDate());
        return "admin/warehouse-exports/list";
    }

    @GetMapping("/admin/warehouse-exports/{id}")
    public String detail(@PathVariable("id") long id, Model model) {
        var batch = batches.find(id).orElse(null);
        if (batch == null) return "redirect:/admin/warehouse-exports";
        model.addAttribute("batch", batch);
        model.addAttribute("lines", batches.lines(id));
        model.addAttribute("downloads", batches.downloads(id));
        return "admin/warehouse-exports/detail";
    }

    @PostMapping("/admin/warehouse-exports/{id}/csv")
    public Object download(@PathVariable("id") long id, RedirectAttributes redirect) {
        WarehouseExportCsvService.Download file;
        try {
            file = csv.download(id);
        } catch (WarehouseExportCsvService.NotDownloadableException refused) {
            redirect.addFlashAttribute("errorMessage", refused.getMessage());
            return "redirect:/admin/warehouse-exports";
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
