package com.example.ecsite.service.payment.safety;

import java.io.*;
import java.lang.reflect.RecordComponent;
import java.sql.*;
import java.time.*;
import java.util.*;
import com.example.ecsite.service.payment.safety.PaymentSafetyReadFacts.*;
import com.example.ecsite.service.payment.safety.PaymentSafetyReadContext.Limits;

/** Standalone JDBC reader, intentionally not a Spring bean. No financial calls or writes.
 * Caller owns a writable READ COMMITTED transaction and must roll it back on ANY failure.
 * Consistency requires all writers to take Order before Payment, as existing financial writers
 * do. Payment membership is sampled before waiting for locks and all row sets are rechecked;
 * detected drift fails closed. Arbitrary SQL writers that ignore this protocol cannot be made
 * serializable by a READ COMMITTED reader (in particular, writes after the final recheck).
 * Do not acquire other Order/Product locks before calling this API in a conflicting order.
 */
public final class JdbcPaymentSafetySnapshotReader {
    public Optional<PaymentSafetyReadFacts> readLocked(Connection connection,long orderId,PaymentSafetyReadContext context) throws SQLException {
        Objects.requireNonNull(connection);Objects.requireNonNull(context);
        if(orderId<=0)throw new IllegalArgumentException("Positive order ID required");
        if(connection.getAutoCommit() || connection.isReadOnly() || connection.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)
            throw new SQLNonTransientException("Writable autoCommit=false READ COMMITTED transaction required","25000");
        Limits limits=context.limits();
        String previous;
        try(var s=statement(connection,"select current_setting('transaction_isolation'),current_setting('transaction_read_only'),current_setting('lock_timeout')",limits);
                var r=s.executeQuery()) {
            if(!r.next() || !"read committed".equals(r.getString(1)) || !"off".equals(r.getString(2)))
                throw new SQLNonTransientException("Actual database transaction is not writable READ COMMITTED","25000");
            previous=r.getString(3);
        }
        setting(connection,limits.lockTimeoutMillis()+"ms",limits);
        Throwable failure=null;
        try {
            var budget=new Budget(limits.inputBytes());
            budget.add(context.verificationProfiles());budget.add(context.timeOrigins());
            var orders=rows(connection,OrderRow.class,"orders o","o.id=?",orderId,true,1,limits,budget);
            if(orders.isEmpty())return Optional.empty();
            var before=paymentIds(connection,orderId,limits);
            var facts=readSets(connection,orders.getFirst(),context,true,budget);
            if(!before.equals(facts.payments().stream().map(PaymentRow::id).toList()))throw drift();
            // READ COMMITTED has statement snapshots, not a transaction snapshot. Detect observed
            // membership/data changes without ever locking a foreign Order or reversing lock order.
            var again=readSets(connection,orders.getFirst(),context,false,new Budget(limits.inputBytes()));
            if(!facts.equals(again))throw drift();
            return Optional.of(facts);
        } catch(SQLException | RuntimeException | Error e) { failure=e;throw e; }
        finally {
            try { setting(connection,previous,limits); }
            catch(SQLException restore) { if(failure!=null)failure.addSuppressed(restore);else throw restore; }
        }
    }

