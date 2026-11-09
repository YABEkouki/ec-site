package com.example.ecsite.support.display;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import com.example.ecsite.config.*;
import com.example.ecsite.controller.*;
import com.example.ecsite.security.*;
import com.example.ecsite.service.*;
import com.example.ecsite.service.order.evidence.*;
import com.example.ecsite.service.orderchange.OrderChangeBrowserService.Card;
import com.example.ecsite.service.payment.*;
import com.example.ecsite.payment.*;
import com.example.ecsite.web.OrderChangePaymentPrivacyFilter;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Synthetic MVC slice only. No application, DataSource, repositories or payment transport. */
@WebMvcTest({OrderEditController.class,OrderEditPaymentController.class,CheckoutController.class})
@Import({SecurityConfig.class,OrderChangePaymentPrivacyFilter.class})
class Feature125DisplayRenderingTest {
    static final long ORDER=900001,CUSTOMER=900002,PAYMENT=910001;
    static final String EDIT="/orders/900001/edit";
    static final UUID RECEIPT=UUID.fromString("00000000-0000-0000-0000-000000900001");
    static final String CARD=EDIT+"/"+RECEIPT+"/payment";
    final CustomUserDetails customer=new CustomUserDetails(CUSTOMER,"表示専用・架空顧客","unused",true,List.of(new SimpleGrantedAuthority("ROLE_USER")));
    @Autowired MockMvc mvc;@Autowired ApplicationContext context;
    @MockitoBean OrderEditBrowserService browser;
    @MockitoBean OrderService orders;
    @MockitoBean ShippingAddressService addresses;
    @MockitoBean PaymentAuthorizationService authorization;
    @MockitoBean PayJpProperties payjp;
    @MockitoBean AdminUserDetailsService adminUserDetailsService;
    @MockitoBean CustomUserDetailsService customUserDetailsService;
    @MockitoBean AdminAuthenticationSuccessHandler adminAuthenticationSuccessHandler;
    @MockitoBean CustomerAuthenticationSuccessHandler customerAuthenticationSuccessHandler;
    final List<DisplayPage> pages=new ArrayList<>();
    OrderContentSnapshot before,after;
    LocalDateTime now=LocalDateTime.of(2030,1,10,12,0);

    @BeforeEach void fixtures(){
        before=snapshot(1);after=snapshot(2);
        when(browser.load(ORDER)).thenReturn(page(null));
        when(browser.preview(eq(ORDER),any())).thenReturn(new OrderEditPreviewService.Preview(before,after,now.plusHours(2),OrderChangeRoutePolicy.Route.RETAIN_AUTH));
        var order=mock(com.example.ecsite.entity.Order.class);when(order.getId()).thenReturn(ORDER);
        when(orders.findOrderByIdAndUserId(ORDER,CUSTOMER)).thenReturn(order);
        when(payjp.publicKey()).thenReturn("pk_test_display_only_dummy");
        when(authorization.recoverAuthorization(order,PAYMENT)).thenReturn(new PaymentAuthorizationRecovery(PAYMENT,new AuthorizationResult(AuthorizationResultStatus.REQUIRES_PAYMENT_METHOD,null,null,null),"display_only_not_a_provider_secret",PaymentAuthorizationRecoveryAction.RESUME_CHECKOUT));
        when(authorization.checkoutTarget(ORDER,PAYMENT,CUSTOMER)).thenReturn(Optional.of(new PaymentOperationSnapshotService.CheckoutTarget(ORDER,PAYMENT,920001)));
        when(browser.startCard(ORDER,RECEIPT)).thenReturn(Optional.of(new Card("pk_test_display_only_dummy","display_only_not_a_provider_secret","http://127.0.0.1:1/display-only-return")));
    }
    OrderContentSnapshot snapshot(int multiplier){
        int subtotal=(680*2+480)*multiplier;
        return new OrderContentSnapshot(1,ORDER,CUSTOMER,0,List.of(
            new OrderContentSnapshot.Item(930001,940001,"表示専用：とても長い商品名でボタンと文字の折り返しを確認する架空商品・大容量セット",950001,"架空分類",680,2*multiplier,1360*multiplier,960001,"STANDARD","標準",1000),
            new OrderContentSnapshot.Item(930002,940002,"表示専用商品B",950001,"架空分類",480,multiplier,480*multiplier,960001,"STANDARD","標準",1000)),
            List.of(new OrderContentSnapshot.Charge(970001,"SHIPPING","送料",550,960001,"STANDARD","標準",1000,1)),subtotal,550,(subtotal+550)*10/110,subtotal+550);
    }
    OrderEditPreviewService.PageContext page(String reason){
        var facts=new OrderPaymentEvidence.Facts(before,PAYMENT,List.of(),List.of(),true,null,List.of(),null,false,false);
        return new OrderEditPreviewService.PageContext(new OrderEditCandidate.Context(facts,CUSTOMER,"ORDERED",now.minusHours(1),now.plusHours(2),null,null,null,now),reason);
    }
    MvcResult render(MockHttpServletRequestBuilder request,String view)throws Exception {
        return mvc.perform(request.with(user(customer))).andExpect(status().isOk()).andExpect(view().name(view)).andReturn();
    }
    DisplayPage capture(String name,String path,MvcResult rendered)throws Exception {
        var response=rendered.getResponse();String html=response.getContentAsString();
        assertThat(html).contains("lang=\"ja\"","name=\"viewport\"","site-header","/css/style.css","class=\"flow-main\"");
        assertThat(html).doesNotContain("sk_test_","sk_live_","JSESSIONID=","jdbc:postgresql:");
        if(html.contains("<form"))assertThat(html).contains("name=\"_csrf\"");
        Map<String,String> headers=new LinkedHashMap<>();
        for(String h:response.getHeaderNames())if(DisplayPage.HEADERS.contains(h.toLowerCase(Locale.ROOT)))headers.put(h,response.getHeader(h));
        String csp=response.getHeader("Content-Security-Policy");
        if(csp!=null){
            var matcher=Pattern.compile("<script[^>]*nonce=\"([^\"]+)\"").matcher(html);int count=0;
            while(matcher.find()){assertThat(csp).contains("'nonce-"+matcher.group(1)+"'");count++;}
            if(html.contains("<script"))assertThat(count).isPositive();
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        }
        var p=new DisplayPage(name,path,headers,response.getContentAsByteArray());pages.add(p);return p;
    }
    MockHttpSession checkoutSession(){var s=new MockHttpSession();s.setAttribute("checkoutOrderId",ORDER);s.setAttribute("checkoutPaymentId",PAYMENT);return s;}

