package com.example.ecsite.controller;

import java.util.*;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import com.example.ecsite.service.orderchange.*;

@Controller
@RequestMapping("/admin/orders/{orderId}/changes/{operationId}/old-cancel-recovery")
public class AdminOldCancelRecoveryController {
    private static final String COMMANDS=AdminOldCancelRecoveryController.class.getName()+".commands";
    private final AdminOldCancelRecoveryViewService views;
    private final OldCancelRecoveryService recovery;
    public AdminOldCancelRecoveryController(AdminOldCancelRecoveryViewService views,OldCancelRecoveryService recovery){this.views=views;this.recovery=recovery;}
    @GetMapping
    public String confirmation(@PathVariable long orderId,@PathVariable UUID operationId,HttpSession session,Model model){
        AdminOldCancelRecoveryViewService.actor();requireOrderId(orderId);
        var page=views.page(orderId,operationId);model.addAttribute("recoveryPage",page);
        OldCancelRecovery.Command command=page.resume();
        if(command==null&&page.target().eligible()){
            synchronized(session){
                command=commands(session).values().stream().filter(c->c.orderId()==orderId&&c.operationId().equals(operationId)
                    &&c.expectedVersion()==page.target().version()).findFirst().orElse(null);
                if(command==null)command=new OldCancelRecovery.Command(orderId,operationId,UUID.randomUUID(),page.target().version(),"VERIFY_OLD_CANCEL");
            }
        }
        if(command!=null){synchronized(session){var saved=commands(session);if(saved.size()>=50&&!saved.containsKey(command.requestId()))saved.remove(saved.keySet().iterator().next());saved.put(command.requestId(),command);}}
        model.addAttribute("recoveryCommand",command);return "admin/orders/old-cancel-recovery";
    }
    @PostMapping
    public String execute(@PathVariable long orderId,@PathVariable UUID operationId,@RequestParam UUID requestId,
            @RequestParam long expectedVersion,@RequestParam String reason,HttpSession session,RedirectAttributes flash){
        AdminOldCancelRecoveryViewService.actor();requireOrderId(orderId);
        // Resolve ownership before invoking recovery. No browser-supplied actor or financial target is accepted.
        var page=views.page(orderId,operationId);OldCancelRecovery.Command command;
        synchronized(session){command=commands(session).get(requestId);}
        if(command==null||command.orderId()!=orderId||!command.operationId().equals(operationId)
            ||command.expectedVersion()!=expectedVersion||!command.reason().equals(reason))throw new IllegalArgumentException("Confirmation command mismatch");
        OldCancelRecovery.Detail detail;
        try{detail=recovery.recoverDetailed(command).detail();}
        catch(AccessDeniedException denied){throw denied;}
        catch(org.springframework.dao.DataAccessException unavailable){detail=OldCancelRecovery.Detail.AUDIT_FAILED;}
        catch(IllegalStateException changed){detail=page.target().version()!=expectedVersion&&page.resume()==null
            ?OldCancelRecovery.Detail.VERSION_CONFLICT:OldCancelRecovery.Detail.NOT_ELIGIBLE;}
        catch(RuntimeException unavailable){detail=OldCancelRecovery.Detail.AUDIT_FAILED;}
        flash.addFlashAttribute("recoveryMessage",message(detail));flash.addFlashAttribute("recoveryResult",detail.name());
        return "redirect:/admin/orders/"+orderId+"/changes/"+operationId+"/old-cancel-recovery";
    }
    @SuppressWarnings("unchecked")
    private Map<UUID,OldCancelRecovery.Command> commands(HttpSession session){
        var value=(Map<UUID,OldCancelRecovery.Command>)session.getAttribute(COMMANDS);
        if(value==null){value=new LinkedHashMap<>();session.setAttribute(COMMANDS,value);}return value;
    }
    private void requireOrderId(long id){if(id<=0)throw new IllegalArgumentException("Invalid order identifier");}
    public static String message(OldCancelRecovery.Detail detail){return switch(detail){
        case COMPLETED->"注文切替が完了しました。";
        case COMMIT_PENDING->"旧与信取消を確認しました。注文切替は未完了です。再照合が必要です。";
        case OLD_EVIDENCE_UNCONFIRMED->"旧与信取消の証拠が成立しないため、回復を停止しました。";
        case NEW_AUTH_UNAVAILABLE->"新与信が失効・不足しているか、有効な与信証拠を確認できないため停止しました。";
        case GET_UNKNOWN->"PAY.JP GETの結果を確認できませんでした。取消や与信の失敗が確定したわけではありません。";
        case VERSION_CONFLICT->"状態の競合を検出しました。最新の状態を確認してください。";
        case NOT_ELIGIBLE->"このoperationは回復対象の条件を満たしていません。";
        case AUDIT_FAILED->"監査開始または結果保存を確認できませんでした。完了と判断せず、状態を確認してください。";
        case HOLD->"証拠または状態を安全に確認できないため停止しました。管理者確認が必要です。";
    };}
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Void> denied(){return ResponseEntity.status(403).build();}
    @ExceptionHandler({IllegalArgumentException.class,MethodArgumentTypeMismatchException.class,org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ResponseEntity<Void> invalid(){return ResponseEntity.badRequest().build();}
    @ExceptionHandler(com.example.ecsite.exception.OrderNotFoundException.class)
    public ResponseEntity<Void> missing(){return ResponseEntity.notFound().build();}
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<String> unavailable(){return ResponseEntity.status(503).body("画面情報を安全に取得できませんでした。");}
}
