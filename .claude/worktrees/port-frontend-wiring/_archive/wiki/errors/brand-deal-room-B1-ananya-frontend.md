# Brand Deal Room (B-1, 60% → live) — Ananya frontend wiring

**Item:** `wiki/tech/BRAND_ADMIN_PENDING_WORK.md` PART 1 / P1 — "Deal Room (60% → live)."
**Owner split:** Vikram (backend, `wiki/errors/brand-deal-room-B1-vikram-backend.md`) + Ananya (this report).
**Date:** 2026-07-11.

## Starting state (stall recovery)

A prior attempt at this task stalled on an environment watchdog, not a code error. Before writing
anything, ran `git status`/`git diff --stat` to check for partial work:

- `src/pages/brand-chat.tsx` (the real routed Deal Room page — confirmed no `src/components/brand/**/brand-chat.tsx`
  exists, per Vikram's report) had a small **pre-existing, unrelated** 12-line diff already on disk: the
  `pdfDownloadUrl` honest-gap fix from the earlier "P1-#5 re-re-review" cycle (Kavya/Vikram, same date). Left
  untouched — not part of B-1's messages/deliverables scope.
- `src/components/brand/contracts/contracts-and-deliverables.tsx` had a similar unrelated, already-complete
  45-line diff (same PDF-gap fix in a different component). Left untouched.
- `src/lib/api.ts` had 94 lines of **comment-only** changes from Vikram's cycle (documenting real vs.
  not-yet-built contract/deliverable routes) — no behavioral changes, confirmed already correct against his
  handoff report. No further edits needed there.
- No partial/broken edits to `chatMessages`/`handleSendMessage` or the deliverables wiring existed yet — that
  work had not been started. Built it fresh.

## What shipped (`src/pages/brand-chat.tsx` only, working tree, not committed)

### 1. Messages — wired to the real backend

