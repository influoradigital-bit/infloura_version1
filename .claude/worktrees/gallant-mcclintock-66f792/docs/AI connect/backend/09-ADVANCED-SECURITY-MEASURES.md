# 🛡️ 09 — ADVANCED DEFENSE-IN-DEPTH SECURITY MEASURES (implementation-level)

> **Author:** Kabir (Offensive Security / Red-Team Lead) · **Date:** 2026-07-05
> **Status:** LAUNCH-GATING for the money + Meera wire. Complements — does not repeat —
> [`03-SECURITY-SPEC.md`](03-SECURITY-SPEC.md) (guardrails G1–G6, must-fixes MF-1..4, 25-row checklist)
> and [`06-MEERA-PERMISSIONS-MATRIX.md`](06-MEERA-PERMISSIONS-MATRIX.md) (R/D/C/Forbidden tiers).
> **Audience:** Vikram (Backend). This is the *how*, at class + method granularity. Where 03 says
> "re-derive amount server-side," this doc names the file, the lock, the invariant, and the attack.
> **Ground truth read for this doc:** `security/JwtService.java`, `JwtAuthenticationFilter.java`,
> `AuthPrincipal.java`, `AuthCookieService.java`, `AuthRateLimitFilter.java`, `config/SecurityConfig.java`,
> `config/JwtProperties.java`, `02-API-CONTRACT-BRAND.md`.

**Reading contract.** 03 is the *policy*. 06 is the *permission matrix*. This is the *build sheet*. Every
control below is `WHAT / WHERE / ATTACK`. Nothing here relaxes 03 or 06; several controls harden gaps I
found while re-reading the live code (JWT parser does no `iss`/`aud`/alg pinning; the JWT filter still
swallows exceptions silently; the rate limiter is per-instance in-memory; refresh tokens are opaque UUIDs
with rotation but no explicit reuse-detection). Those are called out inline as **[LIVE-GAP]**.

---

## LAYER 0 — CROSS-CUTTING PRINCIPLE

**Deny by default, at the layer closest to the data.** Authorization is not a controller concern that can
be forgotten per-route; it is a *repository* concern that is structurally impossible to forget (Layer 1.6,
`TenantGuard`). Every untrusted value (LLM output, scraped HTML, client body, request header) is data
until a server-side derivation or schema promotes it. No control is "the LLM won't do that" — every control
assumes the LLM, the scraped page, and the user are one colluding attacker (03 §1.1, Zone D).

---

## LAYER 1 — AuthN / AuthZ

### 1.1 JWT algorithm pinning + full claim validation  **[LIVE-GAP]**
- **WHAT:** `JwtService.parseAccessToken` currently calls `Jwts.parser().verifyWith(accessKey())` with no
  algorithm restriction, no `requireIssuer`, no `requireAudience`, no clock-skew bound. Harden it: pin the
  algorithm to a single expected value and reject any other (`.sig(Jwts.SIG.HS256)` on the resolver / an
  explicit alg check on the parsed header), require `iss=influora-api`, require `aud=influora-brand` for
  user tokens, enforce `exp`, and set a small clock skew (`.clockSkewSeconds(30)`). Mint side
  (`createAccessToken`) must stamp `iss` and `aud`.
- **WHERE:** `security/JwtService.java` (`parseAccessToken`, `createAccessToken`); new
  `JwtValidationConfig` if the alg-pin is centralized.
- **ATTACK:** `alg:none` downgrade, `alg` confusion (HS256↔RS256 key-substitution), token minted for a
  different service/audience replayed at the brand API, and long-window replay of a barely-expired token.

### 1.2 JWT secret startup guard (MF-3 depth)
- **WHAT:** `@PostConstruct` validator on `JwtProperties`: if profile ≠ `dev` and
  (`accessSecret` equals the committed dev default **or** `< 32 bytes` UTF-8 **or** equals `refreshSecret`
  **or** equals the internal-service-token signing key) → **throw and refuse to boot.** Separate keys per
  role (G6): user-access ≠ refresh ≠ internal-service-token ≠ stream-token ≠ Razorpay-webhook.
- **WHERE:** `config/JwtProperties.java` (add `@PostConstruct check()`), reads from secret manager in prod.
- **ATTACK:** Forge any user's/workspace's token from a known/committed HMAC key → full account + money
  takeover, bypassing every downstream control (03 MF-3, AS-10).

