-- Test-only fixture. Core orders/payments/masters already exist in the dedicated container.
DO $$
DECLARE o bigint; p bigint; product bigint; tax bigint; n bigint; h bigint; r bigint; a bigint; d bigint;
BEGIN
 INSERT INTO user_profiles(user_id,name,postal_code,prefecture,city,address_line,phone,created_at,updated_at)
 SELECT max(id),'profile','1000001','Tokyo','Chiyoda','1','0000000000',now(),now() FROM users;
 INSERT INTO shipping_addresses(user_id,name,recipient_name,postal_code,prefecture,city,address_line,phone,created_at,updated_at)
 SELECT max(id),'address','recipient','1000001','Tokyo','Chiyoda','1','0000000000',now(),now() FROM users;
 SELECT min(id) INTO o FROM orders; SELECT min(id) INTO p FROM payments;
 SELECT min(id) INTO product FROM products; SELECT min(id) INTO tax FROM tax_categories;
 INSERT INTO order_charges(order_id,charge_type,name,amount,tax_category_id,tax_category_code,tax_category_name,tax_rate)
 SELECT o,'SHIPPING','shipping',0,id,code,name,tax_rate FROM tax_categories WHERE id=tax;
 INSERT INTO order_status_histories(order_id,to_status,changed_by_type,changed_by_username) VALUES(o,'ORDERED','USER','reset-user');
 INSERT INTO order_handling_status_histories(order_id,from_status,to_status,changed_by_account_id,changed_by_username) VALUES(o,'NONE','NEEDS_ACTION',9,'reset-admin');
 INSERT INTO order_assignee_histories(order_id,changed_by_account_id,changed_by_username,change_event_id) VALUES(o,9,'reset-admin',gen_random_uuid());
 INSERT INTO order_notes(order_id,note,created_by_account_id,created_by_username) VALUES(o,'test',9,'reset-admin');
 INSERT INTO order_shipping_address_histories(order_id,changed_by_type,changed_by_username) VALUES(o,'USER','reset-user');
 INSERT INTO order_content_change_histories(order_id,change_source,changed_by_type,changed_by_username,
 old_item_subtotal,new_item_subtotal,old_charge_total,new_charge_total,old_tax_amount,new_tax_amount,old_total_amount,new_total_amount)
 VALUES(o,'USER','USER','reset-user',200,200,0,0,0,0,200,200) RETURNING id INTO h;
 INSERT INTO order_content_change_items(history_id,product_id,product_name,change_type) VALUES(h,product,'product','UPDATED');
 INSERT INTO order_content_change_charges(history_id,charge_type,change_type) VALUES(h,'SHIPPING','UPDATED');
 INSERT INTO stock_movements(product_id,product_name,movement_type,quantity,stock_before,stock_after,changed_by_type,changed_by_username,order_id)
 VALUES(product,'product','ORDER_PLACEMENT',-2,22,20,'USER','reset-user',o),
       (product,'product','ADMIN_ADJUSTMENT',2,18,20,'ADMIN','reset-admin',NULL);
 INSERT INTO payment_discrepancies(payment_id,local_status,provider_status,status,first_detected_at,last_detected_at,created_at,updated_at)
 VALUES(p,'AUTHORIZED','CAPTURED','OPEN',now(),now(),now(),now()) RETURNING id INTO d;
 INSERT INTO payment_discrepancy_handling_status_histories(payment_discrepancy_id,from_status,to_status,changed_by_account_id,changed_by_username,change_event_id)
 VALUES(d,'UNCONFIRMED','IN_PROGRESS',9,'reset-admin',gen_random_uuid());
 INSERT INTO payment_discrepancy_audit_runs(execution_type,status,started_at,finished_at,duration_ms,candidate_count,instance_id)
 VALUES('SCHEDULED','SUCCESS',now(),now(),0,0,'reset-fixture');
 UPDATE payment_audit_notification_states SET active=true,episode_no=episode_no+1,first_observed_at=now(),last_observed_at=now(),last_evaluated_at=now(),resolved_at=NULL WHERE warning_type='LATEST_FAILURE';
 INSERT INTO payment_audit_notifications(status,evaluated_at,from_address,recipients,recipient_set_hash,admin_url,attempt_count,max_attempts,retry_delay_ms,next_attempt_at,created_at,updated_at,delivery_uncertain)
 VALUES('RETRY_WAIT',now(),'from@example.test',ARRAY['admin@example.test'],repeat('a',64),'http://localhost/admin',1,3,300000,now(),now(),now(),true) RETURNING id INTO n;
 INSERT INTO payment_audit_notification_items(notification_id,warning_type,episode_no,item_status,first_observed_at,created_at)
 SELECT n,warning_type,episode_no,'INCLUDED',now(),now() FROM payment_audit_notification_states WHERE warning_type='LATEST_FAILURE';
 INSERT INTO payment_audit_notification_attempts(notification_id,attempt_no,claim_token,result,started_at,finished_at,failure_code,subject,body,recipients)
 VALUES(n,1,gen_random_uuid(),'UNKNOWN',now(),now(),'SEND_DEADLINE_EXCEEDED','subject','body',ARRAY['admin@example.test']);
 INSERT INTO payment_audit_notification_handling_histories(notification_id,from_status,to_status,actor_user_id,actor_username,comment,event_id,changed_at)
 VALUES(n,'UNREVIEWED','RESOLVED',9,'reset-admin','reviewed',gen_random_uuid(),now()) RETURNING id INTO h;
 INSERT INTO payment_audit_notification_handlings(notification_id,handling_status,created_at,updated_at,last_resolved_history_id)
 VALUES(n,'RESOLVED',now(),now(),h);
 INSERT INTO payment_audit_notification_handling_history_unknowns(history_id,notification_id,attempt_no) VALUES(h,n,1);
 INSERT INTO payment_audit_notification_manual_resends(notification_id,request_no,request_nonce,actor_user_id,actor_username,reason,risk_acknowledged,status,result_reason_code,requested_at,started_at,finished_at)
 VALUES(n,1,gen_random_uuid(),9,'reset-admin','manual review',true,'UNKNOWN','SEND_DEADLINE_EXCEEDED',now(),now(),now()) RETURNING id INTO r;
 INSERT INTO payment_audit_notification_manual_resend_attempts(manual_resend_id,notification_id,result,claim_token,started_at,finished_at,result_reason_code,recipients,subject,body)
 VALUES(r,n,'UNKNOWN',gen_random_uuid(),now(),now(),'SEND_DEADLINE_EXCEEDED',ARRAY['admin@example.test'],'subject','body') RETURNING id INTO a;
 INSERT INTO payment_audit_notification_handling_history_manual_unknowns(history_id,manual_attempt_id,notification_id) VALUES(h,a,n);
END $$;
