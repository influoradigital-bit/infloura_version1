# Influora Admin — Dashboard API Audit

**Task:** Check every API the admin Dashboard calls — working status per code  
**Done When:** Dashboard total audit — information added to `adminF.md`  
**Branch:** `fix/brand-audit-remediation` · **Date:** 2026-08-09  
**Sources read:** `AdminDashboardController.java` · `AdminDashboardService.java` · `api-contracts.ts` · `usePulseData.ts` · `useOperationsSummary.ts` · `PulseDashboard.tsx`

---

## 1 · APIs the Dashboard Actually Calls

The dashboard page (`/admin`) mounts `PulseDashboard.tsx`, which calls **exactly 2 hooks**. Both are live-wired to real endpoints.

| # | Hook | API Called | HTTP Endpoint | Status |
|---|------|-----------|---------------|--------|
| 1 | `usePulseData` | `dashboardApi.getPulse()` | `GET /api/v1/admin/dashboard/pulse` | ✅ **LIVE** |
| 2 | `useOperationsSummary` | `dashboardApi.getOperationsSummary()` | `GET /api/v1/admin/dashboard/operations` | ✅ **LIVE** |

Two more functions exist in `dashboardApi` but are **NOT called by the dashboard component**:

| # | Function | Endpoint | Status |
|---|----------|----------|--------|
| 3 | `getFinancialSummary(period)` | `GET /api/v1/admin/dashboard/financial?period=` | ⚠️ Backend exists — not wired to dashboard UI |
| 4 | `getMarketingSummary()` | *(no network call)* | ❌ Returns `unavailable()` — not built |

---

## 2 · API 1 — `GET /api/v1/admin/dashboard/pulse`

**Backend:** `AdminDashboardController.pulse()` → `AdminDashboardService.pulse()` → `AdminDashboardStatsCache.pulseStats()`  
**Frontend:** `usePulseData.ts` → `PulseDashboard.tsx` (CEO Pulse KPI grid + Red Flags)  
**Auth gate:** All three roles allowed (`SUPER_ADMIN`, `ADMIN`, `SUPPORT`) — MFA required for SUPER_ADMIN/ADMIN

### Field-by-field status

| Field | What renders | Data source | Real or placeholder |
|-------|-------------|-------------|---------------------|
| `gmv` | GMV KPI card value | `EscrowHoldRepository.sumAmountByStatusIn` (interim proxy) | ✅ Real (proxy — see note) |
| `gmvChange` | WoW % change on GMV card | No snapshot table exists | ⚠️ Always `null` → renders "—" |
| `revenue` | Revenue KPI card value | `WalletTransactionRepository` — platform-fee-ledger formula (Rohan sign-off) | ✅ Real |
| `revenueChange` | WoW % change on Revenue card | No snapshot table | ⚠️ Always `null` → renders "—" |
| `activeCampaigns` | Active Campaigns KPI value | `CampaignRepository.countByStatus(ACTIVE)` | ✅ Real |
| `activeCampaignsChange` | WoW % change on Campaigns card | No snapshot table | ⚠️ Always `null` → renders "—" |
| `escrowFloat` | Escrow Float KPI value | `EscrowHoldRepository` — FUNDED sum | ✅ Real |
| `supportQueueDepth` | Support Queue Depth KPI | `SupportTicketRepository` | ✅ Real |
| `mauBrands` | Brand MAU KPI | `UserRepository.countByUserTypeAndLastLoginAtAfter` (login-recency proxy) | ✅ Real (proxy) |
| `mauCreators` | Creator MAU KPI | Same — login-recency proxy | ✅ Real (proxy) |
| `redFlags` | Red Flags panel | See table below | Partial — only 1 of 5 types implemented |

### Red Flags — which types are implemented

| Flag type | Implemented | Source |
|-----------|-------------|--------|
| `SUPPORT_AGING` | ✅ **Yes** | Tickets open >48h — real query |
| `ESCROW_LOW` | ❌ No | Needs business threshold — not defined |
| `SLA_BREACH` | ❌ No | Needs SLA definition — not defined |
| `PAYOUT_DELAY` | ❌ No | Needs business threshold — not defined |
| `REVIEW_BACKLOG` | ❌ No | Needs threshold — not defined |

> **Note on WoW nulls:** `gmvChange`/`revenueChange`/`activeCampaignsChange` return `null` because no `kpi_daily_snapshot` table or snapshot-job exists yet. The frontend correctly renders "—" for `null` (not "0") — this is an honest gap, not a bug. Fix requires: new migration table + scheduled job (Meera owns DB migrations).

> **Note on GMV proxy:** GMV is computed as the sum of all escrow holds (FUNDED status), which is an interim proxy for real transaction volume. It is labelled as such in the service javadoc.

