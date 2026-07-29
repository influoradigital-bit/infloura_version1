# CR-11 B-1 — re-review of the X-Forwarded-For fix

**Auditor:** Kabir (Red-Team / OWASP). **Date:** 2026-07-28. **Branch:** `cr-08-deal-lifecycle-sse`.
**Target commit:** `3de077d`. **Original finding:** `wiki/errors/CR-11-client-errors-endpoint-redteam.md`
B-1. **Scope:** authorised re-review of this team's own codebase. Read-only — no file edited, no
build run, no Maven invoked.

## Verdict

**PASSES.**

**0 Blockers. 2 High, 2 Medium, 2 Low.**

B-1 is closed. `native` is the correct fix, the right-to-left walk lands where the commit says it
lands, the hand-rolled allow-list was correctly identified as the wrong control in the wrong place,
and **there is no scheme/host/cookie regression** — I went looking for a traded bug and did not find
one. None of the six findings below reopens the Blocker.

Two things are worth saying up front because they are the difference between "the code is right" and
"the system is safe":

1. The fix's correctness rests on a premise about the deployed box that is **stated as settled and is
   not verified** (H-2). If that premise is false, B-1 partially reopens. It is one check.
2. The commit message claims the dead control was "DELETED, not left unused ... which is the whole
   lesson of CR-33." **Half of it was.** The YAML half — the half an operator actually reads — is
   still there, still describing itself as a live security control (H-1).

---

## Q1 — Does `native` actually close it? Yes. Traced against this topology.

I traced Tomcat's `RemoteIpValve.invoke()` against the real Caddyfile
(`deploy/hostinger/Caddyfile`) and the real compose topology, not the general case.

**Topology as built:** `deploy/hostinger/Caddyfile` has no `header_up X-Forwarded-For` and no
`trusted_proxies` on the `{$API_DOMAIN}` block, so Caddy's `reverse_proxy` **appends** its view of
the peer to whatever XFF arrived. Caddy connects to `influora-api:8080` over the default compose
bridge network, so the API's socket peer is a `172.x` container address.

**The walk, concretely.** Attacker sends `X-Forwarded-For: 9.9.9.9`. The API receives:

```
socket peer:      172.18.0.5      (Caddy container)
X-Forwarded-For:  9.9.9.9, 203.0.113.7    (Caddy appended the true client)
```

`RemoteIpValve`:

1. **Gate first.** `internalProxies.matcher(originalRemoteAddr).matches()` — `172.18.0.5` matches, so
   the header is consulted at all. This gate is the thing `ForwardedHeaderFilter` never had, and it
   is why the whole class of bug goes away: from an untrusted peer, XFF is not read at any point.
2. **Walk `idx` from `length-1` downward.** `idx=1` → `203.0.113.7`. `remoteIp` is assigned
   *before* the classification test, then the test runs: not internal, no `trustedProxies` regex
   configured (Spring Boot leaves it null by default, and this commit does not set it), so the
   `else` branch fires — `idx--; break;`.
3. `request.setRemoteAddr("203.0.113.7")`.

**The client-prepended `9.9.9.9` can never win**, because the loop breaks on the first non-internal
entry encountered from the right, and Caddy guarantees at least one such entry to the right of
anything the client wrote. Confirmed: the commit's claim is accurate.

Two consequences worth recording, neither of them a defect:

- The valve **rewrites** the XFF header to the unconsumed left-hand entries. Post-valve, the request's
  `X-Forwarded-For` header reads `9.9.9.9` — pure attacker data. Any future code that reads the
  header directly gets a poisoned value. I grepped: **no production code reads `X-Forwarded-For`**
  (only javadoc references in `AuthRateLimitFilter`, `AdminAuditLogService`,
  `WooCommerceWebhookController`). Clean today; a trap for tomorrow, which the javadoc at
  `AuthRateLimitFilter.java:495-497` already warns against.
- The valve is an **Engine** valve (`factory.addEngineValves`), so it runs strictly before the servlet
  filter chain — ahead of `CorsFilter`, ahead of the Security chain at `-100`, ahead of
  `AuthRateLimitFilter`. Ordering is not in question the way it was under `framework`.

---

## Q2 — The `internal-proxies` regex. Right call, with one unverified premise and one accepted trade.

