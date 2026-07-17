# 16 — VIKRAM: COMPLETE REMAINING-WORK TASK PACKET (build straight from this)

> **Owner:** Priya (CTO) · **For:** Vikram (Backend Developer) · **Date:** 2026-07-05
> **Status:** ACTIVE — the definitive "everything left to build" list after V8 + Phase 1 + Phase 2 shipped green.
> **Grounded in:** `14-REMAINING-TASKS.md` (primary scope), `10-VIKRAM-FILE-MANIFEST.md` (file counts), `04-AI-SERVICE-SPEC.md` (Python service), `11-AI-FLOW-DETAILED.md` (flows), `06-MEERA-PERMISSIONS-MATRIX.md` (tool tiers), `09-ADVANCED-SECURITY-MEASURES.md` (security build sheet), `13-TARA-PHASE1-2-RUN-REPORT.md` (what's already built), `02-API-CONTRACT-BRAND.md` + `01-DATA-MODEL.md` (endpoint/schema detail).

**Read this first.** This packet lists ONLY remaining work. Do NOT re-build anything in the "already shipped" list below — Tara verified all of it green (`mvn compile` SUCCESS, 143 source files) in doc 13. Every task here cites its security control with `[SEC: ...]` tags, its blocker, and a crisp Definition of Done (DoD). The governing law of this whole packet is the one-liner from the AI spec and permissions matrix: **Python proposes, Spring disposes, the human commits money.** No file you write may violate that.

---

## ALREADY SHIPPED — DO NOT RE-BUILD (per docs 13 / 15)

- **V8 wallet ledger** — `WalletTransaction`, double-entry `WalletLedgerService.post()`, `WalletService.debit/credit/getBalance`.
- **Phase 1 (Domain A money core)** — `V9__escrow_holds.sql`, `V10__contracts_and_milestones.sql`; entities `EscrowHold`/`Contract`/`PaymentMilestone` (+ enums); repos; `EscrowService`/`ContractService`/`PayoutService`/`PlatformWalletService`; `WalletController`/`EscrowController`/`ContractController`; hand-rolled `RazorpayClient`/`RazorpayXClient`/`RazorpayWebhookController`/`WebhookSignatureVerifier` (+ `RazorpayProperties`); `MoneyDtos`. Both Kabir money findings (webhook amount validation + nested-JSON parse + injection-resistant body build) are fixed and verified in source.
- **Phase 2 (Domain C AI data layer + read-only Meera)** — `V11`–`V14` migrations; entities `BrandProfile`/`AiConversation`/`AiMessage`/`CampaignIntent`/`BrandAiCredit`/`MeeraToolCall` (+ 8 enums); 6 tenant-scoped repos; services `MeeraSessionService`/`BrandContextAssembler`/`AICreditService`/`StreamTokenService`; `MeeraController` (real) + `MeeraInternalController` (**501 stub — Phase 4 makes it real**); `MeeraDtos`; `MeeraStreamProperties`.

**Known placeholders standing in for remaining work:** `MeeraSessionService.sendTurn` persists a placeholder/echo ASSISTANT message (no real LLM — that's Domain D). `MeeraInternalController` returns 501 on all five executor endpoints (that's Phase 4).

---

## SUMMARY TABLE

| # | Domain / Phase | New files | Modify | Migrations | Priority | Blocked by | Coordination handoff |
|---|---|---:|---:|---:|---|---|---|
| 1 | **D — Python AI service** `influora-ai/` | ~21 | — | — | 🔴 Highest | none (Phase 1+2 landed) | Kavya → Meera(container) → Kabir(SsrfGuard/token) → Priya |
| 2 | **Phase 4 — Meera tool executors** (Domain C remainder) | ~13 | 1 (`MeeraInternalController`) | — | 🔴 High | Phase 1 (done) → unblocked | Kavya → Meera → **Kabir gate (launch-blocking)** → Priya |
| 3 | **E — Security hardening** (net-new + boundary) | ~12–17 | 4 | 2 (`idempotency_keys`, `audit_log`) | 🔴 Woven into 1 & 2 | none — accompanies 1 & 2 | Kabir specifies + gates every phase |
| 4 | **B — Notifications** | 39 | — | 3 (`V15`–`V17`) | 🟡 Medium | none — parallel anytime | Kavya (retry/backoff) → Meera → Priya |
| 5 | **Automated tests** (cross-cutting) | ≥1 per money/AI service | — | — | 🟡 Medium, rising risk | code exists to test | Kavya reviews coverage; Meera runs in CI |
| 6 | **Razorpay SDK swap** | — | ~4 | — | 🟡 Medium | Priya approval (given) | **Kabir re-gate after swap** |
| 7 | **Live MySQL migration execution** | ops | — | runs V8–V17 + 2 | 🔴 Blocks real verification | Meera provisions DB | Meera runs; Vikram logs; Kavya verifies |

**Grand total remaining: ~21 (D) + ~13 (Phase 4) + 39 (B) + ~12 net-new (E) = ~85 new/modified files**, plus 5 migrations (V15–V17 + idempotency_keys + audit_log), plus test classes, plus the live-DB and SDK-swap ops items.

**The one thing that unblocks Meera actually working:** Domain D (Python reasoner) + Phase 4 (Spring executors) together. Until BOTH exist, Meera only echoes a placeholder. Build these two first, in parallel, coordinating the `/internal/meera/*` contract between them — you own both sides, so this is an internal coordination task, not a cross-team handoff.

---

## GATE — READ BEFORE YOU SHIP ANY MONEY FILE

> **No money file ships without Kabir's re-test.** Every file that touches money, escrow, payouts, credits, or the `/internal/meera/*` write-tier surface (Phase 4 D/C-tier executors, Domain E money-path classes, the Razorpay SDK swap) is launch-gated by Kabir re-running **RT-G1..RT-G6 + MF-1..MF-4 + LB-1..LB-9** against the live endpoints. Any red RT is a launch blocker, not a follow-up ticket. Do not request Priya sign-off on any money/Meera-executor file until Kabir's gate is green. The nine launch-blockers are reproduced in the Domain E section — treat them as your acceptance checklist for the whole money+Meera wire.

---

# DOMAIN D — PYTHON AI SERVICE (`influora-ai/`, new repo, ~21 files) 🔴 HIGHEST

**Why first:** until this exists, `MeeraSessionService.sendTurn` only echoes a placeholder (confirmed doc 13). This is the reasoner that makes Meera real. **Stateless FastAPI. No DB, no money key, no raw PII.** Reads with `04-AI-SERVICE-SPEC.md` (the full build spec) and `11-AI-FLOW-DETAILED.md` (Flows 1–5).

**Invariants (from `04` Appendix — every file must respect):** (1) Python never reads MySQL, never sees raw PII, never holds a money/DB key. (2) Tool-calls are proposals; Spring re-derives amounts + re-authorizes. (3) Every call carries a valid Spring-issued scoped token; `workspace_id` matches token and body. (4) Cached prefix tenant-agnostic; brand data in per-tenant suffix. (5) No full prompts/transcripts/PII in logs. (6) Spring gates auth+credits before Python is reachable. (7) Voice failure → silent text fallback.

### D0 — Skeleton & infra (5 files)

| File | Purpose / key behavior | Security | DoD |
|---|---|---|---|
| `app/main.py` | FastAPI app; register `/chat`, `/analyze-site`, `/voice/transcribe`, `/voice/speak`, `/healthz`, `/readyz`; wire auth dependency + redaction logging middleware. | `[SEC: LogRedactionFilter]` | `uvicorn` boots; `/healthz` 200 no-auth; `/readyz` reports provider reachability + keys loaded. |
| `app/config.py` | Env loading via secrets manager; provider models pinned by version string; per-provider timeouts, retry/breaker params; `PROMPT_VERSION` constant (`meera-2026.07.05`). Keys server-side only — never any `NEXT_PUBLIC`-equivalent exposure. | `[SEC: SecretsConfig G6]` blast-radius isolation (LLM/voice keys only, never Razorpay/DB) | Missing/weak key → refuse boot; models pinned; India/approved regions only. |
| `requirements.txt` | `fastapi`, `uvicorn`, `httpx[http2]`, `anthropic`, `google-genai`, Sarvam client, `playwright`, `pyjwt[crypto]`. **Hash-pinned.** | `[SEC: Layer 10 SCA]` `pip-audit` in CI, build fails on high/critical | `pip-audit` clean; all versions hash-pinned. |
| `Dockerfile` | Non-root user, **read-only FS**, egress restricted to approved LLM endpoints + Spring internal only. Playwright headless Chromium installed. | `[SEC: Kabir guardrail #6 egress allow-list]` | Container runs non-root; egress to any non-allowlisted host blocked; no local disk writes. |
| `.env.example` | Documents every env var (provider keys, Spring JWKS URL, internal HMAC key ref, `PROMPT_VERSION`) — **no real secrets**. | `[SEC: Layer 6]` no secrets in repo/image | Every runtime var documented; zero real values committed. |

### D1 — Auth (1 file) — validate Spring-issued scoped token on EVERY call

| File | Key behavior | Security | DoD |
|---|---|---|---|
| `app/auth/service_token.py` | Verify signature against Spring's rotating **JWKS** (cache JWKS, honor `kid`). Reject expired / wrong `aud` / wrong `iss` / wrong alg. Assert `token.workspace_id == body.workspace_id` → else 403. Assert scope matches endpoint (`chat:stream` cannot call `/analyze-site`). On any failure: 401/403, structured error, **no provider call, no token spend.** Two token shapes: service token (`aud=influora-internal`, ≤5min, for `/analyze-site`,`/voice/*`,proxied `/chat`) and scoped stream token (`scope=chat:stream`, single-workspace, single-conversation, one-time nonce). | `[SEC: 04 §1.1]` `[SEC: Kabir #2 no lone static key]` `[SEC: LB-2]` | Tests: expired token → 401; wrong `aud` → 403; tenant mismatch (`token.ws != body.ws`) → 403; scope crossover → 403; each rejects **before** any provider call. |

### D2 — Prompt assembly (2 files) — the ~65% cost lever + tenant isolation

| File | Key behavior | Security | DoD |
|---|---|---|---|
| `app/prompt/persona.py` | Meera system prompt, **versioned in git**; global rails (sentence-case, contractions, no "!", verb-first CTAs, never claim to move money). Stamp `PROMPT_VERSION` on every message returned. | `[SEC: 5.3 versioned schema]` | Persona text is code; `PROMPT_VERSION` bumps on any change and is stamped on every reply. |
| `app/prompt/assembler.py` | Three-block layout for Anthropic prompt caching: **Block A** stable tenant-agnostic prefix (persona + 5 tool schemas + rails, `cache_control: ephemeral`); **Block B** per-brand cached (profile/tone/catalog/summary, keyed by `workspace_id`); **Block C** volatile suffix (history + newest turn, uncached). Untrusted content (scraped site, user chat) wrapped in `<untrusted_...>` delimiters as data-not-instructions. | `[SEC: 5.2 cache-key = (prompt_version, workspace_id, session_id)]` `[SEC: 4.6 prompt-injection isolation]` `[SEC: LB-6]` | **Regression test: Brand B's reply never contains Brand A's data.** Block A contains zero brand data. Cache key never global. |

### D3 — Providers (3 files) — httpx clients + circuit breakers

| File | Purpose | Security | DoD |
|---|---|---|---|
| `app/providers/claude.py` | Claude Sonnet chat + streaming; prompt-caching `cache_control` markers; per-provider timeout (first-token 8s), max output tokens, cancellation on client disconnect. | `[SEC: 6 circuit-breaker; chat down → surfaced error, credits NOT consumed]` | Streams tokens; breaker opens on sustained failure; client disconnect cancels in-flight call (no wasted tokens). |
| `app/providers/gemini.py` | Gemini 2.0 Flash — website scrape classify (niche, tone dial, catalog, palette); grammar-cleanup pass option. | `[SEC: 6 breaker; scrape down → "paste a link" degrade]` | Returns structured brand fields; scrape fail → graceful signal, no crash. |
| `app/providers/sarvam.py` | Sarvam STT (Hinglish) + TTS; India-region; latency-sensitive timeouts (STT 10s). | `[SEC: 7 voice failure → silent text fallback]` | STT/TTS work; any failure returns fallback signal, never a dead end. |

### D4 — Routes (3 files)

| File | Key behavior | Security | DoD |
|---|---|---|---|
| `app/routes/chat.py` | **SSE** streaming endpoint (`text/event-stream`). Direct Python→browser SSE using the scoped stream token. Event protocol: `token`, `thinking`, `tool_start`, `tool_result`, `prompt_meta`, `done`, `error`. Heartbeat `: ping` ~15s. On assistant final text, POST it back to Spring via signed callback (persist AiMessage). Latency: TTFT ≤1.2s p50 / ≤2.5s p95 on cache hit. | `[SEC: stream-token validate, one-time nonce]` `[SEC: 5.4 credits gated by Spring first]` | SSE streams incrementally; `thinking`/`tool_*` events drive canvas; TTFT target met on cache hit; final text persisted to Spring. |
| `app/routes/analyze_site.py` | Playwright headless render → Gemini Flash classify → structured brand profile. **Every fetch routes through `ssrf_guard`.** On success POST `/internal/meera/site-analyzed` to Spring (signed, idempotency-key). End-to-end ≤45s. | `[SEC: SsrfGuard]` `[SEC: LB-5 launch-blocking]` `[SEC: 4.6 strip active content from scraped HTML]` | No fetch bypasses `ssrf_guard`; private-IP/metadata URL rejected; result posted to Spring idempotently. |
| `app/routes/voice.py` | `/voice/transcribe` → Sarvam STT (Hinglish) → grammar-cleanup (meaning-preserving, never reinterpret intent) → return `{raw_transcript, cleaned_text, lang_detected}` **edit-first (not auto-sent)**. `/voice/speak` → Sarvam TTS audio stream. Credit weighting surfaced to Spring: input=3, reply=4. | `[SEC: 7 fallback at every stage]` | Cleaned text lands in composer for user edit; every failure path returns text fallback. |

### D5 — Tool loop & schemas (2 files) — the critical money-proposal path

| File | Key behavior | Security | DoD |
|---|---|---|---|
| `app/tools/schemas.py` | The **5 tool JSON schemas** — SINGLE SOURCE, must match `06-MEERA-PERMISSIONS-MATRIX.md` and Spring's executor contract (`02-API-CONTRACT-BRAND.md`): `show_creators`(R), `calculate_budget`(R), `create_campaign`(D), `request_payment`(C), `confirm_launch`(C). `request_payment.display_amount_hint` is chat-copy only, discarded by Spring. | `[SEC: 5.3 name-whitelist; CI diff vs Spring so schemas never drift]` | CI shared-schema diff-check passes; exactly 5 schemas; no sixth tool exists. |
| `app/tools/loop.py` | Function-calling loop. Claude emits `tool_use` → map name → Spring `/internal/meera/{tool}` via `clients/spring.py`. Forward with: service token, `workspace_id`, on-behalf user context, **idempotency key derived from `tool_use.id` + `workspace_id`**, tool input AS-PROPOSED. **Never** read `amount` from Claude and forward as authoritative. If Spring returns `PENDING_CONFIRM`, surface as canvas state — do NOT loop to "done". Loop-iteration cap (≈6) to prevent runaway. | `[SEC: Kabir #1 amount re-derived by Spring]` `[SEC: ToolCallValidator on Python side too]` `[SEC: idempotency]` | Injected/hallucinated amount never forwarded as authoritative; retried stream cannot double-execute (same idempotency key); unknown tool name rejected; iteration cap holds. |

### D6 — Security & clients (3 files)

| File | Key behavior | Security | DoD |
|---|---|---|---|
| `app/security/ssrf_guard.py` | Scheme allowlist (`https` only). Resolve DNS **first**, block private/loopback/link-local/CGNAT (`10/8,172.16/12,192.168/16,127/8,169.254/16,::1,fc00::/7`) **and `169.254.169.254`**. DNS-rebind protection: resolve once, pin IP, connect to that IP. Redirect cap ≤2, every hop re-validated. Response-size + timeout caps. | `[SEC: 4.4 SsrfGuard]` `[SEC: LB-5]` **Kabir gates before this touches the internet** | Tests: `169.254.169.254` → reject; `http://` → reject; DNS-rebind (hostname public, resolves private) → reject; >2 redirects → reject. |
| `app/security/redaction.py` | Log-redaction backstop: scrub PII (PAN/phone/email/bank), token/secret patterns, full prompt bodies/transcripts/audio. Log shapes/lengths/counts, never values. Structured JSON keyed by `workspace_id`+`request_id`+`prompt_version`. | `[SEC: 5.6 no secrets/PII in logs]` `[SEC: Kabir #3]` | No full prompt/catalog/transcript/audio in any log line; redaction filter applied to the pipeline. |
| `app/clients/spring.py` | Signed callback client to Spring `/internal/meera/*`. Computes `InternalRequestSigner` HMAC over `method+path+sha256(body)+timestamp+nonce` → `X-Meera-Signature`; carries `X-Meera-Service-Token` + on-behalf user JWT + `Idempotency-Key`. **No blind retry on money-tool forwards** (Spring owns dedupe). | `[SEC: 2.4 request signing; matches Spring InternalRequestVerifier]` | Every internal call is signed + service-token + on-behalf + idempotency-keyed; money forwards are never blind-retried. |

### D7 — Tests (`tests/`, 1 dir counted as 1 file item)

| Coverage | DoD |
|---|---|
| Chat streaming smoke test; `ssrf_guard` (metadata/private-IP/rebind/redirect rejects); `service_token` validation (expired/wrong-aud/tenant-mismatch/scope); tool-loop (idempotency key stable per `tool_use.id`, amount never forwarded authoritative, unknown tool rejected); tenant-isolation regression (Brand B never sees Brand A). | All pass in CI; `pip-audit` clean. |

**Domain D coordination:** Vikram builds → Kavya QA's streaming behavior → Meera provisions the container/deployment (non-root, read-only FS, egress rules) → **Kabir security-gates `ssrf_guard.py` + `service_token.py` before this touches the internet (RT-G6: no keys ever reach frontend)** → Priya sign-off. The `/internal/meera/*` contract is coordinated with Phase 4 below — Vikram owns both sides; keep `schemas.py` and the Spring executor DTOs in lockstep (CI diff-check).

---

# PHASE 4 — MEERA TOOL EXECUTORS (Domain C remainder, ~13 files) 🔴 HIGH

**Now unblocked** (Phase 1 money core landed). `MeeraInternalController` returns 501 stubs — this phase makes it real. Build the Spring side of the `/internal/meera/*` contract that Domain D's `loop.py` calls into. Reads with `11-AI-FLOW-DETAILED.md` Flow 3 (the critical path) and `06-MEERA-PERMISSIONS-MATRIX.md` (R/D/C tiers).

**Governing rule (matrix):** Meera proposes; Spring disposes; the human commits money. "The customer said yes" in chat is **not** authorization. C-tier commit endpoints are **public** browser endpoints on the human JWT — **never** reachable from `/internal/meera/*`. `request_payment` returns `PENDING_CONFIRM` only; it never debits.

### P4.1 — Read-tier executors (2 files)

| File | Tier | Key behavior | Security | DoD |
|---|---|---|---|---|
| `service/meera/tool/ShowCreatorsExecutor.java` | R | Rank + return matched creators from verified pool, tenant-scoped. No raw creator PII. | `[SEC: TenantGuard]` `[SEC: G3 no PII to prompt]` | Returns only verified stats scoped to `workspaceId`; cross-tenant → 404. |
| `service/meera/tool/CalculateBudgetExecutor.java` | R | Suggest pool + per-reel rate from product price + goal. Suggestion only — charged amount always re-derived at commit. | `[SEC: read-only, no state change]` | Pure computation; writes nothing; returns advisory numbers. |

### P4.2 — Write-tier executors (3 files) — the money trapdoors

| File | Tier | Key behavior | Security | DoD |
|---|---|---|---|---|
| `service/meera/tool/CreateCampaignExecutor.java` | D | Write DRAFT campaign from `campaign_intents` → draft `campaigns` row, `@Transactional`, idempotent via `meera_tool_calls` dedupe. Draft state only; going live is a separate human commit. | `[SEC: IdempotencyService]` `[SEC: draft-state only, no money field writable]` `[SEC: G5 credit-gated]` | Replay with same idempotency key returns prior result (no duplicate draft); no money moves. |
| `service/meera/tool/RequestPaymentExecutor.java` | C | **Amount re-derived server-side** from `campaign_intents.product_price` + fee config via `AmountDerivationService`. Produces `PENDING_CONFIRM` human-confirm action **only — never moves money directly.** AI-supplied `display_amount_hint` discarded. Writes immutable audit record. | `[SEC: Kabir G1 amount re-derivation]` `[SEC: MF-1]` `[SEC: AuditLogService]` `[SEC: LB-3]` **launch-gated** | Injected/tampered amount → `409 AMOUNT_MISMATCH`; returns `PENDING_CONFIRM`, never a debit; audit row written. |
| `service/meera/tool/ConfirmLaunchExecutor.java` | C | Only proceeds if escrow == `FUNDED` **verified from DB** (not asserted by AI). Then invites + escrow-hold + credit reset. Idempotent. | `[SEC: EscrowStateMachine verifies FUNDED from DB]` `[SEC: IdempotencyService]` `[SEC: AuditLogService]` **launch-gated** | AI claiming "funded" cannot trigger launch; only real DB FUNDED state proceeds; idempotent replay-safe. |

### P4.3 — Validator + controller wiring (2 items)

| File | Key behavior | Security | DoD |
|---|---|---|---|
| `service/meera/tool/ToolCallValidator.java` | Validate every tool-call against (a) the versioned JSON schema (name-whitelist of the 5 tools; reject unknown names, extra fields, out-of-enum) **and** (b) the `06` permission matrix (R/D/C tier gating; drop + log any Forbidden F1–F4 or auto-commit-that-should-be-human). No LLM-emitted field trusted for a monetary/authorization decision. | `[SEC: 5.3 schema + matrix]` `[SEC: LB-6]` | Unknown tool name → reject+log; extra field → reject; a tool mapping to Forbidden → dropped+logged. |
| **MODIFY** `web/MeeraInternalController.java` | Replace the 5 stub 501 endpoints (`/internal/meera/show-creators`, `/calculate-budget`, `/create-campaign`, `/request-payment`, `/confirm-launch`) with real calls to the executors above. Also wire `/internal/meera/site-analyzed` callback from Domain D. Runs inside the internal filter chain (Domain E) — dual credential enforced. | `[SEC: 2.1 internal chain]` `[SEC: 2.2/2.3 dual credential]` | All 5 endpoints return real results (no 501); each guarded by service token + on-behalf JWT + HMAC/nonce + idempotency. |

### P4.4 — Boundary auth wiring (referenced from Domain E; build here as the executors need them)

| Item | Key behavior | Security | DoD |
|---|---|---|---|
| **mTLS / signed-service-token auth + network isolation** (T4.5) | `/internal/*` reachable only via dual credential: short-lived `aud=influora-internal` service token (≤60s, pinned alg, distinct key) **and** on-behalf human JWT. Internal listeners bound to private interface; IP allowlist as defense-in-depth. | `[SEC: 2.1/2.2, MF-2, LB-2]` **launch-gated** | Lone static key rejected; internet-exposed `/internal/*` blocked; stolen service token alone cannot pick a victim workspace. |
| **On-behalf-of JWT resolution for read tools** (T4.1) | Even read tools re-validate the forwarded human JWT (`iss`/`aud`/exp/`workspaceId`) so a service token alone can't read arbitrary workspaces. | `[SEC: 2.3 OnBehalfAuthResolver]` | Read executor rejects a call whose on-behalf JWT `workspaceId` ≠ body `workspaceId`. |

**Phase 4 acceptance (Kabir gate, launch-blocking):** injected tool-args cannot move money; amount tampering rejected (`409`); idempotency replay safe; tenant isolation holds; RT-G1/G2/G4 pass; 25-row acceptance checklist green.

**Phase 4 coordination:** Vikram builds → Kavya QA's idempotency/replay behavior → **Kabir gates (launch-blocking, not optional) — no `/internal/meera/*` write endpoint ships without his re-test** → Priya signs off. Coordinate `schemas.py` (Python) ↔ executor DTOs (Spring) — same 5 tools, CI diff-checked, so they never drift.

---

# DOMAIN E — SECURITY HARDENING (~12 net-new + 4 MODIFY + 2 migrations) 🔴 WOVEN

**Not a final bolt-on.** These accompany Domains D and Phase 4 and gate them before live traffic. Source: `09-ADVANCED-SECURITY-MEASURES.md` (the 24 mandatory files; several overlap with A/C/D — build those as the shared implementation, do NOT double-build). Owner: Vikram builds, Kabir specifies + gates.

> **Already-counted-elsewhere (do NOT rebuild here):** `TenantGuard`/`TenantScopedRepository` (verify all built repos extend it), `IdempotencyService` (referenced by A4/Phase 4), `LedgerInvariantValidator`/`EscrowStateMachine`/`PayoutStateMachine` (shared with money services), `WebhookSignatureVerifier` (Domain A, shipped), `SsrfGuard` (Domain D), `ToolCallValidator` (Phase 4). Confirm each exists and is wired; only build the genuinely net-new items below.

### E1 — Internal trust-boundary (net-new)

| File | Spec | Security | DoD |
|---|---|---|---|
| `security/InternalSecurityConfig` | Second `SecurityFilterChain` (`@Order(1)`, `securityMatcher("/internal/**")`) running before the public chain; mesh-only; public `SecurityConfig` gets explicit negative matcher for `/internal/**`. | `[SEC: 2.1, AS-1]` `[SEC: LB-2]` | `/internal/**` never matched by the public chain; internet-exposed internal endpoint blocked. |
| `security/InternalServiceTokenFilter` | Verify `X-Meera-Service-Token` (JWT, `aud=influora-internal`, `exp≤60s`, `iss=meera-python`, pinned alg, distinct key). Reject lone static key. Rotation ≤24h. | `[SEC: 2.2, MF-2, LB-2]` | Expired/wrong-aud/wrong-alg/wrong-iss → reject; static key alone → reject. |
| `security/OnBehalfAuthResolver` | Re-validate forwarded human JWT on `/internal/**`; enforce `workspaceId` match + OWNER/ADMIN for money. | `[SEC: 2.3, G1]` | Stolen service token alone cannot pick a victim workspace. |
| `security/InternalRequestVerifier` + `security/NonceCache` | Verify HMAC over `method+path+bodyHash+timestamp+nonce` (constant-time); reject `|now−ts|>30s`; reject seen nonce (Redis TTL 60s). | `[SEC: 2.4]` | Replayed captured internal call rejected; body-swap after signing rejected. |

### E2 — Money-path (verify shared; net-new where missing)

| File | Spec | Security | DoD |
|---|---|---|---|
| `service/IdempotencyService` + `idempotency_keys` **migration** | `executeOnce(key, supplier)`; `UNIQUE(idempotency_key)` table; insert-first so the DB constraint is the arbiter under concurrency. | `[SEC: 3.1, LB-3]` | Concurrent double-submit yields exactly one effect. |
| `service/AmountDerivationService` | Sole authority for every monetary value; `409 AMOUNT_MISMATCH` on advisory drift beyond tolerance; persist `server_amount`. | `[SEC: 3.2, MF-1, LB-3]` | No DTO carries a chargeable amount; advisory drift → 409, no charge. |
| `LedgerInvariantValidator` / `EscrowStateMachine` / `PayoutStateMachine` | Verify shared implementations from Domain A are wired: sum=0 + no negative balance pre-commit; legal escrow/payout edges only; FUNDED verified from DB. | `[SEC: 3.3/3.5, LB-3/LB-4]` | Illegal transition rejected; single-sided write blocked. |

### E3 — Auth hardening (net-new)

| File | Spec | Security | DoD |
|---|---|---|---|
| `security/RefreshTokenReuseDetector` (`RefreshTokenService`) | `family_id`+`rotated_at`+`superseded_by`; replayed rotated token → revoke whole family, force re-login; one-time-use per token. | `[SEC: 1.4]` | Replaying a rotated-away token trips family revocation. |
| `security/JwtHardeningConfig` (`JwtValidationConfig`) | Alg-pin (single expected), `requireIssuer(influora-api)`, `requireAudience(influora-brand)`, `exp`, clock skew ≤30s. | `[SEC: 1.1, LB-1]` | `alg:none`/alg-confusion/wrong-aud/long-window replay all rejected. |
| `security/TokenRevocationService` *(if session-invalidation in scope)* | `jti`/`tokens_valid_after` revocation for logout-everywhere; Redis-backed, checked in JWT filter. | `[SEC: 1.5]` | Stolen access token killable before natural expiry. |

### E4 — Perimeter, rate-limit, audit (net-new)

| File | Spec | Security | DoD |
|---|---|---|---|
| `security/SecurityHeadersFilter` | HSTS (`includeSubDomains`,1yr), CSP `default-src 'none'; frame-ancestors 'none'; base-uri 'none'`, `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, minimal `Permissions-Policy`. | `[SEC: Layer 7]` | All headers present on every response. |
| `config/CorsConfig` | Explicit origin allowlist (SPA origin); `allowCredentials` only with concrete origin; **never `*` with credentials**. | `[SEC: Layer 7, LB-9]` | CORS `*`+credentials impossible. |
| `security/RateLimitService` | Redis/bucket4j shared store (replace in-memory); per-IP + per-user + per-endpoint; Meera-turn, stream-token-issue, money buckets; enumeration + brute-force lockout; body-size + token caps. | `[SEC: Layer 8, LB-7]` | Limits hold across horizontal scale (not per-node). |
| `service/AuditLogService` + `audit_log` **migration** | Append-only immutable (hash-chained / WORM; no UPDATE/DELETE grant) for every money mutation (`server_amount`, idempotency key, before/after balance), every auth event (incl. rejection reason enum), every AI tool-call (name, tier, schema result, `prompt_version`). Anomaly alerts to Kabir/Rohan. | `[SEC: Layer 9, LB-8]` | Table has no UPDATE/DELETE grant; money+auth+tool-call events all written. |
| `security/SecretsConfig` | Blast-radius separation: LLM ≠ DB ≠ Razorpay ≠ webhook ≠ user-JWT ≠ refresh ≠ internal-token ≠ stream-token ≠ R2 — each independent slot; `@PostConstruct` guard refuses boot on weak/default/shared secret in non-dev. | `[SEC: 1.2/Layer 6, MF-3, LB-1]` | Non-dev boot fails on committed-default/`<32B`/shared secret. |
| `service/CostCircuitBreaker` *(if not shipped with AICreditService)* | Hard per-workspace + global daily token/₹ ceiling; refuses turns (`429`/`402`) when tripped — not logged-and-continued. | `[SEC: 5.5, LB-7]` | Ceiling hit → turns refused, not just logged. |

### E5 — MODIFY (existing live classes, Kabir `[LIVE-GAP]`)

| File | Fix | Security | DoD |
|---|---|---|---|
| `security/JwtService.java` | Alg-pinning, `iss`/`aud`/`exp`/skew validation; mint side stamps `iss`+`aud`+`jti`. | `[SEC: 1.1, LB-1]` | Parser pins alg, validates all claims; forged/wrong-aud rejected. |
| `security/JwtAuthenticationFilter.java` | Stop swallowing exceptions silently; emit structured `auth.token.rejected` event (reason enum `EXPIRED/BAD_SIG/MALFORMED/BAD_AUD/BAD_ALG`, source IP, no token bytes) before clearing context; still 401 never 500. **Verify Phase 0 T0.2 actually landed — predates Phase 1/2 reports.** | `[SEC: 1.3, MF-3/C-22]` | Rejected token logged with reason; never 500; never authenticated. |
| `security/AuthRateLimitFilter.java` | Move from in-memory to distributed store (back onto `RateLimitService`). | `[SEC: Layer 8, LB-7]` | Limits hold under horizontal scale. |
| `config/SecurityConfig.java` | **Restore if still truncated (Phase 0 blocker), then** wire in all new filters, CORS bean, headers filter, `@EnableMethodSecurity`, explicit negative matcher for `/internal/**`. | `[SEC: 2.1, Layer 7]` | Compiles; all new filters wired; `/internal/**` excluded from public chain. |

### THE LAUNCH-BLOCKER CHECKLIST (Kabir re-tests all of these live)

- **[LB-1]** JWT alg-pin + `iss`/`aud`/skew + dev-default secret guard.
- **[LB-2]** No `/internal/meera/*` money endpoint reachable without dual credential (service token + on-behalf JWT); no lone static key.
- **[LB-3]** No money DTO accepts a chargeable `amount`; every mutation has server re-derivation + idempotency `UNIQUE` + `SELECT FOR UPDATE` + ledger sum=0.
- **[LB-4]** No escrow FUNDED without verified webhook signature; no payout reachable from AI surface / without out-of-band human confirm.
- **[LB-5]** Site-analyzer has `SsrfGuard` (metadata/private-IP block + DNS-rebind pinning).
- **[LB-6]** No PII in prompt/log; cache key has `workspace_id`; tool-calls validated vs schema + `06` matrix.
- **[LB-7]** Rate limiting off in-memory for money/Meera routes; cost circuit-breaker present.
- **[LB-8]** Audit log append-only/immutable for money + auth + AI tool-calls.
- **[LB-9]** CI has SCA/CVE gating on high/critical; no CORS `*` with credentials.

**Domain E coordination:** Vikram builds; Kabir specifies + gates every phase (not optional, not a final step); Priya arbitrates any architecture conflicts.

---

# DOMAIN B — NOTIFICATIONS (39 files) 🟡 MEDIUM — no money/AI dependency, parallel anytime

No blockers. Rides alongside everything. Owner: Vikram. Full event list in `07-NOTIFICATION-SYSTEM-SPEC.md`.

**Migrations (3):** `V15__notifications.sql` · `V16__email_outbox.sql` · `V17__email_preferences.sql` — DoD: sequence V1→V17 no gaps.

**Entities (3):** `Notification.java` · `EmailOutbox.java` · `EmailPreference.java` — ULID keys, `@Enumerated(STRING)`, `Instant` timestamps, hand-written getters (no Lombok), builder pattern (match `Campaign.java`).

**Repositories (3):** `NotificationRepository` · `EmailOutboxRepository` · `EmailPreferenceRepository` — `[SEC: TenantGuard]` all tenant-scoped, no unscoped `findAll` leak.

**Services + eventing (3 + 22 event records):**

| File | Key behavior | Security | DoD |
|---|---|---|---|
| `service/notification/NotificationService.java` | Create in-app + queue email row, **idempotent**. | `[SEC: IdempotencyService]` | Duplicate event → one notification. |
| `service/notification/NotificationListener.java` | `@EventListener` across all 22 domain events → NotificationService. | tenant scope | Each of 22 events routes correctly. |
| `service/notification/EmailWorker.java` | `@Scheduled(30s)` outbox poller; exponential backoff on failure. | — | Poll cycle sends pending; failed rows retried with backoff, capped. |
| `service/notification/event/*.java` (22 records) | `CampaignCreatedEvent`, `ProposalSentEvent`, `ShipmentCreatedEvent`, … (full list in `07`). Tiny records, one `event/` package. | — | All 22 present; published by their source services. |

**Integration (1):** `integration/msg91/Msg91EmailClient.java` — template send. DoD: sends via Msg91 template; failure surfaces to outbox retry.

**Controller + DTOs (4):** `web/NotificationController.java` (GET list, POST read, unsubscribe) + `web/dto/notification/*` (~3 DTOs, Bean-Validation annotated). DoD: list scoped to `principal.workspaceId`; unsubscribe writes `EmailPreference`.

**Domain B coordination:** Vikram builds → Kavya QA's the outbox retry/backoff logic → Meera build-verifies → Priya. (Aditya may later want SEO-adjacent lifecycle-email hooks — not in scope unless requested.)

---

# CROSS-CUTTING TASKS

### T5 — Automated tests (rising risk — write incrementally, NOT deferred)

Zero test classes exist today (Swapnil flagged this). Write alongside steps 1–4, prioritizing the highest-risk money paths.

- [ ] `WalletService` — double-entry invariant, concurrent double-spend attempt.
- [ ] `EscrowService` — state-machine transitions, idempotency replay, `validateWebhookAmount` mismatch → `ESCROW_AMOUNT_MISMATCH`.
- [ ] `ContractService` — PDF SHA-256 hash integrity.
- [ ] `PayoutService` — state machine.
- [ ] `AICreditService` — circuit-breaker gate, monthly reset, atomic decrement.
- [ ] `MeeraSessionService` — turn persistence.
- [ ] **Each Phase 4 executor — especially `RequestPaymentExecutor` amount-tampering → `409`.**
- [ ] `influora-ai/tests/` — SSRF guard, service-token validation, streaming smoke test (Domain D7).

**DoD:** ≥1 test class per money/AI service; Kavya reviews coverage against standards; Meera runs them in CI. **Coordination:** Vikram writes → Kavya reviews → Meera runs.

### T6 — Live MySQL migration execution (Meera's territory; Vikram logs)

V8–V14 are only code-verified — never run against a live DB (doc 13 caveat). Flyway checksum/column-type/index issues won't surface until they execute.

- [ ] Meera provisions a live MySQL datasource (dev/staging).
- [ ] Run `flyway:migrate` for V8–V14; then V15–V17 (notifications) on top; then `idempotency_keys` + `audit_log` (Domain E).
- [ ] Smoke-test basic CRUD against each new table post-migration.

**DoD:** All migrations apply cleanly on a live DB with no checksum/DDL errors. **Coordination:** Meera provisions + runs; **Vikram logs each in `wiki/processes/schema-changes.md`**; Kavya verifies data integrity post-migration.

### T7 — Razorpay SDK swap (adjacent to Phase 4, same money surface)

Do this alongside Phase 4 — cheaper to swap once than twice. Priya already approved the dependency.

- [ ] Add `com.razorpay:razorpay-java` to `pom.xml`; confirm entry in `wiki/tech/approved-deps.md`.
- [ ] Replace hand-rolled `java.net.http.HttpClient` calls in `RazorpayClient.java` / `RazorpayXClient.java` with SDK calls.
- [ ] Re-verify `WebhookSignatureVerifier` against the SDK's webhook payload shape (SDK changes response parsing — **do not assume no-op**).
- [ ] **Re-run Kabir's money-endpoint gate after the swap.**

**DoD:** SDK wired; webhook signature verification re-tested against real SDK response shapes; Kabir re-gate green. **Coordination:** Vikram builds → **Kabir re-gates after swap** → Priya signs off.

---

# RECOMMENDED BUILD ORDER & DEPENDENCY GRAPH

```
                 ┌─────────────────────────────────────────────────┐
                 │  (E) SECURITY — woven into every phase below,    │
                 │  gated by Kabir BEFORE anything goes live        │
                 └─────────────────────────────────────────────────┘
                                    │ accompanies
        ┌───────────────────────────┴───────────────────────────┐
        ▼                                                        ▼
┌─────────────────┐   /internal/meera/*   ┌──────────────────────────────┐
│ (D) PYTHON AI   │◄──── contract ───────►│ (PHASE 4) TOOL EXECUTORS     │
│  service        │  (Vikram owns both     │  MeeraInternalController real │
│  loop.py calls ─┼──────────────────────►│  + ToolCallValidator          │
│  Spring         │  sides — coordinate)   │  + dual-credential wiring     │
└─────────────────┘                        └──────────────────────────────┘
        │  together = MEERA ACTUALLY WORKS (not an echo stub)   │
        └──────────────────────────┬───────────────────────────┘
                                    │ + swap once
                                    ▼
                         ┌────────────────────┐
                         │ (T7) Razorpay SDK  │  (same money surface as P4)
                         └────────────────────┘

  (B) NOTIFICATIONS ─── no blockers ─── build in parallel any time
  (T5) TESTS ────────── write incrementally alongside D / P4 / A ── never deferred
  (T6) LIVE DB ──────── Meera runs ASAP, independent; re-run after each new migration
```

**Sequenced plan:**

1. **Domain D + Phase 4 in parallel** — the two together make Meera functional. `loop.py` (D) and the executors (P4) are mutually dependent; coordinate the `/internal/meera/*` contract early. Vikram owns both sides → internal coordination, not a cross-team handoff. **This is your first move.**
2. **Razorpay SDK swap (T7)** — alongside Phase 4, same money surface.
3. **Domain E items with no other phase to ride on** (`AuditLogService`, `RateLimitService`, `RefreshTokenReuseDetector`, `JwtHardeningConfig`, `SecretsConfig`, the 4 MODIFY hardenings) — concurrent with step 1, **gated by Kabir before Domain D sees real traffic.**
4. **Domain B (notifications)** — no blockers; pick up in parallel once bandwidth allows.
5. **Automated tests (T5)** — write incrementally alongside 1–4; prioritize `RequestPaymentExecutor` + `WalletService`.
6. **Live MySQL migration execution (T6)** — Meera runs ASAP (validates V8–V14 already built), and again after each new migration lands.
7. **Final Kabir gate** — full 25-row checklist + RT-G1..G6 + MF-1..4 + LB-1..LB-9 green across Domains A, C (incl. Phase 4), D, E → Priya sign-off → Swapnil final review.

---

# COORDINATION SUMMARY (who Vikram hands to, per domain)

Universal pipeline: **Vikram builds → Kavya (QA, no code passes without her) → Meera (local build-verify + DB/DevOps) → Kabir (security gate — launch-blocking for money/Meera) → Priya (sign-off) → Swapnil (final review).**

| Domain | Vikram hands to | Special coordination |
|---|---|---|
| D — Python AI service | Kavya (streaming) → Meera (container: non-root, read-only FS, egress) → **Kabir (SsrfGuard + token before internet)** → Priya | `schemas.py` ↔ Spring executor DTOs — CI diff-check so they never drift |
| Phase 4 — executors | Kavya (idempotency/replay) → Meera → **Kabir (launch-blocking gate on every write-tier endpoint)** → Priya | The `/internal/meera/*` contract is coordinated with Domain D — Vikram owns both sides |
| E — security | Kabir specifies + gates every phase (not a final step) → Priya arbitrates conflicts | Confirm shared classes (TenantGuard, IdempotencyService, state machines) are wired, not double-built |
| B — notifications | Kavya (retry/backoff) → Meera → Priya | Aditya may want SEO lifecycle-email hooks later (out of scope unless requested) |
| Tests | Kavya (coverage review) → Meera (runs in CI) | Not deferred — write alongside the code |
| Live DB | **Meera runs**; Vikram logs `wiki/processes/schema-changes.md`; Kavya verifies integrity | Meera's territory to execute, not Vikram's |
| Razorpay SDK | **Kabir re-gates after swap** → Priya signs off | SDK changes response parsing — re-test webhook verification, don't assume no-op |

---

**Bottom line:** ~85 new/modified files + 5 migrations + tests + 2 ops items remain. Build **Domain D + Phase 4 in parallel first** — together they turn Meera from an echo stub into a working reasoner, and nothing else unblocks the product. Security (Domain E) is woven in, not bolted on, and **no money file ships until Kabir's RT/MF/LB gate is green.**