---

## 3 · API 2 — `GET /api/v1/admin/dashboard/operations`

**Backend:** `AdminDashboardController.operations()` → `AdminDashboardService.operations()`  
**Frontend:** `useOperationsSummary.ts` → `PulseDashboard.tsx` (Operations KPI row)  
**Auth gate:** All three roles — same as pulse

### Field-by-field status

| Field | What renders | Data source | Real or placeholder |
|-------|-------------|-------------|---------------------|
| `activeCampaigns` | Active Campaigns ops KPI | `CampaignRepository.countByStatus(ACTIVE)` | ✅ Real |
| `campaignsAtRisk` | Campaigns At Risk KPI | Hardcoded `0` | ⚠️ Always 0 — "at risk" SLA threshold not defined (product decision pending, Rohan/product owner) |
| `reviewBacklog` | Review Backlog KPI | `pendingKyc + pendingApplications + pendingContentFlags(PENDING+ESCALATED)` — M-28 fix | ✅ Real (3-source count) |
| `supportQueueDepth` | Support Queue Depth ops KPI | `SupportTicketRepository.countByStatusIn(OPEN/IN_PROGRESS/WAITING_USER)` | ✅ Real |
| `avgReviewTime` | Avg Review Time KPI (hours) | Mean resolution time of tickets resolved in last 30 days — M-28 fix | ✅ Real |

> **Note on `campaignsAtRisk = 0`:** This is an explicit honest placeholder, not a bug. The service javadoc records it as needing a product-defined "at risk" threshold before a real query can be written. The value is honest-0 (not fabricated positive data).

> **Note on reviewBacklog (M-28 fix):** Was previously hardcoded `0`. Now sums: brand workspaces with `PENDING` verification + creator profiles with `PENDING` application status + content flags in `PENDING` or `ESCALATED` state. Three real queries, one return value.

> **Note on avgReviewTime (M-28 fix):** Was previously hardcoded `0.0`. Now computes actual mean hours between `createdAt` and `resolvedAt` for tickets resolved in the last 30 days. Returns `0.0` only if no tickets were resolved in that window (no fabrication).

---

## 4 · API 3 — `GET /api/v1/admin/dashboard/financial` (NOT called by dashboard)

**Backend:** `AdminDashboardController.financial()` → `AdminRevenueService.getFinancialSummary()`  
**Frontend:** `dashboardApi.getFinancialSummary(period)` — **defined but not wired to any dashboard hook**  
**Who calls it:** Used by the Revenue page (`/admin/revenue`), not the Dashboard  

| Status | Detail |
|--------|--------|
| ✅ Backend live | Controller endpoint exists, delegates to `AdminRevenueService` |
| ⚠️ Not on Dashboard | No hook on `/admin` calls this — it surfaces on the Revenue section |

---

## 5 · API 4 — `getMarketingSummary()` (NOT built)

**Frontend:** Returns `unavailable<>('marketing analytics dashboard — not built (marketing metrics service pending)')` — **no HTTP call is ever issued**  
**Backend:** No `getMarketingSummary` handler exists in `AdminDashboardController`  

| Status | Detail |
|--------|--------|
| ❌ Not built | `unavailable()` wrapper — zero network requests, UI receives graceful error message |
| Blocks | Acquisition metrics, growth funnel full shape, reputation score not available on dashboard |

---

## 6 · Summary Table — All Dashboard APIs

| API | Endpoint | Backend exists | FE wired | Data real | Notes |
|-----|----------|---------------|----------|-----------|-------|
| `getPulse` | `GET /dashboard/pulse` | ✅ Yes | ✅ Yes | Mostly — 7/10 fields real | 3 WoW change fields = `null` (no snapshot table); 4 of 5 red-flag types not implemented |
| `getOperationsSummary` | `GET /dashboard/operations` | ✅ Yes | ✅ Yes | Mostly — 4/5 fields real | `campaignsAtRisk` = honest 0, awaiting SLA threshold from product |
| `getFinancialSummary` | `GET /dashboard/financial` | ✅ Yes | ⚠️ Not from Dashboard | Real | Called by Revenue page, not the Dashboard component |
| `getMarketingSummary` | *(none)* | ❌ No | ❌ No | N/A | `unavailable()` stub — no data, no endpoint |

---

## 7 · Gaps & What's Needed to Close Them

