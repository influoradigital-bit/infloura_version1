# 🔄 MEERA AI FLOW — END-TO-END (micro-detail for Vikram)

> **Owner:** Priya (CTO) · **Date:** 2026-07-05
> **Reads with:** `02-API-CONTRACT-BRAND.md`, `04-AI-SERVICE-SPEC.md`, `06-MEERA-PERMISSIONS-MATRIX.md`, `09-ADVANCED-SECURITY-MEASURES.md`, `10-VIKRAM-FILE-MANIFEST.md`.
> **Purpose:** The exact request lifecycle of a Meera turn — every hop, who does what, and the security checkpoint at each step. Build the code to match these sequences.

---

## PRINCIPLE RESTATED

Browser talks to Spring (auth/money/state). Spring hands a **sanitized** context + a **short-lived stream token** to the browser, which opens SSE **directly to Python**. Python reasons and *proposes* tool-calls; when a tool touches state/money, Python calls **back into Spring** `/internal/meera/*`, which re-authorizes, re-derives amounts, executes idempotently, and returns the result. **Python never holds DB or money authority.**

---

## FLOW 1 — Brand onboards, website analyzed

```
1. Browser → Spring   POST /brand/meera/analyze-site { url }
                      [SEC] JWT valid · workspace-scoped · credit gate (AICreditService)
                      [SEC] url validated (scheme http/https, not private) before accept
2. Spring → Python    POST /analyze-site { url, callback_token }   (signed service token, aud=internal, ≤60s)
3. Python             SsrfGuard: resolve DNS, REJECT private IPs / 169.254.169.254 / redirects>3
                      Playwright render → Gemini Flash extract { catalog, tone, niche, palette }
4. Python → Spring    POST /internal/meera/site-analyzed { profile }   (signed, idempotency-key)
                      [SEC] InternalServiceTokenFilter + InternalRequestVerifier (HMAC/nonce)
5. Spring             persist BrandProfile (V11) · emit ai.site_analyzed event (in-app notif #23)
6. Browser            polls GET /brand/meera/profile → renders on canvas (Snapshot stage)
```
**Files exercised:** `MeeraController`, `BrandContextAssembler`, `StreamTokenService`, `clients/spring.py`, `routes/analyze_site.py`, `ssrf_guard.py`, `MeeraInternalController`, `BrandProfile` entity/repo, `NotificationListener`.

---

## FLOW 2 — A chat turn (streaming, read-only reasoning)

```
1. Browser → Spring   POST /brand/meera/turn { conversationId, message }
                      [SEC] JWT · tenant scope · AICreditService.gate() → decrement (before Python)
                      Spring persists user AiMessage (V12)
                      Spring mints stream token (StreamTokenService: aud=stream, ≤60s, single-use, bound to userId+conversationId)
   Spring → Browser   200 { streamToken, streamUrl (Python) }
2. Browser → Python   GET {streamUrl}?token=…   (SSE)
                      [SEC] Python validates stream token (sig, aud, exp, one-time nonce)
3. Python             BrandContext already sanitized by Spring (no PII) — assemble prompt:
                      [ persona+tools  (cache_control: ephemeral) ]   ← stable, cached
                      [ brand profile   (cache_control: ephemeral) ]   ← per-brand, cached
                      [ conversation history + new message ]           ← volatile
4. Python → Claude    stream=true
5. Python → Browser   SSE events: thinking → token…token → (tool_use?) → done
6. Spring             on stream close, Python posts assistant AiMessage back via signed callback → persist (V12)
```
**Cache win:** steps marked cached cut ~65% of input cost (PRD §6). **Credit is charged in step 1**, before any model spend — a hostile client can't run the model for free.

---

## FLOW 3 — A tool-call that touches state/money (the critical path)

Triggered mid-Flow-2 when Claude emits `tool_use`.

