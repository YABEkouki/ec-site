package com.example.ecsite.service.warehouse;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

import com.example.ecsite.security.AdminUserDetails;

/**
 * Shared helpers of the warehouse files: CSV field quoting, SHA-256 and the administrator check. The Feature131
 * one-file CSV and its downloads were replaced by the two warehouse files ({@link WarehouseFileFormat}, Feature137);
 * the download audits (V87, V100) remain as history.
 */
public final class WarehouseExportCsvService {

    private WarehouseExportCsvService() {}

    static String escape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\r") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    public static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static AdminUserDetails requireAdmin() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))
                || !(authentication.getPrincipal() instanceof AdminUserDetails actor)
                || actor.getId() == null || actor.getId() <= 0
                || actor.getUsername() == null || actor.getUsername().isBlank() || actor.getUsername().length() > 100) {
            throw new AccessDeniedException("有効な管理者認証が必要です。");
        }
        return actor;
    }
}
