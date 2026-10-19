package com.example.ecsite.support.reset;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.repository.*;
import com.example.ecsite.payment.*;
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.OrderShippingPreparationService;
import com.example.ecsite.service.payment.*;
import com.example.ecsite.support.isolation.IsolatedDatabaseGuard;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** Destructive fixtures are restricted to this class-owned disposable PostgreSQL 17 container. */
// This manual reset contract is deliberately V53-only; V54 rejection is tested separately.
@SpringBootTest(properties={"spring.flyway.target=53","logging.level.root=WARN","spring.jpa.show-sql=false"})
@ActiveProfiles("test") @Testcontainers @DirtiesContext
@com.example.ecsite.support.CaptureConnectionTest
class OrderPaymentResetPostgresTest {
 @Container static final PostgreSQLContainer DB=new PostgreSQLContainer("postgres:17")
     .withDatabaseName("reset_validation_"+UUID.randomUUID().toString().replace("-",""));
 @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
  r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);
  r.add("spring.flyway.url",DB::getJdbcUrl);r.add("spring.flyway.user",DB::getUsername);r.add("spring.flyway.password",DB::getPassword);
 }
 @Autowired Environment env; @Autowired DataSource source; @Autowired JdbcTemplate jdbc;
 @Autowired PlatformTransactionManager manager;
 @Autowired OrderRepository orders; @Autowired PaymentRepository payments; @Autowired PaymentTransactionRepository transactions;
 @Autowired UserRepository users; @Autowired CategoryRepository categories; @Autowired ProductRepository products; @Autowired TaxCategoryRepository taxes;
 @Autowired OrderShippingPreparationService preparation; @Autowired PaymentCaptureStartService start;
 @Autowired PaymentCaptureResultService result; @Autowired PaymentCaptureService capture; @Autowired PayJpWebhookCaptureSyncService webhook;
 @MockitoBean PaymentGateway gateway;
 // This suite intentionally remains at V53 to validate the approved historical reset SQL.
 // V54 persistence/atomicity is covered by CaptureShipmentFlowPostgresTest and CaptureRequestStorePostgresTest.
 @MockitoBean com.example.ecsite.payment.capture.CaptureRequestStore requestStore;
 final java.util.Map<Long,com.example.ecsite.payment.capture.StoredCaptureRequest> requestBoundary=new java.util.HashMap<>();
 final Path sql=Path.of("scripts/manual/reset-order-payment-data.sql");
 long productId; List<Fixture> old;
 record Fixture(long order,long payment,long transaction,String provider) {}
 <T>T tx(Supplier<T> work){return new TransactionTemplate(manager).execute(s -> work.get());}
 @BeforeEach void setup() throws Exception {
  // Docker image, labels, published port and credentials are verified before any fixture write.
  IsolatedDatabaseGuard.verify(env);
  try(var c=source.getConnection()){assertThat(c.getMetaData().getURL()).isEqualTo(DB.getJdbcUrl());}
  assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo(DB.getDatabaseName());
  assertThat(jdbc.queryForObject("select version from flyway_schema_history where success order by installed_rank desc limit 1",String.class)).isEqualTo("53");
  assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
  authenticate();requestBoundary.clear();
  tx(()->{
  when(requestStore.insert(any(),any(),any(),any(),any())).thenAnswer(call->{
   Payment payment=call.getArgument(0);PaymentTransaction operation=call.getArgument(1);
   var saved=new com.example.ecsite.payment.capture.StoredCaptureRequest(operation.getId(),payment.getId(),operation.getId(),operation.getOrderContentRevision(),call.getArgument(2),call.getArgument(3),call.getArgument(4));
   requestBoundary.put(operation.getId(),saved);return saved;
  });
  when(requestStore.findByIdAndCaptureTransactionId(anyLong(),anyLong())).thenAnswer(call->{
   Long id=call.getArgument(0),transaction=call.getArgument(1);var saved=requestBoundary.get(transaction);
   return saved!=null && saved.id()==id?java.util.Optional.of(saved):java.util.Optional.empty();
  });return null;});
  productId=tx(() -> {
   var now=orders.findCurrentTimeInTokyo();var u=new User();u.setUsername("reset-"+UUID.randomUUID());u.setPassword("test");u.setEnabled(true);u.setCreatedAt(now);u.setUpdatedAt(now);users.saveAndFlush(u);
   jdbc.update("insert into admin_accounts(username,password,created_at,updated_at) values(?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","reset-"+UUID.randomUUID(),"test");
   var category=categories.saveAndFlush(new Category("reset-"+UUID.randomUUID()));var product=new Product();product.setName("reset-product");product.setPrice(100);product.setStock(20);product.setCategory(category);product.setTaxCategory(taxes.findAll().getFirst());products.saveAndFlush(product);return product.getId();
  });
  old=new ArrayList<>();for(int i=0;i<5;i++) old.add(fixture(i==1 || i==2 || i==3));
  for(int i:List.of(2,3)){var f=old.get(i);var operation=start.start(f.order(),9L,"reset-admin","reset memo");if(i==3)result.apply(operation.snapshot(),new CaptureResult(CaptureResultStatus.CAPTURED,f.provider(),null,null));}
  var legacy=old.get(4);tx(() -> {var p=payments.findById(legacy.payment()).orElseThrow();transactions.saveAndFlush(new PaymentTransaction(p,PaymentTransactionType.CAPTURE,200,0,"legacy-"+UUID.randomUUID(),orders.findCurrentTimeInTokyo()));return null;});
  try(var c=source.getConnection()){c.setAutoCommit(false);execute(c,Files.readString(Path.of("src/test/resources/reset/order-payment-reset-histories.sql")));c.commit();}
  // Every fully-cleared table must be exercised, including V52 UNKNOWN links.
  for(String table:targets())assertThat(count(table)).as(table+" fixture").isPositive();
 }
 @AfterEach void cleanup() throws Exception {
  try(var c=source.getConnection()){c.setAutoCommit(false);gate(c);execute(c,Files.readString(sql));c.commit();}
  finally{SecurityContextHolder.clearContext();}
 }
 void authenticate(){var a=new AdminUserDetails(9L,"reset-admin","test",true,List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(a,"",a.getAuthorities()));}
 Fixture fixture(boolean prepared){return tx(() -> {
  var now=orders.findCurrentTimeInTokyo();var time=now.minusDays(2).toLocalDate().atTime(10,0);var product=products.findById(productId).orElseThrow();var tax=product.getTaxCategory();var cat=product.getCategory();
  var o=new Order(users.findAll().getLast().getId(),200,time,time.withHour(14));if(prepared)o.beginShippingPreparation(time.withHour(14),7L,"preparer");
  o.addItem(new OrderItem(productId,product.getName(),cat.getId(),cat.getName(),tax.getId(),tax.getCode(),tax.getName(),tax.getTaxRate(),100,2));orders.saveAndFlush(o);
  var p=new Payment(o,PaymentProvider.PAYJP,PaymentMethod.CARD,200,now);p.setProviderPaymentId("pfw_reset_"+UUID.randomUUID(),now);p.markAuthorized(now);payments.saveAndFlush(p);
  var a=new PaymentTransaction(p,PaymentTransactionType.AUTHORIZE,200,0,"auth-"+UUID.randomUUID(),now);a.markSucceeded(p.getProviderPaymentId(),now);transactions.saveAndFlush(a);return new Fixture(o.getId(),p.getId(),a.getId(),p.getProviderPaymentId());
 });}
 long count(String table){return jdbc.queryForObject("select count(*) from "+table,Long.class);}
 List<String> targets() throws Exception {
  var matcher=java.util.regex.Pattern.compile("\\('([a-z_]+)', true\\)").matcher(Files.readString(sql));var tables=new ArrayList<String>();while(matcher.find())tables.add(matcher.group(1));assertThat(tables).hasSize(25);return tables;
 }
 void execute(Connection c,String s)throws SQLException{try(var statement=c.createStatement()){statement.execute(s);}}
 void gate(Connection c)throws SQLException{execute(c,"""
  CREATE TEMP TABLE order_payment_reset_gate ON COMMIT DROP AS
  SELECT current_database() AS expected_database,current_user AS expected_user,inet_server_addr() AS expected_server_addr,
  inet_server_port() AS expected_server_port,'APPROVE_ORDER_PAYMENT_RESET'::text AS approval,'isolated-fixture-only'::text AS backup_reference
  """);}
 Map<String,String> snapshot(Connection c)throws SQLException{
  var map=new TreeMap<String,String>();var tables=new ArrayList<String>();
  try(var s=c.createStatement();var r=s.executeQuery("select tablename from pg_tables where schemaname='public' order by tablename")){while(r.next())tables.add(r.getString(1));}
  for(String table:tables)try(var s=c.createStatement();var r=s.executeQuery("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]')::text from public.\""+table+"\" t")){r.next();map.put(table,r.getString(1));}return map;
 }
 Map<String,String> snapshot()throws Exception{try(var c=source.getConnection()){return snapshot(c);}}
 List<Map<String,Object>> sequences(){return jdbc.queryForList("select sequencename,last_value from pg_sequences where schemaname='public' order by sequencename");}
 void reset()throws Exception{try(var c=source.getConnection()){c.setAutoCommit(false);gate(c);execute(c,Files.readString(sql));c.commit();}}
 @Test void commitClearsAllTargetsAndPreservesEveryOtherRowStockAndSequences()throws Exception{
  var before=snapshot();var seq=sequences();var stock=jdbc.queryForList("select * from stock_movements where order_id is null order by id");
  reset();for(String table:targets())assertThat(count(table)).as(table).isZero();
  var after=snapshot();for(String table:before.keySet())if(!targets().contains(table)&&!Set.of("stock_movements","payment_audit_notification_states").contains(table))assertThat(after.get(table)).as(table).isEqualTo(before.get(table));
  assertThat(jdbc.queryForList("select * from stock_movements order by id")).isEqualTo(stock);assertThat(sequences()).isEqualTo(seq);
  assertThat(count("payment_audit_notification_states")).isEqualTo(6);
  assertThat(jdbc.queryForObject("select count(*) from payment_audit_notification_states where active or episode_no<>0 or first_observed_at is not null or last_evaluated_at is not null or last_observed_at is not null or resolved_at is not null",Long.class)).isZero();
  verifyNoInteractions(gateway);
 }
 @Test void successfulDryRunRollbackRestoresAllRows()throws Exception{var before=snapshot();try(var c=source.getConnection()){c.setAutoCommit(false);gate(c);execute(c,Files.readString(sql));c.rollback();}assertThat(snapshot()).isEqualTo(before);}
 @ParameterizedTest @ValueSource(strings={"FK","STATE","STOCK","TRIGGER","MIDWAY","OMISSION","GATE"})
 void failureRollsBackEveryRow(String fault)throws Exception{
  var before=snapshot();try(var c=source.getConnection()){
   c.setAutoCommit(false);
   String setup=switch(fault){
    case "FK" -> "create table reset_unexpected_ref(order_id bigint references orders(id)); insert into reset_unexpected_ref select min(id) from orders";
    case "STATE" -> "delete from payment_audit_notification_states where warning_type='NO_HISTORY'";
    case "STOCK" -> "update stock_movements set movement_type='UNKNOWN' where order_id is null";
    case "TRIGGER" -> "create function reset_fault() returns trigger language plpgsql as $$ begin SET CONSTRAINTS chk_manual_attempt_request_consistency IMMEDIATE; return OLD; end $$; create trigger zz_reset_fault after delete on payment_audit_notification_manual_resend_attempts for each row execute function reset_fault()";
    case "MIDWAY" -> "create function reset_fault() returns trigger language plpgsql as $$ begin raise exception 'injected midway failure'; end $$; create trigger reset_fault before delete on orders for each row execute function reset_fault()";
    case "OMISSION" -> "create rule reset_skip_delete as on delete to payment_discrepancy_audit_runs do instead nothing";
    default -> "select 1";
   };
   execute(c,setup);var faulted=snapshot(c);gate(c);if(fault.equals("GATE"))execute(c,"update order_payment_reset_gate set expected_database='wrong_target'");
   var save=c.setSavepoint();SQLException failure=catchThrowableOfType(() -> execute(c,Files.readString(sql)),SQLException.class);
   assertThat((Throwable)failure).isNotNull();assertThat(failure.getSQLState()).isEqualTo(fault.equals("TRIGGER")?"23514":"P0001");
   String message=switch(fault){case "FK" -> "foreign key";case "STATE" -> "six notification";case "STOCK" -> "stock movement";case "TRIGGER" -> "requires an attempt";case "MIDWAY" -> "injected midway";case "OMISSION" -> "Reset rows remain";default -> "Reset target";};
   assertThat(failure.getMessage()).contains(message);c.rollback(save);assertThat(snapshot(c)).isEqualTo(faulted);c.rollback();
  }assertThat(snapshot()).isEqualTo(before);verifyNoInteractions(gateway);
 }
 @Test void missingGateRejectsBeforeDeletion()throws Exception{var before=snapshot();try(var c=source.getConnection()){c.setAutoCommit(false);SQLException e=catchThrowableOfType(() ->execute(c,Files.readString(sql)),SQLException.class);assertThat((Throwable)e).isNotNull();assertThat(e.getSQLState()).isEqualTo("P0001");assertThat(e.getMessage()).contains("explicit transaction gate");c.rollback();}assertThat(snapshot()).isEqualTo(before);}

 @ParameterizedTest @ValueSource(strings={"DATABASE","USER","ADDRESS","PORT","APPROVAL","BACKUP"})
 void everyTargetApprovalAndBackupMismatchRejectsWithoutChanges(String field)throws Exception{
  var before=snapshot();try(var c=source.getConnection()){
   c.setAutoCommit(false);gate(c);execute(c,switch(field){
    case "DATABASE" -> "update order_payment_reset_gate set expected_database='wrong'";
    case "USER" -> "update order_payment_reset_gate set expected_user='wrong'";
    case "ADDRESS" -> "update order_payment_reset_gate set expected_server_addr='192.0.2.1'::inet";
    case "PORT" -> "update order_payment_reset_gate set expected_server_port=1";
    case "APPROVAL" -> "update order_payment_reset_gate set approval='wrong'";
    default -> "update order_payment_reset_gate set backup_reference='   '";
   });
   SQLException e=catchThrowableOfType(() ->execute(c,Files.readString(sql)),SQLException.class);assertThat((Throwable)e).isNotNull();assertThat(e.getSQLState()).isEqualTo("P0001");assertThat(e.getMessage()).contains("Reset target");c.rollback();
  }assertThat(snapshot()).isEqualTo(before);
 }
 @Test void concurrentTableUserRejectsImmediatelyAndRollsBack()throws Exception{
  var before=snapshot();try(var blocker=source.getConnection();var reset=source.getConnection()){
   blocker.setAutoCommit(false);execute(blocker,"select id from orders for update");
   reset.setAutoCommit(false);gate(reset);SQLException e=catchThrowableOfType(() ->execute(reset,Files.readString(sql)),SQLException.class);
   assertThat((Throwable)e).isNotNull();assertThat(e.getSQLState()).isEqualTo("55P03");reset.rollback();blocker.rollback();
  }assertThat(snapshot()).isEqualTo(before);
 }
 @ParameterizedTest @ValueSource(strings={"CAPTURED","PENDING","FAILED"})
 void newFlowAfterResetUsesNewIdsAndPreservesShipmentForEveryGatewayOutcome(String outcome)throws Exception{
  long oldOrder=jdbc.queryForObject("select max(id) from orders",Long.class),oldPayment=jdbc.queryForObject("select max(id) from payments",Long.class),oldTx=jdbc.queryForObject("select max(id) from payment_transactions",Long.class);
  var providers=jdbc.queryForList("select provider_payment_id from payments",String.class);reset();
  var f=fixture(false);assertThat(f.order()).isGreaterThan(oldOrder);assertThat(f.payment()).isGreaterThan(oldPayment);assertThat(f.transaction()).isGreaterThan(oldTx);assertThat(providers).doesNotContain(f.provider());
  var untouched=snapshot();for(String provider:providers)assertThat(webhook.synchronize(provider)).isEqualTo(PaymentSynchronizationOutcome.SKIPPED);assertThat(snapshot()).isEqualTo(untouched);verifyNoInteractions(gateway);
  preparation.start(f.order());when(gateway.capture(any())).thenAnswer(call -> {
   // Separate connection sees committed SHIPPED+PENDING+history before any external call.
   assertThat(jdbc.queryForObject("select status from orders where id=?",String.class,f.order())).isEqualTo("SHIPPED");
   assertThat(jdbc.queryForObject("select paid_at from orders where id=?",Timestamp.class,f.order())).isNull();
   assertThat(jdbc.queryForObject("select count(*) from payment_transactions where payment_id=? and transaction_type='CAPTURE' and status='PENDING'",Long.class,f.payment())).isEqualTo(1);
   assertThat(jdbc.queryForObject("select count(*) from order_status_histories where order_id=?",Long.class,f.order())).isEqualTo(1);
   return new CaptureResult(CaptureResultStatus.valueOf(outcome),f.provider(),"test-code","test reason");
  });
  var stock=jdbc.queryForList("select id,stock from products order by id");capture.captureForShipment(f.order(),9L,"reset-admin","new memo");
  var row=jdbc.queryForMap("select * from orders where id=?",f.order());assertThat(row.get("status")).isEqualTo("SHIPPED");assertThat(row.get("paid_at")!=null).isEqualTo(outcome.equals("CAPTURED"));
  assertThat(jdbc.queryForList("select id,stock from products order by id")).isEqualTo(stock);
  assertThat(jdbc.queryForObject("select count(*) from order_status_histories where order_id=?",Long.class,f.order())).isEqualTo(1);
  if(outcome.equals("CAPTURED"))capture.captureForShipment(f.order(),9L,"reset-admin","duplicate memo");
  else if(outcome.equals("PENDING")){
   when(gateway.retrievePaymentFlow(f.provider())).thenReturn(new PaymentFlowState(f.provider(),PaymentFlowStatus.PROCESSING,null,null));
   capture.captureForShipment(f.order(),9L,"reset-admin","duplicate memo");verify(gateway).retrievePaymentFlow(f.provider());
  }else assertThatThrownBy(() -> capture.captureForShipment(f.order(),9L,"reset-admin","duplicate memo")).isExactlyInstanceOf(IllegalStateException.class).hasMessage("売上確定対象を安全に確認できません。管理者による確認が必要です。注文番号: "+f.order());
  assertThat(jdbc.queryForObject("select count(*) from order_status_histories where order_id=?",Long.class,f.order())).isEqualTo(1);
  assertThat(jdbc.queryForMap("select * from orders where id=?",f.order())).isEqualTo(row);
  verify(gateway,times(1)).capture(any());
 }
 @ParameterizedTest @ValueSource(strings={"DRY_RUN","APPLY_ORDER_PAYMENT_RESET","FAIL_APPLY"})
 void actualPsqlWrapperDefaultsToRollbackAndCommitsOnlyWithExplicitApply(String mode)throws Exception{
  var before=snapshot();DB.copyFileToContainer(MountableFile.forHostPath(sql),"/tmp/reset-order-payment-data.sql");DB.copyFileToContainer(MountableFile.forHostPath(Path.of("scripts/manual/reset-order-payment-data.psql")),"/tmp/reset-order-payment-data.psql");
  var args=new ArrayList<>(List.of("psql","-X","-U",DB.getUsername(),"-d",DB.getDatabaseName(),"-v","expected_database="+DB.getDatabaseName(),"-v","expected_user="+DB.getUsername(),"-v","expected_server_addr=","-v","expected_server_port=","-v","approval="+(mode.equals("FAIL_APPLY")?"WRONG":"APPROVE_ORDER_PAYMENT_RESET"),"-v","backup_reference=isolated-fixture-only"));
  if(!mode.equals("DRY_RUN"))args.addAll(List.of("-v","apply=APPLY_ORDER_PAYMENT_RESET"));args.addAll(List.of("-f","/tmp/reset-order-payment-data.psql"));
  var execution=DB.execInContainer(args.toArray(String[]::new));if(mode.equals("FAIL_APPLY")){assertThat(execution.getExitCode()).isNotZero();assertThat(execution.getStderr()).contains("Reset target");assertThat(snapshot()).isEqualTo(before);return;}
  assertThat(execution.getExitCode()).withFailMessage(execution.getStderr()).isZero();
  if(mode.equals("DRY_RUN"))assertThat(snapshot()).isEqualTo(before);else for(String table:targets())assertThat(count(table)).isZero();
 }
}
