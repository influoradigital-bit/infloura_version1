# QA Review: Creator Coupon Dashboard (Wave A Task A4)
Date: 2026-07-07
Reviewer: Kavya
Status: APPROVED with one advisory note

## Files Reviewed
- `src/lib/api.ts` (creatorCoupons section, lines 1134-1222)
- `src/hooks/creator/useCreatorCoupons.ts`
- `src/pages/creator-coupons.tsx`
- `src/pages/creator-affiliate-earnings.tsx`
- `src/components/creator/CreatorCampaignCard.tsx`
- `src/components/creator/AffiliateEarningsView.tsx`
- `src/App.tsx` (routing)
- `src/components/creator/creator-layout.tsx` (nav)

## QA Checks

### 1. Honest gap handling — PASS
**Verdict:** Confirmed the `useCreatorCoupons` hook genuinely surfaces the NOT_IMPLEMENTED state correctly.

**Evidence:**
- In `api.ts` lines 1188-1195: `isLive()` → throws `ApiError('NOT_IMPLEMENTED', ...)`, else returns mock data
- In `useCreatorCoupons.ts` lines 40-45: catches `ApiError` with code `'NOT_IMPLEMENTED'` and sets `notImplemented: true`
- In `creator-coupons.tsx` lines 35-46: renders amber banner with explicit "API not yet available" message when `notImplemented === true`
- Gap note in `api.ts` lines 1143-1162 documents the missing backend endpoint (`GET /creator/coupons`) and the needed follow-up for Vikram

The error is NOT silently swallowed or defaulted to an empty array. In live mode (`VITE_API_MODE=live`), the API genuinely rejects, the hook surfaces that as `notImplemented`, and the page renders an explicit, honest banner explaining the situation.

In mock/demo mode (no `.env.local`, default), the API returns illustrative rows, and no banner shows — this is correct behavior for demo mode.

### 2. Demo mode is clearly labeled — ADVISORY (not blocking)
**Verdict:** Demo data in mock mode has no visual distinction from real data.

**Observation:**
When running in mock mode (VITE_API_MODE !== 'live', the default), the two illustrative coupon cards (PRIYA20, BOAT15PRIYA) render with no badge, banner, or note indicating they are sample data. They appear as if they're real coupons.

**Why this is acceptable:**
- Mock mode is development-only (no `.env.local` with `VITE_API_MODE=live` means mock)
- The amber "API not yet available" banner appears only in LIVE mode when the endpoint genuinely fails
- This follows the same pattern as the brand-side tracking UI (A3) — mock mode shows demo data without a "demo" label; live mode shows the real state

**Advisory recommendation (not a gate):**
If Ananya wants to make demo mode more obvious for reviewers, she could add a small "Demo mode" badge in the page header when `!isApiLive()` && `import.meta.env.DEV`. But this is not a blocking issue since mock mode is dev-only.

### 3. Affiliate earnings placeholder — PASS
**Verdict:** Confirmed it's genuinely a coming-soon shell with no fabricated data.

**Evidence:**
- `AffiliateEarningsView.tsx` lines 24-46: renders a dashed-border card with "Coming soon" text and explanation
- No hooks, no API calls, no fabricated numbers
- Page correctly loads at `/creator/affiliate-earnings?demo=true`
- Verified in live dev server: shows "Coming soon" message with explanation that affiliate campaigns aren't live yet

### 4. Route/nav wiring — PASS
**Verdict:** Both routes are correctly wired and reachable.

**Evidence:**
- `src/App.tsx` lines 327-343: both routes (`/creator/coupons`, `/creator/affiliate-earnings`) wrapped in `CreatorProtectedRoute`
- `src/components/creator/creator-layout.tsx` lines 67: "Coupons" nav item with Ticket icon between Deals and Wallet
- Live verification: navigated to both `/creator/coupons?demo=true` and `/creator/affiliate-earnings?demo=true`, both pages loaded correctly

### 5. TypeScript errors — PASS
**Verdict:** Only 5 pre-existing errors, no new ones.

**Command run:** `npx tsc --noEmit`

**Output:**
```
src/components/feature/meera/ToolResultRenderer.tsx(248,7): error TS2322
src/components/motion/FadeUp.tsx(32,8): error TS2745
src/components/motion/FadeUp.tsx(32,12): error TS2322
src/components/motion/WordReveal.tsx(21,13): error TS2745
src/components/motion/WordReveal.tsx(21,17): error TS2322
```

These are the same 5 pre-existing errors in unrelated files (ToolResultRenderer, FadeUp, WordReveal) that have been present before this task. No new TypeScript errors introduced by A4.

## Code Quality Observations

### Strengths
1. **Backend gap documented thoroughly** — gap note in `api.ts` is clear, specific, and cites the missing endpoint
2. **No fabricated API contracts** — affiliate earnings is a genuine placeholder, not a fake implementation
3. **Component simplicity** — `CreatorCampaignCard` only renders what the backend actually provides (code, discount, usage, tracking link), no invented stats
4. **Error handling** — three-state model (loading, error, notImplemented) is clear and maintainable
5. **Routing clean** — both routes follow the existing `CreatorProtectedRoute` pattern

### No issues found
- No `any` types
- No console.log statements
- No hardcoded credentials
- All TypeScript props properly typed
- Components follow PascalCase naming
- Hooks follow camelCase with 'use' prefix

## Live Dev Server Verification

**Environment:** Local dev server on port 3000 (`npm run dev` via Claude Preview)

**Tests performed:**
1. ✅ Navigated to `/creator/coupons?demo=true` — page loaded, two coupon cards rendered
2. ✅ Verified coupon card content matches mock data from `api.ts` (PRIYA20, BOAT15PRIYA)
3. ✅ Confirmed "Coupons" nav item present in sidebar between Deals and Wallet
4. ✅ Navigated to `/creator/affiliate-earnings?demo=true` — coming-soon card rendered correctly
5. ✅ Console logs: no errors
6. ✅ Page title correct: "Creator OS - Brand Dashboard"

## Next Steps
APPROVED — route to Meera for final build verification (`npm run build`, `npm run dev`, `npm run test` if applicable, confirm no console errors in build output).

## Advisory Note (non-blocking)
Demo mode (mock) shows illustrative data without a visual label. This is consistent with the brand-side tracking UI (A3) and acceptable for dev-only mock mode. If Ananya wants to make it more obvious for reviewers, a "Demo mode" badge could be added when `!isApiLive() && import.meta.env.DEV`, but this is not blocking.
