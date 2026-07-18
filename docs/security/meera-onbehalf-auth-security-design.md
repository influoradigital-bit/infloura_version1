# Meera On-Behalf Tool Authorization — Security Design

Author: Kabir (Red-Team / Offensive Security Lead)
Scope: Defensive DESIGN pass on Influora's OWN application. Authorized. No code changes proposed as part of this document — this is a threat model + safe-enablement design. Every requirement below cites `file:line` evidence.
Status: Design review. Contains a GO/NO-GO verdict (see §8).

---

## 0. Executive summary — what is actually in the code

Priya's brief describes a synchronous `onbehalf_jwt = ""` in a Java `buildChatRequestBody`. **That code no longer exists.** `MeeraChatAiClient.java` and the synchronous Java→Python call were removed in the Wave 2 streaming-first refactor (`MeeraSessionService.java:30-42` javadoc). The system today is materially further along than the brief implies, and the real on-behalf mechanism is already wired end-to-end:

- Browser opens the SSE turn **directly** against Python `POST /chat`, authenticated by a scoped **stream token** in the `Authorization` header (`useMeeraStream.ts:259-268`, minted at `StreamTokenService.java:73-96`).
- The browser **also** puts an `onbehalf_jwt` in the POST **body** (`MeeraChatPanel.tsx:323-329`), and Python forwards it verbatim to Spring on every tool call (`loop.py:212-219`, `spring.py:117-128`).
- Spring gates each `/internal/meera/*` call through a dual-credential mesh: `InternalServiceTokenFilter` (service token + HMAC/replay, `InternalServiceTokenFilter.java:77-117`) then `OnBehalfAuthResolver.resolveForWorkspace` (re-validates the human JWT, asserts `token.workspaceId == body.workspace_id`, `OnBehalfAuthResolver.java:51-74`) then `ToolCallValidator` (5-tool whitelist + tier, `ToolCallValidator.java:67-101`).
- Money is already handled correctly: `request_payment`/`confirm_launch` only ever return `PENDING_CONFIRM`; the actual money movement is a **separate** browser endpoint on human click (`MeeraInternalController.java:58-61`, `MeeraWorkspace.tsx:61-81`, the "Kabir B1" precedent).

So the question is not "how do we build on-behalf auth" — it is **"the on-behalf plumbing exists but the credential it carries is wrong, and two guardrails are documented-but-not-enforced. Fix those before turning it on."**

**The headline finding:** the on-behalf credential the browser forwards is the user's **full-lifetime, full-scope access JWT**, read straight out of `localStorage` — from the exact key an earlier hardening (`token-store.ts`, "H-30") deliberately emptied because `localStorage` is XSS-readable. See §1-A. This is the single must-fix before enabling.

---

## 1. Threat model (prioritized)

### 1-A. [CRITICAL] On-behalf credential is the full user access token, sourced from XSS-readable storage

Evidence:
- `MeeraChatPanel.tsx:327` — `onbehalf_jwt: localStorage.getItem('brand_token') ?? ''`.
- `token-store.ts:1-16` — "H-30" hardening: the access JWT was **moved out of `localStorage` (`brand_token`/`creator_token`) into a module-level in-memory variable** precisely because "an XSS bug anywhere in the app (or a compromised third-party script) could steal a durable, replayable token." The canonical accessor is now `getMemoryToken('brand')` (`token-store.ts:21-23`).
- `spring.py:120` — that token is placed in the `X-Onbehalf-Authorization` header on **every** outbound internal call.
- `OnBehalfAuthResolver.java:104-115` — Spring parses it with `jwtService.parseAccessToken(...)`, "the exact same parser as the public `JwtAuthenticationFilter`" (`OnBehalfAuthResolver.java:16-19`). i.e. it is a **full public-API access token**, not a Meera-scoped one.