### 1.3 JWT filter fails closed *and loud*  **[LIVE-GAP]**
- **WHAT:** `JwtAuthenticationFilter` currently does `catch (JwtException ignored) { clearContext(); }` —
  fails closed (good) but silent (bad: forged vs. malformed vs. expired are indistinguishable, so RT and
  SIEM see nothing). Emit a structured security event (`auth.token.rejected` with reason enum:
  `EXPIRED / BAD_SIG / MALFORMED / BAD_AUD / BAD_ALG`, source IP, no token bytes) before clearing context.
  Still 401, never 500, never authenticated.
- **WHERE:** `security/JwtAuthenticationFilter.java`; route event through `AuditLogService` (Layer 9).
- **ATTACK:** Credential-stuffing / token-forgery probing goes undetected; incident response is blind.

### 1.4 Refresh-token rotation + reuse detection  **[LIVE-GAP]**
- **WHAT:** Refresh tokens are opaque (`createRefreshTokenValue`), stored hashed (`hashToken` SHA-256),
  delivered via HttpOnly cookie (`AuthCookieService`), rotated on use. **Add reuse-detection:** each refresh
  row carries `family_id` + `rotated_at` + `superseded_by`. On `/auth/refresh`, if the presented token is
  already `superseded` (a rotated-away token being replayed), **revoke the entire family** and force
  re-login. One-time-use per token; the newest token is the only live one in the family.
- **WHERE:** new `RefreshTokenService` + `refresh_tokens` table columns; wired at `/auth/refresh`.
- **ATTACK:** Stolen refresh token (e.g. exfil’d before rotation) used in parallel with the legitimate
  client → reuse of a rotated token trips family revocation, capping the theft window to one rotation.

### 1.5 Session invalidation / global logout
- **WHAT:** Server-side revocation list keyed by `jti` (access tokens carry `id`/`jti` already —
  `createAccessToken` sets `.id(UUID)`). "Logout everywhere" and admin-forced-logout add the user's live
  `jti`s (or a `tokens_valid_after` timestamp per user) to a short-TTL revocation cache checked in the JWT
  filter. Access-token TTL is short (≤15 min) so the revocation set stays small.
- **WHERE:** `TokenRevocationService` (Redis-backed) consulted in `JwtAuthenticationFilter`; refresh
  family revocation from 1.4 handles the durable credential.
- **ATTACK:** After password reset / suspected compromise, a still-valid stolen access token keeps working
  until natural expiry with no way to kill it.

### 1.6 Repository-level tenant scoping — the reusable `TenantGuard` pattern
- **WHAT:** No query may forget `workspace_id`. Make it structurally impossible: (a) a Hibernate
  `@Filter("tenant")` enabled per-request from `AuthPrincipal.getWorkspaceId()` in an interceptor, applied
  to every tenant-owned entity; **and** (b) a `TenantGuard.requireOwned(entity, principal)` helper that
  every service calls after a by-id load (the existing `CampaignService.loadOwned` /
  `BrandContextService.requireBrandWorkspace` pattern, generalized). Cross-tenant hits return **404, not
  403** (03 §1.2 hidden-as-404). A repository method that takes a raw id without a `workspaceId` predicate
  is a code-review reject.
- **WHERE:** `security/TenantContext` (thread-local workspace), `TenantFilterInterceptor`,
  `security/TenantGuard`; applied across all `*Repository` / `*Service` money + Meera classes.
- **ATTACK:** IDOR / horizontal privilege escalation — brand A reads or mutates brand B's campaign, wallet,
  escrow, conversation by guessing/enumerating a ULID (AS-7 data bleed, cross-tenant money movement).

### 1.7 Per-endpoint authorization annotations
- **WHAT:** Method-level `@PreAuthorize` on every money/commit route: `hasRole('BRAND')` plus
  workspace-role for money (`@workspaceRoles.isOwnerOrAdmin(#principal)` for `escrow/fund`,
  `request_payment`). Enable `@EnableMethodSecurity`. Commit-tier (06 tier C) endpoints are **public
  browser endpoints on the human JWT only** — never reachable from `/internal/meera/*` (06 enforcement
  table). Forbidden (06 F1–F4) endpoints simply do not exist for the AI surface.
- **WHERE:** controllers (`EscrowController`, `MeeraController`, `WalletController`); `config` for
  `@EnableMethodSecurity`; a `WorkspaceRoleEvaluator` bean.
- **ATTACK:** A member (non-owner) or a forwarded on-behalf token triggering a money action the human's
  actual role forbids; privilege confusion between read/draft/commit tiers.

---

## LAYER 2 — SPRING ↔ PYTHON TRUST BOUNDARY (`/internal/**`)

