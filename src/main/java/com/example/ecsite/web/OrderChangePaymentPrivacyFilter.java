package com.example.ecsite.web;

import java.io.IOException;
import java.util.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Before Security: do not retain SDK credentials in saved requests, MVC parameters or application query logs.
 * Native connector/reverse-proxy access logs see the original request and need their own query-free formats. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OrderChangePaymentPrivacyFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        if(!path.matches("/orders/[^/]+/increase/[^/]+/payment(?:/return|/continue)?")
                && !path.matches("/checkout/payment(?:/return|/continue)?")){
            chain.doFilter(request,response);return;
        }
        boolean returned=path.endsWith("/return");
        String nonce=Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        request.setAttribute("paymentScriptNonce",nonce);
        response.setHeader("Cache-Control","no-store");
        response.setHeader("Referrer-Policy","no-referrer");
        response.setHeader("Content-Security-Policy","default-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'; "
            +"script-src 'nonce-"+nonce+"'"+(returned?"":" https://js.pay.jp")+"; "
            +(returned?"":"frame-src https://js.pay.jp; connect-src 'self' https://js.pay.jp https://api.pay.jp; style-src 'unsafe-inline';"));
        if(!returned){chain.doFilter(request,response);return;}
        var redacted=new HttpServletRequestWrapper(request){
            @Override public String getQueryString(){return null;}
            @Override public String getHeader(String name){return "referer".equalsIgnoreCase(name)?null:super.getHeader(name);}
            @Override public Enumeration<String> getHeaders(String name){return "referer".equalsIgnoreCase(name)?Collections.emptyEnumeration():super.getHeaders(name);}
            @Override public Enumeration<String> getHeaderNames(){
                return Collections.enumeration(Collections.list(super.getHeaderNames()).stream().filter(n->!"referer".equalsIgnoreCase(n)).toList());
            }
            @Override public String getParameter(String name){return null;}
            @Override public String[] getParameterValues(String name){return null;}
            @Override public Enumeration<String> getParameterNames(){return Collections.emptyEnumeration();}
            @Override public Map<String,String[]> getParameterMap(){return Map.of();}
        };
        chain.doFilter(redacted,response);
    }
}
