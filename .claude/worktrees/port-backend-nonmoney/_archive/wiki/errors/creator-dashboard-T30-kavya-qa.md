# QA Review: Creator Dashboard Home Page — Task #30 (Kavya Kv1)

**Reviewer:** Kavya Patel (QA Lead)  
**Date:** 2026-07-09 (~19:55 IST)  
**Verdict:** ✅ **APPROVED** — routed to Arjun for Kabir/Meera dispatch (no new backend surface; Kabir awareness-only)  
**Scope:** Ananya Task #30 (A1) — `/creator/dashboard` rollup home page  
**Reference:** `TASK_INBOX.md` Task #30; CEO doc `wiki/tech/creator/CREATOR_CEO_INSTRUCTIONS_SWAPNIL.md` §P0-A1  
**Reviewed Files:**
- `src/pages/creator-dashboard.tsx` (new)
- `src/App.tsx` — route + `CreatorProtectedRoute`
- `src/pages/creator-login.tsx` — post-login redirect
- `src/pages/creator-onboarding.tsx` — completion redirect
- `src/components/creator/creator-layout.tsx` — logo home link
- `src/pages/creator-deals.tsx` — exported `mockDeals` for dev rollup
- `src/lib/api.ts` — `wallet.get`, `deals.list`, `contracts.listUnsigned`, `creatorDeliverables.listForDeal` (contract cross-check)

**Git state at review:** `creator-dashboard.tsx` untracked; companion redirect/route files modified but uncommitted. Code review is against working tree — Meera should verify build on the commit Arjun lands.

---

## Executive Summary

Creator dashboard **passes Kv1 QA**. Summary cards derive exclusively from existing `api.ts` clients (`wallet.get`, `deals.list`, `contracts.listUnsigned`, `creatorDeliverables.listForDeal`). No new backend endpoints or `api.ts` groups were added for this slice. Login and onboarding completion redirect to `/creator/dashboard`; layout logo returns home. Live-mode zero-deal creators get an honest dashed empty state with campaigns/profile CTAs. Loading (card skeletons + spinner) and error (destructive `Alert` + `ApiError.message`) follow the established creator-page pattern, with one minor deviation: no inline **Try again** retry button (carry-forward M-1).

Non-blocking polish: action-breakdown rows navigate to generic deals filters instead of contract/deliverable contexts (M-2); mock mode hardcodes `awaitingSignature = 0` (L-1).

---

## Task #30 Definition of Done — Verification

| DoD Item | Result | Evidence |
|----------|--------|----------|
| All data from existing shipped endpoints only | ✅ PASS | `fetchDashboardData()` L109–113: `api.wallet.get('creator')`, `api.deals.list('creator', 'all')`, `api.contracts.listUnsigned('creator')`, `loadDeliverablePendingCount()` → `api.creatorDeliverables.listForDeal(id)` per active deal. No `http.request` calls outside `api` object. |
| Honest empty states (zero-deal creator) | ✅ PASS | `isEmptyCreator = !loading && deals.length === 0` L197; dashed card L407–434 with campaigns + profile CTAs. Subtitle copy L213–214. Active/pending cards show zeros with honest helper text L281–282, L310–311. |
| Login redirect → dashboard | ✅ PASS | `creator-login.tsx` L37–38: `navigate(done ? '/creator/dashboard' : '/creator/onboarding')` |
| Onboarding completion → dashboard | ✅ PASS | `creator-onboarding.tsx` L231–233 |
| Layout home → dashboard | ✅ PASS | `creator-layout.tsx` L129, L241 logo `onClick` |
| Route registered + protected | ✅ PASS | `App.tsx` L263–268 under `CreatorProtectedRoute` |
| Zero new backend dependency | ✅ PASS | No creator-dashboard controller/service in `influora-api`. Grep confirms no new frontend HTTP paths. |
| `npm run build` PASS | ⏳ **Meera gate** | Claimed in TASK_INBOX; not re-run per Arjun routing (Meera M2 on commit) |

---

## API Client Inventory (No New Backend)

