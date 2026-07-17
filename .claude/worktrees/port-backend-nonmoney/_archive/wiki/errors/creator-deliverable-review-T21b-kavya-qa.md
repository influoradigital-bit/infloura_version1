# QA Review: Brand Deliverable Review UI — Task #21b (Kavya)

**Reviewer:** Kavya Patel (QA Lead)  
**Date:** 2026-07-09 (~17:00 IST; re-verified ~17:05 IST)  
**Verdict:** ✅ **APPROVED** — routed to Kabir (brand review UI surface + M-2 carry-forward) → Priya CTO sign-off  
**Scope:** Ananya Task #21b — brand approve/revise UI vs Vikram Task #21 API  
**Reference:** `TASK_INBOX.md` Task #21b; `wiki/tech/creator/09_CREATOR_DELIVERABLES_SPEC.md` §11.4–11.5; backend QA (`creator-deliverable-review-T21-kavya-qa.md`)  
**Reviewed Files:**
- `src/pages/brand-chat.tsx` — inline timeline + Tools panel deliverables sheet
- `src/components/brand/timeline/panels/deliverable-review-panel.tsx` — `CollaborationTimeline` review sheet
- `src/components/brand/deal-room/deal-deliverables-tab.tsx` — structured deliverables list
- `src/components/brand/deal-room/brand-deliverable-revise-modal.tsx` — revision feedback dialog
- `src/lib/api.ts` — `deliverables.approve`, `deliverables.requestRevision`, `deliverables.list`
- `src/lib/brand-deliverable-utils.ts` — `isBrandReviewableApiStatus`, `isBrandReviewableStatus`, status mappers
- `src/components/brand/timeline/event-cards/deliverable-card.tsx` — timeline card gate (cross-check)
- `src/components/brand/timeline/collaboration-timeline.tsx` — post-success overrides (cross-check)

---

## Executive Summary

Brand deliverable **review UI wiring passes QA** for the primary structured paths. Approve and request-revision call `api.deliverables.approve` / `api.deliverables.requestRevision`, matching Task #21 backend routes. Feedback is required on revise flows (`BrandDeliverableReviseModal` + `DeliverableReviewPanel`). `reviewingId` guards prevent double-submit. Post-success status refresh works in `brand-chat` (`applyReviewStatus` + `loadBrandDeliverables`) and `CollaborationTimeline` (`deliverableOverrides`).

`npm run build` **PASS** (Vite 6.4.2, **4589** modules, ~58s, exit 0). No linter diagnostics on touched files.

**High-priority finding (pre-prod, sprint non-blocking):** `brand-chat.tsx` inline timeline always renders `mockTimelineEvents` with hardcoded IDs (`del-1`, `del-2`). In live mode, Approve/Request Changes on those cards POSTs to the real API with mock IDs → expected `404`/`DELIVERABLE_NOT_FOUND`. Errors land in `reviewError` but render only inside the Deliverables **sheet**, not adjacent to the inline card — silent failure UX. **Workaround:** use the Tools panel `DealDeliverablesTab` (loads `api.deliverables.list` when available, falls back to mock deal-1 rows). **Fix before brand review prod:** wire inline timeline to live deal messages or disable inline review actions when IDs are not API-backed.

Kabir carry-forward unchanged: M-2 `TextSanitizer` on brand `feedback` before prod; M-21-1 brand-review rate limit.

---

## Build Verification

| Gate | Result | Evidence |
|------|--------|----------|
| `npm run build` | ✅ **PASS** | Vite 6.4.2, 4589 modules, built ~58s, exit 0 |
| ESLint / TS on touched files | ✅ **PASS** | No linter diagnostics |
| `console.log` in T21b deliverable path | ✅ **PASS** | None in review handlers |
| `console.log` elsewhere in `brand-chat.tsx` | ⚠️ **FINDING** | L521 shipment, L638 proposal — pre-existing, not T21b scope |

**Command run (2026-07-09, Kavya re-verify):**
```bash
npm run build
# → ✓ 4589 modules transformed; ✓ built in 58.31s
```

---

## Task #21b Definition of Done — Verification