### 2.1 Dedicated internal filter chain + network isolation
- **WHAT:** A **second** `SecurityFilterChain` (`@Order(1)`, `securityMatcher("/internal/**")`) that runs
  *before* the public chain and rejects any `/internal/**` request whose source is not the mesh. Bind
  internal listeners to a private interface / separate port; deny at the app with an IP allowlist
  (mesh CIDR) as defense-in-depth even behind network policy. The public `SecurityConfig` chain must never
  match `/internal/**`.
- **WHERE:** new `InternalSecurityConfig` (second chain); `config/SecurityConfig` gets an explicit negative
  matcher; network policy / firewall outside the app.
- **ATTACK:** Internet-exposed money executors (AS-1); a leaked env or SSRF pivot reaching
  `/internal/meera/*` directly.

### 2.2 Short-lived signed service token (`aud=influora-internal`, ≤60s) — NOT a static key
- **WHAT:** `InternalServiceTokenFilter` verifies `X-Meera-Service-Token`: a JWT signed with the
  *internal* signing key (distinct from user-access key — G6), `aud=influora-internal`, `exp ≤ 60s`,
  `iss=meera-python`, pinned alg. Reject expired, wrong-aud, wrong-alg, wrong-iss. **A lone
  `INTERNAL_API_KEY` is forbidden as sole authorization on any money endpoint (MF-2).** Signing key
  rotation ≤24h. mTLS is the accepted alternative/addition.
- **WHERE:** `security/InternalServiceTokenFilter` (in the internal chain, first); token minted by a
  narrow internal issuer.
- **ATTACK:** AS-1 / AS-3 — a leaked long-lived key granting permanent un-scoped un-rotatable money
  authority; a compromised Python container replaying credentials.

### 2.3 On-behalf-of human JWT re-authorization (dual credential)
- **WHAT:** Every `/internal/meera/*` call carries **both** the service token **and** the original brand
  JWT (`X-Onbehalf-Authorization`). Spring re-validates the human JWT (validity, `iss`/`aud`, expiry,
  `workspaceId`) and re-checks `workspaceId` == body `workspaceId`, and OWNER/ADMIN for money. A stolen
  service token alone cannot pick a victim workspace.
- **WHERE:** `OnBehalfAuthResolver` (internal chain, after 2.2); reuses `JwtService`/`AuthPrincipal`.
- **ATTACK:** Service-token theft → arbitrary-workspace money movement; the second credential (G1) bounds
  the blast radius to the caller's own workspace.

### 2.4 Request signing / HMAC + replay protection (nonce + timestamp)
- **WHAT:** `InternalRequestSigner` (Python) and `InternalRequestVerifier` (Spring): sign
  `HMAC(sharedInternalHmacKey, method + path + sha256(body) + timestamp + nonce)` into
  `X-Meera-Signature`. Spring verifies the HMAC (constant-time compare), rejects `|now - timestamp| > 30s`,
  and rejects a `nonce` already seen (short-TTL nonce cache). Binds the body to the signature so a captured
  request cannot be replayed or its body swapped.
- **WHERE:** `security/InternalRequestVerifier` (internal chain, after 2.3); `NonceCache` (Redis TTL 60s).
- **ATTACK:** Replay of a captured valid internal call (double escrow hold); body-tampering after signing;
  timing side-channels on signature compare.

---

## LAYER 3 — MONEY-PATH DEFENSE

### 3.1 Idempotency keys (UNIQUE + dedupe table)
- **WHAT:** Every money mutation and every `/internal/meera/*` write requires `Idempotency-Key`
  (the Anthropic `tool_use.id` for internal, a client UUID header for browser). A `meera_tool_calls` /
  `idempotency_records` row with a **`UNIQUE(idempotency_key)`** constraint stores the first result;
  retries return the stored result, never a second effect. Insert-first, so the DB constraint — not
  app logic — is the arbiter under concurrency.
- **WHERE:** `service/IdempotencyService` (`executeOnce(key, supplier)`); `meera_tool_calls` /
  `idempotency_records` table with UNIQUE key; wired in every money service.
- **ATTACK:** Double-submit / network-retry / at-least-once tool-call delivery causing a second charge,
  a second escrow hold, a duplicate payout.

### 3.2 Server-side amount re-derivation (G1 depth, MF-1)
- **WHAT:** Money DTOs have **no** `amount`/`fee`/`total` field usable for charging. Spring re-derives
  from persisted state (`campaign_intents.product_price`, `payment_milestones.amount`) and config
  (`PLATFORM_FEE_PERCENT`, `ESCROW_HOLD_PERCENT`). If the request carries an advisory `amount` that differs
  from derived beyond tolerance → `409 AMOUNT_MISMATCH`, no charge, log as anomaly. Persist
  `meera_tool_calls.server_amount`.
- **WHERE:** `AmountDerivationService`; enforced in `EscrowService`, `PaymentService`,
  `MeeraInternalController` DTOs.
