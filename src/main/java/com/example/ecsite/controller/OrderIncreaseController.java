package com.example.ecsite.controller;

import java.util.*;
import com.example.ecsite.form.OrderItemChangeForm;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.orderchange.OrderIncreaseService;
import com.example.ecsite.exception.*;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;

/** Local candidate screens only. No Gateway, client secret, or financial orchestration dependency. */
@Controller
@RequestMapping("/orders/{id}/increase")
public class OrderIncreaseController {
    private static final String QUOTES=OrderIncreaseController.class.getName()+".quotes";
    private final OrderIncreaseService increases;
    public OrderIncreaseController(OrderIncreaseService increases){this.increases=increases;}
    @InitBinder("increaseForm")
    void binder(WebDataBinder binder){
        binder.setAllowedFields("contentRevision","items[*].orderItemId","items[*].quantity");
        binder.setAutoGrowCollectionLimit(1000);
    }
    @ModelAttribute
    void headers(HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");
        response.setHeader("Referrer-Policy","no-referrer");
    }
    @GetMapping
    public String input(@PathVariable long id,@AuthenticationPrincipal CustomUserDetails user,Model model){
        var input=increases.input(id,user.getId());
        if(input.pending()!=null)return operationUrl(id,input.pending().id());
        model.addAttribute("orderId",id);
        if(!input.available()){model.addAttribute("mode","UNAVAILABLE");return "orders/increase";}
        var form=new OrderItemChangeForm();form.setContentRevision(input.revision());
        for(var line:input.lines()){
            var item=new OrderItemChangeForm.Item();item.setOrderItemId(line.id());item.setQuantity(line.beforeQuantity());form.getItems().add(item);
        }
        model.addAttribute("input",input);model.addAttribute("increaseForm",form);model.addAttribute("mode","INPUT");
        return "orders/increase";
    }
    @PostMapping("/preview")
    public String preview(@PathVariable long id,@AuthenticationPrincipal CustomUserDetails user,
            @ModelAttribute("increaseForm") OrderItemChangeForm form,BindingResult binding,HttpSession session,Model model){
        var input=increases.input(id,user.getId());
        if(binding.hasErrors() || form.getContentRevision()==null || form.getItems()==null || form.getItems().size()!=input.lines().size()
                || form.getItems().size()>1000)throw new IllegalArgumentException("Invalid quantities");
        var before=new HashMap<Long,Integer>();input.lines().forEach(l->before.put(l.id(),l.beforeQuantity()));
        var seen=new HashSet<Long>();var quantities=new HashMap<Long,Integer>();
        for(var item:form.getItems()){
            var quantity=item.getQuantity();var old=before.get(item.getOrderItemId());
            if(old==null || quantity==null || quantity<old || !seen.add(item.getOrderItemId()))throw new IllegalArgumentException("Only existing-item increases are accepted");
            if(quantity>old)quantities.put(item.getOrderItemId(),quantity);
        }
        UUID token=UUID.randomUUID();
        var quote=increases.preview(new OrderIncreaseService.Request(id,user.getId(),token,form.getContentRevision(),quantities));
        synchronized(session){
            var quotes=quotes(session);quotes.put(token,quote);
            while(quotes.size()>8)quotes.remove(quotes.keySet().iterator().next());
        }
        model.addAttribute("orderId",id);model.addAttribute("mode","CONFIRM");model.addAttribute("preview",quote);
        model.addAttribute("confirmationToken",token);return "orders/increase";
    }
    @PostMapping
    public String accept(@PathVariable long id,@AuthenticationPrincipal CustomUserDetails user,
            @RequestParam UUID confirmationToken,HttpSession session){
        final OrderIncreaseService.Preview quote;
        synchronized(session){quote=quotes(session).get(confirmationToken);}
        if(quote==null)throw new IllegalStateException("Confirmation expired");
        if(quote.request().orderId()!=id || quote.request().customerId()!=user.getId())throw new OrderNotFoundException(id);
        var operation=increases.acceptConfirmed(quote);
        return operationUrl(id,operation.id());
    }
    @GetMapping("/{operationId}")
    public String status(@PathVariable long id,@PathVariable UUID operationId,
            @AuthenticationPrincipal CustomUserDetails user,Model model){
        var operation=increases.status(id,user.getId(),operationId);
        model.addAttribute("orderId",id);model.addAttribute("operation",operation);model.addAttribute("mode","STATUS");
        model.addAttribute("statusMessage",switch(operation.state()){
            case "RESERVED" -> "数量増加候補を受け付けました。決済はまだ開始していません。";
            case "COMPLETED" -> "注文変更が完了しました。変更後の注文詳細をご確認ください。";
            case "ABORTED" -> "変更候補は取り下げ済みです。元の注文をご確認ください。";
            case "MANUAL_REVIEW","AUTH_UNKNOWN","OLD_CANCEL_UNKNOWN","OLD_CANCEL_REJECTED","ABORT_UNKNOWN" -> "要確認・復旧待ちです。重複した変更を行わず、確認をお待ちください。";
            default -> "注文変更を処理中です。この表示だけで決済・注文変更の完了を意味するものではありません。";
        });
        return "orders/increase";
    }
    @PostMapping("/{operationId}/withdraw")
    public String withdraw(@PathVariable long id,@PathVariable UUID operationId,@AuthenticationPrincipal CustomUserDetails user){
        increases.abort(id,user.getId(),operationId);return operationUrl(id,operationId);
    }
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class,InvalidOrderStatusException.class,org.springframework.beans.InvalidPropertyException.class})
    @ResponseStatus(org.springframework.http.HttpStatus.CONFLICT)
    public String unavailable(Model model){
        model.addAttribute("mode","UNAVAILABLE");return "orders/increase";
    }
    private String operationUrl(long id,UUID operation){return "redirect:/orders/"+id+"/increase/"+operation;}
    @SuppressWarnings("unchecked")
    private Map<UUID,OrderIncreaseService.Preview> quotes(HttpSession session){
        var value=session.getAttribute(QUOTES);
        if(value==null){value=new LinkedHashMap<UUID,OrderIncreaseService.Preview>();session.setAttribute(QUOTES,value);}
        return (Map<UUID,OrderIncreaseService.Preview>)value;
    }
}
