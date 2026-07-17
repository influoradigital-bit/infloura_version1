# 🔌 API CONTRACT — Brand + Meera AI (Spring Boot)

> **Author:** Vikram (Backend) · **Date:** 2026-07-05 · **Milestone:** M2.5
> **Grounded in:** `BACKEND-ARCHITECTURE-DECISION.md`, `BACKEND-API-SPEC.md` (§3 conventions), `01-DATA-MODEL.md`.
> **Security controls cross-referenced to:** `03-SECURITY-SPEC.md` (Kabir). Every 🔒 marker below has a matching control there.

---

## 0. CONVENTIONS (inherited — do not re-invent)

Matches the existing app exactly (`BACKEND-API-SPEC.md` §3, `ApiResponse.java`, `AuthPrincipal.java`, `SecurityConfig.java`).

- **Base URL:** `/api/v1` (public); internal endpoints under `/internal/**` (see §3).
- **Envelope:** `ApiResponse<T>` → `{ success, data, error, meta, timestamp }`. Errors → `{ success:false, error:{ code, message, field }, timestamp }`. Use `ApiException(code, message, HttpStatus)`.
- **Auth (public):** `Authorization: Bearer <access_token>`, HS256 JWT carrying `sub`(userId), `userType`, `email`, `workspaceId`. Resolved to `AuthPrincipal` by `JwtAuthenticationFilter`. Controllers read `@AuthenticationPrincipal AuthPrincipal principal`.
- **IDs:** ULID `VARCHAR(26)`.
- **Every public Meera endpoint is workspace-scoped** off `principal.getWorkspaceId()` — never a body-supplied workspace id (Guardrail 4).
- **New public controllers:** `MeeraController` (`/meera`), `CreditController` (`/meera/credits`), `EscrowController` (`/wallet/escrow`), reusing `CampaignController` where possible.

---

## 1. PUBLIC BRAND ENDPOINTS (browser → Spring)

All require a valid brand JWT; all scoped to `principal.workspaceId`. These are the only surface the browser touches — the browser never calls Python or the internal endpoints.

### 1.1 Start / resume a Meera session
```
POST /meera/sessions
Auth: Bearer (BRAND)
```
Idempotent-ish: reuses the workspace's ACTIVE conversation or opens one.
**Response 201:**
```json
{ "success": true, "data": {
  "conversationId": "01J...",
  "status": "ACTIVE",
  "brandProfileStatus": "READY",
  "credits": { "remaining": 100, "unlimited": false }
}}
```
🔒 Blocked if `brandProfileStatus != READY` returns `data.status = "ANALYZING"` (poll). Credit-gated: opening a session does not spend; sending a turn does.

### 1.2 Send a turn → get a stream token (see §4 for streaming design)
```
POST /meera/sessions/{conversationId}/messages
Auth: Bearer (BRAND)
Body: { "content": "The new vitamin C serum we just dropped" }
```
Spring: (1) verifies conversation belongs to `workspaceId`, (2) **credit gate + decrement** via `AICreditService.tryConsume` (🔒 Guardrail 5 — hard circuit-breaker, `402 CREDITS_EXHAUSTED` → soft paywall), (3) persists the USER `ai_messages` row, (4) assembles the **sanitized** brand-context (allow-list only — 🔒 Guardrail 3), (5) issues a short-lived stream token.
**Response 200:**
```json
{ "success": true, "data": {
  "messageId": "01J...",
  "streamToken": "eyJ... (≤60s, aud=meera-stream, scoped workspace+conversation)",
  "streamUrl": "https://ai.influora.internal/stream",
  "creditsRemaining": 99
}}
```
🔒 `CREDITS_EXHAUSTED` (402), `CONVERSATION_NOT_FOUND` (404, cross-tenant hidden as 404), `BRAND_PROFILE_NOT_READY` (409).

### 1.3 Credit status
```
GET /meera/credits
Auth: Bearer (BRAND)
```
```json
{ "success": true, "data": {
  "creditsRemaining": 99, "monthlyAllotment": 100,
  "unlimited": false, "unlimitedUntil": null,
  "cycleStart": "2026-07-01", "state": "FREE"
}}
```

