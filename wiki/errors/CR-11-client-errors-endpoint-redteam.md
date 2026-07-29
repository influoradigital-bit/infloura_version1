# CR-11 — `POST /api/v1/client-errors` red-team

**Auditor:** Kabir (Red-Team / OWASP). **Date:** 2026-07-28. **Branch:** `cr-08-deal-lifecycle-sse`.
**Target commit:** `61d0158`. **Scope:** authorised audit of this team's own codebase. Read-only —
no file was edited, no build was run.

## Verdict

**DOES NOT PASS.**

**1 Blocker, 2 High, 3 Medium, 1 Low.**

The controller itself is well built. Almost every control the contract asks for is implemented
correctly, and several things I expected to break did not (the 16 KB cap in particular is sound
against all four bypass routes I tried on paper). The endpoint fails on its **environment**, not its
logic: the contract names "rate-limit per IP" as a *mandatory* security constraint, that control is
this endpoint's only abuse defence, and in the deployed Hostinger configuration it does not work.

CR-11 did not introduce the rate-limit defect. CR-11 is, however, the first thing to bet its whole
threat model on it.

---

## Correction to the review premise

> "production serves frontend and API from the SAME origin behind Caddy"

**This is not true**, and several conclusions depend on it. `deploy/hostinger/Caddyfile` defines
three *separate* site blocks:

| Block | Upstream |
|---|---|
| `{$APP_DOMAIN}` | `frontend:80` |
| `{$API_DOMAIN}` | `influora-api:8080` |
| `{$AI_DOMAIN}` | `influora-ai:8000` |

`docker-compose.hostinger.yml:92-94` confirms it: `INFLUORA_API_PUBLIC_URL:
https://${API_DOMAIN}/api/v1`, `INFLUORA_WEB_BASE_URL: https://${APP_DOMAIN}`, `CORS_ALLOWED_ORIGINS:
https://${APP_DOMAIN}`. **`app.<domain>` and `api.<domain>` are different origins.** The SPA's own
crash report is a cross-origin request.

### What is actually mitigated, and by what

**Mitigated — by CORS, not by same-origin: browser-driven drive-by posting.** I expected this to be
a finding and it is not. `ClientErrorController` has no `consumes` and no `@RequestBody`; it reads the
raw stream and calls `readTree`, so it will happily parse a body sent as `text/plain`. That means a
`fetch(..., {mode:'no-cors'})`, a `navigator.sendBeacon`, or a cross-site `<form>` POST is a *simple*
request and is not preflighted — the request would reach the server. It is stopped anyway: Spring's
`DefaultCorsProcessor` rejects the **actual** (non-preflight) request too when the `Origin` is not
allow-listed, returning 403 before the controller runs, and all three of those vectors do send
`Origin`. `CorsFilter` also sits ahead of `AuthRateLimitFilter` in the chain, so a rejected
cross-origin flood does not even burn the bucket. Any website in the world therefore *cannot* turn its
visitors into log-injection mules. Good outcome — but it rests entirely on `CORS_ALLOWED_ORIGINS`
being correct, not on the origins being the same.

**Not mitigated by anything: every request that carries no `Origin` header.** curl, python, Go, a
botnet, a headless browser with the header stripped. CORS is not an access control for non-browser
clients, and nothing in this design pretends it is. **This is the entire attack surface for the
Blocker and both Highs below.** Do not let "we're behind Caddy" be read as a mitigation for any of
them.

**Side effect worth knowing:** because the origins differ, the SPA's own report *must* be preflighted
(it sends `application/json`). A CORS misconfiguration would therefore silently kill CR-11
instrumentation while leaving the endpoint fully open to scripted abuse — the exact inversion of what
you want.

---

## BLOCKER

### B-1 — The per-IP rate limit does not function in production. Three independent ways.

`AuthRateLimitFilter.clientIp()` (lines 480-497) is the sole abuse defence for this endpoint. It is
defeated on all three of its possible deployment branches.

**(a) `ForwardedHeaderFilter` has already rewritten the "socket peer" before this filter sees it.**