Why this is the top risk:
1. **Storage regression.** Reading `localStorage.getItem('brand_token')` reintroduces the exact vulnerability `token-store.ts` was written to close. Either (a) `brand_token` is empty in `localStorage` post-H-30, so `onbehalf_jwt` is `''` and tools silently 401 into text (this is almost certainly the real reason Priya observes "`onbehalf_jwt=""` on purpose, tools degrade to text" — the `""` is not a hardcoded constant, it is the empty read of an emptied key), **or** (b) something is still writing the access token back to `localStorage`, in which case any XSS in the app steals a full account token. Both are unacceptable; "wire up `localStorage.brand_token`" would be the wrong fix.
2. **Over-broad blast radius.** The token authorizes the *entire* public API, not just the 5 Meera tools. `OnBehalfAuthResolver` checks only signature/exp/`workspaceId`/`userType` (`OnBehalfAuthResolver.java:51-73`) — there is **no scope/allowed-tools claim** constraining it to Meera. A separate service (Python), its process memory, its logs, or a compromise now holds a credential good for everything the user can do.
3. **Long replay window.** Its TTL is the full access-token lifetime (minutes-to-hours), not the ≤60s the stream token gets (`StreamTokenService.java:48`). A captured on-behalf token is replayable far past the turn.

Attack scenarios: XSS anywhere in the SPA → exfiltrate a full-account token; a Python-side log/crash dump/SSRF that captures request headers → full-account token; a malicious npm dependency reading the forwarded body.

### 1-B. [HIGH] Stream-token single-use / replay is documented but NOT enforced

Evidence:
- `StreamTokenService.java:66-72` (javadoc): "Single-use is enforced by the caller binding the token to `messageId` **and Python not accepting replays for an already-streamed message (tracked server-side per the contract)** — this method only mints; it does not track consumption."
- `service_token.py:223-274` (`verify_token`) — the actual Python verifier checks signature, `exp`, `aud`, `iss`, `scope`, and `workspace_id`. **There is no `jti`/`messageId` consumption tracking anywhere.** Nothing rejects a second presentation of the same stream token within its ≤60s life.
- `chat.py:95-104` only binds `conversation_id` when the token carries one — it does not consume/burn the token.

Impact: within the ≤60s window a captured stream token can be replayed to trigger a **second full, paid LLM turn** (token-spend abuse; note the spend gate at `chat.py:114` is global, not per-token-jti). Each replay re-runs the tool loop with **fresh** `tool_use.id`s → **fresh** idempotency keys (`spring.py:72-77`, key = `tool_use.id:workspace_id`) → a **duplicate draft campaign** (`CreateCampaignExecutor` dedups on the key, `CreateCampaignExecutor.java:79-83`, but a new key is a new campaign). The assistant write-back is idempotent on the stable `turn_id` (`chat.py:284`, `spring.py:224`), so that one path dedups; campaign creation does not.

### 1-C. [HIGH] `conversation_id` is not re-validated against the tenant in the tool executors (Guardrail-4 gap on the tool path)

Evidence:
- `MeeraInternalController.java:130,148,163` — tool routes pass `conversationIdOf(body)` (an attacker-influenceable body field, `MeeraInternalController.java:217-220`) straight into executors.
- `OnBehalfAuthResolver.resolveForWorkspace` validates `token.workspaceId == body.workspace_id` (`OnBehalfAuthResolver.java:54-62`) — it validates the **workspace**, never that `conversation_id` belongs to that workspace.
- `CreateCampaignExecutor.java:130-149,170-180` — writes `CampaignIntent.conversationId` and the `meera_tool_calls` ledger row using the body's `conversation_id` unchecked.
- Contrast the `/messages` write-back path, which does it correctly: it resolves the conversation → workspace and cross-checks the JWT against **that** (`MeeraInternalController.java:179-180`, `MeeraSessionService.java:208-224` "Guardrail 4"). The tool path does the inverse and skips the check.

Impact: a user legitimately authorized for workspace A can stamp A's created campaign/intent/tool-call ledger rows with a `conversation_id` that belongs to workspace B, polluting B's conversation-scoped history and the tool-call audit ledger. The created `Campaign.workspaceId`/`CampaignIntent.workspaceId` are correctly A (so this is not a cross-tenant money move), but the conversation linkage is forgeable. Lower blast radius than 1-A/1-B, still a tenant-isolation defect that must close before draft/commit tools ship.

### 1-D. [MEDIUM→contained] Prompt-injection / confused-deputy steering tool calls

Evidence of existing containment:
- Untrusted brand text and user chat are angle-bracket-neutralized **and** delimiter-wrapped before ever entering a prompt (`assembler.py:64-80,97-105,196-231`; the structural neutralizer is `untrusted.py:14-58`). Both layers are required and the neutralization is not the bypassable single-`.replace()` an earlier review flagged.
- Block A (persona + tool schemas) carries **zero** brand data; PII fields are stripped defense-in-depth (`assembler.py:11-14,37-53,83-94`).
- Unknown roles in replayed history are treated conservatively as untrusted user data (`assembler.py:228-230`, the `unknown_role` wrap).
- Unknown/hallucinated tool names are rejected before any forward (`loop.py:184-196`, `ToolCallValidator.java:67-83`).
- Money tools cannot be auto-executed (see 1-E).

