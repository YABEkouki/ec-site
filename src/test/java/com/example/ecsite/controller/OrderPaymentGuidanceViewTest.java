package com.example.ecsite.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import java.time.LocalDateTime;
import java.util.*;
import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.repository.*;
import com.example.ecsite.service.payment.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class OrderPaymentGuidanceViewTest {
    @Controller static class ViewController {
        final Order order; final PaymentService payment;
        ViewController(Order order,PaymentService payment){this.order=order;this.payment=payment;}
        @GetMapping("/test-order") String view(Model model) {
            model.addAttribute("order",order);model.addAttribute("statusHistories",List.of());
            model.addAttribute("shippingAddressHistories",List.of());
            model.addAttribute("canResumePayment",payment.canResumeAuthorization(order));
            model.addAttribute("requiresPaymentConfirmation",payment.requiresAuthorizationConfirmation(order));
            return "orders/detail";
        }
    }
    @ParameterizedTest @ValueSource(strings={"missing","blank","pending","threeDSecure"})
    void actualTemplateShowsSafeStopOrGetResume(String scenario) throws Exception {
        LocalDateTime now=LocalDateTime.now();Order order=new Order(1L,3000,now,now.plusDays(1));ReflectionTestUtils.setField(order,"id",42L);
        Payment payment=new Payment(order,PaymentProvider.PAYJP,PaymentMethod.CARD,3000,now);ReflectionTestUtils.setField(payment,"id",52L);
        boolean missing=scenario.equals("missing")||scenario.equals("blank");
        if(!missing)payment.setProviderPaymentId("pf",now);
        if(scenario.equals("blank"))ReflectionTestUtils.setField(payment,"providerPaymentId","   ");
        if(scenario.equals("threeDSecure"))payment.markRequiresAction(now);
        var repository=mock(PaymentRepository.class);var transactions=mock(PaymentTransactionRepository.class);
        when(repository.findByOrderIdOrderByCreatedAtAscIdAsc(42L)).thenReturn(List.of(payment));
        when(transactions.findByPaymentIdAndTransactionTypeAndStatus(52L,PaymentTransactionType.AUTHORIZE,PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.of(new PaymentTransaction(payment,PaymentTransactionType.AUTHORIZE,3000,0,"key",now)));
        var service=new PaymentService(repository,transactions,mock(PaymentConsistencyEvaluator.class));
        var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setTemplateMode("HTML");resolver.setCharacterEncoding("UTF-8");
        var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);var views=new ThymeleafViewResolver();views.setTemplateEngine(engine);views.setCharacterEncoding("UTF-8");views.setContentType("text/html;charset=UTF-8");
        var mvc=MockMvcBuilders.standaloneSetup(new ViewController(order,service)).setViewResolvers(views).build();
        String html=mvc.perform(get("/test-order")).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        if(missing)assertThat(html).doesNotContain("カード決済を再開").contains("決済結果を確認中", "管理者へお問い合わせ", "42");
        else assertThat(html).contains("カード決済を再開").doesNotContain("管理者へお問い合わせ");
    }
}
