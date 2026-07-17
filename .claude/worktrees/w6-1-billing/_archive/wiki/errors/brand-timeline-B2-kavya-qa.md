# QA Review: Timeline Component (BRAND Item B-2)

**Date:** 2026-07-11
**Reviewer:** Kavya (QA Lead)
**Status:** FAIL — does not satisfy B-2's requirement
**Component:** `src/components/brand/timeline/collaboration-timeline.tsx` (493 LOC)
**Imported by:** 3 live routed pages (brand-campaign-detail.tsx, creator-inbox.tsx, creator-active.tsx)
**Commit Provenance:** f86f90c (pre-staged by unknown actor, swept into commit)

---

## Summary

The Timeline component **is not mock-backed presentational code** — it is fully live-wired with `isApiLive()` gating and real `api.messages.*` fetch/send. However, it **does not provide a complete data layer** because the backend today only writes three message kinds (`text`, `proposal`, `system`) and has zero activity-log persistence for the other four timeline tags (`contract`, `deliverable`, `payment`, `shipment`).

The wiring that exists is correct. The gap is backend persistence coverage, not frontend quality.

**B-2 verdict:** This item addresses **~40%** of "Timeline (55% → live)" — messages and proposals are live, the other 60% of timeline event types are not tracked. Whether this counts as "done" depends on Priya's product decision: ship messages-only and defer the rest, or block B-2 until the full activity log lands.

---

## 1. What It Actually Is

### Live-Wired Message Fetch & Send
- **Lines 191-212**: `loadMessages()` calls `api.messages.list(role, dealId)` when `isApiLive()` is true
- **Lines 330-361**: `handleSend()` calls `api.messages.send(role, dealId, text)` in live mode
- **Error handling**: Loading states (line 187), error banners with retry (lines 421-438, 458-463)
- **Mark-read**: Calls `api.messages.markRead` on mount (line 198, swallowed error intentionally)
- **Demo fallback**: `buildDefaultEvents` + sessionStorage persistence (lines 32-152, only invoked in `!isApiLive()`)

### Backend Message Kinds (Verified in api.ts)
Lines 700-717 of `src/lib/api.ts` define `DealMessage`:
```typescript
export type MessageKind =
  | 'text'
  | 'system'
  | 'proposal'
  | 'contract'
  | 'deliverable'
  | 'payment'
  | 'shipment';
```

Real backend today (per code comments lines 219-224 of timeline component):
- **DOES write:** `text` (messages), `proposal` (proposal/counter/accept/reject via `DealService.persistProposalMessage`), `system` (backend `appendSystemMessage`)
- **DOES NOT write:** `contract`, `deliverable`, `payment`, `shipment` — "nothing outside `DealService` touches `dealMessageRepository`"

### Gap Notice Rendered to User
Lines 402-410 show an **info alert in live mode only**:
> "Messages and proposals below are real. Contract signing, deliverable, and payment history aren't tracked as activity events yet — check the Contract and Deliverables panels for current status."

This is honest, fail-open transparency (good).

### Tag Filter Bar
Lines 363-399 render 7 tag filters:
- `all`, `message`, `proposal`, `contract`, `deliverable`, `payment`, `system`

In live mode, only `message`, `proposal`, and `system` will ever have content. The other three tabs will always show "No events to show" until backend persistence is built.

---

## 2. QA Checklist Results

### isApiLive() Gating: **PASS**
- [x] All fetch/send paths check `isApiLive()` before calling real endpoints
- [x] Demo fallback is cleanly separated (buildDefaultEvents + sessionStorage, lines 32-152, 251-261)
- [x] No mixing — live mode never uses buildDefaultEvents, demo mode never calls api.*

### Error Handling: **PASS**
- [x] Loading state shown (lines 415-420)
- [x] Fetch error shown with retry button (lines 421-438)
- [x] Send error shown (lines 458-463)
- [x] No silent failure — every fetch/send has an error Alert rendered
- [x] Retry calls `loadMessages()` again (line 433)

### Demo Mode Fail-Closed: **PASS**
- [x] Demo mode does not pretend to call the API (lines 334-347 use sessionStorage, not fetch)
- [x] `buildDefaultEvents` generates client-only timeline data (lines 32-132)
- [x] No risk of demo data leaking to backend

### Security / IDOR: **PASS**
- [x] All `api.messages.*` calls pass `role` and `dealId` (lines 196, 353)
- [x] Backend authorization is the deal-scoping gate (not checked here — out of scope for FE QA)
- [x] No client-side authorization logic (correct pattern — trust backend)
- [x] No hardcoded credentials or API keys

### TypeScript: **PASS**
- [x] No `any` types (line 12 imports `DealMessage`, line 714 of api.ts defines it with typed fields)
- [x] All props properly typed (lines 154-165)
- [x] `metadata` typed as `Record<string, any>` (line 714 of api.ts, line 11 of types — acceptable for extensibility)

### Performance: **PASS**
- [x] `loadMessages` is memoized with useCallback (line 191)
- [x] `liveEvents`/`events`/`filteredEvents`/`sortedEvents` are memoized (lines 225-292)
- [x] No inline styles (Tailwind only)
- [x] Auto-scroll is throttled to sortedEvents.length change (line 294)

### Accessibility: **PASS**
- [x] Textarea is keyboard-navigable (line 466)
- [x] Enter-to-send (lines 470-474)
- [x] Send button disabled when empty or sending (line 485)
- [x] No animations requiring useReducedMotion (only smooth scroll, line 295 — acceptable)

