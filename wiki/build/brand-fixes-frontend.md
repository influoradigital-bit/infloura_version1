# Brand Surface Audit — Frontend Fixes (Ananya)

Source: `wiki/reports/brand-feature-audit.md`. Backend counterpart:
`wiki/build/brand-fixes-backend.md` (Vikram — **not yet written** as of this
pass; the two items below that depend on it are typed against documented
expectations and flagged for reconciliation once it lands).

`npx tsc --noEmit` — **PASS** (exit 0, no errors).

---

## Round 2 — Priya contract-reconciliation loop-back + Kabir F2 (2026-07-22)

Reviews: `wiki/build/brand-fixes-priya-review.md`, `wiki/build/brand-fixes-kabir-review.md`.
`npx tsc --noEmit` — **PASS** (exit 0, no errors) after these changes.

### #4 — BLOCKING null-handling fix (Priya)

Root cause: `AnalyticsDtos.ContentPerformanceResponse` is
`@JsonInclude(NON_NULL)`, so nullable `reach`/`impressions`/`engagementRate`
are **omitted** from the JSON (arrive as `undefined`), never sent as `null`.
FE typed `reach`/`impressions` as required `number` and guarded the rate with
`!== null` — `undefined !== null` is `true`, so the guard never caught the
omitted case, rendering the literal text `"undefined"` / `"undefined%"` on
any post Meta didn't report reach for. Applied Priya's exact 3-point fix:

- `src/lib/api.ts` — `ContentPerformanceItem.reach` / `.impressions` retyped
  `number | null` (were required `number`); doc comment explains the
  NON_NULL-omission wire behavior.
- `src/components/analytics/ContentPerformancePanel.tsx` — `formatCompact`
  signature widened to `number | null | undefined`, returns `'—'` on
  `n == null` (catches both explicit null and omitted/undefined) instead of
  `String(n)` producing `"undefined"`.
- `src/components/analytics/ContentPerformancePanel.tsx` — engagement-rate
  guard changed from `item.engagementRate !== null` to
  `item.engagementRate != null` (loose), so the omitted/`undefined` case now
  correctly falls to `'—'` instead of rendering `"undefined%"`.

### #3 — Kabir F2 render-safety (confirmed, no code defect; added a doc note)

Kabir's F2: `SafetyCheck.detail` is model-generated free text (Kabir F1:
`rationale`/`overall_rationale` are NOT re-redacted on the return path), so a
hostile creator caption could in principle steer its wording — the render
path must be plain text, never `dangerouslySetInnerHTML`.

Verified: `DeliverableSafetyReviewCard.tsx` passes `check.detail` only as a
`title` attribute on `Badge` (`src/components/ui/badge.tsx` spreads
`...props` onto a plain `<span>`) — React sets this as a literal DOM
attribute (native tooltip), never parsed or executed as HTML. Grepped this
component and its render tree for `dangerouslySetInnerHTML` — zero matches.
**No code defect; already safe.** Added an inline comment at the render site
recording why, so a future edit doesn't casually swap it for an HTML-parsing
render.

### Minor (non-blocking, Priya nit) — mock check ids

`src/lib/api.ts`'s demo-mode mock (`deliverables.getSafetyReview`) used 3
illustrative check ids (`disclosure`/`brand_mention`/`garm_risk`) that don't
match Vikram's real service. Replaced with the actual fixed 10-category GARM
set (`app/tools/schemas.py::GARM_CATEGORIES`, labels mirrored from
`DeliverableSafetyReviewService.buildCategoryLabels`) so demo mode isn't
misleading. Zero live impact (`!isLive()` path only).

Also updated the `DeliverableSafetyReview`/`getSafetyReview` doc comments in
`src/lib/api.ts` — they previously said the contract was an unconfirmed
"documented expectation"; Priya's reconciliation has since confirmed it
field-for-field against `DeliverableSafetyDtos.java`, and Kabir's red-team
pass cleared all 4 invariants (info-barrier, IDOR, injection, advisory-only).

### Files touched (round 2)

- `src/lib/api.ts`
- `src/components/analytics/ContentPerformancePanel.tsx`
- `src/components/brand/deliverables/DeliverableSafetyReviewCard.tsx`

