# QA Review: Brand Deal Room B-1 (60% → live)
**Date:** 2026-07-11  
**Reviewer:** Kavya (QA Lead)  
**Scope:** Commits `9761f71`, `e274fe1`, `ccbff0f`  
**Status:** PASS WITH FINDINGS

---

## Summary
B-1 live-wiring for brand Deal Room messages + deliverables is structurally sound. The `isApiLive()` gating is correct, demo mode is genuinely untouched, and error-handling (load failures, send failures, stale-row clearing) follows the M-2 pattern with no silent failures. Kabir M-1 fix (server-side `kind` forcing) is confirmed in place.

Two **MEDIUM** findings relate to defensive type-safety gaps and one **LOW** finding notes a status mis-bucketing edge case that may never occur in practice but is imprecise.

---

## Findings

### MEDIUM

**1. Missing null-safety on `selectedDeal` in approve/revise handlers**  
**File:** `src/pages/brand-chat.tsx` (commit `ccbff0f`)  
**Lines:** 550, 558 (approx; in `handleApproveLive` / `handleReviseLive`)  

```typescript
const handleApproveLive = async (id: string) => {
  if (!selectedDeal) return;
  setDeliverablesError(null);
  try {
    await deliverablesApi.approve(id);
    await loadDeliverables(selectedDeal.id); // ← selectedDeal could be null here
  } catch {
    setDeliverablesError('Could not approve. Try again.');
  }
};
```

**Issue:** The early-return `if (!selectedDeal) return;` guards the first reference, but `selectedDeal` is React state and can transition to `null` between the check and the `loadDeliverables(selectedDeal.id)` call in the `try` block. The TS type is `DealListItem | null`.

**Impact:** Race condition — unlikely in normal UX (the deliverables panel is gated on `selectedDeal` being non-null), but not impossible if the user switches deals mid-approval. Would cause a runtime error (`Cannot read property 'id' of null`) rather than silently succeeding or showing the generic "Could not approve" message.

**Recommendation:** Snapshot `selectedDeal.id` at function entry:
```typescript
const handleApproveLive = async (id: string) => {
  const dealId = selectedDeal?.id;
  if (!dealId) return;
  setDeliverablesError(null);
  try {
    await deliverablesApi.approve(id);
    await loadDeliverables(dealId); // use snapshot
  } catch {
    setDeliverablesError('Could not approve. Try again.');
  }
};
```
Same pattern for `handleReviseLive`.

---

**2. Type-unsafe deliverables DTO cast**  
**File:** `src/pages/brand-chat.tsx` (commit `ccbff0f`)  
**Line:** 523 (in `loadDeliverables`)  

```typescript
const rows = (await deliverablesApi.list('brand', dealId)) as Array<Record<string, unknown>>;
```

**Issue:** The cast to `Array<Record<string, unknown>>` trusts that the backend returns an array. If the backend DTO shape changes (e.g., the envelope is mis-parsed and `deliverablesApi.list` returns the envelope object instead of `data.deliverables`), this will silently pass an object to `Array.isArray(rows)`, which returns `false`, mapping to `[]` — a silent empty-state rather than an error.

**Impact:** Low runtime risk (the API contract is stable), but the defensive `Array.isArray(rows) ? rows : []` check on line 524 suggests awareness of type drift — the cast defeats that defense.

**Recommendation:** Either:
1. Define the backend DTO shape (`type DeliverableListItem = { id: string; title: string; status: string }`) and cast to that, OR  
2. Remove the cast and let TS infer `unknown`, then guard at runtime:
   ```typescript
   const rows = await deliverablesApi.list('brand', dealId);
   if (!Array.isArray(rows)) {
     throw new Error('Unexpected response shape');
   }
   ```

---

### LOW

**3. Status mis-bucketing for `REJECTED` and `METRICS_REPORTED`**  
**File:** `src/pages/brand-chat.tsx` (commit `ccbff0f`)  
**Lines:** 526–533 (status mapping in `loadDeliverables`)  

**Backend enum values:**  
`PENDING`, `DRAFT`, `SUBMITTED`, `REVISION_REQUESTED`, `RESUBMITTED`, `APPROVED`, `REJECTED`, `POSTED`, `METRICS_REPORTED`, `VERIFIED`

**Frontend mapping:**
```typescript
const status: DealDeliverableItem['status'] =
  raw === 'APPROVED' || raw === 'VERIFIED' || raw === 'POSTED'
    ? 'approved'
    : raw === 'SUBMITTED' || raw === 'RESUBMITTED'
      ? 'pending_review'
      : raw === 'REVISION_REQUESTED'
        ? 'revision'
        : 'pending'; // ← fallback bucket
```

**Issue:**  
- `REJECTED` falls to the `'pending'` bucket (should arguably be its own state or map to `'revision'` if the UI has no distinct REJECTED card).  
- `METRICS_REPORTED` (a post-verification audit state) falls to `'pending'`, which is semantically wrong — it's past approval.

