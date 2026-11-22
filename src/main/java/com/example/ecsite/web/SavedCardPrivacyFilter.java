package com.example.ecsite.web;

import java.io.IOException;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Feature139-A, before Security: the card registration page loads payments.js under a nonce CSP, and the Setup Flow
 * return URL (which carries {@code setup_flow_client_secret}) is reduced to {@code setup_flow_id} before anything
 * (saved requests, MVC parameters, application logs) can see the query.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SavedCardPrivacyFilter extends OncePerRequestFilter {

    static final String REGISTER = "/mypage/cards/new";
    static final String RETURN = "/mypage/cards/return";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean register = path.equals(REGISTER);
        boolean returned = path.equals(RETURN);
        if (!register && !returned) {
            chain.doFilter(request, response);
            return;
        }
        String nonce = Base64.getEncoder().encodeToString(
                UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        request.setAttribute("paymentScriptNonce", nonce);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy", "default-src 'none'; base-uri 'none'; frame-ancestors 'none'; "
                + "form-action 'self'; script-src 'nonce-" + nonce + "'" + (register ? " https://js.pay.jp" : "") + "; "
                + (register ? "frame-src https://js.pay.jp; connect-src 'self' https://js.pay.jp https://api.pay.jp; "
                        + "style-src 'self' 'unsafe-inline';" : "style-src 'self';"));
        if (register) {
            chain.doFilter(request, response);
            return;
        }
        Map<String, String[]> parameters = new LinkedHashMap<>();
        String flow = request.getParameter("setup_flow_id");
        if (flow != null && flow.matches("[A-Za-z0-9_-]{1,255}")) parameters.put("setup_flow_id", new String[] { flow });
        var redacted = new HttpServletRequestWrapper(request) {
            @Override public String getQueryString() { return null; }
            @Override public String getHeader(String name) { return "referer".equalsIgnoreCase(name) ? null : super.getHeader(name); }
            @Override public Enumeration<String> getHeaders(String name) {
                return "referer".equalsIgnoreCase(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
            }
            @Override public Enumeration<String> getHeaderNames() {
                return Collections.enumeration(Collections.list(super.getHeaderNames()).stream()
                        .filter(n -> !"referer".equalsIgnoreCase(n)).toList());
            }
            @Override public String getParameter(String name) {
                var values = parameters.get(name);
                return values == null || values.length == 0 ? null : values[0];
            }
            @Override public String[] getParameterValues(String name) {
                var values = parameters.get(name);
                return values == null ? null : values.clone();
            }
            @Override public Enumeration<String> getParameterNames() { return Collections.enumeration(parameters.keySet()); }
            @Override public Map<String, String[]> getParameterMap() {
                var copy = new LinkedHashMap<String, String[]>();
                parameters.forEach((k, v) -> copy.put(k, v.clone()));
                return Collections.unmodifiableMap(copy);
            }
        };
        chain.doFilter(redacted, response);
    }
}
