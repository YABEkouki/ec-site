# Feature125 isolated display preview

This test-only preview uses eight production Thymeleaf templates and sixteen
synthetic cases. It is not a customer session or a payment simulator. Real
SecurityConfig and OrderChangePaymentPrivacyFilter run inside a MockMvc slice;
application Services are mocks. No DataSource, payment gateway or application
transaction bean is present. Fixture IDs start at 900001 and do not represent
orders 8–12. No development DB or PAY.JP connection is needed or permitted.

## Supported preparation and shutdown

From the repository:

```sh
scripts/run-f125-display-preview.sh --check
scripts/run-f125-display-preview.sh --serve
```

The launcher copies src/scripts/.mvn/pom.xml/mvnw into a private /tmp directory.
It does not copy .env, .git, target or provider credentials. Maven outputs only to
that copy. Inherited application/provider/JVM overrides are removed. Preparation
failure stops without a DB fallback. --check tests and exits; --serve prints its
random loopback origin, manifest and registered routes. Ctrl-C normally stops
only its display server and removes the private copy. It never stops the live
EC app. Use a separate terminal and preserve the printed origin while inspecting.

The HTTP server serves registered immutable HTML, CSS and favicon only, on
127.0.0.1 and a random port distinct from 8080. GET/HEAD are supported; POST and
other methods return 405; unknown routes return 404 and traversal returns 400.
There is no MVC/DB/provider proxy. Exported headers, including CSP, are replayed
without reconstruction. HTML script nonces correspond to captured CSP nonces.
No real session cookies are exported. Synthetic authentication is display data.

## JavaScript-disabled Chrome inspection only

Use a newly created temporary Chrome profile/context, never a customer profile.
Keep its debugging endpoint on loopback. Disable extensions, sync, background
networking and component updates. No card entry, clicks on confirmation buttons,
provider SDK loading or requests to the running app are part of this procedure.
The operator creates and later normally closes only that temporary Chrome.

```sh
node src/test/js/display/inspect-display.cjs <temporary-browser-ws-endpoint> <display-origin> <pages.json> /tmp/display-disabled
```

An explicit `disabled` before the output directory is also accepted. The default
is disabled. Any other mode, including the former `blocked`, is rejected with a
"JavaScript-enabled display inspection is not certified" error BEFORE fixture
reads, browser connections or target creation. There is no enable/unsafe override.
The launcher only prepares/serves fixtures; it never launches Chrome or invokes
the experimental guard automatically.

The supported driver disables page scripts before navigation and keeps Fetch
interception restricted to registered same-origin GET/HEAD, bypassing cache and
service workers. It records 1280px and 375px screenshots and DOM measurements.
It does not invoke the experimental child-target blocker. When done, the driver
disposes its synthetic context; normally close temporary Chrome, then Ctrl-C the
preview server. Check the server's received-POST counter (zero during display
inspection). Never terminate the running EC application as cleanup.

## Evidence and limits (B-2G-2D through B-2G-2G)

- MockMvc/server tests covered sixteen HTML cases plus CSS/favicon, real
  SecurityConfig/PrivacyFilter, CSRF hidden fields, CSP/nonce matching, immutable
  headers, GET/HEAD delivery, POST 405, 404 and traversal rejection.
- B-2G-2E recorded sixteen cases at 1280px and 375px: 32 JavaScript-disabled
  screenshots. Their scope is layout, wrapping, state text and manual fallback
  visibility; keyboard/visual findings must be read from that inspection record.
  They do not prove JavaScript execution or busy-state transitions.
- JavaScript-enabled real Chrome verification is INCOMPLETE. B-2G-2E miscounted
  data: error-page images as external responses. The common classifier now
  distinguishes HTTP(S), WS(S), internal data/blob/about/chrome/devtools and unknown
  schemes. Internal descendants receive no network exemption; unknown schemes
  invalidate the guard's safety check. Internal payloads are not logged.
- B-2G-2F stopped on unpaused page/browser_ui targets and a target-close failure.
  IDs/context/timestamps/protocol-error details were insufficient to determine
  each target's origin or the exact failure. Child-target pre-execution stopping
  and WebSocket interception are NOT certified. Type names alone prove no safety.
- The experimental helpers in network-guard.cjs remain unit-test subjects only.
  Their child blocker closes rather than resumes children and invalidates the run.
  They are NOT a certified browser egress boundary and must not be used to enable
  Feature125 page scripts. Only installDisplayGuard with javascript:false is used
  by the normal inspection command.
- Real PAY.JP Widget appearance, card entry and 3DS behavior remain UNVERIFIED by
  this preview. No SDK substitute is presented as a real Widget. Earlier live TEST
  scenarios, if used, are separate evidence and do not certify this guard.

Counters are distinct: intercepted postAttempts/externalAttempts are intent;
requestsReleased counts CDP decisions; response events are observations. None is
an OS-level proof of zero egress. Server received POSTs and an independent owned
observer measure only their own endpoints. No process-wide egress confinement was
established. Saved headers/static nonce matching do not prove all runtime CSP
behavior. Never test the guard against PAY.JP or third-party destinations.

Completion therefore adopts method C: existing JavaScript-disabled display
results plus Java/MockMvc/JavaScript regression evidence, with enabled-browser
and real Widget limits explicitly retained. No worker/scheduler/migration or
production behavior is introduced by this test infrastructure.
