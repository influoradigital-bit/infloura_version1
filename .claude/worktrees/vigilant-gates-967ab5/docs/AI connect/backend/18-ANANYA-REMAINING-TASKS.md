# 18 — ANANYA REMAINING FRONTEND TASKS — Meera AI Cofounder (wire-up + full surface)

> **Owner:** Priya (CTO) · **For:** Ananya (Frontend Developer — React/Next... see Derivation note) · **Date:** 2026-07-05
> **Status:** PLANNING — definitive remaining-work packet, derived from the backend API contract + AI flows
> **Milestone:** M2.5 → M3 (real-backend cutover)
> **Primary sources (read these first, in this order):**
> - `02-API-CONTRACT-BRAND.md` — public brand endpoints + streaming design (authoritative endpoint contract)
> - `04-AI-SERVICE-SPEC.md` — Python→browser SSE event protocol, voice endpoints, latency targets
> - `11-AI-FLOW-DETAILED.md` — the end-to-end flows the UI drives (onboarding, chat turn, tool-call, escrow fund, voice)
> - `06-MEERA-PERMISSIONS-MATRIX.md` — the commit-tier security contract (chat-yes ≠ consent; human click gates money)
> - `15-WHAT-WE-BUILT.md` — what's real vs stubbed on the backend today
> - `14-REMAINING-TASKS.md` — remaining backend scope (what unblocks what)
> **Frontend alignment sources (found on disk — reconcile against these):**
> - `docs/AI connect/FRONTEND-BUILD-SPEC-MEERA.md` — the original approved frontend build sheet (stack, tokens, 5-stage layout, motion, §5A voice)
> - `docs/AI connect/ANANYA-BUILD-NOTES.md` — what Ananya already built (mock-first vertical slice, QA'd + Kabir-fixed)
> - `docs/BACKEND-STACK.md` — API base URL + conventions

---

## DERIVATION NOTE (read before anything else)

This packet is **derived**. The backend docs (`02`, `04`, `06`, `11`, `14`, `15`) are backend-only. There was **no `TECH-STACK.md`** at the repo root and **no file literally named `*FRONTEND*`** — but there **is** a real, approved frontend spec at `docs/AI connect/FRONTEND-BUILD-SPEC-MEERA.md` and a build log at `docs/AI connect/ANANYA-BUILD-NOTES.md`. Every task below is inferred from the API contract + AI flows and **must be reconciled against those two frontend docs before build.** Where the API contract and the frontend spec disagree, the API contract (`02`) wins for endpoint shape; the frontend spec (`FRONTEND-BUILD-SPEC-MEERA.md`) wins for layout/motion/tokens.

**Two things are already true and change the shape of this packet:**

1. **The Meera workspace UI already exists, mock-first.** Per `ANANYA-BUILD-NOTES.md`, the full 50/50 workspace, 5 living-canvas stages, escrow-lock hero, credit paywall, and §5A voice layer are **built, compiling, QA'd (Kavya), and security-fixed (Kabir A2/A3/A4).** It runs on a **scripted turn engine** (`MEERA_CONVERSATION_SCRIPT` / `MeeraTurn`) and mock function-call glue (`useMeeraStage`), NOT a real backend. So most of this packet is **wire-up and hardening, not greenfield UI.**
2. **The backend Meera brain does not exist yet.** Per `15-WHAT-WE-BUILT.md`: `MeeraSessionService` still echoes a placeholder; `MeeraInternalController` returns `501` stubs; **0% of the Python AI service (Domain D) and Phase 4 tool executors are built.** So Ananya **cannot** wire real SSE/tools until Domain D + Phase 4 land — but she **can** build the real SSE client + confirm controls against a **mock SSE server / mock endpoints** now, so the swap is a config change, not a rewrite (exactly how the turn engine was designed — see `ANANYA-BUILD-NOTES.md` §7 "Known limitation").

### Stack assumptions (confirmed from `FRONTEND-BUILD-SPEC-MEERA.md` §1 + `BACKEND-STACK.md`)

The task brief said "React/Next.js." **The repo is actually React 18 + Vite (NOT Next.js) + TypeScript.** Confirm this before writing code — it changes routing, env-var access, and SSR assumptions:

| Item | Reality (from the frontend spec §1) |
|---|---|
| Framework | **React 18 + Vite + TypeScript** — *not* Next.js. There is dead Next.js scaffold under `src/app/brand/**` — **do not touch it.** |
| Routing | `react-router-dom v7` (`src/App.tsx`) — routes, not the Next app-router |
| Styling | **Tailwind v4 (CSS-based, no `tailwind.config`)**, tokens in `src/app/globals.css` (`@theme inline`), shadcn/ui |
| Motion / 3D | Framer Motion + React Three Fiber |
| Env vars | Vite: `import.meta.env.*` (NOT `process.env` / `NEXT_PUBLIC_`). Client-exposed vars are `VITE_*`. |
| API client | `src/lib/api.ts`, base `/api/v1`, port 8080 |
| **TASK-0 (do first)** | **Read `FRONTEND-BUILD-SPEC-MEERA.md` + `ANANYA-BUILD-NOTES.md` + `TECH-STACK.md` (if one is created) top to bottom before writing code.** Ananya already reads TECH-STACK.md before every task — treat these two docs as its equivalent for the Meera surface until a TECH-STACK.md exists. |

---

## ⚠️ ENDPOINT-PATH DISCREPANCY (resolve with Vikram before wiring)

The two backend source docs **name the same endpoints differently.** Do not guess — confirm with Vikram which shipped:

| Purpose | `02-API-CONTRACT-BRAND.md` (authoritative contract) | `11-AI-FLOW-DETAILED.md` (flow prose) |
|---|---|---|
| Analyze site | *(implied via profile)* | `POST /brand/meera/analyze-site` |
| Start/resume session | `POST /meera/sessions` | — |
| Send a turn | `POST /meera/sessions/{conversationId}/messages` | `POST /brand/meera/turn` |
| Profile / analysis status | `GET /meera/brand-profile` | `GET /brand/meera/profile` |
| Credits | `GET /meera/credits` | — |
| Escrow fund | `POST /wallet/escrow/fund` | `POST /brand/escrow/fund` |
| Escrow status | `GET /wallet/escrow/{escrowHoldId}` | — |
| Create campaign | `POST /meera/sessions/{conversationId}/create-campaign` | — |

**Default assumption:** use the `02` paths (it is the signed contract, §1). All are under base `/api/v1`. Centralize every path in **one place in `src/lib/api.ts`** so a rename is a one-line change. **The browser only ever calls Spring `/api/v1/*` and the Python SSE stream edge — never Python's internal `/internal/meera/*` endpoints** (`02` §2 trust boundary).

---

## SUMMARY TABLE

| # | Task group | Type | Binds to (backend) | Blocked by | Priority |
|---|---|---|---|---|---|
| 1 | Brand onboarding / website-analysis UI | Mostly NEW | `POST /analyze-site`, `GET /meera/brand-profile` (poll) | Domain D `/analyze-site` (mock now) | 🔴 High |
| 2 | Meera Living-Canvas chat (turn engine → real turns) | WIRE-UP | `POST /meera/sessions`, `POST /meera/sessions/{id}/messages` | Spring session real; Domain D for tokens | 🔴 Highest |
| 3 | SSE streaming client | NEW (core) | Python `GET {streamUrl}?token=` SSE (`04` §4) | Domain D (mock SSE now) | 🔴 Highest |
| 4 | Tool-result canvas renderers | WIRE-UP | `tool_start`/`tool_result` SSE events; tool payload shapes (`02` §3.1–3.5) | Phase 4 executors (mock now) | 🔴 High |
| 5 | Commit-tier confirm controls (human-click gates) | NEW + WIRE | `POST /wallet/escrow/fund`, `create-campaign`, proposal/contract sign | Phase 4 + escrow real | 🔴 Highest (security) |
| 6 | Wallet / escrow / contract UI | Partial NEW | `GET /wallet/escrow/{id}`, contract gen/sign, Razorpay | Phase 1 (done) + Phase 4 | 🟡 Medium |
| 7 | Voice UI (Hinglish, edit-first) | WIRE-UP | `POST /voice/transcribe`, `POST /voice/speak` (`04` §5) | Domain D voice (mock now) | 🟡 Medium |
| 8 | AI credit meter | WIRE-UP | `GET /meera/credits`; `creditsRemaining` on turn resp; `402` | Spring credit svc (done) | 🟡 Medium |
| 9 | Notifications UI | NEW | `GET/POST /notifications`, unsubscribe (Domain B) | Domain B (not started) | 🟢 Lower |
| 10 | Cross-cutting: loading/error/empty, latency, a11y, no-secrets, resilience | Woven | all of the above | — | 🔴 Woven |

**Legend:** NEW = build it · WIRE-UP = the UI exists (mock), replace mock with real backend · Partial NEW = some exists, some greenfield.

---

## RECOMMENDED BUILD ORDER

Onboarding + chat shell + SSE first — that's what makes Meera **visibly work against a real brain** instead of a script. Everything downstream (tools, confirm gates, voice) rides the same stream.

1. **API layer + env + the endpoint map (`src/lib/api.ts`).** Add all Meera/escrow/credit/notification paths (§ discrepancy table), a `VITE_MEERA_STREAM_URL` env, and typed request/response DTOs mirroring `02`/`04`. No UI yet.
2. **SSE streaming client (Task 3).** Build `useMeeraStream` against a **mock SSE server** (a tiny local `text/event-stream` responder emitting the `04` §4 event script). This is the spine — build it standalone and unit-test event parsing/reconnect/cancel before touching the chat.
3. **Chat turn cutover (Task 2).** Replace the scripted `MeeraTurn` resolution in `MeeraChatPanel.handleSend` with: `POST session` → `POST message` → open SSE → render `token`/`thinking` events. Keep the mock turn engine behind a `?demo=true` / env flag so the demo still works and QA has a deterministic path.
4. **Onboarding / analyze-site (Task 1).** URL field + async analyzing + poll `GET brand-profile` → Snapshot stage. Gate session start on `brandProfileStatus === READY`.
5. **Tool-result renderers (Task 4).** Bind `show_creators` / `calculate_budget` / `create_campaign` payloads to the existing Stage 2/3 components; drive stage advance from `tool_result`, not from the script.
6. **Commit-tier confirm controls (Task 5) — the security-critical gate.** Real "Fund escrow" button hitting `POST /wallet/escrow/fund` on the user JWT; confirm_launch; proposal-envelope confirm; contract e-signature. Kabir gates this.
7. **Wallet / escrow / contract UI (Task 6)** + **credit meter (Task 8)** + **voice cutover (Task 7).**
8. **Notifications UI (Task 9).**
9. **Cross-cutting hardening (Task 10)** — woven throughout, finalized before sign-off.

### Backend dependency reality (what she can/can't do now)

| She CAN build now (against mock) | She must WAIT for |
|---|---|
| SSE client + event parser + reconnect/cancel (mock `text/event-stream`) | Real streaming needs **Domain D Python `/chat`** (`14` item 1, not started) |
| Chat turn request flow (`POST session`/`message`) against a mock/stub Spring | Real turns need `MeeraSessionService` to stop echoing (needs Domain D) |
| Tool-result renderers against mock tool payloads (shapes are frozen in `02` §3) | Real tool data needs **Phase 4 executors** (`14` item 2 — `MeeraInternalController` is `501` today) |
| Escrow-fund confirm UI against Phase-1 escrow (money core is **built**, `15`) | `request_payment` producing the PENDING action needs Phase 4 `RequestPaymentExecutor` |
| Credit meter against `GET /meera/credits` (AI credit service is **built**, `15`) | — |
| Voice UI against mock `transcribe`/`speak` (already stubbed, `ANANYA-BUILD-NOTES.md` §8) | Real Hinglish cleanup needs Domain D voice |
| Notifications UI against a mock list | Real data needs **Domain B** (`14` item 3 — not started) |

**Design everything as a config/adapter swap**, mirroring how the turn engine was authored (`ANANYA-BUILD-NOTES.md` §7): the `triggersStage` name is already the function-call name, so replacing mock resolution with a real streamed `tool_result` is a body swap, not a UI rewrite.

### Coordination note (pipeline)

Per `FRONTEND-BUILD-SPEC-MEERA.md` §9: **Build → Kavya (QA) → Meera/DevOps (build verify: `npm run build` / `npm run dev` / curl checks) → Kabir (security) → Priya (sign-off) → Swapnil (final).** Nothing ships to brands without Priya + Swapnil sign-off. Coordinate the `/internal/meera/*` and SSE contract shapes with **Vikram** early (he owns both Spring and Python sides). Every commit-tier confirm control (Task 5) is a **launch-blocking Kabir gate** — do not ship it un-reviewed.

---

## TASK GROUP 1 — Brand onboarding / website-analysis UI (Flow 1)

**Status:** Mostly NEW. `ANANYA-BUILD-NOTES.md` §2 explicitly **deferred** the onboarding URL field (spec §8 item 4). The in-workspace Snapshot stage exists; the pre-workspace analyze step does not.

**Flow (from `11` Flow 1):** Browser `POST /analyze-site {url}` → Spring credit-gates + validates URL → hands to Python (async) → Python scrapes+classifies → posts profile back to Spring → **browser polls** `GET /meera/brand-profile` until `analysisStatus === READY` → render brand snapshot on the canvas.

### Components
| Component | New/Reuse | Notes |
|---|---|---|
| `brand-onboarding.tsx` URL field | EXTEND | Add a **required website-URL** input to the existing onboarding flow (`FRONTEND-BUILD-SPEC-MEERA.md` §8.4). Client-side validate scheme `http/https` (Python re-validates + SSRF-guards server-side; client validation is UX only, never a security control). |
| `AnalyzingState` | NEW | "Analyzing your business…" async state (30–60s per `04` latency table). Progress affordance (indeterminate), not a raw spinner (T3 spirit). |
| `StageSnapshot` | REUSE | Already built. Bind its props to the polled `GET brand-profile` payload (`niche_tags`, `product_catalog`, brand color) instead of `meera-mock.ts`. |
| Paste-a-link fallback | NEW | On `analysis_error` (scrape failed), show the PRD §9 fallback: let the brand paste a link / retry, no error wall (`04` §graceful fallback). |

### State
- `analysisStatus: 'IDLE' | 'ANALYZING' | 'READY' | 'ERROR'` (mirror `GET /meera/brand-profile.analysisStatus`).
- Poll `GET /meera/brand-profile` on an interval (start ~2s, back off to ~5s), **cap total wait ~90s** then show the paste-a-link fallback. Cancel the poll on unmount.
- Session start (`POST /meera/sessions`) is **blocked** until `brandProfileStatus === READY` — Spring returns `data.status = "ANALYZING"` / `409 BRAND_PROFILE_NOT_READY` otherwise (`02` §1.1, §1.2). The UI keeps polling, does not error.

### Definition of Done
- [ ] Onboarding requires a valid website URL before Meera opens.
- [ ] "Analyzing…" state renders while `analysisStatus === ANALYZING`; resolves into `StageSnapshot` on `READY`.
- [ ] Polling backs off, has a hard cap, and cancels on unmount (no leaked intervals).
- [ ] `analysis_error` → paste-a-link fallback, no dead end.
- [ ] Snapshot renders real `niche_tags` + `product_catalog` from the profile endpoint (no PII beyond allow-listed fields — `04` §2 forbidden fields).

---

## TASK GROUP 2 — Meera Living-Canvas chat (turn engine → real turns) · THE CENTERPIECE

**Status:** WIRE-UP. The composer, message list, thinking-state (T3), quick-reply chips, and 50/50 canvas shell all **exist** (`ANANYA-BUILD-NOTES.md` §1). Today they run on `MEERA_CONVERSATION_SCRIPT`. Replace the scripted resolution with real Spring calls + the SSE stream (Task 3).

**Flow (from `11` Flow 2 + `02` §1.2, §4):**
```
1. Browser POST /meera/sessions            → { conversationId, brandProfileStatus, credits }
2. Browser POST /meera/sessions/{id}/messages { content }
                                           → { messageId, streamToken, streamUrl, creditsRemaining }
   (Spring has ALREADY credit-gated + decremented + persisted the user message here)
3. Browser opens EventSource → {streamUrl}?token=<streamToken>   (Task 3)
4. Render token/thinking/tool_* events into the message list + canvas
5. On done, the finalized assistant turn is persisted server-side (Python → Spring §3.6).
   On stream failure, fall back to GET /meera/sessions/{id}/messages?after=<messageId> (02 §4.5)
```

### Components (all REUSE — rewire, don't rebuild)
| Component | Change |
|---|---|
| `MeeraChatPanel.handleSend` | Replace scripted turn cursor with: `POST message` → open SSE → append streamed `token` text into the live assistant bubble. Keep the mock turn engine behind a demo flag for QA. |
| `MessageBubble` | No structural change; now fed incrementally by `token` events (append to the in-flight bubble). |
| `ThinkingState` (T3) | Drive its step log from **`thinking` SSE events** (`{step, done}`), not the mock `showThinking`. Check-off on `done:true`. |
| `Composer` | `sendLocked` while a turn is streaming (already exists as a prop); re-enable on `done`/`error`. Free-text and chips share one `onSend` (already true). |
| `useMeeraStage` | Now advanced by `tool_start`/`tool_result` events (Task 4), not by `MeeraTurn.triggersStage`. |

### State
- `conversationId` (from session), `activeMessageId`, `streamToken` (short-lived, ≤60s — never persist it).
- `turnPhase: 'idle' | 'sending' | 'streaming' | 'tooling' | 'done' | 'error'`.
- `messages: RenderedMessage[]` — keep the runtime-history model already built (`ANANYA-BUILD-NOTES.md` §7); append streamed tokens to the last assistant entry.
- `creditsRemaining` — update from the `POST message` response and from `GET /meera/credits` (Task 8).

### Definition of Done
- [ ] Sending a message hits `POST /meera/sessions/{id}/messages`, receives a `streamToken` + `streamUrl`, and opens the stream.
- [ ] Assistant text streams token-by-token into a single growing bubble (TTFT target ≤1.2s p50 surfaced — Task 10).
- [ ] `thinking` events drive the T3 step log with check-offs.
- [ ] On stream error, the UI falls back to the non-stream `?after=<messageId>` fetch and shows the finalized turn.
- [ ] The mock turn engine still runs under `?demo=true` (dev-only, Kabir A2 pattern) for deterministic QA.
- [ ] Composer locks during streaming, unlocks on `done`/`error`; no double-send.

---

## TASK GROUP 3 — SSE streaming client · CORE

**Status:** NEW. This is the spine of the real Meera. Build it standalone, unit-tested, before wiring the chat.

**Contract (`04` §1 endpoints, §4 protocol; `02` §4; `11` Flow 2 step 2):**
- The browser connects **directly to Python** at `GET {streamUrl}?token=<streamToken>` (SSE), presenting the **short-lived scoped stream token** Spring issued (`aud=meera-stream`, ≤60s, single-use, bound to `workspaceId`+`conversationId`+`messageId`). **The browser never sends a user JWT to Python and never holds an LLM key** (Guardrail 6 — `04` Appendix; Task 10 G6).
- Python validates the token before emitting a byte; no valid token → no stream.

### The exact SSE event protocol to handle (`04` §4 — cite verbatim)
```
event: token        data: {"text": "Got it — Vitamin C"}                       // incremental assistant text
event: thinking     data: {"step": "Scanning 300 creators", "done": false}     // T3 log line
event: tool_start   data: {"name": "show_creators", "input": {...}}            // canvas glue
event: tool_result  data: {"name": "show_creators", "status": "ok"}            // stage advance
event: prompt_meta  data: {"prompt_version": "meera-2026.07.05"}
event: done         data: {"finish_reason": "stop"}
event: error        data: {"code": "provider_timeout", "fallback": "text"}
```
Plus: **heartbeat comment `: ping` every ~15s** (keep-alive through proxies) — ignore it, but treat its *absence* (>~30s silence) as a dead connection.

### Implementation notes
| Concern | Requirement |
|---|---|
| Transport | `EventSource` does **not** support custom headers, and the token is a **query param** here — good (`?token=`). If POST-with-body streaming is ever needed, use `fetch` + `ReadableStream` SSE parsing instead. Confirm with Vikram which Python exposes; `04` §1 says `GET` SSE for the direct-browser path. |
| Event dispatch | Register named listeners for each `event:` type (`token`/`thinking`/`tool_start`/`tool_result`/`prompt_meta`/`done`/`error`). `JSON.parse` each `data:` payload defensively (try/catch — a malformed frame must not crash the stream). |
| Heartbeat | Track last-message timestamp; if no data or `: ping` for ~30s, treat as stale → reconnect or fall back. |
| Reconnect | On transient disconnect **before `done`**, reconnect **is risky** — the stream token is single-use + `messageId`-bound and may be spent. **Preferred recovery: fall back to `GET /meera/sessions/{id}/messages?after=<messageId>`** (`02` §4.5) to fetch the finalized turn rather than re-opening a spent token. Do NOT silently re-`POST message` (that decrements credits again). |
| Cancellation | **On component unmount, on new send, and on user "stop": close the EventSource** — Python cancels in-flight provider calls on client disconnect (`04` §4, no wasted tokens). This is mandatory; a leaked stream burns provider cost. |
| `error` event | `{code, fallback:"text"}` → render an inline degraded notice, keep the text path alive, do not wall the UI (`04` §5 fallbacks). |
| `prompt_meta` | Capture `prompt_version` for support/telemetry only; do not display. |

### Suggested shape
`src/hooks/useMeeraStream.ts` → `{ open(streamUrl, token, handlers), close(), status }` with handlers `{ onToken, onThinking, onToolStart, onToolResult, onDone, onError }`. Keep it **transport-only** (no UI, no stage logic) so Task 2/4 consume it.

### Definition of Done
- [ ] All 7 event types parsed and dispatched; malformed frames are swallowed without killing the stream.
- [ ] Heartbeat silence >~30s detected → recovery path.
- [ ] Recovery uses the non-stream `?after=` fetch, never a credit-double-spending re-POST.
- [ ] Stream closes on unmount / new send / stop — verified no orphaned connections (Network tab shows the SSE request terminating).
- [ ] `error` event degrades to text, never a dead end.
- [ ] Unit tests: token concatenation, thinking check-off, tool sequence, done, error, and a truncated/garbled frame.

---

## TASK GROUP 4 — Tool-result canvas renderers

**Status:** WIRE-UP. Stage 2 (`StageRecommend`), Stage 3 (`StageMatching`), and the campaign preview all **exist** on mock data. Rebind them to the real tool payloads that arrive via `tool_result` events (Task 3) and the tool DTO shapes in `02` §3.

**Payload shapes (frozen in `02` §3.1–3.3):**
```jsonc
// show_creators (02 §3.1)
{ "creators": [ { "creatorId":"01J...", "displayName":"…", "followers":150000, "engagementRate":5.2 } ], "matchedTotal": 38 }
// calculate_budget (02 §3.2)
{ "pool":15000, "perCreator":1000, "platformFee":2250, "total":17250 }
// create_campaign (02 §3.3)
{ "campaignId":"01J...", "status":"DRAFT", "serverBudget":15000 }
```

| Tool | Renderer (existing) | Bind to |
|---|---|---|
| `show_creators` | `StageMatching` + `CreatorCard` grid (T4 verified badge, stagger, re-filter T8) | `creators[]` + `matchedTotal`; count-up "38 found → top 15" |
| `calculate_budget` | `StageRecommend` + `FeeBreakdown` (T5) + `StatPair` count-ups (T6) | `pool` / `perCreator` / `platformFee` / `total` — **display the SERVER numbers only** (never re-compute client-side; the server number is the only quotable one — `06` #4) |
| `create_campaign` | Stage 2 campaign-card / draft preview | `campaignId` + `status:DRAFT` + `serverBudget` |
| stage advance | `useMeeraStage.advance(name)` | Fire on `tool_result {status:"ok"}`, not on script |

### State
- `toolState: Record<toolName, { status, data }>` accumulated across the turn; a `tool_start` shows the loading/thinking affordance, `tool_result` resolves it and advances the stage.
- On `tool_result.status !== "ok"`, show a per-stage error affordance (retry via re-ask), don't crash the canvas.

### Definition of Done
- [ ] Each of the 3 read/draft tools renders its real payload into the correct existing stage.
- [ ] Budget numbers shown are **exactly** the server's (`total`, `perCreator`) — a regression test asserts the UI never shows a client-computed budget.
- [ ] Creator cards show only allow-listed public stats (`displayName`, `followers`, `engagementRate`) — **no PII** (`02` §3.1, `06` #5).
- [ ] `tool_start`→`tool_result` drives the stage transition and the T3 log; failure states are handled.

---

## TASK GROUP 5 — Commit-tier confirm controls (the human-click gates) · SECURITY-CRITICAL

**Status:** NEW + WIRE. This is the load-bearing security UX of the whole product. **Kabir launch-blocking gate.**

### The one rule the frontend MUST enforce (`06`, §THE GOVERNING PRINCIPLE)
> **Meera proposes. Spring disposes. The HUMAN commits money.** "The customer said yes" in chat is **NOT** authorization. Consent for anything money- or contract-binding must be an **authenticated human action in the UI — a real click on a real confirm control tied to the live session** — never Meera inferring agreement from conversation.

Concretely for Ananya:
- Meera (via `request_payment` / `confirm_launch` / send-proposal tool results) can **only surface a button, pre-filled.** She can never auto-submit a money/commit action.
- The commit click calls a **PUBLIC Spring endpoint on the user's JWT** — **NOT** anything under `/internal/meera/*`, and **NOT** routed through Python. Python is not in the money leg at all (`11` Flow 3 note, `06` §ENFORCEMENT).
- The **amount is never sent from the client as authoritative.** For escrow fund, the body carries `campaignId` (+ optional `milestoneId`) — **not the amount** (`02` §1.4: "The amount is NOT in the body"). Spring re-derives it. The client may display a hint, but it must fetch/confirm the authoritative number from the server before/at confirm.

### The four commit gates

| Gate | Trigger (tool_result) | Human-click control → endpoint | Body |
|---|---|---|---|
| **Fund escrow** | `request_payment` → `AWAIT_HUMAN_CONFIRM` (`02` §3.4) | **"Fund & go live"** → `POST /wallet/escrow/fund` (BRAND OWNER/ADMIN) | `{ campaignId, milestoneId:null }` + **required `Idempotency-Key` header (client UUID)** — **NO amount** |
| **Confirm launch** | `confirm_launch` proposal | **"Approve & release" / "Go live"** → succeeds only after escrow `FUNDED` (server-verified) | `{ campaignId }` |
| **Send proposal** | send-proposal (envelope) | **"Send proposal"** confirm UNLESS within a pre-approved budget envelope (`06` Ruling B) | per contract; over-envelope → server `403` → force human confirm |
| **Contract e-signature** | contract-generated (Draft) | **"Review & sign"** e-signature step (`06` Ruling C — Meera drafts, never signs) | signature payload; signed PDF SHA-256 stored server-side |

### Components
| Component | New/Reuse | Notes |
|---|---|---|
| `PayButton` / "Fund & go live" | REUSE (exists) | Rewire from mock `markPaid()` to a real `POST /wallet/escrow/fund` call. Generate + send a client `Idempotency-Key` (UUID) header; retry-safe. Loading→success→locked (already built). |
| `ConfirmLaunchButton` | EXTEND | The existing `handleGoLive`/"Approve & release" CTA — gate it on server-confirmed `FUNDED` escrow status (poll `GET /wallet/escrow/{id}`), not on a local `lockComplete` flag. |
| `ProposalConfirm` | NEW | Envelope-aware confirm; if server returns `403` (over-envelope), force an explicit confirm dialog. |
| `ContractSignFlow` | NEW | Review draft PDF → e-sign → show signed state + hash. |
| Razorpay handoff | NEW | `POST /wallet/escrow/fund` returns a `razorpayOrderId`; open Razorpay Checkout; escrow only funds on **webhook verification** server-side (`02` §1.4) — the UI shows "Securing…" until `GET /wallet/escrow/{id}.status === FUNDED`, **never assumes success from the client callback.** |

### Security UX requirements (all enforced by the frontend, verified by Kabir)
- [ ] **No commit action is ever auto-triggered by chat content or a tool_result alone.** A tool_result only *renders the button*; a real user click submits it.
- [ ] Commit buttons call **public** endpoints on the **user JWT** — audit that none of them ever hit `/internal/meera/*` (that surface is not even reachable from the browser).
- [ ] **No amount is client-authoritative.** The escrow-fund request sends `campaignId`, not amount; the displayed number is fetched/confirmed from the server. A test asserts the request body carries no `amount` field.
- [ ] Every commit request carries an **`Idempotency-Key`** (client UUID) and is safe to retry (`02` §1.4).
- [ ] "Money moves only when you approve" microcopy (T7) present at the lock; the button names the action ("Fund & go live", "Pay ₹17,250", "Approve & release") — never "Submit"/"OK" (`FRONTEND-BUILD-SPEC-MEERA.md` §3 Tejas rules).
- [ ] Funding success is confirmed by **server escrow status = FUNDED** (webhook-verified), not by the Razorpay client callback.

### Definition of Done
- [ ] Escrow-lock hero (T2) plays on **real** `GET /wallet/escrow/{id}` → `FUNDED`, not a mock timer.
- [ ] All four gates render only as human-clickable controls; none can be fired by Meera/chat/tool-result automatically.
- [ ] Request bodies verified: escrow-fund carries `campaignId` + `Idempotency-Key`, **no amount**.
- [ ] Over-envelope proposal → server `403` → explicit human confirm.
- [ ] Contract flow shows draft → human e-sign → signed+hashed state; no client-side "sign for you" path.
- [ ] Kabir sign-off on the commit surface (launch-blocking).

---

## TASK GROUP 6 — Wallet / escrow / contract UI

**Status:** Partial NEW. The escrow **pill** (T1), **fee breakdown** (T5), and **lock hero** (T2) exist. Balance, live escrow-status polling, and the contract flow need real wiring.

| Surface | Component | Binds to |
|---|---|---|
| Wallet balance | `WalletBalance` (NEW or reuse existing brand wallet widget) | brand wallet read endpoint (`06` #6 — read-only; Meera can read but never move) |
| Escrow status pill (T1) | `EscrowPill` (REUSE) | `GET /wallet/escrow/{escrowHoldId}.status` → map `PENDING/DRAFT → SECURED(FUNDED) → LIVE(RELEASING)` |
| Escrow state machine display | `EscrowPill` variants | **DRAFT → SECURING → 🔒 SECURED → RELEASING** — green (`--escrow`) only on SECURED+ (trust color is sacred, `FRONTEND-BUILD-SPEC-MEERA.md` §2) |
| Contract generate/sign | `ContractSignFlow` (Task 5) | contract generate + sign endpoints; signed-PDF hash |
| Razorpay handoff | Razorpay Checkout | order from `POST /wallet/escrow/fund`; webhook-verified funding |
| Payout ledger (T9) | `PayoutLedger` (REUSE) | per-creator release rows; `@creator ₹1,000 released ✓` + timestamp |

### State
- Poll `GET /wallet/escrow/{escrowHoldId}` after a fund action until terminal (`FUNDED`/`RELEASED`), then stop. Reflect status in the pill live (T1 always-visible).
- Escrow-status transitions drive the canvas: `FUNDED` → play lock hero → enable "Go live".

### Definition of Done
- [ ] Escrow pill reflects **real** server status (`PENDING/FUNDED/RELEASED`) with correct color semantics; green only means secured/released.
- [ ] Wallet balance is **read-only** in the Meera surface — no UI path lets Meera move/add funds (`06` F1/F2/F3 are structurally absent server-side; the UI must not imply otherwise).
- [ ] Razorpay handoff opens Checkout and confirms funding via server webhook status, not client callback.
- [ ] Contract draft → e-sign → signed state renders; payout ledger shows real releases.

---

## TASK GROUP 7 — Voice UI (Hinglish, edit-first)

**Status:** WIRE-UP. The §5A voice layer is **built** on the Web Speech API mock (`ANANYA-BUILD-NOTES.md` §8): `MicButton`, `VoiceToggle`, `VoiceWaveform`, `MeeraPresence`, `useVoiceInput`, `useVoiceOutput`, `cleanTranscript` mock seam. Swap the mock STT/cleanup/TTS for the real backend voice endpoints when Domain D voice lands.

**Flow (`04` §5, `11` Flow 5):**
```
tap-and-talk → POST /voice/transcribe (audio)
   → { raw_transcript, cleaned_text, lang_detected }   (Hinglish-aware, grammar-cleaned)
   → show cleaned_text in the composer EDIT-FIRST (NOT auto-sent)
   → user tweaks → normal /chat turn
reply → POST /voice/speak (text) → audio (optional TTS, synced to MeeraPresence waveform)
```

| Component | Change |
|---|---|
| `useVoiceInput` / `cleanTranscript` | Replace the local Web Speech STT + whitespace-mock cleanup with `POST /voice/transcribe`; render `cleaned_text` into the composer **edit-first** (never auto-send — the trust rule, `04` §5). Keep the graceful-fallback state machine (already built + Kabir A4 length cap). |
| `useVoiceOutput` | Replace `speechSynthesis` with `POST /voice/speak` audio; **never block a reply on audio** — text renders immediately, audio plays alongside if enabled+working. |
| `MicButton` | idle → listening → transcribing → editable result; on STT fail → "Didn't catch that — type it instead?" → text. |
| `MeeraPresence` / `VoiceWaveform` | Presence talking state synced to TTS playback (already wired to `isSpeaking`). |
| `VoiceToggle` | Persistent speak-replies on/off (localStorage — already built). |

### Graceful fallback (mandatory at every stage — `04` §5)
| Failure | Behavior |
|---|---|
| Mic/audio unsupported, permission denied | stay on text composer, no error wall (MicButton simply not shown when unsupported) |
| STT fails / low confidence | "Didn't catch that — type it instead?" → text |
| Cleanup errors | fall back to `raw_transcript` in the composer (still edit-first) |
| TTS fails | disable voice-output silently; text reply already rendered |
| Any provider timeout | SSE `error` `{fallback:"text"}` → continue in text |

### Credit-cost awareness (`04` §5 weighting)
Voice costs more: **voice input = 3, voice reply = 4** (vs text = 1); website analysis = 10. Spring meters these **before** calling Python. Surface the higher cost near the mic/toggle (subtle), and reflect the decrement in the credit meter (Task 8).

### Definition of Done
- [ ] Text chat is fully functional with voice OFF / unsupported / failed — **no dead ends anywhere.**
- [ ] Voice input shows editable cleaned text before sending; meaning never altered (server does cleanup; client never reinterprets).
- [ ] Hinglish input transcribes + cleans to correct English (server-side).
- [ ] TTS never blocks a reply; text renders first, audio is additive.
- [ ] `prefers-reduced-motion` → presence static (online dot only), no waveform motion.
- [ ] Voice actions metered against credits (3/4 weighting reflected); text-only baseline unchanged.

---

## TASK GROUP 8 — AI credit meter

**Status:** WIRE-UP. The `CreditPaywall` soft-wall exists. Bind the meter to real credit state; the AI credit service is **built** server-side (`15`).

**Binds to:** `GET /meera/credits` (`02` §1.3) → `{ creditsRemaining, monthlyAllotment, unlimited, unlimitedUntil, cycleStart, state }`; plus `creditsRemaining` returned on every `POST message` (`02` §1.2); plus `402 CREDITS_EXHAUSTED` (`02` §1.2, Guardrail 5).

Also handle `credit_state.mode` = `unlimited | metered | paused` (the shape Python receives, `04` §2) — surface the equivalent to the user.

| State | UX |
|---|---|
| `unlimited` (funded/live) | No meter pressure; subtle "unlimited while live" affordance |
| `metered` (FREE tier) | Show `creditsRemaining / monthlyAllotment`; decrement feedback on each turn (and 3/4 for voice) |
| Low (warning threshold) | `--warning` treatment (credits low) |
| `paused` / `402 CREDITS_EXHAUSTED` | Swap `Composer` for `CreditPaywall` — an **invitation, not an apology**: "Fund your first campaign to unlock me fully — or I'm back on the 1st." (`FRONTEND-BUILD-SPEC-MEERA.md` §3 Tejas) |

### Definition of Done
- [ ] Meter reflects `GET /meera/credits`; updates from `creditsRemaining` on each turn response.
- [ ] Decrement feedback is visible per turn (weighted: text 1, voice in 3, voice reply 4).
- [ ] `402 CREDITS_EXHAUSTED` / `paused` swaps in `CreditPaywall`; sending is blocked; fund-to-unlock CTA present.
- [ ] `unlimited` state suppresses meter pressure. Credit reset on escrow funding (`11` Flow 4 — `ai.credits_reset`) reflected after a fund.

---

## TASK GROUP 9 — Notifications UI (Domain B)

**Status:** NEW. Backend **not started** (`14` item 3) — build against a mock list, swap when Domain B lands. Also covers the deferred "proactive Meera nudge surface" (`ANANYA-BUILD-NOTES.md` §2, spec §8.9).

**Binds to (Domain B, `14` item 3 / `07-NOTIFICATION-SYSTEM-SPEC.md`):** `GET /notifications` (list), `POST /notifications/{id}/read`, unsubscribe / email-preferences endpoint. In-app notifications are emitted by the backend `NotificationListener` across ~22 domain events (e.g. `ai.site_analyzed`, `escrow.funded`, `creator.campaign_live` — `11` Flows 1 & 4).

| Component | Notes |
|---|---|
| `NotificationBell` + list | Read/unread state, badge count, mark-read on open |
| `NotificationItem` | Type-styled (info/success/warning); links into the relevant Meera stage / campaign |
| `NotificationPreferences` | Unsubscribe + email preferences (Domain B `EmailPreference`) |
| Proactive Meera nudge surface | Inbound Meera nudges surfaced in the bell + optionally the chat (spec §8.9) |

### Definition of Done
- [ ] In-app notification list with read/unread + badge count.
- [ ] Mark-as-read persists (`POST read`).
- [ ] Unsubscribe / preferences UI wired (or mock-ready with a clean adapter for Domain B).
- [ ] Proactive Meera nudges appear in the bell (and optionally chat), non-intrusive.
- [ ] Empty state handled (no notifications yet).

---

## TASK GROUP 10 — Cross-cutting (loading/error/empty, latency, a11y, secrets, resilience)

Woven through every task above; finalized before sign-off.

### Loading / error / empty states
- [ ] Every async surface (analyze, stream, tool, fund, credits, notifications) has explicit **loading, error, and empty** states. **Never a blank spinner** — the T3 "shows her work" streaming log is the loading pattern for chat (`FRONTEND-BUILD-SPEC-MEERA.md` §3 T3).
- [ ] Errors degrade, never wall: scrape fail → paste-a-link; stream fail → non-stream fetch; voice fail → text; provider timeout → text.

### Latency targets (surface + budget — `04` §4)
| Metric | Target | Frontend obligation |
|---|---|---|
| TTFT (chat, cache hit) | **≤ 1.2s p50 / ≤2.5s p95** | Open the stream the instant `POST message` returns; render the first `token` immediately; no artificial delay before first paint |
| Inter-token cadence | smooth, no >2s stalls (except tool round-trip) | Append tokens as they arrive; during a tool round-trip show the T3 log, not a stall |
| `/analyze-site` | ≤ 45s | Analyzing state must tolerate up to ~60s + fallback |
| Voice STT | ≤ 2.5s | show transcribing state, then editable result |

### Accessibility (WCAG AA — `FRONTEND-BUILD-SPEC-MEERA.md` DoD)
- [ ] Streaming assistant text in an `aria-live="polite"` region; tool/stage changes announced.
- [ ] Commit buttons are real, focusable, labelled controls (not div-clicks); focus ring uses `--accent-glow`.
- [ ] `prefers-reduced-motion` bypasses all animation; count-ups snap; lock shows final state; presence static.
- [ ] Color contrast ≥ 4.5:1 (text) / 3:1 (large UI) — the `useBrandTheme` OKLCH clamp already enforces this for the accent; verify escrow-green/danger/warning too.
- [ ] Keyboard-navigable end to end; mobile touch targets ≥ 44px.

### No secrets/keys in the frontend (Guardrail G6 — `04` Appendix, `06` §ENFORCEMENT)
- [ ] **No LLM/provider keys, no Razorpay secret, no `INTERNAL_API_KEY`, no service token ever in the client bundle or env.** The browser holds only: the user JWT (auth) and the short-lived scoped **stream token** (≤60s, `aud=meera-stream`). Nothing else.
- [ ] Only `VITE_*` vars are client-exposed; audit that no secret is `VITE_`-prefixed. Grep the built `dist/` bundle for key patterns before ship (mirror the Kabir A2 `dist/` grep discipline in `ANANYA-BUILD-NOTES.md`).
- [ ] The browser never calls Python `/internal/meera/*` and never calls Python with a user JWT (`02` §2, `04` §1.1).
- [ ] `?demo=true` / mock-auth paths are dead-stripped from production (`import.meta.env.DEV` gate — already fixed, Kabir A2/A3; keep this discipline for every new mock adapter).

### Reconnect / resilience
- [ ] SSE recovery via non-stream `?after=` fetch (never a credit-double-spending re-POST) — Task 3.
- [ ] Streams cancelled on unmount/new-send/stop (no orphaned connections, no wasted tokens).
- [ ] Idempotency-Key on every commit request (no double-charge on retry) — Task 5.
- [ ] Poll loops (analyze, escrow status, credits) back off and cancel on unmount (no leaked intervals).

### Definition of Done (cross-cutting)
- [ ] `npm run build` + `tsc --noEmit` clean (no new errors beyond the 2 known pre-existing `FadeUp`/`WordReveal` ones).
- [ ] Mobile at 375px: no overflow, canvas sheet works, all commit CTAs reachable.
- [ ] Lighthouse ≥ 85 mobile; no layout shift (reserve dimensions).
- [ ] Kabir security pass on the commit surface + no-secrets audit; Kavya QA; Meera/DevOps build verify.

---

## APPENDIX — INVARIANTS THE FRONTEND MUST NOT VIOLATE

1. **The human commits money, never Meera.** Chat "yes" is not consent. Every money/contract action is a real human click on a real control calling a **public** Spring endpoint on the **user JWT** (`06`).
2. **No amount is client-authoritative.** Escrow-fund sends `campaignId`, not an amount; Spring re-derives (`02` §1.4). Budget numbers displayed are the server's, never client-computed (`06` #4).
3. **The browser never talks to Python `/internal/*` and never sends a user JWT to Python.** Browser → Spring (`/api/v1/*`) and Browser → Python SSE edge (scoped stream token only) (`02` §2, `04` §1.1).
4. **No secrets in the client.** Only the user JWT + the ≤60s scoped stream token ever reach the browser (G6).
5. **Every stream is cancellable and every commit is idempotent.** Close SSE on unmount/send; `Idempotency-Key` on every money call.
6. **Text always works alone.** Voice, TTS, and streaming are enhancements; every failure falls back to a fully functional text path — no dead ends (`04` §5, §7).
7. **Trust color is sacred.** `--escrow`/`--success` green means secured/released/verified and nothing else; brand theming drives the accent layer only (`FRONTEND-BUILD-SPEC-MEERA.md` §2).

— Priya
