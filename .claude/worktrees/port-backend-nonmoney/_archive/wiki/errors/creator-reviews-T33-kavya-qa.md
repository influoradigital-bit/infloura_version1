# QA Review: creator-reviews + brand-reviews pages — Task #33 A4 (Kavya)

**Reviewer:** Kavya Patel (QA Lead)  
**Date:** 2026-07-09 (~20:05 IST)  
**Verdict:** ✅ **APPROVED** — Kabir **SKIPPED** (backend Task #29 K1 complete per `wiki/errors/creator-reviews-T29-kabir-redteam.md`) → **Meera** build/dev verification  
**Scope:** Ananya Task #33 A4 — `/creator/reviews`, `/brand/reviews`, POST review wire, star+text form, loading/error/empty, honest received-list gap  
**Reference:** `TASK_INBOX.md` Task #33; `wiki/tech/creator/CREATOR_CEO_INSTRUCTIONS_SWAPNIL.md` §1.2; `TECH-STACK.md` rule §7 (no fabricated contracts); backend `ReviewDtos.java` / Task #29 V4  
**Reviewed Files:**
- `src/pages/creator-reviews.tsx`
- `src/pages/brand-reviews.tsx`
- `src/components/shared/collaboration-reviews-panel.tsx`
- `src/components/shared/review-card.tsx`
- `src/components/shared/star-rating-input.tsx`
- `src/lib/api.ts` — `creatorReviews` / `brandReviews` / `deals.list` (lines ~2521–2678, ~823–832)
- `src/App.tsx` — routes `/creator/reviews`, `/brand/reviews`

---

## Executive Summary

Task #33 A4 **passes QA** on all four verification gates requested by Arjun. Both pages share a single `CollaborationReviewsPanel` with role-aware API clients. In **live mode**, completed collaborations load via `GET /deals?status=completed` with a defensive `status === 'COMPLETED'` filter; review submission calls `POST /creator/reviews` or `POST /brand/reviews` with `collaborationId`, `stars`, and optional `text` (trimmed, max 1000). The received-reviews tab **never fabricates data in live mode** — `listReceived()` rejects with typed `NOT_IMPLEMENTED`, the panel surfaces an amber banner, and `receivedReviews` stays `[]`.

Error handling for `ALREADY_REVIEWED` and `COLLABORATION_NOT_COMPLETED` is explicit: user-friendly copy, `ALREADY_REVIEWED` also marks the deal reviewed in session state. Loading spinners, destructive error alerts with retry, and empty states are implemented on both tabs.

**`npm run build` (Vite): PASS** — clean in 20.94s; only pre-existing `tsconfig.json` duplicate `baseUrl` advisory and chunk-size warning. No new TypeScript errors in Task #33 files (project-wide `tsc` still reports pre-existing admin-test and motion errors unrelated to this slice).

**Kabir skipped** — backend write surface was red-teamed in Task #29 (`wiki/errors/creator-reviews-T29-kabir-redteam.md`: IDOR, COMPLETED gate, double-review, cross-role all CLOSED). Frontend does not introduce new auth or data-exfil paths.

---

## Task #33 Definition of Done — Verification

| DoD Item | Result | Evidence |
|----------|--------|----------|
| `/creator/reviews` ships | ✅ PASS | `App.tsx` L371–376 inside `CreatorProtectedRoute` |
| `/brand/reviews` ships | ✅ PASS | `App.tsx` L234–239 inside `BrandLayoutWrapper` → `ProtectedRoute` |
| POST review wire (live) | ✅ PASS | `api.creatorReviews.create` / `api.brandReviews.create` → `POST /{role}/reviews` with correct JWT role |
| Star + optional text form | ✅ PASS | `StarRatingInput` + `Textarea` maxLength 1000; client requires `stars >= 1` before submit |
| COMPLETED-only UX | ✅ PASS | `loadRateableDeals` → `deals.list(role, 'completed')` + `.filter(status === 'COMPLETED')`; backend re-gates on POST |
| `ALREADY_REVIEWED` handling | ✅ PASS | `collaboration-reviews-panel.tsx` L153–155: message + `reviewedIds` update |
| `COLLABORATION_NOT_COMPLETED` handling | ✅ PASS | L156–157: dedicated user message |
| Received tab honest gap (live) | ✅ PASS | `listReceived` rejects `NOT_IMPLEMENTED`; amber banner; no review cards rendered |
| Loading / error / empty states | ✅ PASS | Both tabs: `Loader2`, destructive `Alert` + retry, `EmptyRateState` / `EmptyReceivedState` |
| No fabricated live received reviews | ✅ PASS | Live path never populates `receivedReviews`; mock rows gated behind `!isLive()` only |
| Build passes | ✅ PASS | `npm run build` exit 0 (Kavya env) |
| Kavya QA | ✅ THIS DOC | |
| Kabir | ⏭️ **SKIPPED** | Backend K1 done (T29) |
| Meera | ⏳ **NEXT** | Build + dev route smoke |

---

## Gate 1: COMPLETED-Only UX

```36:54:src/components/shared/collaboration-reviews-panel.tsx
async function loadRateableDeals(role: Role): Promise<RateableDeal[]> {
  if (!isApiLive()) {
    return [ /* illustrative demo row */ ];
  }

  const rows = await api.deals.list(role, 'completed');
  return rows
    .filter((deal) => deal.status === 'COMPLETED')
    .map((deal: Deal) => ({
      id: deal.id,
      counterpartyName: deal.counterpartyName,
      campaignName: deal.campaignName,
    }));
}
```

**Defense in depth:** UI queries `status=completed` filter **and** re-filters uppercase `'COMPLETED'` (matches `CollaborationStatus` in `types.ts`). Non-completed deals cannot appear in the rate list. If a deal transitions or backend state drifts, POST still returns `409 COLLABORATION_NOT_COMPLETED` with surfaced copy — no silent success.

**Mock mode:** Hardcoded `deal-done-1` row only when `!isApiLive()` — dev/demo only, consistent with established `api.ts` mock discipline.

---

## Gate 2: Error Handling — `ALREADY_REVIEWED` / `COLLABORATION_NOT_COMPLETED`

```133:164:src/components/shared/collaboration-reviews-panel.tsx
  const handleSubmit = async (deal: RateableDeal) => {
    // ... stars validation ...
    try {
      await reviewsClient.create({
        collaborationId: deal.id,
        stars: draft.stars,
        text: draft.text.trim() || undefined,
      });
      setReviewedIds((prev) => new Set(prev).add(deal.id));
      setSubmitSuccessId(deal.id);
    } catch (err) {
      if (err instanceof ApiError && err.code === 'ALREADY_REVIEWED') {
        setReviewedIds((prev) => new Set(prev).add(deal.id));
        setSubmitError('You have already reviewed this collaboration.');
      } else if (err instanceof ApiError && err.code === 'COLLABORATION_NOT_COMPLETED') {
        setSubmitError('Reviews are only allowed after the collaboration is completed.');
      } else {
        setSubmitError(err instanceof ApiError ? err.message : 'Could not submit review.');
      }
    }
  };
```

**HTTP client propagation:** `HttpClient.request` maps `envelope.error.code` → `ApiError.code` (`api.ts` L174–179), so backend stable codes reach the panel unchanged.

| Code | UI behavior | User copy |
|------|-------------|-----------|
| `ALREADY_REVIEWED` | Deal removed from pending list; destructive alert | "You have already reviewed this collaboration." |
| `COLLABORATION_NOT_COMPLETED` | Alert only; deal stays visible | "Reviews are only allowed after the collaboration is completed." |
| Other `ApiError` | Alert with server message | `err.message` |
| Non-API throw | Generic fallback | "Could not submit review." |

**Client validation:** Submit blocked when `draft.stars < 1` with inline message — aligns with backend `@Min(1) @Max(5)`.

---

## Gate 3: No Fabricated Received Reviews (Live Mode)

```2626:2634:src/lib/api.ts
  listReceived: (): Promise<ReviewDisplayRecord[]> =>
    isLive()
      ? Promise.reject(
          new ApiError(
            'NOT_IMPLEMENTED',
            'The creator received-reviews endpoint (GET /creator/reviews/received) has not been built yet.',
          ),
        )
      : mockOr(MOCK_CREATOR_RECEIVED_REVIEWS),
```

**Panel handling (`refreshReceived`):**
1. Catches `NOT_IMPLEMENTED` → `setReceivedNotImplemented(true)`, `setReceivedReviews([])`
2. Amber banner cites exact missing endpoint `GET /{role}/reviews/received`
3. Render guard: review cards only when `receivedReviews.length > 0`; empty state suppressed when `receivedNotImplemented` — **banner only, no fake rows**

Brand path mirrors creator (`brandReviews.listReceived`).

**Demo/mock mode:** `MOCK_CREATOR_RECEIVED_REVIEWS` / `MOCK_BRAND_RECEIVED_REVIEWS` return illustrative rows; banner hidden (correct — endpoint would succeed in mock). Banner copy explicitly states "illustrative data in demo mode only."

---

## Gate 4: Build & TypeScript

| Check | Result | Notes |
|-------|--------|-------|
| `npm run build` | ✅ PASS | Exit 0; 4597 modules; 20.94s |
| Pre-existing advisories | INFO | Duplicate `baseUrl` in `tsconfig.json`; chunk > 500 kB |
| Task #33 file TS errors | ✅ NONE | No errors in reviewed page/component files |
| `console.log` / debug | ✅ NONE | Grep clean on review files |
| `any` in new code | ✅ NONE | Props and state fully typed |

---

## API Contract Cross-Check (POST)

| Field | Frontend (`CreateReviewPayload`) | Backend (`CreateReviewRequest`) | Match |
|-------|----------------------------------|----------------------------------|-------|
| `collaborationId` | string, required | `@NotBlank String` | ✅ |
| `stars` | number, client ≥ 1 | `@NotNull @Min(1) @Max(5)` | ✅ |
| `text` | optional, trimmed, max 1000 UI | `@Size(max = 1000) String` optional | ✅ |

Response type `ReviewRecord` mirrors `ReviewResponse` (id, collaborationId, reviewerType, reviewerUserId, stars, text, createdAt).

**Role routing:** `creatorReviews.create` sends `role: 'creator'`; `brandReviews.create` sends `role: 'brand'` — matches `CreatorReviewController` / `BrandReviewController` context gates.

---

## UI / UX States Matrix

| State | Rate tab | Received tab |
|-------|----------|--------------|
| Loading | Centered `Loader2` | Centered `Loader2` |
| Error | Destructive alert + "Try again" → `refreshDeals` | Destructive alert + "Try again" → `refreshReceived` |
| Empty (no data) | `EmptyRateState` | `EmptyReceivedState` (live, when endpoint exists) |
| NOT_IMPLEMENTED | N/A | Amber banner only; no cards |
| Success | Per-card green confirmation | N/A (write-only slice) |
| Submitting | Per-card disabled form + spinner button | N/A |

**Accessibility:** `StarRatingInput` uses `role="radiogroup"`, `role="radio"`, `aria-checked`, `aria-label` per star, `sr-only` label; `useReducedMotion()` disables hover scale. `ReviewCard` uses `aria-label` on star display and semantic `<time dateTime>`.

---

## TECH-STACK.md Compliance

| Rule | Result |
|------|--------|
| Vite + React Router (not Next.js) | ✅ Page components + `App.tsx` routes |
| Reuse shadcn/ui primitives | ✅ Alert, Button, Card, Tabs, Textarea, Label |
| `useReducedMotion()` on animation | ✅ `star-rating-input.tsx` |
| No fabricated backend contracts (rule §7) | ✅ `NOT_IMPLEMENTED` fail-closed for received list |
| Typed API client (`api.ts`) | ✅ `ReviewRecord`, `CreateReviewPayload`, `ApiError` |
| No secrets in client | ✅ |
| No `console.log` | ✅ |

---

## Findings Register

| ID | Severity | Finding | Action |
|----|----------|---------|--------|
| L-T33-1 | P3 | No sidebar/nav link to Reviews in `creator-layout.tsx` or brand layout — routes exist but not discoverable from nav | Optional follow-up; deep-link `/creator/reviews`, `/brand/reviews` works |
| L-T33-2 | P3 | Already-reviewed deals reappear after full page reload — `reviewedIds` is session-only; no GET "pending reviews" or "my reviews" endpoint to pre-filter | Acceptable for V4 write-only slice; resolves on submit via `ALREADY_REVIEWED` |
| L-T33-3 | P3 | `submitError` is global across all rate cards — error from one deal shows above all cards | Minor UX; non-blocking |
| L-T33-4 | INFO | `creatorReviews.flag` / `brandReviews.flag` wired in `api.ts` but no UI in this slice | Out of Task #33 scope (flagging is separate surface) |
| L-T33-5 | INFO | Demo mode received tab shows mock reviews without "demo" badge | Consistent with A4 creator-coupons pattern — dev-only |
| L-T33-6 | INFO | `isApiLive()` in panel vs internal `isLive()` in `api.ts` — same `API_MODE === 'live'` check, different export names | Cosmetic consistency only |

**No P0 or P1 blockers.**

---

## Meera Build Brief

Arjun: route Meera with this checklist:

1. **`npm run build`** — confirm clean (Kavya: PASS).
2. **`npm run dev`** — smoke both routes with `?demo=true`:
   - `/creator/reviews?demo=true` — Rate tab shows demo deal; Received tab shows mock review cards (no amber banner).
   - `/brand/reviews?demo=true` — same pattern for brand role.
3. **Live mode** (`VITE_API_MODE=live` in `.env.local`) if credentials available:
   - Received tab → amber "Read API not yet available" banner, **zero** review cards.
   - Rate tab → loads from `GET /deals?status=completed` (empty OK).
   - Submit review on a completed collab → success state; duplicate submit → `ALREADY_REVIEWED` message.
4. **No new `tsc` errors** in Task #33 files (project-wide debt unchanged).

---

## Pipeline Routing

```
Ananya T33 A4 ──✅ Kavya APPROVED──► Kabir SKIPPED (T29 K1 done) ──► Meera build/dev smoke ──► Priya sign-off
```

**Next owner:** Meera  
**Kabir:** Skipped per pipeline instruction — backend security gate satisfied by Task #29 red-team  
**Unblocks:** Priya Week 4 CREATOR sign-off on reviews UI slice

---

*Kavya Patel, QA Lead — Sage Digital*