- **ATTACK:** AS-9 amount tampering — hold ₹1 on a ₹22,500 deal, or ₹9,999,999 to drain a wallet; an
  LLM-proposed amount to an attacker.

### 3.3 Double-entry ledger invariant (sum = 0)
- **WHAT:** Every money movement writes paired `wallet_transactions` legs (debit + credit) in one
  `@Transactional`. A `LedgerInvariantValidator` asserts, before commit, that the sum of legs for the
  transaction group is exactly zero and that no wallet balance goes negative. Balances are *derived from /
  reconciled against* the ledger, never written independently. A reconciliation job re-checks
  `sum(all legs) == 0` globally.
- **WHERE:** `service/LedgerService` + `LedgerInvariantValidator`; `wallet_transactions` table.
- **ATTACK:** Money created or destroyed by a buggy/forged single-sided write; silent balance drift; an
  accounting attack that credits without a matching debit.

### 3.4 Concurrency control — pessimistic + optimistic locking
- **WHAT:** Wallet-balance mutations use `SELECT ... FOR UPDATE`
  (`@Lock(PESSIMISTIC_WRITE)` on the wallet row) inside the transaction so two concurrent holds cannot both
  read the pre-spend balance. Long-lived rows (campaign, escrow_hold, milestone) carry a
  `@Version` optimistic-lock column; a stale-version write fails and retries. Credit decrement is atomic
  (`UPDATE ... SET credits = credits - :c WHERE credits >= :c`).
- **WHERE:** `WalletRepository.lockForUpdate`, `@Version` on `Wallet`/`EscrowHold`/`Campaign` entities,
  `AICreditService.tryConsume`.
- **ATTACK:** Double-spend / TOCTOU race — 20 concurrent turns each seeing 1 credit; two escrow holds each
  seeing full balance; overdraft.

### 3.5 State-machine guards on escrow / payout transitions
- **WHAT:** `EscrowStateMachine` allows only the legal edges
  (`PENDING→FUNDED→RELEASED / REFUNDED`; never `PENDING→RELEASED`, never re-fund a FUNDED, never release an
  unfunded). `confirm_launch` verifies escrow == `FUNDED` **from the DB**, not asserted by the AI.
  Every transition is guarded, logged, and idempotent.
- **WHERE:** `service/EscrowStateMachine`; enforced in `EscrowService`, `PayoutService`,
  `MeeraInternalController.confirm_launch`.
- **ATTACK:** Illegal transition — release funds never escrowed; double-release; re-fund exploitation;
  the AI claiming "funded" to trigger a launch/payout.

### 3.6 Razorpay webhook signature verification (HMAC)
- **WHAT:** `WebhookSignatureVerifier` verifies `X-Razorpay-Signature` =
  `HMAC_SHA256(razorpayWebhookSecret, rawBody)` (constant-time), **on the raw pre-parse body**. Reject on
  mismatch. Escrow only transitions to `FUNDED` on a *verified* webhook, never on the client's
  "I paid" claim. Webhook events are idempotent (dedupe on `razorpay_event_id`).
- **WHERE:** `service/WebhookSignatureVerifier` + `RazorpayWebhookController` (public but signature-gated);
  webhook secret in its own blast-radius slot (G6).
- **ATTACK:** Forged "payment succeeded" webhook funding escrow without real payment; replayed webhook
  double-crediting; body-parse-then-verify signature bypass.

### 3.7 Out-of-band confirmation for payouts
- **WHAT:** Any payout / payout-destination change is a **human, authenticated, out-of-band-confirmed**
  action (06 F1/F3 forbidden to Meera; re-auth required). Payout release requires a fresh human
  confirmation (step-up: re-enter credential or OTP) and is never reachable from `/internal/meera/*`.
- **WHERE:** `PayoutController` (public, step-up guarded); structurally absent from the internal chain.
- **ATTACK:** Prompt-injected "change payout account and release" (06 attack table); social-engineering the
  funding destination.

---

## LAYER 4 — INPUT & INJECTION

### 4.1 Bean Validation on every DTO
- **WHAT:** Every request DTO annotated (`@NotNull`, `@Size`, `@Pattern`, `@Positive`, `@Valid` on
  nested); controllers use `@Valid`. A `@ControllerAdvice` maps `MethodArgumentNotValidException` to the
  standard `ApiResponse` error envelope with `error.field`. Unknown JSON properties rejected
  (`FAIL_ON_UNKNOWN_PROPERTIES=true`) so an injected `amount` field is a 400, not a silent ignore.
