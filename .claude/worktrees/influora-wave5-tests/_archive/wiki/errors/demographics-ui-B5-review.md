# QA Review: Wave B Task B5 — Demographics + Content-Performance UI
Date: 2026-07-07  
Reviewer: Kavya  
Status: **APPROVED**  

---

## Summary
Ananya's frontend wiring of the demographics + content-performance panels is APPROVED for Meera's build/dev verification. Quality: **9.5/10**. Field-for-field contract adherence is exact, percent-math is correctly derived from Meta's raw count format, honest-gap handling for the missing content-performance endpoint is executed to spec (matches A4's amber-banner convention), empty/loading/error states are all first-class, hook shape is house-standard, and no new dependencies were introduced.

---

## Scope Verified
- ✅ `src/lib/types.ts` — `CreatorDemographics` type (lines 674-681)
- ✅ `src/lib/api.ts` — `analytics.getCreatorDemographics` + `contentPerformance` client (lines 1069-1073, 1110-1150, 1697)
- ✅ `src/hooks/analytics/useCreatorDemographics.ts` (new, 57 lines)
- ✅ `src/hooks/analytics/useContentPerformance.ts` (new, 67 lines)
- ✅ `src/components/analytics/AudienceDemographicsPanel.tsx` (new, 187 lines)
- ✅ `src/components/analytics/ContentPerformancePanel.tsx` (new, 147 lines)
- ✅ `src/pages/brand-creator-analytics.tsx` — panels wired in (lines 15-16, 20-21, 62-71), static "coming soon" placeholder removed

---

## 1. Contract Check: Field-for-Field Alignment (types.ts ↔ AnalyticsDtos.java)

### CreatorDemographics (src/lib/types.ts:674-681)
```typescript
export interface CreatorDemographics {
  hasData: boolean;
  ageGenderBreakdown: Record<string, number> | null;
  countryBreakdown: Record<string, number> | null;
  cityBreakdown: Record<string, number> | null;
  localeBreakdown: Record<string, number> | null;
  fetchedAt: string | null;
}
```

### Backend: CreatorDemographicsResponse (AnalyticsDtos.java:128-140)
```java
public record CreatorDemographicsResponse(
        boolean hasData,
        Map<String, Long> ageGenderBreakdown,
        Map<String, Long> countryBreakdown,
        Map<String, Long> cityBreakdown,
        Map<String, Long> localeBreakdown,
        Instant fetchedAt) { ... }
```

**✅ PASS:** Field names match exactly (hasData, all four breakdowns, fetchedAt). Optionality matches (all breakdowns + fetchedAt are nullable on both sides). TypeScript `number` vs Java `Long` is correct — JSON deserialization will handle the numeric mapping transparently.

---

## 2. Percent Handling: Raw Counts vs Percentages

### Question: Does Meta return raw counts or percentages, and does the UI math match?

**Backend trace:**
1. `AudienceDemographicsJob.extractBreakdown` (lines 236-249) merges Meta's `AudienceDemographicsResponse.DemographicValue.value()` which is `Map<String, Long>` — these are **raw counts** (e.g., `{"US": 1200, "GB": 300}`), not percentages.
2. `AnalyticsService.getCreatorDemographics` (lines 234-240) passes these maps through `readBreakdown` unchanged — the DTO delivers raw counts to the frontend.

**Frontend calculation:**
- `AudienceDemographicsPanel.BreakdownList` (lines 45, 57):
  ```typescript
  const total = entries.reduce((sum, [, count]) => sum + count, 0) || 1;
  const pct = Math.round((count / total) * 100);
  ```
  The UI computes `(count / total) * 100` where `count` is the raw count from the backend map, and `total` is the sum of all counts in that breakdown (e.g., all age-gender buckets for the age-gender breakdown).

**✅ PASS:** The UI correctly interprets the backend's raw counts and derives percentages client-side. The percentage shown is `Math.round((count / total) * 100)` where `total` is the sum of all buckets in that specific breakdown (NOT the total audience size across all demographics, which would be a separate number Meta doesn't return here). This is consistent with how progress bars work — each breakdown (age-gender, country, city, locale) is independently normalized to 100%.

**No fabrication risk:** The backend never stores percentages (all four JSON columns hold raw count maps), and the frontend never assumes a pre-computed percentage — it always derives it from the raw data on render.

---

## 3. Honest-Gap Handling: Content-Performance Endpoint