    private PaymentSafetyReadFacts readSets(Connection c,OrderRow order,PaymentSafetyReadContext context,boolean lock,Budget budget) throws SQLException {
        var l=context.limits();long id=order.id();
        var payments=rows(c,PaymentRow.class,"payments p","p.order_id=?",id,lock,l.payments(),l,budget);
        var txs=rows(c,TransactionRow.class,"payment_transactions t","t.payment_id in (select id from payments where order_id=?)",id,false,l.transactions(),l,budget);
        var history=rows(c,HistoryRow.class,"order_status_histories h","h.order_id=?",id,false,l.statusHistory(),l,budget);
        var requests=rows(c,CaptureRow.class,"capture_requests c","c.payment_id in (select id from payments where order_id=?)",id,false,l.captureRequests(),l,budget);
        var paymentIds=new HashSet<Long>();for(var p:payments) { if(!Objects.equals(p.orderId(),id))throw drift();paymentIds.add(p.id()); }
        var txIds=new HashSet<Long>();for(var t:txs) { if(!paymentIds.contains(t.paymentId()))throw drift();txIds.add(t.id()); }
        for(var h:history)if(!Objects.equals(h.orderId(),id))throw drift();
        for(var r:requests)if(!paymentIds.contains(r.paymentId()) || !txIds.contains(r.captureTransactionId()))throw drift();
        var result=new PaymentSafetyReadFacts(order,payments,txs,history,requests,context.verificationProfiles(),context.timeOrigins());
        // Bound the complete canonical input (including framing and explicit evidence), not only bodies.
        if(PaymentSafetyInputFingerprint.encodedSize(result)>l.inputBytes())throw limit();
        return result;
    }
    private List<Long> paymentIds(Connection c,long order,Limits l) throws SQLException {
        var result=new ArrayList<Long>();
        try(var s=statement(c,"select id from payments where order_id=? order by id limit ?",l)) {
            s.setLong(1,order);s.setInt(2,l.payments()+1);
            try(var r=s.executeQuery()) { while(r.next()) { if(result.size()==l.payments())throw limit();result.add(r.getObject(1,Long.class)); } }
        }
        return result;
    }
    private <T> List<T> rows(Connection c,Class<T> type,String from,String where,long id,boolean lock,int maximum,Limits l,Budget budget) throws SQLException {
        var fields=type.getRecordComponents();String alias=from.substring(from.lastIndexOf(' ')+1);
        String columns=String.join(",",Arrays.stream(fields).map(f->alias+"."+column(f)).toList());
        String sql="select "+columns+" from "+from+" where "+where+" order by "+alias+".id limit ?"+(lock?" for update":"");
        var result=new ArrayList<T>();
        try(var s=statement(c,sql,l)) {
            s.setLong(1,id);s.setInt(2,maximum+1);
            try(var r=s.executeQuery()) {
                while(r.next()) {
                    if(result.size()==maximum)throw limit();
                    Object[] values=new Object[fields.length];
                    for(int i=0;i<fields.length;i++)values[i]=value(r,i+1,fields[i].getType(),budget);
                    try {
                        T row=type.getDeclaredConstructor(Arrays.stream(fields).map(RecordComponent::getType).toArray(Class<?>[]::new)).newInstance(values);
                        budget.add(row);result.add(row);
                    } catch(ReflectiveOperationException e) { throw new SQLDataException("Cannot map complete raw row","22000",e); }
                }
            }
        }
        return List.copyOf(result);
    }
    private static String column(RecordComponent component) { return component.getName().replaceAll("([A-Z])","_$1").toLowerCase(Locale.ROOT); }
    private Object value(ResultSet row,int index,Class<?> type,Budget budget) throws SQLException {
        if(type==Bytes.class) {
            try(var in=row.getBinaryStream(index)) {
                if(in==null)return null;
                byte[] bytes=in.readNBytes((int)Math.min(budget.remaining()+1,Integer.MAX_VALUE));
                if(bytes.length>budget.remaining())throw limit();
                return new Bytes(bytes);
            } catch(IOException e) { throw new SQLDataException("Cannot read complete binary field","22000",e); }
        }
        if(type==Instant.class) { var t=row.getObject(index,OffsetDateTime.class);return t==null?null:t.toInstant(); }
        if(type==String.class || type==Long.class || type==Integer.class || type==Short.class || type==LocalDateTime.class)
            return row.getObject(index,type);
        throw new SQLDataException("Unsupported raw field type","22000");
    }
    private static PreparedStatement statement(Connection c,String sql,Limits l) throws SQLException {
        var s=c.prepareStatement(sql);
        try { s.setFetchSize(l.fetchSize());s.setQueryTimeout(l.queryTimeoutSeconds());return s; }
        catch(SQLException | RuntimeException e) { try {s.close();}catch(SQLException close){e.addSuppressed(close);}throw e; }
    }
    private static void setting(Connection c,String value,Limits l) throws SQLException {
        try(var s=statement(c,"select set_config('lock_timeout',?,true)",l)) {s.setString(1,value);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Cannot configure lock timeout");}}
    }
    private static SQLDataException limit() { return new SQLDataException("Payment safety input limit exceeded; partial facts discarded","54000"); }
    private static SQLTransactionRollbackException drift() { return new SQLTransactionRollbackException("Payment safety row sets changed while reading; rollback and retry required","40001"); }
    private static final class Budget {
        private final long maximum;
        private long used;
        Budget(long maximum) {this.maximum=maximum;}
        long remaining() {return maximum-used;}
        void add(Object value) throws SQLDataException {used+=PaymentSafetyInputFingerprint.encodedSize(value);if(used>maximum)throw limit();}
    }
}
