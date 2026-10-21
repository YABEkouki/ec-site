package com.example.ecsite.service.orderchange;
import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.repository.*;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.OrderStatusHistoryService;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"logging.level.root=WARN","spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
@com.example.ecsite.support.CaptureConnectionTest
class OrderChangePaymentCustomerPostgresTest {
 @Autowired MockMvc mvc;
 @Autowired OrderIncreaseService increases;
 @Autowired OrderReauthorizationTransactions financialTransactions;
 @Autowired OrderRepository orders;
 @Autowired UserRepository users;
 @Autowired ProductRepository products;
 @Autowired CategoryRepository categories;
 @Autowired TaxCategoryRepository taxes;
 @Autowired PaymentRepository payments;
 @Autowired PaymentTransactionRepository transactions;
 @Autowired OrderStatusHistoryService histories;
 @Autowired PlatformTransactionManager manager;
 @Autowired JdbcTemplate jdbc;
 @MockitoBean PaymentGateway gateway;
    <T>T tx(Supplier<T> work){return new TransactionTemplate(manager).execute(s->work.get());}
    record Fixture(long order,long user,long item,long product){}
    Fixture fixture(){return tx(()->{
        var now=orders.findCurrentTimeInTokyo();String key=UUID.randomUUID().toString();
        var user=new User();user.setUsername("increase-"+key);user.setPassword("password");user.setEnabled(true);user.setCreatedAt(now);user.setUpdatedAt(now);users.saveAndFlush(user);
        var category=categories.saveAndFlush(new Category(key));var tax=taxes.findAll().getFirst();
        var product=new Product();product.setName("item");product.setPrice(900);product.setStock(10);product.setCategory(category);product.setTaxCategory(tax);products.saveAndFlush(product);
        var order=new Order(user.getId(),750,now,now.plusHours(1));
        order.addItem(new OrderItem(product.getId(),"ordered item",category.getId(),category.getName(),tax.getId(),tax.getCode(),tax.getName(),tax.getTaxRate(),100,2));
        order.addCharge(new OrderCharge(OrderChargeType.SHIPPING,"送料・梱包料",550,tax.getId(),tax.getCode(),tax.getName(),tax.getTaxRate(),10));orders.saveAndFlush(order);
        histories.record(order,null,OrderStatus.ORDERED,OrderStatusHistoryActorType.USER,user.getId(),user.getUsername(),null);
        var payment=new Payment(order,PaymentProvider.PAYJP,PaymentMethod.CARD,750,now);payment.setProviderPaymentId("pfw_"+key,now);payment.markAuthorized(now);payments.saveAndFlush(payment);
        var auth=new PaymentTransaction(payment,PaymentTransactionType.AUTHORIZE,750,0,key,now);auth.markSucceeded(payment.getProviderPaymentId(),now);transactions.saveAndFlush(auth);
        return new Fixture(order.getId(),user.getId(),order.getItems().getFirst().getId(),product.getId());
    });}