**Is it too broad?** For internet-reachable traffic: no, and for a reason the commit does not state —
**the API port is not published.** I checked both compose files: `ports:` appears only under the
`caddy` service (`docker-compose.hostinger.yml:25`, `docker-compose.test.yml:35`). `influora-api` has
no `ports:` and no `expose:`. So "a client that can reach the API container directly, bypassing
Caddy" does not exist from the internet. The RFC1918 breadth is therefore not reachable by an
external attacker, and tightening it to a specific Caddy IP would reintroduce exactly the
"not stable across recreates" fragility that made `TRUSTED_PROXIES` useless. Keeping Tomcat's default
is the correct call.

The regex string itself is a faithful copy of Spring Boot's `ServerProperties.Tomcat.Remoteip`
default. I checked the two non-obvious ways it could have silently broken and neither did:

- **YAML plain scalar.** The value contains `{`, `}`, `|`, `\` and `:`. In block context a plain
  scalar's flow-indicator restrictions apply only to the first character (`$`), and none of the
  colons is followed by a space, so it parses as one scalar. Backslashes are not escape-processed in
  plain scalars, so `\.` and `\d{1,3}` survive intact. Using a plain scalar rather than a
  double-quoted one was necessary and correct.
- **`${VAR:default}` with colons in the default.** `PropertyPlaceholderHelper` splits on the **first**
  `:` and takes `substring(sep+1)` as the default — the rest of the string, colons included. So
  `0:0:0:0:0:0:0:1|::1` is preserved. This is the kind of thing that fails silently and it does not
  fail here.

See H-2 and M-4 for the residual trust this regex grants.

---

## Q3 — Did removing the hand-rolled allow-list break anything? No functional consumer. But it was not actually removed.

**Java side:** clean. `influora.security.trusted-proxies` had exactly one consumer,
`AuthRateLimitFilter.trustedProxiesRaw`, and it is gone along with `trustedProxies()` and the cached
`Set`. No other class binds the key. No other class reads `TRUSTED_PROXIES`. Nothing referenced
`clientIp()` beyond `rateLimitKey()`. Nothing else in `influora-api/src` breaks.

**No deploy depended on it.** Its default was empty, and when set it was actively wrong (it gated
left-most-entry parsing, which was the wrong entry). Removing it removes a broken control, not a
working one. The env var going inert is harmless, and the commit's reasoning for leaving
`TRUSTED_PROXIES` in the compose files (rollback safety) is sound.

**But see H-1** — the property definition and its security comment survive in `application.yml`.

---

## Q4 — Scheme/host regression? None. Checked properly, not assumed.

This was the most likely place to trade one bug for another, and it did not happen.

**`native` preserves X-Forwarded-Proto/Host/Port handling.** Spring Boot's
`TomcatWebServerFactoryCustomizer.customizeRemoteIpValve` — which is what `forward-headers-strategy:
native` triggers — explicitly configures the valve with `protocolHeader` defaulted to
`X-Forwarded-Proto`, `protocolHeaderHttpsValue` `https`, `hostHeader` `X-Forwarded-Host`, `portHeader`
`X-Forwarded-Port`, `remoteIpHeader` `X-Forwarded-For`. On `X-Forwarded-Proto: https` the valve calls
`request.setSecure(true)`, sets the scheme to `https`, and — with no `X-Forwarded-Port` present, which
is Caddy's behaviour — sets the server port to the configured `httpsServerPort` (443). That is
correct for redirect-URL generation, and strictly better-defined than what `framework` did.

**Nothing in this app derives a URL from the request anyway.** I grepped
`influora-api/src/main/java` for `ServletUriComponentsBuilder`, `fromCurrentRequest`, `fromRequest(`,
`getRequestURL`, `isSecure()`, `getScheme()`, `getServerName()`, `sendRedirect`, `requiresChannel` —
**zero hits.** The only `getScheme()` calls are on `java.net.URI` objects parsed from configured
strings, not from requests. Every absolute URL is built from `influora.web-base-url` /
`influora.api.public-url` (`INFLUORA_WEB_BASE_URL` / `INFLUORA_API_PUBLIC_URL`).

**Secure-cookie flags are config, not `isSecure()`.** `AuthCookieService.java:35` and
`AdminAuthCookieService.java:37` bind `influora.auth.refresh-cookie.secure` /
`admin-refresh-cookie.secure` as `@Value` booleans, and `SecretsStartupValidator.validate()` **fails
boot** outside dev if either is false (`SecretsStartupValidator.java:385-400`). Compose sets
`AUTH_REFRESH_COOKIE_SECURE: "true"`. The cookie flag has no dependency on the forwarded-headers
strategy in either direction.

**The one real regression risk does not apply.** `native` does **not** honour `X-Forwarded-Prefix`;
`framework` does. If Caddy stripped a path prefix, switching would have broken URL generation. It
does not: the prod Caddyfile proxies a whole host block with no path manipulation, and the test box
uses `handle /api/*` — **not** `handle_path` — so the `/api/v1` context path arrives intact
(`docker-compose.test.yml:16-20`). The test box additionally pins `header_up X-Forwarded-Proto http`,
which the valve handles correctly (not equal to `https` ⇒ `setSecure(false)`, scheme `http`, port 80).

`native` also drops RFC 7239 `Forwarded` header support that `framework` had. Caddy does not emit
`Forwarded`. No impact.

**Conclusion: no cookie bug, no redirect bug, no scheme bug. You did not trade one for the other.**

---

## Q5 — The audit-log half. The claim is true. The comment is not sufficient, but not for the reason you'd expect.

**Is `AdminAuditLogService.clientIp()` (`:578`) actually correct now?** Yes. `RemoteIpValve` mutates
the Coyote request in place, so `getRemoteAddr()` returns the resolved client IP for *every*
downstream consumer, not just the filter. And in the non-proxied case (untrusted peer) it returns the
true socket peer, which is also correct. Both branches are right. The commit's characterisation of
this as "the worse half" — forged forensic records rather than a rate-limit bypass — is correct and
was the right thing to call out.

**Does it need its own assertion?** Not the obvious one. A unit test asserting that a method whose
body is `return request.getRemoteAddr()` returns `getRemoteAddr()` is tautological; it would pass on
the vulnerable build too, because under `framework` this method was *also* just returning
`getRemoteAddr()` and the poisoning happened upstream. **The correctness of this method is now 100% a
property of `application.yml`, and 0% a property of this file.** So the assertion it needs is not
here — it is a test of the valve semantics (Q6) plus a startup gate on the strategy (M-3). The
comment is doing the only job a comment can do; the missing controls are elsewhere.

---

## Q6 — The test. The honesty is real. The false confidence is subtler than the caveat covers.

**Credit where due.** The scope caveat in the class javadoc
(`AuthRateLimitFilterSpoofedForwardedForTest.java:30-36`) is honest and correctly stated: it proves
the filter's contract, cannot exercise `RemoteIpValve`, and says so rather than letting a green suite
imply more. The second test (`aDifferentPeerKeepsItsOwnBudget`) is the right companion — it
discriminates the guard from the opposite failure mode (global-bucket collapse) rather than merely
detecting change. Revert-proving it is the correct standard. This is better test hygiene than most of
what I review here.

**But there is a false-confidence residue the caveat does not name.** The test guards the half that
never broke. The original defect was *not* "someone added header parsing to `clientIp()`" — it was
one word in a compose file. Under `framework`, `AuthRateLimitFilter` never saw a meaningful XFF at
all: `getRemoteAddr()` was already poisoned and the raw header was already stripped. **This test,
written against the pre-fix build, would have passed.** It commemorates a bug it could not have
caught. That should be in the javadoc, because the next reader will otherwise treat it as regression
cover for B-1 and it is not.

**What a real test of the valve needs — and it is cheap, contrary to the commit's framing.**

`org.apache.catalina.filters.RemoteIpFilter` is the filter twin of `RemoteIpValve`; they share the
identical resolution algorithm. It is already on the classpath via `tomcat-embed-core` (pulled by
`spring-boot-starter-tomcat`), and it works with `MockHttpServletRequest` / `MockFilterChain` in a
plain JUnit test — **no Spring context, no embedded container, no Docker**. That last point matters
here specifically: per `ConfigurationPropertiesRegistrationTest`'s own javadoc, *every*
`@SpringBootTest` in this repo errors out on testcontainers/Docker discovery in this environment, so
a `@SpringBootTest(webEnvironment = RANDOM_PORT)` approach would be dead on arrival. `RemoteIpFilter`
sidesteps that entirely.

Load the `internal-proxies` string **from `application.yml` itself** (`YamlPropertySourceLoader` on
the classpath resource) rather than duplicating it, so the test binds shipped config to asserted
behaviour, then assert three cases:

| peer | `X-Forwarded-For` | expected `getRemoteAddr()` | what it proves |
|---|---|---|---|
| `172.18.0.5` | `9.9.9.9, 203.0.113.7` | `203.0.113.7` | right-to-left; the Caddy-appended entry wins |
| `203.0.113.7` | `9.9.9.9` | `203.0.113.7` | untrusted peer ⇒ header ignored entirely |
| `172.18.0.5` | `9.9.9.9, 172.17.0.1` | `9.9.9.9` | **documents H-2's failure mode** rather than hiding it |

Add a fourth asserting `application.yml`'s `server.forward-headers-strategy` default is literally
`native`, and the config-drift gap closes too.

---

## HIGH

### H-1 — `influora.security.trusted-proxies` was not deleted. The YAML half survives, still describing itself as a live security control.

The commit message states: *"The hand-rolled allow-list (`influora.security.trusted-proxies` + its
cached Set) is DELETED, not left unused — a security control that reads as if it still protects
something is worse than none, which is the whole lesson of CR-33."*

`influora-api/src/main/resources/application.yml:142-147` still reads:

```yaml
    # [SEC: Wave-1 S6 / Kabir audit H-4] Comma-separated allow-list of trusted reverse-proxy
    # socket addresses. See AuthRateLimitFilter#clientIp — empty by default (fail-safe): with no
    # trusted proxy configured, X-Forwarded-For is ignored entirely and the raw socket peer is
    # used as the rate-limit key. Set to the LB/ingress IP(s) in any real deploy that sits behind
    # a reverse proxy.
    trusted-proxies: ${TRUSTED_PROXIES:}
```

Every sentence of that is now false. It cross-references `AuthRateLimitFilter#clientIp`, a method
that no longer reads it. It claims a fail-safe behaviour the property no longer produces. It instructs
operators to *"Set to the LB/ingress IP(s) in any real deploy that sits behind a reverse proxy"* — an
instruction to configure a no-op. `docker-compose.hostinger.yml:102` still carries
`TRUSTED_PROXIES: ${TRUSTED_PROXIES}   # verify: docker network inspect (bridge gateway)`, i.e. a
deploy-time chore for a dead key.

The Java comment at `AuthRateLimitFilter.java:207-215` explains that the env var is deliberately left
in the *compose* files for rollback safety. That reasoning is sound and I accept it. It does not
extend to the `application.yml` property definition and its five-line security narrative, which is
precisely the artefact CR-33 was about — and the artefact this commit says it removed.

**Severity is false-assurance, not exploitability.** Nothing is exploitable because of this. It is
High because the next operator or auditor who reads `application.yml` — which is the file you read to
understand this system's security posture — will believe a trusted-proxy allow-list is protecting XFF
handling, and because a reviewer signing off on the commit message would be signing off on a
statement that is not true.

**Fix.** Delete lines 142-147. If the env var must survive for rollback, that is already handled by
its presence in the compose files; it does not need a YAML binding. Six lines.

### H-2 — `native` is correct **only if Caddy sees a non-RFC1918 client address**, and that is asserted, not verified.

`application.yml:86-88` says the default regex *"is correct here and needs no per-deploy tuning."*
That is true if and only if the address Caddy appends to XFF is outside RFC1918/loopback. The valve
skips internal-matching entries and **keeps walking left**. So if the right-most entry is ever a
bridge address, the walk continues into the attacker-controlled portion of the header and B-1
reopens — for exactly the clients affected.

Two conditions on a Hostinger VPS can produce that, and neither is exotic:

**(a) IPv6.** `ports: - "443:443"` publishes IPv4 NAT rules. If the host has IPv6 and Docker's
`ip6tables` is not enabled, inbound IPv6 connections are relayed by the userland `docker-proxy`,
which connects to the container **from the bridge gateway**. Caddy then sees `172.17.0.1` as
`r.RemoteAddr` and appends *that*. Result:

- Attacker sends `X-Forwarded-For: 9.9.9.9` → API sees `9.9.9.9, 172.17.0.1` → right-most is
  internal, skipped → **`remoteIp` = `9.9.9.9`.** Full B-1 bypass, IPv6 only.
- No XFF sent → API sees `172.17.0.1` alone → internal, skipped, loop exits with `remoteIp` still
  holding `172.17.0.1` → **every IPv6 client shares one bucket.** That is branch (c) of the original
  finding — global-bucket collapse — reintroduced for a slice of traffic.

This is moot if the domains have no `AAAA` records. **Nobody has checked.**

**(b) Anything reaching Caddy via the host or hairpin NAT** takes the same relay path with the same
result. Requires host access, so it is not an independent attack, but it means any on-box smoke test
of the rate limiter will measure the wrong thing.

**This is not a regression** — pre-fix, every one of these paths was broken for *all* clients, not a
subset. The fix is strictly better in every case. The finding is against the *claim* of settledness,
which is the one place the commit's otherwise-careful honesty slips: it correctly says "verify the
valve after deploying" but does not say what to verify, so the check will not happen.

**Fix / verification, before B-1 is marked closed:**

1. `dig AAAA <APP_DOMAIN> <API_DOMAIN>` — if empty, (a) is not live and say so in the runbook.
2. On the box: `docker network inspect` for the actual subnet, and confirm whether `docker-proxy`
   processes are bound (`ps aux | grep docker-proxy`).
3. Behavioural, no code: from two genuinely different external IPs, exhaust the `sensitive` bucket on
   one and confirm the other still has its full budget; then from one IP send `sensitive`-bucket
   requests with a rotating `X-Forwarded-For` and confirm the 429 still lands. Repeat over IPv6 if
   `AAAA` exists. That single procedure proves both the evasion guard and the non-collapse guard on
   the real chain, which no unit test can.

---

## MEDIUM

### M-3 — Nothing stops the exact re-break. The defect was one env var, and it is still one env var.

The vulnerability that shipped to production was `SERVER_FORWARD_HEADERS_STRATEGY: framework` in a
compose file. After this commit it is `native` in the same compose file, with the YAML default also
`native` (`application.yml:83`). **No test fails and no boot fails if either flips back.** The 1511-test
suite would stay green through a full reintroduction of the Blocker. Worse, `none` — which reads like
the safest value and was this file's previous "fail-safe" default — now silently collapses every
IP-keyed bucket to the Caddy container's address, because the hand-rolled allow-list that used to
paper over that case is gone.

The idiomatic fix already exists in this codebase. `SecretsStartupValidator` binds bare `@Value` flags
for exactly this class of config footgun (`:159-163`) and aborts startup outside dev
(`:385-400`, `@PostConstruct validate()`). Add:

```java
@Value("${server.forward-headers-strategy:none}")
private String forwardHeadersStrategy;
```

and, outside dev, refuse to boot on `framework` (naming CR-11 B-1 in the message) and refuse-or-warn
on `none`. This is the highest-leverage remaining item: it converts a silent, re-introducible,
production-only security defect into a boot failure.

Medium rather than High only because the committed values are currently correct.

### M-4 — The trust boundary is now "the whole bridge network", not "Caddy". Record it.

`internal-proxies` matching RFC1918 means the API trusts XFF from **any** peer on the compose
network, not just Caddy. `influora-ai`, `frontend`, `mysql`, `redis` and `clamav` all qualify. A
foothold in any of them yields the ability to forge an arbitrary client IP into every rate-limit
bucket **and into the admin audit table** (`AdminAuditLogService.clientIp`, `:578`) — the forensic
record whose integrity the whole second half of this commit is about.

`influora-ai` is the one that matters: a Python service that processes untrusted model output, holds
`INTERNAL_REQUEST_HMAC_SECRET` and `INTERNAL_SERVICE_TOKEN_SECRET`, and already speaks to
`http://influora-api:8080/api/v1`.

Requires an existing foothold, so Medium, and I am **not** recommending you tighten the regex — a
per-container IP is unstable across recreates and that fragility is exactly what made `TRUSTED_PROXIES`
worthless. Accept the trade; write it down. One sentence in the `application.yml` comment
("this trusts every peer on the compose network, not only Caddy") turns an unstated assumption into a
known one.

---

## LOW

### L-5 — Dead imports left behind by the deleted control.

`AuthRateLimitFilter.java` still imports `java.util.Arrays` (`:10`), `java.util.Set` (`:12`) and
`java.util.stream.Collectors` (`:16`). All three are now at **zero** usages in the file — they existed
solely for `trustedProxies()`. Cosmetic and harmless, but they are the literal leftovers of the
control the commit says it removed cleanly, in the same file that says so.

### L-6 — `internal-proxies` is now a pinned literal with nothing binding it to behaviour.

Inlining the default is defensible (it makes the value auditable and overridable), but it has costs
worth recording:

- It is pinned to Spring Boot 3.3.5's `ServerProperties.Tomcat.Remoteip` default and will no longer
  track upstream. Tomcat's own `RemoteIpValve` default additionally carries IPv6 ULA/link-local
  branches (`fe[89ab]…`, `f[cd]…`) that this string omits — harmless for an IPv4-internal stack, but
  it means "Tomcat's default" in the comment at `:86` is really a subset of it.
- **Upgrade tripwire:** the value is dense with backslashes (`\.`, `\d{1,3}`). Spring Framework 6.2
  (Boot 3.4+) changed `PropertyPlaceholderHelper` to do escape-character processing. Verify this
  regex survives a Boot upgrade byte-for-byte — if backslashes are consumed, `internal-proxies`
  silently becomes a *different* regex, no test notices, and the failure is either "trusts nothing"
  (global bucket collapse) or "trusts more than intended". The Q6 test that loads the string from the
  yaml and asserts resolution behaviour closes this too.

---

## Verified sound — no finding

Stated explicitly so nobody "fixes" them.

- **The right-to-left walk lands on the true peer.** Traced against the real Caddyfile. A
  client-prepended entry cannot win as long as H-2's premise holds.
- **The `internalProxies` gate on the socket peer** is the structural improvement, and it is the
  thing `ForwardedHeaderFilter` lacked. From an untrusted peer, XFF is not consulted at all.
- **No scheme, host, port, redirect, `isSecure()` or secure-cookie regression.** Grep-verified, not
  assumed — see Q4.
- **No `X-Forwarded-Prefix` dependency** in either deploy (`handle /api/*`, not `handle_path`).
- **No production code reads `X-Forwarded-For` directly**, so the valve's header rewrite poisons
  nothing today.
- **The API port is not published** in either compose file. Only `caddy` publishes.
- **YAML scalar and `${VAR:default}` colon handling are both correct** — two non-obvious silent
  failure modes that did not fire.
- **`AdminAuditLogService.clientIp()` is genuinely correct now**, in both the proxied and direct
  branches.
- **The commit message's scope caveat about the test is honest** and the revert-proof discipline is
  the right standard. My Q6 finding sharpens it; it does not contradict it.

---

## Fix order

1. **H-2 — verify the live topology** (AAAA records + `docker-proxy` + the two-IP behavioural check).
   This is the only item that decides whether B-1 is actually closed on the box. Do it before anyone
   marks CR-11 B-1 resolved.
2. **H-1 — delete `application.yml:142-147.`** Six lines. The commit message already claims this is
   done.
3. **M-3 — startup gate in `SecretsStartupValidator`.** Two fields, one check. Prevents recurrence.
4. **Q6 — `RemoteIpFilter` unit test** loading the regex from `application.yml`. Closes the valve-test
   gap and L-6 at once.
5. **M-4 comment, L-5 imports.**

Items 2, 3 and 5 are mechanical and can ship together. Item 1 needs no code.

---

## Still open from the original CR-11 review

B-1 aside, nothing else from `wiki/errors/CR-11-client-errors-endpoint-redteam.md` has been addressed
by this commit and none of it was in scope:

- **M-4 (log rotation)** — still the cheapest real mitigation available, one `logging:` block, and it
  removes the disk-full path that takes MySQL down.
- **H-2 (upload timeout)**, **H-3 (log forging)**, **M-5**, **M-6**, **L-7 (matrix-parameter bucket
  evasion)** — unchanged. L-7 in particular was ranked Low *because* B-1 made it redundant. **B-1 is
  now fixed, so L-7 is the next bucket bypass** and should be picked up in the following pass.
