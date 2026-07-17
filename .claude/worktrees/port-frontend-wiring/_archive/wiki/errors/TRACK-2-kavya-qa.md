# QA Review: TRACK-2 Campaign Tracking UI Clarity Labels
Date: 2026-07-13
Reviewer: Kavya Reddy (QA Lead)
Status: **CONDITIONAL PASS** → Route to Meera for local verification

---

## Summary

Ananya's changes implement Priya's ruling (tracking-subsystem-ruling.md Q2+Q3) by clarifying that coupons pay commission while tracking links only report clicks. Copy accuracy is correct, TypeScript is clean, and tests pass 2/2. **However, the entire page is UNREACHABLE in production** — no route exists in `App.tsx` — which is CRITICAL severity.

---

## Files Reviewed

1. `src/lib/api.ts` — type definition update
2. `src/components/creator/CreatorCampaignCard.tsx` — UI labels & fallback logic
3. `src/pages/creator-coupons.test.tsx` — test suite
4. `src/App.tsx` — routing configuration (CRITICAL ISSUE FOUND)

---

## QA Checklist Results

### ✅ PASS — Copy Accuracy
**Lines 90-92 (CreatorCampaignCard.tsx):**
```tsx
<p className="mt-1.5 text-xs font-medium text-emerald-800">
  💰 Earns you commission on each sale
</p>
```
Correctly states coupons pay commission (matches Priya Q2 ruling: coupons are redemption-gated, payment-linked).

**Lines 116-118:**
```tsx
<p className="mt-1.5 text-xs font-medium text-blue-700">
  📊 Tracks clicks &amp; attribution (does not pay commission)
</p>
```
Correctly states tracking links do NOT pay (matches Priya Q2 ruling: tracking links are reporting-only).

**Lines 119-122:**
```tsx
<p className="mt-1 flex items-start gap-1 text-xs font-medium text-amber-800">
  <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
  <span>Share this exact link — clicks are only tracked through it.</span>
</p>
```
Warning present and accurate — reinforces that the tracking URL must be shared verbatim.

### ✅ PASS — TypeScript Standards
**Line 10 (CreatorCampaignCard.tsx):**
```tsx
import type { CreatorCouponResponse } from '@/lib/api';
```
No `any` types. New optional field properly typed:

**Lines 1839-1840 (api.ts):**
```tsx
redirectUrl?: string | null;
```
Optional field added to existing interface, backward-compatible.

### ✅ PASS — Component Reuse
Uses existing shadcn/ui components: `Card`, `Badge`, `Button`, `Label`, `Input` — no new dependencies.

### ⚠️ CONDITIONAL — Fallback Logic (Interim State)
**Line 59 (CreatorCampaignCard.tsx):**
```tsx
const shareUrl = coupon.redirectUrl ?? coupon.trackingUrl;
```

**Analysis:**
- **Until backend ships `redirectUrl` (TRACK-3):** this falls back to the raw `trackingUrl` (brand site URL with UTM params). The label says "📊 Tracks clicks" but the URL is NOT a `/track/click/{id}` redirect yet — it's just the brand site URL.
- **User impact:** Creator shares `trackingUrl` thinking it tracks clicks → clicks are NOT counted because the URL skips the redirect endpoint.
- **Mitigation:** The warning "Share this exact link" is present, but the interim state MISLEADS creators into sharing a link that doesn't do what the UI claims.

**Verdict:** This is safe to ship ONLY IF:
1. The backend (TRACK-3) ships `redirectUrl` BEFORE any creators actually use this page, OR
2. The component hides the "SHARE THIS LINK" block entirely until `redirectUrl` is populated (add `{coupon.redirectUrl && ...}` guard on line 96).

**Recommendation:** Ananya should add the conditional render guard so the link block only appears when `redirectUrl` is actually returned:
```tsx
{coupon.redirectUrl && (
  <div className="mb-4">
    <Label className="text-xs text-muted-foreground">SHARE THIS LINK</Label>
    ...
  </div>
)}
```
This prevents creators from seeing a tracking link that doesn't track.

