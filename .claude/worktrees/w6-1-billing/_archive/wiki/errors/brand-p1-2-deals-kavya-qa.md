# QA Review — Brand P1-#2 "Deals (40% → live)" — Deal list + counter-offer wiring

**Reviewer:** Kavya (QA Lead)
**Date:** 2026-07-10
**Scope:** `src/pages/brand-chat.tsx` (live deal list fetch + counter-offer modal) + `src/components/brand/deal-room/brand-deal-counter-modal.tsx` (new)
**Security review:** Kabir PASS WITH FINDINGS (1 non-blocking LOW on idempotency-key header convention — `wiki/errors/brand-p1-2-deals-counter-kabir-redteam.md`)

---

## Status: **PASS**

All critical requirements met. No regressions. Demo mode fully preserved. Accept button correctly NOT wired (honest gap notice shown instead). TypeScript clean (all tsc errors are pre-existing test-matcher issues, unrelated to this change).

---

## Verification Results

### 1. Deal List Fetch — Loading/Error States ✅ PASS

**Requirement:** In live mode, `brand-chat.tsx` must fetch deal rooms via `api.deals.list('brand', 'all')` with proper loading/error states. Must NOT show an empty silent list on fetch failure (the exact anti-pattern caught in P0-#3 `creator-discovery.tsx`).

**Verified:**
- **Lines 511-515:** State initialized correctly — `dealRooms` starts empty in live mode, `dealsLoading` starts `true`, `dealsError` null.
- **Lines 573-591 (`fetchDeals`):** 
  - Sets `dealsLoading(true)` before fetch
  - On success: populates `dealRooms` via `remote.map(mapDealToBrandDealRoom)`
  - On failure: **explicitly sets `dealRooms([])` AND `dealsError(message)`** — both states set, no silent empty-list fallback
  - Always runs `finally` to clear loading flag
- **Lines 898-929 (sidebar render):**
  - `dealsLoading` → skeleton rows (4 placeholders)
  - `dealsError` → **destructive Alert with error message + Retry button** calling `fetchDeals()` again (lines 910-929)
  - Neither loading nor error show an empty list as if nothing's wrong — user always sees honest state
- **Lines 593-595:** Effect calls `fetchDeals()` on mount, correctly scoped to `isApiLive()` check inside the function itself

**PASS** — no silent-failure path found. Error state is a visible, actionable Alert with retry.

---

### 2. Counter-Offer Modal — Input Validation & Error Handling ✅ PASS

**Requirement:** Modal must validate amount > 0, disable during submission, surface API errors without closing early, and only close on success.

**Verified (`brand-deal-counter-modal.tsx`):**
- **Lines 47-53:** Modal resets state on every `open` (amount, message, validationError all cleared)
- **Lines 55-70 (`handleSubmit`):**
  - Client-side guard: `!amount.trim() || !Number.isFinite(parsed) || parsed <= 0` → sets `validationError`, early returns (does NOT call API)
  - On validation pass: clears `validationError`, awaits `onSubmit({amount, message})` in try-catch
  - On success: clears form fields, calls `onOpenChange(false)` to close
  - On failure (catch): does **not** call `onOpenChange(false)` — modal stays open, parent's `error` prop (line 42) surfaces the API error via `displayError` (line 72)
- **Lines 130-135:** Error display — if `validationError` OR `error` (from parent), shows destructive text with AlertCircle icon
- **Lines 147:** Submit button `disabled={isSubmitting}` — double-submit blocked once parent sets `isSubmittingCounter(true)`
- **Lines 63-66:** On success, modal clears form and closes; on throw, modal keeps open and parent re-renders with `error` prop populated

**Parent wiring (`brand-chat.tsx:764-778`):**
- Sets `isSubmittingCounter(true)` before `await api.deals.counter(...)`
- On catch: sets `counterError(...)` and **throws** (line 775) — this propagates back to modal's catch block, which does NOT close the dialog
- On success: calls `fetchDeals()` to refresh list, then clears `isSubmittingCounter`
- Modal's `error` prop is wired to `counterError` (line 1765)

**PASS** — modal correctly stays open on API failure, shows error inline, only closes on success.

---

### 3. Accept Button Gap Notice — Visible & Honest ✅ PASS

**Requirement:** Accept button must be hidden in live mode and replaced with a visible amber gap notice explaining it's not available yet (backend doesn't support brand-initiated accept — confirmed by Vikram and Kabir).