- Added `loadMessages(dealId)` (mirrors `creator-chat.tsx`'s `loadMessages`): `api.messages.list('brand', dealId)`
  on deal open, `api.messages.markRead('brand', dealId)` fire-and-forget after a successful load, loading/error
  state (`messagesLoading`, `messagesError`), no silent-empty-on-failure — a failed load clears the list *and*
  sets a visible error with a Retry button.
- `handleSendMessage` is now async and branches on `isApiLive()`:
  - **Demo mode: byte-for-byte unchanged** — still appends to the local-only `chatMessages` bucket, same as
    before.
  - **Live mode:** calls `api.messages.send('brand', dealId, text)`, appends the real returned `DealMessage` to
    a new `liveMessages` state, surfaces send failures via `messagesError` (input text is preserved on failure
    so the user can retry — not cleared).
- `chatTimelineEvents` (the unified chat feed) now sources its `'message'`-type entries from `liveMessages`
  (filtered to `kind === 'text'`, mapped to the same shape the existing render branch already expects) instead
  of the mock `message` events, when `isApiLive()`. The mock `proposal`/`contract`/`payment` timeline cards are
  **left as cosmetic placeholders** — those state transitions are already live-wired through their own handlers
  (`api.deals.*`, `api.contracts.*`), only their chat-feed *display* is still mock. Converting those to the
  metadata-driven model `creator-chat.tsx` uses for its full timeline (`DealMessage.kind` covers
  proposal/contract/deliverable/payment too) is a materially larger rewrite than what Vikram's handoff or the
  task brief asked for this cycle — flagged below as follow-up, not done here to avoid re-stalling on scope.
- Added loading/error/empty UI directly in the chat feed (spinner while loading with nothing yet shown, a
  destructive `Alert` + Retry button on load failure, a plain "No messages yet" line when the live list is
  genuinely empty) and disabled the input/send button while a send is in flight (`sendingMessage`, with a
  spinner on the send button) — mirrors `creator-chat.tsx`'s existing pattern exactly.
- The old "newly sent messages appended live" render block (previously rendered `chatMessages` in every mode)
  is now demo-mode-only (`!isApiLive() && chatMessages...`) since live-mode sent messages flow through
  `liveMessages` → `chatTimelineEvents` instead — avoids double-rendering a brand's own message twice in live
  mode.

### 2. Deliverables — loading/error/empty states added (list wiring was already correct)

`loadBrandDeliverables`/`brandDeliverableRows` already called the real, now-backed `api.deliverables.list('brand',
dealId)` — that part needed no change. What was missing per the task brief ("no silent-empty-on-failure"): any
non-`NOT_IMPLEMENTED` failure (network error, 500, auth failure, etc.) was caught and silently reduced to an
empty list with no visible error. Added:

- `deliverablesError` state, set on any caught error that isn't the `NOT_IMPLEMENTED` gap case.
- A destructive `Alert` + Retry button in the Deliverables tool panel (mirrors the existing `contractDetailError`
  pattern on the Payments tab) when `deliverablesError` is set.
- An explicit "No deliverables have been added to this deal yet" empty state when the load succeeded but
  returned zero rows (previously: nothing rendered, looked like the panel silently failed).
- Left `deliverablesListGap` (the `NOT_IMPLEMENTED` branch) in place as defensive fallback even though the
  endpoint is now built — it just won't trigger anymore in practice; removing it entirely felt like unnecessary
  risk for zero behavioral gain this cycle.

### 3. `isApiLive()` gating

All new/changed logic (`loadMessages`, live-branch of `handleSendMessage`, the `liveMessages` merge into
`chatTimelineEvents`, `deliverablesError`) is gated behind `isApiLive()`. Demo mode's code paths are either
completely unchanged (`handleSendMessage`'s demo branch, `chatTimelineEvents`'s demo branch, `chatMessages`
render block now explicit `!isApiLive()`) or were never reached before (`loadMessages` no-ops immediately if
`!isApiLive()`). Confirmed via diff review — no demo-mode line was touched except adding the `!isApiLive()`
guard on the render block that used to run unconditionally.

### 4. Shipment — honest gap, untouched

`handleSubmitShipment`/`shipment` state was already local-only with an explicit "Not saved to the backend"
`Alert` in the UI and a code comment documenting the confirmed absence of any backend shipment concept (per
Vikram's investigation — no `Shipment` entity, no endpoint). No backend exists to wire to, so per the task
constraint this was left exactly as-is — did not add a fake save control or pretend persistence.

## Verification

- `npx tsc --noEmit -p tsconfig.json`: **zero errors in `src/pages/brand-chat.tsx`** (one round of fixes needed
  — two `as const` assertions on conditional expressions aren't legal TS, replaced with
  `as 'brand' | 'creator'` / `as 'read' | 'delivered'`). 229 pre-existing error lines remain repo-wide, all in
  unrelated files (`*.test.tsx` jest-dom matcher typing gap, `creator-wallet.tsx`, etc.) — none touched by this
  change, none newly introduced.
- `npm run build`: **succeeds**, 4602 modules transformed, ~23s. Only pre-existing warnings (duplicate
  `baseUrl` key in tsconfig, two chunks >500kB — same baseline as every prior cycle's Meera report).
- Did **not** get a clean browser smoke test — the Browser pane preview hung/timed out mid-session (the same
  class of environment issue that stalled the original attempt). Stopped rather than fight it further, since
  `tsc --noEmit` + `npm run build` already give strong signal and Meera's local-run verification is the
  authoritative gate per the standard pipeline. Flagging this so Meera knows to actually click through the
  Deal Room chat/deliverables flow in live mode, not just rely on the build passing.

## Files touched

- `src/pages/brand-chat.tsx` (this cycle's actual changes — messages + deliverables wiring, ~180 lines added/changed)

## Known follow-up (not done this cycle, flagged not fabricated)

- The chat feed's `proposal`/`contract`/`payment` cards are still mock/cosmetic in live mode (only `message` and
  `deliverable` are now real). Converting them to read from `DealMessage.metadata` the way `creator-chat.tsx`
  already does for its *entire* timeline would give full parity and is probably the "right" end state, but it's
  a bigger rewrite than this cycle's brief covers — recommend a dedicated follow-up item rather than silently
  expanding scope here.
- Browser E2E smoke test blocked by an unresponsive preview pane this session — needs a human/Meera click-through
  in live mode, not just build/typecheck, before sign-off.

## Next steps

- **Kavya**: QA review of `src/pages/brand-chat.tsx`'s message/deliverable wiring diff.
- **Kabir**: security pass on brand chat now that it's live-wired (per Vikram's next-steps note in the backend
  handoff — brand chat send/read now hits real, JWT-scoped endpoints for the first time).
- **Meera**: `npm run build` + actual click-through of the Deal Room chat feed and Deliverables panel in live
  mode (send a message, reload, confirm it persists; check deliverables list renders/errors correctly) — the
  browser smoke test I couldn't complete this session.
