package com.example.ecsite.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.example.ecsite.service.warehouse.WarehouseExportBatchStore;
import com.example.ecsite.service.warehouse.WarehouseFileOutputStore;
import com.example.ecsite.service.warehouse.WarehouseExportRunner;

/**
 * Feature131 C-2: warehouse export batches, read-only. Batches are created only by the 14:05 export, never from this
 * screen. Feature137-B: the files are written to the FTP folder (no download); the screen shows the file output
 * and offers the re-output of a failed one ({@link AdminWarehouseFileOutputController}). Past CSV downloads (V87)
 * remain visible as history.
 */
@Controller
public class AdminWarehouseExportController {

    private final WarehouseExportBatchStore batches;
    private final WarehouseExportRunner runner;
    private WarehouseFileOutputStore fileOutputs;

    public AdminWarehouseExportController(WarehouseExportBatchStore batches, WarehouseExportRunner runner) {
        this.batches = batches;
        this.runner = runner;
    }

    /** Feature137-B. */
    @Autowired
    void setFileOutputs(WarehouseFileOutputStore fileOutputs) {
        this.fileOutputs = fileOutputs;
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
        var output = fileOutputs.forBatch(id).orElse(null);
        model.addAttribute("output", output);
        model.addAttribute("outputRetries", output == null ? List.of() : fileOutputs.retries(output.id()));
        return "admin/warehouse-exports/detail";
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