Residual risk: injection can still induce a **successful** call to an in-whitelist tool — most importantly `create_campaign` (D-tier, executes for real). That is bounded (no money, `Campaign.status=DRAFT`, no invites, reversible — `CreateCampaignExecutor.java:151-165`), but a determined injection could spam draft campaigns. Acceptable for rollout **if** rate-limited and behind the phased gate (§8), not before.

### 1-E. [LOW / by-design-correct] LLM auto-executing money / irreversible actions

Evidence this is already correct and must stay correct:
- `request_payment`/`confirm_launch` are `commit` tier and only ever return `PENDING_CONFIRM`; the loop explicitly does **not** auto-advance to done on a pending-confirm (`loop.py:238-257`, `schemas.py:16-17,110-142`).
- The controller documents "Meera proposes; Spring disposes; the human commits money" and there is **structurally no endpoint** for payment-method/payout/go-live-money changes (`MeeraInternalController.java:56-61`).
- Actual escrow funding is a separate, server-authoritative, human-clicked endpoint with no client-supplied amount (`MeeraWorkspace.tsx:61-81`, the B1 precedent).
- Python never treats any AI-supplied amount as authoritative; `display_amount_hint` is chat-copy only and Spring re-derives every amount (`loop.py:3-8,203-207`, `schemas.py:110-128`).

This is the model to preserve. The design below hard-codes it as an invariant.

### 1-F. [MEDIUM] Voice cost-abuse + PII in audio/transcripts

Evidence:
- Spend gate runs before every provider call on both voice routes (`voice.py:165-178,265-272`), edit-first (never auto-sent — module docstring `voice.py:14-16`), TTS capped at 200 chars (`voice.py:29,105-138,274-304`).
- Gaps: (a) **no per-workspace voice rate limit** — the only ceiling is the *global* spend gate (`voice.py:165`), so one workspace can drive STT/TTS cost up to the global kill-switch; (b) `cleaned_text` flows into the composer → chat → is persisted to `ai_messages` (`chat.py:273-286`, `MeeraSessionService.doPersistAssistantWriteback`), so **voice-derived PII lands in the durable transcript**; log redaction (`shape_of`, `voice.py:183`) does not cover storage; (c) audio bytes appear transient (read into memory at `voice.py:180`, not persisted in-route) — good, but there is no explicit retention statement to hold that line.

---

## 2. On-behalf JWT contract (the fix for 1-A)

Replace "forward the user's full access token" with a **purpose-minted, per-turn, narrowly-scoped on-behalf token**, minted in Java at the same point the stream token is minted.

Mint location: `MeeraSessionService.doSendTurn` at `MeeraSessionService.java:200`, immediately alongside `streamTokenService.mint(...)`. Return it in the `TurnResult` (`MeeraSessionService.java:425-430`) so `sendTurn` hands both tokens to the browser together. The browser then forwards the **on-behalf token** as `onbehalf_jwt`, and **must stop reading `localStorage.getItem('brand_token')`** (`MeeraChatPanel.tsx:327`).

Claims (all required; reject if any absent):

| Claim | Value | Rationale |
|---|---|---|
| `iss` | `influora-api` | Match existing issuer discipline (`StreamTokenService.java:51-53`). |
| `aud` | `meera-onbehalf` | **Distinct** from `meera-stream` (`StreamTokenService.java:49`) and from the public access-token audience, so this token is useless on the public API and vice-versa. |
| `sub` | userId | Who is acting. |
| `workspaceId` | tenant | Checked at `OnBehalfAuthResolver.java:54-62`. |
| `userType` | brand user type | Already consumed at `OnBehalfAuthResolver.java:64-73`. |
| `conversationId` | the turn's conversation | Enables the 1-C fix (executor cross-check). |
| `turnId` / `messageId` | the USER message id | Binds the token to one turn; enables single-use (1-B). |
| `scope` | e.g. `meera.tools` (and optionally a per-tier allow-list) | Constrains the token to the Meera tool surface only — closes the over-broad-authority half of 1-A. |
| `jti` | random UUID | Single-use tracking key. |
| `iat` / `exp` | now / now + ≤120s | Short replay window. Hard-cap in code exactly like `StreamTokenService.MAX_TTL_SECONDS` (`StreamTokenService.java:48,74`) so a misconfigured env cannot widen it. |

