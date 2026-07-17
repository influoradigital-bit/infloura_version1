# 🛡️ KABIR — SECURITY AUDIT: MEERA AI COFOUNDER WORKSPACE (M2.5)

> **From:** Kabir (Red-Team / Offensive Security) · **To:** Kavya (QA), Priya (CTO), Ananya (Frontend) · **Date:** 2026-07-05
> **Scope:** Our own repo only (`New Influora`), Meera M2.5 vertical slice + the surfaces it touches. Frontend threat model — mock-first Vite app, no live AI/backend endpoints yet.
> **Standard:** OWASP Top 10 + ASVS, applied to the actual source.
> **Verdict:** **SHIP (with 2 pre-backend blockers tracked).** Zero exploitable vulns in the shipped mock. All real risk is at the client/server trust boundary and MUST be enforced server-side before the backend goes live.

---

## HOW TO READ THIS

Two buckets, deliberately separated:

- **REAL NOW** — exploitable or wrong in the code as it ships today.
- **MUST-ENFORCE-WHEN-BACKEND-LANDS** — the mock is fine; the danger is trusting client state once real money/APIs are wired. These are not "mock bugs," they are contract requirements for Vikram's backend. Flagging now so they are not forgotten when `VITE_API_MODE=live`.

Finding count: **0 Critical · 0 High (real-now)** · 2 Medium · 3 Low · 4 Info (real-now) · **4 High (deferred/backend-contract)**.

---

# PART A — REAL NOW

## [MEDIUM] A1 — Session JWTs (access + refresh) stored in `localStorage`
- **Where:** `src/lib/auth-session.ts:41-42`, `src/lib/api.ts:87-95`, `src/App.tsx:43,60`
- **Issue:** Brand and creator access tokens AND the **refresh token** are stored in `localStorage`. Any successful XSS anywhere in the app (a single injected script — e.g. via a future compromised dependency, a `dangerouslySetInnerHTML` regression, or scraped-brand content later rendered raw) can read `brand_token` + `brand_refresh_token` and exfiltrate them. A refresh token in JS-readable storage turns a transient XSS into durable account takeover — the attacker can mint fresh access tokens long after the XSS payload is gone.
- **Impact:** Full brand account takeover, including the money surface (escrow funding, payout release) once those are live.
- **Why only Medium now:** Today everything is mock; the token is the literal string `mock_brand_token`. The severity is latent — it becomes High the moment real JWTs flow.
- **Fix:** Preferred — move the real session token to an `httpOnly; Secure; SameSite=Lax` cookie set by the backend; the SPA never touches it in JS. If localStorage is retained for the access token for pragmatic reasons, the **refresh token must NOT be** — keep refresh in an httpOnly cookie only. Re-rate this to High and resolve before `VITE_API_MODE=live`.

## [MEDIUM] A2 — Route protection is client-side-only and demo-mode bypasses it entirely
- **Where:** `src/App.tsx:42-47` (`ProtectedRoute`), `59-64` (`CreatorProtectedRoute`)
- **Issue:** Two problems. (1) The guard is `localStorage.getItem('brand_token')` truthiness — a client-only trust decision; anyone can `localStorage.setItem('brand_token','x')` in devtools and walk into `/brand/meera` and every other protected brand route. (2) The bypass: `isDemoMode = ?demo=true OR import.meta.env.MODE === 'development'`. **Any unauthenticated visitor can reach every protected route by appending `?demo=true`.** This is intended for the demo, but it is a URL-flag auth bypass that must never survive into a production build serving real data.
- **Impact:** Now — none (all data is mock). Later — unauthenticated access to any brand's workspace and campaign data if the `?demo=true` escape hatch is still live in prod, or if the client-only guard is treated as a real boundary.
- **Fix:** (1) Client route guards stay as UX only; the backend must authorize **every** data request server-side (never trust the presence of a route render). (2) Gate the `?demo=true` bypass behind a build-time flag that is compiled OUT of production bundles (e.g. `import.meta.env.VITE_ALLOW_DEMO === 'true'`, unset in prod), so a prod build literally cannot honor `?demo=true`. Do not rely on `MODE === 'development'` alone — confirm the prod build sets `MODE=production`.

