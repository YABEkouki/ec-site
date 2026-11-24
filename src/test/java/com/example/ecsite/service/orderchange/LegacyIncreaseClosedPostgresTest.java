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
class LegacyIncreaseClosedPostgresTest {
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

 Map<String,String> saved(){
  var rows=new TreeMap<String,String>();
  for(String table:List.of("orders","order_items","products","payments","payment_transactions","stock_movements","order_change_operations","order_change_items","inventory_reservations"))
   rows.put(table,jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(r) order by to_jsonb(r)::text),'[]'::jsonb)::text from "+table+" r",String.class));
  return rows;
 }
 @Test void everyEntranceOnlyGuidesToTheOrderDetail() throws Exception {
  var f=fixture();var before=saved();String message="数量の変更は、注文詳細の「注文内容を変更する」から行ってください。";
  for(var request:List.of(get(root(f)),
    post(root(f)+"/preview").with(csrf()).param("contentRevision","0").param("items[0].orderItemId",Long.toString(f.item())).param("items[0].quantity","5"),
    post(root(f)).with(csrf()).param("confirmationToken",UUID.randomUUID().toString())))
   mvc.perform(request.with(customer(f.user()))).andExpect(status().isFound()).andExpect(redirectedUrl("/orders/"+f.order()))
    .andExpect(flash().attribute("errorMessage",message)).andExpect(header().string("Cache-Control","no-store"));
  mvc.perform(get("/orders/"+f.order()).flashAttr("errorMessage",message).with(customer(f.user())))
   .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString(message)));
  assertThat(saved()).isEqualTo(before);verifyNoInteractions(gateway);
 }
 @Test void ownershipLoginAndCsrfAreStillRequired() throws Exception {
  var f=fixture();var before=saved();
  mvc.perform(get("/orders/"+f.order()).with(customer(f.user()+1000))).andExpect(status().isNotFound());
  mvc.perform(get(root(f))).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
  for(String path:List.of("","/preview"))mvc.perform(post(root(f)+path).with(customer(f.user()))).andExpect(status().isForbidden());
  mvc.perform(get(root(f)+"/"+UUID.randomUUID()).with(customer(f.user()))).andExpect(status().isNotFound());
  assertThat(saved()).isEqualTo(before);verifyNoInteractions(gateway);
 }
 @Test void theDatabaseRefusesNewLegacyRows() {
  var f=fixture();long payment=jdbc.queryForObject("select id from payments where order_id=?",Long.class,f.order());var before=saved();
  for(String state:List.of("RESERVED","ABORTED","COMPLETED"))rejects(()->jdbc.update("""
   insert into order_change_operations(id,order_id,customer_id,submission_key,request_digest,base_content_revision,
     old_payment_id,before_amount,after_amount,item_subtotal,charge_total,tax_amount,shipping_name,shipping_amount,
     shipping_tax_category_id,shipping_tax_category_code,shipping_tax_category_name,shipping_tax_rate,shipping_display_order,
     accepted_at,deadline_at,grace_until,state,created_at,updated_at)
   select ?,id,user_id,?,repeat('a',64),0,?,750,850,850,0,0,'送料',0,1,'STANDARD','標準',10,10,
     ordered_at,change_deadline_at,change_deadline_at+interval '10 minutes',?,localtimestamp,localtimestamp from orders where id=?
   """,UUID.randomUUID(),UUID.randomUUID(),payment,state,f.order()));
  rejects(()->jdbc.update("insert into order_change_items(operation_id,order_id,order_item_id) values(?,?,?)",UUID.randomUUID(),f.order(),f.item()));
  rejects(()->jdbc.update("insert into inventory_reservations(operation_id,order_item_id,product_id) values(?,?,?)",UUID.randomUUID(),f.item(),f.product()));
  assertThatThrownBy(()->LegacyOrderIncreaseFixture.accept(context,f.order(),f.user(),Map.of(f.item(),3))).hasMessageContaining("legacy quantity increase is closed");
  assertThat(saved()).isEqualTo(before);
 }
 @Autowired org.springframework.context.ApplicationContext context;
 static void rejects(org.assertj.core.api.ThrowableAssert.ThrowingCallable call){LegacyIncreaseClosureUpgradePostgresTest.rejects(call);}
}