| Gap | Owner | What's needed |
|-----|-------|--------------|
| WoW change deltas (`gmvChange`, `revenueChange`, `activeCampaignsChange`) always `null` | Meera (DB) | New `kpi_daily_snapshot` table migration + scheduled snapshot job |
| `campaignsAtRisk` always `0` | Rohan/Product | Define "at risk" SLA threshold → Vikram writes query |
| Red flags: only `SUPPORT_AGING` fires | Rohan/Product | Define thresholds for `ESCROW_LOW`, `SLA_BREACH`, `PAYOUT_DELAY`, `REVIEW_BACKLOG` |
| `getMarketingSummary` not built | Marketing/Backend | No data source (no ad-spend tracking, no Referral table, no signup-source attribution) — Tier B/C epic |
| GMV is a proxy (escrow holds) | Vikram | Replace with real GMV from completed transaction ledger once order/transaction table ships |
| MAU is a login-recency proxy | Vikram | Replace with session/event-tracking table once it exists |

---

## 8 · Auth & Caching

| Item | Detail |
|------|--------|
| Role gate | Both `/pulse` and `/operations` allow `SUPER_ADMIN`, `ADMIN`, `SUPPORT` — all three tiers |
| MFA gate | `requireRoleWithMfaSatisfied()` is called on every request — MFA session required for SUPER_ADMIN/ADMIN |
| Cache | `pulse()` delegates to `AdminDashboardStatsCache.pulseStats()` — Redis-backed P1 cache (cache hit skips DB queries; role/MFA check runs uncached on every call before the cache is consulted) |
| Token auth | FE sends `Authorization: Bearer <admin_token>` from `localStorage.admin_token` on every request |

---

*Sourced from live code — not types. Files: `AdminDashboardController.java` · `AdminDashboardService.java` · `api-contracts.ts` · `usePulseData.ts` · `useOperationsSummary.ts` · `PulseDashboard.tsx` — branch `fix/brand-audit-remediation` · 2026-08-09*

---
---

# Influora Admin — Users API Audit

**Task:** Check every API the admin Users section calls — working status per code  
**Done When:** Users total audit — appended to `adminF.md` — Priya to verify  
**Branch:** `fix/brand-audit-remediation` · **Date:** 2026-08-09  
**Sources read:** `useBrandList.ts` · `useCreatorList.ts` · `useBrandDetail.ts` · `useCreatorDetail.ts` · `useCreatorApplications.ts` · `BrandProfile.tsx` · `api-contracts.ts` · `AdminBrandController.java` · `AdminCreatorController.java`

---

## 9 · Users Section — APIs Overview

The Users page (`/admin/users`) drives 3 tabs and 2 profile sub-pages. **5 hooks** are in play; **16 endpoints total** — 7 brand, 9 creator.

| # | Hook | API function | HTTP endpoint | Status |
|---|------|-------------|---------------|--------|
| 1 | `useBrandList` | `brandApi.list()` | `GET /api/v1/admin/brands` | ✅ **LIVE** |
| 2 | `useBrandDetail` | `brandApi.getById(id)` | `GET /api/v1/admin/brands/{id}` | ✅ **LIVE** |
| 3 | `useCreatorList` | `creatorApi.list()` | `GET /api/v1/admin/creators` | ✅ **LIVE** |
| 4 | `useCreatorDetail` | `creatorApi.getById(id)` | `GET /api/v1/admin/creators/{id}` | ✅ **LIVE** |
| 5 | `useCreatorApplications` | `creatorApi.getPendingApplications()` | `GET /api/v1/admin/creators/applications/pending` | ✅ **LIVE** |

All 5 read hooks are live-wired. No mock data. No `unavailable()` stubs in the read path.

---

## 10 · Brand APIs — All 7 Endpoints

**Backend:** `AdminBrandController.java` · **API base:** `/api/v1/admin/brands`  
**Auth gate:** `AdminContextService.requireRoleWithMfaSatisfied()` on every endpoint — server-side, not UI

### 10a · Read endpoints

| # | FE function | HTTP | Backend method | FE hook | Status |
|---|-------------|------|----------------|---------|--------|
| B-1 | `brandApi.list(params)` | `GET /admin/brands` | `AdminBrandController.list()` | `useBrandList.ts` | ✅ **LIVE** |
| B-2 | `brandApi.getById(id)` | `GET /admin/brands/{id}` | `AdminBrandController.getById()` | `useBrandDetail.ts` | ✅ **LIVE** |

**`useBrandList` — query parameters sent vs. supported:**

| Filter | FE sends it? | BE accepts it? | Notes |
|--------|-------------|----------------|-------|
| `search` | ✅ Yes | ✅ Yes | Debounced 350ms before firing |
| `kycStatus` | ✅ Yes | ✅ Yes | `PENDING / APPROVED / REJECTED` |
| `isSuspended` | ✅ Yes | ✅ Yes | Boolean toggle |
| `page` | ✅ Yes | ✅ Yes | **1-indexed** (BE defaults to `1`; service converts to 0-indexed Spring Data page internally) |
| `pageSize` | ✅ Yes | ✅ Yes | Default 20 |
| `industry` | ❌ Not sent | ❌ No BE param | Field exists in `BrandFilters` type — no rendered UI control, no BE support |
| `size` | ❌ Not sent | ❌ No BE param | Same — type-only, no rendered control, no BE support |

