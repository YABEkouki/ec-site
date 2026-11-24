package com.example.ecsite.controller;

import java.util.*;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.orderchange.OrderIncreaseService;
import com.example.ecsite.service.orderchange.OrderChangeWithdrawalService;
import com.example.ecsite.exception.*;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Status and withdrawal of quantity increases accepted before Feature140-A. Nothing new is accepted. */
@Controller
@RequestMapping("/orders/{id}/increase")
public class OrderIncreaseController {
    private static final String WITHDRAWALS=OrderIncreaseController.class.getName()+".withdrawals";
    public record WithdrawalCommand(long orderId,long customerId,UUID operationId,UUID requestId,long expectedVersion){}
    private final OrderIncreaseService increases;
    private final OrderChangeWithdrawalService withdrawals;
    public OrderIncreaseController(OrderIncreaseService increases,OrderChangeWithdrawalService withdrawals){this.increases=increases;this.withdrawals=withdrawals;}
    @ModelAttribute
    void headers(HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");
        response.setHeader("Referrer-Policy","no-referrer");
    }
    /** Feature140-A: closed. Nothing is read or written here; the order detail checks the owner. */
    @GetMapping
    public String closedInput(@PathVariable long id,RedirectAttributes redirect){return closed(id,redirect);}
    @PostMapping("/preview")
    public String closedPreview(@PathVariable long id,RedirectAttributes redirect){return closed(id,redirect);}
    @PostMapping
    public String closedAccept(@PathVariable long id,RedirectAttributes redirect){return closed(id,redirect);}
    private String closed(long id,RedirectAttributes redirect){
        redirect.addFlashAttribute("errorMessage","数量の変更は、注文詳細の「注文内容を変更する」から行ってください。");
        return "redirect:/orders/"+id;
    }
    @GetMapping("/{operationId}")
    public String status(@PathVariable long id,@PathVariable UUID operationId,
            @AuthenticationPrincipal CustomUserDetails user,Model model){
        var operation=increases.status(id,user.getId(),operationId);
        var withdrawal=withdrawals.status(id,user.getId(),operationId);
        model.addAttribute("withdrawalStatus",withdrawal);
        model.addAttribute("orderId",id);model.addAttribute("operation",operation);model.addAttribute("mode","STATUS");
        model.addAttribute("statusMessage","LATE_ADOPTION".equals(operation.finalOutcome())&&!"COMPLETED".equals(operation.state())
            ? "注文変更期限までに変更処理が完了しませんでした。決済状態の確認が完了次第、変更後の内容で発送準備を進めます。"
            : "NOT_ADOPTED".equals(operation.finalOutcome())&&!"ABORTED".equals(operation.state())
            ? "注文変更期限までに決済手続きが完了しなかったため、この変更は成立しませんでした。決済状態の確認後に発送準備を進めます。"
            : "NOT_ADOPTED".equals(operation.finalOutcome())
            ? "注文変更期限までに決済手続きが完了しなかったため、この変更は成立しませんでした。ご注文は変更前の内容のままです。"
            : switch(operation.state()){
            case "RESERVED" -> "この変更の受付は終了しました。新しい決済は開始できません。候補を取り下げると、確保した追加在庫を解放します。";
            case "COMPLETED" -> "注文変更が完了しました。変更後の注文詳細をご確認ください。";
            case "ABORTED" -> operation.deadlineClosed()
                ? "注文変更期限を過ぎたため、この変更は成立しませんでした。ご注文は変更前の内容のままです。"
                : "変更候補は取り下げ済みです。元の注文をご確認ください。";
            case "MANUAL_REVIEW" -> operation.deadlineClosed()
                ? "注文変更期限を過ぎたため、この変更は成立しませんでした。決済状態の確認をお待ちください。"
                : "要確認・復旧待ちです。重複した変更を行わず、確認をお待ちください。";
            case "AUTH_UNKNOWN","OLD_CANCEL_UNKNOWN","OLD_CANCEL_REJECTED","ABORT_UNKNOWN" -> "要確認・復旧待ちです。重複した変更を行わず、確認をお待ちください。";
            default -> "注文変更を処理中です。この表示だけで決済・注文変更の完了を意味するものではありません。";
        });
        return "orders/increase";
    }
    @GetMapping("/{operationId}/withdraw")
    public String confirmWithdrawal(@PathVariable long id,@PathVariable UUID operationId,
            @AuthenticationPrincipal CustomUserDetails user,HttpSession session,Model model){
        var operation=increases.status(id,user.getId(),operationId);
        var withdrawal=withdrawals.status(id,user.getId(),operationId);
        if(!withdrawal.canRequest())return operationUrl(id,operationId);
        final WithdrawalCommand command;
        synchronized(session){
            var commands=withdrawalCommands(session);
            var existing=commands.get(operationId);
            if(existing!=null && (existing.orderId()!=id || existing.customerId()!=user.getId()))throw new OrderNotFoundException(id);
            command=existing!=null && existing.expectedVersion()==operation.version()?existing:
                    new WithdrawalCommand(id,user.getId(),operationId,UUID.randomUUID(),operation.version());
            commands.put(operationId,command);
        }
        model.addAttribute("orderId",id);model.addAttribute("operation",operation);
        model.addAttribute("withdrawalCommand",command);model.addAttribute("mode","WITHDRAW_CONFIRM");
        return "orders/increase";
    }
    @PostMapping("/{operationId}/withdraw")
    public String withdraw(@PathVariable long id,@PathVariable UUID operationId,@AuthenticationPrincipal CustomUserDetails user,
            @RequestParam(required=false) UUID requestId,@RequestParam(required=false) Long expectedVersion,HttpSession session){
        // Check ownership even when the browser has no valid confirmation command.
        withdrawals.status(id,user.getId(),operationId);
        final WithdrawalCommand command;
        synchronized(session){command=withdrawalCommands(session).get(operationId);}
        if(command==null)throw new IllegalStateException("Withdrawal confirmation expired");
        if(command.orderId()!=id || command.customerId()!=user.getId() || !command.operationId().equals(operationId))throw new OrderNotFoundException(id);
        if(!command.requestId().equals(requestId) || expectedVersion==null || command.expectedVersion()!=expectedVersion)
            throw new IllegalStateException("Withdrawal confirmation changed");
        withdrawals.withdraw(id,user.getId(),operationId,command.requestId(),command.expectedVersion());
        return operationUrl(id,operationId);
    }
    @SuppressWarnings("unchecked")
    private Map<UUID,WithdrawalCommand> withdrawalCommands(HttpSession session){
        var value=session.getAttribute(WITHDRAWALS);
        if(value==null){value=new LinkedHashMap<UUID,WithdrawalCommand>();session.setAttribute(WITHDRAWALS,value);}
        return (Map<UUID,WithdrawalCommand>)value;
    }
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class,InvalidOrderStatusException.class,org.springframework.beans.InvalidPropertyException.class})
    @ResponseStatus(org.springframework.http.HttpStatus.CONFLICT)
    public String unavailable(Model model){
        model.addAttribute("mode","UNAVAILABLE");return "orders/increase";
    }
    private String operationUrl(long id,UUID operation){return "redirect:/orders/"+id+"/increase/"+operation;}
}