    @Test void generatesAllEightTemplatesAndRequiredCasesWithoutDatabaseOrGateway()throws Exception {
        assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
        assertThat(context.getBeanNamesForType(PaymentGateway.class)).isEmpty();
        assertThat(context.getBeanNamesForType(OrderEditApplicationTransactions.class)).isEmpty();
        assertThat(mockingDetails(browser).isMock()).isTrue();assertThat(mockingDetails(authorization).isMock()).isTrue();
        capture("edit-normal",EDIT+"?case=normal",render(get(EDIT),"orders/edit"));
        when(browser.load(ORDER)).thenReturn(page("表示専用：発送準備開始後は編集できません。"));
        capture("edit-unavailable",EDIT+"?case=unavailable",render(get(EDIT),"orders/edit"));
        when(browser.load(ORDER)).thenReturn(page(null));
        capture("edit-input-error",EDIT+"?case=input-error",render(post(EDIT+"/confirm").with(csrf()).param("revision","0").param("lines[0].existingItemId","930001").param("lines[0].productId","940001").param("lines[0].quantity","-1"),"orders/edit"));
        for(boolean multiple:List.of(false,true)){
            var session=new MockHttpSession();
            var result=mvc.perform(post(EDIT+"/confirm").session(session).with(user(customer)).with(csrf()).param("revision","0").param("lines[0].existingItemId","930001").param("lines[0].productId","940001").param("lines[0].quantity","4"))
                .andExpect(status().is3xxRedirection()).andReturn();
            String location=result.getResponse().getRedirectedUrl();
            if(!multiple){var single=new OrderContentSnapshot(1,ORDER,CUSTOMER,0,List.of(before.items().getFirst()),before.charges(),1360,550,173,1910);
                when(browser.preview(eq(ORDER),any())).thenReturn(new OrderEditPreviewService.Preview(single,single,now.plusHours(2),OrderChangeRoutePolicy.Route.RETAIN_AUTH));
                // Start a fresh quote after selecting the single-line fixture, so GET verifies the saved preview.
                session=new MockHttpSession();result=mvc.perform(post(EDIT+"/confirm").session(session).with(user(customer)).with(csrf()).param("revision","0").param("lines[0].existingItemId","930001").param("lines[0].productId","940001").param("lines[0].quantity","4")).andReturn();location=result.getResponse().getRedirectedUrl();
            }
            capture(multiple?"confirm-multiple":"confirm-normal",EDIT+"/confirm?case="+(multiple?"multiple":"normal"),render(get(location).session(session),"orders/edit-confirm"));
            when(browser.preview(eq(ORDER),any())).thenReturn(new OrderEditPreviewService.Preview(before,after,now.plusHours(2),OrderChangeRoutePolicy.Route.RETAIN_AUTH));
        }
        for(String label:List.of("変更完了","処理中","カード認証待ち","確認待ち","未適用終了")){
            int index=List.of("変更完了","処理中","カード認証待ち","確認待ち","未適用終了").indexOf(label);
            var step=new OrderEditBrowserService.NextStep(OrderEditBrowserService.NextAction.DISPLAY_ONLY,"DISPLAY_FIXTURE");
            when(browser.result(ORDER,RECEIPT)).thenReturn(new OrderEditBrowserService.BrowserResult(RECEIPT,label,false,label.equals("カード認証待ち"),step));
            var p=capture("result-"+index,EDIT+"/result?case="+index,render(get(EDIT+"/result").param("admission",RECEIPT.toString()),"orders/edit-result"));
            if(!label.equals("変更完了"))assertThat(new String(p.body(),java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("class=\"completion-card\"");
        }
        when(browser.result(ORDER,RECEIPT)).thenReturn(new OrderEditBrowserService.BrowserResult(RECEIPT,"受付済み",true,false,new OrderEditBrowserService.NextStep(OrderEditBrowserService.NextAction.AUTO_CONTINUE_ALLOWED,"DISPLAY_FIXTURE")));
        capture("result-auto",EDIT+"/result?case=auto",render(get(EDIT+"/result").param("admission",RECEIPT.toString()),"orders/edit-result"));
        capture("edit-card",CARD,render(post(CARD).with(csrf()),"orders/increase-payment"));
        capture("edit-return",CARD+"/return",render(get(CARD+"/return"),"orders/increase-payment-return"));
        capture("checkout-payment","/checkout/payment",render(get("/checkout/payment").session(checkoutSession()),"checkout/payment"));
        capture("checkout-return","/checkout/payment/return",render(get("/checkout/payment/return").session(checkoutSession()),"checkout/payment-return"));
        capture("checkout-complete","/checkout/complete",render(get("/checkout/complete").flashAttr("orderId",ORDER),"checkout/complete"));
        for(String path:List.of("/css/style.css","/favicon.ico")){
            var r=mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse();
            var headers=new LinkedHashMap<String,String>();
            for(String h:r.getHeaderNames())if(DisplayPage.HEADERS.contains(h.toLowerCase(Locale.ROOT)))headers.put(h,r.getHeader(h));
            pages.add(new DisplayPage(path.endsWith("ico")?"favicon":"stylesheet",path,headers,r.getContentAsByteArray()));
        }
        assertThat(pages).hasSize(18);
        verify(browser,never()).submit(anyLong(),any(),any(),any());verify(browser,never()).continueAdmission(anyLong(),any());verify(browser,never()).continueAutomatically(anyLong(),any());
        verify(orders,times(2)).findOrderByIdAndUserId(ORDER,CUSTOMER);verifyNoMoreInteractions(orders);
        var output=System.getProperty("f125.display.output");
        Path dir=output==null?Files.createTempDirectory("f125-display-pages-"):Path.of(output);
        DisplayBundle.write(dir,pages);
        var restored=tools.jackson.databind.json.JsonMapper.builder().build().readValue(Files.readString(dir.resolve("pages.json")),DisplayPage[].class);
        assertThat(restored).hasSize(pages.size());
        for(int i=0;i<restored.length;i++){assertThat(restored[i].body()).isEqualTo(pages.get(i).body());assertThat(restored[i].headers()).isEqualTo(pages.get(i).headers());}
        try(var server=new DisplayHttpServer(Arrays.asList(restored))){
            var client=java.net.http.HttpClient.newHttpClient();
            for(var page:restored){var response=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(server.origin()+page.path())).build(),java.net.http.HttpResponse.BodyHandlers.ofByteArray());assertThat(response.statusCode()).isEqualTo(200);assertThat(response.body()).isEqualTo(page.body());for(var header:page.headers().entrySet())assertThat(response.headers().firstValue(header.getKey())).contains(header.getValue());}
            assertThat(server.receivedPosts()).isZero();assertThat(server.forwardedRequests()).isZero();
        }
    }
}
