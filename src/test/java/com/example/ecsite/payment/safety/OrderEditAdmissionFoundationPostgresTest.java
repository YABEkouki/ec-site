package com.example.ecsite.payment.safety;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.example.ecsite.support.isolation.IsolatedDatabaseGuard;
import static org.assertj.core.api.Assertions.*;

/** Real commits on guarded ecsite_test; server-setting cases alone use disposable containers. */
class OrderEditAdmissionFoundationPostgresTest {
    static PostgreSQLContainer container;
    static PaymentSafetyExpandPostgresTest db;
    static JdbcTemplate jdbc;
    static UUID generation;
    record Fixture(long order,long customer,long payment,long authorization,UUID epoch) {}
    @BeforeAll static void database() {
        PaymentSafetyExpandPostgresTest.verifyContainerBeforeAnyDatabaseAccess();
        if(System.getProperty("ecsite.test.database-mode","shared").equals("testcontainers")) {
            container=commitTrackingContainer();container.start();
        }
        db=open(container);db.migrate("70");db.legacyFixture();var before=db.legacyRows();db.migrate("71");
        assertThat(db.legacyRows()).isEqualTo(before);jdbc=db.jdbc;
    }
    static PostgreSQLContainer commitTrackingContainer() {
        return new PostgreSQLContainer("postgres:17")
            .withDatabaseName("isolated_f125_admission_"+UUID.randomUUID().toString().replace("-",""))
            .withUsername("admission_test").withPassword(UUID.randomUUID().toString())
            .withCommand("postgres","-c","track_commit_timestamp=on","-c","synchronous_commit=on","-c","fsync=on");
    }
    static PaymentSafetyExpandPostgresTest open(PostgreSQLContainer c) {
        if(c==null) {
            PaymentSafetyExpandPostgresTest.verifyContainerBeforeAnyDatabaseAccess();
            var result=new PaymentSafetyExpandPostgresTest();result.isolatedSchema();return result;
        }
        var env=new MockEnvironment();
        env.setActiveProfiles("test");
        env.setProperty("ecsite.test.database-mode","testcontainers");
        for(String prefix:List.of("spring.datasource","spring.flyway")) {
            env.setProperty(prefix+".url",c.getJdbcUrl());
            env.setProperty(prefix+(prefix.endsWith("flyway")?".user":".username"),c.getUsername());
            env.setProperty(prefix+".password",c.getPassword());
        }
        IsolatedDatabaseGuard.verify(env);
        var result=new PaymentSafetyExpandPostgresTest();result.schema="admission_"+UUID.randomUUID().toString().replace("-","");
        result.source=new PGSimpleDataSource();result.source.setURL(c.getJdbcUrl());result.source.setUser(c.getUsername());
        result.source.setPassword(c.getPassword());result.source.setCurrentSchema(result.schema);
        result.jdbc=new JdbcTemplate(result.source);return result;
    }
    @AfterAll static void cleanup() { if(container!=null)container.stop(); }
    @BeforeEach void runtime(TestInfo info) {
        jdbc.update("update order_edit_runtime_control set state='STOPPED',generation_id=null,version=version+1 where id=1");
        if(!info.getTestMethod().orElseThrow().getName().equals("migrationStartsStoppedAndDoesNotInstallProductionActivation")) {
            // V76 forbids abandoning unresolved generations. Reuse the same test lineage;
            // older-schema safety tests retain their original generation-switch fixtures.
            boolean operational=Boolean.TRUE.equals(jdbc.queryForObject("select to_regprocedure('f125_runtime_available()') is not null",Boolean.class));
            var open=operational?jdbc.queryForList("select distinct db_generation_id from order_edit_admissions where closed_at is null",UUID.class):List.<UUID>of();
            assertThat(open).hasSizeLessThanOrEqualTo(1);
            if(open.isEmpty()){
                generation=UUID.randomUUID();
                jdbc.update("insert into order_edit_runtime_generations(id,identity) values (?,f125_admission_identity())",generation);
            }else generation=open.getFirst();
            jdbc.update("update order_edit_runtime_control set state='ISOLATED_TEST',generation_id=?,version=version+1 where id=1",generation);
        }
    }
    static <T>T tx(Supplier<T> action) {return new TransactionTemplate(new DataSourceTransactionManager(db.source)).execute(s->action.get());}
    static Fixture fixture(boolean past) {
        long order=db.order(),payment=db.payment(order);
        long customer=jdbc.queryForObject("select user_id from orders where id=?",Long.class,order);
        jdbc.update("update orders set ordered_at=date_trunc('day',timezone('Asia/Tokyo',clock_timestamp()))+?::interval+interval '10 hours',change_deadline_at=date_trunc('day',timezone('Asia/Tokyo',clock_timestamp()))+?::interval+interval '14 hours',item_subtotal=6000,total_amount=6000,tax_amount=545,active_payment_id=? where id=?",
            past?"-2 days":"2 days",past?"-2 days":"2 days",payment,order);
        jdbc.update("update payments set amount=6000 where id=?",payment);
        long authorization=db.transaction(payment,"AUTHORIZE","SUCCEEDED","INITIAL_ORDER_AUTHORIZATION");
        jdbc.update("update payment_transactions set amount=6000,completed_at=localtimestamp,provider_transaction_id=(select provider_payment_id from payments where id=?) where id=?",payment,authorization);
        UUID epoch=UUID.randomUUID();byte[] snapshot=OrderPaymentEvidenceFoundationPostgresTest.snapshot(order,customer,0,6000);
        // Structural V69 fixture, not an implemented order writer or pure-Validator attestation.
        tx(()->{
            jdbc.update("""
                insert into order_payment_evidence_epochs(id,order_id,payment_id,authorization_transaction_id,origin_kind,
                  start_content_revision,start_total_amount,authorized_amount,authorization_revision,provider_flow_id,
                  start_snapshot,start_fingerprint,observation_payload,observation_fingerprint,observation_started_at,
                  observation_finished_at,evidence_version,evidence_digest)
                select ?,?,?,?,'INITIAL_AUTH',0,6000,6000,0,p.provider_payment_id,?,sha256(?),decode('7b7d','hex'),
                  sha256(decode('7b7d','hex')),clock_timestamp(),clock_timestamp(),1,sha256(decode('01','hex')) from payments p where id=?
                """,epoch,order,payment,authorization,snapshot,snapshot,payment);
            jdbc.update("update orders set current_evidence_epoch_id=? where id=?",epoch,order);return null;
        });
        return new Fixture(order,customer,payment,authorization,epoch);
    }
    static UUID insert(Fixture f,UUID key) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
            insert into order_edit_admissions(id,order_id,customer_id,actor_username,submission_key,
              request_version,request_payload,request_digest,candidate_version,candidate_payload,candidate_digest,
              base_content_revision,base_evidence_epoch_id,base_evidence_history_id,base_fingerprint,base_payment_id,
              base_authorization_id,authorized_amount,authorization_revision,before_amount,after_amount,deadline_at,db_generation_id)
            select ?,o.id,o.user_id,'ignored caller actor',?,1,decode('010203','hex'),sha256(decode('010203','hex')),
              1,decode('040506','hex'),sha256(decode('040506','hex')),o.content_revision,e.id,o.evidence_head_history_id,
              e.start_fingerprint,e.payment_id,e.authorization_transaction_id,e.authorized_amount,e.authorization_revision,
              o.total_amount,5000,o.change_deadline_at at time zone 'Asia/Tokyo',?
            from orders o join order_payment_evidence_epochs e on e.id=o.current_evidence_epoch_id where o.id=?
            """,id,key,generation,f.order());
        return id;
    }
    static UUID record(Fixture f) { return tx(()->insert(f,UUID.randomUUID())); }
    static String verify(UUID id) { return tx(()->jdbc.queryForObject("select f125_admission_verify(?)",String.class,id)); }
    @Test void migrationStartsStoppedAndDoesNotInstallProductionActivation() {
        assertThat(jdbc.queryForObject("select state from order_edit_runtime_control where id=1",String.class)).isEqualTo("STOPPED");
        db.rejects("23514",()->jdbc.update("update order_edit_runtime_control set state='ENABLED_PRODUCTION',version=version+1 where id=1"));
    }

    @Test void recordingRejectsChangedProviderIdentity() {
        var f=fixture(false);
        jdbc.update("update payments set provider_payment_id='pfw_changed_after_epoch' where id=?",f.payment());
        db.rejects("23514",()->record(f));
    }

    @Test void realPreDeadlineCommitCreatesImmutableProofAndDecisionAtomically() {
        var f=fixture(false);String before=jdbc.queryForObject("select to_jsonb(o)::text from orders o where id=?",String.class,f.order());
        UUID id=record(f);
        assertThat(verify(id)).isEqualTo("ACCEPTED");assertThat(verify(id)).isEqualTo("ACCEPTED");
        var proof=jdbc.queryForMap("""
            select e.scope,e.status_before,e.status_after,e.original_xid::text original,e.verification_xid::text verification,
              e.original_commit_at=pg_xact_commit_timestamp(e.original_xid::xid) as exact_stamp,
              e.original_commit_at<e.deadline_at as early,pg_typeof(a.original_xid)::text as xid_type
            from order_edit_admission_evidences e join order_edit_admissions a on a.id=e.admission_id where e.admission_id=?
            """,id);
        assertThat(proof).containsEntry("scope","ISOLATED_TEST").containsEntry("status_before","committed").containsEntry("status_after","committed")
            .containsEntry("exact_stamp",true).containsEntry("early",true).containsEntry("xid_type","xid8");
        assertThat(proof.get("original")).isNotEqualTo(proof.get("verification"));
        assertThat(jdbc.queryForObject("select count(*) from order_edit_admission_events where admission_id=?",Integer.class,id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select to_jsonb(o)::text from orders o where id=?",String.class,f.order())).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from order_change_operations where order_id=?",Integer.class,f.order())).isZero();
    }
    @Test void realPostDeadlineCommitIsRejectedAndReleasesOnlyThisAdmission() {
        var f=fixture(true);UUID id=record(f);
        assertThat(verify(id)).isEqualTo("REJECTED_LATE");
        assertThat(jdbc.queryForObject("select closed_at is not null from order_edit_admissions where id=?",Boolean.class,id)).isTrue();
        assertThat(record(f)).isNotEqualTo(id);
    }
    @Test void strictEqualityComparisonUsesAnActualCommitTimestampButIsNotAnEqualityE2e() {
        UUID id=record(fixture(false));
        var comparison=jdbc.queryForMap("""
            select f125_admission_before_deadline(c,c+interval '1 microsecond') as early,
              f125_admission_before_deadline(c,c) as equal,
              f125_admission_before_deadline(c,c-interval '1 microsecond') as late
            from (select pg_xact_commit_timestamp(original_xid::xid) c from order_edit_admissions where id=?) x
            """,id);
        assertThat(comparison).containsEntry("early",true).containsEntry("equal",false).containsEntry("late",false);
    }
    @Test void xidIsCapturedByDatabaseAndUnsigned64BitRepresentationRoundTrips() {
        var f=fixture(false);
        UUID id=tx(()->{
            var expected=jdbc.queryForObject("select pg_current_xact_id()::text",String.class);
            UUID recorded=insert(f,UUID.randomUUID());
            assertThat(jdbc.queryForObject("select original_xid::text from order_edit_admissions where id=?",String.class,recorded)).isEqualTo(expected);
            return recorded;
        });
        assertThat(jdbc.queryForObject("select pg_xact_status(original_xid) from order_edit_admissions where id=?",String.class,id)).isEqualTo("committed");
        assertThat(jdbc.queryForObject("select '18446744073709551615'::xid8::text",String.class)).isEqualTo("18446744073709551615");
    }
    @Test void sameTransactionVerificationCannotBecomeEvidenceAndRollsBackReceipt() {
        var f=fixture(false);
        db.rejects("23514",()->tx(()->{UUID id=insert(f,UUID.randomUUID());jdbc.queryForObject("select f125_admission_verify(?)",String.class,id);return null;}));
        assertThat(jdbc.queryForObject("select count(*) from order_edit_admissions where order_id=?",Integer.class,f.order())).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"update","delete","truncate"})
    void proofAndEventsCannotBeRewritten(String mutation) {
        UUID id=record(fixture(false));verify(id);
        for(String table:List.of("order_edit_admission_evidences","order_edit_admission_events")) {
            String sql=switch(mutation) {case "update"->"update "+table+" set admission_id=admission_id";case "delete"->"delete from "+table;default->"truncate "+table+" cascade";};
            db.rejects("55000",()->jdbc.execute(sql));
        }
    }
    @ParameterizedTest @ValueSource(strings={"request_payload=decode('ff','hex')","candidate_payload=decode('ff','hex')","base_content_revision=1",
        "deadline_at=deadline_at+interval '1 day'","original_xid='18446744073709551615'::xid8","base_payment_id=0","after_amount=5500"})
    void fixedContextCannotBeChanged(String assignment) {
        UUID id=record(fixture(false));
        db.rejects("55000",()->jdbc.update("update order_edit_admissions set "+assignment+",version=version+1 where id=?",id));
    }
    @Test void duplicateKeysAndDifferentKeysOnSameOpenOrderAreBothRejected() {
        var f=fixture(false);UUID key=UUID.randomUUID();UUID id=tx(()->insert(f,key));
        db.rejects("23505",()->tx(()->insert(f,key)));
        db.rejects("23505",()->record(f));
        assertThat(jdbc.queryForObject("select id from order_edit_admissions where order_id=? and submission_key=?",UUID.class,f.order(),key)).isEqualTo(id);
    }
    @Test void callerCannotProvideAFalseTimestampOrCommitOnlyEvidenceOrOnlyDecision() {
        UUID id=record(fixture(false));
        db.rejects("23514",()->tx(()->{jdbc.update("insert into order_edit_admission_evidences(admission_id,original_commit_at) values (?,'2000-01-01T00:00:00Z')",id);return null;}));
        db.rejects("23514",()->tx(()->{jdbc.update("insert into order_edit_admission_evidences(admission_id) values (?)",id);return null;}));
        db.rejects("23514",()->tx(()->{jdbc.update("update order_edit_admissions set admission_state='ACCEPTED',execution_state='QUEUED',version=version+1 where id=?",id);return null;}));
        assertThat(jdbc.queryForObject("select admission_state from order_edit_admissions where id=?",String.class,id)).isEqualTo("RECORDED_UNVERIFIED");
        assertThat(jdbc.queryForObject("select count(*) from order_edit_admission_evidences where admission_id=?",Integer.class,id)).isZero();
    }
    @Test void missingAutomaticEventCannotCommitAndFailedVerificationCanBeHeldSeparately() {
        UUID id=record(fixture(false));
        // Test-only event-save fault in this dedicated schema; deferred proof checks remain active.
        jdbc.execute("alter table order_edit_admissions disable trigger order_edit_admission_event");
        try {db.rejects("23514",()->verify(id));}
        finally {jdbc.execute("alter table order_edit_admissions enable trigger order_edit_admission_event");}
        tx(()->{jdbc.queryForObject("select f125_admission_hold(?,'TEST_EVENT_SAVE_FAILURE')",Object.class,id);return null;});
        assertThat(jdbc.queryForObject("select admission_state from order_edit_admissions where id=?",String.class,id)).isEqualTo("UNPROVEN");
        assertThat(verify(id)).isEqualTo("ACCEPTED");
    }
    @Test void generationSwitchAndStoppedRuntimeNeverCertifyOldReceipts() {
        UUID id=record(fixture(false));UUID other=UUID.randomUUID();
        jdbc.update("insert into order_edit_runtime_generations(id,identity) values (?,f125_admission_identity())",other);
        jdbc.update("update order_edit_runtime_control set generation_id=?,version=version+1 where id=1",other);
        db.rejects("55000",()->verify(id));
        jdbc.update("update order_edit_runtime_control set state='STOPPED',generation_id=null,version=version+1 where id=1");
        db.rejects("55000",()->record(fixture(false)));
    }
    @Test void rollbackLeavesNoAdmissionAndSafeClosureReleasesGate() {
        var f=fixture(false);UUID key=UUID.randomUUID();
        assertThatThrownBy(()->tx(()->{insert(f,key);throw new IllegalStateException("test rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from order_edit_admissions where order_id=?",Integer.class,f.order())).isZero();
        UUID id=tx(()->insert(f,key));verify(id);
        tx(()->{jdbc.queryForObject("select f125_admission_close_unapplied(?,'TEST_EXPLICIT_CLOSE')",Object.class,id);return null;});
        assertThat(jdbc.queryForObject("select admission_state from order_edit_admissions where id=?",String.class,id)).isEqualTo("ACCEPTED");
        assertThat(record(f)).isNotEqualTo(id);
        db.rejects("55000",()->jdbc.update("update order_edit_admissions set execution_state='QUEUED',closed_at=null,version=version+1 where id=?",id));
    }
    @Test void unprovenApplicationAttemptsCannotReleaseGateOrPretendCompletion() {
        UUID id=record(fixture(false));verify(id);
        tx(()->{jdbc.update("update order_edit_admissions set execution_state='OBSERVING',version=version+1 where id=?",id);return null;});
        db.rejects("23514",()->tx(()->{jdbc.queryForObject("select f125_admission_close_unapplied(?,'LEASE_EXPIRED')",Object.class,id);return null;}));
        tx(()->{jdbc.update("update order_edit_admissions set execution_state='APPLYING',version=version+1 where id=?",id);return null;});
        db.rejects("23514",()->tx(()->{jdbc.update("update order_edit_admissions set execution_state='COMPLETED',version=version+1 where id=?",id);return null;}));
    }
    @Test void matchingCompletedV69OperationCanCloseAdmissionAtomically() {
        var f=fixture(false);UUID id=record(f);verify(id);UUID op=UUID.randomUUID();
        tx(()->{
            jdbc.queryForObject("select f125_admission_lock(?)",Object.class,id);
            jdbc.update("update order_edit_admissions set execution_state='OBSERVING',version=version+1 where id=?",id);
            jdbc.update("update order_edit_admissions set execution_state='APPLYING',version=version+1 where id=?",id);
            // Structural integration fixture only; candidate interpretation and the order writer are out of scope.
            jdbc.update("""
                insert into order_change_operations(id,order_id,customer_id,submission_key,request_digest,base_content_revision,
                  old_payment_id,before_amount,after_amount,item_subtotal,charge_total,tax_amount,shipping_name,shipping_amount,
                  shipping_tax_category_id,shipping_tax_category_code,shipping_tax_category_name,shipping_tax_rate,shipping_display_order,
                  accepted_at,deadline_at,grace_until,state,created_at,updated_at,execution_version,execution_route,
                  base_evidence_epoch_id,base_evidence_history_id,old_authorization_transaction_id,old_authorized_amount,
                  old_authorization_revision,admission_observation_payload,admission_observation_fingerprint)
                select ?,r.order_id,r.customer_id,r.submission_key,encode(r.request_digest,'hex'),r.base_content_revision,
                  r.base_payment_id,r.before_amount,r.after_amount,r.after_amount,0,0,'送料',0,5,'STANDARD','標準',10,10,
                  o.ordered_at,o.change_deadline_at,o.change_deadline_at+interval '10 minutes','RESERVED',localtimestamp,localtimestamp,
                  1,r.route,r.base_evidence_epoch_id,r.base_evidence_history_id,r.base_authorization_id,r.authorized_amount,
                  r.authorization_revision,decode('7b7d','hex'),sha256(decode('7b7d','hex'))
                from order_edit_admissions r join orders o on o.id=r.order_id where r.id=?
                """,op,id);
            byte[] body=OrderPaymentEvidenceFoundationPostgresTest.snapshot(f.order(),f.customer(),1,5000);
            long history=jdbc.queryForObject("""
                insert into order_content_change_histories(order_id,change_source,changed_by_type,changed_by_account_id,
                  changed_by_username,old_item_subtotal,new_item_subtotal,old_charge_total,new_charge_total,old_tax_amount,new_tax_amount,
                  old_total_amount,new_total_amount,before_content_revision,after_content_revision,evidence_epoch_id,
                  previous_history_id,before_fingerprint,after_fingerprint,after_snapshot,evidence_version,evidence_digest)
                select order_id,'CUSTOMER','USER',customer_id,actor_username,6000,5000,0,0,545,454,
                  6000,5000,0,1,base_evidence_epoch_id,null,base_fingerprint,sha256(?),?,1,sha256(decode('02','hex'))
                from order_edit_admissions where id=? returning id
                """,Long.class,body,body,id);
            jdbc.update("insert into order_content_change_items(history_id,product_id,product_name,change_type) values (?,7,'商品','UPDATED')",history);
            jdbc.update("insert into order_content_change_charges(history_id,charge_type,change_type) values (?,'SHIPPING','UPDATED')",history);
            jdbc.update("update orders set evidence_head_history_id=?,content_revision=1,item_subtotal=5000,total_amount=5000,tax_amount=454 where id=?",history,f.order());
            jdbc.update("update order_change_operations set state='COMPLETED',version=version+1,content_change_history_id=? where id=?",history,op);
            jdbc.update("update order_edit_admissions set execution_state='COMPLETED',applied_operation_id=?,version=version+1 where id=?",op,id);
            return null;
        });
        assertThat(jdbc.queryForMap("select execution_state,applied_operation_id,closed_at is not null as closed from order_edit_admissions where id=?",id))
            .containsEntry("execution_state","COMPLETED").containsEntry("applied_operation_id",op).containsEntry("closed",true);
        assertThat(jdbc.queryForObject("select count(*) from order_edit_admission_events where admission_id=?",Integer.class,id)).isEqualTo(5);
    }
    @Test void twoTransactionsCompetingForAnOrderHaveOnlyOneDurableReceipt() throws Exception {
        var f=fixture(false);var inserted=new CountDownLatch(1);var release=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->tx(()->{UUID id=insert(f,UUID.randomUUID());inserted.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException();}catch(InterruptedException e){throw new IllegalStateException(e);}return id;}));
            assertThat(inserted.await(10,TimeUnit.SECONDS)).isTrue();
            var second=pool.submit(()->record(f));release.countDown();
            assertThat(first.get(10,TimeUnit.SECONDS)).isNotNull();
            assertThatThrownBy(()->second.get(10,TimeUnit.SECONDS)).hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class);
            assertThat(jdbc.queryForObject("select count(*) from order_edit_admissions where order_id=?",Integer.class,f.order())).isEqualTo(1);
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void actualDisabledCommitTrackingCannotActivateOrProduceEvidence() {
        try(var off=new PostgreSQLContainer("postgres:17").withDatabaseName("isolated_f125_admission_"+UUID.randomUUID().toString().replace("-",""))
            .withUsername("admission_test").withPassword(UUID.randomUUID().toString()).withCommand("postgres","-c","track_commit_timestamp=off")) {
            off.start();var isolated=open(off);isolated.migrate("71");
            isolated.rejects("55000",()->isolated.jdbc.queryForObject("select pg_xact_commit_timestamp(pg_current_xact_id()::xid)",Object.class));
            isolated.rejects("55000",()->isolated.jdbc.update("insert into order_edit_runtime_generations(id,identity) values (?,f125_admission_identity())",UUID.randomUUID()));
            assertThat(isolated.jdbc.queryForObject("select state from order_edit_runtime_control where id=1",String.class)).isEqualTo("STOPPED");
        }
    }
}
