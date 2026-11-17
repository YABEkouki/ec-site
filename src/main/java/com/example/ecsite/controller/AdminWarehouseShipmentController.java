package com.example.ecsite.controller;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.service.warehouse.WarehouseShipmentCsvParser;
import com.example.ecsite.service.warehouse.WarehouseShipmentImportQueries;
import com.example.ecsite.service.warehouse.WarehouseShipmentImportService;

/**
 * Feature132-B: upload of the warehouse shipped CSV and the per-row outcomes. Uploading only records the rows;
 * the orders are shipped afterwards by the background worker (app.warehouse-shipment-import.enabled).
 */
@Controller
public class AdminWarehouseShipmentController {

    private final WarehouseShipmentImportService imports;
    private final WarehouseShipmentImportQueries queries;
    private final boolean workerEnabled;

    public AdminWarehouseShipmentController(WarehouseShipmentImportService imports, WarehouseShipmentImportQueries queries,
            @Value("${app.warehouse-shipment-import.enabled:false}") boolean workerEnabled) {
        this.imports = imports;
        this.queries = queries;
        this.workerEnabled = workerEnabled;
    }

    @GetMapping("/admin/warehouse-shipments")
    public String list(Model model) {
        model.addAttribute("imports", queries.list(60));
        model.addAttribute("workerEnabled", workerEnabled);
        model.addAttribute("header", WarehouseShipmentCsvParser.HEADER);
        model.addAttribute("maxRows", WarehouseShipmentCsvParser.MAX_ROWS);
        return "admin/warehouse-shipments/list";
    }

    @GetMapping("/admin/warehouse-shipments/{id}")
    public String detail(@PathVariable("id") long id, Model model) {
        var found = queries.find(id).orElse(null);
        if (found == null) return "redirect:/admin/warehouse-shipments";
        model.addAttribute("importRecord", found);
        model.addAttribute("lines", queries.lines(id));
        model.addAttribute("workerEnabled", workerEnabled);
        return "admin/warehouse-shipments/detail";
    }

    @PostMapping("/admin/warehouse-shipments")
    public String upload(@RequestParam(name = "file", required = false) MultipartFile file, RedirectAttributes redirect) {
        if (file == null || file.isEmpty()) {
            redirect.addFlashAttribute("errorMessage", "CSVファイルを選択してください。");
            return "redirect:/admin/warehouse-shipments";
        }
        if (file.getSize() > WarehouseShipmentCsvParser.MAX_BYTES) {
            redirect.addFlashAttribute("errorMessage", "ファイルが大きすぎます（上限 1MB）。");
            return "redirect:/admin/warehouse-shipments";
        }
        WarehouseShipmentImportService.Accepted accepted;
        try {
            accepted = imports.upload(file.getOriginalFilename(), file.getBytes());
        } catch (WarehouseShipmentCsvParser.InvalidFileException invalid) {
            redirect.addFlashAttribute("errorMessage", "取り込めませんでした：" + invalid.getMessage());
            return "redirect:/admin/warehouse-shipments";
        } catch (IOException unreadable) {
            redirect.addFlashAttribute("errorMessage", "ファイルを読み込めませんでした。");
            return "redirect:/admin/warehouse-shipments";
        }
        redirect.addFlashAttribute("message", accepted.duplicate()
                ? "同じ内容のファイルは取込み済みです。前回の取込み結果を表示します。"
                : "ファイルを取り込みました。処理待ちの行は順に発送処理されます。");
        return "redirect:/admin/warehouse-shipments/" + accepted.importId();
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