Signing key: **reuse the asymmetric ES256 JWKS keypair** already used for the stream token (`StreamTokenService.java:77-95`, `SpringJwksKeyService`). Rationale: `OnBehalfAuthResolver` currently validates the on-behalf token with the *public-API* `JwtService` parser (`OnBehalfAuthResolver.java:110`). Moving on-behalf tokens onto the same **dedicated Spring identity / published JWKS** the stream token uses gives one clean verify path and keeps the on-behalf token cryptographically independent of the public access token — a compromise of one must not forge the other (the same "distinct signing key" principle already stated for the stream token at `MeeraStreamProperties.java:6-11`). Do **not** reuse the HS256 shared `signingSecret` — it is already dead config slated for removal (`StreamTokenService.java:25-27`).

Single-use / replay defense: on the write path, `OnBehalfAuthResolver` (or a thin wrapper it calls) records `jti` in a short-TTL consumed-token store (Redis with TTL = token TTL, or a DB unique index — mirror the existing nonce/replay store behind `InternalRequestVerifier`) and rejects a second presentation of the same `jti`. Because the token is per-turn and ≤120s, the store stays tiny. This also lets Spring bound "one on-behalf token authorizes at most the tool calls of one turn."

---

## 3. Validation requirements on `MeeraInternalController` — mandatory checklist

Every `/internal/meera/*` write MUST pass **all** of these before any executor runs. Items marked ✅ EXIST are already implemented; items marked ➕ ADD are the gaps this design requires.

1. ✅ **Service-token gate** — `X-Meera-Service-Token` is a well-formed JWT, `aud=influora-internal`, `iss=meera-python`, pinned HS256 (no `alg:none`), `exp` present, TTL ≤ ceiling. `InternalServiceTokenFilter.java:119-144`.
2. ✅ **Request signature / replay** — `X-Meera-Signature` HMAC over `method+path+sha256(body)+timestamp+nonce`, nonce replay-checked. `InternalServiceTokenFilter.java:99-110`, `spring.py:64-69`.
3. ➕ **On-behalf token signature/alg** — validate against the dedicated JWKS keypair (ES256), **not** the public `JwtService.parseAccessToken` path. Update `OnBehalfAuthResolver.java:104-115`.
4. ➕ **On-behalf `aud == meera-onbehalf`** — reject a public access token presented here (and vice-versa). New check in `OnBehalfAuthResolver`.
5. ✅ **On-behalf `exp` / well-formed** — `OnBehalfAuthResolver.java:104-115` (retained).
6. ➕ **On-behalf `iss` required** — assert `iss=influora-api` (currently not checked on this path).
7. ✅ **`token.workspaceId == body.workspace_id`** — `OnBehalfAuthResolver.java:54-62`. **This is the workspace half of Guardrail 4 and it already works.**
8. ➕ **`token.conversationId == body.conversation_id` AND that conversation belongs to `token.workspaceId`** — closes 1-C. The executor (or controller) must load the conversation and assert `conversation.workspaceId == token.workspaceId` before writing (the pattern already used on the `/messages` path at `MeeraInternalController.java:179-180`). Apply to `create_campaign`, `request_payment`, `confirm_launch`.
9. ➕ **On-behalf `scope` allows the tool tier** — assert the token's `scope` permits Meera tools (and, if per-tier scopes are adopted, that a C-tier route got a C-capable scope). Closes the over-broad-authority half of 1-A.
10. ➕ **On-behalf `jti` single-use** — reject a replayed `jti` (§2). Closes 1-B on the write path.
11. ✅ **Tool-name whitelist + non-FORBIDDEN tier** — `ToolCallValidator.java:67-101`, invoked per route (`MeeraInternalController.java:105,115,127,145,160`).
12. ✅ **C-tier requires OWNER/ADMIN** — `resolveForWorkspaceRequiringElevatedRole` (`OnBehalfAuthResolver.java:82-102`) on `request_payment`/`confirm_launch` (`MeeraInternalController.java:143-144,158-159`).
13. ✅ **Idempotency-Key required on money/state routes** — headers on `create_campaign`/`request_payment`/`confirm_launch` (`MeeraInternalController.java:123,137,155`), enforced in executors (`CreateCampaignExecutor.java:79-98`).