**`useBrandDetail` behaviour:**
- Uses `AbortController` — cancels in-flight request on component unmount (no dangling state)
- `refresh()` increments a `reloadKey` state → re-fires `getById` after any mutation

### 10b · Mutation endpoints

| # | FE function | HTTP | Backend method | Caller | Money path | Status |
|---|-------------|------|----------------|--------|-----------|--------|
| B-3 | `brandApi.update(id, data)` | `PUT /admin/brands/{id}` | `AdminBrandController.update()` | `BrandProfile.tsx` | No | ✅ **LIVE** |
| B-4 | `brandApi.verifyKyc(action)` | `POST /admin/brands/{id}/verify-kyc` | `AdminBrandController.verifyKyc()` | `BrandProfile.tsx` | No | ✅ **LIVE** |
| B-5 | `brandApi.suspend(id, reason)` | `POST /admin/brands/{id}/suspend` | `AdminBrandController.suspend()` | `BrandProfile.tsx` | No | ✅ **LIVE** |
| B-6 | `brandApi.reinstate(id, reason)` | `POST /admin/brands/{id}/reinstate` | `AdminBrandController.reinstate()` | `BrandProfile.tsx` | No | ✅ **LIVE** |
| B-7 | `brandApi.overrideBudget(id, campaignId, newBudget, reason)` | `POST /admin/brands/{id}/campaigns/{campaignId}/budget-override` | `AdminBrandController.overrideBudget()` | `BrandProfile.tsx` | **YES** | ✅ **LIVE** |

**Mutation detail:**

| Endpoint | Allow-listed fields / inputs | Reason required | Audit logged |
|----------|------------------------------|-----------------|--------------|
| `PUT /admin/brands/{id}` | `name`, `industry`, `size`, `email` only — backend ignores extra fields | No | Yes |
| `POST …/verify-kyc` | `action` (`APPROVE`/`REJECT`) + `reason` (`VerifyKycRequest`; `brandId` field present in body but server-side ignores it — path variable is the only trusted ID) | ✅ Mandatory | Yes |
| `POST …/suspend` | `reason` | ✅ Mandatory | Yes |
| `POST …/reinstate` | `reason` | ✅ Mandatory | Yes |
| `POST …/budget-override` | `newBudget` (INR, `@NotNull @Positive` — zero/negative rejected), `reason` (`@Size(min = 10, max = 500)`) | ✅ Mandatory (≥10 chars) | Yes |

> ⚠️ **Budget override is a money path.** `BrandProfile.tsx` uses an inline form Card whose "Review Budget Override" button opens a **single** controlled `AlertDialog` (`BrandProfile.tsx:628`) before calling `overrideBudget`. One confirm dialog, not two.
>
> **Where the ≥10-char rule actually lives:** it is bean validation on the `BudgetOverrideRequest` record (`AdminBrandDtos.java:148` — `@NotBlank @Size(min = 10, max = 500)`), triggered by `@Valid` on the controller parameter — **not** imperative code inside `AdminBrandController.overrideBudget()`. A short reason is rejected with `400` before the service is reached. The FE **also** mirrors the rule (`overrideBudgetValid` at `BrandProfile.tsx:339` keeps the button disabled below 10 chars), so the server rule is a backstop, not the only gate.
>
> **Additional server-side money guards** (in `AdminBrandService.overrideCampaignBudget`, not visible in the DTO): a sane upper bound; rejection of >2 fractional digits (`campaigns.budget_max` is `DECIMAL(12,2)` — Kabir L-4); and a **committed-spend floor** computed over FUNDED/RELEASED escrow holds plus agreed rates on contracted collaborations (Kabir M-2 / L-3). A new budget below money already committed is rejected.
>
> This is a **SUPER_ADMIN-only** endpoint (`AdminBrandService.java:484`); ADMIN is rejected (see §14).

> ⚠️ **B-7 void-typing asymmetry:** `brandApi.overrideBudget` is typed as returning `void` on the FE (`api-contracts.ts`, `apiRequest<void>`), but the controller returns `ResponseEntity.ok(Map.of("success", true))` (`AdminBrandController.java:111`) — same pattern as `forceInstagramReauth` (C-7). The `success` field is silently discarded on the FE side.