### 1.4 Escrow — fund (go live)
```
POST /wallet/escrow/fund
Auth: Bearer (BRAND — workspace OWNER or ADMIN)
Idempotency-Key: <client-uuid>   (header, required)
Body: { "campaignId": "01J...", "milestoneId": null }
```
🔒 **The amount is NOT in the body.** Spring re-derives it from the campaign's milestones / intent (money guardrail 1). Verifies wallet balance, creates `escrow_holds` (PENDING→FUNDED), writes the paired `wallet_transactions` legs inside `@Transactional`, fires `EscrowFundedEvent` (→ credit reset). Returns the Razorpay order for the human to confirm; escrow only funds on webhook verification.
```json
{ "success": true, "data": {
  "escrowHoldId": "01J...", "amount": 17250, "currency": "INR",
  "razorpayOrderId": "order_...", "status": "PENDING" }}
```
🔒 `402 INSUFFICIENT_FUNDS`, requires OWNER/ADMIN, `Idempotency-Key` dedup against `wallet_transactions.idempotency_key`.

### 1.5 Escrow — status
```
GET /wallet/escrow/{escrowHoldId}
Auth: Bearer (BRAND) — must belong to workspace
```
Returns `status` (PENDING/FUNDED/RELEASED/…), `amount`, linked campaign/milestone, `fundedAt`.

### 1.6 Create campaign from intent (browser-driven confirm path)
```
POST /meera/sessions/{conversationId}/create-campaign
Auth: Bearer (BRAND)
Body: { "intentId": "01J..." }
```
Human-initiated equivalent of the internal `create_campaign` executor. Spring re-derives budget from the intent's `product_price` + `creator_count` (ignores `proposed_budget`), creates the real `campaigns` row, stamps `campaign_intents.campaign_id`. Returns the created `CampaignResponse` (same DTO as `CampaignController`).
🔒 amount re-derived server-side; workspace-scoped; idempotent on `intentId`.

### 1.7 Website analysis status (poll during onboarding)
```
GET /meera/brand-profile
Auth: Bearer (BRAND)
```
Returns `analysisStatus`, `niche_tags`, `product_catalog` summary, or `analysis_error` for the paste-a-link fallback (PRD §9).

---

## 2. THE TRUST BOUNDARY (why §3 exists)

```
Browser ──Bearer JWT──► Spring PUBLIC (auth + credit gate + decrement + sanitize)
                              │  hands sanitized brand-context + issues stream token
                              ▼
                        PYTHON AI SERVICE (untrusted reasoner; no DB, no money creds)
                              │  parses tool_use → money/state tool-call?
                              ▼
                        Spring INTERNAL /internal/meera/*  (service-auth + on-behalf-of user JWT,
                        idempotent, amount RE-DERIVED, @Transactional) ──► MySQL
```
**Python proposes, Spring disposes.** Python calls §3 endpoints; it never writes MySQL and never authorizes money.

---

## 3. INTERNAL ENDPOINTS — `/internal/meera/*` (Python → Spring, tool-call executors)

**Not internet-routable** (private mesh only — 🔒 Guardrail 2). **Dual auth on every call:**
1. **Service identity:** short-lived signed service token (≤5 min, `aud=influora-internal`), or mTLS — **NOT** a lone static `INTERNAL_API_KEY` (that anti-pattern is explicitly retired, ruling §must-fixes). Header: `X-Meera-Service-Token`.
2. **On-behalf-of user:** the original brand JWT forwarded as `X-Onbehalf-Authorization` — Spring **re-authorizes it** (validity, `workspaceId`, OWNER/ADMIN for money) before executing. Guardrail 1.

**Every call carries `Idempotency-Key`** (the Anthropic `tool_use.id`), deduped against `meera_tool_calls.idempotency_key`. Retries return the prior result, never a second effect.

**Global rule for money executors:** the request DTO's amount/budget fields are **advisory and ignored for charging**. Spring re-derives the authoritative amount from persisted state (`campaign_intents.product_price`, `payment_milestones.amount`) and records it in `meera_tool_calls.server_amount`. A mismatch beyond tolerance → `409 AMOUNT_MISMATCH`, no charge (🔒).

