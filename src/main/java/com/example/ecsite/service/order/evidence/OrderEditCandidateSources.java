package com.example.ecsite.service.order.evidence;

import java.time.LocalDateTime;
import java.util.Objects;
import com.example.ecsite.entity.Product;
import static com.example.ecsite.service.order.evidence.OrderEditCandidate.*;
import static com.example.ecsite.service.order.evidence.OrderContentSnapshotFactory.*;

/** Read adapters only. No bean or repository. Caller must supply one complete consistent 2C read
 * and load product/category/tax data in its read transaction before copying the current offering.
 * There is deliberately no controller or production service calling these adapters in 2D. */
public final class OrderEditCandidateSources {
    private OrderEditCandidateSources() {}
    public static Context context(JdbcOrderPaymentEvidenceReader.Read read,long customerId,LocalDateTime now) {
        try {
            if(read==null||read.state()!=JdbcOrderPaymentEvidenceReader.State.READ||read.facts()==null)throw new IllegalArgumentException();
            var row=Objects.requireNonNull(read.order());var s=read.facts().current();
            if(l(row,"id")!=s.orderId()||l(row,"user_id")!=s.customerId()||i(row,"content_revision")!=s.contentRevision())throw new IllegalArgumentException();
            return new Context(read.facts(),customerId,t(row,"status"),LocalDateTime.parse(t(row,"ordered_at")),
                LocalDateTime.parse(t(row,"change_deadline_at")),time(nullableText(row,"shipping_preparation_started_at")),
                nullableLong(row,"shipping_preparation_started_by_account_id"),nullableText(row,"shipping_preparation_started_by_username"),now);
        } catch(RuntimeException invalid) { throw new IllegalArgumentException("Incomplete or inconsistent candidate source"); }
    }
    public static Offer offer(Product product) {
        try {
            var p=Objects.requireNonNull(product);var category=Objects.requireNonNull(p.getCategory());var tax=Objects.requireNonNull(p.getTaxCategory());
            long id=p.getId(),categoryId=category.getId(),taxId=tax.getId();int price=p.getPrice();
            if(id<=0||categoryId<=0||taxId<=0||price<0)throw new IllegalArgumentException();
            return new Offer(id,Objects.requireNonNull(p.getName()),categoryId,Objects.requireNonNull(category.getName()),price,
                taxId,Objects.requireNonNull(tax.getCode()),Objects.requireNonNull(tax.getName()),
                OrderContentSnapshotCodec.taxRateBasisPoints(tax.getTaxRate()),p.isActive());
        } catch(RuntimeException invalid) {throw new IllegalArgumentException("Incomplete current product offering");}
    }
    private static LocalDateTime time(String value) { return value==null?null:LocalDateTime.parse(value); }
}
