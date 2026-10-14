package com.example.ecsite.support.restart;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import com.example.ecsite.support.PaymentAuditNotificationHistoryFixtures;

/** Only a newly created, explicitly verified restart DB may be seeded. */
public final class ManualResendRestartFixtures {
    public static long seed(JdbcTemplate jdbc,String database){
        if(!database.startsWith("restart118_") || !database.equals(jdbc.queryForObject("select current_database()",String.class))
            || jdbc.queryForObject("select count(*) from payment_audit_notifications",Long.class)!=0)throw new IllegalStateException("Empty restart DB required");
        var fixtures=new PaymentAuditNotificationHistoryFixtures(jdbc);
        long n=fixtures.notification("EXHAUSTED",Instant.now());fixtures.item(n,"LATEST_FAILURE",false);
        jdbc.update("update payment_audit_notification_states set active=true,episode_no=?,first_observed_at=clock_timestamp(),last_observed_at=clock_timestamp() where warning_type='LATEST_FAILURE'",n);
        return jdbc.queryForObject("""
            insert into payment_audit_notification_manual_resends(notification_id,request_no,request_nonce,actor_user_id,actor_username,reason,status,requested_at)
            values(?,1,?,7,'restart-fixture','再起動検証','PENDING',clock_timestamp()) returning id
            """,Long.class,n,UUID.randomUUID());
    }
    public static Map<String,String> fingerprints(JdbcTemplate jdbc){
        Map<String,String> result=new LinkedHashMap<>();
        for(String table:List.of("payment_audit_notifications","payment_audit_notification_items","payment_audit_notification_attempts","payment_audit_notification_states","payment_audit_notification_handlings","payment_audit_notification_handling_histories","payment_audit_notification_handling_history_unknowns","payment_audit_notification_handling_history_manual_unknowns"))
            result.put(table,jdbc.queryForObject("select md5(coalesce(string_agg(row_to_json(t)::text, '' order by row_to_json(t)::text),'')) from "+table+" t",String.class));
        return result;
    }
}