- **WHERE:** all `dto/**` classes; `GlobalExceptionHandler`; Jackson config.
- **ATTACK:** Oversized/malformed input, type confusion, mass-assignment of unexpected fields.

### 4.2 Parameterized queries only
- **WHAT:** JPA / bound parameters exclusively; **zero** string-concatenated SQL/JPQL; no dynamic
  `@Query` built from request strings. If native SQL is unavoidable, only `:named` bind params. A
  build-time grep / Semgrep rule bans string concatenation into query methods.
- **WHERE:** all `*Repository`; Semgrep rule in CI (Layer 10).
- **ATTACK:** SQL / JPQL injection.

### 4.3 Output encoding
- **WHAT:** LLM output and all user/scraped content rendered as **escaped text only** — never an HTML sink
  (`dangerouslySetInnerHTML`), never reflected unescaped into an HTML/error surface. JSON responses only;
  the strict CSP (`default-src 'none'`) on the API is the secondary net.
- **WHERE:** frontend `MessageBubble` (contract with Ananya); Spring never emits HTML with user data.
- **ATTACK:** Stored/reflected XSS via chat content or LLM output; the localStorage-token XSS chain (03 A1).

### 4.4 SSRF protection on the site-analyzer — `SsrfGuard`
- **WHAT:** The brand-site scraper fetches a user-supplied URL — the single most dangerous fetch in the
  system. `SsrfGuard` enforces: **scheme allowlist** (`https` only, no `file://`/`gopher://`/`ftp://`);
  **resolve DNS first, then block** any resolution to private/loopback/link-local/CGNAT ranges
  (`10/8, 172.16/12, 192.168/16, 127/8, 169.254/16, ::1, fc00::/7`) **and the cloud metadata endpoint
  `169.254.169.254`**; **DNS-rebinding protection** — resolve once, pin the IP, and connect to *that IP*
  (not re-resolve); **redirect cap** (≤2) with every hop re-validated through the same guard; response
  size + timeout caps; egress from the scraper restricted to the resolved public IP.
- **WHERE:** `security/SsrfGuard` used by the Python scraper's fetch layer (and any Spring-side URL fetch);
  Python container egress rules (G6) as the outer net.
- **ATTACK:** AS-3 SSRF pivot — read cloud metadata / IAM creds (`169.254.169.254`), reach
  `/internal/meera/*` from inside, port-scan the mesh; DNS-rebind to bypass a check that validated the
  hostname but connected after re-resolution.

### 4.5 File-upload validation
- **WHAT:** Uploads (contract PDFs, brand assets) validated by **content sniff, not extension** (magic
  bytes / `Content-Type` allowlist), hard size cap, filename sanitized (no path traversal), stored in R2
  with a random key (never the user filename), served with `Content-Disposition: attachment` +
  `X-Content-Type-Options: nosniff`. AV/malware scan before the file is usable. Signed-PDF SHA-256 stored
  for tamper-evidence (06 Ruling C).
- **WHERE:** `service/FileUploadValidator` + upload controller; R2 storage layer.
- **ATTACK:** Malware upload, content-type confusion, path traversal, stored-XSS via SVG/HTML masquerading
  as an image, contract tampering.

### 4.6 Prompt-injection isolation (untrusted content delimited)
- **WHAT:** Scraped site text and user chat wrapped in explicit model-visible delimiters
  (`<untrusted_brand_site>…</untrusted_brand_site>`) with a system instruction that inner content is
  *data to analyze, never instructions to obey.* Strip active content from scraped HTML (no `<script>`,
  event handlers, `display:none` text). Persona + tool defs live in the cached, tenant-agnostic prefix;
  untrusted content is always structurally in the suffix (G4). The load-bearing control remains: the LLM
  can only **propose**; Spring re-derives + re-authorizes (03 §3.1), so injection degrades to "wrong text,"
  never "moved money."
- **WHERE:** Python prompt assembly + scraper cleaner; Spring `BrandContextService` for prefix/suffix split.
- **ATTACK:** AS-4 / AS-5 — a hostile brand page or chat message coaxing a malicious tool-call proposal.

---

## LAYER 5 — AI-SPECIFIC

### 5.1 PII stripping before prompt (allowlist projection) — G3 depth
- **WHAT:** `BrandContextService` builds `MeeraContextDto` as a **deny-by-default whitelist projection**:
  products, niche, tone, brand color, aggregate reach, campaign summaries, credit state. **Never** PAN,
  KYC, bank/UPI, GST, phone, email, full creator PII. New DB columns do not auto-flow — the mapper is an
  explicit field list, not entity serialization.
- **WHERE:** `service/BrandContextService` → `MeeraContextDto` (before every Python call).
- **ATTACK:** AS-6 PII leak to prompt / provider logs (DPDP/RBI exposure).

