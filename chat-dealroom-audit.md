# Chat & Deal Room — Accept / Reject / Message Not Appearing (Both Sides)
## Root-Cause Audit v2 · proof-os 0.4.2

**Date:** 2026-08-09 · Branch: fix/brand-audit-remediation  
**Issue:** "Very high issue — accepted or rejected deal, or message sent, not showing in chat or deal page on either side"  
**Verdict:** BELIEVED (fresh-context Priya §6 check: APPROVED AS CORRECTED after v2 rewrite — see creatorF.md §9)  
**v1 rejection reason:** 7 wrong-line citations, fabricated symbol, missed highest-severity surface (`deal-room-dashboard.tsx`)

---

## Source Files Read (Law 3 — real source, no summaries)

| File | Lines read |
|---|---|
| `src/pages/creator-chat.tsx` | L1–1711 |
| `src/pages/brand-chat.tsx` | L1–2090 |
| `src/components/brand/deals/deal-room-dashboard.tsx` | L278–428 (action handlers + loadMessages) |
| `influora-api/.../DealController.java` | L1–223 (full) |
| `influora-api/.../DealService.java` | L1–1341 (full) |
| `influora-api/.../DealMessageStreamRegistry.java` | L1–97 (full) |
| `src/lib/api.ts` — messages section | L1612–1916 |

**Not read:** `src/pages/brand-deals.tsx` (router shell only), `src/pages/creator-deals.tsx`, `brand-campaign-detail.tsx`, `AuthService.java`. None affects the findings below.

---

## What Is Working Correctly (Do Not Change)

| Component | File:Line | Status |
|---|---|---|
| SSE frame format | `DealMessageStreamRegistry.java:74` | ✅ `event: deal-message` + JSON data — correct named-event format |
| Frame parser | `api.ts:L1899–1916` | ✅ Named-event handling, leading-space strip, null for heartbeats |
| UPSERT merge (creator) | `creator-chat.tsx:L901–907` | ✅ `idx === -1` → append; else replace — proposal card mutations work |
| UPSERT merge (brand chat) | `brand-chat.tsx:L1148–1154` | ✅ Same pattern |
| After-commit publish | `DealService.java:L521–533` | ✅ `afterCommit` callback — subscribers only ever see committed state |
| Ordered publish (accept) | `DealService.java:L665–667` | ✅ Settled card before system message — Accept buttons retire first |
| Ordered publish (reject) | `DealService.java:L376–378` | ✅ Same order |
| Actor catch-up (creator chat) | `creator-chat.tsx:L842–847` | ✅ `afterDealMutation = Promise.all([refreshDeal, loadMessages])` |
| Actor catch-up (brand chat) | `brand-chat.tsx:L1345` | ✅ Inline `await Promise.all([refreshDeal(dealId), loadMessages(dealId)])` |
| Reconnect catch-up (creator) | `creator-chat.tsx:L935–936` | ✅ `onReconnect` → `loadMessages + refreshDeal` |
| Reconnect catch-up (brand chat) | `brand-chat.tsx:L1183–1184` | ✅ Same |
| Live render source (brand chat) | `brand-chat.tsx:L1912–1986` | ✅ Live mode renders from `liveMessages` only; demo gated at `!isApiLive()` L1990 |
| 401 retry / terminal close | `api.ts:L1754–1772` | ✅ One `http.bootstrap(role)` retry; 401/403/404 → `closed`, no more reconnects |
| Single-instance limitation documented | `DealMessageStreamRegistry.java:L21–32` | ✅ Redis Pub/Sub upgrade path noted in source |

---

## Root Causes — Ranked by Impact

---

### M-1 🔴 CRITICAL — `/brand/deals` has no SSE and no message refetch after actions

**The most likely cause of the exact symptom reported.**

`src/components/brand/deals/deal-room-dashboard.tsx` is the "Deals" page reached from the main brand navigation (`brand-layout.tsx:88`). This is the primary "deal page" in the ticket.

**SSE:** grep across the entire file returns zero `messages.stream` calls. There is no `onMessage` handler, no `streamStatus`, no reconnect banner. The timeline is a one-shot load — `loadMessages` at `L278–291`, fired once on deal selection (`L294–298`).