---

## PARTIAL #5 — mount the GARM badge on the brand page

Stale premise removed: `BrandSafetyScoreService` is built and
`api.analytics.getCreatorScores` already carries `brandSafetyScore`/`garmFlags`.
Mounted the existing `BrandSafetyBadge` the same way `creator-analytics.tsx`
mounts it for the creator self-view.

Files touched:
- `src/pages/brand-creator-analytics.tsx` — imported and mounted
  `BrandSafetyBadge` in the Scores grid (now 3-up, matching
  `creator-analytics.tsx`'s layout); rewrote the file-header comment that
  claimed the badge was "intentionally not built."
- `src/components/analytics/BrandSafetyBadge.tsx` — rewrote the stale "Known
  backend gap" doc comment (was lines ~74-91). Verified against
  `influora-api/.../service/scoring/BrandSafetyScoreService.java`:
  `writeGarmFlagsJson` now writes a flat above-floor-category JSON string
  array, matching what `JsonLists.stringListFromJson` (read side) and
  `AnalyticsDtos.CreatorScoresResponse.garmFlags` (`List<String>`) expect —
  the parse-mismatch the old comment described is fixed. No component logic
  changed; it already rendered `garmFlags` defensively as plain string tags,
  which is the correct rendering for a flat array.

No FE type changes needed — `CreatorScores.brandSafetyScore` / `garmFlags`
(`src/lib/types.ts`) already had the right shape.

---

## Fix #4 — content-performance hook

Confirmed `useContentPerformance.ts` + `api.ts`'s `contentPerformance.list`
(around the `GET /analytics/creators/{creatorId}/media` call) and fixed the
stale hook doc, which claimed the call "always rejects with a typed
`ApiError('NOT_IMPLEMENTED', ...)`" — false today: `contentPerformance.list`
makes a real `http.request` call in live mode with no such short-circuit.
The backend route itself isn't live yet (`AnalyticsController` only exposes
`/metrics`, `/scores`, `/demographics`), so until Vikram ships it the call
404s; `http.request` doesn't map that to `ApiError('NOT_IMPLEMENTED', ...)`
(a 404 with no JSON envelope fails JSON parsing and surfaces as
`ApiError('NETWORK_ERROR', ...)` instead), so the hook's `notImplemented`
branch is currently dead code, not wrong code — it will start working the
moment the backend returns a real `NOT_IMPLEMENTED` envelope, or become moot
once the route ships for real.

Files touched:
- `src/hooks/analytics/useContentPerformance.ts` — rewrote the stale doc
  comment (was lines 1-13) to describe actual behavior instead of the
  fictional always-NOT_IMPLEMENTED rejection.
- `src/lib/api.ts` — annotated `ContentPerformanceItem` with what
  `ContentPerformancePanel` actually consumes from each field (mediaType/
  postedAt for the row header; reach/impressions/engagementRate for the
  three stat columns) as the FE's required minimum shape, and flagged it for
  reconciliation against `wiki/build/brand-fixes-backend.md` once written.

**Pending backend (typed as optional / flagged, not yet confirmed):**
`ContentPerformanceItem` (`mediaId`, `mediaType`, `postedAt`, `reach`,
`impressions`, `engagementRate`) is all currently required — none marked
optional, since these are exactly the fields `ContentPerformancePanel`
renders unconditionally today. If Vikram's real DTO is missing or renames
any of the six, the panel needs a follow-up change; extra fields are
additive and safe.

---

## Fix #3 — deliverable-level brand-safety review (new build)

Backend DTO not finalized (`wiki/build/brand-fixes-backend.md` not written
yet), so this was built against a documented-expectation shape with only the
guaranteed core required, everything else optional — see the doc comment on
`DeliverableSafetyReview` in `src/lib/api.ts`.

Files touched:
- `src/lib/api.ts` — added `DeliverableSafetyVerdict`,
  `DeliverableSafetyCheckStatus`, `DeliverableSafetyCheck`,
  `DeliverableSafetyReview` types, and
  `deliverables.getSafetyReview(id)` hitting the **expected**
  `GET /deliverables/:id/safety-review` (route not yet confirmed against a
  real controller).