Items 3,4,6,8,9,10 are the enablement gate. Nothing marked ➕ may be skipped for a "read-only first" phase **that includes any write tool** — but read-only tools (`show_creators`,`calculate_budget`) can ship with 1-11 minus the write-specific 8/13 (see §8).

---

## 4. Tool authorization classes

Split matches the existing tier map (`schemas.py:40-46`, `ToolCallValidator.java:34-40`), and the split is sound — keep it:

**Class (i) — read / draft, safe to let the LLM invoke on-behalf:**
- `show_creators` (R), `calculate_budget` (R) — read-only, no writes (`schemas.py:26-46`, `MeeraInternalController.java:100-118`). No idempotency key needed.
- `create_campaign` (D) — writes a **draft** only: `Campaign.status=DRAFT`, no budget/money field writable, no invites sent, reversible (`CreateCampaignExecutor.java:26-42,151-165`). Safe to auto-invoke **provided** it is idempotent (it is, `CreateCampaignExecutor.java:79-83`), rate-limited (add), and the 1-C conversation cross-check lands.

**Class (ii) — money-moving / irreversible, MUST require explicit human confirmation, MUST NOT auto-execute from an LLM tool call:**
- `request_payment` (C), `confirm_launch` (C) — these **stage** a pending action only; they never move money or go live (`schemas.py:16-17,110-142`, `MeeraInternalController.java:56-61`).
- The **actual** money movement (`payments.fundEscrow`) and go-live are separate browser endpoints invoked on explicit human click, server-authoritative, no client amount (`MeeraWorkspace.tsx:61-81`).

Confirm-gate contract (must hold — this is the B1 precedent generalized):
1. A commit-tier tool returns `{status: PENDING_CONFIRM}` / `{action: AWAIT_HUMAN_CONFIRM}` and nothing else state-changing (`loop.py:238-257`).
2. The loop surfaces it as canvas state and **stops** — it does not loop to "done" on its own (`loop.py:255-257`).
3. The money/go-live action is a **distinct** endpoint the human triggers by click; "Secured"/"FUNDED"/`isPaid` is **server truth**, reconciled against a server-confirmed escrow / `payment.released` SSE event, **never** set from client code, **never** bundled into the proposal step (`MeeraWorkspace.tsx:61-81`).
4. Amounts are re-derived server-side; any AI-supplied amount is advisory display text discarded by Spring (`schemas.py:110-128`, `loop.py:203-207`).

Enabling on-behalf tools must not weaken any of these four.

---

## 5. Idempotency & replay

Existing (keep):
- Money/state forwards carry `Idempotency-Key = tool_use.id:workspace_id`, stable across retries of the **same** stream (`spring.py:72-77`, `loop.py:198-201`, `schemas.py:57-59`).
- Write executors dedup insert-first-wins on the key (`CreateCampaignExecutor.java:79-98`; the shared `IdempotencyService.executeOnce` pattern, also used for send-turn/write-back at `MeeraSessionService.java:137-149,296-318`).
- Money-tool forwards are **never** blind-retried at the HTTP layer (`spring.py:140-148,178`, `loop.py:218`); only read-tier calls get bounded retries.
- The browser **never re-POSTs** after a stream error — it falls back to `GET .../messages?after=` (`useMeeraStream.ts:24-29`, `MeeraChatPanel.tsx:387-408`), so a reconnect does not re-run tools.
- Write-back is idempotent on the stable `turn_id` (`chat.py:284`, `spring.py:224`, `MeeraSessionService.java:284-318`).

Gap to close (ties to 1-B): the idempotency key is derived from Claude's `tool_use.id`, which is **regenerated on a fresh generation**. So the "no duplicate" guarantee depends entirely on the browser not re-POSTing and the stream token not being replayable. Once stream-token single-use is enforced (§2/§3-item-10), a replayed turn is rejected before it can mint new `tool_use.id`s. Until then, treat "duplicate draft campaign on stream replay" as a live gap. Requirement: **single-use stream token + single-use on-behalf `jti` are prerequisites for enabling any write tool**, because idempotency alone does not cover a full-stream replay.

---

## 6. Injection containment

