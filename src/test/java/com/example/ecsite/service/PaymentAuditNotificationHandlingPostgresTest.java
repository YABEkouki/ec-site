package com.example.ecsite.service;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.example.ecsite.entity.*;
import com.example.ecsite.form.AdminPaymentAuditNotificationSearchForm;
import com.example.ecsite.repository.*;
import com.example.ecsite.support.PaymentAuditNotificationHistoryFixtures;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties={"spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false",
 "app.payment.discrepancy-audit.notification.enabled=false","app.payment.discrepancy-audit.enabled=false","app.payment.reconciliation.enabled=false"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({AdminPaymentAuditNotificationHandlingService.class,AdminPaymentAuditNotificationService.class,PaymentAuditNotificationHandlingPostgresTest.Config.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentAuditNotificationHandlingPostgresTest {
 @Container static final PostgreSQLContainer DB=new PostgreSQLContainer("postgres:17").withDatabaseName("feature117_isolated");
 @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
  // Both migration and JPA are routed to the same disposable container; no local default is used.
  r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);
  r.add("spring.flyway.url",DB::getJdbcUrl);r.add("spring.flyway.user",DB::getUsername);r.add("spring.flyway.password",DB::getPassword);
 }
 @TestConfiguration static class Config {
  @Bean LocalValidatorFactoryBean validator(){return new LocalValidatorFactoryBean();}
  @Bean PaymentAuditNotificationHandlingToken tokens(){return new PaymentAuditNotificationHandlingToken(Base64.getEncoder().encodeToString(new byte[32]),Clock.systemUTC());}
 }
 @Autowired JdbcTemplate jdbc; @Autowired DataSource source; @Autowired PlatformTransactionManager manager;
 @Autowired AdminPaymentAuditNotificationHandlingService service; @Autowired AdminPaymentAuditNotificationService reader;
 @Autowired PaymentAuditNotificationHandlingRepository handlings;
 PaymentAuditNotificationHistoryFixtures f;
 @BeforeEach void setup() throws Exception {
  try(var c=source.getConnection()) {assertThat(c.getMetaData().getURL()).isEqualTo(DB.getJdbcUrl());}
  assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("feature117_isolated");
  f=new PaymentAuditNotificationHistoryFixtures(jdbc);f.clear();
 }
 String token(long id){return reader.findById(id,7L).handling().snapshotToken();}
 void change(long id,PaymentAuditNotificationHandlingStatus s,String comment){service.changeStatus(id,7,"admin",token(id),s,comment);}
 long notification(){return f.notification("EXHAUSTED",PaymentAuditNotificationHistoryFixtures.NOW);}
 long count(String table){return jdbc.queryForObject("select count(*) from "+table,Long.class);}
 @Test void migrationAndHibernateValidation(){assertThat(jdbc.queryForObject("select max(version::int) from flyway_schema_history where version is not null and success",Integer.class)).isEqualTo(56);}
 @ParameterizedTest @CsvSource({"UNREVIEWED,IN_PROGRESS","UNREVIEWED,RESOLVED","IN_PROGRESS,UNREVIEWED","IN_PROGRESS,RESOLVED","RESOLVED,IN_PROGRESS","RESOLVED,UNREVIEWED"})
 void transitionsAndVersion(PaymentAuditNotificationHandlingStatus from,PaymentAuditNotificationHandlingStatus to){
  long id=notification();if(from!=PaymentAuditNotificationHandlingStatus.UNREVIEWED) change(id,from,"準備");
  long before=handlings.findById(id).map(PaymentAuditNotificationHandling::getVersion).orElse(-1L);
  change(id,to,"確認");assertThat(handlings.findById(id).orElseThrow().getHandlingStatus()).isEqualTo(to);
  assertThat(handlings.findById(id).orElseThrow().getVersion()).isEqualTo(before+1);
 }
 @Test void snapshotResolutionReopenAndReconfirmation(){
  long id=notification();f.attempt(id,1,"UNKNOWN");f.attempt(id,2,"IN_PROGRESS");change(id,PaymentAuditNotificationHandlingStatus.RESOLVED,"確認");
  long resolved=handlings.findById(id).orElseThrow().getLastResolvedHistoryId();
  assertThat(reader.findById(id).handling().confirmedUnknowns()).containsExactly(1);
  finalizeUnknown(id);assertThat(service.needsReconfirmation(id)).isTrue();assertThat(reader.findById(id).handling().requiresRecheck()).isTrue();
  change(id,PaymentAuditNotificationHandlingStatus.IN_PROGRESS,"再確認");assertThat(handlings.findById(id).orElseThrow().getLastResolvedHistoryId()).isEqualTo(resolved);
  change(id,PaymentAuditNotificationHandlingStatus.RESOLVED,"再確認済み");assertThat(service.needsReconfirmation(id)).isFalse();assertThat(reader.findById(id).handling().confirmedUnknowns()).containsExactly(1,2);
 }
 void finalizeUnknown(long id){jdbc.update("update payment_audit_notification_attempts set result='UNKNOWN',finished_at=clock_timestamp(),failure_code='CLAIM_LOST' where notification_id=? and attempt_no=2",id);}
 @Test void changedUnknownAndNoOp(){long id=notification();f.attempt(id,2,"IN_PROGRESS");String old=token(id);finalizeUnknown(id);
  assertThatThrownBy(()->service.changeStatus(id,7,"admin",old,PaymentAuditNotificationHandlingStatus.RESOLVED,"確認")).isInstanceOf(PaymentAuditNotificationHandlingConflictException.class);
  assertThat(count("payment_audit_notification_handling_histories")).isZero();
  assertThat(service.changeStatus(id,7,"admin",token(id),PaymentAuditNotificationHandlingStatus.UNREVIEWED,null)).isFalse();assertThat(handlings.findById(id)).isEmpty();
 }
 @ParameterizedTest @ValueSource(strings={"SENT","CANCELLED"}) void uncertainEligibility(String status){long id=f.notification(status,PaymentAuditNotificationHistoryFixtures.NOW);jdbc.update("update payment_audit_notifications set delivery_uncertain=true where id=?",id);change(id,PaymentAuditNotificationHandlingStatus.RESOLVED,"確認");assertThat(reader.findById(id).handling().confirmedUnknowns()).isEmpty();}
 @Test void nonActionableRejected(){long id=f.notification("SENT",PaymentAuditNotificationHistoryFixtures.NOW);assertThat(reader.findById(id).handling().snapshotToken()).isNull();assertThat(count("payment_audit_notification_handlings")).isZero();}
 @Test void unicodeAndSupplementaryLength(){long id=notification();for(int cp:new int[]{9,10,11,12,13,32,0x85,0xa0,0x1680,0x2000,0x2001,0x2002,0x2003,0x2004,0x2005,0x2006,0x2007,0x2008,0x2009,0x200a,0x200b,0x2028,0x2029,0x202f,0x205f,0x3000}){
  String blank=new String(Character.toChars(cp));assertThatThrownBy(()->change(id,PaymentAuditNotificationHandlingStatus.RESOLVED,blank)).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->history(id,"RESOLVED",blank)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
 }change(id,PaymentAuditNotificationHandlingStatus.RESOLVED,"😀".repeat(1000));assertThatThrownBy(()->change(id,PaymentAuditNotificationHandlingStatus.IN_PROGRESS,"😀".repeat(1001))).isInstanceOf(IllegalArgumentException.class);}
 long history(long id,String to,String comment){return jdbc.queryForObject("insert into payment_audit_notification_handling_histories(notification_id,from_status,to_status,actor_user_id,actor_username,comment,event_id,changed_at) values(?,'UNREVIEWED',?,7,'admin',?,?,clock_timestamp()) returning id",Long.class,id,to,comment,UUID.randomUUID());}
 @Test void generatedForeignKeysAndUnique(){long id=notification(),other=notification();f.attempt(id,1,"UNKNOWN");long resolved=history(id,"RESOLVED","確認"),notResolved=history(id,"IN_PROGRESS",null);
  jdbc.update("insert into payment_audit_notification_handlings(notification_id,handling_status,created_at,updated_at,last_resolved_history_id) values(?,'RESOLVED',clock_timestamp(),clock_timestamp(),?)",id,resolved);
  assertThat(jdbc.queryForObject("select last_resolved_history_status from payment_audit_notification_handlings where notification_id=?",String.class,id)).isEqualTo("RESOLVED");
  assertThatThrownBy(()->jdbc.update("update payment_audit_notification_handlings set last_resolved_history_id=? where notification_id=?",notResolved,id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  assertThatThrownBy(()->jdbc.update("insert into payment_audit_notification_handlings(notification_id,handling_status,created_at,updated_at,last_resolved_history_id) values(?,'RESOLVED',clock_timestamp(),clock_timestamp(),?)",other,resolved)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  jdbc.update("insert into payment_audit_notification_handling_history_unknowns(history_id,notification_id,attempt_no) values(?,?,1)",resolved,id);
  for(long parent:new long[]{id,other}) assertThatThrownBy(()->jdbc.update("insert into payment_audit_notification_handling_history_unknowns(history_id,notification_id,attempt_no) values(?,?,2)",resolved,parent)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  assertThatThrownBy(()->jdbc.update("insert into payment_audit_notification_handling_history_unknowns(history_id,notification_id,attempt_no) values(?,?,1)",resolved,id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
 }
 @Test void rollbackAfterHistoryInsert(){long id=notification();f.attempt(id,1,"UNKNOWN");jdbc.execute("create function reject_snapshot() returns trigger language plpgsql as $$ begin raise exception 'fixture failure'; end $$");jdbc.execute("create trigger reject_snapshot before insert on payment_audit_notification_handling_history_unknowns for each row execute function reject_snapshot()");
  try{assertThatThrownBy(()->change(id,PaymentAuditNotificationHandlingStatus.RESOLVED,"確認")).isInstanceOf(RuntimeException.class);assertThat(count("payment_audit_notification_handling_histories")).isZero();assertThat(count("payment_audit_notification_handlings")).isZero();}
  finally{jdbc.execute("drop trigger reject_snapshot on payment_audit_notification_handling_history_unknowns");jdbc.execute("drop function reject_snapshot()");}}
 @ParameterizedTest @ValueSource(ints={20,50,100}) void pagingAndSearch(int size){for(int i=0;i<101;i++){long id=notification();f.item(id,"LATEST_FAILURE",false);f.item(id,"LONG_RUNNING",true);}
  var form=new AdminPaymentAuditNotificationSearchForm();form.setHandlingStatus(PaymentAuditNotificationHandlingStatus.UNREVIEWED);form.setActionableOnly(true);form.setWarningType(PaymentAuditNotificationWarningType.LATEST_FAILURE);
  var page=reader.search(form,0,size);assertThat(page.getTotalElements()).isEqualTo(101);assertThat(page.getContent()).hasSize(size);assertThat(page.getContent().stream().map(x->x.id()).toList()).isSortedAccordingTo(Comparator.reverseOrder());
  form.setRequiresRecheck(true);assertThat(reader.search(form,0,size).getTotalElements()).isZero();
 }
 @ParameterizedTest @ValueSource(booleans={false,true}) void concurrentUpdates(boolean existing) throws Exception {long id=notification();if(existing)change(id,PaymentAuditNotificationHandlingStatus.IN_PROGRESS,null);String t=token(id);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  try{Callable<Boolean> task=()->{start.await();try{return service.changeStatus(id,7,"admin",t,PaymentAuditNotificationHandlingStatus.RESOLVED,"確認");}catch(PaymentAuditNotificationHandlingConflictException e){return false;}};var a=pool.submit(task);var b=pool.submit(task);start.countDown();assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);assertThat(count("payment_audit_notification_handling_histories")).isEqualTo(existing?2:1);}
  finally{pool.shutdownNow();}}
 @Test void parentLockSerializesUnknownFinalization() throws Exception {long id=notification();f.attempt(id,2,"IN_PROGRESS");String t=token(id);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  try{var writer=pool.submit(()->new TransactionTemplate(manager).execute(s->{jdbc.queryForObject("select id from payment_audit_notifications where id=? for update",Long.class,id);locked.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}finalizeUnknown(id);return null;}));assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
   var admin=pool.submit(()->service.changeStatus(id,7,"admin",t,PaymentAuditNotificationHandlingStatus.RESOLVED,"確認"));release.countDown();writer.get(10,TimeUnit.SECONDS);assertThatThrownBy(()->{try{admin.get(10,TimeUnit.SECONDS);}catch(ExecutionException e){throw e.getCause();}}).isInstanceOf(PaymentAuditNotificationHandlingConflictException.class);assertThat(count("payment_audit_notification_handling_histories")).isZero();}
  finally{release.countDown();pool.shutdownNow();}}
 @Test void resolvedRecheckAndFullAndSearch(){
  long id=notification();f.item(id,"LATEST_FAILURE",false);f.attempt(id,1,"UNKNOWN");f.attempt(id,2,"IN_PROGRESS");
  change(id,PaymentAuditNotificationHandlingStatus.RESOLVED,"確認");finalizeUnknown(id);
  long other=f.notification("SENT",PaymentAuditNotificationHistoryFixtures.NOW);
  var form=new AdminPaymentAuditNotificationSearchForm();form.setHandlingStatus(PaymentAuditNotificationHandlingStatus.RESOLVED);form.setActionableOnly(true);form.setRequiresRecheck(true);
  form.setNotificationId(id);form.setStatus(PaymentAuditNotificationStatus.EXHAUSTED);form.setWarningType(PaymentAuditNotificationWarningType.LATEST_FAILURE);
  form.setFrom(LocalDate.of(2026,10,9));form.setTo(LocalDate.of(2026,10,9));
  var page=reader.search(form,Integer.MAX_VALUE,20);assertThat(page.getTotalElements()).isEqualTo(1);assertThat(page.getContent().getFirst().requiresRecheck()).isTrue();
  form.setNotificationId(other);assertThat(reader.search(form,0,20).getTotalElements()).isZero();
 }
 @Test void checkAndEventUniqueness(){long id=notification();long h=history(id,"IN_PROGRESS",null);
  assertThatThrownBy(()->jdbc.update("insert into payment_audit_notification_handling_histories(notification_id,from_status,to_status,actor_user_id,actor_username,event_id,changed_at) select notification_id,from_status,to_status,actor_user_id,actor_username,event_id,changed_at from payment_audit_notification_handling_histories where id=?",h)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  assertThatThrownBy(()->jdbc.update("update payment_audit_notification_handling_histories set to_status=from_status where id=?",h)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  assertThatThrownBy(()->history(id,"RESOLVED","😀".repeat(1001))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  jdbc.update("insert into payment_audit_notification_handlings(notification_id,handling_status,created_at,updated_at) values(?,'IN_PROGRESS',clock_timestamp(),clock_timestamp())",id);
  assertThatThrownBy(()->jdbc.update("update payment_audit_notification_handlings set version=-1 where notification_id=?",id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
 }
 @Test void targetRejectionAndDeliveryDataUnchanged(){long id=f.notification("SENT",PaymentAuditNotificationHistoryFixtures.NOW);
  var signer=new PaymentAuditNotificationHandlingToken(Base64.getEncoder().encodeToString(new byte[32]),Clock.systemUTC());
  assertThatThrownBy(()->service.changeStatus(id,7,"admin",signer.issue(id,7,false,0,List.of()),PaymentAuditNotificationHandlingStatus.IN_PROGRESS,null)).isInstanceOf(IllegalArgumentException.class);
  jdbc.update("update payment_audit_notifications set delivery_uncertain=true where id=?",id);
  String before=jdbc.queryForObject("select row_to_json(n)::text from payment_audit_notifications n where id=?",String.class,id);
  change(id,PaymentAuditNotificationHandlingStatus.IN_PROGRESS,null);
  assertThat(jdbc.queryForObject("select row_to_json(n)::text from payment_audit_notifications n where id=?",String.class,id)).isEqualTo(before);
 }
}