Envelope is the same `ApiResponse<T>`. Filter chain: internal filter first (rejects requests lacking mesh identity), then on-behalf-of JWT resolution.

### 3.1 `show_creators` — READ
```
POST /internal/meera/show_creators
Auth: X-Meera-Service-Token + X-Onbehalf-Authorization
Body: { "workspaceId":"01J...", "niche":"skincare", "location":"Mumbai", "count":15 }
```
Read-only. Ranks `creator_profiles` + `platform_stats` (CreatorMatcherService). No idempotency needed (safe/read), but still service-authed and workspace-scoped. Returns creator cards (allow-listed public stats only — 🔒 no creator PII, Guardrail 3).
```json
{ "success": true, "data": { "creators": [ { "creatorId":"01J...","displayName":"…","followers":150000,"engagementRate":5.2 } ], "matchedTotal": 38 }}
```

### 3.2 `calculate_budget` — READ
```
POST /internal/meera/calculate_budget
Body: { "workspaceId":"01J...","productPrice":899,"goal":"HYPE","creatorCount":15 }
```
Read/compute-only (BudgetCalculatorService). Returns the **server's** budget — this is the number Meera is allowed to quote.
```json
{ "success": true, "data": { "pool":15000, "perCreator":1000, "platformFee":2250, "total":17250 }}
```

### 3.3 `create_campaign` — WRITE (state, no money yet)
```
POST /internal/meera/create_campaign
Headers: Idempotency-Key: <tool_use.id>
Body: { "workspaceId":"01J...","conversationId":"01J...","intentId":"01J...",
        "campaignType":"HYPE","proposedBudget":15000 }   // proposedBudget IGNORED for charge
```
Spring: re-authorize on-behalf JWT (OWNER/ADMIN), re-derive budget from the intent, create `campaigns` row (status DRAFT), upsert `campaign_intents` (status CONFIRMED, stamp `campaign_id`), record `meera_tool_calls` (server_amount). No money moves here.
```json
{ "success": true, "data": { "campaignId":"01J...","status":"DRAFT","serverBudget":15000 }}
```
🔒 idempotent (`Idempotency-Key`), workspace-scoped, on-behalf re-auth, amount re-derived.

### 3.4 `request_payment` — WRITE (money — produces a PENDING human-confirm action)
```
POST /internal/meera/request_payment
Headers: Idempotency-Key: <tool_use.id>
Body: { "workspaceId":"01J...","campaignId":"01J...","milestoneId":null,"amount":17250 }  // amount IGNORED
```
🔒 **Does NOT move money and does NOT trust `amount`.** Spring re-derives the total from the campaign/milestones, creates a **PENDING `escrow_holds`** + Razorpay order, and returns a payment action the **human confirms** in the browser (Guardrail 1 — LLM never authorizes money). Records `meera_tool_calls.server_amount`.
```json
{ "success": true, "data": {
  "escrowHoldId":"01J...","serverAmount":17250,"currency":"INR",
  "razorpayOrderId":"order_...","action":"AWAIT_HUMAN_CONFIRM" }}
```
🔒 idempotent; OWNER/ADMIN on-behalf re-auth; `402 INSUFFICIENT_FUNDS`; `409 AMOUNT_MISMATCH` if derived ≠ proposed.

### 3.5 `confirm_launch` — WRITE (state transition, post-funding)
```
POST /internal/meera/confirm_launch
Headers: Idempotency-Key: <tool_use.id>
Body: { "workspaceId":"01J...","campaignId":"01J..." }
```
Only succeeds if the campaign's escrow is **FUNDED** (verified against `escrow_holds`, not asserted by the AI). Transitions campaign DRAFT→ACTIVE, triggers creator invites (CampaignAutoCreatorService). No money moves (already escrowed).
```json
{ "success": true, "data": { "campaignId":"01J...","status":"ACTIVE","invitesQueued":40 }}
```
🔒 idempotent; verifies FUNDED state server-side; on-behalf re-auth.

