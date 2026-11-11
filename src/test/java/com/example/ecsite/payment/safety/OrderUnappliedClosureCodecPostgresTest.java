package com.example.ecsite.payment.safety;

import java.util.*;
import java.sql.SQLException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import com.example.ecsite.service.order.evidence.*;
import static com.example.ecsite.service.order.evidence.OrderEditExecution.*;
import static org.assertj.core.api.Assertions.*;

/** Raw TEXT, not a pre-normalized JSONB parameter: compares the same input on both implementations. */
class OrderUnappliedClosureCodecPostgresTest {
    static PaymentSafetyExpandPostgresTest db;static JdbcTemplate jdbc;
    final OrderUnappliedClosureCodec codec=new OrderUnappliedClosureCodec();
    @BeforeAll static void database(){OrderEditExecutionCoordinatorPostgresTest.database();db=OrderEditExecutionCoordinatorPostgresTest.db;jdbc=db.jdbc;}
    @AfterAll static void cleanup(){OrderEditExecutionCoordinatorPostgresTest.cleanup();}
    @BeforeEach void runtime(TestInfo info){new OrderEditAdmissionFoundationPostgresTest().runtime(info);}
    void agrees(String audit,boolean accepted){
        if(accepted){
            byte[] expected=codec.canonicalValue(audit);
            byte[] actual=jdbc.queryForObject("select f125_closure_canonical(?::text)",byte[].class,audit);
            assertThat(actual).isEqualTo(expected);
            assertThat(jdbc.queryForObject("select sha256(f125_closure_canonical(?::text))",byte[].class,audit)).isEqualTo(codec.sha256(expected));
        }else{
            assertThatThrownBy(()->codec.canonicalValue(audit)).isInstanceOf(IllegalArgumentException.class);
            Throwable error=catchThrowable(()->jdbc.queryForObject("select f125_closure_canonical(?::text)",byte[].class,audit));
            assertThat(error).isNotNull();while(!(error instanceof SQLException)&&error.getCause()!=null)error=error.getCause();
            assertThat(error).isInstanceOf(SQLException.class);assertThat(((SQLException)error).getSQLState()).isEqualTo("23514");
        }
    }
    @ParameterizedTest @ValueSource(ints={-1,0,1})
    void rawAuditByteBoundary(int delta){agrees("null"+" ".repeat(4_194_304+delta-4),delta<=0);}
    @ParameterizedTest @ValueSource(ints={-1,0,1})
    void unicodeAuditByteBoundary(int delta){String s="\"あ\"";agrees(s+" ".repeat(4_194_304+delta-s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length),delta<=0);}
    @ParameterizedTest @ValueSource(ints={-1,0,1})
    void canonicalScalarByteBoundary(int delta){agrees("\""+"a".repeat(2_097_152+delta-5)+"\"",delta<=0);}
    @ParameterizedTest @ValueSource(ints={-1,0,1})
    void numericDigitBoundary(int delta){agrees("7".repeat(1000+delta),delta<=0);agrees("-"+"7".repeat(1000+delta),delta<=0);}
    @ParameterizedTest @ValueSource(ints={-1,0,1})
    void expandedNumericBoundary(int delta){agrees("1e"+(999+delta),delta<=0);agrees("1e-"+(999+delta),delta<=0);}
    @ParameterizedTest @ValueSource(ints={63,64,65})
    void rootContainerDepthBoundary(int depth){
        agrees("[".repeat(depth)+"]".repeat(depth),depth<=64);
        agrees("[".repeat(depth)+"null"+"]".repeat(depth),depth<=64);
        agrees("{\"v\":".repeat(depth)+"0"+"}".repeat(depth),depth<=64);
    }
    @ParameterizedTest @ValueSource(strings={"null","\"\"","[]","{}","true","false","0","-0","-0.00","0.000","1.000","1e2","1E+02","-1e-2","0e999","1e-999","\"日本語\\n商品\"","\"é\"","\"é\"","{\"b\":1,\"a\":null}"})
    void emptySignedExponentAndUnicodeValues(String audit){agrees(audit,true);}
    @ParameterizedTest @ValueSource(strings={"0e1000","0e-1000","0e0000","+1","01","NaN","Infinity","{\"a\":1,\"a\":2}","{\"a\":1,\"\\u0061\":2}","null null","\"\\u0000\"","\"\\ud800\""})
    void invalidOrNonPortableInputRejectedBeforeJsonbNormalization(String audit){agrees(audit,false);}
    @Test void auditedCounterexampleAndFixedVector(){
        String old="{\"s\": \""+"\\\\".repeat(2_000_100)+"\"}";
        assertThat(old.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(4_000_209);agrees(old,true);
        assertThat(codec.canonicalValue(old)).hasSize(2_000_115);
        String vector="{\"z\":null,\"a\":[\"\",1.00,true]}";agrees(vector,true);
        assertThat(java.util.HexFormat.of().formatHex(codec.sha256(codec.canonicalValue(vector))))
            .isEqualTo("13ada7a56ab47b73f611ab9b20bd1329833e623c46ba38b2284e5d42e76ffc0c");
        agrees("1".repeat(1001),false);agrees("[".repeat(65)+"]".repeat(65),false);agrees(null,false);
    }
    String state(UUID id){return jdbc.queryForObject("""
        select jsonb_build_object('admission',to_jsonb(a),
          'control',(select to_jsonb(c) from order_edit_execution_controls c where admission_id=a.id),
          'attempts',(select jsonb_agg(to_jsonb(x) order by control_version) from order_edit_execution_attempts x where admission_id=a.id),
          'results',(select jsonb_agg(to_jsonb(x) order by control_version) from order_edit_execution_attempt_results x where admission_id=a.id),
          'closure',(select to_jsonb(e) from order_edit_unapplied_evidences e where admission_id=a.id),
          'events',(select jsonb_agg(to_jsonb(x) order by event_no) from order_edit_admission_events x where admission_id=a.id),
          'gate',a.closed_at is null)::text from order_edit_admissions a where id=?
        """,String.class,id);}
    @ParameterizedTest @ValueSource(strings={"CANONICAL","AUDIT"})
    void oversizeEvidenceRollsBackEntireClosureAndRetry(String limit){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);
        // Order-linked history is captured BEFORE admission, never patched into an existing baseline.
        String reason=limit.equals("AUDIT")?"repeat(chr(1),500)":"repeat('x',500)";
        jdbc.update("""
            insert into stock_movements(product_id,product_name,movement_type,quantity,stock_before,stock_after,
                changed_by_username,changed_by_type,order_id,reason)
            select ?, 'fixture','ORDER_RETURN',1,10,11,'test','USER',?,
            """+reason+" from generate_series(1,?)",f.product(),f.order(),limit.equals("AUDIT")?1700:4500);
        var accepted=OrderEditAdmissionServicePostgresTest.service().submit(OrderEditAdmissionServicePostgresTest.command(f));
        assertThat(accepted.outcome()).isEqualTo(OrderEditAdmissionService.Outcome.ACCEPTED);UUID id=accepted.admissionId();
        var helper=new OrderEditExecutionCoordinatorPostgresTest();helper.initialize(id,"1 minute");
        var service=new OrderEditExecutionCoordinator(db.source);var claim=new Claim(id,UUID.randomUUID(),0);
        assertThat(service.claim(claim).outcome()).isEqualTo(Outcome.RECORDED);
        assertThat(service.resolve(new Resolution(id,UUID.randomUUID(),claim.attemptId(),1,1,Disposition.REQUEUE,"LOCAL_ONLY")).outcome()).isEqualTo(Outcome.RECORDED);
        String before=state(id);var command=new OrderUnappliedClosure.Command(id,UUID.randomUUID(),2,"CLOSED_UNAPPLIED","OVERSIZE_TEST");
        Throwable failure=catchThrowable(()->OrderEditAdmissionServicePostgresTest.tx(()->{
            jdbc.queryForObject("select f125_execution_close_request(?,?,?,?,?)",Object.class,id,command.requestId(),2,command.terminal(),command.reason());return null;
        }));
        assertThat(failure).isNotNull();while(!(failure instanceof SQLException)&&failure.getCause()!=null)failure=failure.getCause();
        assertThat(failure).isInstanceOf(SQLException.class);assertThat(((SQLException)failure).getSQLState()).isEqualTo("23514");
        assertThat(failure.getMessage()).contains(limit.equals("AUDIT")?"Closure audit exceeds 4 MiB":"Closure canonical exceeds 2 MiB");
        assertThat(state(id)).isEqualTo(before);
        for(int retry=0;retry<2;retry++){
            assertThat(service.closeUnapplied(command).outcome()).isEqualTo(Outcome.BLOCKED);
            assertThat(state(id)).isEqualTo(before);
            var observed=service.recoverClosure(command);assertThat(observed.outcome()).isEqualTo(Outcome.NOT_OBSERVED);
            assertThat(observed.view().state()).isEqualTo(State.QUEUED);assertThat(observed.view().token()).isEqualTo(2L);
            assertThat(observed.view().attempts()).hasSize(1);assertThat(observed.view().results()).hasSize(1);
        }
        assertThat(jdbc.queryForObject("select closed_at is null from order_edit_admissions where id=?",Boolean.class,id)).isTrue();
    }