> ⚠️ **B-7 has no UI role gate.** The budget-override Card in `BrandProfile.tsx` is rendered for **any** admin who can view the brand profile — there is no `SUPER_ADMIN` check anywhere in the component (grep for `role`/`SUPER_ADMIN` returns only a comment and the team-member `Badge`). An `ADMIN` sees the form, fills it in, confirms the dialog, and only then receives a `403` from `AdminBrandService:484`. Security is intact (server is the authority); the **UX is misleading**. Tracked in §13.

---

## 11 · Creator APIs — All 9 Endpoints

**Backend:** `AdminCreatorController.java` · **API base:** `/api/v1/admin/creators`

### 11a · Read endpoints

| # | FE function | HTTP | Backend method | FE hook | Status |
|---|-------------|------|----------------|---------|--------|
| C-1 | `creatorApi.list(params)` | `GET /admin/creators` | `AdminCreatorController.list()` | `useCreatorList.ts` | ✅ **LIVE** |
| C-2 | `creatorApi.getPendingApplications(page, pageSize)` | `GET /admin/creators/applications/pending` | `AdminCreatorController.getPendingApplications()` | `useCreatorApplications.ts` | ✅ **LIVE** |
| C-3 | `creatorApi.getById(id)` | `GET /admin/creators/{id}` | `AdminCreatorController.getById()` | `useCreatorDetail.ts` | ✅ **LIVE** |

**`useCreatorList` — query parameters sent vs. supported:**

| Filter | FE sends it? | BE accepts it? | Notes |
|--------|-------------|----------------|-------|
| `search` | ✅ Yes | ✅ Yes | Debounced 350ms |
| `applicationStatus` | ✅ Yes | ✅ Yes | `PENDING / APPROVED / REJECTED` |
| `suspended` | ✅ Yes | ✅ Yes | FE type field is `isSuspended` (`CreatorFilters`); `api-contracts.ts` translates it to the wire param `suspended` before sending — translation is handled, no runtime mismatch |
| `page` | ✅ Yes | ✅ Yes | **1-indexed** — same as brand list, but **unclamped** (see defect note below) |
| `pageSize` | ✅ Yes | ✅ Yes | Default 20 |
| `instagramVerified` | ❌ Not sent | ❌ No BE param | Type-only — no BE support |
| `niche` | ❌ Not sent | ❌ No BE param | Type-only — no BE support |
| `tier` | ❌ Not sent | ❌ No BE param | Type-only — no BE support |
| `minFollowers` | ❌ Not sent | ❌ No BE param | Type-only — no BE support |
| `maxFollowers` | ❌ Not sent | ❌ No BE param | Type-only — no BE support |

> 🐞 **Page-clamp asymmetry (creator list — real defect, not a doc nit).** Both list endpoints default `page` to `1` and convert to a 0-indexed Spring Data page, but only the brand side is defensive:
> - `AdminBrandService.java:176` — `int pageIndex = Math.max(0, page - 1);` → `?page=0` is clamped and returns page 1.
> - `AdminCreatorService.java:167` — `PageRequest.of(page - 1, pageSize, …)` with **no clamp** → `?page=0` calls `PageRequest.of(-1, …)`, which throws `IllegalArgumentException` → **`500`, not `400`**.
>
> The FE never sends `page=0` (all three hooks initialise `useState(1)` and `api-contracts.ts` defaults `page = 1`), so this is unreachable through the admin UI today — but it is a hand-crafted-request / future-caller 500. It also propagates to **C-2**, since `listPendingApplications()` delegates straight to `list()`. Fix: mirror the brand `Math.max(0, page - 1)` clamp. Tracked in §13.

**`useCreatorApplications` — query parameters:**

| Filter | FE sends it? | BE accepts it? | Notes |
|--------|-------------|----------------|-------|
| `page` | ✅ Yes | ✅ Yes | |
| `pageSize` | ✅ Yes | ✅ Yes | |
| *(filter fields)* | ❌ None | ❌ None | Pending applications list has no filter controls — raw paginated list only |

**`useCreatorDetail` behaviour:**
- Uses `AbortController` — same cancel-on-unmount pattern as `useBrandDetail`
- `refresh()` → `reloadKey` increment → re-fires `getById` after mutations

### 11b · Mutation endpoints