| DoD Item | Result | Evidence |
|----------|--------|----------|
| `api.deliverables.approve` wired in brand UI | ✅ PASS | `deliverable-review-panel.tsx` L86; `brand-chat.tsx` L597 |
| `api.deliverables.requestRevision` + `{ feedback }` | ✅ PASS | `deliverable-review-panel.tsx` L131; `brand-chat.tsx` L620 |
| Gate on `SUBMITTED`/`RESUBMITTED` only | ✅ PASS | `isBrandReviewableApiStatus` in panel + card; tab uses `isBrandReviewableStatus` (see M-21b-2) |
| Feedback required on revise | ✅ PASS | Panel L115–117; modal L45–48 |
| Post-action refresh / status override | ✅ PASS | `brand-chat` `refreshAfterReview`; timeline `deliverableOverrides` |
| `isApiLive()` mock gating + gap banner | ✅ PASS | Panel L177–200; `brand-chat` L1350–1361 (`deliverablesListGap`) |
| Loading / in-flight guards | ✅ PASS | `reviewingId` disables buttons; panel `isSubmitting` |
| Error surfacing | ⚠️ **PARTIAL** | Sheet panel shows `reviewError`; inline timeline does not (H-21b-1) |
| `npm run build` PASS | ✅ PASS | Executed this review |

---

## Surface Map

| Surface | Component | API wiring | Live-ready |
|---------|-----------|------------|------------|
| Brand campaign timeline | `CollaborationTimeline` → `DeliverableReviewPanel` | ✅ `approve` / `requestRevision` | ✅ Mock event has `deliverableId` in metadata |
| Brand deal room — Tools panel | `brand-chat` → `DealDeliverablesTab` + `BrandDeliverableReviseModal` | ✅ via `handleApproveDeliverable` / `handleReviseSubmit` | ✅ Uses `api.deliverables.list` + status overrides |
| Brand deal room — inline chat cards | `brand-chat` `mockTimelineEvents` deliverable branch | ✅ calls API | ❌ Mock IDs in live mode (H-21b-1) |

---

## API Contract Cross-Check (`api.ts`)

| Frontend call | Backend route | Match |
|---------------|---------------|-------|
| `deliverables.approve(id)` | `POST /deliverables/{id}/approve` | ✅ |
| `deliverables.requestRevision(id, feedback)` | `POST /deliverables/{id}/revise` body `{ feedback }` | ✅ |
| Default auth role | `role = 'brand'` (`http.request` default) | ✅ |
| Mock path `!isLive()` | `mockOr({ status: 'APPROVED' })` / `REVISION_REQUESTED` | ✅ |
| `deliverables.list('brand', dealId)` | `GET /deals/{dealId}/deliverables` — **not built** | ✅ Honest `NOT_IMPLEMENTED` gap banner |

```979:991:src/lib/api.ts
  approve: (id: string) =>
    isLive()
      ? http.request<{ status: DeliverableStatus }>('POST', `/deliverables/${id}/approve`)
      : mockOr({ status: 'APPROVED' as DeliverableStatus }),

  requestRevision: (id: string, feedback: string) =>
    isLive()
      ? http.request<{ status: DeliverableStatus }>('POST', `/deliverables/${id}/revise`, {
          body: { feedback },
        })
      : mockOr({ status: 'REVISION_REQUESTED' as DeliverableStatus }),
```

---

## Component Review

### `brand-chat.tsx`

**Strengths:**
- `handleApproveDeliverable` / `handleReviseSubmit` — correct API calls, `ApiError` message surfacing, `reviewingId` mutex, optimistic `applyReviewStatus`, list refresh via `loadBrandDeliverables`.
- `BrandDeliverableReviseModal` wired with `isSubmitting` + `error` props.
- `DealDeliverablesTab` receives filtered items; hides pending placeholders when live list gap active.
- `isTimelineDeliverableReviewable` bridges mock timeline status + API `apiStatus` from tab items.

**Issues:**
- **H-21b-1:** Inline deliverable cards (L1105–1189) always source `mockTimelineEvents`; approve uses `event.data.id` (`del-1`, `del-2`) even when `isApiLive()`. Real deliverables only appear in Tools panel.
- **M-21b-1:** `reviewError` Alert rendered only inside deliverables sheet (L1341–1348), not in main chat scroll area — inline action failures invisible.
- **L-21b-3:** `console.log` on shipment/proposal handlers (pre-existing).