Already strong; requirements are "do not regress + one addition":
- Keep the two-layer untrusted handling (neutralize every `<`/`>` **and** delimiter-wrap) for all brand text, user chat, and any scraped/site content (`assembler.py:64-105,196-231`, `untrusted.py:14-58`, `assembler.py:261-265` for scraped site text). Do not reintroduce a local `.replace()`-based stripper (`assembler.py:74-80`).
- Keep Block A brand-data-free and the PII field strip (`assembler.py:11-14,37-53,83-94`).
- Keep `unknown_role` → untrusted-user wrapping (`assembler.py:228-230`).
- Keep unknown-tool rejection at both layers (`loop.py:184-196`, `ToolCallValidator.java:67-83`).
- **Addition:** because injection can still drive an in-whitelist `create_campaign`, add a per-workspace rate limit on D-tier tool executions (e.g. N draft campaigns / hour) so a successful injection cannot spam the ledger. There is a `DAILY_ACTION_LIMIT_EXCEEDED` signal already referenced (`MeeraSessionService.java:256,353`) — extend/apply it to D-tier tool calls.

Note: the strongest containment is structural and already present — **injection cannot move money** because money is gated behind human click on a separate endpoint (§4). The whole prompt-injection class is thereby capped at "reversible draft pollution," not financial loss. Preserve that boundary above all.

---

## 7. Voice half

Requirements:
- **Keep** spend-gate-before-provider on both routes (`voice.py:165-178,265-272`), edit-first / never-auto-send (`voice.py:14-16`), and TTS 200-char cap (`voice.py:29,274-304`). Voice never enters the tool loop, so no on-behalf tool authority flows through voice — keep it that way (do not add a "voice can confirm" path).
- **Add per-workspace voice rate/abuse limits.** Today the only ceiling is the global spend gate (`voice.py:165`); one workspace can burn cost up to the global kill-switch. Add a per-workspace STT/TTS call cap (reuse the daily-action-limit machinery).
- **PII retention.** `cleaned_text` becomes durable chat content (`chat.py:273-286`). Requirement: (a) document that voice transcripts are stored as ordinary `ai_messages` and subject to the same retention/erasure policy as typed chat; (b) confirm and **assert in code/tests** that raw audio bytes are never persisted or logged beyond the in-memory transcode (`voice.py:180-187` reads then discards); (c) ensure `shape_of` redaction stays on all audio/transcript log lines (`voice.py:181-184,280-284`).

---

## 8. Phased rollout + GO/NO-GO gates

Ordered plan — each phase is independently shippable and gated:

**Phase 0 — Fix the credential (blocking, no tools enabled).**
- Mint the dedicated per-turn on-behalf token in Java (§2); return it from `sendTurn`.
- Frontend: stop reading `localStorage.getItem('brand_token')` (`MeeraChatPanel.tsx:327`); forward the minted on-behalf token instead. Confirm nothing writes the access token back to `localStorage` (uphold `token-store.ts`).
- `OnBehalfAuthResolver`: validate against the dedicated JWKS/aud/iss/scope (checklist items 3,4,6,9).
- Exit gate: a public access token is rejected at `/internal/meera/*`; an on-behalf token is rejected at the public API; unit + integration tests prove both.

**Phase 1 — Read-only tools live.** Enable `show_creators`, `calculate_budget` only. Requires checklist 1-7,9,11-12. Exit gate: red-team confirms cross-tenant read is impossible (token.workspaceId mismatch → 403) and no write tool is reachable.

**Phase 2 — Single-use + conversation binding (blocking for writes).**
- Enforce stream-token single-use and on-behalf `jti` single-use (checklist item 10, §2, §5).
- Enforce `conversation_id` ↔ workspace cross-check in executors (checklist item 8, 1-C).
- Add D-tier per-workspace rate limit (§6).
- Exit gate: replaying a captured stream token → rejected; a create_campaign body with a foreign `conversation_id` → rejected; injection stress test cannot spam past the rate limit.

**Phase 3 — Draft tool live.** Enable `create_campaign`. Requires all of Phase 2. Exit gate: draft-only proven (no money field writable — already true at `CreateCampaignExecutor.java:151-165`), idempotent under retry and under replay.

**Phase 4 — Commit-tier staging live.** Enable `request_payment`, `confirm_launch` **as PENDING_CONFIRM stagers only**. Requires the §4 confirm-gate contract to hold verbatim, C-tier OWNER/ADMIN enforcement (already at `OnBehalfAuthResolver.java:82-102`), and the money endpoints to remain separate/human-clicked/server-authoritative (`MeeraWorkspace.tsx:61-81`). Exit gate: red-team proves no tool call can move money or go live without a distinct human click; amount re-derivation holds.

