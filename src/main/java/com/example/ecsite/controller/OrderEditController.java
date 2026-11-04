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

/** Preview only. Neither these routes nor session quotes authorize an order change. */
@Controller
@RequestMapping("/orders/{id}/edit")
public class OrderEditController {
    private static final String QUOTES=OrderEditController.class.getName()+".previews";
    private record Quote(long customer,OrderEditCandidate.Request request,OrderEditPreviewService.Preview preview){}
    public record Row(String productName,Integer unitPrice,boolean existing,int beforeQuantity){}
    private final OrderEditPreviewService service;
    public OrderEditController(OrderEditPreviewService service){this.service=service;}
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
        var page=service.load(id,user.getId());OrderEditForm form=OrderEditForm.from(page.before());
        if(preview!=null){var q=quote(session,preview,id,user.getId());if(q!=null)form=OrderEditForm.from(q.request());
            else model.addAttribute("errorMessage","確認画面の有効性を確認できません。入力し直してください。");}
        return edit(page,form,null,null,0,model);
    }
    @PostMapping("/search")
    public String search(@PathVariable long id,@AuthenticationPrincipal CustomUserDetails user,
            @Valid @ModelAttribute("editForm") OrderEditForm form,BindingResult binding,
            @RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="0") int page,Model model){
        var order=service.load(id,user.getId());
        if(!binding.hasErrors()&&order.editable())try{model.addAttribute("productPage",service.search(keyword,page));}
        catch(IllegalArgumentException invalid){binding.reject("search",invalid.getMessage());}
        return edit(order,form,binding,keyword,page,model);
    }
    @PostMapping("/add")
    public String add(@PathVariable long id,@AuthenticationPrincipal CustomUserDetails user,
            @Valid @ModelAttribute("editForm") OrderEditForm form,BindingResult binding,
            @RequestParam long addProductId,@RequestParam(defaultValue="") String keyword,Model model){
        var order=service.load(id,user.getId());
        if(!binding.hasErrors()&&order.editable())try{service.offer(addProductId);form.add(addProductId);}
        catch(IllegalArgumentException|ArithmeticException invalid){binding.reject("add","選択した商品は追加できません。数量・商品を確認してください。");}
        return edit(order,form,binding,keyword,0,model);
    }
    @PostMapping("/confirm")
    public String confirm(@PathVariable long id,@AuthenticationPrincipal CustomUserDetails user,
            @Valid @ModelAttribute("editForm") OrderEditForm form,BindingResult binding,
            @RequestParam(defaultValue="") String keyword,HttpSession session,Model model){
        var order=service.load(id,user.getId());
        if(binding.hasErrors()||!order.editable())return edit(order,form,binding,keyword,0,model);
        try{
            var request=form.request(id);var calculated=service.preview(id,user.getId(),request);
            if(calculated.route()==OrderChangeRoutePolicy.Route.NO_CHANGE){binding.reject("unchanged","変更内容がありません。");return edit(order,form,binding,keyword,0,model);}
            UUID token=UUID.randomUUID();synchronized(session){var quotes=quotes(session);quotes.put(token,new Quote(user.getId(),request,calculated));while(quotes.size()>8)quotes.remove(quotes.keySet().iterator().next());}
            return "redirect:/orders/"+id+"/edit/confirm?preview="+token;
        }catch(IllegalArgumentException invalid){binding.reject("candidate",invalid.getMessage());return edit(order,form,binding,keyword,0,model);}
    }
    @GetMapping("/confirm")
    public String confirmation(@PathVariable long id,@AuthenticationPrincipal CustomUserDetails user,
            @RequestParam UUID preview,HttpSession session,Model model,RedirectAttributes flash){
        service.load(id,user.getId());var q=quote(session,preview,id,user.getId());
        if(q==null){flash.addFlashAttribute("errorMessage","確認画面の有効性を確認できません。入力し直してください。");return "redirect:/orders/"+id+"/edit";}
        final OrderEditPreviewService.Preview fresh;
        try{fresh=service.preview(id,user.getId(),q.request());}
        catch(IllegalArgumentException changed){
            flash.addFlashAttribute("errorMessage","商品または注文内容が変わりました。入力内容を保持していますので、内容を確認してください。");
            return "redirect:/orders/"+id+"/edit?preview="+preview;
        }
        if(!fresh.equals(q.preview())){
            flash.addFlashAttribute("errorMessage","価格または注文内容が変わりました。内容を再確認してください。");
            return "redirect:/orders/"+id+"/edit?preview="+preview;
        }
        model.addAttribute("preview",fresh);model.addAttribute("previewToken",preview);return "orders/edit-confirm";
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