- `src/hooks/brand/useDeliverableSafetyReview.ts` — new hook. Advisory-only
  contract: any fetch failure (route not live, real error, or classifier not
  yet run) degrades silently to `review: null` — no error state, so a
  not-yet-shipped backend never puts a scary banner in front of
  approve/reject.
- `src/components/brand/deliverables/DeliverableSafetyReviewCard.tsx` — new
  component. Collapsible card, overall-verdict chip in the header (click to
  expand pass/fail/warning check chips), text+icon on every chip (not
  color-only), shadcn `text-destructive-foreground`/`text-success`/
  `text-warning` token family — matches its mount site
  (`DeliverableViewer.tsx`), not the `meera-*` family (that's scoped to the
  Meera chat surface only, confirmed by grepping `meera-` usage across
  `src/components/feature/meera/*`). Renders `null` when there's no review
  data, so it takes zero space when the backend endpoint isn't live.
  Follows the `CompliancePreCheck.tsx` advisory idiom described in
  `wiki/ai-review/meera-label-to-moat-build-plan.md` section 3.1 (collapsible
  pass/fail chips, advisory only, never blocks the primary action).
- `src/components/brand/deliverables/DeliverableViewer.tsx` — imported the
  hook + card, called the hook unconditionally at the top (same pattern as
  `useDeliverableDetail`), mounted the card between "Previous Feedback" and
  the Approve/Request Changes action row. The card is purely additive to the
  DOM — `deliverable.canApprove`/`canRequestRevision` and the button
  handlers are completely untouched, so the review can never gate those
  actions.

**Pending backend (typed as optional, marked in `src/lib/api.ts`):**
`DeliverableSafetyReview.score` (number | null) and `.computedAt`
(string | null) are optional — plausible additions a GARM-style classifier
would return but not rendered by the FE today. `DeliverableSafetyCheck.detail`
is optional (used as a tooltip `title` on the chip if present). Required core:
`overallVerdict: 'PASS' | 'REVIEW' | 'FAIL'` and
`checks: DeliverableSafetyCheck[]` (each `{ id, label, status }`). Route path
`GET /deliverables/:id/safety-review` is also unconfirmed — reconcile once
`wiki/build/brand-fixes-backend.md` exists.

---

## PARTIAL 1 (audit) — mount `ContentPerformancePanel` on brand creator-analytics (2026-07-22)

Ruling: `wiki/build/partials-resolution-plan.md` PARTIAL 1 (Priya). All four
links in the chain (backend route, `api.contentPerformance.list`,
`useContentPerformance`, `ContentPerformancePanel`) already existed — only
the UI mount was missing. Single-file change, exact scope, no adapter.

Files touched:
- `src/pages/brand-creator-analytics.tsx` — added imports for
  `ContentPerformancePanel` (`@/components/analytics/ContentPerformancePanel`)
  and `useContentPerformance` (`@/hooks/analytics/useContentPerformance`);
  called `useContentPerformance(creatorId)` right after the existing
  `useCreatorScores` call, destructuring `data: content`, `loading:
  contentLoading`, `error: contentError`, `notImplemented:
  contentNotImplemented`; mounted `<ContentPerformancePanel>` between the
  Scores grid (3-up: FakeFollowerIndicator/QualityScoreDisplay/
  BrandSafetyBadge) and the "Audience demographics — coming soon" block.
  Props map 1:1 to the hook return (`data`/`loading`/`error`/
  `notImplemented`), no reshaping.

`npx tsc --noEmit` — **PASS** (exit 0, no errors).

---

## Full file list

- `src/pages/brand-creator-analytics.tsx`
- `src/components/analytics/BrandSafetyBadge.tsx`
- `src/components/analytics/ContentPerformancePanel.tsx`
- `src/hooks/analytics/useContentPerformance.ts`
- `src/lib/api.ts`
- `src/hooks/brand/useDeliverableSafetyReview.ts` (new)
- `src/components/brand/deliverables/DeliverableSafetyReviewCard.tsx` (new)
- `src/components/brand/deliverables/DeliverableViewer.tsx`
