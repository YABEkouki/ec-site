package com.example.ecsite.service.order.evidence;

import java.time.LocalDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.Page;
import com.example.ecsite.entity.Product;
import com.example.ecsite.service.ProductService;
import com.example.ecsite.form.ProductSearchForm;
import com.example.ecsite.exception.*;
import static com.example.ecsite.service.order.evidence.OrderEditCandidate.*;

/** Display-only adapter. No locks, admission/enrollment, inventory writes or provider dependencies. */
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class OrderEditPreviewService {
    public record PageContext(Context context,String unavailableReason){
        public OrderContentSnapshot before(){return context.facts().current();}
        public LocalDateTime deadline(){return context.deadline();}
        public boolean editable(){return unavailableReason==null;}
        /** Button availability only; the enrollment service makes the authoritative locked decision. */
        public boolean legacyEnrollmentAvailable(){return !context.facts().evidenceRequired()&&stateReason(context)==null;}
    }
    public record Preview(OrderContentSnapshot before,OrderContentSnapshot after,LocalDateTime deadline,OrderChangeRoutePolicy.Route route){}
    private final JdbcTemplate jdbc;
    private final ProductService products;
    private final JdbcOrderPaymentEvidenceReader reader;
    private final OrderEditCandidateCalculator calculator=new OrderEditCandidateCalculator();
    @Autowired public OrderEditPreviewService(JdbcTemplate jdbc,ProductService products){this(jdbc,products,new JdbcOrderPaymentEvidenceReader(jdbc));}
    OrderEditPreviewService(JdbcTemplate jdbc,ProductService products,JdbcOrderPaymentEvidenceReader reader){this.jdbc=jdbc;this.products=products;this.reader=reader;}
    public PageContext load(long order,long customer){
        var owners=jdbc.queryForList("select user_id from orders where id=?",Long.class,order);
        if(customer<=0||owners.size()!=1||owners.getFirst()!=customer)throw new OrderNotFoundException(order);
        var read=reader.read(order);
        if(read.state()!=JdbcOrderPaymentEvidenceReader.State.READ||read.facts()==null)throw new InvalidOrderStatusException("注文情報を確認できないため、編集できません。");
        var now=jdbc.queryForObject("select timezone('Asia/Tokyo',clock_timestamp())",LocalDateTime.class);
        var context=OrderEditCandidateSources.context(read,customer,now);
        if(context.facts().current().customerId()!=customer)throw new OrderNotFoundException(order);
        String reason=stateReason(context);
        if(reason==null&&(read.decision()==null||!read.decision().locallyVerified()))reason="注文・決済情報の確認が必要なため、一括編集は利用できません。";
        if(reason==null){
            var admissions=jdbc.queryForList("select admission_state,execution_state,closed_at,applied_operation_id from order_edit_admissions where order_id=? order by id",order);
            if(admissions.stream().anyMatch(r->OrderEditAdmissionGuard.blocks((String)r.get("admission_state"),(String)r.get("execution_state"),r.get("closed_at")!=null,r.get("applied_operation_id")!=null)))
                reason="受付済みの注文変更を確認中です。処理結果を確認してください。";
        }
        return new PageContext(context,reason);
    }
    static String stateReason(Context context){
        if(!"ORDERED".equals(context.status()))return "現在の注文状態では編集できません。";
        if(context.preparationStartedAt()!=null||context.preparationAccountId()!=null||context.preparationUsername()!=null)return "発送準備が始まっているため、編集できません。";
        if(!context.now().isBefore(context.deadline()))return "注文変更期限を過ぎています。";
        return null;
    }
    public Preview preview(long order,long customer,Request request){
        var page=load(order,customer);if(!page.editable())throw new InvalidOrderStatusException(page.unavailableReason());
        if(request==null||request.orderId()!=order||request.lines().isEmpty()||request.lines().size()>1000)throw new IllegalArgumentException("編集内容を確認してください。");
        var saved=new HashMap<Long,OrderContentSnapshot.Item>();page.before().items().forEach(i->saved.put(i.orderItemId(),i));
        var required=new TreeSet<Long>();
        for(var l:request.lines()){
            var old=l.existingItemId()==null?null:saved.get(l.existingItemId());
            if(l.existingItemId()!=null&&old==null)throw new IllegalArgumentException("注文に含まれない明細です。");
            if(l.quantity()>0&&(old==null||old.productId()!=l.productId()||l.quantity()>old.quantity()))required.add(l.productId());
        }
        var offers=required.stream().map(this::offer).toList();
        var result=calculator.calculate(page.context(),request,offers);
        if(result.route()==OrderChangeRoutePolicy.Route.BLOCKED){
            if(request.lines().stream().allMatch(l->l.quantity()==0))throw new IllegalArgumentException("商品をすべて削除する場合は、注文キャンセルをご利用ください。");
            throw new IllegalArgumentException("数量・商品・注文の版を確認してください。価格変更時は再確認が必要です。");
        }
        return new Preview(page.before(),result.candidate().previewSnapshot(),page.deadline(),result.route());
    }
    public Offer offer(long id){
        try{
            var p=products.findById(id);var o=OrderEditCandidateSources.offer(p);
            if(!o.active()||!p.getCategory().isActive()||!p.getTaxCategory().isActive())throw new IllegalArgumentException();
            return o;
        }catch(ProductNotFoundException|IllegalArgumentException invalid){throw new IllegalArgumentException("選択した商品は追加できません。商品を検索し直してください。");}
    }
    public Page<Product> search(String keyword,int page){
        if(page<0||page>10000||keyword!=null&&keyword.length()>100)throw new IllegalArgumentException("検索条件を確認してください。");
        var form=new ProductSearchForm();form.setKeyword(keyword);return products.searchForUser(form,page,10);
    }
}