**Impact:** Brand sees a REJECTED deliverable as "pending" (misleading). `METRICS_REPORTED` is post-live, unlikely to appear in the brand Deal Room (creator-facing audit workflow), so low practical impact.

**Recommendation:** Either:
1. Add a `rejected` or `failed` status to the UI 4-value union and map `REJECTED` there, OR  
2. Document that `REJECTED` is terminal and should not appear in the brand's deliverables list (filtered server-side) — if it does appear, it's a backend contract violation.

`METRICS_REPORTED` should map to `'approved'` (it's a post-approval audit state, not pre-approval).

Suggested mapping:
```typescript
const status: DealDeliverableItem['status'] =
  raw === 'APPROVED' || raw === 'VERIFIED' || raw === 'POSTED' || raw === 'METRICS_REPORTED'
    ? 'approved'
    : raw === 'SUBMITTED' || raw === 'RESUBMITTED'
      ? 'pending_review'
    : raw === 'REVISION_REQUESTED'
      ? 'revision'
    : 'pending'; // PENDING, DRAFT, (REJECTED if not filtered)
```

---

## Verified — No Issues

### ✅ 1. `isApiLive()` gating correct
- **Messages (e274fe1):** Live mode wrapped in `isApiLive() && selectedDeal`, demo mode wrapped in `!isApiLive()`. No overlap.
- **Deliverables (ccbff0f):** Live panel gated `isApiLive() ? (loading | error | <DealDeliverablesTab />) : <DealDeliverablesTab />` (demo). Clean ternary, no bleed.

### ✅ 2. Silent-failure regressions eliminated (M-2 class)
- **Messages load:** `catch { setLiveMessages([]); setMessagesError(...) }` — stale rows cleared, error surfaced with Retry button.
- **Messages send:** `catch { setMessage(content); setMessagesError(...) }` — input preserved, error shown. No silent loss.
- **Deliverables load:** `catch { setLiveDeliverables([]); setDeliverablesError(...) }` — stale rows cleared, error surfaced with Retry button.
- **Approve/revise:** `catch { setDeliverablesError(...) }` — errors surface (though see MEDIUM-1 for null-safety gap).

All error states render an `<AlertCircle />` + message + Retry button. No silent swallowing.

### ✅ 3. Demo mode untouched
- **Messages:** The `!isApiLive()` block (lines 857–1173 in e274fe1) is the original `mockTimelineEvents.map(...)` render tree, unchanged.
- **Deliverables:** The demo-mode `<DealDeliverablesTab />` (lines 1346–1352 in ccbff0f) uses the original `deliverableItems` state, unchanged.

No edits inside the demo-mode blocks — live-wiring is additive-only.

### ✅ 4. Kabir M-1 fix confirmed
**File:** `influora-api/src/main/java/com/influora/service/DealService.java` (commit `9761f71`)  
**Line:** 438 (in `sendMessage`)

```java
// Kabir M-1: user-initiated messages must not be able to spoof privileged
// card kinds (system/payment/contract/proposal). Only 'text' is client-
// selectable here; all server-authoritative kinds are set exclusively on
// internal paths (persistProposalMessage, system notifications).
DealMessageKind kind = DealMessageKind.text;
```

The frontend `messagesApi.send(role, dealId, content)` sends only `content` (no `kind` field in the request body). The backend ignores any client-supplied kind and forces `text`. PASS.

### ✅ 5. Honest gaps intact
- **No shipment backend:** The commit messages note that shipment tracking is local-only (no `POST /deals/:id/shipment` equivalent). This is accepted scope; B-1 is "60% → live" (messages + deliverables only).
- **No feedback field on requestRevision:** `deliverablesApi.requestRevision(id, '')` sends empty feedback. Pre-existing UI gap (noted in commit message); matches demo mode's `handleRequestRevision(id, '')` signature. Not a regression.

---

## Known & Accepted (Not Failed)

1. **Browser runtime E2E deferred to Meera** — build + tsc passed; live Spring/MySQL stack not available this session.
2. **Empty feedback on revision requests** — UI has no feedback input field; backend accepts empty string. Pre-existing, matches demo.
3. **Shipment local-only** — no backend wiring for shipment; out of B-1 scope.

---

## Next Steps

**Required before merging to main:**
- **MEDIUM-1:** Fix null-safety in `handleApproveLive` / `handleReviseLive` (snapshot `dealId` at entry).
- **MEDIUM-2:** Remove unsafe DTO cast or define the shape explicitly.

**Optional (low-priority):**
- **LOW-3:** Map `METRICS_REPORTED → 'approved'`; decide on `REJECTED` (separate state or server-side filter).

**Meera verification (open):**
- Browser runtime E2E with live backend (Spring + MySQL).
- Confirm deliverables approve/revise + messages send/mark-read round-trip.

---

## Verdict

**PASS WITH FINDINGS** — no blocking issues, but two MEDIUM defensive-coding gaps should be fixed before merge. The live-wiring is production-ready once those are addressed.

**Routing:** Return to **Vikram** for MEDIUM-1 and MEDIUM-2 fixes. Re-submit when complete.
