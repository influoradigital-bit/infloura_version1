# B-1 Deal Room — Frontend Rebuild (in-session, post data-loss)

**Date:** 2026-07-11 · **By:** orchestrator (in-session, direct) · **Branch:** `feature/analytics-platform`

## Why this exists
Ananya's original Deal Room frontend wiring was **destroyed** by a mid-session `git stash`
(reverted the tracked `brand-chat.tsx` to HEAD). Root cause + guardrails: commit `f86f90c`.
This is the durable, committed rebuild of the frontend half.

## What was wired (live, `isApiLive()`-gated)
`src/pages/brand-chat.tsx`:
- **Messages — load:** `loadMessages()` calls `api.messages.list('brand', dealId)` on deal
  select, fires a best-effort `markRead`, and renders real `DealMessage[]` in the feed.
  Loading spinner, error state + **Retry**, and empty state. On error it **clears stale rows**
  from the previously-selected deal (this is exactly the M-2 class Kavya flagged on the
  deliverables path — applied here to messages).
- **Messages — send:** `handleSendMessage` is now async; in live mode it `POST`s via
  `api.messages.send('brand', dealId, content)` and appends the returned message. On failure it
  **restores the input text** and shows an error — no silent loss.
- **Demo mode untouched:** the entire scripted mock timeline + local `chatMessages` path is now
  gated behind `{!isApiLive() && (...)}` and behaves exactly as before.

## Backend (already committed, `9761f71`)
- `GET /deals/{dealId}/deliverables` (workspace-scoped, uniform 404 cross-workspace).
- Brand-role message send/list test coverage in `DealServiceTest`.
- **Kabir M-1 fix:** user-initiated `sendMessage` forced to `kind = text` (no privileged-card spoof).
- Kabir security PASS 0C/0H (`brand-deal-room-B1-kabir-redteam.md`).

## Verification done
- `npx tsc --noEmit` — **zero errors in `brand-chat.tsx`** (only pre-existing jest-dom matcher
  errors in unrelated `*.test.tsx`).
- `npm run build` — **PASS** (`✓ built in 14.81s`; only pre-existing chunk-size warnings).

## Honest gaps / remaining B-1 follow-ups (NOT done — do not mark closed)
1. **Deliverables live render** — `api.deliverables.list` return type is untyped (`unknown`);
   mapping it into `DealDeliverableItem` shape was deferred rather than guessed blind. The
   deliverables panel still uses mock in both modes. Needs the DTO shape confirmed, then the
   same loading/error/empty treatment.
2. **Runtime verification (Meera)** — build passes but the flow was NOT clicked through in a
   browser (preview pane hung twice this session). A real login → deal → send/read pass is the
   remaining gate before B-1 is `[x]`.
3. **Shipment persistence** — no backend (Vikram confirmed); still an honest local-only gap
   pending a Priya product decision.

## Status
Backend ✅ committed · Messages FE ✅ committed + build-verified · Deliverables-live ⏳ ·
Runtime verify ⏳ (Meera). **B-1 is `[~]` in progress, not `[x]`.**