## [LOW] A3 — `mock_creator_token` / `mock_brand_token` hardcoded literals set client-side
- **Where:** `src/pages/creator-register.tsx:47,58`, `src/pages/creator-login.tsx:33`, `src/lib/api.ts:230,240,245`
- **Issue:** Login/register in mock mode writes a hardcoded token string to `localStorage` with no credential check. This is correct for a mock, but it is a foot-gun: if `isLive()` ever returns false in a shipped build (misconfigured env), auth is effectively "anyone is logged in." It is also a hardcoded-credential pattern a scanner will flag.
- **Impact:** Auth no-op if mock mode leaks into production.
- **Fix:** Add a hard runtime guard: if `import.meta.env.PROD && !isApiLive()`, throw / render a config-error screen rather than silently minting a mock token. Fail closed, not open.

## [LOW] A4 — STT recognition language/permission: transcript handled correctly, but no explicit user gesture gate on `recognition.start()` beyond the button
- **Where:** `src/hooks/useVoiceInput.ts:60-123`
- **Issue:** Minor/defensive. `recognition.start()` triggers the browser's native mic-permission prompt, which is correct (browser-enforced, user must grant). The hook handles every failure path (`onerror`, `onnomatch`, empty transcript, permission-denied) gracefully back to `idle` with fallback copy — this is genuinely well built. The only nit: the transcript is read from `event.results[0][0].transcript` and passed straight into `cleanTranscript` → composer. There is no length cap on the transcript before it enters React state. Not exploitable (React escapes it on render — see A-Info-1), but a pathological multi-minute dictation could bloat the textarea/state.
- **Impact:** Negligible today (self-DoS at worst).
- **Fix:** Cap transcript length (e.g. slice to a few thousand chars) in `onresult` before `cleanTranscript`. Belt-and-suspenders.

## [LOW] A5 — `useVoiceOutput` speaks arbitrary text via `speechSynthesis`; today only Meera's own copy, but the seam is generic
- **Where:** `src/hooks/useVoiceOutput.ts:81-104`, called from `MeeraChatPanel` after each Meera line renders
- **Issue:** `speak(text)` is called only with Meera's scripted reply strings today (safe, authored content). But the hook is a generic `speak(anyString)`. When the backend lands and Meera's replies become LLM-generated / echo brand-scraped or user-influenced content, TTS will vocalize whatever the model returns. That is not an injection vector (audio, not markup), but it is an untrusted-content path worth noting so no one later pipes raw user transcript into `speak()`.
- **Impact:** None now. Later: TTS reading attacker-influenced content aloud (low-stakes, but a channel).
- **Fix:** Keep `speak()` fed only from Meera's own rendered reply text (already the contract in `MeeraChatPanel`). Document that user transcript must never be routed to `speak()`.

## [INFO] A-Info-1 — Voice transcript → composer → chat bubble is XSS-safe (verified, no action)
- **Where:** `src/components/feature/meera/Composer.tsx:45,102-105`; `src/components/feature/meera/MessageBubble.tsx:38`
- **Finding:** I traced the user-controlled voice transcript end to end. STT result → `cleanTranscript` → `setValue` (textarea `value`, React-controlled) → on send, `onSend(trimmed)` → rendered as `{text}` in `MessageBubble`. Every hop is React text interpolation or a controlled input — **no `dangerouslySetInnerHTML`, no raw HTML sink**. The `aria-live` region (`Composer.tsx:102`) also interpolates `{transcriptionAnnouncement ?? voiceFallback}` as text. This is the correct pattern. A transcript of `<img src=x onerror=alert(1)>` renders as inert text. **No XSS.**