```
5a. Python            parse tool_use { name, input }
                      ToolCallValidator: name ∈ {show_creators, calculate_budget, create_campaign,
                                                  request_payment, confirm_launch} else REJECT
5b. Python → Spring   POST /internal/meera/{tool}  { input, on_behalf_of: userJWT, idempotency_key }
                      [SEC] InternalServiceTokenFilter (service token) 
                      [SEC] OnBehalfAuthResolver (re-authorize the USER's JWT — not just service trust)
                      [SEC] IdempotencyService: if key seen → return prior result (no re-execute)
5c. Spring executor   by tier (06-MEERA-PERMISSIONS-MATRIX):
     • show_creators / calculate_budget  (R) → read, tenant-scoped, return data
     • create_campaign                   (D) → write DRAFT campaign (V13 intent → campaigns), @Transactional
     • request_payment                   (C) → **re-derive amount server-side**, create PENDING human-confirm
                                               action; DO NOT move money; return { status: PENDING_CONFIRM }
     • confirm_launch                    (C) → only if a human-confirmed action exists; escrow hold + invites
5d. Spring → Python   tool_result { … }   (AuditLogService writes immutable record for C-tier)
5e. Python → Claude   append tool_result → continue stream
5f. Python → Browser  SSE resumes: token…token → done
```

**The money guarantee, concretely:**
- `request_payment` NEVER debits. It returns `PENDING_CONFIRM`; the browser renders a real confirm button; the human clicks; the **browser** calls a **public** endpoint (`POST /brand/escrow/fund`) on the user JWT → `EscrowService.hold()` with the **server-derived** amount. Python is not in this leg at all.
- Amount in the AI's tool input is **ignored** — `EscrowService` recomputes from `campaign_intents` + fee rules `[SEC: MF-1]`.
- Every C-tier call is idempotent and audit-logged.

---

## FLOW 4 — Go-live funds escrow, credits reset, creators notified

```
1. Browser → Spring   POST /brand/escrow/fund { campaignId }   (human click; JWT)
                      [SEC] amount re-derived · idempotency-key · EscrowStateMachine (DRAFT→SECURED)
2. Spring             WalletService.debit + WalletTransaction (V8, double-entry, sum=0 checked)
                      EscrowHold row SECURED (V9)  ·  Razorpay Route split reserved
3. Spring (events)    escrow.funded → NotificationListener:
                        • creator.campaign_live email+in-app to all invited creators (#5)
                      ai.credits_reset → AICreditService.reset(brandId) (unlimited while live) (#26)
4. Browser            canvas → StageLive (LIVE indicator + secured escrow pill)
```

---

## FLOW 5 — Voice turn (M2.5, cascaded)

```
1. Browser → Python   POST /voice/transcribe (audio)   [after Spring credit gate + stream token]
2. Python → Sarvam    STT (Hinglish) → text ; grammar cleanup → return EDITED text (edit-first)
3. Browser            shows editable transcript → user confirms → normal Flow 2 turn
4. Assistant reply    Flow 2 → then POST /voice/speak → Sarvam TTS → stream audio
   [SEC] any voice failure → graceful text fallback (no dead end) · credits: input=3, reply=4
```

---

## SECURITY CHECKPOINT SUMMARY (per hop)

| Hop | Checkpoint | File |
|---|---|---|
| Browser→Spring | JWT (alg-pinned, iss/aud/exp/skew), tenant scope, credit gate | `JwtService`, `TenantGuard`, `AICreditService` |
| Spring→Python | signed service token (aud=internal, ≤60s) | `StreamTokenService`, `service_token.py` |
| Browser→Python (SSE) | single-use stream token, nonce | `StreamTokenService`, `routes/chat.py` |
| Python site fetch | SSRF guard (private-IP/metadata block) | `ssrf_guard.py` |
| Python→Spring /internal | service token + on-behalf user JWT + HMAC/nonce + idempotency | `InternalServiceTokenFilter`, `OnBehalfAuthResolver`, `InternalRequestVerifier`, `IdempotencyService` |
| Tool execution | permission-tier check, amount re-derivation, state machine, audit | `ToolCallValidator`, `EscrowService`, `EscrowStateMachine`, `AuditLogService` |
| Any money move | human-confirmed public endpoint only; AI never in the leg | `EscrowController`, `EscrowService` |

---

## WHAT PYTHON CAN NEVER DO (enforced by absence)

- No DB connection string. No wallet/payout/payment-method endpoint reachable.
- No code/config/deploy access.
- Cannot finalize a C-tier action — only stage it for human confirm.
- Its tool vocabulary is exactly 5 schemas; anything else is rejected at `ToolCallValidator` on both sides.

This flow is the contract. If a code path lets Python move money, skip a credit gate, or reach a private IP, it is a **launch blocker** — Kabir re-tests all of it (RT-G1..G6, LB-1..LB-9).