| # | FE function | HTTP | Backend method | Caller | Money path | Status |
|---|-------------|------|----------------|--------|-----------|--------|
| C-4 | `creatorApi.update(id, data)` | `PUT /admin/creators/{id}` | `AdminCreatorController.update()` | `CreatorProfile.tsx` | No | ✅ **LIVE** |
| C-5 | `creatorApi.adjustTier(adjustment)` | `PUT /admin/creators/{id}/tier` | `AdminCreatorController.adjustTier()` | `CreatorProfile.tsx` | No | ✅ **LIVE** |
| C-6 | `creatorApi.reviewApplication(action)` | `POST /admin/creators/{id}/review-application` | `AdminCreatorController.reviewApplication()` | `CreatorProfile.tsx` | No | ✅ **LIVE** |
| C-7 | `creatorApi.forceInstagramReauth(id)` | `POST /admin/creators/{id}/instagram/force-reauth` | `AdminCreatorController.forceInstagramReauth()` | `CreatorProfile.tsx` | No | ✅ **LIVE** |
| C-8 | `creatorApi.suspend(id, reason)` | `POST /admin/creators/{id}/suspend` | `AdminCreatorController.suspend()` | `CreatorProfile.tsx` | No | ✅ **LIVE** |
| C-9 | `creatorApi.reinstate(id, reason)` | `POST /admin/creators/{id}/reinstate` | `AdminCreatorController.reinstate()` | `CreatorProfile.tsx` | No | ✅ **LIVE** |

**Mutation detail:**

| Endpoint | Allow-listed fields / inputs | Reason required | Audit logged |
|----------|------------------------------|-----------------|--------------|
| `PUT /admin/creators/{id}` | `name`, `niche` only — other fields silently ignored by Jackson | No | Yes |
| `PUT /admin/creators/{id}/tier` | `AdjustTierRequest` = `creatorId` (present in body, **ignored server-side** — path variable is the only trusted ID) + `newTier` (`NANO/MICRO/MID/MACRO`, validated against the `CreatorTier` enum) + `newQualityScore` (`Double`, accepted but **never persisted** — no-op, see note) + `reason` (`@Size(min = 10, max = 500)`) | ✅ Mandatory (≥10 chars) | Yes |
| `POST …/review-application` | `ReviewApplicationRequest` = `creatorId` (present in body, **ignored server-side** — same discipline as `VerifyKycRequest.brandId`) + `action` (`APPROVE`/`REJECT`) + `reason` (`@NotBlank @Size(max = 2000)`). **No `qualityScore` on this DTO** — the FE type's optional `qualityScore` would be dropped by Jackson, and `CreatorProfile.tsx` never sends it anyway | ✅ Mandatory | Yes |
| `POST …/instagram/force-reauth` | No body required | No | Yes |
| `POST …/suspend` | `reason` | ✅ Mandatory | Yes |
| `POST …/reinstate` | `reason` | ✅ Mandatory | Yes |

> ⚠️ **`forceInstagramReauth` response body note:** The FE types `creatorApi.forceInstagramReauth` as returning `void`, but `AdminCreatorController.forceInstagramReauth()` returns `{"success": true}` (not an empty `204`). This is intentional — an empty body would cause `apiRequest()`'s unconditional `.response.json()` call to throw. The `success` field is silently discarded on the FE side. **This is documented asymmetry, not a bug.**

> ⚠️ **Creator `update()` scope gap:** The FE passes a `Partial<Creator>` (potentially all Creator fields), but the BE `UpdateCreatorRequest` DTO only maps `name` and `niche`. Extra fields are silently ignored by Jackson — no `400` error, but they have no effect. This means any UI control for other creator fields (e.g., bio, location) would appear to succeed but change nothing.

> ⚠️ **`adjustTier` — `newQualityScore` is a no-op, and doubly dead:** `AdjustTierRequest` accepts `newQualityScore` and Jackson maps it, but `AdminCreatorService.adjustTier()` never reads the field — it is silently discarded (intentionally: `qualityScore` is a computed metric from `CreatorScore`/`ScoreCalculationJob`, never an admin free-set value — stated in both the DTO javadoc `AdminCreatorDtos.java:116-119` and the service javadoc `AdminCreatorService.java:424-425`). On top of that, the **only caller never sends it**: `CreatorProfile.tsx:336` calls `adjustTier({ creatorId, newTier, reason })`. The field is wire-compat ballast on both sides. The only changes that persist are `newTier` + the audit log entry.

> ℹ️ **Body-supplied IDs are ignored on all three ID-carrying DTOs.** `VerifyKycRequest.brandId`, `ReviewApplicationRequest.creatorId` and `AdjustTierRequest.creatorId` are all present in the JSON body (mirroring the FE action-object types) and all **deliberately ignored server-side** — the `@PathVariable` is the only trusted ID source. Note the FE does use the body field to *build the URL*: `brandApi.verifyKyc` posts to `/brands/${action.brandId}/verify-kyc`, `creatorApi.reviewApplication` to `/creators/${action.creatorId}/review-application`, `creatorApi.adjustTier` to `/creators/${adjustment.creatorId}/tier`. So the ID travels twice; only the path copy is trusted, which is the correct posture.

---

## 12 · Users Section — Summary Table