### 5.2 Tenant isolation on cache keys — G4 depth
- **WHAT:** Prompt-cache key is `(prompt_version, workspace_id, session_id)` — never global. Cached prefix
  is tenant-agnostic (persona + tool defs, `cache_control: ephemeral`); **all** brand data lives in the
  per-tenant suffix. Python is stateless per request; no module-level cache keyed by anything but
  `workspace_id`.
- **WHERE:** Python cache-key construction; Spring passes `workspace_id` on every call.
- **ATTACK:** AS-7 cross-tenant cache bleed — brand B served brand A's cached catalog ("Zephyr-9").

### 5.3 Tool-call output validation against schema + permission matrix
- **WHAT:** `ToolCallValidator` validates every LLM-emitted tool-call against (a) the versioned JSON schema
  (name-whitelist of the defined tools only; reject unknown names, extra fields, out-of-enum values) **and**
  (b) the **06 permission matrix** — R/D/C tier gating: a tool-call that maps to a Forbidden (F1–F4) or an
  auto-commit-that-should-be-human row is dropped and logged. No field the LLM emits is trusted for a
  monetary/authorization decision.
- **WHERE:** `service/ToolCallValidator` in Spring, before any executor runs; schema is versioned code in
  git alongside the executor.
- **ATTACK:** Schema-drift exploitation, unknown/forged tool names, an injection promoting a draft to a
  commit or invoking a forbidden capability.

### 5.4 Credit / rate gate before the model — G5 depth
- **WHAT:** Credit gate + atomic decrement (3.4) runs in Spring **before** the Python service is reachable
  or a stream token is issued. Insufficient → `402` before any LLM call. Python is not internet-routable,
  so there is no path to the model that skips the gate.
- **WHERE:** `AICreditService.tryConsume` in `MeeraController` (`/meera/sessions/{id}/messages`).
- **ATTACK:** AS-8 cost abuse — spamming turns / hitting Python directly to burn tokens.

### 5.5 Cost circuit-breaker (hard, not a report)
- **WHAT:** `CostCircuitBreaker` enforces per-workspace **and** global daily token/₹ ceilings; when hit,
  new Meera turns are **refused** (`429`/`402`), not logged-and-continued. Max tokens per turn, max input
  length capped before the prompt is built.
- **WHERE:** `service/CostCircuitBreaker`; checked in `MeeraController` alongside the credit gate.
- **ATTACK:** Runaway bill from automation or a compromised loop; Rohan's cost meter blown.

### 5.6 No secrets in prompts + logging redaction
- **WHAT:** No API keys, DB creds, internal tokens, or PII ever placed in a prompt. `LogRedactionFilter`
  scrubs PII patterns (PAN regex, phone, email, bank), token/secret patterns, and full prompt bodies from
  every log line — log the *event* + token counts, not content.
- **WHERE:** `logging/LogRedactionFilter` (logback/log4j2 filter) applied to Spring + Python; enforced in
  prompt assembly.
- **ATTACK:** Secret/PII exfil via logs or provider retention; log-based reconstruction of prompts.

---

## LAYER 6 — SECRETS & CONFIG

- **WHAT:** `.env` only in local; **secret manager in prod** (no secrets in the repo, image, or `application.yml`
  defaults). Per-environment segregation (dev/stage/prod distinct secrets). **Blast-radius separation**
  (G6): LLM key ≠ DB creds ≠ Razorpay key ≠ Razorpay webhook secret ≠ user-JWT key ≠ refresh key ≠
  internal-service-token key ≠ stream-token key ≠ R2 creds — each an independent slot. No secret in logs
  (Layer 5.6). Python holds LLM/voice keys only, never Razorpay/DB.
- **WHERE:** secret manager config; `JwtProperties` startup guard (1.2); container secret mounts.
- **ATTACK:** One leaked secret compromising multiple systems; a Python compromise yielding money/DB creds
  (AS-3); a committed-default secret in prod.

---

## LAYER 7 — TRANSPORT & HEADERS

- **WHAT:** HSTS (`includeSubDomains`, 1-year) — already set in `SecurityConfig`; keep and add `preload`
  once the domain is submittable. CSP `default-src 'none'; frame-ancestors 'none'; base-uri 'none'` —
  present; keep. `X-Frame-Options: DENY` — present. Add `X-Content-Type-Options: nosniff`,
  `Referrer-Policy: no-referrer` (present), `Permissions-Policy` minimal. **CORS allowlist** — explicit
  origin list (the SPA origin), `allowCredentials` only with a concrete origin, **never `*` with
  credentials**. Cookies: refresh cookie is already `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`
  (`AuthCookieService`) — keep; `secure=true` mandatory in every non-local deploy.
