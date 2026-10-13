package com.example.ecsite.support.browser;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.ecsite.support.PaymentAuditNotificationHistoryFixtures;

/** Initial synthetic rows only; never invokes delivery or administrator changeStatus(). */
public final class BrowserNotificationFixtures {
    private BrowserNotificationFixtures() {}
    public static void seed(JdbcTemplate jdbc,TransactionTemplate tx,PasswordEncoder encoder,String username,String password,String baseUrl) {
        if(!jdbc.queryForObject("select current_database()",String.class).startsWith("browser117_")) throw new IllegalStateException("Browser fixture database rejected");
        tx.executeWithoutResult(s->{
            if(jdbc.queryForObject("select count(*) from payment_audit_notifications",Long.class)!=0) throw new IllegalStateException("Browser fixture requires an empty notification baseline");
            // Disable migration-seeded administrators only in this disposable database.
            jdbc.update("update admin_accounts set enabled=false");
            long actor=jdbc.queryForObject("insert into admin_accounts(username,password,enabled,created_at,updated_at) values(?,?,true,now(),now()) returning id",Long.class,username,encoder.encode(password));
            var f=new PaymentAuditNotificationHistoryFixtures(jdbc);
            String[] statuses={"SENT","EXHAUSTED","SENT","CANCELLED","EXHAUSTED","EXHAUSTED","SENT","EXHAUSTED","PENDING","CLAIMED","SENDING","RETRY_WAIT"};
            String[] warnings={"LATEST_FAILURE","CONSECUTIVE_FAILURES","LONG_RUNNING","DELAYED","NO_HISTORY","LONG_UNHANDLED"};
            for(int i=0;i<24;i++) {
                int kind=i%12;
                int count=switch(kind){case 0->1;case 1,4,5,6,7->3;case 2->2;case 3,10,11->1;default->0;};
                long id=f.notification(statuses[kind],Instant.now().minusSeconds(i*3600L),count);
                f.item(id,warnings[i%6],false);
                if(kind==5 || kind==6) f.item(id,warnings[(i+1)%6],true);
                boolean uncertain=kind==2 || kind==3 || kind==5 || kind==6;
                jdbc.update("update payment_audit_notifications set delivery_uncertain=?,admin_url=? where id=?",uncertain,baseUrl+"/admin/payment-discrepancy-audits",id);
                for(int attempt=1;attempt<=count;attempt++) {
                    String result=(uncertain && attempt==1 || kind==6 && attempt==2)?"UNKNOWN"
                        :statuses[kind].equals("SENT") && attempt==count?"SUCCESS":kind==10?"IN_PROGRESS":"FAILURE";
                    f.attempt(id,attempt,result);
                }
                String handling=kind==4?"IN_PROGRESS":kind==5 || kind==6?"RESOLVED":kind==7?"UNREVIEWED":null;
                if(handling!=null) {
                    Long history=null;
                    if(!handling.equals("UNREVIEWED")) history=jdbc.queryForObject("""
                        insert into payment_audit_notification_handling_histories(notification_id,from_status,to_status,
                        actor_user_id,actor_username,comment,event_id,changed_at)
                        values(?,'UNREVIEWED',?,?,?,'ブラウザ検証用の合成履歴',?,now()) returning id
                        """,Long.class,id,handling,actor,username,UUID.randomUUID());
                    jdbc.update("insert into payment_audit_notification_handlings(notification_id,handling_status,created_at,updated_at,last_resolved_history_id) values(?,?,now(),now(),?)",id,handling,handling.equals("RESOLVED")?history:null);
                    if(handling.equals("RESOLVED")) jdbc.update("insert into payment_audit_notification_handling_history_unknowns(history_id,notification_id,attempt_no) values(?,?,1)",history,id);
                }
            }
        });
    }
}
