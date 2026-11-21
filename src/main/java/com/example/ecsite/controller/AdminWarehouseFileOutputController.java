package com.example.ecsite.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.service.warehouse.WarehouseFileOutputStore;

/**
 * Feature137-B: administrator re-output of a failed warehouse file output (decision 11). Every administrator may
 * do it; the request is recorded (V102 warehouse_file_output_retries) and the output is written by the next pass.
 */
@Controller
public class AdminWarehouseFileOutputController {

    private final WarehouseFileOutputStore outputs;

    public AdminWarehouseFileOutputController(WarehouseFileOutputStore outputs) {
        this.outputs = outputs;
    }

    @PostMapping("/admin/warehouse-file-outputs/{id}/retry")
    public String retry(@PathVariable("id") long id, RedirectAttributes redirect) {
        var before = outputs.find(id).orElse(null);
        if (before == null) {
            redirect.addFlashAttribute("errorMessage", "倉庫ファイルの出力記録が見つかりません。");
            return "redirect:/admin/warehouse-exports";
        }
        try {
            outputs.retry(id);
            redirect.addFlashAttribute("successMessage", "倉庫ファイルの再出力を受け付けました。次の出力処理で書き出します。");
        } catch (WarehouseFileOutputStore.NotRetryableException refused) {
            redirect.addFlashAttribute("errorMessage", refused.getMessage());
        }
        return before.kind() == WarehouseFileOutputStore.Kind.REGULAR
                ? "redirect:/admin/warehouse-exports/" + before.batchId()
                : "redirect:/admin/orders/" + before.orderId();
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