| UI metric | Client | Backend route (existing) | Notes |
|-----------|--------|--------------------------|-------|
| Available balance | `api.wallet.get('creator')` | `GET /wallet` | Mock returns demo balances when `!isApiLive()` |
| Active deal count | `api.deals.list('creator', 'all')` → `mapDealToDealsPageRow` | `GET /deals?role=creator&status=all` | Filter `contracted \| in_progress \| review` client-side |
| Unread messages (pending) | Same deals list | `unreadCount` on deal rows | Summed L116–117 |
| Awaiting signature (pending) | `api.contracts.listUnsigned('creator')` | `GET /contracts/unsigned` | `.catch(() => [])` — partial failure tolerant |
| Submittable deliverables (pending) | `api.creatorDeliverables.listForDeal(dealId)` | `GET /creator/deliverables?collaboration_id=…` | One call per active deal; per-call `.catch(() => [])` |
| Quick links | React Router `<Link>` | N/A — navigation only | deals / campaigns / wallet |

**Confirmed absent:** dashboard-specific endpoint, new `api.ts` export, direct `fetch()` / `http.request` in page.

---

## Hostile-Path Review

| Scenario | Expected | Observed | Verdict |
|----------|----------|----------|---------|
| No `creator_token` | Redirect login | `CreatorProtectedRoute` → `/creator/login` | ✅ |
| `wallet.get` throws | Error surfaced, no crash | `ApiError.message` in destructive Alert L231–236; state reset to `EMPTY_*` L174–176 | ✅ |
| `deals.list` throws | Same | Entire `fetchDashboardData` fails (Promise.all) — correct fail-fast | ✅ |
| `contracts.listUnsigned` throws | Page still loads | Silently `[]` L112 — pending count may undercount | ✅ |
| Single `listForDeal` throws | Page still loads | Per-deal `.catch(() => [])` L86 — undercount only | ✅ |
| Zero active deals | Skip deliverable fan-out | `loadDeliverablePendingCount` early return L83 | ✅ |
| Live creator, zero deals | Empty state | Dashed card + welcome subtitle | ✅ |
| Unmount during fetch | No setState leak | `cancelled` flag L162–184 | ✅ |
| Mock mode (`!isApiLive()`) | Demo rollup | `mockDeals` + mock wallet; unsigned contracts = 0 hardcoded L99 | ⚠️ L-1 |
| Error during load | Don't show stale success copy | Cards show zeros; error Alert above grid | ✅ (see L-3) |
| Rapid re-navigation | — | Single mount effect `[]` — no duplicate fetch on revisit without remount | ✅ |

---

## Loading / Error Pattern Cross-Check

| Pattern | creator-deals | creator-campaigns | creator-wallet | creator-dashboard |
|---------|---------------|-------------------|----------------|-------------------|
| Initial loading flag | `isApiLive()` | `true` | `isApiLive()` | `true` |
| Card/list skeleton | Loader2 center | Skeleton rows | Skeleton hero | Skeleton per summary card ✅ |
| Destructive Alert | ✅ | ✅ | ✅ | ✅ |
| `ApiError` instanceof | ✅ | ✅ | ✅ | ✅ |
| Inline **Try again** | ✅ L327–334 | ✅ L285–287 | ✅ Retry button | ❌ **M-1** |
| Spinner footer | Loader2 list | — | — | Loader2 L446–450 ✅ |
| `isApiLive()` mock gate | ✅ | ✅ | ✅ | ✅ |

Dashboard matches peers on Alert + skeleton + `ApiError` handling. Missing retry is the only material pattern gap.

---

## Functional Review

### Live mode data flow

1. Mount → `fetchDashboardData()` parallel wallet + deals + unsigned contracts.
2. Map deals → sum `unreadCount`; count unsigned contracts; fan-out `listForDeal` for active deal IDs.
3. Render three summary cards + optional action breakdown when `pending.total > 0`.
4. Zero deals → empty-state card; non-zero deals + zero pending → "All caught up" banner.

### Mock mode