**After accept/reject/counter:** all three action handlers call `await loadDeals()` only. `loadMessages` is never called after an action:

| Handler | File:Line | What it calls after API | Missing |
|---|---|---|---|
| `handleAcceptProposal` | `deal-room-dashboard.tsx:L356–376` | `await loadDeals()` only | `loadMessages` |
| `handleSendCounter` | `deal-room-dashboard.tsx:L378–405` | `await loadDeals()` only | `loadMessages` |
| `handleRejectProposal` | `deal-room-dashboard.tsx:L407–428` | `await loadDeals()` only | `loadMessages` |

**Effect:** after a brand accepts, counters, or rejects on this surface, **neither side sees any change in the message timeline** — not the actor, not the counterparty. The deal list badge may update (from `loadDeals`), but the chat thread shows no settled card, no "Brand accepted the proposal" system message, nothing. The backend wrote both; they are just never fetched.

**Fix (1–2 hours):** In each handler, after `await loadDeals()`, add `await loadMessages(selectedDeal.id)`. This is the same `afterDealMutation` pattern that `creator-chat.tsx:L842–847` and `brand-chat.tsx:L1345` already use.

---

### M-2 🔴 HIGH — Brand reject is absent from `brand-chat.tsx`

`dealsApi.reject` does not appear in `src/pages/brand-chat.tsx`. The proposal card in live mode renders an "Accept" button and a "Counter" button (visible at `brand-chat.tsx:L1552–1580`), but no Decline/Reject. `dealsApi.reject` exists only in `deal-room-dashboard.tsx:L414` and `brand-campaign-detail.tsx:L698`.

A brand on the `/brand/chat` route cannot reject a proposal at all. They must navigate to the Deals page or the campaign detail. The report's "same trace applies symmetrically when creator rejects" was wrong: symmetry on the chat surface only holds for Accept and Counter.

**Fix:** Add a Decline handler and button to `brand-chat.tsx` mirroring `handleDeclineProposal` in `creator-chat.tsx:L1299–1334`.

---

### C-1 🔴 HIGH — Counterparty has no fallback poll when SSE is dead (brand-chat / creator-chat)

**Applies to the `/brand/chat` and `/creator/chat` surfaces.** On these surfaces, SSE is wired correctly for happy-path delivery, but the counterparty has no periodic poll.

When side A accepts/rejects a deal from `/brand/chat` or `/creator/chat`:
- **Actor:** `afterDealMutation` / inline `Promise.all([refreshDeal, loadMessages])` fires immediately → actor sees the change regardless of SSE state. ✅
- **Counterparty:** receives the event only if their SSE connection is alive. If dead (30-min timeout, network drop, tab backgrounded), `DealMessageStreamRegistry.publish()` finds no emitter → **event silently discarded**. ❌

Recovery: SSE `onReconnect` → `loadMessages + refreshDeal`. But only after exponential backoff (base 1 000 ms, max 30 000 ms per `api.ts:L1612–1613`, jittered into the top half of each window per `api.ts:L1718`, stability reset at `api.ts:L1830`). A flapping backend can extend gaps to 15–30 seconds. If `streamStatus` reaches `closed` (terminal 401/403/404), there is no further reconnect — the user must reload.

**Note:** `streamStatus` initialises to `'open'` on both pages (`creator-chat.tsx:L760`, `brand-chat.tsx:L708`). The degraded banner is suppressed during the initial connect window and the first failure window — a brief silent period before the UI acknowledges the problem.

| File | Line | Role |
|---|---|---|
| `DealMessageStreamRegistry.java` | L67–80 | `publish()` silently returns when emitter list is empty |
| `creator-chat.tsx` | L895–943 | SSE effect — no poll fallback |
| `brand-chat.tsx` | L1142–1194 | SSE effect — no poll fallback |
| `api.ts` | L1612–1613 | Backoff constants: base 1 000 ms, max 30 000 ms |
| `api.ts` | L1718 | Jitter: top half of ceiling |
| `api.ts` | L1830 | Stability reset: only resets when connection held ≥ `STREAM_STABLE_MS` |

