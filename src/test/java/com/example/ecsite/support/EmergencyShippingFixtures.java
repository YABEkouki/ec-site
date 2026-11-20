package com.example.ecsite.support;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Feature136 test support. An administrator shipping preparation needs a permitted administrator and its
 * emergency shipping record in the same transaction (V97); fixtures go through the real triggers.
 */
public final class EmergencyShippingFixtures {
    private EmergencyShippingFixtures() {
    }

    public record Admin(long id, String username) {
    }

    /** Creates an enabled administrator with the emergency shipping permission (granted by SQL, as in operation). */
    public static Admin permittedAdmin(JdbcTemplate jdbc, String usernamePrefix) {
        String username = usernamePrefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        long id = jdbc.queryForObject("""
                insert into admin_accounts(username,password,enabled,created_at,updated_at,emergency_shipping_allowed)
                values (?,'{noop}unused',true,localtimestamp,localtimestamp,true) returning id
                """, Long.class, username);
        return new Admin(id, username);
    }

    /** Same, with an exact username (must be unique in the database). */
    public static Admin permittedAdminNamed(JdbcTemplate jdbc, String username) {
        long id = jdbc.queryForObject("""
                insert into admin_accounts(username,password,enabled,created_at,updated_at,emergency_shipping_allowed)
                values (?,'{noop}unused',true,localtimestamp,localtimestamp,true) returning id
                """, Long.class, username);
        return new Admin(id, username);
    }

    /**
     * Makes the fixed test administrator (id, username) exist with the permission. Existing fake ids used by
     * older tests (e.g. 9 "shipper") become real rows; an id already taken by another username fails loudly.
     */
    public static Admin ensurePermittedAdmin(JdbcTemplate jdbc, long id, String username) {
        jdbc.update("""
                insert into admin_accounts(id,username,password,enabled,created_at,updated_at,emergency_shipping_allowed)
                select ?,?,'{noop}unused',true,localtimestamp,localtimestamp,true
                where not exists(select 1 from admin_accounts where id=?)
                """, id, username, id);
        jdbc.queryForObject("select setval(pg_get_serial_sequence('admin_accounts','id'),(select max(id) from admin_accounts))",
                Long.class);
        grant(jdbc, id);
        String stored = jdbc.queryForObject("select username from admin_accounts where id=?", String.class, id);
        if (!username.equals(stored)) {
            throw new IllegalStateException("admin id " + id + " is already used by " + stored);
        }
        return new Admin(id, username);
    }

    /** Operator SQL grant (V97 records the history). */
    public static void grant(JdbcTemplate jdbc, long adminId) {
        jdbc.update("update admin_accounts set emergency_shipping_allowed=true where id=? and not emergency_shipping_allowed",
                adminId);
    }

    /** Starts emergency shipping through the service with the order's current revision (as the detail screen). */
    public static com.example.ecsite.service.ShippingPreparationStartResult start(
            com.example.ecsite.service.OrderShippingPreparationService service, JdbcTemplate jdbc, long orderId) {
        Integer revision = jdbc.queryForList("select content_revision from orders where id=?", Integer.class, orderId)
                .stream().findFirst().orElse(0);
        return service.start(orderId, "test fixture", revision);
    }

    /** Fixed administrator used by older fixtures as the preparer (id 7, "preparer"). */
    public static final Admin PREPARER = new Admin(7L, "preparer");

    /**
     * Inside the caller's transaction, for a persisted and still unprepared order entity: writes the record,
     * then sets the preparation on the entity (flushed with the transaction).
     */
    public static void begin(JdbcTemplate jdbc, com.example.ecsite.entity.Order order, Admin admin, LocalDateTime at) {
        ensurePermittedAdmin(jdbc, admin.id(), admin.username());
        record(jdbc, order.getId(), admin, at);
        order.beginShippingPreparation(at, admin.id(), admin.username());
    }

    /**
     * Writes the emergency record for an unprepared order. The caller must set the order's preparation to
     * (preparedAt, admin) in the same transaction; with an auto-commit JdbcTemplate use {@link #prepare}.
     */
    public static void record(JdbcTemplate jdbc, long orderId, Admin admin, LocalDateTime preparedAt) {
        jdbc.update("""
                insert into emergency_shipping_preparations(order_id,admin_account_id,admin_username,reason,prepared_at,
                    order_status,content_revision,total_amount,change_deadline_at,created_at)
                values (?,?,?,'test fixture',?,'ORDERED',0,0,localtimestamp,localtimestamp)
                """, orderId, admin.id(), admin.username(), preparedAt);
    }

    /**
     * Records and starts an administrator preparation in one transaction (joins the caller's transaction when the
     * connection is not in auto-commit), applying {@code extraAssignments} (e.g. a shipment) in the same order
     * UPDATE. Returns the preparation time; preparedAt null = DB clock.
     */
    public static LocalDateTime prepare(JdbcTemplate jdbc, long orderId, Admin admin, LocalDateTime preparedAt,
            String extraAssignments, Object... extraArgs) {
        LocalDateTime at = preparedAt != null ? preparedAt
                : jdbc.queryForObject("select date_trunc('microseconds', timezone('Asia/Tokyo', clock_timestamp()))::timestamp",
                        LocalDateTime.class);
        String extra = extraAssignments == null || extraAssignments.isBlank() ? "" : "," + extraAssignments;
        Object[] args = new Object[3 + extraArgs.length + 1];
        args[0] = at; args[1] = admin.id(); args[2] = admin.username();
        System.arraycopy(extraArgs, 0, args, 3, extraArgs.length);
        args[args.length - 1] = orderId;
        String update = "update orders set shipping_preparation_started_at=?,shipping_preparation_started_by_account_id=?,"
                + "shipping_preparation_started_by_username=?" + extra + " where id=?";
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            record(jdbc, orderId, admin, at);
            jdbc.update(update, args);
            return at;
        }
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                JdbcTemplate local = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                record(local, orderId, admin, at);
                local.update(update, args);
                connection.commit();
            } catch (RuntimeException | java.sql.SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
            return null;
        });
        return at;
    }
}