| # | Endpoint | Backend exists | FE wired | Real data | Notes |
|---|----------|---------------|----------|-----------|-------|
| B-1 | `GET /admin/brands` | ✅ | ✅ | ✅ | 3 filters + 2 pagination params; industry/size type-only, no BE support |
| B-2 | `GET /admin/brands/{id}` | ✅ | ✅ | ✅ | AbortController, refresh pattern |
| B-3 | `PUT /admin/brands/{id}` | ✅ | ✅ | ✅ | 4 allow-listed fields |
| B-4 | `POST …/verify-kyc` | ✅ | ✅ | ✅ | Mandatory reason → audit log |
| B-5 | `POST …/suspend` | ✅ | ✅ | ✅ | Mandatory reason → audit log |
| B-6 | `POST …/reinstate` | ✅ | ✅ | ✅ | Mandatory reason → audit log |
| B-7 | `POST …/budget-override` | ✅ | ✅ | ✅ | **Money path · SUPER_ADMIN only** — single AlertDialog + ≥10-char reason (server-enforced); `void`-typed FE but returns `{success:true}` |
| C-1 | `GET /admin/creators` | ✅ | ✅ | ✅ | 3 filters sent; 5 type-only filters not wired or supported |
| C-2 | `GET /admin/creators/applications/pending` | ✅ | ✅ | ✅ | Pagination only — no filter controls |
| C-3 | `GET /admin/creators/{id}` | ✅ | ✅ | ✅ | AbortController, refresh pattern |
| C-4 | `PUT /admin/creators/{id}` | ✅ | ✅ | ✅ | name/niche only (other fields silently ignored) |
| C-5 | `PUT …/tier` | ✅ | ✅ | ✅ | Mandatory reason → audit log |
| C-6 | `POST …/review-application` | ✅ | ✅ | ✅ | Mandatory reason; `action` field (APPROVE/REJECT); no `qualityScore` on DTO |
| C-7 | `POST …/instagram/force-reauth` | ✅ | ✅ | ✅ | Returns `{success:true}` — FE discards silently |
| C-8 | `POST …/suspend` | ✅ | ✅ | ✅ | Mandatory reason → audit log |
| C-9 | `POST …/reinstate` | ✅ | ✅ | ✅ | Mandatory reason → audit log |

**All 16 Users-section endpoints: backend exists, FE wired, real data. Zero `unavailable()` stubs in Users.**

---

## 13 · Gaps & Open Items (Users Section)

| Gap | Severity | What's needed |
|-----|----------|--------------|
| Brand filter: `industry` / `size` not sent | Low | Add query params to `AdminBrandController.list()` + FE filter controls if needed |
| Creator filter: `instagramVerified / niche / tier / minFollowers / maxFollowers` not sent | Medium | 5 type-defined filters with no BE support — either add BE or remove from type |
| Creator `update()` extra fields silently ignored | Low | Either restrict FE type to `{name, niche}` or add fields to `UpdateCreatorRequest` |
| `adjustTier` — `newQualityScore` accepted by DTO but service never persists it | Low | Either wire it to the creator entity or remove from `AdjustTierRequest` |
| Pending Applications list: no filter controls | Low | Product decision — does admin need status/niche filter on pending list? |
| `forceInstagramReauth` (C-7) typed `void` but returns `{success:true}` | Low | Update return type to `{success: boolean}` for honesty |
| `overrideBudget` (B-7) typed `void` but returns `{success:true}` | Low | Same void-typing asymmetry as C-7 — update return type |
| **Creator list `page=0` → `500`** (`AdminCreatorService.java:167` has no `Math.max(0, page-1)` clamp; brand list does) | **Medium** | One-line fix: clamp like `AdminBrandService.java:176`. Affects C-1 **and** C-2 (delegates to `list()`). Unreachable via the UI today, reachable by any hand-crafted request |
| **B-7 budget-override Card has no UI role gate** | **Medium (UX)** | Card renders for ADMIN/SUPPORT-visible profiles with no `SUPER_ADMIN` check; ADMIN only discovers the block as a `403` after confirming the dialog. Hide or disable the Card for non-SUPER_ADMIN. Server-side authz is correct — this is a dead-control UX defect |
| Kabir (red-team) review pending on brand/creator detail hooks | **Security** | KYC/compliance surfaces — await Kabir sign-off before production promotion |

---

## 14 · Security Notes (Users Section)