**Fix (1–2 hours):** `useEffect` in both chat pages: when `streamStatus !== 'open'`, call `loadMessages(dealId) + refreshDeal(dealId)` every 20 seconds. Stop when `streamStatus === 'open'`.

---

### C-2 🔴 HIGH — Single-instance SSE registry: cross-replica silent drop

`DealMessageStreamRegistry` is a JVM-heap `ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>>` (`DealMessageStreamRegistry.java:L21–32`). With 2+ backend replicas behind a load balancer, a write on Instance A publishes to Instance A's registry only — Instance B's clients are invisible. Event is silently dropped; no error, no retry, no log.

**Severity note:** This is conditional on replica count. If the live VPS runs a single replica (likely, MVP), this bug is dormant. It becomes the dominant failure mode the moment any horizontal scaling or rolling restart occurs.

**Fix (architectural, weeks):** Redis Pub/Sub or Postgres `LISTEN/NOTIFY` per-dealId channel. Each replica subscribes; any replica that handles a write publishes; all replicas fan out to their local emitters.

---

### C-3 🟡 MEDIUM — 30-minute emitter timeout creates recurring drop windows

`EMITTER_TIMEOUT_MS = 30L * 60 * 1000` (`DealMessageStreamRegistry.java:L39–40`). Every 30 minutes every emitter times out. During the reconnect window any published event is dropped. `onReconnect` refetches on successful reconnect, but events published during an extended failure window are permanently lost for that client.

**Fix:** Server-side heartbeat comments every 25 seconds keep connections alive through proxy idle timeouts. Extend `EMITTER_TIMEOUT_MS` to 55 minutes. Implement `Last-Event-ID` replay for gap recovery.

---

### M-4 🟡 MEDIUM — Brand counter on `/brand/chat` skips `refreshDeal`

`brand-chat.tsx:L1278–1299`: after `api.deals.counter(...)`, calls `await loadMessages(dealId)` only. `selectedDeal` (and `canRespondToProposal`, which derives from `selectedDeal.rawStatus`) is left stale until the next SSE frame or manual navigation. The creator equivalent (`creator-chat.tsx:L1367`) calls `afterDealMutation`, which does both `refreshDeal` + `loadMessages`.

**Fix:** Add `refreshDeal(dealId)` after the counter API call, matching the creator pattern.

---

### C-4 🟡 MEDIUM — Deliverables panel empty for every live deal (not cosmetic)

`DealService.toDealResponse()` hardcodes `deliverablesDone = 0` (`DealService.java:L1115`), `deliverablesTotal = 0` (`L1116`), `nextDeadline = null` (`L1117`).

**Mechanism:** `brand-chat.tsx:L511–516` gates the entire Deliverables panel: `if (!deal || deal.deliverablesTotal === 0) return []`. With the hardcoded `0`, this condition is always true — the Deliverables panel returns an empty list for every live deal. Not cosmetic: a real deal with real deliverables shows a blank panel. The sidebar `{deal.progress > 0 && ...}` guard (`L1730`) also hides the progress bar, but that is a secondary effect.

**Fix:** Compute real counts from the deliverable table in `DealService.toDealResponse()`. `nextDeadline` = earliest incomplete deliverable due date.

---

### M-6 ℹ️ LOW — `streamStatus` initialises to `'open'` before first connection resolves

`creator-chat.tsx:L760`, `brand-chat.tsx:L708`. Both pages initialise `streamStatus` to `'open'`. The degraded banner (`streamStatus !== 'open'`) is suppressed during the initial connect and during the first failure — a silent window before the UI signals that updates are not live.

**Fix:** Initialise to `'connecting'` and set to `'open'` only on the first successful SSE frame or heartbeat. Show a neutral "Connecting…" state initially rather than asserting live before it is.

---

## End-to-End Trace: "I accepted — other side doesn't see it"

### On `/brand/deals` (deal-room-dashboard.tsx) — unconditional blackout

