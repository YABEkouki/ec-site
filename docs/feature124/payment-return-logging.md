# Feature124 C2: payment callback deployment checks

This is a deployment prerequisite, not evidence that external infrastructure has been configured.
Never use a real client secret as a logging test value.

## Application boundary

- Dedicated callbacks: `/orders/{orderId}/increase/{operationId}/payment/return` and `/checkout/payment/return`.
  Initial checkout continues via the clean CSRF-protected `/checkout/payment/continue` POST.
- The early servlet filter removes its query, parameter map and Referer before Security/MVC.
  In particular, Security's saved login request contains only the clean path.
- The callback has no external scripts, stylesheet, images or analytics. Its nonce script immediately
  removes query and fragment using `history.replaceState`. Its response is `no-store` and `no-referrer`.
- The callback ignores provider-supplied Flow IDs, statuses and client secrets. A CSRF-protected POST
  retrieves the persisted Flow on the server and runs the existing validated business flow.
- The card page contains a secret in its response body only. It is not a session/flash/DB value;
  its model object's `toString()` is redacted. Do not enable HTTP response-body recording for this page.
- The embedded Tomcat access-log format uses method + `%U` (path) + protocol, never `%r`, `%q` or
  `%{Referer}i`. `spring.mvc.log-request-details=false` must remain effective in deployed profiles.
  Check environment/profile overrides as well as the checked-in properties.
- Set `app.base-url` to the trusted root HTTPS application origin (HTTP localhost is development only).
  Do not derive a return URL from the request Host or forwarded headers.

## Reverse proxy, CDN, WAF, load balancer, APM and collector

The first HTTP callback reaches these BEFORE the application. JavaScript and servlet wrapping do not
redact their logs. Review every hop and telemetry exporter, including error, security, sampled request,
breadcrumb, trace/span URL, session replay and response-body records.

An Nginx query-free access format can be:

```nginx
log_format payment_path_only '$remote_addr [$time_local] "$request_method $uri $server_protocol" $status $body_bytes_sent';
```

Do not use `$request`, `$request_uri`, `$args`, `$query_string` or `$http_referer` in callback records.
Nginx error messages can also include a raw request line. If field-level redaction before persistence
is unavailable, an illustrative callback location is:

```nginx
location ~ ^/(orders/[0-9]+/increase/[0-9a-fA-F-]+/payment/return|checkout/payment/return)$ {
    access_log /var/log/nginx/payment-return.log payment_path_only;
    error_log /dev/null;
    proxy_set_header Host $host;
    proxy_pass http://ecsite_backend;
}
```

Adapt this to the actual routing. Suppressing the location error log trades away those diagnostics;
use path-only status/latency metrics instead. It does not control a fronting WAF/CDN or earlier parsing
errors. Those require their own policy. APM must not record full callback URLs, query, Referer or card
response bodies. Redact before storage/export rather than after ingestion. Configure cache bypass
for all of these payment routes. Do not add analytics to the return/card templates.

## Required acceptance test in the actual TEST deployment

1. Audit effective access/error/APM/CDN/WAF configuration and profile overrides at every hop.
2. With a synthetic marker such as `sdk_callback_log_probe_2026`, request the callback with
   `?payment_flow_client_secret=<marker>` (literal marker, never a real secret). Test authenticated,
   unauthenticated/login, wrong owner, missing operation and upstream-error paths. Include a synthetic
   Referer containing the marker too.
3. Search application, connector, proxy, WAF, CDN, collector and APM records and retained saved requests
   for the marker. None may persist. Confirm useful path/status metrics still exist.
4. In browser DevTools confirm the callback response has the security/cache headers, loads no external
   resources, removes query and fragment, and submits only a clean CSRF-protected continuation URL.
5. Exercise the actual PAY.JP TEST Widgets card entry and required 3DS, including return navigation,
   cookie/login expiry and CSP violations. Confirm `confirmPayment` receives only a clean trusted return
   URL. Verify card responses are not cached or captured by APM/session replay.
6. Repeat marker checks after configuration changes. If any hop cannot be verified, do not enable the
   customer payment entry in that environment. Do not substitute JavaScript URL removal for this check.

## Deliberate recovery limits

A first card screen is durably issued once per operation. A lost response, reload, SDK load failure,
validation error or ambiguous confirm result does not reissue it or generate a new key. The existing
Flow can be observed; reservations/Guard remain until a verified terminal/recovery outcome. Normal
Widgets confirmation performs 3DS. Interrupted `requires_action` is shown as waiting for the original
SDK flow; automatic `handleNextAction`, return-URL updates and reconfirm are not guessed here.

Expired, financially-unstarted RESERVED operations are swept in bounded batches by the existing
payment reconciliation scheduler (when enabled). Every release rechecks state, decision and absence
of Payment/financial request/transaction under locks. No started financial operation is expired by it.

## Initial checkout recovery and maintenance mode

Initial checkout stores only order/payment IDs in its session. Card display checks ownership and the
exact existing Payment before GET; it never creates a Flow. Only `requires_payment_method` displays
Widgets. Processing, interrupted action and confirmation states remain observation-only. The initial
card screen uses Web Locks plus a non-secret localStorage attempt marker to prevent concurrent
same-origin tabs from confirming again after an attempted confirmation, including ambiguous errors.
If Web Locks/storage is unavailable, the screen fails closed and offers server observation. This is
not cross-device confirmation fencing; no browser marker is authorization or evidence of success.

Before starting the application for controlled TEST verification, disable all four background paths:

```properties
app.payment.reconciliation.enabled=false
app.payment.discrepancy-audit.enabled=false
app.payment.discrepancy-audit.notification.enabled=false
app.payment.discrepancy-audit.notification.manual-resend-scheduler-enabled=false
```

The last flag defaults to true for compatibility and disables both private scheduler startup and its
entire tick, including expired manual-resend recovery. Disabling only notification delivery does not
stop that recovery. Reconciliation off also disables the automatic RESERVED-expiry sweep. These flags
do not disable explicit customer/admin operations. Verify effective overrides before enabling TEST.