**Verified (`brand-chat.tsx` proposal cards, lines 1253-1336):**
- **Lines 1307-1332 (proposal card action buttons):**
  - Conditional render: `{!isAccepted && !isCountered && (...)}` — only shows buttons on pending proposals
  - **Lines 1309-1315:** `{isApiLive() && (... amber gap notice ...)}` — div with amber border/bg, text says "Accepting proposals from this view isn't available yet — it's coming soon. Send a counter offer to negotiate, or finalize terms via the Contract tool panel once a contract exists."
  - **Lines 1316-1330:** Button row:
    - **Lines 1317-1320:** `{!isApiLive() && (<Button>Accept</Button>)}` — Accept button ONLY shown in demo mode
    - **Lines 1321-1329:** Counter button shown in both modes, calls `openCounterDialog` when `isApiLive()`, no-op when demo
  - In live mode, the Accept button is genuinely absent (gated by `!isApiLive()`), the gap notice is genuinely present (gated by `isApiLive()`)

**PASS** — Accept is correctly unwired, gap notice is visible and actionable (directs user to Counter or Contract panel).

---

### 4. Demo Mode Preservation ✅ PASS

**Requirement:** Demo mode (`!isApiLive()`) must be completely unaffected — same buttons, same behavior as before this change.

**Verified:**
- **Lines 511-513:** `dealRooms` initialized to `mockDealRooms` when `!isApiLive()`, `dealsLoading` starts false
- **Lines 574-578 (`fetchDeals`):** Early return with `mockDealRooms` when `!isApiLive()` — no API call in demo mode
- **Lines 897-1011 (sidebar render):** When `!isApiLive()`, `dealsLoading` is false → skips skeleton, `dealsError` is null → skips error Alert, goes straight to deal list map (same as before)
- **Lines 1316-1330 (proposal card buttons):** In demo mode, both Accept and Counter buttons render (Accept is visible, Counter is visible), both are no-ops (no `onClick` handler in demo mode)
- **Lines 517-523 (selectedDeal init):** In demo mode, picks from `mockDealRooms` based on URL params or falls back to `mockDealRooms[0]` — same as before
- Counter modal in demo mode: button renders, modal opens, but parent's `handleCounterSubmit` (lines 764-778) would call `api.deals.counter(...)` which internally checks `isLive()` and returns `mockOr({ id })` without a real fetch — same mock-or pattern used everywhere

**PASS** — demo mode behavior is byte-for-byte unchanged. No conditional branches in demo mode touch the new live-mode wiring.

---

### 5. Pre-Existing Deliverables Wiring — Untouched ✅ PASS

**Requirement:** Existing deliverables live-wiring (`api.deliverables.list/approve/requestRevision`, `deliverablesListGap`) must not be regressed.

**Verified:**
- **Lines 536-548:** All deliverables state variables unchanged (brandDeliverableRows, deliverablesLoading, deliverablesListGap, reviewingId, reviewError, reviseModal)
- **Lines 672-693 (`loadBrandDeliverables`):** Function body unchanged — still calls `api.deliverables.list('brand', dealId)`, sets `deliverablesListGap(true)` on `NOT_IMPLEMENTED` error
- **Lines 717-732 (`handleApproveDeliverable`):** Unchanged — calls `api.deliverables.approve(itemId)`, sets reviewError on failure, refreshes list on success
- **Lines 739-757 (`handleReviseSubmit`):** Unchanged — calls `api.deliverables.requestRevision(itemId, feedback)`, same error handling
- **Lines 1666-1707 (Deliverables tab in tools panel):** Render logic unchanged — shows gap Alert when `deliverablesListGap`, filters items correctly, passes `onApprove`/`onRequestRevision` handlers
- **Lines 1741-1751 (BrandDeliverableReviseModal):** Modal wiring unchanged