**Phase 5 — Voice hardening.** Ship the §7 per-workspace voice limits + retention assertions. Not on the money critical path; can run parallel to 1-4.

### "DO NOT set `VITE_API_MODE=live` until…" checklist
`VITE_API_MODE=live` is the master switch that turns the whole real flow on (`isApiLive`, `MeeraChatPanel.tsx:120,290`; the `LIVE-MODE CONTRACT` at `MeeraWorkspace.tsx:69-73`). Do not flip it until **all** hold:

- [ ] On-behalf token is the dedicated per-turn minted token — NOT `localStorage.brand_token` (`MeeraChatPanel.tsx:327` changed; §2).
- [ ] On-behalf token carries `aud=meera-onbehalf`, `iss`, `scope`, `conversationId`, `jti`, ≤120s TTL, ES256/JWKS-signed, and `OnBehalfAuthResolver` enforces every one (checklist 3,4,6,8,9,10).
- [ ] Stream-token single-use enforced in `verify_token` (`service_token.py:223-274`) — replay rejected (1-B).
- [ ] Executor `conversation_id` ↔ workspace cross-check in place for all write tools (1-C).
- [ ] D-tier per-workspace rate limit active (§6).
- [ ] Money remains behind a separate human-clicked, server-authoritative endpoint; `isPaid`/"Secured"/go-live reconcile against the server SSE event and are never set from client code (`MeeraWorkspace.tsx:61-81`) — regression test present.
- [ ] Prod env actually overrides the localhost defaults: `MEERA_PUBLIC_CHAT_URL`, `MEERA_CHAT_AI_BASE_URL`, `APP_ENV` set so `SpringJwksKeyService` uses real (not ephemeral) keys and `StaticDevJwksSource` is unreachable (`application-prod.yml:48-77`, `service_token.py:97-163`, `MeeraStreamProperties.java:19-25`).
- [ ] Voice per-workspace limits + transcript retention statement shipped (§7).

---

## 9. Top 3 must-fix risks (for the summary)

1. **On-behalf credential is a full user access token read from XSS-readable `localStorage`** — regresses the H-30 in-memory-token control and hands a separate service full-account authority (`MeeraChatPanel.tsx:327` vs `token-store.ts:1-16`; `OnBehalfAuthResolver.java:104-115` uses the public parser with no scope/aud constraint). Fix: mint a per-turn `aud=meera-onbehalf`, scoped, ≤120s, `jti` token in Java (§2).
2. **Stream-token single-use is documented but not enforced** — replay within ≤60s = duplicate paid turns + duplicate draft campaigns (`StreamTokenService.java:66-72` claim vs `service_token.py:223-274` reality). Fix: enforce `jti`/`messageId` single-use in `verify_token`.
3. **`conversation_id` is not cross-checked against the tenant in the tool executors** — Guardrail-4 is enforced on `/messages` (`MeeraInternalController.java:179-180`) but skipped on the tool path (`MeeraInternalController.java:130,148,163`; `CreateCampaignExecutor.java:130-149`). Fix: assert `conversation.workspaceId == token.workspaceId` before any write.

---

## 10. Verdict

**Design GO — conditional enablement, NO-GO to flip on today.** The architecture is right: the tier split, the "human commits money" boundary, the dual-credential mesh, the injection containment, and the idempotency scaffolding are all present and well-reasoned. Enabling on-behalf tools is **safe to pursue in the phased order above**, and read-only tools (Phase 1) can go live quickly. But it is a hard **NO-GO to enable any write/commit tool or set `VITE_API_MODE=live`** until the three must-fixes land — above all the credential fix (#1), which is both a security regression and, almost certainly, the actual reason tools currently degrade to text.

Hardest problem to get right: **single-use enforcement of short-lived per-turn tokens across the Java-mint → browser-hold → Python-verify → Python-forward → Java-verify chain.** Getting the on-behalf token's claims right is mechanical; making "one token authorizes exactly one turn's tool calls, once" hold across two languages, two verify points (Python at `/chat`, Java at `/internal/meera/*`), a 60-120s window, stream reconnects, and Claude's non-deterministic `tool_use.id` regeneration — without a replay slipping through or a legitimate retry being wrongly rejected — is the subtle part. It needs a shared, correctly-TTL'd consumed-`jti` store and tests that specifically exercise replay, reconnect, and concurrent double-submit.