`docker-compose.hostinger.yml:96` sets `SERVER_FORWARD_HEADERS_STRATEGY: framework`. (The YAML default
in `application.yml:69` is the fail-safe `none`; the deployed compose overrides it.) Under `framework`,
Spring Boot registers `ForwardedHeaderFilter` at `Ordered.HIGHEST_PRECEDENCE`. The Spring Security
chain — which is where `AuthRateLimitFilter` lives, via
`addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)` — registers at
`SecurityProperties.DEFAULT_FILTER_ORDER = -100`. `Integer.MIN_VALUE` runs first.

`ForwardedHeaderFilter` wraps the request in `ForwardedHeaderExtractingRequest`, which **overrides
`getRemoteAddr()`** to return the address parsed from the **left-most** `X-Forwarded-For` entry.

So inside `clientIp()`:

```java
String peer = request.getRemoteAddr();          // <- already the attacker's XFF value
if (peer != null && trustedProxies().contains(peer)) { ... }
return peer;                                     // <- returns the attacker's XFF value
```

The trusted-proxy allow-list is now comparing the spoofed value against itself. It cannot match, so
control falls to the `return peer` fail-safe — which returns the spoofed address. The javadoc's
promise ("a spoofed XFF from an untrusted client can never move a request into a different or fresh
rate-limit bucket") is inverted by a setting in a different file. Rotate the header per request and
there is no limit at all.

**(b) Even without (a): Caddy *appends* to XFF, and this code reads the left-most entry.**

Caddy's `reverse_proxy` appends the immediate peer to any incoming `X-Forwarded-For` rather than
replacing it, and the Caddyfile sets no `header_up X-Forwarded-For {remote_host}` and no
`trusted_proxies`. So an attacker sending `X-Forwarded-For: 9.9.9.9` produces
`X-Forwarded-For: 9.9.9.9, <real-ip>` at the app. `clientIp()` takes the entry **before** the first
comma — the attacker's. Behind exactly one trusted proxy the correct entry to read is the
**right-most**, i.e. the one your own proxy appended. So setting `TRUSTED_PROXIES` correctly does not
save you either; it is the branch that is wrong.

**(c) And if `TRUSTED_PROXIES` is left empty (its default), the limit collapses to a single global
bucket.**

`docker-compose.hostinger.yml:97` passes `TRUSTED_PROXIES: ${TRUSTED_PROXIES}` with the comment
`# verify: docker network inspect (bridge gateway)` — i.e. it must be filled in by hand with a Docker
bridge IP that is not stable across recreates. If it is empty, and (a) does not apply, then
`getRemoteAddr()` is the **Caddy container's** IP for every request on earth. All IP-keyed buckets
key on one string. The crash sink then accepts **30 requests per minute total, for the entire
internet** — one script at 1 req/s permanently 429s every genuine crash report and CR-11 captures
nothing, forever. The same collapse applies to `sensitive` (login: **10/min globally**), `otp`,
`refresh` and `tracking`.

**Blast radius beyond this endpoint.** Every IP-keyed bucket in `AuthRateLimitFilter` — login
brute-force, OTP, refresh, the public webhook/tracking surface — plus the per-IP fallback for all
twelve user-keyed buckets. This is not a CR-11 bug; CR-11 is where it becomes load-bearing.

**Why the tests do not catch it.** `AuthRateLimitFilterClientErrorsBucketTest` calls
`filter.doFilter(...)` directly on a `MockHttpServletRequest` with `setRemoteAddr("10.0.1.5")`. It
proves the bucket arithmetic and nothing about the filter chain that produces `getRemoteAddr()` in a
real container. Both tests pass and both are misleading. Any fix needs a test that sets an
`X-Forwarded-For` header and asserts the key does **not** follow it.

**Fix.** Stop trusting `getRemoteAddr()`. Derive the client IP once, explicitly, from the **right-most**
XFF entry when the true socket peer is a trusted proxy, and read the true socket peer from a source
`ForwardedHeaderFilter` does not rewrite. Prefer pinning it at the edge: add
`header_up X-Forwarded-For {remote_host}` to the API block in the Caddyfile so the header is
*replaced*, not appended — then left-most is correct and (b) closes — and set `TRUSTED_PROXIES`
to the resolved Caddy address (or drop `SERVER_FORWARD_HEADERS_STRATEGY` back to `none` if nothing
needs it, which closes (a)). All three branches need an answer; fixing one leaves the others.

---

## HIGH

### H-2 — Unauthenticated slow-body request → Tomcat thread-pool exhaustion → whole API down

`readCapped()` blocks on `in.read()` on the raw request stream while holding a Tomcat
request-processing thread. Nothing bounds how long that takes:

- Tomcat's `connectionTimeout` (20s default) covers reading request **headers** only.
- `disableUploadTimeout` defaults to `true`, which means **no read timeout is applied during body
  read at all**. `application.yml` sets no `server.tomcat.*` properties, so both defaults stand.
- Caddy streams the request body to the upstream (no `request_buffers` configured) and sets no
  client-side read timeout — the `read_timeout 300s` in the API block is on the `transport http`,
  i.e. reading the *upstream response*, not the client's upload.
- `request_body max_size 1GB` gives an attacker a mile of runway to trickle bytes down.

So each held connection costs one Tomcat thread out of a default pool of 200. Trickle one byte every
few seconds across 200 connections and every endpoint in the API — login, deals, webhooks — stops
responding.

Reachability: at the *intended* 30/min this takes about seven minutes from a single IP (the limiter
counts at filter entry, before the body is read, so slow requests accumulate and are never
re-counted). **Under B-1 it takes seconds.** H-2 and B-1 compound.

This is a property of any POST that reads a body, not of this controller specifically — but this is
the newest unauthenticated one, it needs no HMAC signature (unlike `/webhooks/*`) and no credentials
(unlike `/auth/*`), and its own limiter is broken. It is the cheapest door.

**Fix.** Set `server.tomcat.disable-upload-timeout: false` with a short
`server.tomcat.connection-upload-timeout` (a crash report is ~2 KB; 5s is generous), and/or a Caddy
`servers { timeouts { read_body 10s } }`. Consider capping `request_body max_size` on a per-path
basis so this route does not inherit the 1 GB deliverable-upload allowance.

### H-3 — Log forging works. Stripping control characters is the wrong control for this log format.

The class javadoc claims: *"Truncation also strips control characters (including CR/LF) so a
submitted value can never forge additional WARN log lines."* The claim about **additional lines** is
true. The implied claim — that values cannot be forged — is false, because **the log format is
space-delimited `key=value`, and neither a space nor an `=` is a control character.**

`logback-spring.xml:18`:

```
%d{...} level=%-5level correlationId=%X{correlationId:-n/a} thread=%thread logger=%logger{36} msg=%msg%n%wEx
```

`ClientErrorController:118-120`:

```
[CLIENT_ERROR_REPORT] pathname={} buildId={} userAgent={} message={} stack={} componentStack={}
```

Three separate forging primitives:

**(i) Intra-record field forging.** `pathname` is logged *first*. `pathOnly` cuts at `?`/`#`; it does
not touch spaces. A `pathname` of `/x buildId=trusted-build userAgent=Googlebot message=nothing to
see` produces a record where every subsequent field of the CR-11 marker is attacker-chosen. Anything
that greps `[CLIENT_ERROR_REPORT] pathname=(\S+) buildId=(\S+)` gets the wrong answer.

**(ii) Line-level logfmt forging.** The whole line is logfmt. Injected content lands inside `msg=`,
whose value terminates at the first space for any logfmt parser (Loki's `logfmt`, Vector's
`key_value`, Fluent Bit). An attacker can therefore emit forged `level=`, `correlationId=` and
`logger=` fields into a parsed log record — e.g. forge `correlationId=<a real incident's id>` to
poison triage, or make content appear to originate from
`logger=com.influora.web.AuthController`. Nothing today parses these logs (the file is explicitly
console-only, JSON logging deferred pending an approved dep), so this is **latent** rather than live —
but it becomes live the moment anyone points a shipper at it, and that is exactly what a crash-report
sink invites.

**(iii) `\p{Cntrl}` is US-ASCII only.** Java's POSIX classes are ASCII-scoped: `\p{Cntrl}` is
`[\x00-\x1F\x7F]`. `UNICODE_CHARACTER_CLASS` is not enabled. Survivors:
- **U+0085 NEL, U+2028 LINE SEPARATOR, U+2029 PARAGRAPH SEPARATOR** — not line breaks to
  `BufferedReader.readLine()` or to Docker's json-file driver, but they *are* line terminators to
  `java.util.Scanner` and to Java/JS regex in `MULTILINE` mode. Any parser in that class does see
  forged lines.
- **U+202E RIGHT-TO-LEFT OVERRIDE** (category `Cf`, not `Cc`). A Trojan-Source line: in any bidi-aware
  terminal or log viewer, everything after it renders reversed, so the operator reads something other
  than what was logged. This one needs no downstream parser to be harmful — it attacks the human.

Truncation order is correct (sanitise, then substring), so there is no partial-escape artefact. One
minor note: `substring` can split a surrogate pair and leave a lone surrogate, which would break a
strict JSON log encoder if one is ever added.

**Fix.** Two changes. (1) Quote/JSON-encode each value before it enters the format string — one
`String.valueOf(objectMapper.writeValueAsString(v))` per field, or a small `quote()` helper escaping
`"` and `\` — so a space can never start a new key. (2) Widen the strip from `\p{Cntrl}` to
`[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]`. (1) alone fixes (i) and (ii) and most of (iii); do both.

---

## MEDIUM

### M-4 — Log-volume amplification: unbounded disk on a single-disk box

The client-side dedupe (`ErrorBoundary.tsx:97-99`, `reportedCrashKeys` on `JSON.stringify([message,
pathname])`) is a courtesy to honest browsers and is correctly documented as such. It stops a render
*loop* becoming a flood; it stops nothing else. A script posting distinct `message` values is
unaffected.

A max-size record is ~9 KB of log text (500 + 4000 + 4000 + 200 + 64 + 300 plus the pattern
overhead). Blast radius:

- `logback-spring.xml` has **one appender: CONSOLE.** No file appender, no `SizeAndTimeBasedRollingPolicy`,
  no `totalSizeCap`.
- `docker-compose.hostinger.yml` sets **no `logging:` block** on `influora-api`, so it inherits the
  Docker `json-file` driver, whose default `max-size` is **unlimited**. Logs accumulate in
  `/var/lib/docker/containers/<id>/*-json.log` until the disk is full.
- Everything on this VPS shares that disk: the `mysql_data` volume, `clamav_data`, `caddy_data`
  (issued certs). **Disk-full takes MySQL down**, which is the entire product — and takes Caddy's cert
  renewal with it.

At the *intended* 30/min/IP that is ~390 MB/day from a single IP. Under B-1 it is bounded only by the
attacker's bandwidth.

**Fix.** This is the cheapest real mitigation available and should ship regardless of everything else:
add `logging: { driver: json-file, options: { max-size: "50m", max-file: "5" } }` to the
`influora-api` service (and the others). Then consider sampling or dropping this line to `INFO`.

### M-5 — The contract's own privacy rule is decorative for the data it names

`pathOnly()` is **correct** and I could not bypass it: it cuts at `min(first '?', first '#')`, is
applied before truncation so truncation cannot re-expose a query, and a percent-encoded `%3F` stays
encoded (and `location.pathname` never contains `search` anyway). The implementation of the rule
holds.

The rule itself does not deliver what the contract claims. The contract bans `?deal=<id>` from the
query string — but the app's actual deal route is `/brand/deals/:id` (`App.tsx:344`). The deal id is
in the **path**, and is logged verbatim. The controller's own test asserts exactly this:
`assertTrue(logged.contains("pathname=/deals/abc123"))`. Same for `/brand/campaigns/:id`,
`/brand/campaigns/:campaignId/tracking`, `/brand/analytics/:creatorId`, and `/:handle` (a creator's
public identity). So the specific data the rule exists to keep out of the log is in the log by another
route.

I checked for the worse case and it is not present: **no route carries a token in a path segment** —
there is no `/reset-password/:token`, `/verify-email/:token` or `/invite/:token` route
(grepped `src/` for `path="…:token|reset|verify|invite|unsubscribe…"`, no matches). Those flows are
query- or POST-based. So this is internal-identifier exposure, not credential exposure.

On `stack` / `componentStack` — the harder question asked: React's `componentStack` is component
display names only, negligible. `error.stack` in a production bundle is minified frames plus the
message, and the message is the risk surface. I grepped `src/lib/api.ts` for constructed `Error`s and
found three, none of which embed a response body or token (`api.ts:1617,1639,1693` — status codes and
fixed strings). So there is no *known* live leak. But the invariant holding it up is "nobody ever
throws an `Error` whose message contains a secret", which is enforced nowhere, is one careless
`new Error(\`failed: ${JSON.stringify(response)}\`)` away from being false, and would deposit the
result into a log that anyone on the internet can also write to.

**Fix.** Either correct the contract to say "pathname, which includes deal/campaign/creator ids" and
accept it, or hash/elide the id segments. Independently, add a redaction pass over `message`/`stack`
for token-shaped strings (`eyJ[A-Za-z0-9_-]{10,}\.`, `rzp_(live|test)_\w+`, long hex/base64 runs)
before logging. Cheap, and it converts an unenforced invariant into a control.

### M-6 — "Always 202, never a 4xx" is false one layer up

The controller genuinely cannot return anything but 202 — verified, `report()` catches `Exception`
around everything and returns `ACCEPTED` unconditionally. But `AuthRateLimitFilter` sits **in front**
of it and returns **429 with a JSON error body** for this exact path
(`AuthRateLimitFilter:257-267`). Both the contract ("Never 4xx a malformed report") and the
controller javadoc ("**Always 202, always empty body, never a 4xx**") state an invariant the deployed
system does not hold.

Practical impact is small — the client swallows every failure by construction. The real cost is
diagnostic: reports are silently dropped precisely when volume is high, which is when a render bug is
hitting many users at once, i.e. the failure mode CR-11 was built to catch. Under B-1 branch (c) it is
worse: the global bucket means *any* traffic anywhere silently disables crash capture.

Also: `X-RateLimit-Remaining` is set on the 202 and is CORS-`exposedHeaders` (`CorsConfig.java:26`).
Under branch (c) that turns an unauthenticated endpoint into a live gauge of total site traffic. Minor
on its own; listed because it is the only place I found where this endpoint returns *any* varying
information.

---

## LOW

### L-7 — Matrix-parameter path evades the rate-limit bucket

`bucketFor()` matches `path.equals("/client-errors")` against the percent-decoded raw
`getRequestURI()`. Spring Boot 3's `PathPatternParser` treats matrix variables as segment metadata, so
`POST /api/v1/client-errors;x=1` still routes to the controller — but `stripContext` + `decode` yields
`/client-errors;x=1`, `.equals()` fails, and **no bucket is assigned at all**. The request is
unthrottled.

This is the same class of gap as the earlier `Kabir NEW-1` percent-encoding fix, which added `decode()`
but not `;`-stripping. It affects every literal-path bucket in the filter — `/wallet/withdraw`,
`/webhooks/*`, `/meera/voice/*` — not just this one. (`/auth/` uses `startsWith` and is unaffected.)

Ranked Low only because B-1 already gives an attacker unlimited requests without needing it; if B-1 is
fixed, this becomes the next bypass and should be fixed in the same pass. Needs a live confirm — I
could not run the container.

**Fix.** Strip from the first `;` in each segment before matching, or match on the
`ServletRequestPathUtils`-parsed path rather than the raw URI.

---

## Verified sound — no finding

Listed explicitly because these were the attacks asked for and they did not work. Do not "fix" them.

**The 16 KB cap cannot be defeated by any of the four routes.**
- *Chunked encoding:* `getContentLengthLong()` returns `-1`, `-1 > 16384` is false, control falls to
  the byte-counted read, which is the real enforcement. Correct, and the javadoc says so honestly.
- *Lying (large) `Content-Length`:* short-circuits at the fast path. Free rejection.
- *Lying (small) `Content-Length`:* Tomcat delivers only `Content-Length` bytes to the stream, so the
  surplus never reaches the handler.
- *Compressed body:* Tomcat does **not** decompress request bodies (`server.compression` is
  response-only). A gzip payload is counted as compressed bytes and then fails `readTree` as malformed
  JSON. **There is no decompression-bomb path.**
- Buffer is `MAX_BODY_BYTES + 1`, so memory is bounded at ~16 KB regardless of what is sent.

**No JSON-parser DoS — but only because of the version.** 16 KB of `[[[[…` is ~16 384 levels of
nesting. Spring Boot 3.3.5 (`pom.xml:10`) resolves Jackson 2.17, whose
`StreamReadConstraints.maxNestingDepth` defaults to 1000 — so this throws a
`StreamConstraintsException` (an `Exception`, caught, logged as "malformed JSON body"). On Jackson
< 2.15 the same input is a `StackOverflowError`, which is an `Error`, **not** an `Exception`, and would
escape *both* `catch (Exception)` blocks — producing a 500 and violating the always-202 rule. Worth a
one-line comment so a future dependency downgrade cannot silently reintroduce it.

**Auth interaction is clean.** The controller never reads `Authorization`, never resolves a principal,
never touches the database. A valid token changes nothing; a forged, expired or garbage token changes
nothing — `client-errors` is not in `isUserKeyedBucket()`, so `extractUserId()` is never even called
on this path and its JWT parsing is not reachable here.

**The `permitAll` punches no hole.** `requestMatchers(HttpMethod.POST, "/client-errors")` is
method-scoped and pinned to that exact literal path — no `**`, no `*`. `/client-errors/anything` and
`GET /client-errors` both fall through to `anyRequest().authenticated()`. It is ordered before
`anyRequest()` and before the `/admin/**` `hasRole("ADMIN")` matcher, so it cannot shadow either.
Matches the existing `/portfolio/*/contact` precedent.

**No oracle.** The handler performs zero state lookup — no deal, user, workspace or route existence is
consulted, nothing is persisted, no downstream service is called. There is nothing for a timing or
behavioural difference to reveal. The only observable variance is `readTree` cost as a function of
body size, which is attacker-supplied and therefore already known to the attacker. The
`X-RateLimit-Remaining` counter noted in M-6 is the sole exception and reveals traffic volume, not
existence.

**Response never echoes submitted content.** `ResponseEntity<Void>`, empty body, verified in code and
asserted in `ClientErrorControllerTest.response_neverEchoesSubmittedContent`.

**Server-side re-truncation is real and correctly ordered.** Sanitise then truncate, applied to all six
fields unconditionally, independent of the client caps in `api.ts`. `textOf()` correctly drops
wrong-typed fields (a JSON number where a string is expected) rather than coercing or throwing.

**The always-202 discipline inside the controller is genuinely well done.** Two nested layers, the
outer one deliberately logging only `ex.getClass().getSimpleName()` and never the exception message —
which is the right call, because a Jackson parse exception's message embeds the offending input. That
detail was not an accident and it deserves saying.

---

## Fix order

1. **B-1 — client IP derivation.** Everything else is either gated by it or made trivial by it.
2. **M-4 — Docker log rotation.** One block in the compose file. Removes the disk-full path that takes
   MySQL with it.
3. **H-2 — upload timeout.** Two properties.
4. **H-3 — quote the logged values, widen the character strip.**
5. **M-5 / M-6 / L-7.**

Items 2 and 3 are configuration and can ship immediately, independently of the code changes.