**PASS** — no modifications found in deliverables code paths. This change only touched deal-list fetch and counter-offer modal.

---

### 6. Dead/Unrouted Duplicate Files — Correctly Untouched ✅ PASS

**Requirement:** `deal-room-dashboard.tsx` and `brand-deals.tsx` (the dead/unrouted pair) must not be touched.

**Verified via grep (not shown in this review, but confirmed independently):** Neither file appears in the current change scope. The tracker item explicitly said to wire `brand-chat.tsx` (the routed page), not the unrouted duplicates.

**PASS** — correct scope.

---

### 7. TypeScript / Build Check ⚠️ PASS (with context)

**Command run:** `npx tsc --noEmit`

**Result:** 88+ errors, all in test files (`*.test.tsx`) for vitest matchers (`toBeInTheDocument`, `toHaveAttribute`, `toHaveClass`, etc.). These are **pre-existing** — caused by missing `@testing-library/jest-dom` type imports in vitest setup, not introduced by this change.

**Files affected by this change:**
- `src/pages/brand-chat.tsx` — no tsc errors for this file
- `src/components/brand/deal-room/brand-deal-counter-modal.tsx` — no tsc errors for this file
- `src/lib/api.ts` (counter endpoint already existed) — no new errors

**Verdict:** TypeScript is clean for the changed code. The test errors are a repo-wide hygiene issue, not a blocker for this item.

**PASS** — no type errors introduced by this change.

---

## Summary of Findings

| Category | Status | Notes |
|----------|--------|-------|
| Deal list fetch (live mode) | ✅ PASS | Correct loading/error states, no silent empty-list fallback |
| Deal list fetch (demo mode) | ✅ PASS | Unchanged, still uses mockDealRooms |
| Counter modal validation | ✅ PASS | Validates amount > 0, disables during submit, keeps open on API error |
| Counter modal error display | ✅ PASS | Shows validationError OR API error inline, closes only on success |
| Accept button gap notice | ✅ PASS | Visible amber notice in live mode, Accept button hidden, demo mode shows both buttons as decoration |
| Demo mode preservation | ✅ PASS | Zero functional changes to demo mode behavior |
| Deliverables wiring | ✅ PASS | Completely untouched, no regressions |
| TypeScript | ✅ PASS | Clean for changed files, test errors are pre-existing |

---

## Recommendations

1. **Low-priority consistency note (from Kabir's review):** `api.deals.counter()` does not pass a client-generated `Idempotency-Key` header, unlike `wallet.withdraw`/`topUp` and `messages.send` which all generate one (e.g. `` `deal-counter-${dealId}-${Date.now()}` ``). The server's deterministic fallback key (`deal-counter:{dealId}:{amount}`) is DB-arbitrated and safe, so this is not exploitable — just inconsistent with this codebase's own convention for money-adjacent mutations. Suggest aligning when this endpoint is next touched, not blocking this cycle.

2. **Test coverage gap (non-blocking):** No dedicated test file exists for `brand-chat.tsx` (1782 LOC) or `brand-deal-counter-modal.tsx` (new, 155 LOC). Existing repo tests are 173/174 passing (1 unrelated failure pre-existing). Coverage for these components should be added in a follow-up cycle, not blocking this item.

---

## Final Verdict: **PASS**

- All critical requirements verified present and correct
- No regressions in demo mode or pre-existing deliverables wiring
- Error handling is robust (no silent failures, no early-close on API error)
- Accept button gap notice is honest and visible
- TypeScript clean for changed files
- 1 low-priority consistency note (idempotency-key header) flagged by Kabir, not blocking

**Ready for Meera's build/local verification.**
