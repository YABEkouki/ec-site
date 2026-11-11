package com.example.ecsite.form;

import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.example.ecsite.service.order.evidence.OrderEditCandidate;
import com.example.ecsite.service.order.evidence.OrderContentSnapshot;

/** IDs and quantities only; prices and tax snapshots are never bound from the browser. */
public class OrderEditForm {
    @NotNull @Min(0) private Integer revision;
    @NotEmpty @Size(max=1000) @Valid private List<Line> lines=new ArrayList<>();
    public static class Line {
        @Positive private Long existingItemId;
        @NotNull @Positive private Long productId;
        @NotNull @Min(0) private Integer quantity;
        public Long getExistingItemId(){return existingItemId;} public void setExistingItemId(Long v){existingItemId=v;}
        public Long getProductId(){return productId;} public void setProductId(Long v){productId=v;}
        public Integer getQuantity(){return quantity;} public void setQuantity(Integer v){quantity=v;}
    }
    public Integer getRevision(){return revision;} public void setRevision(Integer v){revision=v;}
    public List<Line> getLines(){return lines;} public void setLines(List<Line> v){lines=v;}
    public OrderEditCandidate.Request request(long order){
        if(revision==null||revision<0||lines==null||lines.isEmpty()||lines.size()>1000)throw new IllegalArgumentException("編集内容を確認してください。");
        return new OrderEditCandidate.Request(order,revision,lines.stream().map(l->{
            if(l==null||l.productId==null||l.productId<=0||l.quantity==null||l.quantity<0||l.existingItemId!=null&&l.existingItemId<=0)throw new IllegalArgumentException("数量・商品を確認してください。");
            return new OrderEditCandidate.Line(l.existingItemId,l.productId,l.quantity);
        }).toList());
    }
    public static OrderEditForm from(OrderEditCandidate.Request request){
        var form=new OrderEditForm();form.revision=request.expectedRevision();
        for(var r:request.lines()){var l=new Line();l.existingItemId=r.existingItemId();l.productId=r.productId();l.quantity=r.quantity();form.lines.add(l);}return form;
    }
    public static OrderEditForm from(OrderContentSnapshot snapshot){
        return from(new OrderEditCandidate.Request(snapshot.orderId(),snapshot.contentRevision(),snapshot.items().stream().map(i->new OrderEditCandidate.Line(i.orderItemId(),i.productId(),i.quantity())).toList()));
    }
    public void add(long product){
        if(lines==null||lines.size()>=1000)throw new IllegalArgumentException("一度に編集できる明細数を超えています。");
        for(var l:lines)if(l.existingItemId==null&&Objects.equals(l.productId,product)){l.quantity=Math.addExact(l.quantity,1);return;}
        var l=new Line();l.productId=product;l.quantity=1;lines.add(l);
    }
}
