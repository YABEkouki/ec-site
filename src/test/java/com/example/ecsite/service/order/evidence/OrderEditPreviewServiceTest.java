package com.example.ecsite.service.order.evidence;

import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import com.example.ecsite.entity.*;
import com.example.ecsite.service.ProductService;
import com.example.ecsite.exception.*;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
import static com.example.ecsite.service.order.evidence.OrderPaymentEvidenceValidatorTest.*;
import static com.example.ecsite.service.order.evidence.OrderEditCandidate.*;

class OrderEditPreviewServiceTest {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    ProductService products=mock(ProductService.class);
    JdbcOrderPaymentEvidenceReader reader=mock(JdbcOrderPaymentEvidenceReader.class);
    OrderEditPreviewService service=new OrderEditPreviewService(jdbc,products,reader);
    LocalDateTime now=LocalDateTime.of(2026,11,4,13,0);
    OrderPaymentEvidence.Facts facts;
    @BeforeEach void setup(){
        facts=facts(initial(),List.of());
        when(jdbc.queryForList("select user_id from orders where id=?",Long.class,1L)).thenReturn(List.of(2L));
        when(jdbc.queryForObject("select timezone('Asia/Tokyo',clock_timestamp())",LocalDateTime.class)).thenReturn(now);
        when(jdbc.queryForList("select admission_state,execution_state,closed_at,applied_operation_id from order_edit_admissions where order_id=? order by id",1L)).thenReturn(List.of());
        read("ORDERED",now.plusHours(1),false);
    }
    void read(String status,LocalDateTime deadline,boolean preparing){
        var json=JsonMapper.builder().build().createObjectNode();
        json.put("id",1);json.put("user_id",2);json.put("content_revision",0);json.put("status",status);
        json.put("ordered_at",now.minusDays(1).toString());json.put("change_deadline_at",deadline.toString());
        if(preparing)json.put("shipping_preparation_started_at",now.toString());else json.putNull("shipping_preparation_started_at");
        json.putNull("shipping_preparation_started_by_account_id");json.putNull("shipping_preparation_started_by_username");
        when(reader.read(1)).thenReturn(new JdbcOrderPaymentEvidenceReader.Read(JdbcOrderPaymentEvidenceReader.State.READ,facts,
            new OrderPaymentEvidenceValidator().validate(facts),null,null,json,null));
    }
    Product product(long id,int price,int rate){
        var p=mock(Product.class);var c=mock(Category.class);var t=mock(TaxCategory.class);
        when(p.getId()).thenReturn(id);when(p.getName()).thenReturn("新商品"+id);when(p.getPrice()).thenReturn(price);when(p.isActive()).thenReturn(true);
        when(p.getCategory()).thenReturn(c);when(c.getId()).thenReturn(4L);when(c.getName()).thenReturn("分類");when(c.isActive()).thenReturn(true);
        when(p.getTaxCategory()).thenReturn(t);when(t.getId()).thenReturn(rate==8?6L:5L);when(t.getCode()).thenReturn(rate==8?"REDUCED":"STANDARD");
        when(t.getName()).thenReturn("税");when(t.getTaxRate()).thenReturn(BigDecimal.valueOf(rate));when(t.isActive()).thenReturn(true);
        when(products.findById(id)).thenReturn(p);return p;
    }
    Request request(Line... lines){return new Request(1,0,List.of(lines));}
    @Test void ownOrderLoadsWithoutAnyWriteOrProviderDependency(){
        assertThat(service.load(1,2).editable()).isTrue();verify(reader).read(1);
        assertThat(Arrays.stream(OrderEditPreviewService.class.getConstructors()).flatMap(c->Arrays.stream(c.getParameterTypes())).noneMatch(t->t.getSimpleName().contains("Gateway")||t.getSimpleName().contains("AdmissionService"))).isTrue();
        assertThat(OrderEditPreviewService.class.getAnnotation(org.springframework.transaction.annotation.Transactional.class).readOnly()).isTrue();
    }
    @Test void otherCustomerIsRejectedBeforeReadingEvidence(){
        assertThatThrownBy(()->service.load(1,99)).isInstanceOf(OrderNotFoundException.class);verifyNoInteractions(reader);
    }
    @ParameterizedTest @ValueSource(strings={"deadline","preparing","cancelled","legacy","pending","admission"})
    void unavailableOrdersAreExplicitlyBlocked(String reason){
        if(reason.equals("legacy"))facts=copy(facts,facts.current(),10L,facts.payments(),facts.authorizations(),null,List.of(),null,false,false);
        if(reason.equals("pending"))facts=copy(facts,facts.current(),10L,facts.payments(),facts.authorizations(),facts.epoch(),List.of(),null,false,true);
        if(reason.equals("admission"))when(jdbc.queryForList("select admission_state,execution_state,closed_at,applied_operation_id from order_edit_admissions where order_id=? order by id",1L)).thenReturn(List.of(new HashMap<>(Map.of("admission_state","RECORDED_UNVERIFIED","execution_state","NOT_READY"))));
        read(reason.equals("cancelled")?"CANCELLED":"ORDERED",reason.equals("deadline")?now:now.plusHours(1),reason.equals("preparing"));
        assertThat(service.load(1,2).editable()).isFalse();
        assertThatThrownBy(()->service.preview(1,2,request(new Line(3L,7,1)))).isInstanceOf(InvalidOrderStatusException.class);
    }
    @ParameterizedTest @ValueSource(ints={4999,5000})
    void replacementAndShippingBoundaryUseServerPrices(int price){
        product(8,price,10);var p=service.preview(1,2,request(new Line(3L,7,0),new Line(null,8,1)));
        assertThat(p.after().itemSubtotal()).isEqualTo(price);assertThat(p.after().chargeTotal()).isEqualTo(price==4999?550:0);
        assertThat(p.after().taxAmount()).isEqualTo(price==4999?504:454);assertThat(p.before().totalAmount()).isEqualTo(6000);
    }
    @Test void mixedTaxesAndMultipleAdditions(){
        product(8,1080,8);product(9,1100,10);
        var p=service.preview(1,2,request(new Line(null,8,1),new Line(null,9,1)));
        assertThat(p.after().totalAmount()).isEqualTo(2730);assertThat(p.after().taxAmount()).isEqualTo(230);
    }
    @Test void existingSnapshotIsNotRepricedForAnIncrease(){
        product(7,9999,8);var p=service.preview(1,2,request(new Line(3L,7,2)));
        assertThat(p.after().items().getFirst().unitPrice()).isEqualTo(6000);assertThat(p.after().items().getFirst().taxRateBasisPoints()).isEqualTo(1000);
        assertThat(p.route()).isEqualTo(OrderChangeRoutePolicy.Route.REAUTHORIZE);
    }
    @Test void noOpIsIdentifiedWithoutProductMasterReads(){
        assertThat(service.preview(1,2,request(new Line(3L,7,1))).route()).isEqualTo(OrderChangeRoutePolicy.Route.NO_CHANGE);verifyNoInteractions(products);
    }
    @ParameterizedTest @ValueSource(strings={"empty","negative","duplicate","otherItem","missingProduct","oldRevision"})
    void invalidCandidatesAreRejected(String kind){
        Request r=switch(kind){case "empty"->request(new Line(3L,7,0));case "negative"->request(new Line(3L,7,-1));
            case "duplicate"->request(new Line(3L,7,1),new Line(3L,7,1));case "otherItem"->request(new Line(999L,7,1));
            case "missingProduct"->request(new Line(null,999,1));default->new Request(1,9,List.of(new Line(3L,7,1)));};
        when(products.findById(999L)).thenThrow(new ProductNotFoundException(999L));
        assertThatThrownBy(()->service.preview(1,2,r)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void sameProductRowsKeepDifferentSavedSnapshotsAndNewOffer() {
        var base=facts.current();
        var start=new OrderContentSnapshot(1,1,2,0,List.of(
            new OrderContentSnapshot.Item(3,7,"旧条件A",4,"保存分類",2000,1,2000,5,"STANDARD","標準",1000),
            new OrderContentSnapshot.Item(4,7,"旧条件B",4,"保存分類",4000,1,4000,5,"STANDARD","標準",1000)),base.charges(),6000,0,545,6000);
        var e=initial();facts=facts(signed(new OrderPaymentEvidence.Epoch(1,e.id(),1,10,20,6000,0,e.origin(),start,null,null,ZERO)),List.of());
        read("ORDERED",now.plusHours(1),false);product(7,500,8);
        var p=service.preview(1,2,request(new Line(3L,7,1),new Line(4L,7,1),new Line(null,7,1)));
        assertThat(p.after().items()).hasSize(3);
        assertThat(p.after().items().stream().map(OrderContentSnapshot.Item::unitPrice)).containsExactlyInAnyOrder(2000,4000,500);
        assertThat(p.after().taxAmount()).isEqualTo(582);
        assertThat(com.example.ecsite.form.OrderEditForm.from(start).getLines()).hasSize(2);
    }
    @Test void nonexistentOrInactiveOfferAndOverflowNeverProduceAPreview(){
        var p=product(8,Integer.MAX_VALUE,10);
        assertThatThrownBy(()->service.preview(1,2,request(new Line(null,8,2)))).isInstanceOf(IllegalArgumentException.class);
        when(p.isActive()).thenReturn(false);
        assertThatThrownBy(()->service.preview(1,2,request(new Line(null,8,1)))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void searchUsesExistingProductSearchAndRejectsUnboundedParameters(){
        when(products.searchForUser(any(),eq(0),eq(10))).thenReturn(org.springframework.data.domain.Page.empty());
        assertThat(service.search("camp",0).isEmpty()).isTrue();
        var capture=org.mockito.ArgumentCaptor.forClass(com.example.ecsite.form.ProductSearchForm.class);
        verify(products).searchForUser(capture.capture(),eq(0),eq(10));assertThat(capture.getValue().getKeyword()).isEqualTo("camp");
        assertThatThrownBy(()->service.search("x".repeat(101),0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.search("camp",-1)).isInstanceOf(IllegalArgumentException.class);
    }

}
