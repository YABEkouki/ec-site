package com.example.ecsite.service.emergency;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.emergency.AdminEmergencyShippingQueries.FileOutput;
import com.example.ecsite.service.emergency.AdminEmergencyShippingQueries.Shipment;
import com.example.ecsite.support.EmergencyShippingFixtures;
import com.example.ecsite.support.EmergencyShippingFixtures.Admin;

/** Feature136-C3: emergency shipping list/audit read models, dashboard counts and the list screen (Feature137-C: file output). */
@SpringBootTest(properties = {"logging.level.root=WARN", "spring.jpa.show-sql=false"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminEmergencyShippingQueriesPostgresTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminEmergencyShippingQueries queries;
    @Autowired MockMvc mvc;

    /** An unprepared order with one item (items must exist before preparation: V98 freezes them). */
    private long order() {
        long user = jdbc.queryForObject("insert into users(username,password,created_at,updated_at) "
                + "values (?,'unused',localtimestamp,localtimestamp) returning id", Long.class, "f136c3-" + UUID.randomUUID());
        long order = jdbc.queryForObject("insert into orders(user_id,total_amount,change_deadline_at,content_revision) "
                + "values (?,600,timezone('Asia/Tokyo',clock_timestamp())-interval '1 hour',0) returning id", Long.class, user);
        long tax = jdbc.queryForObject("select min(id) from tax_categories", Long.class);
        long category = jdbc.queryForObject("insert into categories(name) values (?) returning id", Long.class, "f136c3-" + UUID.randomUUID());
        long product = jdbc.queryForObject("insert into products(name,price,category_id,tax_category_id) values ('f136c3',600,?,?) returning id",
                Long.class, category, tax);
        jdbc.update("insert into order_items(order_id,product_id,product_name,price,quantity,subtotal,"
                + "category_id,category_name,tax_category_id,tax_category_code,tax_category_name,tax_rate) "
                + "values (?,?,'f136c3',600,1,600,?,'c',?,'STANDARD','標準',10)", order, product, category, tax);
        return order;
    }

    private void ship(long order) {
        jdbc.update("update orders set status='SHIPPED', shipped_at=localtimestamp, shipped_by_account_id=8, "
                + "shipped_by_username='shipper' where id=?", order);
    }

    private void download(long order, long admin, String username) {
        long record = jdbc.queryForObject("select id from emergency_shipping_preparations where order_id=?", Long.class, order);
        LocalDateTime preparedAt = jdbc.queryForObject("select prepared_at from emergency_shipping_preparations where id=?",
                LocalDateTime.class, record);
        jdbc.update("insert into emergency_shipping_csv_downloads(emergency_shipping_preparation_id,order_id,admin_account_id,"
                + "admin_username,business_date,row_count,content_sha256,filename,downloaded_at) "
                + "values (?,?,?,?,?,1,?,'',localtimestamp)", record, order, admin, username, preparedAt.toLocalDate(), new byte[32]);
    }

    /** Feature137-C: outcomes of the V102 output, through the transitions its guard allows. */
    private void fileWritten(long order) {
        jdbc.update("update warehouse_file_outputs set state='WRITTEN', attempts=attempts+1, header_sha256=?, detail_sha256=?"
                + " where order_id=?", new byte[32], new byte[32], order);
    }

    private void fileFailed(long order) {
        jdbc.update("update warehouse_file_outputs set state='FAILED', attempts=attempts+1, last_error='x' where order_id=?", order);
    }

    private static List<Long> ids(AdminEmergencyShippingQueries.Result result) {
        return result.rows().stream().map(AdminEmergencyShippingQueries.Row::orderId).toList();
    }

    private AdminUserDetails admin(Admin admin) {
        return new AdminUserDetails(admin.id(), admin.username(), "unused", true, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @Test
    void listFiltersAndCountsEmergencyShipments() {
        var before = queries.summary();
        Admin admin = EmergencyShippingFixtures.permittedAdmin(jdbc, "c3-list");
        long pending = order(), failed = order(), written = order(), shipped = order(), notEmergency = order();
        // Feature138-A (V103): this order has no card payment, so it is emergency-shipped as paid and ships without a capture.
        jdbc.update("update orders set status='PAID', paid_at=localtimestamp where id=?", shipped);
        for (long id : List.of(pending, failed, written, shipped)) {
            EmergencyShippingFixtures.prepare(jdbc, id, admin, null, null);
        }
        fileFailed(failed); fileWritten(written); fileWritten(shipped);
        download(written, admin.id(), admin.username());
        download(written, admin.id(), admin.username());
        ship(shipped);

        assertThat(queries.summary().unshipped() - before.unshipped()).isEqualTo(3);

        LocalDate today = queries.today();
        var all = queries.list(today, today, Shipment.ALL, FileOutput.ALL, 0);
        assertThat(ids(all)).containsSubsequence(shipped, written, failed, pending).doesNotContain(notEmergency);
        assertThat(ids(queries.list(today, today, Shipment.UNSHIPPED, FileOutput.ALL, 0)))
                .contains(pending, failed, written).doesNotContain(shipped);
        assertThat(ids(queries.list(today, today, Shipment.SHIPPED, FileOutput.ALL, 0)))
                .contains(shipped).doesNotContain(pending, failed, written);
        assertThat(ids(queries.list(null, null, Shipment.ALL, FileOutput.FAILED, 0)))
                .contains(failed).doesNotContain(pending, written, shipped);
        assertThat(ids(queries.list(null, null, Shipment.ALL, FileOutput.PENDING, 0)))
                .contains(pending).doesNotContain(failed, written, shipped);
        assertThat(ids(queries.list(null, null, Shipment.ALL, FileOutput.WRITTEN, 0)))
                .contains(written, shipped).doesNotContain(pending, failed);
        // Every emergency shipping recorded from V102 on has an output.
        assertThat(ids(queries.list(null, null, Shipment.ALL, FileOutput.NONE, 0))).doesNotContain(pending, failed, written, shipped);
        assertThat(ids(queries.list(today.minusDays(2), today.minusDays(1), Shipment.ALL, FileOutput.ALL, 0)))
                .doesNotContain(pending, failed, written, shipped);
        assertThat(ids(queries.list(today.plusDays(1), null, Shipment.ALL, FileOutput.ALL, 0))).isEmpty();

        var row = all.rows().stream().filter(r -> r.orderId() == written).findFirst().orElseThrow();
        assertThat(row.fileState()).isEqualTo("WRITTEN");
        assertThat(row.getFileLabel()).isEqualTo("出力済み");
        assertThat(row.fileWrittenAt()).isNotNull();
        assertThat(row.downloadCount()).isEqualTo(2);
        assertThat(row.isUnshipped()).isTrue();
        assertThat(row.mode()).isEqualTo(EmergencyShippingMode.AFTER_DEADLINE);
        assertThat(row.adminUsername()).isEqualTo(admin.username());
        assertThat(row.reason()).isEqualTo("test fixture");
        assertThat(row.getShipperLabel()).isEmpty();
        var failedRow = all.rows().stream().filter(r -> r.orderId() == failed).findFirst().orElseThrow();
        assertThat(failedRow.isFileFailed()).isTrue();
        assertThat(failedRow.getFileLabel()).isEqualTo("出力失敗");
        var pendingRow = all.rows().stream().filter(r -> r.orderId() == pending).findFirst().orElseThrow();
        assertThat(pendingRow.getFileLabel()).isEqualTo("出力待ち");
        assertThat(pendingRow.fileWrittenAt()).isNull();
        assertThat(pendingRow.downloadCount()).isZero();
        var shippedRow = all.rows().stream().filter(r -> r.orderId() == shipped).findFirst().orElseThrow();
        assertThat(shippedRow.isShipped()).isTrue();
        assertThat(shippedRow.shippedAt()).isNotNull();
        // Feature138-B: the shipper of a shipped emergency order (here an administrator).
        assertThat(shippedRow.shippedByAccountId()).isEqualTo(8L);
        assertThat(shippedRow.getShipperLabel()).isEqualTo("shipper");
    }

    @Test
    void listPagesFiftyRowsNewestFirst() {
        Admin admin = EmergencyShippingFixtures.permittedAdmin(jdbc, "c3-page");
        long first = 0, last = 0;
        for (int i = 0; i < AdminEmergencyShippingQueries.PAGE_SIZE + 1; i++) {
            long id = order();
            EmergencyShippingFixtures.prepare(jdbc, id, admin, null, null);
            if (i == 0) first = id;
            last = id;
        }
        var page0 = queries.list(null, null, Shipment.ALL, FileOutput.ALL, 0);
        assertThat(page0.rows()).hasSize(AdminEmergencyShippingQueries.PAGE_SIZE);
        assertThat(page0.rows().getFirst().orderId()).isEqualTo(last);
        assertThat(page0.hasNext()).isTrue();
        assertThat(page0.total()).isGreaterThanOrEqualTo(AdminEmergencyShippingQueries.PAGE_SIZE + 1);
        var lastPage = queries.list(null, null, Shipment.ALL, FileOutput.ALL, (int) ((page0.total() - 1) / AdminEmergencyShippingQueries.PAGE_SIZE));
        assertThat(lastPage.hasNext()).isFalse();
        assertThat(ids(queries.list(null, null, Shipment.ALL, FileOutput.ALL, 1))).contains(first);
        assertThat(queries.list(null, null, Shipment.ALL, FileOutput.ALL, -3).page()).isZero();
    }

    @Test
    void permissionsAreListedFromTheDatabase() {
        Admin granted = EmergencyShippingFixtures.permittedAdmin(jdbc, "c3-perm");
        Admin revoked = EmergencyShippingFixtures.permittedAdmin(jdbc, "c3-revoked");
        jdbc.update("update admin_accounts set emergency_shipping_allowed=false where id=?", revoked.id());
        assertThat(queries.permittedAdmins()).extracting(AdminEmergencyShippingQueries.PermittedAdmin::username)
                .contains(granted.username()).doesNotContain(revoked.username());
        var histories = queries.permissionHistories();
        assertThat(histories.getFirst().adminUsername()).isEqualTo(revoked.username());
        assertThat(histories.getFirst().allowed()).isFalse();
        assertThat(histories.getFirst().changedByDbUser()).isNotBlank();
        assertThat(histories).extracting(AdminEmergencyShippingQueries.PermissionHistory::adminUsername).contains(granted.username());
    }

    @Test
    void listScreenRendersEscapedRowsAndPermissions() throws Exception {
        Admin admin = EmergencyShippingFixtures.permittedAdmin(jdbc, "<b>c3</b>");
        long order = order();
        EmergencyShippingFixtures.prepare(jdbc, order, admin, null, null);
        var viewer = admin(admin);

        var defaultResult = mvc.perform(get("/admin/emergency-shipments").with(user(viewer))).andReturn();
        assertThat(defaultResult.getResponse().getStatus()).isEqualTo(200);
        LocalDate today = queries.today();
        assertThat(defaultResult.getModelAndView().getModel().get("from")).isEqualTo(today.minusDays(29));
        assertThat(defaultResult.getModelAndView().getModel().get("to")).isEqualTo(today);
        String html = defaultResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("緊急出荷一覧", "/admin/orders/" + order, "変更期限後", "&lt;b&gt;c3&lt;/b&gt;", "未出荷",
                "倉庫ファイル", "出力待ち", "emergency-permitted-admins", "emergency-permission-histories", "付与")
                .doesNotContain("<b>c3</b>", "未ダウンロード", "csv=");

        // An empty period (the dashboard links) means all days.
        var allDays = mvc.perform(get("/admin/emergency-shipments").param("from", "").param("to", "")
                .param("shipment", "UNSHIPPED").param("file", "PENDING").with(user(viewer))).andReturn();
        assertThat(allDays.getModelAndView().getModel().get("from")).isNull();
        assertThat(allDays.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("/admin/orders/" + order);

        var reversed = mvc.perform(get("/admin/emergency-shipments").param("from", today.toString())
                .param("to", today.minusDays(1).toString()).with(user(viewer))).andReturn();
        assertThat(reversed.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("期間の開始日が終了日より後になっています。", "emergency-shipment-none");

        assertThat(mvc.perform(get("/admin/emergency-shipments").param("shipment", "BOGUS").with(user(viewer)))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(get("/admin/emergency-shipments").param("from", "2026-13-01").with(user(viewer)))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(get("/admin/emergency-shipments")).andReturn().getResponse().getStatus()).isEqualTo(302);
    }
}