- Uses exported `mockDeals` from `creator-deals.tsx` (documented in TASK_INBOX).
- Wallet via `api.wallet.get` mock branch.
- `awaitingSignature` forced to `0` — dev cannot preview unsigned-contract rollup without live API.

### Empty-state semantics

CEO spec: "brand-new creator with zero deals." Implementation keys on `deals.length === 0` (all statuses), not `activeDealCount === 0` — correct: a creator with only completed deals is not "brand-new empty."

---

## Code Quality Checklist

| Check | Result |
|-------|--------|
| TECH-STACK.md (Vite SPA, shadcn, motion primitives) | ✅ |
| No `console.log` / debug code | ✅ |
| Error handling (`ApiError` instanceof) | ✅ |
| TypeScript types (`DashboardData`, `PendingBreakdown`, API types) | ✅ |
| Comments explain WHY (rollup, no new backend) | ✅ L39–41, L92 comment block |
| No `transition-all` (Emil motion) | ✅ specific property transitions L332, L388 |
| Reuses `CreatorLayout`, motion `FadeUp`/`Stagger*` | ✅ |

---

## Security Review (Basic — deep review N/A)

| Check | Result | Notes |
|-------|--------|-------|
| No hardcoded secrets | ✅ | |
| No client-supplied creator id | ✅ | Identity via JWT `{ role: 'creator' }` in `api.ts` |
| Sensitive data not logged | ✅ | |
| New HTTP surface | ✅ None | Escalate to Kabir only if new endpoints added later |
| **Kabir gate** | **SKIP** | Per Arjun: zero backend dep; awareness-only |

---

## Findings

### Non-blocking (carry-forward)

| ID | Severity | Finding | Recommendation |
|----|----------|---------|----------------|
| M-1 | MEDIUM | Error Alert has no **Try again** button; peer pages (deals, campaigns, wallet) all expose retry | Extract `refetch` from `useEffect` and add `Button size="sm"` in `AlertDescription` matching `creator-deals.tsx` L327–334 |
| M-2 | MEDIUM | Action breakdown: "Awaiting signature" and "Deliverables due" both navigate to `/creator/deals?status=in_progress` L346, L361 — not contract sign or deliverable upload surfaces | Navigate to first deal with unsigned contract / submittable deliverable in `creator-chat`, or honest copy that these are counts-only |
| L-1 | LOW | Mock mode `awaitingSignature = 0` L99 — unsigned-contract pending card never demos in dev | Use `api.contracts.listUnsigned` mock path or a small mock unsigned array when `!isApiLive()` |
| L-2 | LOW | N `listForDeal` calls for N active deals — latency grows with pipeline size | Acceptable for MVP; consider batch endpoint only if perf issue observed in Meera/staging |
| L-3 | LOW | On fetch error, summary cards render ₹0 / 0 counts below error Alert (same class as wallet M-2) | Hide metric values when `error` set, or retain last successful fetch |
| L-4 | LOW | `mockDeals` imported from page module creates coupling between deals list and dashboard | Acceptable short-term per TASK_INBOX; optional later move to `lib/demo-data` |
| L-5 | LOW | No frontend unit tests for rollup helpers (`countSubmittableDeliverables`, `isActiveDeal`) | Project-wide debt — not blocking this slice |

### Blockers

None.

---

## Test Coverage

| Area | Coverage | Notes |
|------|----------|-------|
| Frontend unit tests | ❌ None | Rollup helpers are pure — cheap Vitest candidates later |
| Manual hostile-path (code review) | ✅ | All paths traced above |
| Backend tests | N/A | No backend changes |

---

## Routing

| Next gate | Owner | Action |
|-----------|-------|--------|
| Security | Kabir | **SKIP** (awareness) — no new endpoints |
| Build confirm | Meera | `npm run build` on landed commit; verify untracked `creator-dashboard.tsx` included |
| Sign-off | Priya | Dashboard slice ready for Week 4 credit after Meera PASS |

---

**Kavya sign-off:** ✅ **APPROVED** for Arjun → Kabir (skip) → Meera → Priya pipeline.