## [INFO] A-Info-2 — `cleanTranscript` cannot be an injection or meaning-alteration vector (verified)
- **Where:** `src/lib/clean-transcript.ts:25-40`
- **Finding:** The mock does exactly three things: `.trim()`, collapse `\s+` → single space, and `.charAt(0).toUpperCase() + slice(1)`. It cannot inject markup (output is plain text rendered via React interpolation), and it cannot maliciously alter meaning — `toUpperCase()` on a leading digit/₹/@ is a no-op, and no interior character is touched. Digits, amounts, @handles, and proper nouns pass through byte-for-byte. Meaning-preservation holds **by construction**. The one forward-looking caveat: when this is swapped for a real LLM cleanup (the file is explicitly labelled a mock seam), that LLM output becomes untrusted and must (a) still render only as escaped text, and (b) be treated as capable of altering intent — the edit-first composer (which shows the text before send) is the correct control and must be preserved. Do not add an auto-send toggle that bypasses human review of LLM-cleaned text without a separate risk review.

## [INFO] A-Info-3 — `logVoiceUsage` does not leak transcript/PII (verified)
- **Where:** `src/lib/voice-usage.ts:11-16`
- **Finding:** Takes only `kind: 'stt' | 'tts'`. Never receives or logs the transcript text or any user content. Dev-only `console.debug`. Clean. When Rohan wires the real cost meter, keep it metering the *event*, not the *content* — do not start sending transcript text to a billing endpoint.

## [INFO] A-Info-4 — No new dependencies added; voice built on Web Speech API only (Priya's rule holds)
- **Where:** `package.json`, `src/hooks/useVoiceInput.ts`, `src/hooks/useVoiceOutput.ts`, `src/types/speech.d.ts`
- **Finding:** Confirmed zero new npm deps for the voice/Meera work — STT/TTS use only `window.SpeechRecognition`/`webkitSpeechRecognition`/`speechSynthesis`, typed via an ambient `.d.ts`. Reduced supply-chain surface. `dangerouslySetInnerHTML` appears once (`src/components/ui/chart.tsx:83`) — it is stock shadcn CSS-variable injection where the interpolated `id` comes from `React.useId()` (`chart.tsx:49-50`), developer-controlled, not user input. **Not a vector.** No known-risky pattern introduced by this slice.

---

# PART B — MUST-ENFORCE-WHEN-BACKEND-LANDS (client/server trust boundary)

> The Meera workspace makes **no real money call today** — `handlePay` (`MeeraWorkspace.tsx:54-57`) is a client-side `setTimeout(900ms)` + `markPaid()`, and `useMeeraStage` is pure client state. That is correct for a mock. These are the boundaries that MUST be server-enforced before real rupees move.

## [HIGH — DEFERRED] B1 — Payment / escrow state is decided purely client-side
- **Where:** `src/components/feature/meera/MeeraWorkspace.tsx:54-61`, `src/hooks/useMeeraStage.ts:29-52`, `src/components/feature/meera/StageFunding.tsx`
- **Issue:** `markPaid()` flips `isPaid` in React state with no server confirmation; the escrow-lock hero, the "Secured" pill, and the "Approve & release" → `confirm_launch` transition all trust that client flag. If this pattern survives into the live integration, a user could set `isPaid`/advance stages from devtools and reach a "funds secured / released" UI state — or worse, trigger a release — without a verified payment.
- **Impact (when live):** Fabricated escrow-secured state; unauthorized payout release; financial loss / fraud.
- **Fix (backend contract):** The money path is already correctly shaped in `src/lib/api.ts` — `payments.fundEscrow(dealId)` and `payments.releasePayout(dealId)` take **no client-supplied amount** (`api.ts:836,842`) and must be the sole source of truth. When wiring Meera: (1) `isPaid`/"Secured" must be set only from a server-confirmed payment/webhook (`payment.released` SSE event, `api.ts:15`), never from a client `setTimeout`. (2) The backend must independently compute the fee/total (never trust `computeFee` client math for the charge) and re-authorize the payer against the workspace on every call. (3) Escrow release must require server-side verification that the caller owns the deal AND the deliverable is approved. Treat the client stage machine as display-only.

