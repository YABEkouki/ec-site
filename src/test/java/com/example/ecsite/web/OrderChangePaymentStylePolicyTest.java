package com.example.ecsite.web;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;

class OrderChangePaymentStylePolicyTest {
    @ParameterizedTest @ValueSource(strings={"/orders/1/edit/receipt/payment","/orders/1/increase/operation/payment","/checkout/payment"})
    void widgetAllowsOnlySameOriginCssInAdditionToExistingInlineStyle(String path)throws Exception{
        var result=filter(path);
        assertThat(result.getHeader("Content-Security-Policy")).contains("style-src 'self' 'unsafe-inline';");
        assertThat(withoutStyleAndNonce(result)).isEqualTo("default-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'; script-src 'nonce-X' https://js.pay.jp; frame-src https://js.pay.jp; connect-src 'self' https://js.pay.jp https://api.pay.jp;");
        privateHeaders(result);
    }
    @ParameterizedTest @ValueSource(strings={"/orders/1/edit/receipt/payment/return","/orders/1/increase/operation/payment/return","/checkout/payment/return","/checkout/payment/wait"})
    void returnAllowsSameOriginCssWithoutAllowingInlineStylesOrProviderScripts(String path)throws Exception{
        var result=filter(path);
        assertThat(result.getHeader("Content-Security-Policy")).contains("style-src 'self';");
        assertThat(withoutStyleAndNonce(result)).isEqualTo("default-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'; script-src 'nonce-X';");
        privateHeaders(result);
    }
    @Test void unrelatedPagesDoNotAcquirePaymentCsp()throws Exception{
        for(String path:List.of("/orders/1/edit","/orders/1/edit/result","/checkout/complete","/css/style.css"))
            assertThat(filter(path).getHeader("Content-Security-Policy")).isNull();
    }
    private MockHttpServletResponse filter(String path)throws Exception{
        var request=new MockHttpServletRequest("GET",path);var response=new MockHttpServletResponse();
        new OrderChangePaymentPrivacyFilter().doFilter(request,response,(r,s)->{});return response;
    }
    private String withoutStyleAndNonce(MockHttpServletResponse r){return r.getHeader("Content-Security-Policy").replaceAll("style-src [^;]+; ?","").replaceAll("nonce-[^']+","nonce-X").trim();}
    private void privateHeaders(MockHttpServletResponse r){assertThat(r.getHeader("Cache-Control")).isEqualTo("no-store");assertThat(r.getHeader("Referrer-Policy")).isEqualTo("no-referrer");}
}