- **WHERE:** `config/SecurityConfig` (headers + CORS bean); `AuthCookieService` (cookies).
- **ATTACK:** MITM downgrade, clickjacking, CSRF via ambient cookie, credentialed cross-origin theft,
  MIME-sniff XSS.

---

## LAYER 8 — RATE LIMITING & ABUSE

- **WHAT:** Move the limiter off per-instance in-memory to a **shared store** (Redis/bucket4j) or the edge
  — the current `AuthRateLimitFilter` is explicitly per-instance **[LIVE-GAP]** and won't hold under
  horizontal scale. Buckets: **per-IP + per-user + per-endpoint**. Add Meera-turn and stream-token-issue
  buckets (stricter), and money-endpoint buckets. **Enumeration protection:** uniform responses + timing
  for "user not found" vs "wrong password" and cross-tenant-hidden-as-404 (03). **Brute-force lockout:**
  progressive backoff / temp lock on repeated auth failures per account. **Resource-exhaustion caps:** max
  request body size, max tokens per turn (5.5), request timeouts on the scraper + LLM calls.
- **WHERE:** `RateLimitService` (Redis) replacing/backing `AuthRateLimitFilter`; extended to Meera + money
  routes; `GlobalExceptionHandler` for uniform error shapes.
- **ATTACK:** Distributed brute force bypassing per-node limits (AS-8), account/email enumeration,
  credential stuffing, DoS via huge bodies / long scrapes.

---

## LAYER 9 — AUDITING & DETECTION

- **WHAT:** `AuditLogService` writes an **append-only, immutable** audit record (hash-chained rows or
  WORM store; no UPDATE/DELETE grant on the table) for: every money mutation (with `server_amount`,
  idempotency key, before/after balance), every auth event (login, refresh, rejection reason from 1.3,
  logout, family-revoke), and every AI tool-call (name, tier per 06, schema-validation result,
  `prompt_version`). Structured JSON logs. **Anomaly alerts**: injected-amount mismatch, rejected tool-call,
  reused refresh token, cross-tenant 404 spike, cost-breaker trip, SSRF-guard block — fire to Kabir/Rohan.
- **WHERE:** `service/AuditLogService` + `audit_log` append-only table; alert sink.
- **ATTACK:** Tamper-to-hide (repudiation), undetected slow fraud, blind incident response.

---

## LAYER 10 — DEPENDENCY & BUILD

- **WHAT:** SCA/CVE scanning in CI (OWASP Dependency-Check / Trivy for Java + `pip-audit` for Python);
  **build fails on high/critical** known-vuln packages. Pin all versions (lockfiles: Maven versions
  pinned, Python `requirements.txt` hash-pinned). Semgrep rules for the app-specific bans (string SQL,
  raw URL fetch bypassing `SsrfGuard`, secret literals, `amount` in money DTOs). Base images minimal +
  regularly rebuilt.
- **WHERE:** CI pipeline; `pom.xml` / `requirements.txt`; `.semgrep/` rules.
- **ATTACK:** Supply-chain (AS-3), known-CVE exploitation, dependency confusion.

---

## MANDATORY SECURITY FILES (Vikram must create these)

Security-specific classes gating the money + Meera wire. Each is a one-line build contract.

