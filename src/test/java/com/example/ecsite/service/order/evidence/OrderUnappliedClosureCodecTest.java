package com.example.ecsite.service.order.evidence;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import java.util.HexFormat;

class OrderUnappliedClosureCodecTest {
    @Test void canonicalVectorDistinguishesNullEmptyAndNumericTypes(){
        var codec=new OrderUnappliedClosureCodec();
        assertThat(HexFormat.of().formatHex(codec.canonicalValue("{\"z\":null,\"a\":[\"\",1.00,true]}")))
            .isEqualTo("05000000020000000161040000000302000000000300000001310101000000017a00");
        assertThat(codec.canonicalValue("{\"a\":[\"\",1,true],\"z\":null}"))
            .isEqualTo(codec.canonicalValue("{\"z\":null,\"a\":[\"\",1.00,true]}"));
        assertThat(HexFormat.of().formatHex(codec.sha256(codec.canonicalValue("{\"z\":null,\"a\":[\"\",1.00,true]}"))))
            .isEqualTo("13ada7a56ab47b73f611ab9b20bd1329833e623c46ba38b2284e5d42e76ffc0c");
        assertThat(codec.canonicalValue("null")).isNotEqualTo(codec.canonicalValue("\"\""));
        assertThat(codec.canonicalValue("1")).isNotEqualTo(codec.canonicalValue("\"1\""));
    }
    @Test void duplicateKeysTrailingTokensAndNonFiniteNumbersRejected(){
        var c=new OrderUnappliedClosureCodec();
        for(String s:new String[]{"{\"a\":1,\"a\":2}","null null","NaN","[1,]"})
            assertThatThrownBy(()->c.canonicalValue(s)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unicodeHasNoImplicitNormalization(){
        var c=new OrderUnappliedClosureCodec();
        assertThat(c.canonicalValue("\"é\"")).isNotEqualTo(c.canonicalValue("\"é\""));
        assertThat(c.canonicalValue("{\"日本語\":\"商品\",\"a\":null}"))
            .isEqualTo(c.canonicalValue("{\"a\":null,\"日本語\":\"商品\"}"));
    }

    @Test void rawAuditUtf8BoundaryIsFourMiB(){
        var c=new OrderUnappliedClosureCodec();
        for(int size:new int[]{4_194_303,4_194_304})assertThat(c.canonicalValue("null"+" ".repeat(size-4))).containsExactly((byte)0);
        assertThatThrownBy(()->c.canonicalValue("null"+" ".repeat(4_194_301))).isInstanceOf(IllegalArgumentException.class);
        // Byte limits, not UTF-16 characters: Japanese text plus trailing whitespace.
        String unicode="\"あ\"";int bytes=unicode.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertThat(c.canonicalValue(unicode+" ".repeat(4_194_304-bytes))).hasSize(8);
        assertThatThrownBy(()->c.canonicalValue(unicode+" ".repeat(4_194_305-bytes))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void scalarCanonicalBoundaryIsTwoMiB(){
        var c=new OrderUnappliedClosureCodec();
        for(int size:new int[]{2_097_151,2_097_152})assertThat(c.canonicalValue("\""+"a".repeat(size-5)+"\"")).hasSize(size);
        assertThatThrownBy(()->c.canonicalValue("\""+"a".repeat(2_097_148)+"\"")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void numericLimitsCoverRawDigitsExpandedValueAndExponent(){
        var c=new OrderUnappliedClosureCodec();
        for(int digits:new int[]{999,1000}){
            assertThat(c.canonicalValue("1".repeat(digits))).isNotEmpty();
            assertThat(c.canonicalValue("-"+"1".repeat(digits))).isNotEmpty();
        }
        for(String value:new String[]{"1".repeat(1001),"1e1000","1e-1000","0e1000","0e-1000","1."+"0".repeat(1000)})
            assertThatThrownBy(()->c.canonicalValue(value)).isInstanceOf(IllegalArgumentException.class);
        for(String value:new String[]{"1e999","1e-999","0e999","-0.00","1.0e+2"})assertThat(c.canonicalValue(value)).isNotEmpty();
        assertThat(c.canonicalValue("1e2")).isEqualTo(c.canonicalValue("100.00"));
    }
    @Test void nestingCountsContainersWithRootAtOne(){
        var c=new OrderUnappliedClosureCodec();
        for(int depth:new int[]{63,64}){
            assertThat(c.canonicalValue("[".repeat(depth)+"]".repeat(depth))).isNotEmpty();
            assertThat(c.canonicalValue("[".repeat(depth)+"null"+"]".repeat(depth))).isNotEmpty();
        }
        assertThatThrownBy(()->c.canonicalValue("[".repeat(65)+"]".repeat(65))).isInstanceOf(IllegalArgumentException.class);
        assertThat(c.canonicalValue("null")).hasSize(1);
    }
    @Test void previousAuditSizeCounterexampleNowFitsBothMiBLimits(){
        var c=new OrderUnappliedClosureCodec();String audit="{\"s\": \""+"\\\\".repeat(2_000_100)+"\"}";
        assertThat(audit.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(4_000_209);
        assertThat(c.canonicalValue(audit)).hasSize(2_000_115);
    }

    @Test void combinedArrayCanonicalLimitAppliesEvenWhenEveryChildFits(){
        var c=new OrderUnappliedClosureCodec();
        for(int delta:new int[]{-1,0,1}){
            String input="[\""+"a".repeat(1_000_000)+"\",\""+"b".repeat(1_097_137+delta)+"\"]";
            if(delta<=0)assertThat(c.canonicalValue(input)).hasSize(2_097_152+delta);
            else assertThatThrownBy(()->c.canonicalValue(input)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
