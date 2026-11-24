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
    private final org.springframework.beans.factory.ObjectProvider<com.example.ecsite.service.order.evidence.OrderEditFailureClosureService> closures;
    public OrderEditPaymentController(OrderEditBrowserService browser){this(browser,null);}
    @org.springframework.beans.factory.annotation.Autowired
    public OrderEditPaymentController(OrderEditBrowserService browser,org.springframework.beans.factory.ObjectProvider<com.example.ecsite.service.order.evidence.OrderEditFailureClosureService> closures){this.browser=browser;this.closures=closures;}
    /** Explicit owner-bound CSRF POST; provider GET and local failure closure only. */
    @PostMapping("/discard-failed") public String discardFailed(@PathVariable long id,@PathVariable UUID admission,RedirectAttributes flash){
        browser.admission(id,admission);
        var outcome=closures.getObject().terminate(admission);
        if(outcome!=com.example.ecsite.service.order.evidence.OrderEditFailureClosureService.Outcome.CLOSED_UNAPPLIED){
            flash.addFlashAttribute("errorMessage","この変更の終了を確認できません。再認証や新しい変更をせず、保存済みの結果をご確認ください。");
            return status(id,admission)+"&closurePending=true";
        }
        flash.addFlashAttribute("successMessage","今回の変更を取りやめました。元の注文は引き続き有効で、注文内容と金額は変更していません。");
        return status(id,admission);
    }
    /** Feature139-C: saved cards for the reauthorization. Absent (feature not wired) keeps the card entry screen. */
    private com.example.ecsite.service.savedcard.OrderChangeSavedCardService savedCards;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setSavedCards(com.example.ecsite.service.savedcard.OrderChangeSavedCardService savedCards){this.savedCards=savedCards;}
    /** With usable saved cards, the card choice is shown first; nothing is recorded or sent to PAY.JP except the card list read. */
    @PostMapping public String start(@PathVariable long id,@PathVariable UUID admission,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.example.ecsite.security.CustomUserDetails user,
            Model model,RedirectAttributes flash){
        if(savedCards!=null&&user!=null&&savedCards.enabled()&&browser.result(id,admission).canAuthenticate()){
            var options=savedCards.options(user.getId());
            if(!options.isEmpty()){
                urls(id,admission,model);model.addAttribute("admission",admission);model.addAttribute("savedCards",options);
                return "orders/increase-payment-choice";
            }
        }
        return startCard(id,admission,model,flash);
    }
    /** The existing card entry: records the one-time confirmation start and shows the Widget. */
    @PostMapping("/manual") public String manual(@PathVariable long id,@PathVariable UUID admission,Model model,RedirectAttributes flash){
        return startCard(id,admission,model,flash);
    }
    @PostMapping("/saved-card") public String savedCard(@PathVariable long id,@PathVariable UUID admission,@RequestParam(required=false) String paymentMethodId,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.example.ecsite.security.CustomUserDetails user,
            Model model,RedirectAttributes flash){
        browser.admission(id,admission);
        if(savedCards==null||paymentMethodId==null||!paymentMethodId.matches("pm_[A-Za-z0-9_-]{1,252}")){
            flash.addFlashAttribute("errorMessage","登録済みのカードで認証できません。");return status(id,admission);
        }
        var result=savedCards.pay(user.getId(),id,admission,paymentMethodId);
        urls(id,admission,model);
        switch(result.outcome()){
            case REQUIRES_ACTION -> {model.addAttribute("card",result.card());return "orders/increase-payment-action";}
            case CONTINUE -> {return "orders/increase-payment-return";}
            case CARD_NOT_USABLE -> flash.addFlashAttribute("errorMessage","選択したカードは使えません。カードを選び直してください。");
            case UPDATE_REFUSED -> flash.addFlashAttribute("errorMessage","選択したカードを設定できませんでした。この変更ではカード認証をやり直せません。変更の締切後に、この変更は取りやめになります。");
            default -> flash.addFlashAttribute("errorMessage","登録済みのカードで認証を開始できません。開始済み・通信不明の場合は再認証せず、サーバーで結果をご確認ください。");
        }
        return status(id,admission);
    }
    /** Restarts 3-D Secure of the confirm this site sent; never sends the confirm again. */
    @PostMapping("/authenticate") public String authenticate(@PathVariable long id,@PathVariable UUID admission,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.example.ecsite.security.CustomUserDetails user,
            Model model,RedirectAttributes flash){
        browser.admission(id,admission);
        var card=savedCards==null?java.util.Optional.<com.example.ecsite.service.orderchange.OrderChangeBrowserService.Card>empty()
            :savedCards.authenticate(user.getId(),id,admission);
        if(card.isEmpty()){
            flash.addFlashAttribute("errorMessage","本人認証を開始できません。サーバーで結果をご確認ください。");return status(id,admission);
        }
        urls(id,admission,model);model.addAttribute("card",card.get());return "orders/increase-payment-action";
    }
    private String startCard(long id,UUID admission,Model model,RedirectAttributes flash){
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
    @PostMapping("/continue") public String continuePayment(@PathVariable long id,@PathVariable UUID admission,@RequestParam(defaultValue="false") boolean automatic,@RequestParam(required=false) Long automaticVersion){
        if(automatic){if(automaticVersion==null)browser.continueAutomatically(id,admission);else browser.continueAutomatically(id,admission,automaticVersion);}
        else browser.continueAdmission(id,admission);return status(id,admission);
    }
    private void urls(long id,UUID admission,Model model){
        model.addAttribute("feature125AutoContinue",true);
        model.addAttribute("orderId",id);model.addAttribute("paymentContinueUrl","/orders/"+id+"/edit/"+admission+"/payment/continue");
        model.addAttribute("paymentStatusUrl","/orders/"+id+"/edit/result?admission="+admission);
    }
    private String status(long id,UUID admission){return "redirect:/orders/"+id+"/edit/result?admission="+admission;}
}
