package com.example.ecsite.controller;

import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import com.example.ecsite.service.order.evidence.OrderEditBrowserService;

/** Explicit scoped browser authentication. A return GET never reconciles or accepts an SDK result. */
@Controller
@RequestMapping("/orders/{id}/edit/{admission}/payment")
public class OrderEditPaymentController {
    private final OrderEditBrowserService browser;
    public OrderEditPaymentController(OrderEditBrowserService browser){this.browser=browser;}
    @PostMapping public String start(@PathVariable long id,@PathVariable UUID admission,Model model,RedirectAttributes flash){
        var card=browser.startCard(id,admission);
        if(card.isEmpty()){
            flash.addFlashAttribute("errorMessage","認証画面を開始できません。開始済み・通信不明の場合は再認証せず、サーバーで結果をご確認ください。");
            return status(id,admission);
        }
        urls(id,admission,model);model.addAttribute("card",card.get());return "orders/increase-payment";
    }
    @GetMapping("/return") public String returned(@PathVariable long id,@PathVariable UUID admission,Model model){
        browser.admission(id,admission);urls(id,admission,model);return "orders/increase-payment-return";
    }
    @PostMapping("/continue") public String continuePayment(@PathVariable long id,@PathVariable UUID admission){
        browser.continueAdmission(id,admission);return status(id,admission);
    }
    private void urls(long id,UUID admission,Model model){
        model.addAttribute("feature125AutoContinue",true);
        model.addAttribute("orderId",id);model.addAttribute("paymentContinueUrl","/orders/"+id+"/edit/"+admission+"/payment/continue");
        model.addAttribute("paymentStatusUrl","/orders/"+id+"/edit/result?admission="+admission);
    }
    private String status(long id,UUID admission){return "redirect:/orders/"+id+"/edit/result?admission="+admission;}
}
