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
        boolean edit=path.matches("/orders/[^/]+/edit/[^/]+/payment(?:/return|/continue)?");
        boolean editContinue=path.matches("/orders/[^/]+/edit/continue");
        if(!edit&&!editContinue&&!path.matches("/orders/[^/]+/increase/[^/]+/payment(?:/return|/continue)?")
                && !path.matches("/checkout/payment(?:/return|/continue|/wait)?")){
            chain.doFilter(request,response);return;
        }
        boolean returned=path.endsWith("/return")||path.equals("/checkout/payment/wait");
        String nonce=Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        request.setAttribute("paymentScriptNonce",nonce);
        response.setHeader("Cache-Control","no-store");
        response.setHeader("Referrer-Policy","no-referrer");
        response.setHeader("Content-Security-Policy","default-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'; "
            +"script-src 'nonce-"+nonce+"'"+(returned?"":" https://js.pay.jp")+"; "
            +(returned?"":"frame-src https://js.pay.jp; connect-src 'self' https://js.pay.jp https://api.pay.jp; style-src 'unsafe-inline';"));
        if(!returned&&!edit&&!editContinue){chain.doFilter(request,response);return;}
        Map<String,String[]> parameters=new LinkedHashMap<>();
        if(!returned&&"POST".equals(request.getMethod())){
            if(edit&&path.endsWith("/continue")&&"true".equals(request.getParameter("automatic"))){
                parameters.put("automatic",new String[]{"true"});
                String version=request.getParameter("automaticVersion");
                if(version!=null&&version.matches("[0-9]{1,18}"))parameters.put("automaticVersion",new String[]{version});
            }
            var csrf=request.getParameterValues("_csrf");if(csrf!=null)parameters.put("_csrf",csrf.clone());
            var admission=request.getParameter("admission");
            if(editContinue&&admission!=null&&admission.matches("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))parameters.put("admission",new String[]{admission});
        }
        var redacted=new HttpServletRequestWrapper(request){
            @Override public String getQueryString(){return null;}
            @Override public String getHeader(String name){return "referer".equalsIgnoreCase(name)?null:super.getHeader(name);}
            @Override public Enumeration<String> getHeaders(String name){return "referer".equalsIgnoreCase(name)?Collections.emptyEnumeration():super.getHeaders(name);}
            @Override public Enumeration<String> getHeaderNames(){
                return Collections.enumeration(Collections.list(super.getHeaderNames()).stream().filter(n->!"referer".equalsIgnoreCase(n)).toList());
            }
            @Override public String getParameter(String name){var values=parameters.get(name);return values==null||values.length==0?null:values[0];}
            @Override public String[] getParameterValues(String name){var values=parameters.get(name);return values==null?null:values.clone();}
            @Override public Enumeration<String> getParameterNames(){return Collections.enumeration(parameters.keySet());}
            @Override public Map<String,String[]> getParameterMap(){var copy=new LinkedHashMap<String,String[]>();parameters.forEach((k,v)->copy.put(k,v.clone()));return Collections.unmodifiableMap(copy);}
        };
        chain.doFilter(redacted,response);
    }
}