    @ParameterizedTest @ValueSource(ints={-1,0,1})
    void canonicalAggregateBoundary(int delta){agrees("[\""+"a".repeat(1_000_000)+"\",\""+"b".repeat(1_097_137+delta)+"\"]",delta<=0);}
    @Test void savedEvidenceReadUsesTheSameRawAuditLimit(){
        var helper=new OrderEditExecutionCoordinatorPostgresTest();UUID id=helper.receipt();var command=helper.closure(id,0);
        assertThat(helper.service().closeUnapplied(command).outcome()).isEqualTo(Outcome.RECORDED);
        var row=jdbc.queryForMap("select evidence_document::text audit,canonical,evidence_digest digest from order_edit_unapplied_evidences where admission_id=?",id);
        String audit=(String)row.get("audit");byte[] canonical=(byte[])row.get("canonical"),digest=(byte[])row.get("digest");
        int size=audit.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        for(int delta:new int[]{-1,0})assertThat(codec.verify(audit+" ".repeat(4_194_304+delta-size),canonical,digest).matches(command)).isTrue();
        assertThatThrownBy(()->codec.verify(audit+" ".repeat(4_194_305-size),canonical,digest)).isInstanceOf(IllegalArgumentException.class);
        assertThat(helper.service().closeUnapplied(command).outcome()).isEqualTo(Outcome.RECORDED);
        assertThat(helper.service().recoverClosure(command).outcome()).isEqualTo(Outcome.OBSERVED);
    }
}