### `deliverable-review-panel.tsx`

**Strengths:**
- `canBrandReviewDeliverable` delegates to strict `isBrandReviewableApiStatus`.
- Missing `deliverableId` blocked in live mode with clear error (L77–79, L120–122).
- Feedback validation before revise (L115–117).
- `actionError` destructive Alert (L351–357).
- Gap banner when `!isApiLive()` (L189–200).

**Issues:**
- **L-21b-1:** Duplicate branches in `handleApprove` / `handleRequestRevision` — `isApiLive() && deliverableId` and `else if (deliverableId)` bodies are identical; dead abstraction.
- **L-21b-2:** Feedback `Textarea` has no `maxLength` (modal caps at 2000).
- **L-21b-4:** When `!deliverableId && !isApiLive()`, approve/revise silently closes panel with no action (edge case for malformed mock events).

### `deal-deliverables-tab.tsx`

**Strengths:**
- Presentational; clean status badges and progress copy.
- `isPending` gated by `isBrandReviewableStatus(item.apiStatus)` when `apiStatus` present.
- `reviewingId` disables action buttons during in-flight requests.
- Empty state copy is user-friendly.

**Issues:**
- **M-21b-2:** When `apiStatus` absent, `isPending` falls back to `item.status === 'pending_review'` only — looser than strict API gate for mock rows without `apiStatus`.

### `brand-deliverable-revise-modal.tsx`

**Strengths:**
- Required feedback validation with inline error (L45–48).
- `maxLength={2000}` + character counter (L91, L99).
- Resets state on close (L37–41).
- Parent API errors via `error` prop; re-throw preserves modal open on failure (L55–57).
- `disabled={isSubmitting}` on inputs and buttons.

**Minor:** On success, both parent (`handleReviseSubmit` L622) and modal (`handleSubmit` L54) close — redundant but harmless.

---

## Status Gating Review (`brand-deliverable-utils.ts`)

```5:22:src/lib/brand-deliverable-utils.ts
export function isBrandReviewableApiStatus(
  status: CreatorDeliverableRowStatus | string | undefined,
): boolean {
  if (!status) return false;
  const normalized = String(status).toUpperCase().replace(/-/g, '_');
  return normalized === 'SUBMITTED' || normalized === 'RESUBMITTED';
}

export function isBrandReviewableStatus(
  status: CreatorDeliverableRowStatus | string | undefined,
): boolean {
  if (isBrandReviewableApiStatus(status)) return true;
  if (!status) return false;
  const ui = String(status).toLowerCase();
  return ui === 'under_review' || ui === 'submitted' || ui === 'resubmitted' || ui === 'pending_review';
}
```

- **Strict gate** (`isBrandReviewableApiStatus`) used in `DeliverableReviewPanel` + `DeliverableEventCard` — aligns with backend `canReview`.
- **Loose gate** (`isBrandReviewableStatus`) used in `brand-chat` timeline bridge + `DealDeliverablesTab` — allows mock UI statuses (`pending_review`, `under_review`). Acceptable for demo/mock rows; live tab items should always carry `apiStatus` from `mapBrandListItemToTabItem`.

---

## Hostile / Edge-Case Matrix

| Scenario | Expected | Status |
|----------|----------|--------|
| Approve from `CollaborationTimeline` with `deliverableId` | API call + override to `approved` | ✅ PASS |
| Revise without feedback (modal) | Validation error, no API call | ✅ PASS |
| Revise without feedback (panel) | `feedbackError`, no API call | ✅ PASS |
| Double-click Approve | `reviewingId` blocks second call | ✅ PASS |
| API 404 / network error | User-facing message | ✅ PASS (sheet); ⚠️ inline timeline silent (H-21b-1) |
| `!isLive()` mock mode | `mockOr` returns success statuses | ✅ PASS |
| Live mode inline `del-2` approve | Backend 404 | ❌ **H-21b-1** — API called with mock ID |
| Live mode Tools panel approve (API list empty, deal-1 mock) | API call with mock `del-*` from timeline mapper | ⚠️ Same class of issue for deal-1 fallback rows |
| Missing `deliverableId` in live panel | Error banner, no API call | ✅ PASS |
| Whitespace-only feedback | Rejected client-side | ✅ PASS |
| Feedback > 2000 chars (modal) | `maxLength` enforced | ✅ PASS |
| Feedback unbounded (panel) | Sent to API | ⚠️ L-21b-2 (backend TEXT, Kabir M-2) |

