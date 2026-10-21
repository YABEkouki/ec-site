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
class OrderIncreaseCustomerPostgresTest {
 @Autowired MockMvc mvc;
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
 @Test void inputIsOwnedAndNeverCallsProvider() throws Exception {
  var f=fixture();mvc.perform(get(root(f)).with(customer(f.user()))).andExpect(status().isOk()).andExpect(view().name("orders/increase"));
  mvc.perform(get(root(f)).with(customer(f.user()+1000))).andExpect(status().isNotFound());
  verifyNoInteractions(gateway);
 }

 String quote(Fixture f,MockHttpSession session) throws Exception {
  var r=mvc.perform(post(root(f)+"/preview").with(customer(f.user())).with(csrf()).session(session)
    .param("contentRevision","0").param("items[0].orderItemId",Long.toString(f.item())).param("items[0].quantity","5")
    .param("totalAmount","1")).andExpect(status().isOk()).andExpect(model().attributeExists("preview","confirmationToken")).andReturn();
  return r.getModelAndView().getModel().get("confirmationToken").toString();
 }
 @Test void confirmedServerQuoteIsIdempotentAndDoesNotChangeOriginalOrder() throws Exception {
  var f=fixture();var session=new MockHttpSession();var token=quote(f,session);
  assertThat(jdbc.queryForObject("select count(*) from order_change_operations where order_id=?",Integer.class,f.order())).isZero();
  assertThat(jdbc.queryForObject("select active_payment_id from orders where id=?",Long.class,f.order())).isNull();
  var a=mvc.perform(post(root(f)).with(customer(f.user())).with(csrf()).session(session).param("confirmationToken",token)
    .param("totalAmount","1").param("items[0].quantity","999"))
    .andExpect(status().isFound()).andReturn().getResponse().getRedirectedUrl();
  mvc.perform(post(root(f)).with(customer(f.user())).with(csrf()).session(session).param("confirmationToken",token))
    .andExpect(status().isFound()).andExpect(redirectedUrl(a));
  assertThat(jdbc.queryForObject("select count(*) from order_change_operations where order_id=?",Integer.class,f.order())).isEqualTo(1);
  assertThat(jdbc.queryForObject("select after_amount from order_change_operations where order_id=?",Integer.class,f.order())).isEqualTo(1050);
  assertThat(jdbc.queryForObject("select quantity from order_items where id=?",Integer.class,f.item())).isEqualTo(2);
  assertThat(jdbc.queryForObject("select total_amount from orders where id=?",Integer.class,f.order())).isEqualTo(750);
  assertThat(jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product())).isEqualTo(7);
  mvc.perform(get(a).with(customer(f.user()))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("決済はまだ開始していません")));
  mvc.perform(get(root(f)).with(customer(f.user()))).andExpect(status().isFound()).andExpect(redirectedUrl(a));
  mvc.perform(get(a).with(customer(f.user()+1000))).andExpect(status().isNotFound());
  verifyNoInteractions(gateway);
 }
 @Test void csrfAndConfirmationOwnershipAreRequired() throws Exception {
  var f=fixture();var session=new MockHttpSession();var token=quote(f,session);
  mvc.perform(post(root(f)).with(customer(f.user())).session(session).param("confirmationToken",token)).andExpect(status().isForbidden());
  mvc.perform(post(root(f)).with(customer(f.user()+1000)).with(csrf()).session(session).param("confirmationToken",token)).andExpect(status().isNotFound());
  mvc.perform(post(root(f)).with(customer(f.user())).with(csrf()).param("confirmationToken",token)).andExpect(status().isConflict());
  verifyNoInteractions(gateway);
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"1","0","-1","2147483648"})
 void invalidQuantityIsRejected(String quantity) throws Exception {
  var f=fixture();mvc.perform(post(root(f)+"/preview").with(customer(f.user())).with(csrf())
    .param("contentRevision","0").param("items[0].orderItemId",Long.toString(f.item())).param("items[0].quantity",quantity))
    .andExpect(status().isConflict());verifyNoInteractions(gateway);
 }
 @Test void foreignOrDuplicateItemsAreRejected() throws Exception {
  var f=fixture();for(long id:List.of(f.item()+1000,f.item())){
   var request=post(root(f)+"/preview").with(customer(f.user())).with(csrf()).param("contentRevision","0")
    .param("items[0].orderItemId",Long.toString(id)).param("items[0].quantity","5");
   if(id==f.item())request.param("items[1].orderItemId",Long.toString(id)).param("items[1].quantity","6");
   mvc.perform(request).andExpect(status().isConflict());
  }
 }
 @Test void acceptanceRechecksDeadlineAndPreparationAfterPreview() throws Exception {
  for(boolean preparation:List.of(false,true)){
   var f=fixture();var session=new MockHttpSession();var token=quote(f,session);
   if(preparation)tx(()->{orders.findByIdForUpdate(f.order()).orElseThrow().beginShippingPreparation(orders.findCurrentTimeInTokyo(),7L,"admin");return null;});
   else jdbc.update("update orders set ordered_at=clock_timestamp()-interval '2 hours',change_deadline_at=clock_timestamp()-interval '1 hour' where id=?",f.order());
   mvc.perform(post(root(f)).with(customer(f.user())).with(csrf()).session(session).param("confirmationToken",token)).andExpect(status().isConflict());
   assertThat(jdbc.queryForObject("select count(*) from order_change_operations where order_id=?",Integer.class,f.order())).isZero();
  }
 }
 @Test void changedServerPricingRequiresNewConfirmation() throws Exception {
  var f=fixture();var session=new MockHttpSession();var token=quote(f,session);
  jdbc.update("update order_items set price=200 where id=?",f.item());
  mvc.perform(post(root(f)).with(customer(f.user())).with(csrf()).session(session).param("confirmationToken",token)).andExpect(status().isConflict());
  assertThat(jdbc.queryForObject("select count(*) from order_change_operations where order_id=?",Integer.class,f.order())).isZero();
 }
 @Test void statusAndWithdrawalAreLocalOnly() throws Exception {
  var f=fixture();var session=new MockHttpSession();var token=quote(f,session);
  var url=mvc.perform(post(root(f)).with(customer(f.user())).with(csrf()).session(session).param("confirmationToken",token)).andReturn().getResponse().getRedirectedUrl();
  mvc.perform(post(url+"/withdraw").with(customer(f.user())).with(csrf())).andExpect(status().isFound());
  mvc.perform(post(url+"/withdraw").with(customer(f.user())).with(csrf())).andExpect(status().isFound());
  mvc.perform(get(url).with(customer(f.user()))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("変更候補は取り下げ済み")));
  assertThat(jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product())).isEqualTo(10);
  verifyNoInteractions(gateway);
 }
 @Test void reviewAndCompletedStatesAreDistinct() throws Exception {
  var f=fixture();var session=new MockHttpSession();var token=quote(f,session);
  var url=mvc.perform(post(root(f)).with(customer(f.user())).with(csrf()).session(session).param("confirmationToken",token)).andReturn().getResponse().getRedirectedUrl();
  jdbc.update("update order_change_operations set state='MANUAL_REVIEW' where order_id=?",f.order());
  mvc.perform(get(url).with(customer(f.user()))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("要確認・復旧待ち")));
  jdbc.update("update order_change_operations set state='COMPLETED' where order_id=?",f.order());
  mvc.perform(get(url).with(customer(f.user()))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("注文変更が完了しました")));
  verifyNoInteractions(gateway);
 }
 @Test void withdrawalRequiresOwnerCsrfAndReservedState() throws Exception {
  var f=fixture();var session=new MockHttpSession();var token=quote(f,session);
  var url=mvc.perform(post(root(f)).with(customer(f.user())).with(csrf()).session(session).param("confirmationToken",token)).andReturn().getResponse().getRedirectedUrl();
  mvc.perform(post(url+"/withdraw").with(customer(f.user()))).andExpect(status().isForbidden());
  mvc.perform(post(url+"/withdraw").with(customer(f.user()+1000)).with(csrf())).andExpect(status().isNotFound());
  jdbc.update("update order_change_operations set state='AUTHORIZING' where order_id=?",f.order());
  mvc.perform(post(url+"/withdraw").with(customer(f.user())).with(csrf())).andExpect(status().isConflict());
  assertThat(jdbc.queryForObject("select state from inventory_reservations where operation_id=(select id from order_change_operations where order_id=?)",String.class,f.order())).isEqualTo("HELD");
  verifyNoInteractions(gateway);
 }
 @Test void sparseFormIndexIsRejectedWithoutReservation() throws Exception {
  var f=fixture();mvc.perform(post(root(f)+"/preview").with(customer(f.user())).with(csrf())
   .param("contentRevision","0").param("items[1001].orderItemId",Long.toString(f.item())).param("items[1001].quantity","5"))
   .andExpect(status().isConflict());verifyNoInteractions(gateway);
 }
}