 String root(Fixture f){return "/orders/"+f.order()+"/increase";}
 org.springframework.test.web.servlet.request.RequestPostProcessor customer(long id){return user(new CustomUserDetails(id,"customer","unused",true,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))));}

 java.util.UUID accept(Fixture f){return increases.accept(new OrderIncreaseService.Request(f.order(),f.user(),java.util.UUID.randomUUID(),0,Map.of(f.item(),5))).id();}
 @Test void paymentRequiresOwnershipAndCreatesNoFlowForOtherCustomer() throws Exception {
  var f=fixture();var op=accept(f);
  mvc.perform(post(root(f)+"/"+op+"/payment").with(customer(f.user()+1000)).with(csrf())).andExpect(status().isNotFound());
  verifyNoInteractions(gateway);
 }

 final java.util.concurrent.atomic.AtomicReference<String> newState=new java.util.concurrent.atomic.AtomicReference<>("requires_payment_method");
 final java.util.concurrent.atomic.AtomicBoolean oldCancelled=new java.util.concurrent.atomic.AtomicBoolean();
 final String secret="browser-only-secret-marker";
 String flow;
 com.example.ecsite.payment.payjp.PayJpPaymentFlowObservation observed(String id,String status,int amount,Map<String,Object> metadata){
  return new com.example.ecsite.payment.payjp.PayJpPaymentFlowObservation(id,status,(long)amount,"jpy",0L,
   status.equals("requires_capture")?(long)amount:0L,"manual",false,null,null,metadata);
 }
 void mockFlow(Fixture f,UUID op){
  flow="pfw_browser_"+op;newState.set("requires_payment_method");oldCancelled.set(false);
  String old=jdbc.queryForObject("select provider_payment_id from payments where order_id=?",String.class,f.order());
  when(gateway.sendOrderChangeRequest(any())).thenAnswer(call->{
   assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
   var request=((com.example.ecsite.payment.orderchange.OrderChangeRequestSender.Dispatch)call.getArgument(0)).request();
   if(request.httpRequest().kind().equals("CREATE"))return observed(flow,"requires_payment_method",1050,Map.of());
   assertThat(request.httpRequest().kind()).isEqualTo("OLD_CANCEL");oldCancelled.set(true);return observed(old,"canceled",750,Map.of());
  });
  when(gateway.observeOrderChangeFlow(anyString())).thenAnswer(call->{
   assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
   String id=call.getArgument(0);return id.equals(old)?observed(old,oldCancelled.get()?"canceled":"requires_capture",750,Map.of()):
    observed(flow,newState.get(),1050,Map.of("order_id",Long.toString(f.order()),"order_change_operation_id",op.toString()));
  });
  when(gateway.retrieveAuthorization(flow)).thenReturn(new com.example.ecsite.payment.AuthorizationRecovery(
   new com.example.ecsite.payment.AuthorizationResult(com.example.ecsite.payment.AuthorizationResultStatus.REQUIRES_PAYMENT_METHOD,flow,null,null),secret));
 }
 String payment(Fixture f,UUID op){return root(f)+"/"+op+"/payment";}
 @Test void firstCardScreenIsDurableAndCannotBeReissuedAcrossTabsOrSessions() throws Exception {
  var f=fixture();var op=accept(f);mockFlow(f,op);var session=new MockHttpSession();
  var result=mvc.perform(post(payment(f,op)).with(customer(f.user())).with(csrf()).session(session))
   .andExpect(status().isOk()).andExpect(view().name("orders/increase-payment"))
   .andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("Referrer-Policy","no-referrer"))
   .andExpect(content().string(org.hamcrest.Matchers.containsString(secret))).andReturn();
  assertThat(result.getModelAndView().getModel().get("card").toString()).doesNotContain(secret);
  String nonce=result.getResponse().getHeader("Content-Security-Policy").split("nonce-")[1].split("'")[0];
  assertThat(result.getResponse().getContentAsString()).contains("nonce=\""+nonce+"\"");
  for(String name:java.util.Collections.list(session.getAttributeNames()))assertThat(String.valueOf(session.getAttribute(name))).doesNotContain(secret);
  mvc.perform(post(payment(f,op)).with(customer(f.user())).with(csrf()).session(new MockHttpSession()))
   .andExpect(status().isFound()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(secret))));
  verify(gateway,times(1)).sendOrderChangeRequest(any());
  assertThat(jdbc.queryForObject("select browser_confirmation_issued_at is not null from order_change_operations where id=?",Boolean.class,op)).isTrue();
  assertThat(jdbc.queryForObject("select to_jsonb(o)::text from order_change_operations o where id=?",String.class,op)).doesNotContain(secret);
  assertThatThrownBy(()->jdbc.update("update order_change_operations set browser_confirmation_issued_at=null where id=?",op)).isInstanceOf(org.springframework.dao.DataAccessException.class);
 }
 @Test void returnOnlyScrubsQueryAndPostUsesServerEvidence() throws Exception {
  var f=fixture();var op=accept(f);mockFlow(f,op);
  mvc.perform(post(payment(f,op)).with(customer(f.user())).with(csrf())).andExpect(status().isOk());
  clearInvocations(gateway);newState.set("requires_action");
  var callback=mvc.perform(get(payment(f,op)+"/return?payment_flow_client_secret="+secret+"&status=succeeded&payment_flow_id=evil")
    .with(customer(f.user()))).andExpect(status().isOk()).andExpect(view().name("orders/increase-payment-return"))
    .andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("Referrer-Policy","no-referrer"))
    .andExpect(header().string("Content-Security-Policy",org.hamcrest.Matchers.containsString("default-src 'none'")))
    .andReturn().getResponse().getContentAsString();
  assertThat(callback).contains("history.replaceState").doesNotContain(secret,"https://","<link","src=","evil");
  verifyNoInteractions(gateway);
  mvc.perform(post(payment(f,op)+"/continue").with(customer(f.user())).with(csrf()).param("status","succeeded"))
    .andExpect(status().isFound());
  assertThat(jdbc.queryForObject("select state from order_change_operations where id=?",String.class,op)).isEqualTo("REQUIRES_ACTION");
  assertThat(oldCancelled.get()).isFalse();verify(gateway,never()).sendOrderChangeRequest(any());
  newState.set("requires_capture");
  mvc.perform(post(payment(f,op)+"/continue").with(customer(f.user())).with(csrf())).andExpect(status().isFound());
  assertThat(jdbc.queryForObject("select state from order_change_operations where id=?",String.class,op)).isEqualTo("COMPLETED");
  assertThat(jdbc.queryForObject("select quantity from order_items where id=?",Integer.class,f.item())).isEqualTo(5);
  assertThat(jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product())).isEqualTo(7);
 }
 @Test void securitySavedRequestNeverRetainsSdkQuery() throws Exception {
  var f=fixture();var op=accept(f);var session=new MockHttpSession();
  mvc.perform(get(payment(f,op)+"/return?payment_flow_client_secret="+secret).header("Referer","https://example.test/?payment_flow_client_secret="+secret).session(session)).andExpect(status().isFound());
  var saved=(org.springframework.security.web.savedrequest.SavedRequest)session.getAttribute("SPRING_SECURITY_SAVED_REQUEST");
  assertThat(saved).isNotNull();assertThat(saved.getRedirectUrl()).doesNotContain(secret,"payment_flow_client_secret");
  assertThat(saved.getParameterMap()).isEmpty();assertThat(saved.getHeaderValues("Referer")).isEmpty();
  verifyNoInteractions(gateway);
 }
 @Test void processingGetAndRepeatedStartNeverConfirmOrCreateAgain() throws Exception {
  var f=fixture();var op=accept(f);mockFlow(f,op);
  mvc.perform(post(payment(f,op)).with(customer(f.user())).with(csrf())).andExpect(status().isOk());
  newState.set("processing");clearInvocations(gateway);
  mvc.perform(get(payment(f,op)).with(customer(f.user()))).andExpect(status().isFound());
  mvc.perform(post(payment(f,op)).with(customer(f.user())).with(csrf())).andExpect(status().isFound());
  verify(gateway,never()).sendOrderChangeRequest(any());verify(gateway,never()).retrieveAuthorization(anyString());
 }
 @Test void paymentCsrfAndForeignReturnAreRejectedBeforeProviderAccess() throws Exception {
  var f=fixture();var op=accept(f);
  mvc.perform(post(payment(f,op)).with(customer(f.user()))).andExpect(status().isForbidden());
  mvc.perform(get(payment(f,op)+"/return").with(customer(f.user()+1000))).andExpect(status().isNotFound());
  mvc.perform(post(payment(f,op)+"/continue").with(customer(f.user()+1000)).with(csrf())).andExpect(status().isNotFound());
  verifyNoInteractions(gateway);
 }
 @Test void wrongMetadataCannotExposeClientSecret() throws Exception {
  var f=fixture();var op=accept(f);mockFlow(f,op);
  when(gateway.observeOrderChangeFlow(flow)).thenReturn(observed(flow,"requires_payment_method",1050,Map.of("order_id","9999","order_change_operation_id",op.toString())));
  mvc.perform(post(payment(f,op)).with(customer(f.user())).with(csrf())).andExpect(status().isFound());
  verify(gateway,never()).retrieveAuthorization(anyString());assertThat(oldCancelled.get()).isFalse();
 }
 void expire(Fixture f,UUID op){
  var now=tx(()->orders.findCurrentTimeInTokyo());
  jdbc.update("update order_change_operations set accepted_at=?,deadline_at=?,grace_until=? where id=?",now.minusHours(3),now.minusHours(2),now.minusHours(2).plusMinutes(10),op);
 }
 @Test void expiredReservedIsReleasedOnceAndNeverCreatesFlow() throws Exception {
  var f=fixture();var op=accept(f);expire(f,op);
  assertThat(increases.expiredReservedCandidates(100)).anyMatch(c->c.operationId().equals(op));
  mvc.perform(post(payment(f,op)).with(customer(f.user())).with(csrf())).andExpect(status().isFound());
  assertThat(increases.expireReserved(f.order(),op)).isFalse();increases.abort(f.order(),f.user(),op);
  assertThat(jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product())).isEqualTo(10);
  assertThat(jdbc.queryForObject("select state from inventory_reservations where operation_id=?",String.class,op)).isEqualTo("RELEASED");
  verifyNoInteractions(gateway);
 }
 @Test void persistedFinancialRequestPreventsReservedExpiryEvenWithInconsistentState() {
  var f=fixture();var op=accept(f);financialTransactions.prepare(f.order(),op);expire(f,op);
  jdbc.update("update order_change_operations set state='RESERVED' where id=?",op);
  assertThat(increases.expireReserved(f.order(),op)).isFalse();
  assertThat(increases.expiredReservedCandidates(100)).noneMatch(c->c.operationId().equals(op));
  assertThatThrownBy(()->increases.abort(f.order(),f.user(),op)).isInstanceOf(IllegalStateException.class);
  assertThat(jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product())).isEqualTo(7);
  verifyNoInteractions(gateway);
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.EnumSource(value=com.example.ecsite.payment.PaymentGatewayErrorCategory.class,names={"TRANSPORT_RESULT_UNKNOWN","IDEMPOTENCY_WAIT_TIMEOUT","SERVER_RESULT_UNKNOWN"})
 void createUnknownPreservesSameRequestAndKey(com.example.ecsite.payment.PaymentGatewayErrorCategory category) throws Exception {
  var f=fixture();var op=accept(f);mockFlow(f,op);
  doThrow(new com.example.ecsite.payment.PaymentGatewayException(category,null,null,true)).when(gateway).sendOrderChangeRequest(any());
  mvc.perform(post(payment(f,op)).with(customer(f.user())).with(csrf())).andExpect(status().isFound());
  var request=jdbc.queryForMap("select id,idempotency_key,request_body from order_change_financial_requests where operation_id=?",op);
  mvc.perform(post(payment(f,op)).with(customer(f.user())).with(csrf())).andExpect(status().isFound());
  var replay=jdbc.queryForMap("select id,idempotency_key,request_body from order_change_financial_requests where operation_id=?",op);
  assertThat(replay.get("id")).isEqualTo(request.get("id"));assertThat(replay.get("idempotency_key")).isEqualTo(request.get("idempotency_key"));
  assertThat((byte[])replay.get("request_body")).isEqualTo((byte[])request.get("request_body"));
  assertThat(jdbc.queryForObject("select count(*) from payments where order_id=?",Integer.class,f.order())).isEqualTo(2);
  verify(gateway,times(category==com.example.ecsite.payment.PaymentGatewayErrorCategory.SERVER_RESULT_UNKNOWN?1:2)).sendOrderChangeRequest(any());
 }

 @Test void manualWithdrawalAndExpiryRaceReleaseExactlyOnce() throws Exception {
  var f=fixture();var op=accept(f);expire(f,op);
  var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
  try{
   var a=pool.submit(()->{start.await();increases.expireReserved(f.order(),op);return true;});
   var b=pool.submit(()->{start.await();increases.abort(f.order(),f.user(),op);return true;});
   start.countDown();assertThat(a.get(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(b.get(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
  }finally{pool.shutdownNow();}
  assertThat(jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product())).isEqualTo(10);
  assertThat(jdbc.queryForObject("select count(*) from inventory_reservations where operation_id=? and state='RELEASED'",Integer.class,op)).isEqualTo(1);
 }
 @Test void competingCardClaimsAllowOnlyOneInitialConfirmation() throws Exception {
  var f=fixture();var op=accept(f);mockFlow(f,op);
  // The core persists the Flow and observes it, but does not issue a browser credential.
  var core=new OrderReauthorizationService(financialTransactions,gateway,
   applicationRequests,manager);
  core.advance(f.order(),op);
  var proof=observed(flow,"requires_payment_method",1050,Map.of("order_id",Long.toString(f.order()),"order_change_operation_id",op.toString()));
  var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
  try{
   var a=pool.submit(()->{start.await();return financialTransactions.issueBrowserConfirmation(f.order(),op,proof);});
   var b=pool.submit(()->{start.await();return financialTransactions.issueBrowserConfirmation(f.order(),op,proof);});
   start.countDown();assertThat(List.of(a.get(20,java.util.concurrent.TimeUnit.SECONDS),b.get(20,java.util.concurrent.TimeUnit.SECONDS)))
    .containsExactlyInAnyOrder(true,false);
  }finally{pool.shutdownNow();}
 }
 @Autowired com.example.ecsite.payment.orderchange.OrderChangeFinancialRequestStore applicationRequests;
 @Test void existingSchedulerExpiresReservedEvenWhenFinancialScanFails() {
  var f=fixture();var op=accept(f);expire(f,op);
  var reconciliation=mock(com.example.ecsite.service.payment.PaymentReconciliationService.class);
  doThrow(new IllegalStateException("scan unavailable")).when(reconciliation).reconcilePendingTransactions();
  new com.example.ecsite.service.payment.PaymentReconciliationScheduler(reconciliation,increases).reconcile();
  assertThat(jdbc.queryForObject("select state from order_change_operations where id=?",String.class,op)).isEqualTo("ABORTED");
  assertThat(jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product())).isEqualTo(10);
  verifyNoInteractions(gateway);
 }
}
