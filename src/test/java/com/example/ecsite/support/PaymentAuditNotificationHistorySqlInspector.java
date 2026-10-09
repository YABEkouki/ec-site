package com.example.ecsite.support;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/** Per-test SQL observation without values or production instrumentation. */
public class PaymentAuditNotificationHistorySqlInspector implements StatementInspector {
    private static final ThreadLocal<List<String>> SQL = new ThreadLocal<>();
    private static final ThreadLocal<Consumer<String>> BEFORE = new ThreadLocal<>();
    public static void start() { SQL.set(new ArrayList<>()); }
    public static List<String> statements() { return List.copyOf(SQL.get()); }
    public static void before(Consumer<String> callback) { BEFORE.set(callback); }
    public static void clear() { SQL.remove(); BEFORE.remove(); }
    @Override public String inspect(String sql) {
        if (SQL.get() != null) SQL.get().add(sql);
        var callback = BEFORE.get();
        if (callback != null) callback.accept(sql);
        return sql;
    }
}