---

## Code Quality Checklist

| Check | Status |
|-------|--------|
| TECH-STACK.md: `isApiLive()` mock gating | ✅ |
| shadcn/ui components from `src/components/ui` | ✅ |
| No debug code in review handlers | ✅ |
| TypeScript types on props / API responses | ✅ |
| Error handling with `ApiError` | ✅ |
| Loading / disabled states | ✅ |
| `'use client'` directives | ⚠️ Vite project — harmless noise (L-21b-5) |

---

## Findings

### H-21b-1: `brand-chat` inline timeline uses mock deliverable IDs in live mode
Inline chat cards always render `mockTimelineEvents` (L926). Approve/revise handlers pass `deliverableId` from mock data (`del-1`, `del-2`) to live API endpoints. Backend returns `DELIVERABLE_NOT_FOUND`. `reviewError` is set but only visible in the Deliverables sheet — not in the chat feed.

**Required before brand review prod:** Wire deal-room timeline to live `api.messages.list` deliverable events (or disable inline review CTAs when ID is not API-backed). Tools panel path is the interim correct surface.

### M-21b-1: Inline review errors not surfaced in chat feed
Move `reviewError` Alert above chat scroll or toast on inline action failure.

### M-21b-2: Loose vs strict reviewable status helpers
`DealDeliverablesTab` / `brand-chat` use `isBrandReviewableStatus` which accepts UI-only statuses. Ensure live rows always include `apiStatus`; consider strict gate when `isApiLive()`.

### L-21b-1: Duplicate API branches in `deliverable-review-panel`
Collapse `isApiLive() && deliverableId` / `else if (deliverableId)` into single path.

### L-21b-2: Panel feedback lacks `maxLength`
Align with modal 2000-char cap for parity with backend hygiene (Kabir M-2).

### L-21b-3: Pre-existing `console.log` in `brand-chat.tsx`
L521, L638 — remove in polish pass.

### L-21b-4: Silent no-op when mock event lacks `deliverableId`
Panel closes without feedback if `!deliverableId && !isApiLive()`.

### L-21b-5: `'use client'` in Vite components
Non-idiomatic for Vite + React Router; no runtime impact.

### Security carry-forward (Kabir)
- **M-2:** Brand `feedback` rendered to creator via `reviewNotes` — `TextSanitizer` required before prod (Task #22 shipped backend; UI still sends raw).
- **M-21-1:** No client-side rate limit on approve/revise clicks (server-side debt).

---

## QA Sign-Off

- [x] `api.deliverables.approve` / `requestRevision` wired in brand UI surfaces
- [x] Feedback validation on revise flows
- [x] Status gating on `SUBMITTED`/`RESUBMITTED` (strict path in timeline panel)
- [x] In-flight guards (`reviewingId`, `isSubmitting`)
- [x] Post-success status refresh / overrides
- [x] `isApiLive()` mock gating + honest list gap banner
- [x] `api.ts` contract alignment with Task #21 backend
- [x] `npm run build` PASS (4589 modules)
- [x] No linter errors on reviewed files
- [ ] H-21b-1 fix before brand review prod deploy — **PRE-PROD DEBT**
- [ ] Kabir UI security gate — **NEXT GATE**

**Kavya verdict: ✅ APPROVED.** Sprint gate **GO**. Route to Kabir (M-2 UI surface), then Priya CTO sign-off. H-21b-1 is **pre-prod blocker** for inline `brand-chat` timeline review — Tools panel + `CollaborationTimeline` paths are acceptable for sprint demo.

---

**Document Control:** Created 2026-07-09 by Kavya (Task #21b). Prior: `creator-deliverable-review-T21-kavya-qa.md`. Next: Kabir red-team → Priya CTO sign-off.