| Item | Detail |
|------|--------|
| Server-side auth gate | `AdminContextService.requireRoleWithMfaSatisfied()` — role check + MFA session check before any business logic. **Coverage verified line-by-line:** brand 7 gate calls for 7 endpoints (`AdminBrandService.java` L146, L172, L259, L297, L326, L364, L484); creator 8 gate calls for 9 endpoints (L150, L202, L216, L274, L299, L336, L378, L434) — the 9th, `listPendingApplications` (L473), inherits `list()`'s gate by delegation. No ungated endpoint |
| **Role gates are not uniform — 3 tiers (each verified against the source line)** | **Tier 1 — reads, `SUPER_ADMIN`+`ADMIN`+`SUPPORT`:** `AdminBrandService.getById` (L146), `.list` (L172), `AdminCreatorService.list` (L150), `.getById` (L202), `.listPendingApplications` (via L150). **Tier 2 — mutations, `SUPER_ADMIN`+`ADMIN`:** brand `verifyKyc` (L259), `suspend` (L297), `reinstate` (L326), `update` (L364); creator `reviewApplication` (L216), `forceInstagramReauth` (L274), `suspend` (L299), `reinstate` (L336), `update` (L378), `adjustTier` (L434). **Tier 3 — money, `SUPER_ADMIN` only:** `overrideCampaignBudget` (`AdminBrandService.java:484`, comment "Kabir L-1 — money mutation is SUPER_ADMIN only") |
| Mandatory reason field | Every destructive or compliance-critical mutation (KYC, suspend, reinstate, tier change, budget override) requires `reason` — enforced by `@NotBlank` bean validation on the request record + `@Valid` on the controller parameter; missing → `400` before the service runs. `overrideBudget` and `adjustTier` additionally carry `@Size(min = 10)`. Max lengths differ by DTO: 2000 chars for KYC/application review, 500 for suspend/reinstate/tier/budget-override |
| Budget override confirm flow | UI presents inline form Card → single "Review Budget Override" `AlertDialog` (`BrandProfile.tsx:628`, controlled, no `AlertDialogTrigger`) → calls `overrideBudget`. One confirm dialog, not two. FE disables the button below 10 reason-chars; the server enforces the same rule independently via the DTO. **But there is no UI `SUPER_ADMIN` gate on the Card — see §13** |
| AbortController | Both `useBrandDetail` and `useCreatorDetail` cancel in-flight requests on unmount — no dangling auth token exposure from orphaned fetches |
| Kabir review pending | Brand/creator detail surfaces (KYC docs, compliance actions) flagged for red-team review — not yet cleared for production |
| Audit log | All 11 mutations write via `AdminAuditLogService.record(...)` — 5 call sites in `AdminBrandService` (L280 verifyKyc, L309 suspend, L337 reinstate, L411 update, L601 overrideBudget) and 6 in `AdminCreatorService` (L250, L284, L319, L352, L406, L452). Fields: acting admin, action, entity type/ID, old value, new value, reason, `ipAddress` (re-derived server-side from the request, never client-supplied), and `AdminAuditLogSource.SERVER_INTERNAL` (`AdminAuditLogService.java:519`) — distinct from `CLIENT_REPORTED` (L350) used by `recordClientEntry`. An audit-write failure is logged loudly but never propagated, so it cannot roll back an already-committed mutation |
| Body-supplied IDs never trusted | `VerifyKycRequest.brandId` / `ReviewApplicationRequest.creatorId` / `AdjustTierRequest.creatorId` are accepted for wire compatibility and discarded — the `@PathVariable` is the sole ID source on all three mutations (see §11b note) |

---

*Sourced from live code — not types. Files: `AdminBrandController.java` · `AdminCreatorController.java` · `AdminBrandDtos.java` · `AdminCreatorDtos.java` · `AdminBrandService.java` · `AdminCreatorService.java` · `useBrandList.ts` · `useCreatorList.ts` · `useBrandDetail.ts` · `useCreatorDetail.ts` · `useCreatorApplications.ts` · `BrandProfile.tsx` · `CreatorProfile.tsx` · `api-contracts.ts` · `UsersPage.tsx` — branch `fix/brand-audit-remediation` · 2026-08-09*  
*Priya (CTO) verified — PARTIAL (12 corrections applied) → corrected*  
*Priya (CTO) re-verified 2026-08-09 (pass 2, full line-by-line trace of all 16 endpoints + 12 named source files): **PASS on all 8 previously-flagged claims** (1-indexing, `verifyKyc.action`/`brandId`-ignored, `ReviewApplicationRequest` shape, `AdjustTierRequest` shape + ≥10-char, single AlertDialog, SUPER_ADMIN-only gate, 3-tier role matrix, B-7 void asymmetry, `isSuspended`→`suspended` translation). **Zero fabrications found.** Two previously-undocumented real defects added: creator-list `page=0` → `500` (unclamped `PageRequest.of(page-1, …)`) and B-7's missing UI role gate. Enforcement attribution corrected (bean validation on the request records, not imperative controller code), and the money-path guards, audit-log call sites, and role-gate line numbers made citable.*