### ✅ PASS — Color Contrast (WCAG AA)
- `text-emerald-800` on white: ~7.5:1 (PASS AA for small text)
- `text-blue-700` on white: ~4.6:1 (PASS AA for small text)
- `text-amber-800` on white: ~5.8:1 (PASS AA for small text)

All labels meet WCAG AA minimum (4.5:1 for normal text).

### ✅ PASS — Test Coverage
**Test suite: `creator-coupons.test.tsx`**
```
✓ 2 passed (2 total)
```
Both tests green (header render + mock data display).

---

## 🚨 CRITICAL ISSUE — Unreachable Page

**Finding:**
```bash
# App.tsx routing check
grep -n "creator/coupons" src/App.tsx
# → NO RESULTS

grep -n "CreatorCouponsPage" src/App.tsx
# → NO RESULTS
```

**Impact:**
The entire `CreatorCouponsPage` has **NO ROUTE** in `App.tsx`. Creators cannot navigate to `/creator/coupons` — it 404s or gets swallowed by the `/:handle` catch-all route (line 397).

**What this breaks:**
- Creators cannot view the coupon codes that **earn them money**.
- The page tested in `creator-coupons.test.tsx` exists in code but is dead in the live app.
- This is a tier-1 user-facing feature gap — coupons are the affiliate payment model (tracking-subsystem-ruling.md).

**Fix Required (block delivery):**
Add to `App.tsx` after line 302 (inside creator protected routes):
```tsx
<Route
  path="/creator/coupons"
  element={
    <CreatorProtectedRoute>
      <CreatorCouponsPage />
    </CreatorProtectedRoute>
  }
/>
```

Also add import at top:
```tsx
import CreatorCouponsPage from '@/pages/creator-coupons';
```

**Severity:** CRITICAL — this is not a typo or style issue, it's a missing feature gate. No creator can reach this page until routed.

---

## No Regressions
Existing components/hooks unchanged. Test suite passes 2/2.

---

## VERDICT: **CONDITIONAL PASS**

### Pass Criteria Met:
✅ Copy accurately reflects Priya's coupon vs. link ruling
✅ TypeScript clean (no `any`, proper optional field)
✅ WCAG AA contrast on all new labels
✅ Tests pass 2/2

### Conditional Gate (choose ONE before Meera verifies):

**Option A (safe):** Ananya adds conditional render guard so tracking link only shows when `redirectUrl` is populated:
```tsx
{coupon.redirectUrl && ( /* entire SHARE THIS LINK block */ )}
```

**Option B (risky):** Ship as-is and rely on TRACK-3 backend landing before creators use the page. **NOT RECOMMENDED** — opens a window where the UI lies to users.

### CRITICAL BLOCKER (must fix):
❌ **Route missing in `App.tsx`** — page is unreachable. Ananya must:
1. Import `CreatorCouponsPage` in `App.tsx` (line 2-51 imports section)
2. Add route after line 302 (creator protected routes)

---

## Next Steps

1. **BLOCKER FIX:** Ananya adds route to `App.tsx` (see fix above) — re-submit for QA review.
2. **CONDITIONAL FIX (choose one):**
   - Ananya adds `{coupon.redirectUrl && ...}` guard (safe), OR
   - Swapnil/Priya approve shipping without guard (risky — relies on TRACK-3 timing).
3. **After fixes:** Meera runs `npm run build` + `npm run dev`, navigates to `/creator/coupons?demo=true`, confirms page loads and labels render.

---

## Files to Fix

| File | Issue | Fix |
|------|-------|-----|
| `src/App.tsx` | No `/creator/coupons` route | Add import + `<Route path="/creator/coupons" element={...} />` |
| `src/components/creator/CreatorCampaignCard.tsx` | Tracking link shows even when non-functional (optional) | Wrap "SHARE THIS LINK" block in `{coupon.redirectUrl && ...}` |

---

**QA Status:** **CONDITIONAL** — route to Meera ONLY after:
1. CRITICAL route fix is applied, AND
2. Conditional guard decision is made (add guard OR accept interim risk).

— Kavya Reddy, QA Lead