## [HIGH — DEFERRED] B2 — Fee/amount math is client-side; must be recomputed server-side
- **Where:** `src/data/meera-mock.ts` (`computeFee`), consumed in `StageFunding.tsx:29`, `FeeBreakdown`
- **Issue:** `Pool ₹15,000 + Fee 15% = ₹17,250` is computed in the browser for display. Fine for a mock. If any charge amount is ever derived from or trusted from the client (pool size, fee %, total), a tampered client can under/over-charge or manipulate the fee.
- **Impact (when live):** Payment-amount tampering.
- **Fix:** Backend is the authority on pool/fee/total for the actual charge. Client math is presentational only. The `Idempotency-Key` header pattern already in `api.ts:129` should protect fund/release from double-submit — ensure fund/release endpoints actually consume it.

## [HIGH — DEFERRED] B3 — Every protected data read/write must be server-authorized (not gated by the client route guard)
- **Where:** `src/App.tsx:42-64`, all `isLive()` branches in `src/lib/api.ts`
- **Issue:** See A2. The client `ProtectedRoute` is UX. Once live, the backend must enforce authz on every endpoint (campaigns, deals, messages, wallet, escrow) with tenant isolation — a brand must never read/act on another workspace's deals via a guessed/incremented ID (IDOR). The API already scopes by `Authorization: Bearer` + `role`; the risk is endpoints that take an `:id` (`/deals/:id`, `/campaigns/:id`, `/creators/:id/invite`) trusting it without an ownership check.
- **Impact (when live):** IDOR / cross-tenant data access and cross-tenant state change.
- **Fix (backend contract):** Server-side ownership check on every `:id`-parameterized route; scope all queries to the authenticated workspace. Add object-level authorization (ASVS V4). Rate-limit auth, OTP (`sendBrandEmailOtp`/`verifyBrandEmail`), and the public portfolio `contact` endpoint (`api.ts:1060`) — all are enumeration/abuse targets. The refresh-token endpoint especially must be rate-limited and rotation-enforced.

## [HIGH — DEFERRED] B4 — CSRF + secure transport must be in place before real state-changing calls
- **Where:** `src/lib/api.ts` (all `POST`/`PATCH`/`DELETE`), `.env.local.example`
- **Issue:** If the session ever moves to a cookie (recommended in A1), every state-changing request (`fundEscrow`, `releasePayout`, `wallet.recharge`, `wallet.withdraw`, `deals.*`, `contracts.sign`) needs CSRF protection. Also: `.env.local.example` defaults `VITE_API_BASE_URL=http://localhost:8080` — that is correct for local dev, but the production base URL must be **HTTPS-only**; a plaintext base URL would expose Bearer tokens on the wire.
- **Impact (when live):** CSRF-forced payments/withdrawals; token interception over plaintext.
- **Fix:** With cookie sessions → `SameSite=Lax/Strict` + per-session CSRF token (or origin check) on all mutations. Enforce HTTPS for the prod API base. Keep the Bearer-header model (which is inherently CSRF-resistant) OR the cookie+CSRF model — do not mix a cookie-borne token with header auth ambiguously. Add security headers at the edge (CSP, HSTS, X-Frame-Options / frame-ancestors) — a strong CSP is also the best secondary mitigation for the A1 localStorage-token XSS risk.

---

## VERDICT

**SHIP the M2.5 mock slice.** It is clean: no XSS, the voice/transcript path is correctly escaped, `cleanTranscript` is meaning-safe and non-injectable, no secrets, no new deps, graceful voice fallbacks, and the well-designed `api.ts` already puts money authority server-side. Ananya's edit-first voice guardrail (STT never auto-sends) holds — I traced it, there is no path from `useVoiceInput` to `onSend`.

**Nothing here blocks the demo.** But **B1–B4 are hard blockers for the live backend cutover** and A1/A2/A3 must be resolved (and re-rated up) the moment real JWTs and real money flow. Route these to Vikram (backend authz, escrow/payment server-truth, CSRF, rate limiting) and Ananya (demo-bypass build gate A2, fail-closed mock guard A3, transcript length cap A4). Re-test required before `VITE_API_MODE=live` ships to brands.

— Kabir
