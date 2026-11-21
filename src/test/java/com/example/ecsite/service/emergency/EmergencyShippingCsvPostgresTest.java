package com.example.ecsite.service.emergency;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.warehouse.WarehouseExportCsvService;
import com.example.ecsite.support.EmergencyShippingFixtures;
import com.example.ecsite.support.EmergencyShippingFixtures.Admin;

/** Feature136-C2: the per-order warehouse CSV of an emergency-shipped order and its download audit (V100). */
@SpringBootTest(properties = {"logging.level.root=WARN", "spring.jpa.show-sql=false"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EmergencyShippingCsvPostgresTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired EmergencyShippingCsvService csv;
    @Autowired MockMvc mvc;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private long order(String shippingName) {
        long user = jdbc.queryForObject("insert into users(username,password,created_at,updated_at) "
                + "values (?,'unused',localtimestamp,localtimestamp) returning id", Long.class, "f136c2-" + UUID.randomUUID());
        long order = jdbc.queryForObject("insert into orders(user_id,total_amount,change_deadline_at,content_revision,"
                + "shipping_name,shipping_postal_code,shipping_prefecture,shipping_city,shipping_address_line,shipping_phone) "
                + "values (?,1800,timezone('Asia/Tokyo',clock_timestamp())-interval '1 hour',2,?,'100-0001','東京都','千代田区',"
                + "'千代田1-1 \"本館\"','03-0000-0000') returning id", Long.class, user, shippingName);
        long tax = jdbc.queryForObject("select min(id) from tax_categories", Long.class);
        long category = jdbc.queryForObject("insert into categories(name) values (?) returning id", Long.class, "f136c2-" + UUID.randomUUID());
        String itemInsert = "insert into order_items(order_id,product_id,product_name,price,quantity,subtotal,category_id,category_name,"
                + "tax_category_id,tax_category_code,tax_category_name,tax_rate) values (?,?,?,600,?,?,?,'c',?,'STANDARD','標準',10)";
        long first = product(category, tax);
        long second = product(category, tax);
        jdbc.update(itemInsert, order, second, "商品B", 1, 600, category, tax);
        jdbc.update(itemInsert, order, first, "商品A, 特別", 2, 1200, category, tax);
        return order;
    }

    private long product(long category, long tax) {
        return jdbc.queryForObject("insert into products(name,price,category_id,tax_category_id) values ('f136c2',600,?,?) returning id",
                Long.class, category, tax);
    }

    private long admin(String prefix, boolean emergencyAllowed) {
        if (emergencyAllowed) return EmergencyShippingFixtures.permittedAdmin(jdbc, prefix).id();
        return jdbc.queryForObject("insert into admin_accounts(username,password,enabled,created_at,updated_at) "
                + "values (?,'unused',true,localtimestamp,localtimestamp) returning id", Long.class, prefix + "-" + UUID.randomUUID());
    }

    private String username(long admin) {
        return jdbc.queryForObject("select username from admin_accounts where id=?", String.class, admin);
    }

    private static AdminUserDetails details(long id, String username) {
        return new AdminUserDetails(id, username, "unused", true, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private void loginAs(long admin) {
        var actor = details(admin, username(admin));
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(actor, null, actor.getAuthorities()));
    }

    private RequestPostProcessor adminUser(long admin) {
        return user(details(admin, username(admin)));
    }

    private long recordId(long order) {
        return jdbc.queryForObject("select id from emergency_shipping_preparations where order_id=?", Long.class, order);
    }

    private int downloads(long order) {
        return jdbc.queryForObject("select count(*) from emergency_shipping_csv_downloads where order_id=?", Integer.class, order);
    }

    private static void rejects(String sqlState, String message, Runnable action) {
        Throwable failure = catchThrowable(action::run);
        assertThat(failure).as("expected %s", message).isNotNull();
        while (!(failure instanceof SQLException) && failure.getCause() != null) failure = failure.getCause();
        assertThat(failure).isInstanceOf(SQLException.class).hasMessageContaining(message);
        assertThat(((SQLException) failure).getSQLState()).isEqualTo(sqlState);
    }

    @Test
    void downloadUsesTheWarehouseFormatAndIsRecordedWithItsDigest() throws Exception {
        Admin preparer = EmergencyShippingFixtures.permittedAdmin(jdbc, "c2-prep");
        long order = order("山田 太郎");
        LocalDateTime preparedAt = EmergencyShippingFixtures.prepare(jdbc, order, preparer, null, null);
        long record = recordId(order);
        long downloader = admin("c2-plain", false); // every administrator may download, not only permitted ones
        loginAs(downloader);

        var file = csv.download(order);
        String date = preparedAt.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String expected = WarehouseExportCsvService.HEADER + "\r\n"
                + "E" + record + "," + date + "," + order + ",1,山田 太郎,100-0001,東京都,千代田区,\"千代田1-1 \"\"本館\"\"\",03-0000-0000,"
                + jdbc.queryForObject("select product_id from order_items where order_id=? order by id limit 1", Long.class, order)
                + ",商品B,1\r\n"
                + "E" + record + "," + date + "," + order + ",2,山田 太郎,100-0001,東京都,千代田区,\"千代田1-1 \"\"本館\"\"\",03-0000-0000,"
                + jdbc.queryForObject("select product_id from order_items where order_id=? order by id desc limit 1", Long.class, order)
                + ",\"商品A, 特別\",2\r\n";
        assertThat(file.content()).startsWith(0xEF, 0xBB, 0xBF);
        assertThat(new String(file.content(), 3, file.content().length - 3, StandardCharsets.UTF_8)).isEqualTo(expected);
        assertThat(file.rowCount()).isEqualTo(2);
        assertThat(file.filename()).matches("emergency-shipping-" + order + "-\\d{8}-\\d{6}\\.csv");

        var row = jdbc.queryForMap("select * from emergency_shipping_csv_downloads where order_id=?", order);
        assertThat(row.get("emergency_shipping_preparation_id")).isEqualTo(record);
        assertThat(row.get("admin_account_id")).isEqualTo(downloader);
        assertThat(row.get("admin_username")).isEqualTo(username(downloader));
        assertThat(row.get("row_count")).isEqualTo(2);
        assertThat(row.get("business_date").toString()).isEqualTo(preparedAt.toLocalDate().toString());
        assertThat((byte[]) row.get("content_sha256")).isEqualTo(MessageDigest.getInstance("SHA-256").digest(file.content()));
        LocalDateTime downloadedAt = ((java.sql.Timestamp) row.get("downloaded_at")).toLocalDateTime();
        assertThat(file.filename()).isEqualTo("emergency-shipping-" + order + "-"
                + downloadedAt.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".csv");
        LocalDateTime dbNow = jdbc.queryForObject("select timezone('Asia/Tokyo',clock_timestamp())::timestamp", LocalDateTime.class);
        assertThat(downloadedAt).isBetween(dbNow.minusMinutes(1), dbNow);

        // A second download is byte-identical and recorded again; the list is newest first.
        var again = csv.download(order);
        assertThat(again.content()).isEqualTo(file.content());
        assertThat(downloads(order)).isEqualTo(2);
        var listed = csv.downloads(order);
        assertThat(listed).hasSize(2);
        assertThat(listed.getFirst().id()).isGreaterThan(listed.get(1).id());
        assertThat(listed.getFirst().filename()).isEqualTo(again.filename());
    }

    @Test
    void onlyAnEmergencyPreparedOrderCanBeDownloaded() {
        loginAs(admin("c2-refuse", false));
        long unprepared = order("未準備");
        assertThatThrownBy(() -> csv.download(unprepared))
                .isInstanceOf(WarehouseExportCsvService.NotDownloadableException.class).hasMessageContaining("緊急出荷として");
        assertThatThrownBy(() -> csv.download(Long.MAX_VALUE))
                .isInstanceOf(WarehouseExportCsvService.NotDownloadableException.class).hasMessageContaining("注文が見つかりません");
        assertThat(downloads(unprepared)).isZero();
    }

    @Test
    void downloadRequiresAnAdministrator() {
        Admin preparer = EmergencyShippingFixtures.permittedAdmin(jdbc, "c2-auth");
        long order = order("認証");
        EmergencyShippingFixtures.prepare(jdbc, order, preparer, null, null);
        assertThatThrownBy(() -> csv.download(order)).isInstanceOf(AccessDeniedException.class);
        assertThat(downloads(order)).isZero();
    }

    @Test
    void theDatabaseGuardsTheDownloadAudit() {
        Admin preparer = EmergencyShippingFixtures.permittedAdmin(jdbc, "c2-db");
        long order = order("DB");
        LocalDateTime preparedAt = EmergencyShippingFixtures.prepare(jdbc, order, preparer, null, null);
        long record = recordId(order);
        long admin = admin("c2-db-plain", false);
        String insert = "insert into emergency_shipping_csv_downloads(emergency_shipping_preparation_id,order_id,admin_account_id,"
                + "admin_username,business_date,row_count,content_sha256,filename,downloaded_at) values (?,?,?,?,?,?,?,'x.csv',?)";
        byte[] digest = new byte[32];
        LocalDateTime forged = LocalDateTime.of(2000, 1, 1, 0, 0);
        java.time.LocalDate day = preparedAt.toLocalDate();

        rejects("23514", "does not match the order", () -> jdbc.update(insert, record, order, admin, username(admin), day, 3, digest, forged));
        rejects("23514", "does not match the order", () -> jdbc.update(insert, record, order, admin, username(admin), day.minusDays(1), 2, digest, forged));
        rejects("42501", "cannot download", () -> jdbc.update(insert, record, order, admin, "other-name", day, 2, digest, forged));
        long other = order("別注文");
        rejects("23514", "prepared by its emergency shipping record", () -> jdbc.update(insert, record, other, admin, username(admin), day, 2, digest, forged));
        jdbc.update("update admin_accounts set enabled=false where id=?", admin);
        rejects("42501", "cannot download", () -> jdbc.update(insert, record, order, admin, username(admin), day, 2, digest, forged));
        jdbc.update("update admin_accounts set enabled=true where id=?", admin);

        jdbc.update(insert, record, order, admin, username(admin), day, 2, digest, forged);
        var row = jdbc.queryForMap("select downloaded_at,filename from emergency_shipping_csv_downloads where order_id=?", order);
        LocalDateTime downloadedAt = ((java.sql.Timestamp) row.get("downloaded_at")).toLocalDateTime();
        assertThat(downloadedAt).isNotEqualTo(forged);
        assertThat(row.get("filename")).isEqualTo("emergency-shipping-" + order + "-"
                + downloadedAt.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".csv");

        rejects("55000", "append-only", () -> jdbc.update("update emergency_shipping_csv_downloads set row_count=3 where order_id=?", order));
        rejects("55000", "append-only", () -> jdbc.update("delete from emergency_shipping_csv_downloads where order_id=?", order));
        rejects("55000", "append-only", () -> jdbc.execute("truncate emergency_shipping_csv_downloads"));
        assertThat(downloads(order)).isEqualTo(1);
    }

    @Test
    void httpDownloadIsAnAdminPostWithCsrf() throws Exception {
        Admin preparer = EmergencyShippingFixtures.permittedAdmin(jdbc, "c2-http");
        long order = order("HTTP");
        EmergencyShippingFixtures.prepare(jdbc, order, preparer, null, null);
        long admin = admin("c2-http-plain", false);
        String url = "/admin/orders/" + order + "/emergency-shipping/csv";

        var response = mvc.perform(post(url).with(adminUser(admin)).with(csrf())).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith("text/csv");
        assertThat(response.getHeader("Content-Disposition")).contains("filename=\"emergency-shipping-" + order + "-");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(downloads(order)).isEqualTo(1);

        assertThat(mvc.perform(post(url).with(adminUser(admin))).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(post(url).with(csrf())).andReturn().getResponse().getStatus()).isEqualTo(302);
        assertThat(downloads(order)).isEqualTo(1);

        long unprepared = order("未準備HTTP");
        var refused = mvc.perform(post("/admin/orders/" + unprepared + "/emergency-shipping/csv").with(adminUser(admin)).with(csrf()))
                .andReturn();
        assertThat(refused.getResponse().getRedirectedUrl()).isEqualTo("/admin/orders/" + unprepared);
        assertThat(refused.getFlashMap().get("errorMessage")).asString().contains("緊急出荷として");
        assertThat(mvc.perform(post("/admin/orders/abc/emergency-shipping/csv").with(adminUser(admin)).with(csrf()))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
    }
}
