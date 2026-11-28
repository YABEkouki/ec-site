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
@SpringBootTest(properties={"logging.level.root=WARN","spring.jpa.show-sql=false",
    "spring.flyway.target=107"})
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
        // Feature147: on purpose, this legacy fixture leaves the order without an active payment (like orders before V55),
        // so the legacy increase also covers ActivePaymentService.initializeForOrderChange setting it.
        var payment=new Payment(order,PaymentProvider.PAYJP,PaymentMethod.CARD,750,now);payment.setProviderPaymentId("pfw_"+key,now);payment.markAuthorized(now);payments.saveAndFlush(payment);
        var auth=new PaymentTransaction(payment,PaymentTransactionType.AUTHORIZE,750,0,key,now);auth.markSucceeded(payment.getProviderPaymentId(),now);transactions.saveAndFlush(auth);
        return new Fixture(order.getId(),user.getId(),order.getItems().getFirst().getId(),product.getId());
    });}

 @Autowired org.springframework.context.ApplicationContext context;
 String accepted(Fixture f){return root(f)+"/"+LegacyOrderIncreaseFixture.accept(context,f.order(),f.user(),Map.of(f.item(),5)).id();}
 String root(Fixture f){return "/orders/"+f.order()+"/increase";}
 org.springframework.test.web.servlet.request.RequestPostProcessor customer(long id){return user(new CustomUserDetails(id,"customer","unused",true,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))));}
 @Test void reservedStatusOffersOnlyWithdrawalAndIsOwned() throws Exception {
  var f=fixture();var url=accepted(f);
  mvc.perform(get(url).with(customer(f.user()))).andExpect(status().isOk())
   .andExpect(content().string(org.hamcrest.Matchers.containsString("この変更の受付は終了しました。新しい決済は開始できません。")))
   .andExpect(content().string(org.hamcrest.Matchers.containsString(url+"/withdraw")))
   .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("カード認証を開始・確認する"))));
  mvc.perform(get(url).with(customer(f.user()+1000))).andExpect(status().isNotFound());
  verifyNoInteractions(gateway);
 }
 @Test void statusAndWithdrawalAreLocalOnly() throws Exception {
  var f=fixture();var session=new MockHttpSession();
  var url=accepted(f);
  var confirmation=mvc.perform(get(url+"/withdraw").with(customer(f.user())).session(session))
   .andExpect(status().isOk()).andExpect(view().name("orders/increase")).andReturn();
  var command=(com.example.ecsite.controller.OrderIncreaseController.WithdrawalCommand)confirmation.getModelAndView().getModel().get("withdrawalCommand");
  var repeated=mvc.perform(get(url+"/withdraw").with(customer(f.user())).session(session)).andExpect(status().isOk()).andReturn();
  assertThat(repeated.getModelAndView().getModel().get("withdrawalCommand")).isEqualTo(command);
  assertThat(jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product())).isEqualTo(7);
  for(int attempt=0;attempt<2;attempt++)mvc.perform(post(url+"/withdraw").with(customer(f.user())).with(csrf()).session(session)
   .param("requestId",command.requestId().toString()).param("expectedVersion",Long.toString(command.expectedVersion()))).andExpect(status().isFound());
  mvc.perform(get(url).with(customer(f.user()))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("変更候補は取り下げ済み")));
  assertThat(jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product())).isEqualTo(10);
  verifyNoInteractions(gateway);
 }
 @Test void reviewAndCompletedStatesAreDistinct() throws Exception {
  var f=fixture();var session=new MockHttpSession();
  var url=accepted(f);
  jdbc.update("update order_change_operations set state='MANUAL_REVIEW' where order_id=?",f.order());
  mvc.perform(get(url).with(customer(f.user()))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("要確認・復旧待ち")));
  jdbc.update("update order_change_operations set state='COMPLETED' where order_id=?",f.order());
  mvc.perform(get(url).with(customer(f.user()))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("注文変更が完了しました")));
  verifyNoInteractions(gateway);
 }
 @Test void withdrawalRequiresOwnerCsrfAndSessionConfirmation() throws Exception {
  var f=fixture();var session=new MockHttpSession();
  var url=accepted(f);
  mvc.perform(post(url+"/withdraw").with(customer(f.user()))).andExpect(status().isForbidden());
  mvc.perform(get(url+"/withdraw").with(customer(f.user()+1000))).andExpect(status().isNotFound());
  mvc.perform(post(url+"/withdraw").with(customer(f.user()+1000)).with(csrf())).andExpect(status().isNotFound());
  var confirmation=mvc.perform(get(url+"/withdraw").with(customer(f.user())).session(session)).andExpect(status().isOk()).andReturn();
  var command=(com.example.ecsite.controller.OrderIncreaseController.WithdrawalCommand)confirmation.getModelAndView().getModel().get("withdrawalCommand");
  mvc.perform(post(url+"/withdraw").with(customer(f.user())).with(csrf()).session(session)
   .param("requestId",UUID.randomUUID().toString()).param("expectedVersion",Long.toString(command.expectedVersion()))).andExpect(status().isConflict());
  mvc.perform(post(url+"/withdraw").with(customer(f.user())).with(csrf()).session(new MockHttpSession())
   .param("requestId",command.requestId().toString()).param("expectedVersion",Long.toString(command.expectedVersion()))).andExpect(status().isConflict());
  jdbc.update("update order_change_operations set state='AUTHORIZING' where order_id=?",f.order());
  mvc.perform(post(url+"/withdraw").with(customer(f.user())).with(csrf())).andExpect(status().isConflict());
  assertThat(jdbc.queryForObject("select state from inventory_reservations where operation_id=(select id from order_change_operations where order_id=?)",String.class,f.order())).isEqualTo("HELD");
  verifyNoInteractions(gateway);
 }
 @Test void staleUnsubmittedWithdrawalConfirmationCanBeRefreshed() throws Exception {
  var f=fixture();var session=new MockHttpSession();
  var url=accepted(f);
  var first=mvc.perform(get(url+"/withdraw").with(customer(f.user())).session(session)).andExpect(status().isOk()).andReturn();
  var original=(com.example.ecsite.controller.OrderIncreaseController.WithdrawalCommand)first.getModelAndView().getModel().get("withdrawalCommand");
  jdbc.update("update order_change_operations set version=version+1 where order_id=?",f.order());
  var refreshed=mvc.perform(get(url+"/withdraw").with(customer(f.user())).session(session)).andExpect(status().isOk()).andReturn();
  var current=(com.example.ecsite.controller.OrderIncreaseController.WithdrawalCommand)refreshed.getModelAndView().getModel().get("withdrawalCommand");
  assertThat(current.requestId()).isNotEqualTo(original.requestId());
  assertThat(current.expectedVersion()).isEqualTo(original.expectedVersion()+1);
  mvc.perform(post(url+"/withdraw").with(customer(f.user())).with(csrf()).session(session)
   .param("requestId",original.requestId().toString()).param("expectedVersion",Long.toString(original.expectedVersion()))).andExpect(status().isConflict());
  mvc.perform(post(url+"/withdraw").with(customer(f.user())).with(csrf()).session(session)
   .param("requestId",current.requestId().toString()).param("expectedVersion",Long.toString(current.expectedVersion()))).andExpect(status().isFound());
  assertThat(jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product())).isEqualTo(10);
  verifyNoInteractions(gateway);
 }
}
