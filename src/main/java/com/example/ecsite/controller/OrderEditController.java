package com.example.ecsite.controller;

import java.util.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import com.example.ecsite.exception.*;
import com.example.ecsite.form.OrderEditForm;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.order.evidence.*;

/** Customer preview, explicit receipt submission and read-only result routes. */
@Controller
@RequestMapping("/orders/{id}/edit")
public class OrderEditController {
    private static final String QUOTES=OrderEditController.class.getName()+".previews";
    private record Quote(long customer,OrderEditCandidate.Request request,OrderEditPreviewService.Preview preview){}
    public record Row(String productName,Integer unitPrice,boolean existing,int beforeQuantity){}
    private final OrderEditBrowserService service;
    public OrderEditController(OrderEditBrowserService service){this.service=service;}
    @InitBinder("editForm") void binder(WebDataBinder binder){
        binder.setAllowedFields("revision","lines[*].existingItemId","lines[*].productId","lines[*].quantity");
        binder.setAutoGrowCollectionLimit(1000);
    }
    @ModelAttribute void common(@PathVariable long id,HttpServletResponse response,Model model){
        model.addAttribute("orderId",id);response.setHeader("Cache-Control","no-store");
    }
    @GetMapping
    public String input(@PathVariable long id,@AuthenticationPrincipal CustomUserDetails user,
            @RequestParam(required=false) UUID preview,HttpSession session,Model model){
        var page=service.load(id);OrderEditForm form=OrderEditForm.from(page.before());
        if(preview!=null){var q=quote(session,preview,id,user.getId());if(q!=null)form=OrderEditForm.from(q.request());
            else model.addAttribute("errorMessage","確認画面の有効性を確認できません。入力し直してください。");}
        return edit(page,form,null,null,0,model);
    }
    @PostMapping("/enroll")
    public String enroll(@PathVariable long id,RedirectAttributes flash){
        var result=service.enroll(id);
        switch(result.status()){
            case ENROLLED,ALREADY_ENROLLED -> flash.addFlashAttribute("successMessage","注文情報の確認が完了しました。編集内容をご確認ください。");
            case COMMIT_UNKNOWN -> flash.addFlashAttribute("errorMessage","確認結果を確定できません。注文詳細から状態を確認してください。");
            case OBSERVATION_UNAVAILABLE -> flash.addFlashAttribute("errorMessage","決済情報を確認できませんでした。注文内容は変更していません。");
            default -> flash.addFlashAttribute("errorMessage","注文情報を確認できないため、編集を開始できません。注文詳細をご確認ください。");
        }
        return "redirect:/orders/"+id+"/edit";
    }
    @PostMapping("/search")
    public String search(@PathVariable long id,@AuthenticationPrincipal CustomUserDetails user,
            @Valid @ModelAttribute("editForm") OrderEditForm form,BindingResult binding,
            @RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="0") int page,Model model){
        var order=service.load(id);
        if(!binding.hasErrors()&&order.editable())try{model.addAttribute("productPage",service.search(keyword,page));}
        catch(IllegalArgumentException invalid){binding.reject("search",invalid.getMessage());}
        return edit(order,form,binding,keyword,page,model);
    }
    @PostMapping("/add")
    public String add(@PathVariable long id,@AuthenticationPrincipal CustomUserDetails user,
            @Valid @ModelAttribute("editForm") OrderEditForm form,BindingResult binding,
            @RequestParam long addProductId,@RequestParam(defaultValue="") String keyword,Model model){
        var order=service.load(id);
        if(!binding.hasErrors()&&order.editable())try{service.offer(addProductId);form.add(addProductId);}
        catch(IllegalArgumentException|ArithmeticException invalid){binding.reject("add","選択した商品は追加できません。数量・商品を確認してください。");}
        return edit(order,form,binding,keyword,0,model);
    }
    @PostMapping("/confirm")
    public String confirm(@PathVariable long id,@AuthenticationPrincipal CustomUserDetails user,
            @Valid @ModelAttribute("editForm") OrderEditForm form,BindingResult binding,
            @RequestParam(defaultValue="") String keyword,HttpSession session,Model model){
        var order=service.load(id);
        if(binding.hasErrors()||!order.editable())return edit(order,form,binding,keyword,0,model);
        try{
            var request=form.request(id);var calculated=service.preview(id,request);
            if(calculated.route()==OrderChangeRoutePolicy.Route.NO_CHANGE){binding.reject("unchanged","変更内容がありません。");return edit(order,form,binding,keyword,0,model);}
            UUID token=UUID.randomUUID();synchronized(session){var quotes=quotes(session);quotes.put(token,new Quote(user.getId(),request,calculated));while(quotes.size()>8)quotes.remove(quotes.keySet().iterator().next());}
            return "redirect:/orders/"+id+"/edit/confirm?preview="+token;
        }catch(IllegalArgumentException invalid){binding.reject("candidate",invalid.getMessage());return edit(order,form,binding,keyword,0,model);}
    }
    @GetMapping("/confirm")
    public String confirmation(@PathVariable long id,@AuthenticationPrincipal CustomUserDetails user,
            @RequestParam UUID preview,HttpSession session,Model model,RedirectAttributes flash){
        service.load(id);var q=quote(session,preview,id,user.getId());
        if(q==null){flash.addFlashAttribute("errorMessage","確認画面の有効性を確認できません。入力し直してください。");return "redirect:/orders/"+id+"/edit";}
        final OrderEditPreviewService.Preview fresh;
        try{fresh=service.preview(id,q.request());}
        catch(IllegalArgumentException changed){
            flash.addFlashAttribute("errorMessage","商品または注文内容が変わりました。入力内容を保持していますので、内容を確認してください。");
            return "redirect:/orders/"+id+"/edit?preview="+preview;
        }
        if(!fresh.equals(q.preview())){
            flash.addFlashAttribute("errorMessage","価格または注文内容が変わりました。内容を再確認してください。");
            return "redirect:/orders/"+id+"/edit?preview="+preview;
        }
        model.addAttribute("preview",fresh);model.addAttribute("previewToken",preview);model.addAttribute("submissionAvailable",fresh.candidate()!=null&&service.submissionAvailable());return "orders/edit-confirm";
    }
    @PostMapping("/submit")
    public String submit(@PathVariable long id,@AuthenticationPrincipal CustomUserDetails user,
            @RequestParam UUID preview,HttpSession session,RedirectAttributes flash){
        var saved=quote(session,preview,id,user.getId());
        if(saved==null||saved.preview().candidate()==null){flash.addFlashAttribute("errorMessage","確認内容を確認できません。入力し直してください。");return "redirect:/orders/"+id+"/edit";}
        var result=service.submit(id,preview,saved.request(),saved.preview().candidate());
        if(result.admissionId()==null&&(result.outcome()==OrderEditAdmissionService.Outcome.COMMIT_UNKNOWN||result.outcome()==OrderEditAdmissionService.Outcome.UNPROVEN))
            return "redirect:/orders/"+id+"/edit/result?preview="+preview;
        return submitted(id,result,flash);
    }
    @GetMapping("/result")
    public String result(@PathVariable long id,@RequestParam(required=false) UUID admission,@RequestParam(required=false) UUID preview,
            @AuthenticationPrincipal CustomUserDetails user,HttpSession session,Model model){
        if(admission!=null){model.addAttribute("result",service.result(id,admission));return "orders/edit-result";}
        if(preview==null||quote(session,preview,id,user.getId())==null)throw new IllegalArgumentException("保存済みの確認内容が必要です。");
        var found=service.findAdmission(id,preview);
        if(found!=null)return "redirect:/orders/"+id+"/edit/result?admission="+found;
        model.addAttribute("result",new OrderEditBrowserService.BrowserResult(null,"確認待ち",false));
        model.addAttribute("pendingPreview",preview);model.addAttribute("pendingRetryAvailable",service.submissionAvailable());return "orders/edit-result";
    }
    @PostMapping("/continue")
    public String continueAdmission(@PathVariable long id,@RequestParam UUID admission,RedirectAttributes flash){
        var result=service.continueAdmission(id,admission);
        if(result.admissionId()==null){flash.addFlashAttribute("errorMessage","受付結果を確認できません。保存済みの処理結果を再確認してください。");return "redirect:/orders/"+id+"/edit/result?admission="+admission;}
        return submitted(id,result,flash);
    }
    private String submitted(long order,OrderEditAdmissionService.Result result,RedirectAttributes flash){
        if(result.admissionId()!=null)return "redirect:/orders/"+order+"/edit/result?admission="+result.admissionId();
        String message=switch(result.outcome()){
            case RUNTIME_UNAVAILABLE -> "現在、注文変更の確定を利用できません。注文内容は変更していません。";
            case REJECTED_LATE -> "注文変更期限を過ぎたため、受付できませんでした。";
            case COMMIT_UNKNOWN,UNPROVEN -> "受付結果を確認できません。同じ確認内容で再送するか、注文詳細をご確認ください。";
            default -> "注文状態または確認内容が変わったため、受付できません。内容をご確認ください。";
        };
        flash.addFlashAttribute("errorMessage",message);return "redirect:/orders/"+order+"/edit";
    }
    private String edit(OrderEditPreviewService.PageContext page,OrderEditForm form,BindingResult binding,String keyword,int number,Model model){
        model.addAttribute("page",page);model.addAttribute("editForm",form);model.addAttribute("keyword",keyword==null?"":keyword);model.addAttribute("searchPage",number);
        var saved=new HashMap<Long,OrderContentSnapshot.Item>();page.before().items().forEach(i->saved.put(i.orderItemId(),i));
        var rows=new ArrayList<Row>();
        if(form.getLines()!=null)for(var line:form.getLines()){
            var old=line==null||line.getExistingItemId()==null?null:saved.get(line.getExistingItemId());
            if(old!=null){rows.add(new Row(old.productName(),old.unitPrice(),true,old.quantity()));continue;}
            if(line!=null&&line.getExistingItemId()==null&&line.getProductId()!=null&&line.getProductId()>0)try{
                var offer=service.offer(line.getProductId());rows.add(new Row(offer.productName(),offer.unitPrice(),false,0));continue;
            }catch(IllegalArgumentException unavailable){/* retain input; confirmation validates it again */}
            rows.add(new Row("商品・明細を確認してください",null,false,0));
        }
        model.addAttribute("rows",rows);return "orders/edit";
    }
    private Quote quote(HttpSession session,UUID token,long order,long customer){
        synchronized(session){var q=quotes(session).get(token);if(q!=null&&(q.customer()!=customer||q.request().orderId()!=order))throw new OrderNotFoundException(order);return q;}
    }
    @SuppressWarnings("unchecked") private Map<UUID,Quote> quotes(HttpSession session){
        var value=session.getAttribute(QUOTES);if(value==null){value=new LinkedHashMap<UUID,Quote>();session.setAttribute(QUOTES,value);}return (Map<UUID,Quote>)value;
    }
    @ExceptionHandler(InvalidOrderStatusException.class) @ResponseStatus(org.springframework.http.HttpStatus.CONFLICT)
    public String unavailable(InvalidOrderStatusException error,Model model){model.addAttribute("errorMessage",error.getMessage());return "orders/edit";}
    @ExceptionHandler({IllegalArgumentException.class,org.springframework.beans.InvalidPropertyException.class})
    @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    public String invalid(Model model){model.addAttribute("errorMessage","入力内容を確認してください。");return "orders/edit";}
}
