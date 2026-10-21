package com.example.ecsite.controller;

import java.util.UUID;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.orderchange.*;
import com.example.ecsite.payment.PaymentGatewayException;
import org.springframework.stereotype.Controller;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/orders/{id}/increase/{operationId}/payment")
public class OrderChangePaymentController {
    private final OrderChangeBrowserService browser;
    private final OrderIncreaseService increases;
    public OrderChangePaymentController(OrderChangeBrowserService browser,OrderIncreaseService increases){this.browser=browser;this.increases=increases;}
    @ModelAttribute
    void identifiers(@PathVariable long id,@PathVariable UUID operationId,Model model){
        model.addAttribute("orderId",id);model.addAttribute("operationId",operationId);
    }
    @PostMapping
    public String start(@PathVariable long id,@PathVariable UUID operationId,@AuthenticationPrincipal CustomUserDetails user,Model model){
        try{
            var card=browser.start(id,user.getId(),operationId);
            if(card.isPresent()){model.addAttribute("card",card.get());return "orders/increase-payment";}
        }catch(PaymentGatewayException stopped){/* Static status display, never echo an SDK/provider response. */}
        return status(id,operationId);
    }
    @GetMapping
    public String observe(@PathVariable long id,@PathVariable UUID operationId,@AuthenticationPrincipal CustomUserDetails user){
        try{browser.observe(id,user.getId(),operationId);}catch(PaymentGatewayException stopped){/* retain unknown */}
        return status(id,operationId);
    }
    @GetMapping("/return")
    public String returned(@PathVariable long id,@PathVariable UUID operationId,@AuthenticationPrincipal CustomUserDetails user){
        increases.status(id,user.getId(),operationId);
        // Query parameters are discarded before Spring Security/MVC. Browser claims are never evidence.
        return "orders/increase-payment-return";
    }
    @PostMapping("/continue")
    public String continueChange(@PathVariable long id,@PathVariable UUID operationId,@AuthenticationPrincipal CustomUserDetails user){
        try{browser.continueChange(id,user.getId(),operationId);}catch(PaymentGatewayException stopped){/* retain unknown */}
        return status(id,operationId);
    }
    private String status(long id,UUID operation){return "redirect:/orders/"+id+"/increase/"+operation;}
}
