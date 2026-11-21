package com.example.ecsite.service.emergency;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Feature136-C2 history: the per-order emergency CSV downloads recorded before Feature137-C (V100). The download itself
 * was replaced by the warehouse files written when the emergency shipping is started (V102); the audit rows remain,
 * append-only, and are shown on the order detail as history.
 */
@Service
public class EmergencyShippingCsvService {

    public record DownloadRecord(long id, String adminUsername, LocalDateTime downloadedAt, int rowCount, String filename) {}

    private final JdbcTemplate jdbc;

    public EmergencyShippingCsvService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<DownloadRecord> downloads(long orderId) {
        return jdbc.query("""
                select id,admin_username,downloaded_at,row_count,filename from emergency_shipping_csv_downloads
                where order_id=? order by id desc
                """, (r, n) -> new DownloadRecord(r.getLong("id"), r.getString("admin_username"),
                        r.getObject("downloaded_at", LocalDateTime.class), r.getInt("row_count"), r.getString("filename")),
                orderId);
    }
}
