package com.example.ecsite.payment.safety;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.example.ecsite.service.order.evidence.*;
import static com.example.ecsite.service.order.evidence.OrderEditCandidate.*;
import static com.example.ecsite.service.order.evidence.OrderEditApplicationPlan.*;
import static com.example.ecsite.service.order.evidence.OrderEditExecution.*;
import static org.assertj.core.api.Assertions.*;

/** Existing isolated PG17 fixture only. No provider or order/stock application. */
class OrderEditApplicationPlanPostgresTest {
    static PaymentSafetyExpandPostgresTest db;
    static String legacyBefore,oldClosureDocument;
    static UUID oldGeneration;
    static OrderUnappliedClosure.Command oldClosure;
    @BeforeAll static void database(){
        OrderEditExecutionCoordinatorPostgresTest.database();db=OrderEditExecutionCoordinatorPostgresTest.db;
        var f=OrderPaymentEvidenceFoundationPostgresTest.fixture();OrderPaymentEvidenceFoundationPostgresTest.legacyOperation(f);
        legacyBefore=legacy();
        oldGeneration=UUID.randomUUID();db.jdbc.update("insert into order_edit_runtime_generations(id,identity) values (?,f125_admission_identity())",oldGeneration);
        db.jdbc.update("update order_edit_runtime_control set state='ISOLATED_TEST',generation_id=?,version=version+1 where id=1",oldGeneration);
        var helper=new OrderEditExecutionCoordinatorPostgresTest();UUID id=helper.receipt();var claim=helper.command(id,0);
        helper.service().claim(claim);helper.service().resolve(helper.resolution(claim,Disposition.REQUEUE));oldClosure=helper.closure(id,2);
        assertThat(helper.service().closeUnapplied(oldClosure).outcome()).isEqualTo(Outcome.RECORDED);
        oldClosureDocument=db.jdbc.queryForObject("select evidence_document::text from order_edit_unapplied_evidences where admission_id=?",String.class,id);
        db.jdbc.update("update order_edit_runtime_control set state='STOPPED',generation_id=null,version=version+1 where id=1");
        db.migrate("73");assertThat(db.jdbc.queryForObject("select state from order_edit_runtime_control where id=1",String.class)).isEqualTo("STOPPED");
    }
    @AfterAll static void cleanup(){OrderEditExecutionCoordinatorPostgresTest.cleanup();}
    @BeforeEach void runtime(TestInfo info){new OrderEditAdmissionFoundationPostgresTest().runtime(info);}
    static String legacy(){return db.jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(c) order by id),'[]')::text from order_change_operations c",String.class);}
    OrderEditApplicationPlanRepository repository(){return new OrderEditApplicationPlanRepository(db.jdbc);}
    <T>T tx(java.util.function.Supplier<T> work){return OrderEditAdmissionServicePostgresTest.tx(work);}
    Plan prepare(UUID id){return tx(()->repository().prepare(id));}
    long product(OrderEditAdmissionServicePostgresTest.Fixture f,int price){return db.jdbc.queryForObject("insert into products(name,price,stock,category_id,tax_category_id) select 'added product',?,100,category_id,tax_category_id from products where id=? returning id",Long.class,price,f.product());}
    UUID accepted(OrderEditAdmissionServicePostgresTest.Fixture f,List<OrderEditCandidate.Line> lines){
        var command=tx(()->{
            var read=new JdbcOrderPaymentEvidenceReader(db.jdbc).readLocked(f.order());
            var now=db.jdbc.queryForObject("select timezone('Asia/Tokyo',clock_timestamp())",java.time.LocalDateTime.class);
            var request=new Request(f.order(),0,lines);
            var offers=db.jdbc.query("select p.id,p.name,c.id,c.name,p.price,t.id,t.code,t.name,t.tax_rate,(p.active and c.active and t.active) from products p join categories c on c.id=p.category_id join tax_categories t on t.id=p.tax_category_id order by p.id",
                (r,n)->new Offer(r.getLong(1),r.getString(2),r.getLong(3),r.getString(4),r.getInt(5),r.getLong(6),r.getString(7),r.getString(8),OrderContentSnapshotCodec.taxRateBasisPoints(r.getBigDecimal(9)),r.getBoolean(10)));
            var result=new OrderEditCandidateCalculator().calculateForAdmission(OrderEditCandidateSources.context(read,f.customer(),now),request,offers);
            assertThat(result.route()).isEqualTo(OrderChangeRoutePolicy.Route.RETAIN_AUTH);
            return new OrderEditAdmissionService.Command(f.customer(),UUID.randomUUID(),request,result.candidate());
        });
        var r=OrderEditAdmissionServicePostgresTest.service().submit(command);
        assertThat(r.outcome()).isEqualTo(OrderEditAdmissionService.Outcome.ACCEPTED);return r.admissionId();
    }
    @Test void migrationKeepsLegacyRowsAndRuntimeStoppedByDefault(){
        // BeforeEach activates only the explicit isolated runtime. Initial STOPPED is checked before activation elsewhere.
        org.flywaydb.core.Flyway.configure().dataSource(db.source).defaultSchema(db.schema).target("73").load().validate();
        assertThat(legacy()).isEqualTo(legacyBefore);
        db.jdbc.update("update order_edit_runtime_control set state='STOPPED',generation_id=null,version=version+1 where id=1");
        assertThat(db.jdbc.queryForObject("select state from order_edit_runtime_control where id=1",String.class)).isEqualTo("STOPPED");
        assertThat(db.jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=current_schema() and table_name='order_edit_execution_attempt_results' and column_name like 'application_%'",Integer.class)).isEqualTo(6);
    }
    @Test void oldV72ClosureProofRemainsReadableAndReplayable(){
        db.jdbc.update("update order_edit_runtime_control set generation_id=?,version=version+1 where id=1",oldGeneration);
        var service=new OrderEditExecutionCoordinator(db.source);
        assertThat(service.recoverClosure(oldClosure).outcome()).isEqualTo(Outcome.OBSERVED);
        assertThat(service.closeUnapplied(oldClosure).outcome()).isEqualTo(Outcome.RECORDED);
        assertThat(db.jdbc.queryForObject("select evidence_document::text from order_edit_unapplied_evidences where admission_id=?",String.class,oldClosure.admissionId())).isEqualTo(oldClosureDocument);
    }
    OrderEditAdmissionServicePostgresTest.Fixture twoLines(){
        var l=new LegacyOrderEvidenceEnrollmentPostgresTest().fixture();
        db.jdbc.update("update orders set ordered_at=date_trunc('day',timezone('Asia/Tokyo',clock_timestamp()))+interval '2 days 10 hours',change_deadline_at=date_trunc('day',timezone('Asia/Tokyo',clock_timestamp()))+interval '2 days 14 hours' where id=?",l.order());
        db.jdbc.update("update order_items set price=3000,quantity=1,subtotal=3000 where order_id=?",l.order());
        long first=db.jdbc.queryForObject("select id from order_items where order_id=?",Long.class,l.order());
        long original=db.jdbc.queryForObject("select product_id from order_items where id=?",Long.class,first);
        long next=db.jdbc.queryForObject("insert into products(name,price,stock,category_id,tax_category_id) select 'second',3000,100,category_id,tax_category_id from products where id=? returning id",Long.class,original);
        db.jdbc.update("insert into order_items(order_id,product_id,product_name,category_id,category_name,price,quantity,subtotal,tax_category_id,tax_category_code,tax_category_name,tax_rate) select order_id,?,'second saved',category_id,category_name,3000,1,3000,tax_category_id,tax_category_code,tax_category_name,tax_rate from order_items where id=?",next,first);
        UUID epoch=UUID.randomUUID();
        tx(()->{
            var current=new JdbcOrderPaymentEvidenceReader(db.jdbc).readLocked(l.order()).facts().current();
            byte[] body=new OrderContentSnapshotCodec().encode(current);
            var e=new OrderPaymentEvidence.Epoch(1,epoch,l.order(),l.payment(),l.auth(),6000,0,OrderPaymentEvidence.Origin.INITIAL_AUTH,current,null,null,null);
            byte[] digest=HexFormat.of().parseHex(OrderEvidenceDigests.epoch(e));
            db.jdbc.update("""
              insert into order_payment_evidence_epochs(id,order_id,payment_id,authorization_transaction_id,origin_kind,
                start_content_revision,start_total_amount,authorized_amount,authorization_revision,provider_flow_id,
                start_snapshot,start_fingerprint,observation_payload,observation_fingerprint,observation_started_at,
                observation_finished_at,evidence_version,evidence_digest)
              select ?,?,?,?,'INITIAL_AUTH',0,6000,6000,0,p.provider_payment_id,?,sha256(?),decode('7b7d','hex'),sha256(decode('7b7d','hex')),
                clock_timestamp(),clock_timestamp(),1,? from payments p where id=?
              """,epoch,l.order(),l.payment(),l.auth(),body,body,digest,l.payment());
            db.jdbc.update("update orders set current_evidence_epoch_id=? where id=?",epoch,l.order());return null;
        });
        long customer=db.jdbc.queryForObject("select user_id from orders where id=?",Long.class,l.order());
        return new OrderEditAdmissionServicePostgresTest.Fixture(l.order(),customer,l.payment(),l.auth(),first,original,epoch);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void existingDeletionAndMixedQuantityIncreaseUseProductAggregates(boolean increase){
        var f=twoLines();
        var plan=prepare(accepted(f,List.of(new OrderEditCandidate.Line(f.item(),f.product(),increase?2:1))));
        assertThat(plan.lines()).hasSize(2);assertThat(plan.lines().get(1).after()).isNull();
        assertThat(plan.inventory()).extracting(InventoryPlan::reserveQuantity,InventoryPlan::releaseQuantity)
            .containsExactlyElementsOf(increase?List.of(tuple(1,0),tuple(0,1)):List.of(tuple(0,1)));
    }
    @Test void additionAndDecreaseKeepSnapshotsAndDoNotAllocateOrApply(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);long p=product(f,1000);
        String before=OrderEditAdmissionServicePostgresTest.business(f);
        UUID id=accepted(f,List.of(new OrderEditCandidate.Line(f.item(),f.product(),1),new OrderEditCandidate.Line(null,p,1)));
        var plan=prepare(id);
        assertThat(plan.lines()).hasSize(2);
        var existing=plan.lines().stream().filter(l->l.key().kind()==KeyKind.EXISTING).findFirst().orElseThrow();
        assertThat(existing.persistedItemId()).isEqualTo(f.item());assertThat(existing.after().unitPrice()).isEqualTo(3000);
        var added=plan.lines().stream().filter(l->l.key().kind()==KeyKind.NEW).findFirst().orElseThrow();
        assertThat(added.persistedItemId()).isNull();assertThat(added.before()).isNull();assertThat(added.after().unitPrice()).isEqualTo(1000);
        assertThat(plan.inventory()).extracting(InventoryPlan::reserveQuantity,InventoryPlan::releaseQuantity).containsExactly(tuple(0,1),tuple(1,0));
        assertThat(plan.inventory()).allSatisfy(i->assertThat(i.reservationState()).isNull());
        assertThat(prepare(id)).isEqualTo(plan);assertThat(OrderEditAdmissionServicePostgresTest.business(f)).isEqualTo(before);
        assertThat(db.jdbc.queryForObject("select stock from products where id=?",Integer.class,p)).isEqualTo(100);
    }
    @Test void replacementRepresentsRemovalAndNewLineWithoutReusingDbId(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);long p=product(f,1000);
        var plan=prepare(accepted(f,List.of(new OrderEditCandidate.Line(f.item(),p,1))));
        assertThat(plan.lines()).filteredOn(l->l.key().kind()==KeyKind.EXISTING).singleElement().satisfies(l->assertThat(l.after()).isNull());
        assertThat(plan.lines()).filteredOn(l->l.key().kind()==KeyKind.NEW).singleElement().satisfies(l->{assertThat(l.persistedItemId()).isNull();assertThat(l.after().productId()).isEqualTo(p);});
        assertThat(plan.inventory()).extracting(InventoryPlan::reserveQuantity,InventoryPlan::releaseQuantity).containsExactly(tuple(0,2),tuple(1,0));
    }
    @Test void duplicateAdditionsMergeButExistingPriceIsNeverOverwritten(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);long p=product(f,1000);
        var plan=prepare(accepted(f,List.of(new OrderEditCandidate.Line(null,p,1),new OrderEditCandidate.Line(null,p,2))));
        assertThat(plan.lines()).filteredOn(l->l.key().kind()==KeyKind.NEW).singleElement().satisfies(l->assertThat(l.after().quantity()).isEqualTo(3));
        assertThat(plan.inventory()).filteredOn(i->i.productId()==p).singleElement().satisfies(i->assertThat(i.reserveQuantity()).isEqualTo(3));
        var same=OrderEditAdmissionServicePostgresTest.fixture(false);db.jdbc.update("update products set price=1000 where id=?",same.product());
        var sameProduct=prepare(accepted(same,List.of(new OrderEditCandidate.Line(same.item(),same.product(),1),new OrderEditCandidate.Line(null,same.product(),1))));
        assertThat(sameProduct.lines()).extracting(l->l.after().unitPrice()).containsExactly(3000,1000);
        assertThat(sameProduct.inventory()).isEmpty();
    }
    @Test void multipleNewProductsAggregateIndependently(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);long a=product(f,1000),b=product(f,500);
        var plan=prepare(accepted(f,List.of(new OrderEditCandidate.Line(null,b,2),new OrderEditCandidate.Line(null,a,1))));
        assertThat(plan.lines()).filteredOn(l->l.key().kind()==KeyKind.NEW).hasSize(2);
        assertThat(plan.inventory()).extracting(InventoryPlan::productId).containsExactly(f.product(),a,b);
    }
    @ParameterizedTest @ValueSource(strings={"before_quantity+1","after_quantity+1"})
    void forgedDeltaCannotBeStored(String expression){
        UUID id=new OrderEditExecutionCoordinatorPostgresTest().receipt();prepare(id);
        String field=expression.startsWith("before")?"before_quantity":"after_quantity";
        db.rejects("23514",()->tx(()->{db.jdbc.queryForObject("select f125_execution_lock(?)",Object.class,id);
            db.jdbc.update("insert into order_edit_inventory_plans(admission_id,order_id,product_id,candidate_digest,before_quantity,after_quantity) select admission_id,order_id,product_id,candidate_digest,"+(field.equals("before_quantity")?expression:"before_quantity")+","+(field.equals("after_quantity")?expression:"after_quantity")+" from order_edit_inventory_plans where admission_id=?",id);return null;}));
    }
    @ParameterizedTest @ValueSource(strings={"update","delete","truncate"})
    void plansAreImmutable(String action){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);UUID id=accepted(f,List.of(new OrderEditCandidate.Line(f.item(),f.product(),1)));prepare(id);
        db.rejects("55000",()->db.jdbc.execute(switch(action){case "update"->"update order_edit_inventory_plans set before_quantity=before_quantity";case "delete"->"delete from order_edit_inventory_plans";default->"truncate order_edit_inventory_plans";}));
    }
    @Test void reservationAndApplicationWritersRemainClosed(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);UUID id=accepted(f,List.of(new OrderEditCandidate.Line(f.item(),f.product(),1)));prepare(id);
        db.rejects("23514",()->tx(()->{db.jdbc.queryForObject("select f125_execution_lock(?)",Object.class,id);
            db.jdbc.update("insert into order_edit_inventory_plans(admission_id,order_id,product_id,candidate_digest,before_quantity,after_quantity,reservation_state) select admission_id,order_id,product_id,candidate_digest,before_quantity,after_quantity,'HELD' from order_edit_inventory_plans where admission_id=?",id);return null;}));
        for(String state:List.of("APPLYING","COMPLETED"))db.rejects("23514",()->tx(()->{db.jdbc.queryForObject("select f125_execution_lock(?)",Object.class,id);db.jdbc.update("update order_edit_admissions set execution_state=?,version=version+1 where id=?",state,id);return null;}));
        var claim=new Claim(id,UUID.randomUUID(),0);assertThat(new OrderEditExecutionCoordinator(db.source).claim(claim).outcome()).isEqualTo(Outcome.RECORDED);
        db.rejects("23514",()->tx(()->{db.jdbc.queryForObject("select f125_execution_lock(?)",Object.class,id);
            db.jdbc.update("insert into order_edit_execution_attempt_results(id,attempt_id,admission_id,order_id,fencing_token,control_version,recovery,disposition,reason_code,application_version) select ?,?,a.id,a.order_id,1,1,false,'UNKNOWN','NOT_APPLIED',1 from order_edit_admissions a where id=?",UUID.randomUUID(),claim.attemptId(),id);return null;}));
        db.rejects("23514",()->tx(()->{db.jdbc.queryForObject("select f125_execution_resolve(?,?,?,?, 'APPLIED','NO_WRITER')",Object.class,id,claim.attemptId(),1,1);return null;}));
    }
    @Test void matchingOwnershipIsObservedButOldForeignOrWrongTokenIsRejected(){
        UUID id=new OrderEditExecutionCoordinatorPostgresTest().receipt();prepare(id);var claim=new Claim(id,UUID.randomUUID(),0);var service=new OrderEditExecutionCoordinator(db.source);
        service.claim(claim);var own=new Ownership(id,claim.attemptId(),1,1);
        var context=tx(()->repository().applicationContext(own));assertThat(context.beforeRevision()).isZero();assertThat(context.afterRevision()).isEqualTo(1);
        UUID other=new OrderEditExecutionCoordinatorPostgresTest().receipt();var foreign=new Claim(other,UUID.randomUUID(),0);service.claim(foreign);
        for(var bad:List.of(new Ownership(id,foreign.attemptId(),1,1),new Ownership(id,UUID.randomUUID(),1,1),new Ownership(id,claim.attemptId(),2,1),new Ownership(id,claim.attemptId(),1,2)))
            db.rejects("23514",()->tx(()->repository().applicationContext(bad)));
        service.resolve(new Resolution(id,UUID.randomUUID(),claim.attemptId(),1,1,Disposition.REQUEUE,"TEST_REQUEUE"));
        db.rejects("23514",()->tx(()->repository().applicationContext(own)));
    }
    @Test void stoppedRuntimeAndWrongTransactionCannotPrepare(){
        UUID id=new OrderEditExecutionCoordinatorPostgresTest().receipt();assertThatThrownBy(()->repository().prepare(id)).isInstanceOf(IllegalStateException.class);
        db.jdbc.update("update order_edit_runtime_control set state='STOPPED',generation_id=null,version=version+1 where id=1");
        db.rejects("55000",()->tx(()->repository().prepare(id)));
    }
    @Test void rollbackLeavesNoPlanAndPreparingDoesNotPreventSafeClosure(){
        UUID id=new OrderEditExecutionCoordinatorPostgresTest().receipt();
        assertThatThrownBy(()->tx(()->{repository().prepare(id);throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(db.jdbc.queryForObject("select count(*) from order_edit_inventory_plans where admission_id=?",Integer.class,id)).isZero();
        prepare(id);var helper=new OrderEditExecutionCoordinatorPostgresTest();assertThat(helper.service().closeUnapplied(helper.closure(id,0)).outcome()).isEqualTo(Outcome.RECORDED);
        assertThat(tx(()->repository().read(id)).inventory()).hasSize(1);
        db.rejects("23514",()->tx(()->repository().prepare(id)));
    }
    @Test void concurrentPreparationStoresEachProductOnce()throws Exception{
        UUID id=new OrderEditExecutionCoordinatorPostgresTest().receipt();var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{Callable<Plan> action=()->{gate.await();return prepare(id);};var a=pool.submit(action);var b=pool.submit(action);gate.countDown();
            assertThat(a.get(20,TimeUnit.SECONDS)).isEqualTo(b.get(20,TimeUnit.SECONDS));
            assertThat(db.jdbc.queryForObject("select count(*) from order_edit_inventory_plans where admission_id=?",Integer.class,id)).isEqualTo(1);
        }finally{pool.shutdownNow();assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }
}