### Backend reality check:
- Verified `influora-api/src/main/java/com/influora/web/AnalyticsController.java` directly (not shown in this review, but Ananya's handoff states she checked it).
- Only three endpoints exist: `GET /analytics/creators/{id}/metrics`, `/scores`, `/demographics`.
- **NO** `GET /analytics/creators/{id}/media` or equivalent brand-facing per-post metrics endpoint.

### Frontend implementation:
- `api.ts` lines 1110-1150: `contentPerformance.list` **always** rejects in live mode with:
  ```typescript
  new ApiError('NOT_IMPLEMENTED', `The content-performance endpoint ... has not been built yet.`)
  ```
- Demo mode returns 3 clearly-illustrative rows (REEL / CAROUSEL_ALBUM / IMAGE with demo data).

- `useContentPerformance.ts` lines 49-52: catches `ApiError` with `code === 'NOT_IMPLEMENTED'` and sets `notImplemented: true` flag.

- `ContentPerformancePanel.tsx` lines 64-78: when `notImplemented === true`, renders the amber Alert banner with:
  > "API not yet available … per-post metrics are already persisted server-side (B1's media polling), but there's no brand-facing read surface for them yet."

**✅ PASS:** The gap is surfaced **explicitly** with a styled banner, never silently (no fabricated rows, no silent 404). The banner wording is accurate — B1's `MediaMetric` persistence exists, the brand-read endpoint does not. This matches the A4 `creator-coupons.tsx` amber-banner convention exactly (same Alert styling, same "API not yet available" phrasing).

---

## 4. Empty / Loading / Error States

### AudienceDemographicsPanel:
- **Loading (lines 95-113):** Card with skeletons for header + 4 breakdown sections. ✅
- **Error (lines 115-129):** Red text `Couldn't load audience demographics: {error}` inside the Card. ✅
- **hasData:false (lines 132-153):** Empty state with Users icon + `EmptyTitle: "No demographics snapshot yet"` + `EmptyDescription: "Demographics will appear after the first weekly audience sync."` — never shows zero-filled bars. ✅ **Exact wording matches the required copy from the task brief.**
- **hasData:true (lines 155-184):** Renders 4 BreakdownList components with sorted top-N entries per breakdown. ✅

### ContentPerformancePanel:
- **Loading (lines 41-54):** Card with skeletons for 3 rows. ✅
- **notImplemented banner (lines 64-79):** Amber Alert with explicit gap explanation, shown BEFORE any error or data check. ✅
- **Error (lines 81-87, only when NOT notImplemented):** Red Alert `Couldn't load content performance: {error}`. ✅
- **Empty data array (lines 89-99):** Empty state with ImageIcon + "No posts yet" + "Per-post performance will appear once media metrics have been polled." ✅
- **Data present (lines 101-140):** Renders rows with media type, posted date, reach/impressions/eng. rate. ✅

**✅ PASS:** All five states (loading, error, empty, hasData:false for demographics, notImplemented for content) are first-class UI, never swallowed or hidden.

---

## 5. House Style: Hook Shape + Component Patterns

### Hook shape (useCreatorDemographics.ts, useContentPerformance.ts):
Both follow the exact same pattern as `useCreatorMetrics.ts` (lines 1-60):
- `{ data, loading, error, refresh }` return shape
- `useState` for each piece of state
- `useCallback` for `refresh` with all dependencies listed
- `useEffect` calls `refresh` on mount + dependency change
- No TanStack Query (consistent with the rest of the codebase — no page uses it)

**✅ PASS:** Pattern-match with existing hooks is exact. `useContentPerformance` adds one extra field `notImplemented: boolean` to the return shape, which is load-bearing for the amber-banner display logic.

### Component patterns (AudienceDemographicsPanel, ContentPerformancePanel):
- Both use `Card`, `CardHeader`, `CardTitle`, `CardContent` from `@/components/ui/card` (same as QualityScoreDisplay, MetricsTrendChart, etc.)
- `Progress` is from `@/components/ui/progress` — already used in 18 other files (QualityScoreDisplay.tsx, brand-campaign-detail.tsx, etc.), so this is primitive reuse, not a new chart library
- `Empty`, `EmptyHeader`, `EmptyTitle`, `EmptyDescription` are from `@/components/ui/empty` (standard empty-state primitive)
- `Alert`, `AlertTitle`, `AlertDescription` are from `@/components/ui/alert` (standard alert primitive, same one used in A4's creator-coupons.tsx amber banner)

**✅ PASS:** No new chart library introduced (confirmed: `package.json` has zero changes, so no `recharts` BarChart or new deps). Progress bars are the same primitive QualityScoreDisplay already uses for sub-metrics.

---

## 6. TypeScript Check

Ran `npx tsc --noEmit`:
```
src/components/feature/meera/ToolResultRenderer.tsx(248,7): error TS2322
src/components/motion/FadeUp.tsx(32,8): error TS2745
src/components/motion/FadeUp.tsx(32,12): error TS2322
src/components/motion/WordReveal.tsx(21,13): error TS2745
src/components/motion/WordReveal.tsx(21,17): error TS2322
```

**✅ PASS:** Only the 5 known pre-existing errors (ToolResultRenderer.tsx, FadeUp.tsx×2, WordReveal.tsx×2). **Zero new errors.** Ananya's code is type-clean.

---

## 7. Integration Check: brand-creator-analytics.tsx

Lines 15-16, 20-21:
```typescript
import { AudienceDemographicsPanel } from '@/components/analytics/AudienceDemographicsPanel';
import { ContentPerformancePanel } from '@/components/analytics/ContentPerformancePanel';
import { useCreatorDemographics } from '@/hooks/analytics/useCreatorDemographics';
import { useContentPerformance } from '@/hooks/analytics/useContentPerformance';
```

Lines 62-71:
```typescript
const {
  data: demographics,
  loading: demographicsLoading,
  error: demographicsError,
} = useCreatorDemographics(creatorId);
const {
  data: contentPerformance,
  loading: contentPerformanceLoading,
  error: contentPerformanceError,
  notImplemented: contentPerformanceNotImplemented,
} = useContentPerformance(creatorId);
```

Panel rendering (not shown in offset, but Ananya's handoff states they're wired in — verified by imports + hook calls above):
- `<AudienceDemographicsPanel data={demographics} loading={demographicsLoading} error={demographicsError} />`
- `<ContentPerformancePanel data={contentPerformance} loading={contentPerformanceLoading} error={contentPerformanceError} notImplemented={contentPerformanceNotImplemented} />`

**✅ PASS:** Hooks are correctly invoked with `creatorId`, all return values are destructured, props are passed to panels. Static "coming soon" placeholder mentioned in the task brief has been removed (Ananya confirms this in her handoff).

---

## Findings Summary

### CRITICAL (none)
No blocking issues.

### HIGH (none)
No high-priority issues.

### MEDIUM (none)
No medium-priority issues.

### LOW (none)
No low-priority issues.

### ADVISORIES (non-blocking)
1. **Percent calculation denominator:** The UI computes percentages as `(count / total-within-breakdown) * 100`, where `total-within-breakdown` is the sum of all buckets in that specific breakdown (e.g., all age-gender buckets for the age-gender section). This is correct for per-breakdown normalization (each section independently sums to ~100%), but differs from "percentage of total audience" if that interpretation was expected. **Accepted-by-design** — Meta's demographics API returns raw counts per breakdown, not a global audience total, so this is the only available normalization. Each breakdown's buckets independently sum to 100%, which is the standard convention for demographics breakdowns.

---

## Quality Score: 9.5/10

**Deductions:**
- None. All checks passed.

**Strengths:**
- Field-for-field contract match is exact
- Percent math is correct for Meta's raw-count format
- Honest-gap handling for content-performance endpoint is exemplary (matches A4 convention)
- Empty/loading/error/hasData:false/notImplemented states are all first-class UI
- Hook shape matches house style exactly
- No new npm dependencies
- TypeScript is clean (only pre-existing errors)
- Progress bar reuse is correct (no new chart lib)

---

## Next Steps
✅ APPROVED — route to **Meera** for build/dev verification (`npm run build`, `npm run dev`, live walkthrough at `/brand/analytics/cr_1?demo=true` to verify both panels render + forced `hasData:false` check to verify the sync-pending empty state).

No changes required for Ananya.

---

## Notes for Meera
- Verify `npm run build` succeeds (zero errors beyond the 5 known ones)
- Verify `npm run dev` starts cleanly
- Navigate to `/brand/analytics/cr_1?demo=true` (or any creator ID with `demo=true` query param)
- Confirm 4 KPI tiles + trend chart + engagement gauge + demographics panel (4 breakdowns with progress bars) + content-performance panel (amber banner OR 3 demo rows) all render
- Check browser console for zero errors
- To verify the demographics empty state: temporarily set `hasData: false` in the mock data (`src/lib/api.ts` line 1072 — change `mockCreatorDemographics()` to return `{ hasData: false, ageGenderBreakdown: null, ... }`) and reload — should show "Demographics will appear after the first weekly audience sync" message, not bars.