### 3.6 Turn-completion write-back (Python persists assistant output)
```
POST /internal/meera/messages
Headers: Idempotency-Key: <turn-id>
Body: { "conversationId":"01J...","role":"ASSISTANT","content":"…","metadata":{ "promptVersion":"v3","toolUse":[…],"tokenUsage":{…} } }
```
Persists the ASSISTANT `ai_messages` row with `prompt_version` (audit trail, ruling §versioning). Idempotent on turn id.

---

## 4. STREAMING DESIGN (token stream to the browser)

Per the ruling: **token streaming goes direct Python→browser via SSE**, but **Spring owns auth + credit gate + stream-token issuance** so blocking Spring MVC stays out of the token path.

**Flow:**
1. Browser calls `POST /meera/sessions/{id}/messages` (§1.2). Spring does auth + **credit decrement** + context sanitize.
2. Spring mints a **short-lived scoped stream token** (JWT, ≤60s, `aud=meera-stream`, claims: `workspaceId`, `conversationId`, `messageId`, `userId`) signed with a key the Python service can verify. Credits are already spent — the stream token cannot be replayed to spend more (single-use, `messageId`-bound; 🔒 tracked server-side).
3. Browser opens `EventSource` directly to Python `GET {streamUrl}?token=<streamToken>` (Python endpoint on the mesh edge, TLS). Python **verifies the stream token** (aud, exp, signature) before emitting a single byte — no valid token, no stream.
4. Python streams SSE tokens; on tool_use it calls §3 internal endpoints; on completion it calls §3.6 to persist the assistant message.
5. On stream error/timeout, browser falls back to `GET /meera/sessions/{id}/messages?after=<messageId>` (Spring, non-stream) to fetch the finalized turn.

**Spring's responsibilities (documented here, enforced there):**
- 🔒 Auth + tenant check before any token is issued.
- 🔒 Credit gate + decrement **before** issuing the stream token (a cost attack can't reach Python directly — Guardrail 5).
- 🔒 Stream token: short TTL, `aud`-scoped, single-use per `messageId`, workspace+conversation bound (Guardrail 4). LLM/provider keys never leave Python (Guardrail 6) — the browser only ever holds this scoped stream token, never an LLM key.
- Python is **not** on the public internet for the internal path; only the SSE stream edge is reachable, and only with a valid stream token.

---

## 5. MONEY-TOUCHING ENDPOINT CONTROL MATRIX (🔒 → `03-SECURITY-SPEC.md`)

| Endpoint | Moves money? | Amount source | Auth | Idempotency | Controls (Kabir) |
|---|---|---|---|---|---|
| `POST /wallet/escrow/fund` (1.4) | Yes (on webhook) | server-derived | Bearer OWNER/ADMIN | `Idempotency-Key` → `wallet_transactions` | balance check, human-confirm, ledger `@Transactional` |
| `POST /meera/.../create-campaign` (1.6) | No | server-derived | Bearer BRAND | on `intentId` | workspace scope |
| `POST /internal/meera/create_campaign` (3.3) | No | server-derived | Service token + on-behalf JWT | `Idempotency-Key` | mesh identity, on-behalf re-auth |
| `POST /internal/meera/request_payment` (3.4) | No (PENDING only) | **re-derived, `amount` ignored** | Service token + on-behalf OWNER/ADMIN | `Idempotency-Key` | LLM-never-authorizes, human-confirm, AMOUNT_MISMATCH guard |
| `POST /internal/meera/confirm_launch` (3.5) | No (already escrowed) | n/a | Service token + on-behalf JWT | `Idempotency-Key` | verifies FUNDED server-side |
| `POST /internal/meera/show_creators` (3.1) | No (read) | n/a | Service token + on-behalf | n/a (safe) | no creator PII, workspace scope |
| `POST /internal/meera/calculate_budget` (3.2) | No (read) | n/a | Service token + on-behalf | n/a (safe) | server-authoritative number |
| `POST /meera/.../messages` (1.2, stream issue) | No | n/a | Bearer BRAND | n/a | credit gate+decrement BEFORE Python; scoped stream token |

**Universal money rules (all rows):** amounts re-derived server-side and never trusted from the AI service; money mutations run in Spring `@Transactional` with a UNIQUE idempotency key; internal endpoints reject any request lacking mesh identity; `INTERNAL_API_KEY`-as-sole-auth is prohibited.