| # | File / class | One-line spec |
|---|---|---|
| 1 | `security/InternalSecurityConfig` | Second `SecurityFilterChain` (`@Order(1)`) matching `/internal/**`, mesh-only, ahead of the public chain. |
| 2 | `security/InternalServiceTokenFilter` | Verifies `X-Meera-Service-Token` (JWT, `aud=influora-internal`, ≤60s, pinned alg, distinct key); rejects static-key auth. |
| 3 | `security/OnBehalfAuthResolver` | Re-validates the forwarded human JWT on `/internal/**` and enforces `workspaceId` + OWNER/ADMIN. |
| 4 | `security/InternalRequestVerifier` | Verifies HMAC signature over method+path+bodyHash+timestamp+nonce; constant-time; ±30s skew. |
| 5 | `security/NonceCache` | Short-TTL nonce store rejecting replayed internal requests. |
| 6 | `security/TenantContext` + `TenantFilterInterceptor` | Per-request workspace thread-local enabling the Hibernate tenant filter on every query. |
| 7 | `security/TenantGuard` | `requireOwned(entity, principal)` reusable check; cross-tenant → 404. |
| 8 | `security/WorkspaceRoleEvaluator` | `@PreAuthorize` bean for OWNER/ADMIN gating on money routes. |
| 9 | `security/RefreshTokenService` | Rotation + family-based reuse detection; revokes family on replayed rotated token. |
| 10 | `security/TokenRevocationService` | `jti`/`tokens_valid_after` revocation for session invalidation + global logout. |
| 11 | `service/IdempotencyService` | `executeOnce(key, supplier)` backed by a `UNIQUE` idempotency table. |
| 12 | `service/AmountDerivationService` | Sole authority for every monetary value; `409 AMOUNT_MISMATCH` on advisory drift. |
| 13 | `service/LedgerService` + `LedgerInvariantValidator` | Double-entry paired legs; asserts sum=0 and no negative balance pre-commit. |
| 14 | `service/EscrowStateMachine` | Guards legal escrow/payout transitions; verifies FUNDED from DB. |
| 15 | `service/WebhookSignatureVerifier` | Razorpay HMAC over raw body (constant-time) + event dedupe; gates FUNDED. |
| 16 | `security/SsrfGuard` | URL scheme allowlist, private-IP/metadata block, DNS-rebind pinning, redirect cap for the site-analyzer. |
| 17 | `service/FileUploadValidator` | Magic-byte type check, size cap, filename sanitize, AV scan, tamper hash. |
| 18 | `service/ToolCallValidator` | Validates LLM tool-calls vs. versioned schema **and** the 06 permission matrix (R/D/C/Forbidden). |
| 19 | `service/BrandContextService` → `MeeraContextDto` | Deny-by-default PII allowlist projection before every Python call. |
| 20 | `service/CostCircuitBreaker` | Hard per-workspace + global daily token/₹ ceiling; refuses turns when tripped. |
| 21 | `logging/LogRedactionFilter` | Scrubs PII/secret/prompt-body patterns from every Spring + Python log line. |
| 22 | `service/AuditLogService` | Append-only, hash-chained audit for money + auth + AI tool-calls. |
| 23 | `security/RateLimitService` | Redis/shared-store per-IP + per-user + per-endpoint limiter replacing the in-memory filter. |
| 24 | `config/JwtProperties` guard + `JwtValidationConfig` | `@PostConstruct` secret-strength/segregation guard; alg-pin + `iss`/`aud`/skew on the parser. |

**24 mandatory security files.** (Plus in-place hardening of the four existing live classes: `JwtService`,
`JwtAuthenticationFilter`, `SecurityConfig`, `AuthRateLimitFilter`.)

---

## VERDICT — LAUNCH-BLOCKING LIST

**CONDITIONAL PASS of the build sheet.** These are hard gates; any red = no ship of the money or Meera wire:

1. **[LB-1]** JWT parser lacks alg-pin + `iss`/`aud`/skew, and the dev-default secret guard (1.1, 1.2, MF-3)
   — **BLOCK** until the parser pins alg, validates `iss`/`aud`, and the app refuses to boot on a weak/default/
   shared secret.
2. **[LB-2]** Any `/internal/meera/*` money endpoint reachable without the dual credential (short-lived
   `aud=internal` service token **and** on-behalf human JWT) or authorized by a lone static key (2.2, 2.3, MF-2)
   — **BLOCK.**
3. **[LB-3]** Any money DTO that accepts a chargeable `amount`, or a mutation without server re-derivation,
   idempotency `UNIQUE`, `SELECT FOR UPDATE`, and the ledger sum=0 invariant (3.1–3.4, MF-1) — **BLOCK.**
4. **[LB-4]** Escrow FUNDED without verified Razorpay webhook signature, or any payout reachable from the AI
   surface / without out-of-band human confirm (3.5–3.7, 06 F1/F3) — **BLOCK.**
5. **[LB-5]** Site-analyzer without `SsrfGuard` (metadata/private-IP block + DNS-rebind pinning) (4.4) — **BLOCK.**
6. **[LB-6]** Any PII in a prompt/log, or a cache key missing `workspace_id`, or a tool-call not validated
   against schema **and** the 06 matrix (5.1–5.3) — **BLOCK.**
7. **[LB-7]** Rate limiting still per-instance in-memory for money/Meera routes; cost circuit-breaker absent
   (5.5, 8) — **BLOCK.**
8. **[LB-8]** Audit log not append-only/immutable for money + auth + AI tool-calls (9) — **BLOCK.**
9. **[LB-9]** CI without SCA/CVE gating on high/critical, or CORS `*` with credentials (7, 10) — **BLOCK.**

Re-test required after Vikram builds the 24 files and wires the executors. I re-run RT-G1…RT-G6 + MF-1..4
(03 §5) with the money endpoints live. Any red RT is a launch blocker, not a follow-up ticket.

— Kabir
