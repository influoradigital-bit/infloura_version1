# QA Review: creator-coupons.tsx live wire — Task #32 A3 (Kavya)

**Reviewer:** Kavya Patel (QA Lead)  
**Date:** 2026-07-09 (~20:00 IST)  
**Verdict:** ✅ **APPROVED** — routed to **Meera** for `npm run build` + dev walkthrough (Kabir **SKIPPED** — backend Task #28 gated)  
**Scope:** Ananya Task #32 A3 — live `GET /creator/coupons` wire; loading/error/retry/empty; mock vs live mode; mapper correctness  
**Reference:** `TASK_INBOX.md` Task #32; `wiki/errors/creator-coupons-T28-kavya-qa.md` (backend contract); `wiki/errors/creator-coupons-A4-review.md` (prior shell QA)  
**Reviewed Files:**
- `src/lib/api.ts` — `CreatorCouponResponse`, `mapCreatorCouponResponse`, `creatorCoupons.list()`
- `src/hooks/creator/useCreatorCoupons.ts`
- `src/pages/creator-coupons.tsx`
- `src/components/creator/CreatorCampaignCard.tsx` (consumer — unchanged, verified compatible)
- `src/App.tsx` — `/creator/coupons` route (unchanged from A4)
- `src/components/creator/creator-layout.tsx` — nav entry

---

## Executive Summary

Task #32 A3 **passes QA**. Ananya correctly replaced the A4 honest-gap stub with a live `GET /creator/coupons` call. The page now follows the standard three-state fetch pattern (`loading` → `error` with retry → `empty` or list) used by `useCampaignCoupons.ts`. Mock mode still returns illustrative rows via `mockOr(MOCK_CREATOR_COUPONS)`; live mode calls the backend with `role: 'creator'` and normalizes each row through `mapCreatorCouponResponse`.

The A4 amber `notImplemented` banner is correctly removed — the endpoint ships (Task #28 APPROVED). Real API failures surface as destructive error alerts with a "Try again" button, not as gap banners.

`npm run build` **PASS** (Vite 6.4.2, 4597 modules, ~26s). No new TypeScript errors in T32 files.

---

## Task #32 Definition of Done — Verification

| DoD Item | Result | Evidence |
|----------|--------|----------|
| Live `api.creatorCoupons.list()` → `GET /creator/coupons` | ✅ PASS | `api.ts` L2510–2517: `http.request('GET', '/creator/coupons', { role: 'creator' })` |
| Loading state | ✅ PASS | `creator-coupons.tsx` L42–45: centered `Loader2` spinner while `loading` |
| Error state | ✅ PASS | `creator-coupons.tsx` L27–39: destructive `Alert` with `ApiError.message` |
| Retry | ✅ PASS | `creator-coupons.tsx` L34–36: `Button` calls `refresh()`; hook clears error + sets loading |
| Empty state | ✅ PASS | `creator-coupons.tsx` L46–47: `EmptyState` when `data.length === 0 && !error` |
| Mock mode (`VITE_API_MODE !== 'live'`) | ✅ PASS | `api.ts` L2511–2512: `mockOr(MOCK_CREATOR_COUPONS)` with 400ms delay |
| Live mode (`VITE_API_MODE=live`) | ✅ PASS | Real HTTP call; no `NOT_IMPLEMENTED` throw; no fabricated rows |
| Mapper field alignment with T28 DTO | ✅ PASS | See contract table below |
| `notImplemented` gap UI removed | ✅ PASS | Page + hook no longer expose `notImplemented` (correct post-T28) |
| Route/nav wiring | ✅ PASS | `App.tsx` `/creator/coupons` in `CreatorProtectedRoute`; sidebar "Coupons" nav |
| Kavya Kv1 | ✅ THIS DOC | |
| Kabir | ⏭️ **SKIPPED** | Backend Task #28 already gated; frontend-only wire |
| Meera build | ⏳ **NEXT** | |

---

## State-Machine Verification

### Hook (`useCreatorCoupons.ts`)

Mirrors `useCampaignCoupons.ts` shape: `{ data, loading, error, refresh }`.

| Transition | Behavior | Result |
|------------|----------|--------|
| Mount | `loading=true`, calls `api.creatorCoupons.list()` | ✅ |
| Success | `setData(result)`, `loading=false` | ✅ |
| Failure | `setError(ApiError.message \|\| generic)`, `loading=false`, `data` unchanged | ✅ |
| Retry | `setLoading(true)`, `setError(null)`, re-fetch | ✅ |

No `notImplemented` branch — correct. Live failures from 401/403/500 propagate as real errors, not gap banners.

### Page (`creator-coupons.tsx`)

| State | UI | Result |
|-------|-----|--------|
| Initial load | Spinner only | ✅ |
| Loaded + rows | `CreatorCampaignCard` list | ✅ |
| Loaded + empty | `EmptyState` ("No coupons yet") | ✅ |
| API error | Destructive alert + "Try again" | ✅ |
| Mock mode | Two illustrative cards (PRIYA20, BOAT15PRIYA), no gap banner | ✅ |

---

## API Client Review

```2508:2518:src/lib/api.ts
export const creatorCoupons = {
  /** GET /creator/coupons — creator-authed list across all campaigns. */
  list: async (): Promise<CreatorCouponResponse[]> => {
    if (!isLive()) {
      return mockOr(MOCK_CREATOR_COUPONS);
    }
    const rows = await http.request<CreatorCouponResponse[]>('GET', '/creator/coupons', {
      role: 'creator',
    });
    return (rows ?? []).map(mapCreatorCouponResponse);
  },
};
```

**Correct:**
- Path `/creator/coupons` matches `CreatorCouponController` (`context-path=/api/v1` handled by `API_BASE_URL`).
- `role: 'creator'` attaches `creator_token` from localStorage — required for `CreatorContextService`.
- `http.request` unwraps `{ success, data }` envelope — flat `List<CreatorCouponListItem>` in `data` matches T28 controller.
- `(rows ?? []).map(...)` guards null `data` → empty list, not crash.

---

## Mapper Contract Cross-Check

| Frontend `CreatorCouponResponse` | Backend `CreatorCouponListItem` | `mapCreatorCouponResponse` | Match |
|----------------------------------|--------------------------------|------------------------------|-------|
| `id` | `id` | `row.id ?? ''` | ✅ |
| `campaignId` | `campaignId` | `row.campaignId ?? ''` | ✅ |
| `campaignName` | `campaignName` | `row.campaignName ?? ''` | ✅ |
| `brandName` | `brandName` | `row.brandName ?? ''` | ✅ |
| `code` | `code` | `row.code ?? ''` | ✅ |
| `discountType: 'percentage' \| 'fixed'` | `String discountType` | `'fixed'` if exact match, else `'percentage'` | ✅ |
| `discountValue: number` | `BigDecimal` | `parseBudgetAmount(row.discountValue)` | ✅ |
| `usageLimit?: number` | `Integer` (nullable) | `row.usageLimit ?? undefined` | ✅ |
| `usageCount: number` | `int` | `row.usageCount ?? 0` | ✅ |
| `expiresAt?: string` | `Instant` (nullable) | `row.expiresAt ?? undefined` | ✅ |
| `createdAt: string` | `Instant` | `row.createdAt ?? new Date().toISOString()` | ✅ |
| `trackingUrl?: string` | `String` (nullable) | `row.trackingUrl ?? undefined` | ✅ |

`CreatorCampaignCard` consumes all mapped fields correctly: percentage vs fixed discount labels, usage limit text, conditional tracking-link block, expiry date formatting.

---

## Mock vs Live Mode

| Mode | Trigger | `creatorCoupons.list()` behavior | Page behavior |
|------|---------|----------------------------------|---------------|
| Mock | `VITE_API_MODE` unset or ≠ `live` | Returns `MOCK_CREATOR_COUPONS` after 400ms delay | Two demo cards, no error banner |
| Live | `VITE_API_MODE=live` | `GET /creator/coupons` with creator JWT | Real data, empty state, or error alert |
| Live + no token | `VITE_API_MODE=live`, no `creator_token` | 401 from backend → `ApiError` | Destructive error + retry (correct — not a gap) |
| Prod + mock misconfig | `import.meta.env.PROD` + mock mode | `MockAuthDisabledError` at auth layer | Fail-closed per TECH-STACK.md |

No silent mock data in live mode. No `NOT_IMPLEMENTED` stub remains on `creatorCoupons.list()`.

---

## Code Quality Checklist

| Check | Result |
|-------|--------|
| TECH-STACK.md — envelope client, mock/live discipline | ✅ PASS |
| No `console.log` / debug code | ✅ PASS |
| No `any` in T32 files | ✅ PASS |
| Proper error handling (`ApiError` instanceof check) | ✅ PASS |
| No hardcoded secrets | ✅ PASS |
| Comments explain contract/gap context | ✅ PASS |
| `CreatorCampaignCard` — no fabricated stats | ✅ PASS (unchanged from A4) |

---

## Build / TypeScript

| Command | Result | Notes |
|---------|--------|-------|
| `npm run build` | ✅ PASS | 4597 modules, zero errors; chunk-size warnings only (pre-existing) |
| `npx tsc --noEmit` (T32 files) | ✅ PASS | Zero errors in `api.ts`, `useCreatorCoupons.ts`, `creator-coupons.tsx`, `CreatorCampaignCard.tsx` |

---

## Findings

### Blocking

*None.*

### Low / Advisory (non-blocking)

| ID | Severity | Finding | Recommendation |
|----|----------|---------|----------------|
| L-T32-1 | LOW | `useStoreIntegration.ts` and `useAffiliateEarnings.ts` JSDoc still cite `useCreatorCoupons.ts` as the `notImplemented` convention — stale after T32. | Update comments to reference `useContentPerformance` or `useStoreIntegration` as the gap-pattern example. |
| L-T32-2 | LOW | On API error, page renders error alert **and** an empty `<div className="space-y-4">` below (list branch with zero cards). | Optional: add `!error` guard on list branch for cleaner layout. |
| L-T32-3 | INFO | `src/hooks/creator/useCreatorCoupons.ts` is **untracked** in git (`??`) while `api.ts` and `creator-coupons.tsx` are modified. | Ensure Ananya stages/commits the hook with the rest of T32 before merge. |
| L-T32-4 | LOW | `mapCreatorCouponResponse` falls back `createdAt` to `new Date().toISOString()` when absent. | Acceptable defensive default; backend always sends `createdAt` per T28 service. |
| L-T32-5 | INFO | Demo mode shows illustrative cards without a "Demo" label (carried from A4 advisory L-A4-2). | Consistent with brand tracking mock pattern; dev-only. |

---

## Security Notes (Kavya surface — Kabir skipped)

| Area | Assessment |
|------|------------|
| Auth token handling | ✅ Uses existing `http.request` with `role: 'creator'` — no token in URL/body |
| IDOR risk | ✅ N/A on frontend — no creator-id param; backend self-scopes |
| Sensitive data logging | ✅ No logging of coupon codes or tokens |
| XSS via tracking URL | ✅ Rendered in read-only `Input` + `href` with `rel="noreferrer"` — standard pattern |

Deep security review deferred to Kabir K3 batch (Task #28 backend) — **skipped per pipeline directive**; backend already APPROVED.

---

## Pipeline Routing

| Step | Owner | Status |
|------|-------|--------|
| Vikram T28 `GET /creator/coupons` | Vikram | ✅ SHIPPED + Kavya APPROVED |
| Ananya T32 live wire | Ananya | ✅ SHIPPED |
| **Kavya T32 QA** | Kavya | ✅ **APPROVED** (this doc) |
| Kabir security | Kabir | ⏭️ **SKIPPED** (backend gated) |
| Meera build + dev walkthrough | Meera | ⏳ **NEXT** |

**Meera checklist:**
1. `npm run build` — confirm green (Kavya pre-verified ✅)
2. `npm run dev` → `/creator/coupons?demo=true` — mock cards render
3. With live backend + `VITE_API_MODE=live` + creator session — confirm real coupons or honest empty state
4. Simulate API failure (stop backend) — confirm error alert + retry works
5. Stage/commit untracked `useCreatorCoupons.ts` if not already in branch

---

*Kavya Patel — QA Lead, Sage Digital.*