| Step | What happens | File:Line |
|---|---|---|
| Brand clicks Accept | `handleAcceptProposal` fires | `deal-room-dashboard.tsx:L356` |
| API call | `dealsApi.accept(selectedDeal.id, 'brand')` | `deal-room-dashboard.tsx:L364` |
| Backend persists + publishes | `DealService.doAccept()` → `publishToStream` × 2 | `DealService.java:L624–683` |
| Brand side | `await loadDeals()` — deal list updates, timeline does NOT | `deal-room-dashboard.tsx:L365` |
| Brand timeline | No `loadMessages` called → **settled card and system message never appear for the actor** | — |
| Creator SSE alive ✅ | `onMessage` → UPSERT settled card, append system msg | `creator-chat.tsx:L901–907` |
| Creator SSE dead ❌ | `publish()` finds no emitter → silent drop → creator sees nothing | `DealMessageStreamRegistry.java:L67–80` |

### On `/brand/chat` or `/creator/chat` — conditional on SSE state

| Step | What happens | File:Line |
|---|---|---|
| Actor clicks Accept | `handleAcceptProposal` | `brand-chat.tsx:L1327`, `creator-chat.tsx:L1257` |
| API call | `dealsApi.accept(...)` / `api.deals.accept(...)` | respective pages |
| Backend commits + publishes | `DealService.doAccept()` → `publishToStream` × 2 | `DealService.java:L624–683` |
| Actor sees change | `Promise.all([refreshDeal, loadMessages])` fires immediately | `brand-chat.tsx:L1345`, `creator-chat.tsx:L842–847` |
| Counterparty SSE alive ✅ | `onMessage` → UPSERT + append | both chat pages |
| Counterparty SSE dead ❌ | `publish()` silent discard → counterparty sees nothing | `DealMessageStreamRegistry.java:L67–80` |
| Counterparty recovers | SSE reconnect → `onReconnect` → `loadMessages + refreshDeal` | both chat pages |
| Counterparty blocked | `streamStatus: 'closed'` → must reload | `api.ts:L1765–1772` |

---

## Defect Summary

| ID | Severity | Surface | Finding | Fix effort |
|---|---|---|---|---|
| M-1 | 🔴 CRITICAL | `/brand/deals` | No SSE + accept/reject/counter never call `loadMessages` — timeline never updates for actor or counterparty | 1–2 hrs |
| M-2 | 🔴 HIGH | `/brand/chat` | `dealsApi.reject` absent — brand cannot decline from the chat surface | 1–2 hrs |
| C-1 | 🔴 HIGH | `/brand/chat`, `/creator/chat` | No poll when SSE dead — counterparty blackout until reload | 1–2 hrs |
| C-2 | 🔴 HIGH | All surfaces | In-memory SSE registry — cross-replica silent drop (dormant on single-replica) | Weeks |
| C-3 | 🟡 MEDIUM | All surfaces | 30-min emitter timeout creates recurring drop windows | Hours |
| M-4 | 🟡 MEDIUM | `/brand/chat` | Counter skips `refreshDeal` — `selectedDeal` stale after counter | 30 min |
| C-4 | 🟡 MEDIUM | `/brand/chat`, `/brand/deals` | Deliverables panel empty for every live deal (hardcoded 0) | Hours |
| M-6 | ℹ️ LOW | `/brand/chat`, `/creator/chat` | `streamStatus` starts `'open'` — silent initial failure window | 30 min |

**Total: 🔴 4 · 🟡 3 · ℹ️ 1**

---

## Not Checked (Law 5)

- Whether the live VPS runs 1 or N replicas (C-2 dormant on single-replica)
- `src/pages/creator-deals.tsx` — whether the creator-side deal list page has the same M-1/M-2 pattern
- `brand-campaign-detail.tsx:L698` — the third surface with `dealsApi.reject` — whether it also skips `loadMessages`
- `STREAM_STABLE_MS` constant value (controls when backoff resets)
- Whether payment/escrow system messages also publish through `DealMessageStreamRegistry` (same C-1/C-2 risk)
- Live two-browser E2E confirming the counterparty blackout and M-1 timeline silence

**Skipped:** [§0-4 OS scripts] — proof-os Python tools not in session path