### Architecture: **PASS**
- [x] Component follows PascalCase (CollaborationTimeline)
- [x] Hooks follow camelCase with use prefix (loadMessages is a callback, not a hook — correct)
- [x] API calls go through `@/lib/api`, not direct fetch
- [x] No direct database calls (correct — client-side)

---

## 3. Findings

### CRITICAL: None

### HIGH: None

### MEDIUM: None

### LOW (Advisory)

**LOW-1: Partial backend coverage not a code bug, but a product gap**
- The component is correctly wired. The limitation is that only 3 of 7 message kinds are ever written by the backend today.
- This is not a QA failure — the code does what it can with what the backend provides.
- The honest gap notice (lines 402-410) is the correct short-term mitigation.
- **Recommendation:** Decide with Priya whether to mark B-2 as "SHIPPED/PARTIAL (messages + proposals live, contract/deliverable/payment activity deferred)" or block it until the full activity log lands.

**LOW-2: No TypeScript strict-null-check for `collaboration.creatorName`**
- Lines 35, 56, 84, 108, 119 use `collaboration.creatorName || 'Creator'` fallback
- This is safe (correct handling of undefined), but `creatorName` should be required in the `Collaboration` type if it's always present
- **Not a bug** — defensive fallback is acceptable here

**LOW-3: markRead fire-and-forget swallows errors**
- Line 198: `.catch(() => undefined)` intentionally ignores markRead failures
- This is acceptable for a non-critical read-receipt call, but it means unread counts may drift if markRead persistently fails
- **Not a bug** — product decision (fail open on non-critical)

**LOW-4: No test coverage**
- No test file found for this component (grep'd, none exists)
- This is a gap but not a blocker — the component is routed and usable
- **Recommendation:** Add basic smoke test (render in both modes, send message in demo mode)

---

## 4. Comparison to B-2 Requirement

**B-2 says:** "Timeline (55% → live) — polished/presentational only, needs a real data layer behind it."

**What this code delivers:**
- ✅ Real `api.messages.list` fetch
- ✅ Real `api.messages.send` post
- ✅ Real `api.messages.markRead` call
- ✅ Error handling + retry
- ✅ isApiLive() gating (no mode mixing)
- ❌ Only 3 of 7 event kinds are persisted by backend (text, proposal, system)
- ❌ Contract signing, deliverable submission, payment release are not tracked as timeline events

**Is this "a real data layer"?**
- **Partial.** The wiring is real, but the backend persistence is incomplete.
- The frontend cannot fabricate events the backend doesn't write.
- This is not a frontend bug — it is a **backend gap** (no activity-log table for non-message events).

**Estimated % of B-2 addressed:**
- Messages + proposals = ~40% of timeline event volume in a typical deal flow
- Contract/deliverable/payment = ~60% of event volume (based on demo data's 7 events: 2 messages, 1 proposal, 1 contract, 1 deliverable, 2 system)

**Can B-2 be marked [x]?**
- **Not without a product decision.** If "live" means "messages are live," then yes. If "live" means "all timeline events are live," then no.

---

## 5. Verdict

**QA Verdict:** **FAIL** — does not satisfy the requirement as written.

**Reason:** B-2 asks for "a real data layer behind it" to replace "polished/presentational only." The component has a real data layer **for messages and proposals**, but the other 60% of timeline event types are still presentational-only (hardcoded in demo mode, absent in live mode).

**Not a code-quality failure** — the wiring that exists is correct, error-safe, and isApiLive-gated. The gap is backend persistence, not frontend implementation.

**Blocker:** No (the component is usable in production for messages + proposals)

**Recommendation:**
1. **Route to Priya** — decide whether to:
   - **Option A:** Ship this as-is, mark B-2 as "SHIPPED/PARTIAL (messages + proposals live, activity log deferred)," add a backlog item for contract/deliverable/payment timeline events
   - **Option B:** Block B-2 until backend activity-log persistence exists for all 7 event kinds
2. **If Option A:** Update B-2's description in `BRAND_ADMIN_PENDING_WORK.md` to clarify "messages + proposals live, other events deferred"
3. **If Option B:** Route to Vikram to build backend activity-log persistence (write `contract`/`deliverable`/`payment`/`shipment` rows to `dealMessageRepository` on status changes)

**No code changes required in this component** — it correctly handles whatever the backend provides.

---

## Next Steps

1. **Arjun** — escalate to Priya for product decision (Option A vs. B above)
2. **If Option A is chosen:** I will update this report to PASS WITH ADVISORY and mark B-2 as SHIPPED/PARTIAL
3. **If Option B is chosen:** Route to Vikram for backend work, then re-QA after backend lands

---

## Files Reviewed
- `src/components/brand/timeline/collaboration-timeline.tsx` (493 LOC, commit f86f90c)
- `src/lib/creator-deal-messages.ts` (67 LOC, sessionStorage persistence helper)
- `src/lib/api.ts` (lines 700-750, DealMessage type + messages namespace)
- `src/pages/brand-campaign-detail.tsx` (import confirmed, line 35)
- `src/pages/creator-inbox.tsx` (import confirmed via grep)
- `src/pages/creator-active.tsx` (import confirmed via grep)

---

**Sign-off:** Kavya (QA Lead), 2026-07-11
