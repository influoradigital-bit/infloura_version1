# Influora — Brand Dashboard API Audit

> **Scope:** Every API call the Brand Dashboard (`/brand/dashboard`) makes, traced from UI → API client → Java controller → DTO.
> **Method:** Static code trace (as per code — not live-probed).
> **Branch:** `fix/brand-audit-remediation` · **Date:** 2026-08-09
> **Entry point:** `src/components/brand/dashboard/dashboard-page.tsx`

---

## 1. Audit Summary

| Metric | Result |
|--------|--------|
| Total APIs called by Dashboard | **4** |
| Endpoints existing in backend | **4 / 4** ✅ |
| Response shapes matching FE types | **4 / 4** ✅ |
| Envelope unwrapping correct | ✅ Yes |
| Error handling present | ✅ Yes (`allSettled` + toast) |
| TypeScript typecheck | ✅ **0 errors** (`npx tsc --noEmit`) |
| **Real defects found** | **1** 🔴 |

**Verdict: APIs are wired correctly. One contract-violation bug in how a valid backend response is rendered.**

---

## 2. API-by-API Trace

| # | UI Element | FE Call | HTTP | Endpoint | Backend Controller | Status |
|---|-----------|---------|------|----------|-------------------|--------|
| 1 | "Requires Your Action" card | `api.dashboard.actions('brand')` | GET | `/dashboard/actions` | `DashboardController.java:34` | ✅ Working |
| 2 | Wallet card | `api.wallet.get('brand')` | GET | `/wallet` | `WalletController.java:72` | ⚠️ Working, render bug |
| 3 | Pipeline card | `api.dashboard.pipeline('brand')` | GET | `/dashboard/pipeline` | `DashboardController.java:41` | ✅ Working |
| 4 | TrendSpark nudge card | `api.trendspark.getNudge()` | GET | `/brand/trendspark/nudge` | `TrendSparkController.java:40` | ✅ Working |

### Also rendered on Dashboard (via `BrandLayout`)

| # | UI Element | FE Call | HTTP | Endpoint | Backend Controller | Status |
|---|-----------|---------|------|----------|-------------------|--------|
| 5 | Notification bell | `api.notifications.list('brand')` | GET | `/notifications` | `NotificationController.java:45` | ✅ Working |
| 6 | "Mark all read" | `api.notifications.markRead()` | POST | `/notifications/read` | `NotificationController.java` | ✅ Working |

---

## 3. Response Shape Verification

### 3a. `GET /dashboard/actions`

| FE expects | Backend `DashboardDtos.ActionItem` | Match |
|-----------|-----------------------------------|-------|
| `id: string` | `String id` | ✅ |
| `type: 'deliverable_review' \| 'counter_proposal' \| 'payment_release' \| 'sign_contract'` | `String type` | ✅ |
| `title: string` | `String title` | ✅ |
| `subtitle: string` | `String subtitle` | ✅ |
| `deadline: string` | `Instant deadline` → ISO string | ✅ |
| `priority: 'urgent' \| 'high' \| 'medium'` | `String priority` | ✅ |
| `amount: number` | `BigDecimal amount` | ✅ |
| `link: string` | `String link` | ✅ |

**8/8 fields aligned.**

### 3b. `GET /dashboard/pipeline`

| FE expects | Backend `DashboardDtos.PipelineStage` | Match |
|-----------|--------------------------------------|-------|
| `stage: string` | `String stage` | ✅ |
| `count: number` | `long count` | ✅ |

**2/2 fields aligned.**

### 3c. `GET /wallet`

| FE expects | Backend `MoneyDtos.WalletSummaryResponse` | Match |
|-----------|------------------------------------------|-------|
| `availableBalance: number` | `BigDecimal availableBalance` | ✅ |
| `escrowLocked: number` | `BigDecimal escrowLocked` | ✅ |
| `runwayDays: number` | `Integer runwayDays` (**nullable**) | ⚠️ See §5 |
| — | `BigDecimal pendingPayouts` (unused by dashboard) | ✅ |

**3/3 consumed fields present.** Nullability handled incorrectly — see §5.

---

## 4. What Is Done Well

| Practice | Where | Why it matters |
|----------|-------|----------------|
| `Promise.allSettled` not `Promise.all` | `dashboard-page.tsx:101` | One failing endpoint can't blank the other two cards |
| Real empty-state fallback, never mock | `dashboard-page.tsx:47` | A failed call shows ₹0, not fabricated figures |
| `Array.isArray` without `.length > 0` | `dashboard-page.tsx:113` | A legitimately-empty new account renders its real empty state |
| Error toast on failure | `dashboard-page.tsx:142` | Was `console.error` only — user now sees the failure |
| Envelope unwrapped correctly | `api.ts:399` (`return envelope.data as T`) | Backend `ApiResponse` wrapper is stripped before FE reads it |
| Cancellation guard | `dashboard-page.tsx:99` | No setState on unmounted component |

---

## 5. 🔴 DEFECT FOUND — Wallet shows false "CRITICAL" alarm

| Field | Detail |
|-------|--------|
| **Severity** | HIGH — user-visible false alarm on money UI |
| **Where** | `src/components/brand/dashboard/dashboard-page.tsx:125` and `:166` |
| **Type** | Documented contract violation |
| **Status** | Confirmed by code trace, both sides read |

### The backend contract (explicit)

`WalletService.java:422-425` Javadoc states:

> Returns `null` — never Infinity or a made-up number — when there has been no spend in the window, since a runway is undefined (effectively infinite) for a dormant wallet. **The frontend is expected to render an honest "—" / "Healthy" state for a null value.**

The same contract is stated a **second time** on the DTO itself — `MoneyDtos.java:35-38`:

> frontend must render an explicit "healthy" / "—" state and **must NEVER substitute a fabricated placeholder number**.

`runwayDays` is `Integer` (nullable) and the DTO carries `@JsonInclude(NON_NULL)` — so on a dormant wallet the field is **omitted from the JSON entirely**.

The backend authors wrote this requirement down in two separate places. The frontend violates both.

### What the frontend actually does

| Step | Code | Result |
|------|------|--------|
| 1 | `runwayDays: walletResult.value.runwayDays ?? 0` (`:125`) | `null` → **`0`** |
| 2 | `runwayDays > 30 ? 'healthy' : runwayDays > 14 ? 'warning' : 'critical'` (`:166`) | `0` → **`'critical'`** |

### What the brand sees

| UI element | Rendered state |
|-----------|----------------|
| Wallet badge | 🔴 **"Critical"** (destructive variant) |
| Card border | Red (`border-destructive-foreground/30`) |
| Runway text | 🔴 **"0d runway"** |
| Warning line | ⚠️ **"Low"** |
| Progress bar | 0%, red fill |
| CTA button | Flips to prominent `default` variant, label changes to **"Recharge now"** instead of "Manage wallet" |

### Who hits this

**Every newly-funded brand workspace.** A brand that has topped up ₹5,00,000 but has not yet spent anything in the trailing 30-day window gets a red CRITICAL wallet alarm claiming **0 days of runway** — while holding a full wallet.

The backend is behaving correctly and said so in writing. The frontend collapses "unknown" into "zero", and zero into "critical".

### Fix

Keep `runwayDays` nullable through to render:

```ts
runwayDays: walletResult.value.runwayDays ?? null,
```

```ts
const walletHealth =
  wallet.runwayDays === null ? 'healthy'
  : wallet.runwayDays > 30 ? 'healthy'
  : wallet.runwayDays > 14 ? 'warning'
  : 'critical';
```

And render `—` instead of `0d` when null — exactly what the backend Javadoc asked for.

---

## 6. NOT CHECKED (law 5 — what this audit cannot see)

| Not verified | Why |
|-------------|-----|
| Runtime behaviour against a live server | Static code trace only; no live probe was run |
| HTTP verb agreement (GET vs POST) | The endpoint oracle compares paths only |
| Whether auth/workspace guards actually pass at runtime | `requireBrandWorkspace` correctness not executed |
| Whether `DashboardService.actions()` returns correct rows | Service-layer query logic not traced |
| Response latency / N+1 query behaviour | Requires profiling |
| The `fe_be_endpoints.py` oracle's coverage | It scopes to **admin** surfaces only, not the brand dashboard — it passed, but it did not test anything in this audit |

---

## 7. Evidence

| Claim | Oracle | Command | Result |
|-------|--------|---------|--------|
| No type errors across FE | `tsc` | `npx tsc --noEmit` | **0 errors, exit 0** |
| Admin FE↔BE paths resolve | `fe_be_endpoints.py` | `python .proof-os/gates/fe_be_endpoints.py` | 57/57 matched, exit 0 (**admin scope only — not this audit's subject**) |
| 4 dashboard endpoints exist | code read | controller `@GetMapping` inspection | 4/4 found |
| DTO shapes align | code read | DTO record field comparison | 13/13 consumed fields aligned |

---

*Dashboard audit produced by static code trace of live branch `fix/brand-audit-remediation`. No live server was probed.*

---
---

# PART 2 — Brand Campaigns API Audit

> **Scope:** Every API call the Brand Campaigns surface makes, across all 6 campaign routes.
> **Method:** Static code trace (as per code — not live-probed).
> **Branch:** `fix/brand-audit-remediation` · **Date:** 2026-08-09

**Routes covered** (`src/App.tsx:172-313`):

| Route | Page/Component |
|-------|----------------|
| `/brand/campaigns` | `campaigns-list.tsx` |
| `/brand/campaigns/new` | `brand-new-campaign.tsx` → `campaign-form.tsx` |
| `/brand/campaigns/new/hype` | `brand-new-hype-campaign.tsx` |
| `/brand/campaigns/:id` | `brand-campaign-detail.tsx` |
| `/brand/campaigns/:id/edit` | `brand-edit-campaign.tsx` |
| `/brand/campaigns/:campaignId/tracking` | `brand-campaign-tracking.tsx` |

---

## 8. Campaigns Audit Summary

> ⚠️ **CORRECTED BY CTO VERIFICATION — see §17-22.** This section originally claimed 15 APIs and 3 defects. Priya's independent re-trace found the real count is **21 APIs** and **6 defects**. The figures below are corrected; §18 and §20 carry her evidence.

| Metric | Result (corrected) | Originally claimed |
|--------|--------------------|--------------------|
| Total APIs used by Campaigns surface | **21** | ~~15~~ |
| Endpoints existing in backend | **21 / 21** ✅ | 15 / 15 |
| HTTP verbs matching | **21 / 21** ✅ | 15 / 15 |
| Backend line citations accurate | **15 / 15 exact** ✅ (CTO-verified) | — |
| Write-payload validation mirrors backend | ✅ Yes (`campaign-validation.ts`) | same |
| TypeScript typecheck | ✅ **0 errors** (`npx tsc --noEmit`) | same |
| **Real defects found** | **6** (2 HIGH, 2 MEDIUM, 2 LOW) | ~~3~~ |

**Verdict: Every endpoint is correctly wired. Two HIGH-severity bugs — one silent data-loss in the campaign list, one fabricated-zero displayed as fact.**

**Root cause worth reading first:** `CampaignMetrics.empty()` is passed at every call site (`CampaignService.java:84, 99, 169, 289`), so `collaboratorsCount`, `activeCollaborations`, `completedCollaborations` and `totalSpend` are **always 0 on the wire**. That single gap drives D-3, D-5, and it is why the fix this audit originally proposed for D-3 would not have worked (§19).

---

## 9. API-by-API Trace

### 9a. Core Campaign CRUD — `CampaignController.java` (`@RequestMapping("/campaigns")`)

| # | UI Action | FE Call | HTTP | Endpoint | Backend | Status |
|---|-----------|---------|------|----------|---------|--------|
| 1 | Campaign list loads | `api.campaigns.list()` | GET | `/campaigns` | `:45` | ⚠️ Truncates — §11 |
| 2 | Open campaign detail | `api.campaigns.get(id)` | GET | `/campaigns/{id}` | `:58` | ✅ Working |
| 3 | Create campaign | `api.campaigns.create()` | POST | `/campaigns` | `:64` | ✅ Working |
| 4 | Edit / Pause / Resume | `api.campaigns.update(id)` | PATCH | `/campaigns/{id}` | `:72` | ✅ Working |
| 5 | Delete campaign | `api.campaigns.delete(id)` | DELETE | `/campaigns/{id}` | `:80` | ✅ Working |
| 6 | Duplicate campaign | `api.campaigns.duplicate(id)` | POST | `/campaigns/{id}/duplicate` | `:86` | ✅ Working |
| 7 | Campaign analytics tab | `api.campaigns.analytics(id)` | GET | `/campaigns/{id}/analytics` | `:97` | ✅ Working |

### 9b. Campaign Templates — `CampaignTemplateController.java` (`@RequestMapping("/campaign-templates")`)

| # | UI Action | FE Call | HTTP | Endpoint | Backend | Status |
|---|-----------|---------|------|----------|---------|--------|
| 8 | Template picker on New Campaign | `api.campaignTemplates.list()` | GET | `/campaign-templates` | `:41` | ✅ Working |
| 9 | Prefill form from template | `api.campaignTemplates.get(id)` | GET | `/campaign-templates/{id}` | `:47` | ✅ Working |
| 10 | "Save as Template" action | `api.campaignTemplates.create()` | POST | `/campaign-templates` | `:54` | ✅ Working |
| 11 | Delete custom template | `api.campaignTemplates.remove(id)` | DELETE | `/campaign-templates/{id}` | `:62` | ✅ Working |

### 9c. Tracking & Coupons — `CampaignTrackingController.java`

| # | UI Action | FE Call | HTTP | Endpoint | Backend | Status |
|---|-----------|---------|------|----------|---------|--------|
| 12 | Tracking links list | `api.campaignTracking.listTrackingLinks()` | GET | `/campaigns/{id}/tracking-links` | `:85` | ✅ Working |
| 13 | Create tracking link | `api.campaignTracking.createTrackingLink()` | POST | `/campaigns/{id}/tracking-links` | `:67` | ✅ Working |
| 14 | Coupons list | `api.campaignTracking.listCoupons()` | GET | `/campaigns/{id}/coupons` | `:113` | ✅ Working |
| 15 | Create coupon | `api.campaignTracking.createCoupon()` | POST | `/campaigns/{id}/coupons` | `:94` | ✅ Working |

---

## 10. What Is Done Well

| Practice | Where | Why it matters |
|----------|-------|----------------|
| Validation constants mirror backend exactly | `campaign-validation.ts:11-12` (`TITLE_MIN=5`, `TITLE_MAX=300`) vs `@Size(min=5,max=300)` | Prevents a guaranteed 400; the file comment notes this gap "was missed twice before this was centralized" |
| Timezone-safe date formatting | `api.ts:1123-1130` | Builds `yyyy-MM-dd` from **local** components, not `toISOString()` — avoids IST rolling the date back a day |
| Explicit FE→BE enum mapping table | `api.ts:1142` (`CAMPAIGN_TYPE_TO_API`) | FE `OPEN` → BE `STANDARD` is lossless and explicit, not an implicit default |
| Search debounce | `campaigns-list.tsx:271` (300ms) | One request per typed phrase, not per keystroke |
| Role gates are UX hints, not security | `campaigns-list.tsx:262-263` | Disables only when role is *known* insufficient; server stays source of truth |
| Cancellation guards on every fetch | `campaigns-list.tsx`, `campaign-form.tsx` | No setState after unmount |

---

## 11. 🔴 DEFECT 1 — Campaign list silently truncates at 100 (HIGH)

| Field | Detail |
|-------|--------|
| **Severity** | HIGH — silent data loss on the primary campaigns screen |
| **Where** | `src/lib/api.ts:1185-1199` + `src/components/brand/campaigns/campaigns-list.tsx:272` |
| **Type** | Discarded pagination envelope + missing UI |

### The backend paginates correctly and says so

`CampaignService.java:72` — the server caps the page size hard:

```java
int safeLimit = Math.min(Math.max(limit, 1), 100);   // ← hard cap of 100
```

and returns full pagination metadata (`CampaignService.java:88-93`):

```java
PageMeta meta = new PageMeta(safePage, safeLimit, result.getTotalElements(), result.hasNext());
```

which the controller ships in the envelope — `CampaignController.java:55`:

```java
return ResponseEntity.ok(ApiResponse.ok(result.items(), result.meta()));
```

**The backend explicitly tells the client `total` and `hasNext`.**

### The frontend throws that away

| Step | Code | Effect |
|------|------|--------|
| 1 | `campaigns.list` uses `http.request` — **not** `requestWithMeta` (`api.ts:1189`) | `envelope.meta` is discarded; `request` returns `envelope.data` only (`api.ts:399`) |
| 2 | `campaigns-list.tsx:272` requests `limit: 100` | Exactly the server ceiling |
| 3 | Pagination UI | **0 occurrences** of `setPage` / `currentPage` / `Pagination` / `loadMore` / `hasMore` across all **1020 lines** of `campaigns-list.tsx` |

### The proof this is an oversight, not a design choice

The **creators** client in the same file does it correctly, and its comment names `/campaigns` as the reference implementation (`api.ts`):

> Uses `requestWithMeta` (not `request`) so callers can paginate … (same `ApiResponse.ok(items, meta)` envelope shape as **GET /campaigns**)

So the codebase knows `/campaigns` returns meta. The creators client consumes it; the campaigns client does not.

### What the brand sees

A brand with **101+ campaigns** sees exactly 100. There is no next page, no "showing 100 of 143", no warning. The 101st campaign is **invisible and unreachable through the UI** — it can only be opened by typing its URL directly. The backend computed `total: 143, hasNext: true` and sent it; the client dropped it on the floor.

### Fix

Switch the client to `requestWithMeta` and surface the meta:

```ts
list: async (params: CampaignListParams = {}) => {
  const { data, meta } = await http.requestWithMeta<CampaignApiRow[]>('GET', '/campaigns', { query: {...} });
  return { campaigns: data.map(mapCampaignFromApi), meta };
}
```

Then add either a pager or an infinite-scroll "Load more" driven by `meta.hasNext` — mirroring what `creators` already does.

---

## 12. 🟡 DEFECT 2 — Progress bar is permanently 0%, and "Sort by Progress" is a dead control (MEDIUM)

| Field | Detail |
|-------|--------|
| **Severity** | MEDIUM — misleading UI + one non-functional control |
| **Where** | `campaigns-list.tsx:286`, `:215`, `:418-419` |
| **Type** | Feature works in mock mode, dead in live mode |

`CampaignResponse` (`CampaignDtos.java:118-145`) has **no `progress` field**. The frontend knows and defaults honestly (`campaigns-list.tsx:284-286`):

```ts
// No `progress` field exists on the API contract yet — default to 0
progress: extended.progress ?? 0,
```

**Consequences in live mode:**

| Symptom | Detail |
|---------|--------|
| Every campaign card's progress bar | Renders **0%** forever — reads as "no work done", not "not measured" |
| "Sort by → Progress" dropdown option | `:215`, `:418` — sorts on a field that is 0 for every row, so **selecting it does nothing** |
| Mock mode | Shows real values (65, 40, 100, 25 — `:91-179`), so this looks fully working in demo |

This is the exact trap law 5 names: a hardcoded value passes every linter and every typecheck.

### ⛔ The client-side fix originally proposed here DOES NOT WORK

This audit first recommended deriving progress from `completedCollaborations / collaboratorsCount`. **CTO verification proved that is a no-op** — see §19.

`CampaignResponse` does carry those three fields (`CampaignDtos.java:141-143`), but they are never populated: `CampaignMapper.java:64-66` returns `CampaignMetrics(0, 0, 0, ZERO)` and **every** call site passes `CampaignMetrics.empty()` (`CampaignService.java:84, 99, 169, 289`). The values are always `0` on the wire, so the expression short-circuits on falsy `0` and returns `0`. Shipping it would change nothing while appearing to fix the bug.

**Correct fix — server-side, not client-side.** Populate `CampaignMetrics` from the collaboration repository in `CampaignService`. That one change closes this defect and **D-5** outright, and is the prerequisite for any real progress value.

Until that lands, hide the progress bar and remove the "Sort by Progress" option rather than rendering a permanently-empty bar.

---

## 13. 🔵 DEFECT 3 — Stale line-number citations in tracking JSDoc (LOW)

| Field | Detail |
|-------|--------|
| **Severity** | LOW — documentation drift only, no runtime impact |
| **Where** | `src/lib/api.ts:3571, 3578, 3591, 3598` |

The `campaignTracking` client cites backend line numbers that have all shifted by ~11 lines:

| JSDoc claims | Actually at | Endpoint |
|-------------|-------------|----------|
| `CampaignTrackingController.java:74` | `:85` | GET tracking-links |
| `CampaignTrackingController.java:56` | `:67` | POST tracking-links |
| `CampaignTrackingController.java:102` | `:113` | GET coupons |
| `CampaignTrackingController.java:83` | `:94` | POST coupons |

**The endpoints themselves are correct and working** — only the comments drifted. Worth a sweep since this repo's comments are used as navigation.

---

## 14. NOT CHECKED — Campaigns (law 5)

| Not verified | Why |
|-------------|-----|
| Runtime behaviour against a live server | Static code trace only; no live probe was run |
| Whether `@RequiresPlan CAMPAIGN_TEMPLATES` gating actually blocks at runtime | Annotation read, not executed |
| Whether HYPE campaign validation passes end-to-end | `HypeConfigDto` conditional validation not traced |
| `CampaignSpecs.forWorkspace` filter/search SQL correctness | Repository-layer query logic not traced |
| Whether duplicate/delete correctly cascade collaborations | Service-layer side effects not traced |
| Analytics numbers' accuracy | Backend states all values are `CREATOR_REPORTED`, never platform-verified |

---

## 15. Combined Evidence — Dashboard + Campaigns

| Claim | Oracle | Command | Result |
|-------|--------|---------|--------|
| No type errors across FE | `tsc` | `npx tsc --noEmit` | **0 errors, exit 0** |
| 15 campaign endpoints exist | code read | controller `@Mapping` inspection | 15/15 found |
| Campaign list truncation | code read | `CampaignService.java:73` + `campaigns-list.tsx:272` + 0 pagination matches in 1020 lines | Confirmed |
| No `progress` field on contract | code read | `CampaignDtos.java:118-145` field list | Confirmed absent |

---

## 16. Running Defect Register

> ⚠️ **SUPERSEDED — the authoritative register is §21 (CTO-corrected).** This table was written before verification and is missing D-5, D-6 and D-7. Kept only to show what the first pass caught.

| ID | Surface | Severity | Summary | Fix size |
|----|---------|----------|---------|----------|
| **D-1** | Dashboard | 🔴 HIGH | Null runway renders as red "CRITICAL / 0d" on funded wallets | 2 lines |
| **D-2** | Campaigns | 🔴 HIGH | List silently truncates at 100; pagination meta discarded, no pager UI | ~15 lines |
| **D-3** | Campaigns | 🟡 MEDIUM | Progress bar always 0%; "Sort by Progress" is a dead control | server-side, see §19 |
| **D-4** | Campaigns | 🔵 LOW | Stale backend line-number citations in tracking JSDoc | comments only |

**→ Missed by this pass, added in §21:** D-5 (🔴 HIGH, `collaboratorsCount` always 0 shown as fact), D-6 (🟡 MEDIUM, `sortBy`/`sortOrder` never sent), D-7 (🔵 LOW, tracking route unreachable).

---

*Audits produced by static code trace of live branch `fix/brand-audit-remediation`. No live server was probed.*
*PART 2 was independently verified and corrected by Priya (CTO) — see §17-22.*

---
---

# PART 2 — CTO VERIFICATION (Priya)

> **Verifier:** Priya, CTO · **Date:** 2026-08-09 · **Scope:** PART 2 only (§8–16). Part 1 verified separately.
> **Method:** Independent re-trace from primary sources. Every claim below was re-opened in the actual file; nothing was accepted from the audit text.

## 17. Verdict — **NEEDS_FIX**

The three reported defects are **all real**. Nothing in §8–16 is a false alarm. However the audit is **incomplete in two material ways** and **one of its recommended fixes does not work**:

| | Finding |
|---|---|
| ✅ | All 15 listed endpoints exist, are used, verbs match, and **all 15 backend line citations are exact** |
| ❌ | The surface calls **21** APIs, not 15 — 6 were missed (§18) |
| ❌ | The DEFECT 2 recommended fix is a **no-op** — the fields it relies on are hardcoded `0` server-side (§19) |
| ❌ | **3 additional defects** found that the audit did not catch, one of them HIGH (§20) |

## 17a. Per-defect ruling

| Audit ID | Ruling | Evidence |
|----------|--------|----------|
| **DEFECT 1** (§11) list truncates at 100 | 🔴 **REAL BUG** | Cap confirmed `CampaignService.java:72`; meta built `:87-92`; shipped `CampaignController.java:55`; discarded by `api.ts:1189` (`http.request`, which returns `envelope.data` only — `api.ts:399`); caller pins `limit: 100` at `campaigns-list.tsx:272`; zero pagination controls in all 1021 lines. Confirmed. |
| **DEFECT 2** (§12) progress always 0 | 🟡 **REAL BUG — fix is wrong** | `CampaignResponse` (`CampaignDtos.java:118-145`) confirmed to have **no** `progress` field. `progress: extended.progress ?? 0` at `campaigns-list.tsx:286`. "Progress" sort option is a live control at `campaigns-list.tsx:593-598`, comparator at `:418-419`. Bug is real. **But the proposed fix fails — see §19.** |
| **DEFECT 3** (§13) stale line numbers | 🔵 **REAL** | All 4 confirmed, each off by exactly 11: `api.ts:3571` says `:74`→actual `:85`; `:3578` says `:56`→`:67`; `:3591` says `:102`→`:113`; `:3598` says `:83`→`:94`. |

## 17b. Claims that verified clean

- All 7 `CampaignController` mappings at `:45, :58, :64, :72, :80, :86, :97` — **exact**.
- All 4 `CampaignTemplateController` mappings at `:41, :47, :54, :62` — **exact**.
- All 4 `CampaignTrackingController` mappings at `:67, :85, :94, :113` — **exact**.
- §10 validation claim: `campaign-validation.ts:11-12` (`TITLE_MIN=5`, `TITLE_MAX=300`) vs `CampaignDtos.java:61` `@Size(min=5, max=300)` — **correct**.
- §11 "the creators client does it right" claim — **correct**, and stronger than stated: `api.ts:1326-1329` (creators) *and* `api.ts:3980-3984` (creator campaign browse) both use `requestWithMeta`. Brand `campaigns.list` is the only list client in the file that drops meta.

### Minor citation corrections (audit text vs. actual)

| Audit says | Actually at |
|---|---|
| `CampaignService.java:73` (the 100 cap) | `:72` |
| `CampaignService.java:88-93` (PageMeta) | `:87-92` |
| `CampaignDtos.java:141-143` (collaboration counts) | `:142-144` |

---

## 18. MISSED — the surface calls 21 APIs, not 15

§8 states "Total APIs used by Campaigns surface: **15**" and §9 claims to trace *every* call. Six are absent:

| # | FE Call | Endpoint | Called from | Backend |
|---|---------|----------|-------------|---------|
| 16 | `api.workspaceMembers.list()` | GET `/workspace/members` | `campaigns-list.tsx:244` | `WorkspaceMemberController.java:74` |
| 17 | `api.reports.exportCampaign()` | GET `/campaigns/{id}/export` | `brand-campaign-detail.tsx:534` | `ReportExportController.java:37` (`@RequiresPlan EXPORT`) |
| 18 | `api.deals.list('brand','all')` | — | `brand-campaign-detail.tsx:567` | DealController |
| 19 | `api.deals.accept()` | — | `brand-campaign-detail.tsx:675` | DealController |
| 20 | `api.deals.reject()` | — | `brand-campaign-detail.tsx:698` | DealController |
| 21 | `api.deals.counter()` | — | `brand-campaign-detail.tsx:724` | DealController |

**#17 is the clearest miss** — `GET /campaigns/{campaignId}/export` is literally a `/campaigns/*` route, on a campaign page, and is plan-gated (a 403 path the audit never considered). **#16 is load-bearing**: it is the call that decides `canEdit` / `canDelete` (`campaigns-list.tsx:262-263`), the very role-gating §10 praises.

Also unrecorded: the 4 tracking calls (§9c #12–15) are **not** made by `brand-campaign-tracking.tsx` directly. That page has zero `api.` references; the calls live in `src/hooks/analytics/useCampaignTrackingLinks.ts:45,66` and `useCampaignCoupons.ts:38,57`. The endpoints are right; the file attribution in §9c is not.

---

## 19. ⚠️ The DEFECT 2 fix does not work

§12 asserts: *"The data to compute it already exists"* and proposes deriving progress from `completedCollaborations / collaboratorsCount`.

**Those fields are hardcoded `0` on every campaign response.** `CampaignMapper.java:64-66`:

```java
public static CampaignMetrics empty() {
    return new CampaignMetrics(0, 0, 0, BigDecimal.ZERO);
}
```

and **every** call site passes `empty()` — there is no other caller anywhere in the backend:

| Path | Line |
|------|------|
| `CampaignService.list` | `CampaignService.java:84` |
| `CampaignService.get` | `:99` |
| `CampaignService.create` / `update` | `:169` |
| `CampaignService.duplicate` | `:289` |

So `collaboratorsCount`, `activeCollaborations`, `completedCollaborations`, and `totalSpend` are **all always 0**. The proposed expression short-circuits on the falsy `0` and returns `0` — the exact value it was meant to replace. Shipping it would burn a dev's afternoon and change nothing. **The real fix is backend: populate `CampaignMetrics` from the collaboration repository.**

---

## 20. Additional defects the audit missed

### D-5 🔴 HIGH — every campaign card reports "0/N creators" in live mode

Same root cause as §19. `collaboratorsCount` is always `0` from the API, and the UI renders it as a hard fact in both view modes:

| Location | Renders |
|----------|---------|
| `campaigns-list.tsx:814` (grid card) | `{campaign.collaboratorsCount}/{campaign.maxCollaborators ?? 0}` |
| `campaigns-list.tsx:864` (list row) | `{campaign.collaboratorsCount}/{...} creators` |

A campaign with 8 active creators displays **"0/10"**. This is not a dim progress bar — it is a **confidently wrong number on the primary campaigns screen**, and it is the same defect class as the Dashboard wallet bug the audit rated HIGH (D-1). Mock mode hides it completely (`campaigns-list.tsx:90,112,156,178` carry real counts), so it demos perfectly. This belongs in the register above D-3.

### D-6 🟡 MEDIUM — sorting is client-side over a truncated window

`CampaignController.java:52-53` accepts `sortBy` (default `createdAt`) and `sortOrder` (default `desc`). `api.ts:1190-1195` sends **only** `page`, `limit`, `search`, `status` — never `sortBy`/`sortOrder`. So the server always returns the newest 100 by creation date, and `campaigns-list.tsx:410-423` re-sorts that slice in the browser. "Sort by Budget" therefore surfaces the highest-budget campaign **among the 100 most recent**, not among all campaigns. Compounds D-2; the backend already supports the correct behaviour and the client ignores it.

### D-7 🔵 LOW — the campaign tracking route is unreachable from the UI

`/brand/campaigns/:campaignId/tracking` is registered at `App.tsx:313`, but a repo-wide search finds **no `<Link>` or `navigate()` to it anywhere in `src/`**. `brand-campaign-detail.tsx` contains zero occurrences of "tracking" (case-insensitive). §9c marks APIs #12–15 "✅ Working" — accurate at the wiring level, but the entire feature is dead-ended: a brand can only reach it by typing the URL. The audit's own §14 "NOT CHECKED" list does not cover reachability.

---

## 21. Corrected Defect Register

| ID | Surface | Severity | Summary | Status |
|----|---------|----------|---------|--------|
| **D-1** | Dashboard | 🔴 HIGH | Null runway renders as red "CRITICAL / 0d" on funded wallets | Part 1 — not re-verified here |
| **D-2** | Campaigns | 🔴 HIGH | List truncates at 100; meta discarded, no pager UI | ✅ **CONFIRMED REAL** |
| **D-5** | Campaigns | 🔴 HIGH | *(new)* `collaboratorsCount` always 0 → every card shows "0/N creators" | ✅ **NEW — CONFIRMED** |
| **D-3** | Campaigns | 🟡 MEDIUM | Progress always 0%; "Sort by Progress" dead | ✅ **REAL — but published fix is a no-op (§19)** |
| **D-6** | Campaigns | 🟡 MEDIUM | *(new)* `sortBy`/`sortOrder` never sent; sorts a truncated window | ✅ **NEW — CONFIRMED** |
| **D-4** | Campaigns | 🔵 LOW | Stale backend line citations in tracking JSDoc (all 4, off by 11) | ✅ **CONFIRMED REAL** |
| **D-7** | Campaigns | 🔵 LOW | *(new)* `/campaigns/:id/tracking` route has zero inbound links | ✅ **NEW — CONFIRMED** |

**Root-cause note:** D-3, D-5 and half of D-2 are one backend gap — `CampaignMetrics` is never populated and pagination meta is never consumed. Fix `CampaignMetrics` server-side first; D-5 closes and D-3 becomes a two-line client change.

## 22. What this verification did NOT check

| Not verified | Why |
|---|---|
| Runtime behaviour | Static trace only; no live server probed, consistent with the original audit |
| `api.deals.*` contract alignment (#18–21) | Newly discovered; endpoint shapes not traced to DealController DTOs |
| Whether `ReportExportController` `@RequiresPlan(EXPORT)` 403 is handled in `brand-campaign-detail.tsx:534` | Error path not traced |
| Part 1 (§1–7) | Out of scope per assignment |

---

*Verification by static re-trace of every cited file on branch `fix/brand-audit-remediation`. No claim in §17–21 was taken from the audit text; each was re-opened at source.*

**Signed: Priya, CTO**

---
---

# PART 3 — Deal Room Deep API Audit

> **Scope:** Every API across the Deal Room lifecycle — deals, chat/messages, contracts, deliverables, approval, shipment, creator profile.
> **Method:** Static code trace (as per code — not live-probed).
> **Branch:** `fix/brand-audit-remediation` · **Date:** 2026-08-09

---

## 23. Deal Room Audit Summary

> ⚠️ **CORRECTED BY RED-TEAM VERIFICATION — see §31-36.** Original figures shown struck through.

| Metric | Result (corrected) | Originally claimed |
|--------|--------------------|--------------------|
| APIs traced across the deal lifecycle | **27 rows** (§27 is numbered 1–27) | ~~26~~ — the two figures contradicted each other |
| Of those, called by `brand-chat.tsx` itself | **14** | — (8 rows belong to Discover, Contracts, or `creator-chat.tsx`; §27 over-attributes them to the Deal Room) |
| Endpoints existing in backend | **all** ✅ | same |
| **Backend line citations accurate** | **32 / 32 exact** ✅ (red-team verified) | — |
| TypeScript typecheck | ✅ **0 errors** — `npx tsc --noEmit`, exit 0, run this session | same |
| **Real defects found** | **2 confirmed + 5 missed = 7** | ~~3~~ (1 was a false alarm) |

**Verdict: every Deal Room endpoint exists and the backend citations are exact. But this pass over-scoped the surface, over-rated its headline defect, reported one defect that does not exist, and missed a HIGH-severity dead path (escrow release, M-1) that outranks everything it did find.**

---

## 24. 🟡 DEFECT D-8 — There are TWO Deal Rooms, and the sidebar points at the incomplete one (MEDIUM)

> ⚠️ **CORRECTED BY RED-TEAM VERIFICATION — see §31-36.** This section originally rated the defect CRITICAL and claimed the full Deal Room was reachable "only via ⌘K". **Both were wrong.** Kabir found five in-app links to `/brand/chat` (four from sidebar-reachable pages, including a primary-styled "Open in Deal Room" button at `contracts-and-deliverables.tsx:921`), and proved that two of the four "brand cannot" actions below are in fact available from `/brand/contracts`. Re-rated **CRITICAL → MEDIUM**. Corrected text follows.

| Field | Detail |
|-------|--------|
| **Severity** | 🟡 MEDIUM *(was CRITICAL — see correction above)* |
| **Where** | `brand-layout.tsx:100` → `/brand/deals`; the fuller room lives at `/brand/chat` |
| **Type** | Routing / navigation defect, not an API defect |

### The two implementations

| | `/brand/deals` — `deal-room-dashboard.tsx` | `/brand/chat` — `brand-chat.tsx` |
|---|---|---|
| Reached from | **Sidebar → "Deals"** (`brand-layout.tsx:100`) | **⌘K Command Bar → "Deal Rooms"** only (`command-bar.tsx:62`) |
| Tabs rendered | `overview`, `messages`, `history` (`:587-593`) | Full lifecycle |
| Contract tab | ❌ absent | ✅ `DealContractTab` (`:2308`) |
| Deliverables tab | ❌ absent | ✅ `DealDeliverablesTab` (`:2378`, `:2388`) |
| Payments tab | ❌ absent | ✅ `DealPaymentsTab` (`:2400`) |
| Shipment form | ❌ absent | ✅ `ShipmentForm` (`:2433`) |
| APIs used | `deals.list/accept/counter/reject`, `messages.list/send/markRead` (7) | all 26 |

### What the brand can and cannot do — CORRECTED

From the sidebar's Deal Room, a brand **can** browse deals, chat, accept, counter and reject. The original claim was that four further actions were stranded. Red-team verification found **only one actually is**:

| Action | Original claim | Verified reality |
|--------|---------------|------------------|
| Sign a contract | ❌ stranded | ✅ **FALSE** — available at `/brand/contracts` (sidebar → Manage): `contracts-and-deliverables.tsx:690` calls `api.contracts.sign('brand', …)`. `brand-chat.tsx` never calls `contracts.sign` at all. |
| Approve a deliverable | ❌ stranded | ✅ **FALSE** — same page, `:632` / `:658`. |
| Release a payment | ❌ stranded | ⚠️ **Worse than claimed** — `DealPaymentsTab` imports **zero** API modules (`deal-payments-tab.tsx:1-9`); its status comes from a prop (`:48`). Not reachable from *either* room. See **M-1**. |
| Mark a product as shipped | ❌ stranded | 🟡 **TRUE** — `brand-chat.tsx:968` is the sole caller repo-wide. This is the one genuinely stranded action. |

**And `/brand/chat` is not ⌘K-only.** Five in-app links reach it, four from sidebar-reachable pages — including a primary-styled **"Open in Deal Room"** button that deep-links to `&tab=contract` (`contracts-and-deliverables.tsx:921`).

### Why this still matters at MEDIUM

The stranded shipment control is not cosmetic: with no way for the brand to mark shipped, the creator's `confirm-receipt` never unlocks and the shipment state machine freezes at `ADDRESS_PROVIDED`. Combined with a code comment that actively misdirects the next maintainer, that earns MEDIUM — but not CRITICAL. A CRITICAL that dissolves under scrutiny to one stranded button devalues the scale for findings that earn it.

### The in-code comment asserts the opposite

`brand-layout.tsx:88-90` states:

> "Deals" now points at `/brand/deals` (`DealRoomDashboard`) — **the actively-maintained Deal Room** — not the older `/brand/chat` page, which `/brand/messages` now covers for pure messaging.

`/brand/chat` is not "the older page for pure messaging". It is the only page that renders contract signing, deliverable approval, payments and shipment. The comment is inverted relative to the code it documents.

### Relationship to the earlier finding

Part 1 §4b flagged the `/brand/chat` vs `/brand/deals` split as a **LOW** "route divergence". That rating was wrong. This trace shows the two routes are not variants of one screen — they are a complete room and a partial one, and navigation points at the partial one. **Re-rated LOW → CRITICAL.**

### Fix

Decide which room is canonical, then make navigation agree:

- **Option A (smaller):** point the sidebar's "Deals" at `/brand/chat`, and update the Command Bar so both agree.
- **Option B (correct long-term):** port `DealContractTab` / `DealDeliverablesTab` / `DealPaymentsTab` / `ShipmentForm` into `deal-room-dashboard.tsx` and retire the second implementation.

Either way, correct the `brand-layout.tsx:88-90` comment — it will mislead the next person to touch this.

---

## 25. 🟡 DEFECT D-9 — The deliverable **Reject** route is unreachable (MEDIUM)

| Field | Detail |
|-------|--------|
| **Severity** | 🟡 MEDIUM — a built backend capability with no client path |
| **Where** | `BrandDeliverableController.java:64` vs `src/lib/api.ts` `deliverables` client |

The backend ships the route and names it as a deliberate fix — `BrandDeliverableController.java:63-64`:

```java
/** B6 — the missing route for {@link BrandDeliverableService#reject}. */
@PostMapping("/{deliverableId}/reject")
```

The brand-safety javadoc (`:78-80`) treats it as a first-class action, stating the advisory verdict "never blocks `approve`/**`reject`**/`revise`".

**But the client has no way to call it:**

| Layer | Approve | Request Revision | Reject |
|-------|---------|------------------|--------|
| Backend route | `:46` ✅ | `:53` ✅ | `:64` ✅ |
| FE api client | `deliverables.approve` ✅ | `deliverables.requestRevision` ✅ | ❌ **absent** |
| DTO capability flag | `canApprove` ✅ | `canRequestRevision` ✅ | ❌ **no `canReject`** (`BrandDeliverableDtos.java:39-40`) |
| UI control | Approve button (`DeliverableViewer.tsx:364`) | Request Revision button (`:354`) | ❌ **no button** |
| UI status badge | `APPROVED` ✅ | `REVISION_REQUESTED` ✅ | ✅ **`REJECTED` renders** (`:32`) |

The viewer will happily **display** a `REJECTED` deliverable — the badge and styling exist — but nothing in the brand UI can put it into that state. The brand's only terminal option is an unbounded revision loop.

### Fix

Add `canReject` to `DeliverableDetailResponse`, add `deliverables.reject` to the API client, and render the button behind that flag — mirroring the approve/revise pattern already in place.

---

## 26. ~~DEFECT D-10 — Two orphaned Deal Room components~~ — **STRUCK: FALSE ALARM**

> 🚫 **This defect does not exist.** Red-team verification (§31-36) proved `bid-card.tsx` and `campaign-brief-card.tsx` are in **neither the working tree nor the git index** — they were deleted in commit `c9210a4`.

**How the error happened:** the "imported by 0 files" check matched surviving copies under untracked `.claude/worktrees/` — the known cross-worktree grep leak in this repo. The files were reported as orphaned *because they no longer exist*, and the audit then attached a "check before deleting" caveat to files already deleted.

**Lesson for future passes:** scope every repo-wide grep away from `.claude/worktrees/`, and confirm a file's existence with `git ls-files` before calling it dead code.

D-10 is withdrawn from the register.

---

## 27. API-by-API Trace — Deal Room

### 27a. Deals — `DealController.java` (`@RequestMapping("/deals")`)

| # | UI Action | FE Call | HTTP | Endpoint | Backend | Status |
|---|-----------|---------|------|----------|---------|--------|
| 1 | Deal list loads | `deals.list(role, status)` | GET | `/deals` | `:65` | ✅ |
| 2 | Open a deal | `deals.get(role, id)` | GET | `/deals/{id}` | `:72` | ✅ |
| 3 | Brand sends priced offer | `deals.create(payload)` | POST | `/deals` | `:78` | ✅ |
| 4 | Accept offer | `deals.accept(id, role)` | POST | `/deals/{id}/accept` | `:86` | ✅ |
| 5 | Reject / withdraw | `deals.reject(id, reason, role)` | POST | `/deals/{id}/reject` | `:94` | ✅ |
| 6 | Counter-offer | `deals.counter(...)` | POST | `/deals/{id}/counter` | `:104` | ✅ (idempotency-keyed) |

### 27b. Chat / Messages — `DealController.java`

| # | UI Action | FE Call | HTTP | Endpoint | Backend | Status |
|---|-----------|---------|------|----------|---------|--------|
| 7 | Load thread | `messages.list(role, dealId, before)` | GET | `/deals/{dealId}/messages` | `:114` | ✅ paginated via `before` |
| 8 | Realtime chat | `messages.stream(role, dealId, handlers)` | GET (SSE) | `/deals/{dealId}/messages/stream` | `:134` | ✅ see §28 |
| 9 | Send message | `messages.send(role, dealId, content)` | POST | `/deals/{dealId}/messages` | `:151` | ✅ |
| 10 | Mark thread read | `messages.markRead(role, dealId)` | POST | `/deals/{dealId}/messages/read` | `:160` | ✅ |

### 27c. Contracts — `ContractController.java` (`@RequestMapping("/contracts")`)

| # | UI Action | FE Call | HTTP | Endpoint | Backend | Status |
|---|-----------|---------|------|----------|---------|--------|
| 11 | Contract list for a deal | `contracts.list(role, dealId)` | GET | `/contracts?dealId=` | `:46` | ✅ |
| 12 | Open contract + milestones | `contracts.get(role, id)` | GET | `/contracts/{contractId}` | `:68` | ✅ |
| 13 | Generate contract | `contracts.generate(payload)` | POST | `/contracts` | `:38` | ✅ |
| 14 | Two-party e-sign | `contracts.sign(id, ...)` | POST | `/contracts/{contractId}/sign` | `:78` | ✅ |
| 15 | Download PDF | `contracts.pdfDownloadUrl(id)` | GET | `/contracts/{contractId}/pdf-download-url` | `:114` | ✅ |

> **Backend surplus:** `GET /contracts/unsigned` (`:58`) has no FE caller. Not a defect — a pending-signature feed the UI has not adopted.

### 27d. Deliverables & Approval

| # | UI Action | FE Call | HTTP | Endpoint | Backend | Status |
|---|-----------|---------|------|----------|---------|--------|
| 16 | Deliverables for a deal | `deliverables.list(role, dealId)` | GET | `/deals/{dealId}/deliverables` | `DealController:170` | ✅ |
| 17 | Open deliverable detail | `deliverables.getDetail(id)` | GET | `/deliverables/{id}` | `BrandDeliverable:39` | ✅ |
| 18 | **Approve** | `deliverables.approve(id)` | POST | `/deliverables/{id}/approve` | `BrandDeliverable:46` | ✅ |
| 19 | **Request revision** | `deliverables.requestRevision(id, feedback)` | POST | `/deliverables/{id}/revise` | `BrandDeliverable:53` | ✅ |
| 20 | Brand-safety advisory | `deliverables.getSafetyReview(id)` | GET | `/deliverables/{id}/safety-review` | `BrandDeliverable:81` | ✅ |
| 21 | Creator submits | `deliverables.submit(...)` | POST | `/creator/deliverables/{id}/submit` | `CreatorDeliverable:78` | ✅ |
| — | **Reject** | ❌ **no client method** | POST | `/deliverables/{id}/reject` | `BrandDeliverable:64` | 🟡 **D-9** |

### 27e. Shipment — `DealController.java`

| # | UI Action | FE Call | HTTP | Endpoint | Backend | Status |
|---|-----------|---------|------|----------|---------|--------|
| 22 | Read shipment state | `shipments.get(role, dealId)` | GET | `/deals/{id}/shipment` | `:217` | ✅ synthetic `AWAITING_ADDRESS` when no row |
| 23 | Creator submits address | `shipments.submitAddress(...)` | POST | `/deals/{id}/shipping-address` | `:190` | ✅ 409 once SHIPPED |
| 24 | **Brand marks shipped** | `shipments.markShipped(...)` | POST | `/deals/{id}/shipment` | `:199` | ✅ ADDRESS_PROVIDED → SHIPPED |
| 25 | Creator confirms receipt | `shipments.confirmReceipt(...)` | POST | `/deals/{id}/shipment/confirm-receipt` | `:208` | ✅ 409 unless SHIPPED |

State machine is guarded on both ends with explicit 409s — `AWAITING_ADDRESS → ADDRESS_PROVIDED → SHIPPED → RECEIVED`.

### 27f. Creator Profile — `CreatorController.java` (`@RequestMapping("/creators")`)

| # | UI Action | FE Call | HTTP | Endpoint | Backend | Status |
|---|-----------|---------|------|----------|---------|--------|
| 26 | Open creator profile | `creators.get(id)` | GET | `/creators/{creatorId}` | `:153` | ✅ |
| 27 | Invite to campaign | `creators.invite(...)` | POST | `/creators/{creatorId}/invite` | `:176` | ✅ |
| — | Campaign picker on profile | `campaigns.list({})` | GET | `/campaigns` | — | ⚠️ inherits **D-2** truncation |

---

## 28. What Is Done Well — Deal Room

| Practice | Where | Why it matters |
|----------|-------|----------------|
| SSE reconnect with backoff + jitter | `api.ts:1689` (CR-31) | A clean server close is treated as a disconnect, not an exit. Previously the room "went permanently deaf with no trace" |
| Reconnect refetches the gap | `brand-chat.tsx:1183-1184` | The stream carries no `Last-Event-ID` replay, so `onReconnect` re-reads **both** messages and deal status — reconnecting without it would resume future frames while silently keeping the hole |
| Stream status surfaced to UI | `onStatusChange` → `setStreamStatus` | The room can say it is disconnected rather than looking healthy while receiving nothing |
| SSE via fetch, not `EventSource` | `api.ts:1680` | `EventSource` cannot send `Authorization`, and the token must never ride in the URL |
| Stream failure is non-fatal | `api.ts:1672-1675` | Degrades to the `messages.list` fetch path; send/render never depend on the stream |
| Idempotency key on counter-offers | `api.ts` `deals.counter` | A double-submit cannot create two counter-offers |
| Shipment transitions guarded server-side | `DealController:190-217` | Explicit 409s (`SHIPMENT_ALREADY_SHIPPED`, `SHIPMENT_NOT_SHIPPED`) rather than trusting client state |
| Dedicated stream regression test | `src/lib/__tests__/deal-message-stream.test.ts` | Asserts `onReconnect` fires on drop and **not** on first connect |

---

## 29. NOT CHECKED — Deal Room (law 5)

| Not verified | Why |
|-------------|-----|
| Runtime behaviour against a live server | Static code trace only; no live probe was run |
| Whether the SSE stream actually delivers frames end-to-end | Requires a running API + real deal |
| Whether reconnect backoff behaves correctly under a real proxy timeout | Unit test covers the callback, not the transport |
| Whether contract two-party signature ordering is enforced | `ContractService.sign` logic not traced |
| Escrow/payment release correctness behind `DealPaymentsTab` | Money path deliberately out of scope here |
| Whether `requireBrandWorkspace` guards pass at runtime | Annotations read, not executed |
| Whether `REJECTED` deliverables can be produced by any other actor | Only the brand path was traced |

---

## 30. Master Defect Register — all three audits

> Rebuilt after red-team verification. D-8 re-rated, D-10 struck, M-1…M-5 added.

| ID | Surface | Severity | Summary | Fix location |
|----|---------|----------|---------|--------------|
| **M-1** | Deal Room | 🔴 **HIGH** | **Escrow release is dead end-to-end** — `releasePayout` is defined in `api.ts:2720` and called from **nowhere** (`grep` returns only the definition). Brands can fund escrow and never release it. | `api.ts:2720` + a UI caller |
| **D-1** | Dashboard | 🔴 HIGH | Null runway renders as red "CRITICAL / 0d" on funded wallets | `dashboard-page.tsx:125,166` |
| **D-2** | Campaigns | 🔴 HIGH | List silently truncates at 100; pagination meta discarded, no pager | `api.ts:1189` + list UI |
| **D-5** | Campaigns | 🔴 HIGH | `collaboratorsCount` always 0, rendered as fact ("0/10") | `CampaignMapper.java:64` |
| **D-8** | Deal Room | 🟡 MEDIUM | Sidebar "Deals" → room missing the shipment control; misleading code comment. *(was CRITICAL)* | `brand-layout.tsx:100` |
| **D-9** | Deal Room | 🟡 MEDIUM | Deliverable **Reject** route exists but is unreachable; `REJECTED` badge renders | `web/dto/deliverable/BrandDeliverableDtos.java:39` |
| **M-2** | Deal Room | 🟡 MEDIUM | `GET /contracts?dealId=` — backend forwards `dealId` on the creator branch, **silently drops it** on the brand branch. A filter that is ignored is worse than one that is missing. | `ContractService.java:823` |
| **M-3** | Contracts | 🟡 MEDIUM | `CONTRACT_DEAL_ROOM` hardcoded demo map used **outside** the `isApiLive()` gate — in live mode every real contract falls through to `?? 'deal-1'` | `contracts-and-deliverables.tsx:98,913,921` |
| **D-3** | Campaigns | 🟡 MEDIUM | Progress bar always 0%; "Sort by Progress" dead — server-side root cause | `CampaignMapper.java:64` |
| **D-6** | Campaigns | 🟡 MEDIUM | `sortBy`/`sortOrder` accepted by backend, never sent | `api.ts:1190` |
| **M-4** | Contracts | 🔵 LOW | "Download PDF" disabled behind a comment claiming no endpoint exists — `api.ts:2180` has it and `deal-contract-tab.tsx:80` already calls it | contracts UI |
| **M-5** | Deal Room | 🔵 LOW | Brand has no open-dispute control; `POST /deals/{dealId}/disputes` (`DealController.java:177`) is called only from `creator-disputes.tsx:106` | brand deal UI |
| **D-4** | Campaigns | 🔵 LOW | Stale backend line-number citations in tracking JSDoc | `api.ts:3571+` |
| **D-7** | Campaigns | 🔵 LOW | `/brand/campaigns/:id/tracking` has no inbound link | `App.tsx:313` |
| ~~D-10~~ | ~~Deal Room~~ | 🚫 **STRUCK** | False alarm — files deleted in `c9210a4`; cross-worktree grep leak | — |

**Three shared root causes account for six defects:**

- `CampaignMetrics.empty()` at every call site → **D-3, D-5**
- Discarded `ApiResponse` meta envelope → **D-2, D-6**
- Money-path UI never wired to money-path APIs → **M-1**, and `DealPaymentsTab` importing zero API modules

### Suggested fix order

1. **M-1** — brands can lock funds in escrow with no way to release them. Highest blast radius on the register.
2. **D-1**, **D-2**, **D-5** — user-visible wrong numbers and silent data loss.
3. **M-2**, **M-3** — silently-ignored filter and a demo constant live in production paths.
4. Everything else.

---

*Audit produced by static code trace of live branch `fix/brand-audit-remediation`. No live server was probed.*

---

# PART 3 — RED-TEAM VERIFICATION (Kabir)

> **Method:** Independent re-trace from primary sources on branch `fix/brand-audit-remediation`. Every line citation in §23–§30 was opened and read. No claim in Part 3 was accepted on its author's word.
> **Date:** 2026-08-09

---

## 31. Verdict — **NEEDS_FIX**

Part 3's **backend line citations are flawless** — all 32 checked cite the correct route. But its **client-side reasoning is wrong in three places**, and one of the three defects it reports does not exist.

| Metric | Part 3 claims | Actually |
|--------|---------------|----------|
| Total APIs traced | 26 | §27's own rows number **1–27** — the summary contradicts its own table |
| Defects found | 3 (1 CRITICAL, 1 MEDIUM, 1 LOW) | **1 confirmed (MEDIUM), 1 overstated, 1 fabricated** |
| Defects missed | — | **5**, one of which outranks D-8 |

---

## 32. Per-defect ruling

### D-8 (§24) — **REAL BUG, but severity is WRONG and its evidence is 75% false**

**What verified clean:**

| Claim | Evidence | Result |
|---|---|---|
| Sidebar "Deals" → `/brand/deals` | `brand-layout.tsx:100` | exact |
| Inverted comment exists | `brand-layout.tsx:89` — "the actively-maintained Deal Room — not the older `/brand/chat` page" | exact |
| `deal-room-dashboard.tsx` renders only 3 tabs | `TabsTrigger` at `:587`, `:590`, `:593` = `overview` / `messages` / `history` | exact |
| That room calls exactly 7 APIs | `deals.list :254`, `.accept :364`, `.counter :385`, `.reject :414`; `messages.list :282`, `.markRead :285`, `.send :342` | exact |
| `brand-chat.tsx` renders the four panels | `DealContractTab :2308`, `DealDeliverablesTab :2378/:2388`, `DealPaymentsTab :2400`, `ShipmentForm :2433` | exact |
| Command Bar points at `/brand/chat` | `command-bar.tsx:62` | exact |

**What is FALSE — the "only reachable via Cmd-K" premise:**

`/brand/chat` has **five** in-app entry points, four of them from sidebar-reachable pages:

```
src/components/brand/contracts/contracts-and-deliverables.tsx:921
  <Link to={`/brand/chat?deal=${...}&tab=contract`}>  "Open in Deal Room"   <- sidebar > Manage > Contracts
src/components/brand/contracts/contracts-and-deliverables.tsx:913
  <Link to={`/brand/chat?deal=${...}&tab=messages`}>  "Message"
src/pages/brand-disputes.tsx:146
  <Link to={`/brand/chat?deal=${dispute.collaborationId}`}>  "View deal room"  <- sidebar > Manage > Disputes
src/components/brand/discover/creator-discovery.tsx:536
  navigate('/brand/chat')   after a proposal is sent                          <- sidebar > Main > Creators
src/pages/brand-campaign-detail.tsx:1225
  navigate(`/brand/chat?creator=${bid.creator.id}`)
```

One of these is a **primary-styled button labelled "Open in Deal Room"** that deep-links straight to `&tab=contract`. "Undiscoverable" does not survive contact with the code.

**What is FALSE — the "brand cannot do these four things" list.** Verified one by one:

| Claimed unreachable action | Reality |
|---|---|
| Sign a contract | **FALSE** — `/brand/contracts` (sidebar > Manage) signs it live: `contracts-and-deliverables.tsx:690` `api.contracts.sign('brand', selectedContract.id, {...})` |
| Review / approve a deliverable | **FALSE** — same page: `:632` `api.deliverables.approve(...)`, `:658` `api.deliverables.requestRevision(...)` |
| Release a payment | **FALSE — and worse than claimed.** `DealPaymentsTab` imports **zero** API modules (`deal-payments-tab.tsx:1-9`). Every milestone status is derived from props (`:48`). Payment release is not reachable from `/brand/chat` **or anywhere else** — see §33 M-1 |
| Mark a product as shipped | **TRUE** — `brand-chat.tsx:968` `api.shipments.markShipped(...)` is the sole caller repo-wide |

The headline's supporting evidence holds for **1 of 4** actions.

**Severity — argued both ways:**

*For CRITICAL:* the sidebar is primary navigation. A brand who never presses Cmd-K and never wanders into Manage > Contracts sees a Deal Room with no contract, deliverable, payment or shipment surface. The in-code comment actively misdirects the next maintainer. Shipment-marking is genuinely stranded — a brand who ships product cannot record it from anywhere the sidebar reaches, and the creator's `confirm-receipt` step is blocked behind it, so the whole shipment state machine stalls at `ADDRESS_PROVIDED`.

*Against CRITICAL:* CRITICAL means the lifecycle is unreachable. It is not. Two of the four actions are first-class citizens of a sidebar page (`/brand/contracts` — labelled "Contracts", grouped under Manage, live-wired at `isApiLive()` `:504`). A third does not exist in either room, so pointing the sidebar at `/brand/chat` would not restore it. Five in-app links land on `/brand/chat`. Nothing is unreachable except shipment-marking — one action, not "the deal lifecycle". A CRITICAL that dissolves to one stranded button on inspection burns the severity scale for the finding that actually deserves it (M-1).

**Ruling: real defect, correct diagnosis of the routing/comment split, wrong severity. Re-rate CRITICAL -> MEDIUM.** Part 1 §4b's original LOW was too low; CRITICAL overcorrects well past the evidence.

### D-9 (§25) — **REAL BUG. Confirmed on every line.**

| Assertion | Verified at |
|---|---|
| Backend reject route exists | `BrandDeliverableController.java:64` `@PostMapping("/{deliverableId}/reject")`, javadoc `:63` "B6 — the missing route for `BrandDeliverableService#reject`" |
| Safety-review javadoc treats reject as first-class | `BrandDeliverableController.java:76-77` — "never blocks `#approve`/`#reject`/`#revise`" |
| FE client has no `deliverables.reject` | `src/lib/api.ts` `deliverables` facade exposes `list`, `getDetail`, `submit`, `approve`, `requestRevision`, `getSafetyReview` — **no reject**. Repo-wide grep for any deliverable-reject caller: 0 hits |
| DTO has no `canReject` | `BrandDeliverableDtos.java:39-40` — `boolean canApprove, boolean canRequestRevision) {}` and the record ends there |
| Viewer renders REJECTED but no reject button | `DeliverableViewer.tsx:32` REJECTED badge; the action block at `:352` is gated on `(deliverable.canApprove \|\| deliverable.canRequestRevision)` only; buttons at `:354` / `:364` |

One correction: the DTO path in §25 is wrong. It is `influora-api/src/main/java/com/influora/web/dto/deliverable/BrandDeliverableDtos.java`, **not** `domain/dto/`. Line numbers are right.

### D-10 (§26) — **FALSE ALARM. The files do not exist.**

```
$ git ls-files src/components/brand/deal-room/
deal-contract-generate.tsx   deal-contract-tab.tsx   deal-deliverables-tab.tsx
deal-payments-tab.tsx        deal-room-step-progress.tsx
proposal-form.tsx            shipment-form.tsx
```

`bid-card.tsx` and `campaign-brief-card.tsx` are absent from the working tree **and** the git index. They were deleted in commit **`c9210a4`** ("fix: proof-os remediation loop (F-0038–F-0046)"):

```
$ git show --name-status c9210a4 | grep -E 'bid-card|campaign-brief'
D  src/components/brand/deal-room/bid-card.tsx
D  src/components/brand/deal-room/campaign-brief-card.tsx
```

`find src -iname "*bid-card*" -o -iname "*campaign-brief*"` returns nothing. The only surviving copies sit under `.claude/worktrees/` — sibling agent worktrees, untracked since `92fa095`, and not part of this branch.

Part 3 described them as "complete components in the deal-room directory that nothing renders". They are not in the directory. It even attached a deletion caveat to files that were already deleted. This is the grep-leaks-across-worktrees failure mode already on record for this repo. **D-10 must be struck from the register.**

---

## 33. MISSED — five defects Part 3 did not catch

### M-1 — HIGH — Escrow release is dead code end-to-end (outranks D-8)

The money-terminal step of the deal lifecycle has a backend route, a typed client method, and **no caller anywhere**:

| Layer | State |
|---|---|
| Backend | `EscrowController.java:101` `@PostMapping("/release")` under `@RequestMapping("/wallet/escrow")` — exists |
| FE client | `api.ts:2720` `releasePayout: (milestoneId) => http.request('POST', '/wallet/escrow/release', { body: { milestoneId } })` — exists |
| Callers | `grep -rn "releasePayout" src/` -> **1 hit: the definition itself** |
| UI | `DealPaymentsTab` is display-only. Its entire import list (`deal-payments-tab.tsx:1-9`) is `Link`, lucide icons, `Badge`, `Button`, `Card`, `ScrollArea`, `Separator`, `formatINR`, and a type. Milestone status is computed from the `deliverablesDone` prop at `:48` |

A brand can fund escrow (`FundEscrowButton.tsx` — "the ONLY way to fund escrow") and can never release it. Money goes in; nothing takes it out. Part 3 listed payment release among things "fully built, fully wired to working endpoints, and reachable only by pressing Cmd-K" — it is neither wired nor reachable. §29 excused the money path as "deliberately out of scope", but §24 still made a load-bearing claim about it. Out-of-scope is not a licence to assert.

### M-2 — MEDIUM — `GET /contracts?dealId=` silently ignores `dealId` for brands

`ContractController.java:46-55`:

```java
@GetMapping
public ApiResponse<List<ContractResponse>> list(
        @AuthenticationPrincipal AuthPrincipal principal,
        @RequestParam(required = false) String dealId) {
    if (principal.getUserType() == UserType.CREATOR) {
        return ApiResponse.ok(contractService.listForCreator(principal, dealId));
    }
    var workspace = brandContext.requireBrandWorkspace(principal);
    return ApiResponse.ok(contractService.listForBrand(principal, workspace.getId()));
}
```

The creator branch forwards `dealId`; the brand branch drops it. `ContractService.java:823` is `listForBrand(AuthPrincipal principal, String workspaceId)` — no `dealId` overload exists (`:843` `listForCreator` does take one). §27c row 11 asserts "Contract list **for a deal** — `contracts.list(role, dealId)` — OK". For `role='brand'` the filter is silently discarded and the entire workspace's contracts come back. `api.ts:2120` passes `query: { dealId }` believing it filters. A no-op query param is worse than an absent one — the caller trusts a filter that never ran.

### M-3 — MEDIUM — Contracts -> Deal Room deep link uses a hardcoded demo map in live mode

`contracts-and-deliverables.tsx:97-102`:

```ts
/** Demo mapping: Contracts inbox -> Deal Room deep link */
const CONTRACT_DEAL_ROOM: Record<string, string> = {
  'contract-1': 'deal-1', 'contract-2': 'deal-3', 'contract-3': 'deal-4',
};
```

Consumed at `:913` and `:921` as `CONTRACT_DEAL_ROOM[selectedContract.id] ?? 'deal-1'` — **outside** the `isApiLive()` gate at `:504`. In live mode every real contract id misses the map, so the primary "Open in Deal Room" button sends the brand to a fabricated `deal-1`. This is simultaneously the strongest counter-evidence to D-8 and a live-mode bug in its own right.

### M-4 — LOW — "Download PDF" is disabled by a comment that is factually wrong

`contracts-and-deliverables.tsx:903-905`:

> "No PDF-export endpoint exists in src/lib/api.ts (contracts facade only has list/get/generate/sign) — left as a non-functional stub rather than faking a download."

`api.ts:2180` defines `pdfDownloadUrl: (role, id) => http.request('GET', '/contracts/${id}/pdf-download-url', { role })`, backed by `ContractController.java:114`, and `deal-contract-tab.tsx:80` already calls it successfully. The button at `:906` is hardcoded `disabled` on a false premise. Part 3 cited both halves of this contradiction (§27c row 15 and §28) without noticing they collide.

### M-5 — LOW — Brand has no open-dispute control

`POST /deals/{dealId}/disputes` (`DealController.java:177`, "either party may open") has a client method at `api.ts:4377` and exactly one caller: `creator-disputes.tsx:106` via `api.creatorDisputes.open(...)`. `api.ts:4392-4393` documents the design intent that "disputes are opened from the deal room". Neither brand Deal Room opens one. `brand-disputes.tsx` only lists and links out. §27 omits this route entirely.

---

## 34. Inaccurate claims in §27's trace

The backend citations are perfect — all 32 verified exact:

- `DealController.java` — 65, 72, 78, 86, 94, 104, 114, 134, 151, 160, 170, 190, 199, 208, 217 (15/15)
- `ContractController.java` — 38, 46, 58, 68, 78, 114 (6/6)
- `BrandDeliverableController.java` — 39, 46, 53, 64, 81 (5/5)
- `CreatorDeliverableController.java` — 78 (1/1)
- `CreatorController.java` — 153, 176 (2/2)

The **attribution** is not. §27 is titled "API-by-API Trace — Deal Room", but 8 of its rows belong to other surfaces:

| Row | §27 says | Actual caller |
|---|---|---|
| 3 `deals.create` | Deal Room | `creator-discovery.tsx:504` — Discover page |
| 11 `contracts.list` | Deal Room | `contracts-and-deliverables.tsx:552` — Contracts page only; **neither** deal room calls it |
| 14 `contracts.sign` | "`/brand/chat` full room" | `contracts-and-deliverables.tsx:690` — Contracts page only. **`brand-chat.tsx` never calls `contracts.sign`.** The Deal Room's `deal-contract-tab.tsx:106` reaches it indirectly via `lib/contract-generator.ts:214` |
| 21 `deliverables.submit` | Deal Room | creator-side route; no brand caller |
| 22 `shipments.get` | Deal Room | `creator-chat.tsx:1088` only — the **brand** room never reads shipment state back from the API |
| 23 `shipments.submitAddress` | Deal Room | `creator-chat.tsx:1174` |
| 25 `shipments.confirmReceipt` | Deal Room | `creator-chat.tsx:1219` |
| 26–27 `creators.get` / `creators.invite` | Deal Room | Discover page |

`brand-chat.tsx`'s real API surface is **14 calls**, not 26: `deals.list :742`, `deals.get :791`, `deals.accept :1335`, `deals.counter :1278`; `messages.list :1018`, `.markRead :1021`, `.stream :1146`, `.send :1206`; `deliverables.list :1034`, `.approve :1075`, `.requestRevision :1087`; `contracts.get :858`, `.generate :881`; `shipments.markShipped :968` — plus `contracts.pdfDownloadUrl` via `deal-contract-tab.tsx:80`. Notably it never calls `deals.create` or `deals.reject`, both of which the sidebar room *does* expose (`deal-room-dashboard.tsx:414`).

**Missing from §27 entirely:** `POST /deals/{dealId}/disputes` (`DealController.java:177`) — a deal-scoped route on the very controller §27a and §27e trace. That is a 28th deal API, not counted.

**Arithmetic:** §23 says "Total APIs traced 26 / Endpoints existing 26/26". §27's rows number 1–27 (27f ends at #27), plus 3 unnumbered dash-rows. The count and the table disagree.

**Asserted without evidence:** §23's "TypeScript typecheck — 0 errors (`npx tsc --noEmit`)". No run is recorded and this verification did not re-run it. Treat as unverified.

**§28 spot-check — all clean.** The CR-31 reconnect rationale is real (`api.ts:1678-1695`), `messages.stream` is fetch-based with `AbortController` (`:1697-1699`), `onReconnect` refetches both thread and deal status (`brand-chat.tsx:1183-1184`), and `src/lib/__tests__/deal-message-stream.test.ts` exists.

---

## 35. Corrected Deal Room register

| ID | Severity | Status after verification |
|----|----------|---------------------------|
| **M-1** | HIGH | **NEW** — escrow release: route + client exist, zero callers; `DealPaymentsTab` is display-only. Funds can be locked and never released |
| **D-8** | MEDIUM (was CRITICAL) | Real routing/comment defect; "only via Cmd-K" is false (5 in-app links) and 3 of its 4 "cannot do" claims are false |
| **D-9** | MEDIUM | **CONFIRMED** on every line; only correction is the DTO path (`web/dto/deliverable/`, not `domain/dto/`) |
| **M-2** | MEDIUM | **NEW** — `dealId` silently ignored for brands on `GET /contracts` |
| **M-3** | MEDIUM | **NEW** — `CONTRACT_DEAL_ROOM` demo map runs in live mode; deep link lands on a fabricated `deal-1` |
| **M-4** | LOW | **NEW** — Download PDF disabled by a false comment; endpoint + client both exist and work |
| **M-5** | LOW | **NEW** — brand has no open-dispute control despite `POST /deals/{id}/disputes` |
| **D-10** | **STRUCK** | Files deleted in `c9210a4`; survive only in untracked `.claude/worktrees/` |

**Recommended order of work:** M-1 (money can't come out) > D-8 Option A + comment fix (cheap, removes the misdirection) > M-3 (live-mode broken link) > D-9 > M-2 > M-4/M-5.

---

## 36. What this verification did NOT check

| Not verified | Why |
|---|---|
| Runtime behaviour of any endpoint | Static trace only; no server was probed |
| `npx tsc --noEmit` — §23's "0 errors" | Not re-run; recorded as unverified rather than inherited |
| Two-party signature ordering in `ContractService.sign` | Same gap Part 3 declared; not closed here |
| Whether `requireBrandWorkspace` denies correctly at runtime | Annotations read, not executed |
| Whether `EscrowController.release` itself is correct | Only its reachability from the client was traced |
| Creator-side deal room completeness | Brand surface only |
| Parts 1 and 2 | Verified separately; out of scope |

---

*Verified by independent primary-source re-trace on `fix/brand-audit-remediation`. Backend line citations: 32/32 correct. Client-side load-bearing claims: 3 of 6 false. One reported defect does not exist; five unreported ones do.*

**Signed: Kabir — Red-Team / Offensive Security Lead**

---
---

# PART 4 — Find Creator · Creator Profile · Chat & Message API Audit

> **Scope:** Discover (Find Creator), Creator Profile, the invite/offer → chat handoff, `/brand/messages`, and the message API.
> **Method:** Static code trace **+ a live test-suite run** (`vitest`) — the first audit in this file with an executable oracle on its subject.
> **Branch:** `fix/brand-audit-remediation` · **Date:** 2026-08-09
> **Method note:** every repo-wide search in this pass was scoped to git-tracked files only, after the `.claude/worktrees/` leak that produced the struck D-10.

---

## 37. Audit Summary

| Metric | Result |
|--------|--------|
| Creator/discovery APIs traced | **5** (search, get, save/toggle, invite, + campaigns list for the picker) |
| Message APIs traced | **4** (list, send, markRead, stream) |
| Endpoints existing in backend | **9 / 9** ✅ |
| **Test oracle** | 🔴 **2 tests FAILING** (`creator-discovery-redirect.test.tsx`) |
| Other suites in scope | ✅ 3 files / 22 tests passing |
| **Real defects found** | **4** (1 HIGH **oracle-proved**, 2 MEDIUM, 1 LOW) |

**Verdict: every endpoint exists and is correctly wired. But the invite → chat handoff is BROKEN and the repo's own test suite already proves it — this is the first defect in this document that is `proved`, not `believed`.**

---

## 38. 🔴 DEFECT D-11 — Invite/offer redirect drops the deal ID (HIGH · **ORACLE-PROVED**)

| Field | Detail |
|-------|--------|
| **Severity** | 🔴 HIGH — breaks the core Discover → Deal Room handoff |
| **Where** | `src/components/brand/discover/creator-discovery.tsx:504, 522, 536` |
| **Evidence class** | **`proved`** — a failing gate, not a model's opinion |

### The oracle output

```
npx vitest run src/components/brand/discover/creator-discovery-redirect.test.tsx

× sends a priced offer, then redirects to THAT creator's deal room
  → expected '/brand/chat' to match /[?&]deal=deal_1(&|$)/
× sends an unpriced invite, then redirects to THAT creator's deal room
  → expected '/brand/chat' to match /[?&]deal=col_1(&|$)/

Test Files  1 failed | 3 passed (4)
      Tests  2 failed | 22 passed (24)
```

Both branches fail — priced offer **and** unpriced invite.

### Root cause — the ID is returned, then thrown away

| Line | Code | Problem |
|------|------|---------|
| `:504` | `await api.deals.create({…})` | Returns `Deal` (carries `id`) — **return value discarded** |
| `:522` | `await api.creators.invite(…)` | Returns `{ collaborationId }` — **return value discarded** |
| `:536` | `navigate('/brand/chat')` | Bare path, no `?deal=` |

Both API calls hand back exactly the identifier the redirect needs, and both are dropped on the floor.

### The receiving end is already built

This is not a missing feature — the destination fully supports it:

- `brand-chat.tsx:630` — `const dealIdFromUrl = searchParams.get('deal')`
- `brand-chat.tsx:718` — selecting a deal writes `next.set('deal', dealId)` back to the URL

So deep-linking into a specific deal room works. **Only Discover's redirect fails to use it.**

### What the brand experiences

A brand searches creators, picks one, composes an offer, sends it — and lands on the **generic chat page** with no conversation selected. They must then find the creator they just contacted in the deal list by hand. On an account with many deals, the conversation they just created is indistinguishable from the rest.

### Fix

Capture the returned ID and append it:

```ts
let dealParam = '';
if (proposalData.budget > 0) {
  const deal = await api.deals.create({ … });
  dealParam = `?deal=${deal.id}`;
  toast({ title: 'Offer sent', … });
} else {
  const { collaborationId } = await api.creators.invite(inviteCreator.id, selectedCampaign, inviteMessage || undefined);
  dealParam = `?deal=${collaborationId}`;
  toast({ title: 'Invitation sent', … });
}
…
navigate(`/brand/chat${dealParam}`);
```

**Verification is free:** the two failing tests turn green. No new test needed — the spec was written before the code drifted.

---

## 39. 🟡 DEFECT D-12 — Ten dead controls on `/brand/messages` (MEDIUM)

| Field | Detail |
|-------|--------|
| **Severity** | 🟡 MEDIUM — ten enabled controls that do nothing |
| **Where** | `src/pages/brand-messages.tsx` |

Every control below renders **fully enabled**, with tooltips and hover states, and has **no `onClick` handler at all**:

| Control | Line | Presentation |
|---------|------|--------------|
| Voice Call | `:631` | Icon button + "Voice Call" tooltip |
| Video Call | `:639` | Icon button + "Video Call" tooltip |
| Pin conversation | `:652` | Dropdown item |
| Mute notifications | `:655` | Dropdown item |
| Archive | `:658` | Dropdown item |
| Report | `:662` | Dropdown item |
| Delete conversation | `:665` | Dropdown item, styled destructive |
| Attach file | `:902` | Icon button |
| Attach image | `:910` | Icon button |
| Emoji picker | `:934` | Icon button |

The **only** wired control in the composer is Send (`onClick={handleSendMessage}`).

Voice/Video call have no backend at all — there is no calling endpoint anywhere in the API. "Delete conversation" is the worst of these: it is styled as a destructive action, which sets an expectation that data will be removed.

**Fix:** either wire them or remove them. If any are roadmap items, `disabled` them with a tooltip explaining why — the same honest-state discipline the wallet DTO applies server-side.

---

## 40. 🟡 DEFECT D-13 — `/brand/messages` has no realtime (MEDIUM)

| Surface | Realtime | Evidence |
|---------|----------|----------|
| `/brand/chat` | ✅ SSE stream + reconnect + gap refetch | `messages.stream` wired (Part 3 §28) |
| `/brand/messages` | ❌ **none** | Only `deals.list`, `messages.list`, `markRead`, `send` |

`brand-messages.tsx` never calls `messages.stream`, and there is no polling interval. Incoming messages appear **only** on page load or when the brand switches conversation.

The sidebar's "Messages" item is the discoverable messaging surface; `/brand/chat` is the one with realtime. A brand sitting on `/brand/messages` will not see a creator's reply arrive.

**Fix:** wire `messages.stream` with the same `onReconnect` → refetch pattern already proven in `brand-chat.tsx:1183-1184`.

---

## 41. 🔵 DEFECT D-14 — Four backend creator endpoints have no caller (LOW)

| Endpoint | Backend | FE caller |
|----------|---------|-----------|
| `GET /creators/featured` | `CreatorController.java:127` | ❌ none |
| `POST /creators/suggestions` | `:136` | ❌ none |
| `GET /creators/{username}/similar` | `:144` | ❌ none |
| `GET /creators/profile/{usernameOrId}` | `:159` | ❌ none (FE uses `GET /creators/{id}` instead) |

`/similar` is the notable one — "creators like this one" is a discovery feature built server-side and never surfaced on the profile page. Not a bug; unrealised capability worth a product decision.

---

## 42. API-by-API Trace

### 42a. Find Creator — Discover (`/brand/discover`)

| # | UI Action | FE Call | HTTP | Endpoint | Backend | Status |
|---|-----------|---------|------|----------|---------|--------|
| 1 | Search + filters | `creators.search(params)` | GET | `/creators` | `CreatorController:40` | ✅ |
| 2 | Bookmark / unbookmark | `creators.toggleSaved(id, saved)` | POST | `/creators/{id}/save` | `:166` | ✅ |
| 3 | Send invite (unpriced) | `creators.invite(id, campaignId, msg)` | POST | `/creators/{id}/invite` | `:176` | ⚠️ **D-11** — response discarded |
| 4 | Send offer (priced) | `deals.create({…})` | POST | `/deals` | `DealController:78` | ⚠️ **D-11** — response discarded |
| 5 | Campaign picker in invite modal | `campaigns.list({})` | GET | `/campaigns` | — | ⚠️ inherits **D-2** truncation |

**Filter parameters accepted by the backend** (`CreatorController:40-50`): `q`, `platforms`, `city`, `verticals`, `categories`, `languages`, `minFollowers`, `maxFollowers` — matching the 8 filter types documented in Part 1 §5c.

### 42b. Creator Profile (`/brand/creators/:id`)

| # | UI Action | FE Call | HTTP | Endpoint | Backend | Status |
|---|-----------|---------|------|----------|---------|--------|
| 6 | Load profile | `creators.get(id)` | GET | `/creators/{creatorId}` | `:153` | ✅ |
| 7 | Invite from profile | `creators.invite(…)` | POST | `/creators/{id}/invite` | `:176` | ✅ |
| 8 | Campaign picker | `campaigns.list({})` | GET | `/campaigns` | — | ⚠️ inherits **D-2** |

### 42c. Message API — both surfaces

| # | UI Action | FE Call | HTTP | Endpoint | Backend | `/brand/chat` | `/brand/messages` |
|---|-----------|---------|------|----------|---------|:---:|:---:|
| 9 | Load conversation list | `deals.list('brand')` | GET | `/deals` | `DealController:65` | ✅ | ✅ |
| 10 | Load thread | `messages.list('brand', dealId)` | GET | `/deals/{dealId}/messages` | `:114` | ✅ | ✅ |
| 11 | Send message | `messages.send('brand', dealId, content)` | POST | `/deals/{dealId}/messages` | `:151` | ✅ | ✅ |
| 12 | Mark read | `messages.markRead('brand', dealId)` | POST | `/deals/{dealId}/messages/read` | `:160` | ✅ | ✅ |
| 13 | Realtime updates | `messages.stream(…)` | GET (SSE) | `/deals/{dealId}/messages/stream` | `:134` | ✅ | ❌ **D-13** |

---

## 43. What Is Done Well

| Practice | Where | Why it matters |
|----------|-------|----------------|
| `creators.search` consumes the meta envelope | `api.ts` — uses `requestWithMeta`, not `request` | **The correct counterexample to D-2.** Discover paginates properly; campaigns does not, despite the same envelope |
| Priced vs unpriced branch is explicit and documented | `creator-discovery.tsx:495-502` | Both paths write the same `(campaignId, creatorId)` Collaboration row and 409 against each other; exactly one fires per submit |
| Honest 409 error message | `:538-542` | Names the real cause ("this creator has been approached before") instead of a generic retry prompt |
| Local→API field translation | `:509-512` | Local shape uses `count`; the contract is `qty` — mapped explicitly rather than hoping they match |
| A test existed for the broken behaviour | `creator-discovery-redirect.test.tsx` | The spec was written correctly and the code drifted — the failure was already captured, just not acted on |

---

## 44. NOT CHECKED — Part 4 (law 5)

| Not verified | Why |
|-------------|-----|
| Runtime behaviour against a live server | Static trace + unit tests only; no live probe |
| Whether search filters return correctly filtered rows | `CreatorDiscoveryService.search` query logic not traced |
| Whether the 409 uniqueness constraint fires as documented | Requires a live duplicate-invite attempt |
| Whether `toggleSaved` persists across sessions | Not traced past the endpoint |
| Whether the 2 failing tests fail for the stated reason only | The assertion is unambiguous, but the fix was not applied and re-run |
| Creator-side chat (`creator-chat.tsx`) | Out of scope — this document audits the brand surface |

---

## 45. Master Defect Register — all four audits

| ID | Surface | Severity | Evidence | Summary |
|----|---------|----------|----------|---------|
| **D-11** | Discover → Chat | 🔴 HIGH | **proved** (2 failing tests) | Invite/offer redirect drops the deal ID; lands on generic chat |
| **M-1** | Deal Room | 🔴 HIGH | believed | Escrow release dead end-to-end — `releasePayout` never called |
| **D-1** | Dashboard | 🔴 HIGH | believed | Null runway renders as red "CRITICAL / 0d" on funded wallets |
| **D-2** | Campaigns | 🔴 HIGH | believed | List truncates at 100; meta discarded, no pager |
| **D-5** | Campaigns | 🔴 HIGH | believed | `collaboratorsCount` always 0, rendered as fact |
| **D-12** | Messages | 🟡 MEDIUM | believed | 10 enabled controls with no handler (incl. destructive "Delete") |
| **D-13** | Messages | 🟡 MEDIUM | believed | No realtime — `/brand/messages` never opens the SSE stream |
| **D-8** | Deal Room | 🟡 MEDIUM | believed | Sidebar "Deals" → room missing the shipment control |
| **D-9** | Deal Room | 🟡 MEDIUM | believed | Deliverable **Reject** route unreachable; `REJECTED` badge renders |
| **M-2** | Deal Room | 🟡 MEDIUM | believed | `GET /contracts?dealId=` silently drops the filter for brands |
| **M-3** | Contracts | 🟡 MEDIUM | believed | Demo constant `?? 'deal-1'` used outside the `isApiLive()` gate |
| **D-3** | Campaigns | 🟡 MEDIUM | believed | Progress bar always 0%; "Sort by Progress" dead |
| **D-6** | Campaigns | 🟡 MEDIUM | believed | `sortBy`/`sortOrder` accepted by backend, never sent |
| **D-14** | Discover | 🔵 LOW | believed | 4 backend creator endpoints with no caller (incl. `/similar`) |
| **M-4** | Contracts | 🔵 LOW | believed | Download PDF disabled behind an outdated comment |
| **M-5** | Deal Room | 🔵 LOW | believed | Brand has no open-dispute control |
| **D-4** | Campaigns | 🔵 LOW | believed | Stale backend line-number citations in tracking JSDoc |
| **D-7** | Campaigns | 🔵 LOW | believed | `/brand/campaigns/:id/tracking` has no inbound link |
| ~~D-10~~ | ~~Deal Room~~ | 🚫 STRUCK | — | False alarm — cross-worktree grep leak |

**18 live defects · 5 HIGH · 1 oracle-proved.**

### Recurring pattern across all four audits

Not one defect in this document is a broken endpoint. **Every backend route traced exists, and every line citation checked out.** The failures are all on the client side, in four repeating shapes:

1. **A response is returned and discarded** — D-11 (deal ID), D-2/D-6 (pagination meta)
2. **A zero stands in for "unknown"** — D-1 (runway), D-3/D-5 (`CampaignMetrics.empty()`)
3. **A control renders without a handler** — D-12 (10 controls), D-3 (sort), M-4 (PDF)
4. **A working API has no caller** — M-1 (escrow release), D-9 (reject), D-14 (4 endpoints), M-5 (disputes)

A build gate cannot catch any of these: they typecheck, they lint, and in mock mode they demo perfectly.

---

*Audit produced by static code trace + `vitest` run on branch `fix/brand-audit-remediation`. No live server was probed.*

---
---

# PART 5 — Payment & Wallet API Audit (Brand + Creator)

> **Scope:** the full money path — wallet, top-up, escrow (fund/release/refund/payout), creator withdrawal, payout methods, platform fee.
> **Method:** Static code trace + **bidirectional caller census** (for every endpoint: does it exist, *and* does anything call it).
> **Branch:** `fix/brand-audit-remediation` · **Date:** 2026-08-09
> **Method note:** all searches scoped to git-tracked files. Every "zero callers" claim was re-run with a looser pattern before being reported — one candidate defect (`brandPlatformFee`) was discarded this way.

---

## 46. Audit Summary

| Metric | Result |
|--------|--------|
| Payment endpoints in backend | **17** (WalletController 8 · EscrowController 6 · PlatformFee 2 · +1) |
| Endpoints with a FE client method | **13 / 17** |
| Endpoints with an actual caller | **12 / 17** |
| TypeScript typecheck | ✅ **0 errors** (`npx tsc --noEmit`, exit 0) |
| **Real defects found** | **2** (1 CRITICAL, 1 MEDIUM) |

**Verdict: every payment endpoint that is called works and is correctly wired. The critical finding is the reverse — money can enter escrow but there is no client path to get it out, in any direction.**

---

## 47. ~~🔴 DEFECT P-1 — The escrow OUTBOUND path is unreachable (CRITICAL)~~ → **DOWNGRADED TO LOW · headline was a FALSE ALARM**

> 🚫 **CORRECTED BY RED-TEAM VERIFICATION — see §53-58.** The claim "money cannot leave escrow" is **wrong**. It was produced by a census that searched only `src/lib/api.ts`. **This repo has two API layers**, and the second one — `src/lib/meera-api.ts` — contains the client this section declared missing. §48 names that second layer three paragraphs later; the census did not consult it.

### What was actually wrong with the census

| Census row | Claim | Verified reality |
|-----------|-------|------------------|
| `GET /wallet/escrow/{id}` | "no client method" | ❌ **FALSE** — `meera-api.ts:570` `getEscrowStatus` → `GET /wallet/escrow/${escrowHoldId}` (`:583`), live caller at `useEscrowFund.ts:377` |
| `POST /wallet/escrow/release` | "0 callers ⇒ no way to release" | ❌ **FALSE conclusion.** The *standalone endpoint* genuinely has 0 callers — but it is not the release path |
| `POST /wallet/escrow/refund` | "no client method" | ⚠️ Reachable **admin-side**: `DisputeService.java:255/259` via `POST /admin/disputes/:id/resolve`, wired at `useDisputeResolve.ts:48` → `DisputeList.tsx:177` |

### The release path that actually exists

Release is wired through **deliverable approval**, with four live call sites:

```
brand-chat.tsx:1075  →  api.ts:2301-2303  →  BrandDeliverableController.java:46
                     →  BrandDeliverableService.java:117  escrowService.tryReleaseOnApproval(…)
                     →  EscrowService.java:632            escrowBackend.release(payeeUserId = collaboration.getCreatorId())
```

Real money, real payee. §47 originally *proposed the deliverable-approval flow as the fix* — that is precisely where the release already lives.

### The self-contradiction this section should have caught

§49b of this same audit calls the creator withdrawal path **"the most completely wired money flow in the application."** That is an escrow exit. A section asserting money has no exit, three pages from a section praising the exit, should never have shipped at CRITICAL.

**Re-rated 🔴 CRITICAL → 🔵 LOW.** What genuinely remains: `POST /wallet/escrow/release` and `POST /wallet/escrow/payout` are endpoints with no client caller — dead surface worth removing or wiring, not a custody failure.

---

## 47a. 🔴 DEFECT P-1′ — Meera-funded escrow holds can be released by **neither** path (HIGH)

> Found by red-team verification. Part 5 held **both halves of this and never joined them.**

| Field | Detail |
|-------|--------|
| **Severity** | 🔴 HIGH — a real, bounded class of funds with no release path |
| **Where** | `MeeraWorkspace.tsx:77` · `EscrowService.java:200` · `ContractService.java:302` |

Both release routes are **milestone-keyed**:

- `tryReleaseOnApproval(…)` releases against a `PaymentMilestone`
- `POST /wallet/escrow/release` takes a `milestoneId` in its body (`api.ts:2722`)

But `PaymentMilestone` rows are created in exactly one place — `ContractService.java:302`, i.e. **only when a contract is generated**.

Meera funds escrow at **campaign level with no milestone** (`MeeraWorkspace.tsx:77`), and the backend explicitly supports that shape (`EscrowService.java:200`).

**Therefore:** escrow funded through Meera before any contract exists has no milestone, and neither release path can address it. Those funds sit in a hold that nothing can release.

This is the true finding the original P-1 was groping toward — narrower than "escrow has no exit", but real, and it lands exactly where P-2 says funding actually happens.

**Fix:** either require a milestone at fund time, or add a release path keyed on `escrowHoldId` rather than `milestoneId`.

---

## 48. 🟡 DEFECT P-2 — Escrow funding is reachable only through the Meera AI chat (MEDIUM)

| Field | Detail |
|-------|--------|
| **Severity** | 🟡 MEDIUM — a core money action has no conventional UI entry point |
| **Where** | callers of `payments.fundEscrow` |

Every caller of `fundEscrow` traces back to the AI assistant:

| File | Role |
|------|------|
| `src/components/feature/meera/MeeraWorkspace.tsx` | the only UI component |
| `src/components/feature/meera/FundEscrowButton.test.tsx` | test |
| `src/hooks/useEscrowFund.ts` / `.test.ts` | hook + test |
| `src/lib/meera-api.ts` | Meera's API layer |

**`brand-wallet.tsx` never calls it.** Its payment surface is `get`, `topUp`, `transactions`, `escrowList` — it can show escrow holds but cannot create one. No deal-room component calls it either.

A brand who does not use Meera has no path to fund escrow from the wallet page or the deal room. Combined with **P-1**, the escrow lifecycle is: fund via AI chat only → then no exit at all.

**Fix:** surface funding from the wallet page and/or the deal room, alongside the release control from P-1.

---

## 49. API-by-API Trace — Payments

### 49a. Brand wallet (`/brand/wallet`) — 4 APIs

| # | UI Action | FE Call | HTTP | Endpoint | Backend | Status |
|---|-----------|---------|------|----------|---------|--------|
| 1 | Balance card | `wallet.get('brand')` | GET | `/wallet` | `WalletController:72` | ✅ |
| 2 | Add funds (Razorpay) | `wallet.topUp(body, idempotencyKey)` | POST | `/wallet/topup` | `:94` | ✅ idempotency-keyed |
| 3 | Transaction ledger | `wallet.transactions('brand', page, limit)` | GET | `/wallet/transactions` | `:135` | ✅ paginated |
| 4 | Escrow holds list | `wallet.escrowList(page, limit)` | GET | `/wallet/escrow` | `EscrowController:58` | ✅ paginated |

### 49b. Creator wallet (`/creator/wallet`) — 7 APIs

| # | UI Action | FE Call | HTTP | Endpoint | Backend | Status |
|---|-----------|---------|------|----------|---------|--------|
| 5 | Balance | `wallet.get('creator')` | GET | `/wallet` | `:72` | ✅ |
| 6 | **Withdraw / cash out** | `wallet.withdraw(amount, idempotencyKey)` | POST | `/wallet/withdraw` | `:115` | ✅ idempotency-keyed |
| 7 | Transaction ledger | `wallet.transactions('creator', …)` | GET | `/wallet/transactions` | `:135` | ✅ |
| 8 | List payout methods | `wallet.getPayoutMethods('creator')` | GET | `/wallet/payout-methods` | `:159` | ✅ |
| 9 | Add UPI/bank | `wallet.addPayoutMethod('creator', payload)` | POST | `/wallet/payout-methods` | `:175` | ✅ write-only fields |
| 10 | Set primary instrument | `wallet.setPrimaryPayoutMethod('creator', id)` | PUT | `/wallet/payout-methods/{id}/primary` | `:191` | ✅ |
| 11 | Platform fee display | `wallet.platformFee()` | GET | `/creator/platform-fee` | `CreatorPlatformFeeController:19` | ✅ |

**The creator payout path is the most completely wired money flow in the application** — withdrawal, instrument management, primary selection and fee transparency are all present and called.

### 49c. Escrow & platform fee — cross-cutting

| # | UI Action | FE Call | HTTP | Endpoint | Backend | Status |
|---|-----------|---------|------|----------|---------|--------|
| 12 | Fund escrow | `payments.fundEscrow(campaignId, key, milestoneId?)` | POST | `/wallet/escrow/fund` | `EscrowController:68` | ⚠️ **P-2** Meera-only |
| 13 | Brand fee copy in chat | `wallet.brandPlatformFee()` | GET | `/brand/platform-fee` | `BrandPlatformFeeController:20` | ✅ called by `brand-chat.tsx` |
| — | Release escrow | `payments.releasePayout(milestoneId)` | POST | `/wallet/escrow/release` | `:101` | 🔴 **P-1** 0 callers |
| — | Refund escrow | ❌ no client | POST | `/wallet/escrow/refund` | `:110` | 🔴 **P-1** |
| — | Payout | ❌ no client | POST | `/wallet/escrow/payout` | `:117` | 🔴 **P-1** |
| — | Single escrow hold | ❌ no client | GET | `/wallet/escrow/{id}` | `:91` | 🔴 **P-1** |
| — | Wallet balance (thin) | ❌ no client | GET | `/wallet/balance` | `:60` | 🔵 surplus — FE uses `GET /wallet` |

---

## 50. What Is Done Well — the money path

| Practice | Where | Why it matters |
|----------|-------|----------------|
| **Null renders as `—`, never `₹0`** | `brand-wallet.tsx:237-238` — `formatCurrency` returns `'—'` for `null`/`NaN` | Verified accurate by red-team. ⚠️ **But the "counterexample to D-1" framing was wrong:** `brand-wallet.tsx:532` does `runwayDays ?? 0`, driving the same red "0 days" alarm at `:936-951`. **D-1 lives in this very file** — the honest `formatCurrency` and the dishonest `?? 0` coexist 300 lines apart |
| Unavailable live figures set to `null`, not mock | `:535-538` — `projectedBurn30Days`, `suggestedRecharge`, `totalTDSDeducted`, `totalGSTPaid` all `null` in live mode | Tax figures (TDS/GST) render `—` rather than a fabricated ₹0 — the honest choice on a compliance-sensitive number |
| Mock suggestions gated out of live | `:733` — `!isApiLive() && …` | The "suggested recharge" nudge cannot fire on fabricated data in production |
| `Idempotency-Key` mandatory on both money writes | `topUp` (`:94`), `withdraw` (`:115`) | A double-submit cannot double-charge or double-withdraw |
| Server re-derives amounts | `WalletTopUpRequest` javadoc — amount cross-checked against Razorpay's captured amount at webhook time | Client-supplied amount is never trusted as final |
| Payout instrument fields are write-only | `addPayoutMethod` — `accountOrVpa`/`ifsc` never returned | Account numbers are not echoed back to the client |
| Dead client removed with a dated reason | `api.ts` — `recharge` (`POST /wallet/recharge`) removed 2026-07-26, "no Java controller has ever [existed]" | The correct disposal of a phantom endpoint — the opposite of leaving `releasePayout` uncalled |

---

## 51. NOT CHECKED — Payments (law 5)

| Not verified | Why |
|-------------|-----|
| Runtime behaviour against a live server | Static trace only; **no live payment was executed** |
| Razorpay webhook correctness (credit-on-capture) | Requires live Razorpay + webhook delivery |
| Whether ledger double-entry actually balances | `WalletLedgerService` arithmetic not traced |
| Whether escrow release would work **if** it were called | P-1 is about reachability; the service-layer logic behind `:101` was not traced |
| TDS / GST calculation correctness | Backend tax logic not traced; FE renders `—` because the API does not supply them |
| Whether creator withdrawal limits are enforced | Constants read (`MIN 500` / `MAX 100000` / 3-per-day) but not executed |
| Concurrency / race conditions on balance mutation | Requires load testing |
| Whether funds already sit in escrow on any real workspace | No live data was inspected |

> ⚠️ **This audit establishes reachability, not correctness.** Nothing here should be read as assurance that money moves correctly — only that certain paths cannot be invoked at all.

---

## 52. Master Defect Register — all five audits

| ID | Surface | Severity | Evidence | Summary |
|----|---------|----------|----------|---------|
| **P-1′** | Payments | 🔴 HIGH | believed | **Meera-funded escrow holds are releasable by neither path** — both are milestone-keyed; Meera funds campaign-level with no milestone |
| **P-3** | Contracts | 🔴 HIGH | believed | `\|\| 50000` fallback across 7 lines — both parties told **"₹50,000 secured in escrow"** on a live zero-value deal |
| **P-4** | Payments | 🔴 HIGH | believed | `creator-wallet.tsx:452` mints `withdraw-${Date.now()}` per click, **defeating server idempotency dedupe** (bounded by row lock + daily cap) |
| **D-11** | Discover → Chat | 🔴 HIGH | **proved** | Invite/offer redirect drops the deal ID (2 failing tests) |
| **D-1** | Dashboard | 🔴 HIGH | believed | Null runway renders as red "CRITICAL / 0d" on funded wallets |
| **D-2** | Campaigns | 🔴 HIGH | believed | List truncates at 100; meta discarded, no pager |
| **D-5** | Campaigns | 🔴 HIGH | believed | `collaboratorsCount` always 0, rendered as fact |
| **P-2** | Payments | 🟡 MEDIUM | believed | Escrow funding reachable only via the Meera AI chat |
| **D-12** | Messages | 🟡 MEDIUM | believed | 10 enabled controls with no handler (incl. destructive "Delete") |
| **D-13** | Messages | 🟡 MEDIUM | believed | No realtime — `/brand/messages` never opens the SSE stream |
| **D-8** | Deal Room | 🟡 MEDIUM | believed | Sidebar "Deals" → room missing the shipment control |
| **D-9** | Deal Room | 🟡 MEDIUM | believed | Deliverable **Reject** route unreachable |
| **M-2** | Deal Room | 🟡 MEDIUM | believed | `GET /contracts?dealId=` silently drops the filter for brands |
| **M-3** | Contracts | 🟡 MEDIUM | believed | Demo constant `?? 'deal-1'` outside the `isApiLive()` gate |
| **D-3** | Campaigns | 🟡 MEDIUM | believed | Progress bar always 0%; "Sort by Progress" dead |
| **D-6** | Campaigns | 🟡 MEDIUM | believed | `sortBy`/`sortOrder` accepted by backend, never sent |
| **D-14** | Discover | 🔵 LOW | believed | 4 backend creator endpoints with no caller |
| **M-4** | Contracts | 🔵 LOW | believed | Download PDF disabled behind an outdated comment |
| **M-5** | Deal Room | 🔵 LOW | believed | Brand has no open-dispute control |
| **D-4** | Campaigns | 🔵 LOW | believed | Stale backend line-number citations |
| **D-7** | Campaigns | 🔵 LOW | believed | `/brand/campaigns/:id/tracking` has no inbound link |
| **P-1** | Payments | 🔵 LOW | believed | `POST /wallet/escrow/release` and `/payout` are endpoints with no client caller — dead surface *(was CRITICAL; headline false alarm)* |
| ~~M-1~~ | ~~Deal Room~~ | 🚫 **STRUCK** | — | Same false alarm as P-1 — release IS wired, via deliverable approval |
| ~~D-10~~ | ~~Deal Room~~ | 🚫 STRUCK | — | False alarm — cross-worktree grep leak |

*Additionally logged by red-team in §53-58: **P-5**, **P-6**, **P-7** (MEDIUM), **P-8** (LOW). §46's endpoint count omits `/billing`, `/admin/billing`, `/admin/finance/fee-config`, `/webhooks/razorpay`.*

**21 live defects · 6 HIGH · 0 CRITICAL · 1 oracle-proved · 2 struck as false alarms.**

### Verified clean on the money path

The **top-up → Razorpay flow is correctly built** and stays webhook-authoritative (`brand-wallet.tsx:458-463`) — the client never credits the ledger itself. The **§50 "done well" claims were all verified accurate**, including that live-mode TDS/GST are `null` and render `—`: there is **no false clean bill of health on tax figures**.

### The pattern, now across five audits

**Across every surface traced — dashboard, campaigns, deal room, discover, chat and payments — not one backend endpoint was found broken or missing.** Every route exists; every line citation checked out. All 19 defects are client-side, in four repeating shapes:

| Shape | Defects |
|-------|---------|
| **A working API has no caller** | **P-1**, P-2, D-9, D-14, M-5, M-4 |
| **A response is returned and discarded** | D-11, D-2, D-6 |
| **A zero stands in for "unknown"** | D-1, D-3, D-5 |
| **A control renders without a handler** | D-12, D-3 |

The single largest category is **built-but-unreachable** — and its most severe instance is the escrow exit.

No build gate catches any of these. They typecheck (`tsc` exit 0 on every run in this document), they lint, and in mock mode they demo perfectly. The one defect a gate *did* catch, D-11, was caught by a test that already existed and was already failing.

---

*Audit produced by static code trace + bidirectional caller census on branch `fix/brand-audit-remediation`. No live server was probed and no payment was executed.*

---
---

# PART 5 — RED-TEAM VERIFICATION (Kabir)

> **Method:** every claim in §§46–52 re-derived from primary source. All searches scoped to `git ls-files`; `.claude/worktrees/`, `.proof-os/` and prior `*.md`/`*.html` audit docs were used only as *leads*, never as evidence. Every "zero callers" claim re-run with bare-name, aliased-import, chained-call and cross-API-layer patterns before being accepted or rejected.
> **Date:** 2026-08-09 · **Branch:** `fix/brand-audit-remediation`

## 53. Verdict — **NEEDS_FIX**

The backend census is accurate. **All 6 `EscrowController` endpoints exist at exactly the cited lines** (`:58`, `:68`, `:91`, `:101`, `:110`, `:117`), and every `WalletController` / platform-fee line citation in §49 checked out. §50's "done well" claims are **all true**.

But **P-1 — the headline CRITICAL — does not survive.** Two of its six census rows are factually wrong, and its central conclusion is refuted by code the audit never looked for.

| Claim | Ruling |
|---|---|
| **P-1** §47 — escrow outbound path unreachable, CRITICAL | 🔴 **FALSE ALARM on the headline.** Severity unjustified. See §54 |
| **P-2** §48 — funding reachable only via Meera | ✅ **REAL BUG.** Confirmed on every line. See §55 |
| **§50** — "What Is Done Well" | ✅ **ACCURATE.** Every claim verified. One caveat in §56 |
| **Missed** | 6 further money-path defects, 2 of them HIGH. See §57 |

---

## 54. P-1 (§47) — **FALSE ALARM.** The escrow exit is reachable; the census missed a second API layer

### 54a. Row-by-row re-derivation

| Backend endpoint | Line exists? | §47 said | Actual | Ruling |
|---|:---:|---|---|---|
| `GET /wallet/escrow` `:58` | ✅ | `escrowList`, 1 caller | `brand-wallet.tsx:414` | ✅ correct |
| `POST /wallet/escrow/fund` `:68` | ✅ | Meera only | confirmed | ✅ correct |
| `GET /wallet/escrow/{id}` `:91` | ✅ | ❌ **no client method** | **client + live caller exist** | 🔴 **WRONG** |
| `POST /wallet/escrow/release` `:101` | ✅ | 🔴 0 callers | true for `payments.releasePayout`; **endpoint is not the release path** | ⚠️ **misleading** |
| `POST /wallet/escrow/refund` `:110` | ✅ | ❌ no client | true in FE; **reachable via admin dispute settlement** | ⚠️ **incomplete** |
| `POST /wallet/escrow/payout` `:117` | ✅ | ❌ no client | confirmed | ✅ correct |

### 54b. `GET /wallet/escrow/{id}` **has a client and a live caller**

The census searched `src/lib/api.ts` only. This repo has **two** frontend API layers, and §48 names the second one itself (`src/lib/meera-api.ts`).

```
src/lib/meera-api.ts:570   getEscrowStatus: async (escrowHoldId: string): Promise<MeeraEscrowStatus> => {
src/lib/meera-api.ts:583     return request<MeeraEscrowStatus>('GET', `/wallet/escrow/${escrowHoldId}`);
src/hooks/useEscrowFund.ts:377   const escrowStatus: MeeraEscrowStatus = await meeraApi.getEscrowStatus(holdId);
```

The disproof was **two lines above the audit's own citation**. §47 cites `api.ts:2578` for `escrowList`; `api.ts:2576` reads:

> `getEscrowStatus per-hold via the Meera API instead).`

So "the UI can list holds but cannot open one" is false.

### 54c. Escrow release **is reachable from the shipped brand client**

`POST /wallet/escrow/release` is a *secondary* path. The **primary** release path is deliverable approval, and it is fully wired:

```
src/pages/brand-chat.tsx:1075                                        await deliverablesApi.approve(id);
src/components/brand/contracts/contracts-and-deliverables.tsx:632    await api.deliverables.approve(...);
src/components/brand/deliverables/DeliverableViewer.tsx:178          await api.deliverables.approve(...);
src/components/brand/timeline/panels/deliverable-review-panel.tsx:85 await deliverablesApi.approve(...);
        ↓  api.ts:2301-2303  isLive() ? POST `/deliverables/${id}/approve`
        ↓  BrandDeliverableController.java:46
        ↓  BrandDeliverableService.java:117   escrowService.tryReleaseOnApproval(workspace.getId(), deliverable.getMilestoneId());
        ↓  EscrowService.java:632             escrowBackend.release(new ReleaseCommand(hold.getId(), payeeUserId, ...))
                                              payeeUserId = collaboration.getCreatorId()   (:614)
```

Four production call sites, live-gated, moving real money to the creator. §47's "no way to release those funds to the creator on delivery" is **false**. §47 even proposes "the natural home is the deliverable-approval flow" as the *fix* — that is where it already is.

### 54d. Refund is reachable too (admin-mediated)

`DisputeService.java:255/259` → `escrowService.adminReleaseForDispute` / `adminRefundForDispute` / `adminSplitForDispute`, exposed at `POST /admin/disputes/:id/resolve` and wired in the shipped admin client: `src/admin/hooks/useDisputeResolve.ts:48` → `src/admin/components/disputes/DisputeList.tsx:177`.

### 54e. The document contradicts itself

§49b states the creator withdrawal path is "the most completely wired money flow in the application" — `wallet.withdraw` confirmed at `creator-wallet.tsx:453`, wired to the Withdraw button at `:1060`. The complete exit therefore exists and every leg is called:

> approve deliverable → `tryReleaseOnApproval` → creator wallet credited → `POST /wallet/withdraw` → bank

§47's "funds entering it have no modelled exit" and §49b cannot both be true. **CRITICAL is not justified.** The residue — one uncalled convenience wrapper (`payments.releasePayout`) plus two never-written clients — is 🔵 **LOW** housekeeping.

### 54f. ⚠️ But there IS a real money-custody gap here — and Part 5 walked past it

**P-1′ · 🔴 HIGH — Meera-funded escrow has no release path at all.**

`tryReleaseOnApproval` takes a **milestoneId**. `POST /wallet/escrow/release` takes a **milestoneId**. `PaymentMilestone` rows are created in exactly one place — `ContractService.java:302` (contract generation).

But escrow funding explicitly supports **campaign-level holds with no milestone** (`EscrowService.java:200`: *"campaign-level funding with no milestoneId"*), and that is precisely what the only funding caller does:

```
src/components/feature/meera/MeeraWorkspace.tsx:77
    const res = await api.payments.fundEscrow(MEERA_DEMO_CAMPAIGN_ID, idempotencyKey)   // no milestoneId
```

A campaign-level hold therefore **cannot be released by the approval path** (no milestone) **nor by `/wallet/escrow/release`** (keyed on milestone). Its only exit is admin dispute settlement, which is keyed on `collaborationId` a campaign-level hold need not have.

This is the true, defensible version of P-1 — and it is exactly the **intersection of P-1 and P-2** that the audit had both halves of and did not join. It is narrower than "the entire outbound half of the ledger", and it is real.

---

## 55. P-2 (§48) — **REAL BUG. Confirmed on every line.**

Full census of `fundEscrow` across all git-tracked files returns only Meera surfaces:

| File | Line | Role |
|---|---|---|
| `src/components/feature/meera/MeeraWorkspace.tsx` | `:77` | **only production UI caller** |
| `src/hooks/useEscrowFund.ts` | `:208` | hook (Meera) |
| `src/lib/meera-api.ts` | `:545` | Meera API layer |
| `src/lib/api.ts` | `:2699` | definition |
| `FundEscrowButton.test.tsx`, `useEscrowFund.test.ts` | — | tests |

`brand-wallet.tsx` confirmed to call only `get`, `topUp`, `transactions`, `escrowList` — **never** `fundEscrow`. No deal-room component calls it. ✅ **Ruling upheld at MEDIUM**, and it is the direct cause of P-1′ above.

---

## 56. §50 "What Is Done Well" — **ACCURATE.** No false clean bill of health

Every praise claim independently verified. This mattered most for the tax figures, and they are honest:

| Claim | Verified |
|---|---|
| `formatCurrency` returns `'—'` for null | ✅ `brand-wallet.tsx:237-238` — `if (amount == null || Number.isNaN(amount)) return '—';` |
| Live mode sets TDS/GST/burn/recharge to `null`, not mock | ✅ `brand-wallet.tsx:535-538` — all four `null as number | null` in the `isApiLive()` branch. **Citation exact.** |
| Mock recharge nudge gated out of live | ✅ `:733` — `{!isApiLive() && wallet.suggestedRecharge != null && ...}` |
| Idempotency mandatory on both money writes | ✅ `WalletController:94` / `:115` |
| Server re-derives top-up amount at webhook | ✅ `MoneyDtos.java:70-72` — cross-checked in `WalletTopUpService#confirmCredited` |
| Payout instrument fields write-only | ✅ `BankAccountDtos.java:15` + `:24` masked response |
| `recharge` removed with dated reason | ✅ `api.ts:2488` |

**Ruling: the §50 tax claims are TRUE.** TDS/GST really do render `—` in live mode rather than a fabricated ₹0.

> ⚠️ **One caveat on the rhetoric, not the facts.** §50 frames the wallet page as the page that "refuses to invent a number" *in contrast to* the dashboard. That contrast is wrong: `brand-wallet.tsx:532` reads `runwayDays: walletSummary.runwayDays ?? 0` — while `api.ts:2361` declares `runwayDays: number | null`, *"null when it can't be derived"*. That coerced `0` then drives a red border and "**0 days**" at `:936-951`. **D-1 is present on the wallet page itself**, in the same file §50 holds up as the counterexample to D-1.

---

## 57. MISSED — six money-path defects Part 5 did not catch

All verified against source by this pass.

### P-3 · 🔴 HIGH — `|| 50000` fabricates an escrow figure in the live path

Live chain, fully traced:

```
creator-deal-mappers.ts:92-95   parseDealAmount → returns 0 when dealValue is null/unparseable
creator-deal-mappers.ts:215     dealAmount: parseDealAmount(deal.dealValue)
creator-chat.tsx:1440 (if liveApi) → :1454  amount: deal.dealAmount
```

then, in components that import **no** `isApiLive`/`isLive` at all (verified: 0 matches in both files):

```
creator-contract-card.tsx:165   ₹{(meta?.amount || 50000).toLocaleString('en-IN')} secured in escrow
creator-contract-panel.tsx:188/201/205/211
contract-panel.tsx:143/178      ₹{(meta?.amount || 50000)...} is locked in escrow
```

`0 || 50000` → **₹50,000**. A live deal with no `dealValue` tells **both** creator and brand that ₹50,000 is secured in escrow when nothing is. A falsy-zero `||` on a money field, ungated, on both sides of the deal. `creator-contract-panel.tsx:205` additionally hardcodes a 15% fee split client-side.

### P-4 · 🔴 HIGH — the withdrawal idempotency key is regenerated on every click

```
creator-wallet.tsx:452   const idempotencyKey = `withdraw-${Date.now()}`;   // inside handleWithdraw
creator-wallet.tsx:453   await api.wallet.withdraw(amount, idempotencyKey);
```

A fresh key per click **defeats the server's dedupe**. The asymmetry is the proof this is an oversight: the money-**in** path solves exactly this and says so —

```
brand-wallet.tsx:493-496  "...reused across retries of that same submission after a network
                           failure — a fresh key per retry would let a client retry double-
                           spend past the server's idempotency dedupe"
brand-wallet.tsx:503-504  const idempotencyKey = topUpIdempotencyKey ?? safeRandomUUID();
```

The money-**out** path did not get the same treatment. **Bounded, stated fairly:** `WalletService:214` takes a row lock and `:231-236` caps withdrawals at `MAX_CREATOR_WITHDRAWALS_PER_DAY`, so this cannot overdraw — the blast radius is repeat payouts up to the daily cap and available balance. `Date.now()` is also millisecond-granular and not user-scoped.

### P-5 · 🟡 MEDIUM — ungated 10% platform fee applied to a live campaign budget

```
brand-campaign-detail.tsx:1865  {campaign.budget ? (          ← no liveApi gate
                       :1868   'Creator Pay (est.)'  budget.max * 0.82
                       :1869   'Platform Fee (10%)'  budget.max * 0.10
                       :1870   'GST (18% on fee)'    budget.max * 0.018
                       :1871   'Contingency'         budget.max * 0.062
```

The real default is **15%** (`application.yml:306` — `platform-fee-percent: ${PLATFORM_FEE_PERCENT:15.00}`). The repo already documents this exact bug class as fixed elsewhere (`api.ts:2507-2508`: *"the deal-room proposal form hardcoded 'Platform Fee (10%)' while the real default is 15%... A brand budgeting off that number under-quoted its own cost"*) — **this sidebar was missed by that fix.** Note the Settlement Summary directly above it *is* correctly fenced by `{!liveApi && mockCompleted && (` at `:1678`.

### P-6 · 🟡 MEDIUM — ungated "₹0 fee / you receive the full amount" on the withdraw dialog

```
creator-wallet.tsx:1039   <span>-₹0</span>                                    ← hardcoded fee
creator-wallet.tsx:1043   {formatINR(parseFloat(withdrawAmount))}             ← "You'll receive"
```

Not gated by `isApiLive()`. The same page fetches a real platform fee at `:335/:342` (`api.wallet.platformFee()`) and then ignores it here. If the backend deducts anything, the creator was promised a number that will not land.

### P-7 · 🟡 MEDIUM — payment-method picker is decorative; its "2% convenience fee" is never applied

`paymentMethod` (`brand-wallet.tsx:352`), selected via the UPI/Card/Net-Banking buttons at `:776/:794/:812`, is **never sent**: `:506` posts `api.wallet.topUp({ amount }, idempotencyKey)`. The card option advertises `2% convenience fee` (`:805`) which is never added, shown, or transmitted.

### P-8 · 🔵 LOW — three dead controls next to real money figures

`brand-wallet.tsx:640` **Export** · `:982-986` **Download Form 16A** · `:1003-1007` **Download GST Summary** — no `onClick` on any. The latter two are tax-document downloads sitting beside live figures.

### Scope note — §46's "17 payment endpoints" understates the money surface

Controllers with money semantics **not covered** by Part 5: `/billing` (`BillingController`, `BrandInvoicingController`), `/admin/billing`, `/admin/finance/fee-config` (`PlatformFeeAdminController`), `/admin/escrow`, and `/webhooks/razorpay` (`RazorpayWebhookController` — the credit-on-capture authority). §51 correctly lists the webhook as NOT CHECKED, but the census total should not read as the whole money surface.

### Verified clean — not defects

The top-up → Razorpay flow is **fully wired and correctly designed**: real checkout script injection (`src/lib/razorpay.ts:72-109`), publishable key fetched from `GET /config/razorpay` (no hardcoded key, `:116-131`), real `.open()` with `handler` / `modal.ondismiss` / `payment.failed` (`:169-188`), and — importantly — `brand-wallet.tsx:458-463` **does not trust the Checkout callback**; it re-fetches `GET /wallet` and shows a "confirming" stage, leaving the webhook authoritative. Creator payout-method management (`creator-wallet.tsx:424/468/487`) is fully called and live-gated, and withdraw is correctly disabled when no payout method exists (`:1061`).

---

## 58. Corrected Part 5 register

| ID | Severity | Ruling | Summary |
|---|---|---|---|
| ~~**P-1**~~ | ~~🔴 CRITICAL~~ → 🔵 **LOW** | **DOWNGRADED — headline false** | Release *is* reachable via deliverable approval (4 call sites); `GET /escrow/{id}` *has* a client (`meera-api.ts:570`) + live caller; refund reachable via admin dispute settlement. Residue = 1 uncalled wrapper + 2 unwritten clients |
| **P-1′** | 🔴 **HIGH** | **NEW — the real gap** | Meera-funded **campaign-level** holds have no milestone, so neither release path can ever release them |
| **P-3** | 🔴 **HIGH** | NEW | `|| 50000` shows a fabricated "₹50,000 secured in escrow" to both parties on a live zero-value deal |
| **P-4** | 🔴 **HIGH** | NEW | Withdrawal idempotency key regenerated per click — defeats server dedupe (bounded by daily cap) |
| **P-2** | 🟡 MEDIUM | ✅ **UPHELD** | Escrow funding reachable only through the Meera AI chat |
| **P-5** | 🟡 MEDIUM | NEW | Ungated 10% platform fee on live campaign budget; real default is 15% |
| **P-6** | 🟡 MEDIUM | NEW | Ungated "₹0 fee / full amount" promise on the withdraw dialog |
| **P-7** | 🟡 MEDIUM | NEW | Payment-method picker never transmitted; "2% convenience fee" never applied |
| **P-8** | 🔵 LOW | NEW | Dead Export / Form-16A / GST-Summary controls beside live money figures |
| **D-1** | 🔴 HIGH | **SCOPE WIDENED** | `runwayDays ?? 0` is on `brand-wallet.tsx:532` too, not only the dashboard |
| ~~M-1~~ | — | **UN-SUPERSEDED** | P-1 no longer supersedes it; both reduce to the same LOW housekeeping item |

### What this changes about the audit's own conclusion

§52 claims *"not one backend endpoint was found broken or missing... all 19 defects are client-side."* That still holds — but the fifth pattern this pass adds is the one Part 5's method could not see:

> **A number is fabricated in a live path by a falsy fallback** — P-3 (`|| 50000`), P-5 (`* 0.10`), P-6 (`-₹0`), D-1 (`?? 0`).

A reachability census cannot detect these: every endpoint involved is called, correctly, and the wrong number is invented *after* the response arrives. **P-3 and P-6 are shown to a creator as a promise about their own money.** They are, on any money-path register, worse than an uncalled wrapper.

### Method note for the next pass

P-1's error had one cause: **the census was scoped to a single API layer** (`src/lib/api.ts`) in a repo with two (`src/lib/meera-api.ts`), and treated "no client method in `api.ts`" as "unreachable". Reachability must be traced **from the backend method inward** — `tryReleaseOnApproval` had four live FE call sites and not one of them mentions the word *escrow*.

---

*Verification by static trace against primary source only. No live server probed, no payment executed. Every finding above cites the file and line it was read from.*

**Signed: Kabir — Red-Team / Offensive Security, Sage Digital**

---
---

# PART 6 — Pipeline · Timeline · Contracts API Audit

> **Scope:** the three Manage-group surfaces — `/brand/pipeline`, the collaboration Timeline, and `/brand/contracts`.
> **Method:** Static code trace + **bidirectional caller census** + `vitest` + `tsc`.
> **Branch:** `fix/brand-audit-remediation` · **Date:** 2026-08-09
> **Method note (accumulated from Parts 1-5):** every search scoped to git-tracked files; **both** API layers checked (`api.ts` *and* `meera-api.ts`) plus `src/hooks/**`; aliased/destructured/chained import patterns all tried; and no component judged dead from prop shape alone — implementations were traced. Two false alarms in earlier parts came from skipping exactly these steps.

---

## 59. Audit Summary

| Surface | APIs | Backend match | Defects |
|---------|-----:|:-------------:|---------|
| **Pipeline** (`/brand/pipeline`) | 1 | ✅ 1/1 | **0** — clean |
| **Timeline** (collaboration timeline) | 3 | ✅ 3/3 | 1 MEDIUM |
| **Contracts** (`/brand/contracts`) | 6 | ✅ 6/6 | 3 (2 MEDIUM, 1 LOW) |

| Oracle | Result |
|--------|--------|
| `npx tsc --noEmit` | ✅ **0 errors, exit 0** |
| `vitest brand-pipeline-stage.test.ts` | ✅ **17/17 passing** |

**Verdict: all 10 endpoints exist and are correctly wired. Pipeline is the cleanest surface audited in this document. Contracts carries three real defects — including a hardcoded demo id that breaks a primary CTA on every real contract.**

---

## 60. ~~✅ PIPELINE — clean~~ → **NOT CLEAN. 5 defects (CTO verification §65-70)**

> 🚫 **CORRECTED.** This section declared Pipeline **0 defects** and praised it. Both were wrong. CTO verification found **five** defects — including one that falsifies this audit's own closing argument (§64).
>
> **The method failure:** everything this section *checked* was accurate. The error was stopping once the surface looked good. Praise was not verified to the standard a defect would have been — and one praise claim was a **source comment repeated as verified fact**.

### What was verified accurate

One API call (`:393`), zero drag handlers, exactly 5 bound `onClick`s, the CR-30 / `TERMS_AGREED` history, and the oracle (**17/17 passing**, independently re-run). `deals.list` also does not truncate — D-2's failure mode does not repeat here.

### ❌ The false praise

**§60 claimed a new `CollaborationStatus` "cannot be silently swallowed." That is FALSE.** `deal-stage.ts:137-138` ends its switch with:

```ts
default: return 'negotiating';
```

A new status is silently bucketed into Negotiating with **no compile error**. `brand-pipeline-stage.ts`'s explicit `null` handling is real, but it sits downstream of a `default:` that already swallowed the unknown. This audit read the module's comment and reported it as a verified property.

### 🟡 PL-1 — the entire SLA "at-risk" feature is dead in live mode (MEDIUM)

`slaHoursRemaining` is populated **only in mock data**; the `Deal` type carries no SLA field. So `atRiskCount` is structurally `0` in live mode, and the **"N at risk" filter button at `:688` never renders.**

§60 certified that button's `onClick` as "wired" — it is bound, and it is also unreachable. **Counting bound handlers is not the same as checking they can be reached.**

### 🟡 PL-2 — a FIFTH copy of the stage switch exists, in Java, and it disagrees (MEDIUM)

`DashboardService.java:126` `bucketFor()` maps status → pipeline bucket server-side, and it does not agree with the board:

| Status | Server (`bucketFor`) | Board (`brand-pipeline-stage`) |
|--------|---------------------|-------------------------------|
| `INVITED` / `APPLIED` / `SHORTLISTED` | Negotiating | **Outreach** |
| `REVIEW_PENDING` | In Progress | **Review** |
| `COMPLETED` | Completed | **Settled** |

The dashboard Pipeline card and the Pipeline board give a brand **two different answers in the same session** — which is verbatim the CR-30 defect `brand-pipeline-stage.ts` was extracted to eliminate. Extracting and testing the *client* copy did not help, because the disagreeing copy is on the server.

### 🟡 PL-3 — dashboard Pipeline card has no color for `Completed` (MEDIUM)

Falling directly out of PL-2: `dashboard-page.tsx:299-306` defines `STAGE_COLOR` with no key for **`Completed`** — the one terminal bucket the server actually emits. That segment renders `text-white` on `bg-muted`, unreadable on this theme. Three declared colors are unreachable because they use the board's vocabulary, not the server's.

### 🔵 PL-4 — undocumented hardcoded empties (LOW)

`platforms: []` and `creatorFollowers: ''` are hardcoded empty in live mode with no comment — unlike `matchScore`, which is handled honestly nearby.

### 🔵 PL-5 — off-by-one at the SLA boundary (LOW)

`:285` `isAtRisk` short-circuits on `0`, so a deal sitting **exactly** at SLA breach is classified not-at-risk.

---

## 60a. What Pipeline still does right

`/brand/pipeline` makes exactly **one** API call:

| # | UI Action | FE Call | HTTP | Endpoint | Backend | Status |
|---|-----------|---------|------|----------|---------|--------|
| 1 | Board loads | `deals.list('brand')` | GET | `/deals` | `DealController:65` | ✅ |

**It is a read-only board, not a drag-and-drop Kanban.** Zero `drag`/`onDrop`/`draggable`/`moveStage` handlers across all 738 lines — and correspondingly, no stage-write API. Since nothing in the UI presents columns as draggable, this is a coherent design, not a broken one. All 5 `onClick` handlers are wired (open deal ×3, navigate to Discover, at-risk filter toggle). **No dead controls.**

### Why this surface is worth copying

The stage derivation was extracted into `src/lib/brand-pipeline-stage.ts` — a documented, **tested** module — for a reason the file states plainly:

> It was local, and being local is what let it drift unnoticed and untested.

The drift it fixed was real (CR-30): `TERMS_AGREED` was mapped to `CONTRACTED`, so one deal read *"Negotiating"* in the deal room while sitting in the **Contracted** column of this board — same brand, same session. The comment names the defect precisely:

> Putting it under Contracted claims a contract that does not exist.

Two further habits worth noting:

- **`CANCELLED`/`DISPUTED` return `null` explicitly** rather than inventing a column. ⚠️ *But see the false-praise note above — the upstream `deal-stage.ts:137` `default:` means an unknown status never reaches this handling.*
- The board **declines to place** those deals rather than inventing a column for them, and says so.

**Oracle:** `brand-pipeline-stage.test.ts` — **17/17 passing**.

> 🔎 **Observation (not a defect):** `CANCELLED` and `DISPUTED` deals are filtered off the board entirely, so a brand cannot see a disputed deal on the pipeline. The code documents this as a deliberate product call (a Disputed column exists on the creator side, CR-26). Flagged for product, not engineering.

---

## 61. TIMELINE — wired correctly; one gap

The collaboration Timeline (`collaboration-timeline.tsx`, rendered by `brand-campaign-detail.tsx`) uses 3 APIs:

| # | UI Action | FE Call | HTTP | Endpoint | Backend | Status |
|---|-----------|---------|------|----------|---------|--------|
| 2 | Load thread | `messages.list` | GET | `/deals/{dealId}/messages` | `DealController:114` | ✅ |
| 3 | Send message | `messages.send` | POST | `/deals/{dealId}/messages` | `:151` | ✅ |
| 4 | Mark read | `messages.markRead` | POST | `/deals/{dealId}/messages/read` | `:160` | ✅ |

### Component census — no orphans

All 10 timeline files are git-tracked and every one has an importer:

| Component | Importers |
|-----------|:---------:|
| `contract-card`, `deliverable-card`, `message-card`, `payment-card`, `proposal-card`, `system-card` | 1 each |
| `timeline-event`, `contract-panel` | 1 each |
| `deliverable-review-panel` | 3 |

### The panels are genuinely functional — verified, not assumed

The panels expose **optional** callbacks (`onApprove?`, `onRequestRevision?`) invoked with `?.()`, which *looks* like a component that only notifies a parent. Tracing the implementation shows otherwise:

- **`deliverable-review-panel.tsx:86`** calls `await deliverablesApi.approve(deliverableId)` **itself**, then fires the optional callback as a notification. Approve and Request-Revision work standalone. It also uses the correct id — `meta.deliverableId`, explicitly distinguished in-code from the timeline event's own `event.id`.
- **`contract-panel.tsx:12`** imports `signContract` from `@/lib/contract-generator`, which calls `api.contracts.sign` at `contract-generator.ts:214` → `ContractController:78`. Signing is real.

> This is the check that would have prevented the false alarms in Parts 3 and 5: **prop shape is not evidence.** Both panels would have been misreported as inert if judged by their callback signatures.

### 🟡 T-1 — Timeline has no realtime (MEDIUM)

`grep -c "messages.stream"` on `collaboration-timeline.tsx` → **0**. No SSE, no polling.

This is the **same defect as D-13** (`/brand/messages`), on a second surface. Of the three brand surfaces that render a message thread, only `/brand/chat` opens the stream:

| Surface | Realtime |
|---------|:--------:|
| `/brand/chat` | ✅ SSE + reconnect + gap refetch |
| `/brand/messages` | ❌ **D-13** |
| Timeline (campaign detail) | ❌ **T-1** |

The proven pattern already exists in `brand-chat.tsx:1183-1184`; two surfaces simply never adopted it.

---

## 62. CONTRACTS — three confirmed defects

| # | UI Action | FE Call | HTTP | Endpoint | Backend | Status |
|---|-----------|---------|------|----------|---------|--------|
| 5 | Contract list | `contracts.list(role, dealId)` | GET | `/contracts` | `ContractController:46` | ⚠️ **C-1** |
| 6 | Open contract | `contracts.get(role, id)` | GET | `/contracts/{id}` | `:68` | ✅ |
| 7 | **Sign contract** | `contracts.sign(...)` | POST | `/contracts/{id}/sign` | `:78` | ✅ |
| 8 | Deal list (join) | `deals.list('brand')` | GET | `/deals` | `DealController:65` | ✅ |
| 9 | **Approve deliverable** | `deliverables.approve(id)` | POST | `/deliverables/{id}/approve` | `BrandDeliverable:46` | ✅ |
| 10 | **Request revision** | `deliverables.requestRevision(...)` | POST | `/deliverables/{id}/revise` | `:53` | ✅ |

> Note: contract signing and deliverable approval are **both live on this sidebar-reachable page** — the evidence that corrected D-8's severity in Part 3.

### 🔵 C-1 — `GET /contracts?dealId=` silently drops the filter for brands (LOW — *was MEDIUM*)

> ⚠️ **Impact claim below is FALSE; severity corrected.** The dropped filter is real (`ContractController.java:49/51/54`; `ContractService.java:823` is the only `listForBrand` signature — no overload). But **no brand sees wrong rows today:** `dealId` is optional at `api.ts:2120`, and the sole brand call site — `contracts-and-deliverables.tsx:552` — is `api.contracts.list('brand')` with no `dealId`. This is a **latent** defect: a trap for the next caller who passes the parameter and is silently ignored. Worth fixing, not worth MEDIUM.

`ContractController.java:46-55`:

```java
@RequestParam(required = false) String dealId
…
if (principal.getUserType() == UserType.CREATOR) {
    return ApiResponse.ok(contractService.listForCreator(principal, dealId));   // ← forwarded
}
var workspace = brandContext.requireBrandWorkspace(principal);
return ApiResponse.ok(contractService.listForBrand(principal, workspace.getId()));  // ← DROPPED
```

The parameter is accepted, documented, forwarded on the creator branch — and **silently ignored on the brand branch**. The FE sends it (`contracts.list(role, dealId)`, `api.ts`), so a brand asking for one deal's contracts receives **every contract in the workspace**.

A filter that is accepted and ignored is worse than one that is missing: the caller has no signal that filtering did not happen, and any UI relying on it shows wrong rows with full confidence.

**Fix:** add a `listForBrand(principal, workspaceId, dealId)` overload, or reject the param for brands with a clear error.

### 🟡 C-2 — Hardcoded demo deal id breaks "Open in Deal Room" on every real contract (MEDIUM)

> ⚠️ **Evidence corrected:** the map is **three entries** (`:98-102` — `contract-1/2/3` → `deal-1/3/4`), not the one entry originally printed here. The conclusion is unchanged and in fact **stronger**: three hardcoded demo ids cover no real contract either.

`contracts-and-deliverables.tsx:98-102` defines a **three-entry demo map**:

```ts
const CONTRACT_DEAL_ROOM: Record<string, string> = {
  'contract-1': 'deal-1',
  'contract-2': 'deal-3',
  'contract-3': 'deal-4',
};
```

It is used at `:913` and `:921` — **outside any live-mode gate**:

```ts
to={`/brand/chat?deal=${CONTRACT_DEAL_ROOM[selectedContract.id] ?? 'deal-1'}&tab=messages`}
to={`/brand/chat?deal=${CONTRACT_DEAL_ROOM[selectedContract.id] ?? 'deal-1'}&tab=contract`}
```

The file **does** import `isApiLive` (`:3`) and uses it at `:504` — these two links were simply never gated.

**Effect:** in live mode every real contract id misses the map and falls through to `?? 'deal-1'`. Both links navigate to `/brand/chat?deal=deal-1` — a demo id that does not exist in live data. The `:921` link is the **primary-styled "Open in Deal Room" CTA**, which Part 3's correction identified as one of the main entry points into the full Deal Room.

So the most prominent route into the complete Deal Room is broken for every real contract.

**Fix:** derive the deal id from the contract record instead of a lookup table, and delete `CONTRACT_DEAL_ROOM`. ✅ **Confirmed implementable** — §63 left this as an open assumption; CTO verification closed it: `ContractApiRecord.collaborationId` exists at `api.ts:2088`.

### 🔵 C-3 — "Download PDF" disabled behind a factually wrong comment (LOW)

`contracts-and-deliverables.tsx:903-908`:

```ts
// No PDF-export endpoint exists in src/lib/api.ts (contracts facade …
<Button variant="outline" size="sm" className="gap-2" disabled>
  <Download className="w-4 h-4" />
  Download PDF
</Button>
```

**The comment is wrong on both counts.** `api.contracts.pdfDownloadUrl` exists (`GET /contracts/{id}/pdf-download-url` → `ContractController:114`), and `deal-contract-tab.tsx:80` already calls it successfully.

A working feature is disabled because a stale comment says it cannot exist.

**Fix:** delete the comment, remove `disabled`, wire to `api.contracts.pdfDownloadUrl` — the call site to copy is two files away.

---

## 63. NOT CHECKED — Part 6 (law 5)

| Not verified | Why |
|-------------|-----|
| Runtime behaviour against a live server | Static trace + unit tests only; no live probe |
| Whether `listForBrand` returns correct rows | Repository/spec query logic not traced |
| Whether contract two-party signature **ordering** is enforced | `ContractService.sign` internals not traced |
| Whether the timeline's event ordering/dedup is correct | Only the API surface was traced |
| Whether pipeline "at-risk" classification is accurate | Client-side heuristic not evaluated |
| Whether `ContractApiRecord` actually carries a deal id (assumed in the C-2 fix) | Field list not re-read — **verify before implementing** |
| PDF generation correctness behind `pdfDownloadUrl` | Endpoint reachability only |

---

## 64. Master Defect Register — all six audits

| ID | Surface | Severity | Evidence | Summary |
|----|---------|----------|----------|---------|
| **P-1′** | Payments | 🔴 HIGH | believed | Meera-funded escrow holds releasable by neither path (both milestone-keyed) |
| **P-3** | Contracts | 🔴 HIGH | believed | `\|\| 50000` fallback — both parties told "₹50,000 secured in escrow" on a live zero-value deal |
| **P-4** | Payments | 🔴 HIGH | believed | `withdraw-${Date.now()}` idempotency key defeats server dedupe |
| **D-11** | Discover → Chat | 🔴 HIGH | **proved** | Invite/offer redirect drops the deal ID (2 failing tests) |
| **D-1** | Dashboard | 🔴 HIGH | believed | Null runway renders as red "CRITICAL / 0d" on funded wallets |
| **D-2** | Campaigns | 🔴 HIGH | believed | List truncates at 100; meta discarded, no pager |
| **D-5** | Campaigns | 🔴 HIGH | believed | `collaboratorsCount` always 0, rendered as fact |
| **C-2** | Contracts | 🟡 MEDIUM | believed | Demo `deal-1` id breaks "Open in Deal Room" on every real contract |
| **PL-2** | Pipeline | 🟡 MEDIUM | believed | **5th copy of the stage switch** (`DashboardService.java:126`) disagrees with the board on 3 statuses — CR-30 drift, still live |
| **PL-1** | Pipeline | 🟡 MEDIUM | believed | SLA "at-risk" feature dead in live mode — the "N at risk" filter button never renders |
| **PL-3** | Dashboard | 🟡 MEDIUM | believed | Pipeline card has no `STAGE_COLOR` for `Completed` — unreadable white-on-muted segment |
| **T-1** | Timeline | 🟡 MEDIUM | believed | No realtime — timeline never opens the SSE stream |
| **D-13** | Messages | 🟡 MEDIUM | believed | No realtime — `/brand/messages` never opens the SSE stream |
| **D-12** | Messages | 🟡 MEDIUM | believed | 10 enabled controls with no handler (incl. destructive "Delete") |
| **P-2** | Payments | 🟡 MEDIUM | believed | Escrow funding reachable only via the Meera AI chat |
| **D-8** | Deal Room | 🟡 MEDIUM | believed | Sidebar "Deals" → room missing the shipment control |
| **D-9** | Deal Room | 🟡 MEDIUM | believed | Deliverable **Reject** route unreachable |
| **D-3** | Campaigns | 🟡 MEDIUM | believed | Progress bar always 0%; "Sort by Progress" dead |
| **D-6** | Campaigns | 🟡 MEDIUM | believed | `sortBy`/`sortOrder` accepted by backend, never sent |
| **C-3** | Contracts | 🔵 LOW | believed | "Download PDF" disabled behind a factually wrong comment (**three** working call sites exist, not one) |
| **C-1** | Contracts | 🔵 LOW | believed | `GET /contracts?dealId=` drops the filter for brands — latent; no live caller passes it *(was MEDIUM)* |
| **PL-4** | Pipeline | 🔵 LOW | believed | `platforms: []` / `creatorFollowers: ''` hardcoded empty in live mode, undocumented |
| **PL-5** | Pipeline | 🔵 LOW | believed | `isAtRisk` short-circuits on `0` — a deal exactly at SLA breach reads not-at-risk |
| **D-14** | Discover | 🔵 LOW | believed | 4 backend creator endpoints with no caller |
| **M-5** | Deal Room | 🔵 LOW | believed | Brand has no open-dispute control |
| **D-4** | Campaigns | 🔵 LOW | believed | Stale backend line-number citations |
| **D-7** | Campaigns | 🔵 LOW | believed | `/brand/campaigns/:id/tracking` has no inbound link |
| **P-1** | Payments | 🔵 LOW | believed | `/wallet/escrow/release` + `/payout` have no client caller *(was CRITICAL — false alarm)* |
| ~~M-2~~ | ~~Deal Room~~ | ⬆️ **PROMOTED** | — | Re-verified at source and re-filed as **C-1** |
| ~~M-3~~ | ~~Contracts~~ | ⬆️ **PROMOTED** | — | Re-verified at source and re-filed as **C-2** |
| ~~M-4~~ | ~~Contracts~~ | ⬆️ **PROMOTED** | — | Re-verified at source and re-filed as **C-3** |
| ~~M-1~~ | ~~Deal Room~~ | 🚫 STRUCK | — | False alarm — release IS wired via deliverable approval |
| ~~D-10~~ | ~~Deal Room~~ | 🚫 STRUCK | — | False alarm — cross-worktree grep leak |

**26 live defects · 7 HIGH · 0 CRITICAL · 1 oracle-proved · 2 struck.**
*(Plus P-5/P-6/P-7 MEDIUM and P-8 LOW logged by red-team in §53-58. Part 6 corrected 4 → 8 defects; Pipeline 0 → 5.)*

### Six surfaces traced. Zero broken endpoints.

Every backend route across dashboard, campaigns, deal room, discover, chat, payments, pipeline, timeline and contracts **exists and is correctly mapped**. Every line citation that survived verification checked out. All 22 defects are client-side, in four shapes:

| Shape | Defects |
|-------|---------|
| **A working API has no caller / is disabled** | C-3, P-1, P-2, D-9, D-14, M-5 |
| **A response is returned and discarded** | D-11, D-2, D-6 |
| **A zero or demo constant stands in for real data** | P-3, C-2, D-1, D-3, D-5 |
| **A proven pattern was not adopted on a second surface** | T-1, D-13 (SSE exists in `brand-chat.tsx`) |

`tsc` exited 0 on every run in this document.

> 🚫 **The original closing claim — "Pipeline is the proof that this class of defect is preventable here" — is FALSIFIED by PL-2.** `brand-pipeline-stage.ts` was extracted and tested precisely to end status-mapping drift. It did not: `DashboardService.java:126` holds a **fifth** copy of that switch, in Java, and it disagrees with the board on three statuses. The dashboard card and the pipeline board show a brand two different answers in the same session — the exact CR-30 symptom, still live.
>
> **The real lesson is narrower and more useful:** extracting and testing *one* copy of shared logic does not eliminate drift while other copies exist — especially across the language boundary, where no test or typechecker spans both. The 17/17 green suite proves the client copy is self-consistent, not that the system agrees with itself. A contract test asserting the server's `bucketFor()` and the client's mapper produce the same bucket for every `CollaborationStatus` is the gate that would actually close this class.

---

*Audit produced by static code trace, bidirectional caller census, `vitest` and `tsc` on branch `fix/brand-audit-remediation`. No live server was probed.*

---
---

# PART 6 — CTO VERIFICATION (Priya)

> **Scope:** §59-64 only (Pipeline, Timeline, Contracts). Parts 1-5 verified separately.
> **Method:** every claim re-read at source; the `vitest` oracle re-run by me, not trusted from the text; both API layers (`api.ts`, `meera-api.ts`) and `src/hooks/**` swept; aliased/destructured/chained import forms all tried; searches scoped to `git ls-files` (no `.claude/worktrees/` leakage).
> **Branch:** `fix/brand-audit-remediation` · **Date:** 2026-08-09

## 65. Verdict — **NEEDS_FIX**

All three declared Contracts defects are real. The Timeline section is the most accurate section in this document. **The Pipeline "clean" verdict is NOT deserved** — the surface has five unreported defects, one of which directly contradicts the closing claim of §64.

| Section | Ruling |
|---------|--------|
| §60 Pipeline "0 defects — clean" | ❌ **OVERTURNED** — 5 missed defects (3 MEDIUM, 2 LOW) |
| §61 Timeline | ✅ **ACCURATE** — 1 stale citation, 1 missed corroborating fact |
| §62 C-1 | ✅ REAL BUG — but stated **impact is false** |
| §62 C-2 | ✅ REAL BUG — but quoted **evidence is false** |
| §62 C-3 | ✅ REAL BUG — confirmed on every line, understated if anything |

**Method note on my own pass:** my first grep for `messages.stream` in `brand-chat.tsx` returned 0 and I nearly filed the audit's SSE claim as a false alarm. It streams via the `messagesApi` alias (`brand-chat.tsx:1146`). The audit was right and my bare-name grep was wrong — the exact trap the method warnings describe.

---

## 66. Per-defect ruling — Contracts (§62)

### C-1 — **REAL BUG at the API contract. Impact claim is FALSE.**

Re-read at source. The mechanism is exactly as described:

| Claim | Line | Result |
|-------|------|--------|
| `@RequestParam(required = false) String dealId` | `ContractController.java:49` | ✅ exact |
| creator branch forwards `dealId` | `:51` `listForCreator(principal, dealId)` | ✅ exact |
| brand branch drops it | `:54` `listForBrand(principal, workspace.getId())` | ✅ exact |
| no brand overload accepting `dealId` | `ContractService.java:823` `listForBrand(AuthPrincipal, String workspaceId)` — sole signature; `:843` `listForCreator(AuthPrincipal, String dealId)` | ✅ confirmed, checked as instructed |

**But the stated consequence does not happen.** §62 asserts *"The FE sends it (`contracts.list(role, dealId)`, `api.ts`), so a brand asking for one deal's contracts receives every contract in the workspace"* — and the table row 5 writes the call as `contracts.list(role, dealId)`.

- `api.ts:2120` — `list: (role: Role, dealId?: string)`. **`dealId` is optional.**
- The **only** brand call site in the tree is `contracts-and-deliverables.tsx:552` — `api.contracts.list('brand')`. **No `dealId` argument.**

No brand UI requests a filtered list today, so no brand sees wrong rows. This is a latent contract defect (a param accepted and silently ignored), not a live wrong-data bug. The reasoning in §62 about why accepted-and-ignored is worse than missing is sound and I endorse the fix — but the severity was set from an impact that does not exist.

**Ruling: REAL BUG — downgrade 🟡 MEDIUM → 🔵 LOW (latent).** Fix stands: add the overload or reject the param.

### C-2 — **REAL BUG. The quoted evidence is FALSE.**

§62 quotes `CONTRACT_DEAL_ROOM` as a *"one-entry demo map"*. It is not. `contracts-and-deliverables.tsx:98-102`:

```ts
const CONTRACT_DEAL_ROOM: Record<string, string> = {
  'contract-1': 'deal-1',
  'contract-2': 'deal-3',
  'contract-3': 'deal-4',
};
```

**Three entries, not one.** The audit's code block omitted two lines. Everything else verified exact:

| Claim | Result |
|-------|--------|
| `isApiLive` imported at `:3` | ✅ exact (`import { api, isApiLive, ApiError, … }`) |
| used at `:504` (`const liveApi = isApiLive();`) | ✅ exact |
| `:913` Message link, `:921` primary "Open in Deal Room" CTA | ✅ exact, both `?? 'deal-1'` |
| neither link gated by `liveApi` | ✅ confirmed |
| real contract ids miss the map → fall to `'deal-1'` | ✅ holds |

The conclusion survives — and is *stronger* than written, since three demo keys make the map look more legitimate to a future reader than one would. But an audit that misquotes the code it is indicting cannot be relied on downstream. **Ruling: REAL BUG, 🟡 MEDIUM stands. Evidence block must be corrected.**

**§63 open item closed:** `ContractApiRecord` **does** carry the deal linkage — `collaborationId` at `api.ts:2088`. The proposed fix is implementable as written.

### C-3 — **REAL BUG. Confirmed on every line, and understated.**

| Claim | Result |
|-------|--------|
| stale comment at `:903-905` | ✅ exact — *"No PDF-export endpoint exists in src/lib/api.ts (contracts facade only has list/get/generate/sign)"* |
| `<Button … disabled>` at `:906` | ✅ exact |
| `api.contracts.pdfDownloadUrl` exists | ✅ `api.ts:2180` → `ContractController.java:114` `GET /{contractId}/pdf-download-url` |
| `deal-contract-tab.tsx:80` calls it | ✅ exact — `const { url } = await api.contracts.pdfDownloadUrl('brand', contractId);` |

**Understated — there are three working download paths, not one.** The audit cited one:
- `deal-contract-tab.tsx:80` (brand)
- `creator-deal-contract-tab.tsx:98` (creator) — not cited
- `contract-panel.tsx:12` imports `downloadContractPDF` from `@/lib/contract-generator` — the **Timeline** panel, in the same feature area §61 audited, already has a download path — not cited

**Ruling: REAL BUG, 🔵 LOW stands.**

---

## 67. §61 Timeline — **ACCURATE**

Every structural claim re-verified and correct.

- **Component census:** 10 git-tracked files, importer counts match **exactly** — 6 event-cards ×1, `timeline-event` ×1, `contract-panel` ×1, `collaboration-timeline` ×1 (`brand-campaign-detail.tsx`), `deliverable-review-panel` **×3**. No orphans.
- **`deliverable-review-panel`** calls `await deliverablesApi.approve(deliverableId)` **itself** — ✅ real, but at **line 85, not 86**. It also uses `meta?.deliverableId` (`:75`) with an explicit guard (`:78`) and a real `requestRevision` at `:109`. The panel is genuinely functional; §61's refusal to judge it from its prop shape was the right call.
- **`contract-panel.tsx:12`** imports `signContract` from `@/lib/contract-generator` — ✅ **exact**. `contract-generator.ts:214` — `await api.contracts.sign(signedBy, contractId, {name, agreedAt})` — ✅ **exact**. Signing is real.
- **Endpoint citations** `DealController:114 / :151 / :160` — ✅ all three exact.

### T-1 — **REAL BUG. Confirmed.**

`collaboration-timeline.tsx` matched **0** occurrences of `stream|EventSource|subscribe|setInterval|refetch|poll` (case-insensitive). It calls only `api.messages.list` (`:196`), `markRead` (`:198`), `send` (`:353`). No realtime.

The comparison table is correct: `brand-chat.tsx` **does** stream — `messagesApi.stream('brand', dealId, {…})` at `:1146`, with `onStatusChange` reconnect state (`:1176`) and gap refetch (`:1185-1186`). **The citation `brand-chat.tsx:1183-1184` is stale** — those lines are comment text; the stream opens at `:1146` and the gap refetch is `:1185-1186`.

**MISSED — the server side is already built.** `DealController.java:134` exposes `GET /deals/{dealId}/messages/stream`, and `api.ts` already wraps it. T-1 is not "a pattern nobody built" — it is a **live SSE endpoint two of three brand surfaces decline to call**. That strengthens T-1 and D-13: the fix is a client-only change against an endpoint that exists and is proven in production by `brand-chat.tsx` and `creator-chat.tsx`.

---

## 68. §60 PIPELINE — the "clean" verdict is **NOT DESERVED**

### What the praise got right — verified, not assumed

| Claim | Result |
|-------|--------|
| Exactly **one** API call | ✅ `brand-pipeline.tsx:393` `dealsApi.list('brand')` — the only `api.`/`*Api.` call in the file |
| Zero drag/drop handlers | ✅ 0 matches for `drag\|onDrop\|draggable\|moveStage` |
| **5** `onClick` handlers, all bound | ✅ exactly 5 (`:304`, `:503`, `:582`, `:659`, `:692`) — none unbound |
| `vitest brand-pipeline-stage.test.ts` → 17/17 | ✅ **I ran it. 1 file passed, 17 tests passed, 25 ms.** |
| CR-30 / `TERMS_AGREED` mapping | ✅ accurate — `TERMS_AGREED` → `deal-stage.ts:120` `'negotiating'` → `brand-pipeline-stage.ts:63` `NEGOTIATING`. Not `CONTRACTED`. |
| `deals.list` does not truncate | ✅ (not claimed, but I checked — `DealService.list` streams to `toList()`, no cap, no pager; the D-2 failure mode does not repeat here) |

The engineering *is* good. The verdict "0 defects" is still wrong.

### ❌ The one praise claim that is FALSE

> §60: *"`CANCELLED`/`DISPUTED` return `null` explicitly rather than falling through a `default:`, so a future `CollaborationStatus` cannot be silently swallowed — it must be classified by the shared `DealStage` switch first."*

The shared switch **has a `default:`**. `deal-stage.ts:137-138`:

```ts
default:
  return 'negotiating';
```

A new `CollaborationStatus` is silently classified `'negotiating'` and lands in the **Negotiating** column with **no compile error anywhere** — `brand-pipeline-stage.ts`'s switch is over the closed `DealStage` union and stays exhaustive. The exhaustiveness guarantee the audit praised does not exist. §60 repeated a source comment as a verified property; I checked the switch it delegates to and it defaults.

---

## 69. MISSED — five defects on the Pipeline surface

### 🟡 PL-1 MEDIUM — the entire SLA "at risk" feature is dead in live mode

`slaHoursRemaining` is set **only** in `mockCollaborations` (`:140, 156, 172, 189, 219, 235, 251`). `mapDealToCollaboration` (`:107-127`) never sets it, and `Deal` (`api.ts:1459-1479`) carries no SLA field. So with `isApiLive()` true, `isAtRisk()` is always falsy and:

| Line | Consequence in live mode |
|------|--------------------------|
| `:421` `atRiskCount` | always **0** |
| `:688` `{atRiskCount > 0 && <Button …>}` | the **"N at risk" filter never renders** |
| `:313` SLA warning banner | never renders |
| `:543` List-view SLA column | permanently blank on every row |
| `:605` Timeline-view "Xh left" badge | never renders |
| `:299` red ring / `bg-red-50` risk highlight | never applies |

**This directly falsifies §60's own praise.** The audit certified all 5 `onClick` handlers as wired and named "at-risk filter toggle" among them. That control is **unreachable in production** — it is gated behind a counter that is structurally always zero.

Note the file documents `engagementRate` and `matchScore` as *"No DTO source on GET /deals"* (`:57-60`) and correctly hides the "Match" stat rather than fabricating it. `slaHoursRemaining` (`:55`) received **no such treatment** — it is an unhandled gap, not a deliberate omission. This is the same shape as D-3 and D-5, which the audit rated MEDIUM and HIGH on the campaigns surface.

### 🟡 PL-2 MEDIUM — the server keeps a fifth copy of the stage switch, and it disagrees

§64 closes with *"Pipeline is the proof that this class of defect is preventable here."* The audit swept the frontend for private copies of the mapping and declared the drift eliminated. It never checked the backend, which has its own:

`DashboardService.java:126` `bucketFor()` — powers `GET /dashboard/pipeline` (`DashboardController:41`), rendered on the brand Dashboard by `dashboard-page.tsx:104` → `PipelineCard` (`:308`):

```java
case INVITED, APPLIED, SHORTLISTED, IN_NEGOTIATION, TERMS_AGREED -> NEGOTIATING;
case CONTRACT_PENDING, CONTRACTED                               -> CONTRACTED;
case IN_PROGRESS, REVIEW_PENDING, REVISION_REQUESTED            -> IN_PROGRESS;
case COMPLETED                                                  -> COMPLETED;
case CANCELLED, DISPUTED                                        -> null;
```

Four buckets (`DashboardService.java:40-45`: Negotiating, Contracted, In Progress, Completed) against the board's six columns:

| Status | Dashboard says | Pipeline board says |
|--------|----------------|---------------------|
| `INVITED` | **Negotiating** | **Outreach** |
| `APPLIED` / `SHORTLISTED` | **Negotiating** | **Outreach** |
| `REVIEW_PENDING` / `REVISION_REQUESTED` | **In Progress** | **Review** |
| `COMPLETED` | **Completed** | **Settled** |

A brand reads "Negotiating 3 · In Progress 2" on the Dashboard, clicks through, and the board says "Outreach 3 · Review 2" for the same deals. **Same brand, same session, two different answers — verbatim the CR-30 defect the module was extracted to kill**, relocated from FE↔FE to FE↔BE. `TERMS_AGREED` agrees across the boundary; nothing else does. The extraction closed four copies of five.

### 🟡 PL-3 MEDIUM — `PipelineCard` has no color for the only terminal bucket the server emits

Falling out of PL-2 and provable at source. `dashboard-page.tsx:299-306`:

```ts
const STAGE_COLOR: Record<string, string> = {
  Outreach, Negotiating, Contracted, 'In Progress', Review, Settled
};
```

Six keys — the **board's** vocabulary. The server emits four labels, and `Completed` is **not among the keys**. `Outreach`, `Review` and `Settled` are dead keys the server can never emit. At `:327` and `:341` the lookup is `STAGE_COLOR[stage.stage] || 'bg-muted'`, and the segment renders `text-white`:

**Every completed deal renders as a `bg-muted` bar with `text-white` text — white on pale grey, unreadable** (this theme is pale-bg/strong-fg), and mis-colored relative to every other segment. Three of the six declared colors are unreachable.

### 🔵 PL-4 LOW — two more fields hardcoded empty in live mode, undocumented

`mapDealToCollaboration` sets `platforms: []` (`:118`) and `creatorFollowers: ''` (`:115`) unconditionally. Consequences: the platform-icon row (`:366-368`) is empty on every card, and the follower-count span renders blank on every card (`:328`) and every list row (`:519`). Unlike `engagementRate`/`matchScore`, neither carries a "no DTO source" comment — a future reader will read them as data that failed to load.

### 🔵 PL-5 LOW — `isAtRisk` truthiness bug

`:285` — `const isAtRisk = (collab) => collab.slaHoursRemaining && collab.slaHoursRemaining < 12;`

Returns `number | boolean | undefined`, not `boolean`. When `slaHoursRemaining` is **`0`** — a deal exactly at SLA breach, the most urgent case — the `&&` short-circuits on the falsy `0` and the deal is classified **not at risk**. Masked in production today only because PL-1 leaves the field permanently undefined; live in demo mode and a landmine the moment PL-1 is fixed. Needs `!= null &&`.

---

## 70. Corrected Part 6 register

| ID | Surface | Was | Now | Ruling |
|----|---------|-----|-----|--------|
| **PL-1** | Pipeline | — | 🟡 MEDIUM | **NEW** — SLA at-risk feature dead in live mode; the audit's own "wired onClick" is unreachable |
| **PL-2** | Pipeline / Dashboard | — | 🟡 MEDIUM | **NEW** — server `bucketFor` is a fifth copy of the stage switch and disagrees with the board |
| **PL-3** | Dashboard | — | 🟡 MEDIUM | **NEW** — `Completed` has no `STAGE_COLOR`; renders white-on-muted, 3 dead keys |
| **T-1** | Timeline | 🟡 MEDIUM | 🟡 MEDIUM | **CONFIRMED** — and the SSE endpoint already exists (`DealController:134`) |
| **C-2** | Contracts | 🟡 MEDIUM | 🟡 MEDIUM | **CONFIRMED** — map has **3** entries, not 1; audit's code block is wrong |
| **C-1** | Contracts | 🟡 MEDIUM | 🔵 **LOW** | **DOWNGRADED** — real at the contract, but no brand call site passes `dealId` |
| **C-3** | Contracts | 🔵 LOW | 🔵 LOW | **CONFIRMED** — 3 working download paths exist, audit cited 1 |
| **PL-4** | Pipeline | — | 🔵 LOW | **NEW** — `platforms`/`creatorFollowers` hardcoded empty, undocumented |
| **PL-5** | Pipeline | — | 🔵 LOW | **NEW** — `isAtRisk` returns `0` for a 0h-remaining deal |

**Part 6 corrected: 8 defects across the three surfaces (4 MEDIUM, 4 LOW) — not 4.** Pipeline goes from **0 → 5**.

### Citation corrections

| §  | Audit says | Actual |
|----|-----------|--------|
| §60 | "all 738 lines" | 739 |
| §60 | "a future `CollaborationStatus` cannot be silently swallowed" | `deal-stage.ts:137-138` has `default: return 'negotiating'` |
| §61 | `deliverable-review-panel.tsx:86` | `:85` |
| §61 | `brand-chat.tsx:1183-1184` | stream `:1146`, gap refetch `:1185-1186` |
| §62 | `CONTRACT_DEAL_ROOM` "one-entry" | three entries, `:98-102` |
| §62 | table row 5 `contracts.list(role, dealId)` | `api.contracts.list('brand')` — one arg |
| §62 | "The FE sends it" | no brand call site passes `dealId` |

### What this verification did NOT check

- Runtime behaviour against a live server — static trace + the one `vitest` oracle only.
- `ContractService.listForBrand`'s repository query correctness (§63's open item stands).
- Two-party signature **ordering** inside `ContractService.sign`.
- Timeline event ordering/dedup.
- Whether `GET /dashboard/pipeline` and `GET /deals` return consistent *counts* — I compared the two **bucketing rules** at source (PL-2), not live payloads.
- PDF generation correctness behind `pdfDownloadUrl`.

### Fix order

1. **PL-2 + PL-3** — one change: make the server emit the board's six-column vocabulary (or the board consume the server's), then delete the dead `STAGE_COLOR` keys. Highest ratio of user-visible contradiction to effort, and it closes the last copy of the switch CR-30 was written to eliminate.
2. **PL-1** — either add an SLA field to `DealResponse`, or delete the at-risk UI. Do not leave a control that cannot render. Fix **PL-5** in the same commit.
3. **C-2** — derive from `ContractApiRecord.collaborationId` (`api.ts:2088`), delete `CONTRACT_DEAL_ROOM`.
4. **C-3** — delete the comment, drop `disabled`, copy `deal-contract-tab.tsx:80`.
5. **T-1** — adopt `messagesApi.stream` from `brand-chat.tsx:1146`. Same commit as D-13.
6. **C-1 / PL-4** — cleanup.

---

*Verified by Priya (CTO) against primary sources on branch `fix/brand-audit-remediation`, 2026-08-09. `vitest brand-pipeline-stage.test.ts` re-run by me: 17/17. No live server probed.*

---
---

# PART 7 — Analytics · Reviews · Disputes API Audit

> **Scope:** the final three MANAGE-group surfaces. **This completes the brand navigation** — all 12 sidebar items are now traced.
> **Method:** Static code trace + bidirectional caller census + `vitest` + `tsc`.
> **Branch:** `fix/brand-audit-remediation` · **Date:** 2026-08-09

> **Method note — applying the Part 6 lesson.** Part 6 declared Pipeline "clean" and stopped looking; verification found 5 defects. This pass therefore audited each surface for the specific failure modes found elsewhere in this document, and **states below what was actively ruled out**, not merely what was seen:
> fabricated/demo constants outside `isApiLive()` (C-2, P-3) · discarded pagination meta (D-2) · `?? 0` for unknown (D-1) · bound-but-unreachable controls (PL-1) · client/server logic disagreement (PL-2) · orphaned components (D-10) · missing SSE where a thread renders (T-1/D-13).

---

## 65. Audit Summary

| Surface | APIs | Backend match | Defects |
|---------|-----:|:-------------:|---------|
| **Analytics** (`/brand/analytics`) | 4 | ✅ 4/4 | 0 |
| **Reviews** (`/brand/reviews`) | 3 | ✅ 3/3 | 0 |
| **Disputes** (`/brand/disputes`) | 1 | ✅ 1/1 | 2 (1 MEDIUM, 1 LOW) |

| Oracle | Result |
|--------|--------|
| `npx tsc --noEmit` | ✅ **0 errors, exit 0** |
| `vitest brand-disputes-api.test.ts` | ✅ 2/2 passing |
| `vitest brand-disputes.test.tsx` | ✅ 4/4 passing |

**Verdict: all 8 endpoints exist, match, and are reached. Analytics and Reviews are genuinely clean under adversarial check. Disputes is read-only by construction — the brand can see disputes but cannot open one.**

---

## 66. 🔬 Methodological finding — a third false-zero pattern

This pass produced a **false zero-caller result** that is worth recording, because it defeats the census technique used throughout this document.

An initial census reported:

```
brandReviews.create       → 0 callers
brandReviews.listReceived → 0 callers
```

Both are **false**. `collaboration-reviews-panel.tsx:88` selects the client **at runtime**:

```ts
const reviewsClient = role === 'creator' ? api.creatorReviews : api.brandReviews;
…
const rows = await reviewsClient.listReceived();   // :135
await reviewsClient.create({ … });                 // :184
```

The string `brandReviews.create` **never appears in the codebase** — and never will. No static search for it can succeed.

This is the **third** distinct false-zero pattern encountered:

| # | Pattern | Where it bit | Detection |
|---|---------|-------------|-----------|
| 1 | Aliased import — `deals as dealsApi` | Parts 3, 5, 6 (3×) | search the alias too |
| 2 | Second API layer — `meera-api.ts` | Part 5 — produced a **false CRITICAL** | search both layers |
| 3 | **Runtime-selected client** — `role === 'x' ? a : b` | here | ⚠️ **no search pattern works** — must read the consuming component |

**Consequence for this document:** any "zero callers" claim resting on a single grep is not proof. The surviving zero-caller findings (P-1, D-14, DP-2) were re-checked by opening the consuming surfaces; they hold. But the technique has a floor, and this is it.

---

## 67. ✅ ANALYTICS — clean under adversarial check

| # | UI Action | FE Call | HTTP | Endpoint | Backend | Status |
|---|-----------|---------|------|----------|---------|--------|
| 1 | Build creator roster | `deals.list('brand')` | GET | `/deals` | `DealController:65` | ✅ |
| 2 | Metrics + trend chart | `analytics.getCreatorMetrics(id, start, end)` via `useCreatorMetrics` | GET | `/analytics/creators/{id}/metrics` | `AnalyticsController:61` | ✅ |
| 3 | Brand-safety badge | `analytics.getCreatorScores(id)` via `useCreatorScores` | GET | `/analytics/creators/{id}/scores` | `:74` | ✅ |
| 4 | Demographics | `analytics.getCreatorDemographics(id)` via `useCreatorDemographics` | GET | `/analytics/creators/{id}/demographics` | `:84` | ✅ |

Also present and reached: `/analytics/creators/{id}/media` (`:101`) via `useContentPerformance` → `ContentPerformancePanel`.

**Role-aware routing is handled honestly** — `useCreatorMetrics:76-77` selects `creatorAnalytics.getMyMetrics()` for a creator viewing themselves vs `analytics.getCreatorMetrics(creatorId)` for a brand, against two distinct controllers (`CreatorAnalyticsController` `/creator/analytics/me` vs `AnalyticsController` `/analytics/creators`). Same runtime-selection shape as §66, but here it crosses a genuine authorization boundary.

**Actively ruled out:**

| Failure mode | Result |
|--------------|--------|
| Dead controls (PL-1) | ❌ none — 2 buttons, 1 `onClick` + 1 `<Button asChild><Link to="/brand/discover">`; the second is a correct Link pattern, not an unbound handler |
| Hardcoded demo constants (C-2/P-3) | ❌ none found |
| Empty-state honesty | ✅ a brand with no deals gets a real "Discover Creators" empty state (`:176-178`), not a fabricated roster |
| Error handling | ✅ explicit error state with a working "Try again" (`:155`) |

> ⚠️ **Not ruled out:** whether the metrics *values* are accurate. Part 2 established that campaign analytics are `CREATOR_REPORTED`, never platform-verified. Reachability ≠ correctness.

---

## 68. ✅ REVIEWS — clean under adversarial check

`brand-reviews.tsx` is a 20-line shell rendering `CollaborationReviewsPanel`; the shared panel holds the logic for both roles.

| # | UI Action | FE Call | HTTP | Endpoint | Backend | Status |
|---|-----------|---------|------|----------|---------|--------|
| 5 | Reviewable collaborations | `deals.list('brand')` | GET | `/deals` | `DealController:65` | ✅ |
| 6 | **Submit a review** | `reviewsClient.create(…)` (`:184`) | POST | `/brand/reviews` | `BrandReviewController:36` | ✅ |
| 7 | Reviews received | `reviewsClient.listReceived()` (`:135`) | GET | `/brand/reviews/received` | `:45` | ✅ |
| 8 | Flag an unfair review | `api.brandReviews.flag(id, reason)` (`:100`) | POST | `/brand/reviews/{id}/flag` | `:51` | ✅ |

**All 5 `<Button>`s have `onClick` — no dead controls.**

**Server-error mapping is specific, not generic** (`:194-198`) — distinct messages for *"You have already reviewed this collaboration"* and *"Reviews are only allowed after the collaboration is completed"*, with an `ApiError` fallback. Duplicate-review state is also tracked client-side (`reviewedIds`) so a submitted card cannot be re-submitted.

---

## 69. DISPUTES — read-only by construction

| # | UI Action | FE Call | HTTP | Endpoint | Backend | Status |
|---|-----------|---------|------|----------|---------|--------|
| 9 | Dispute list | `brandDisputes.list()` | GET | `/brand/disputes/list` | `BrandDisputeController:38` | ✅ |

### 🟡 DP-1 — The brand cannot open a dispute (MEDIUM)

`BrandDisputeController` exposes **only `@GetMapping`s** (`:38`, `:45`). There is no create route on the brand controller, no `create` method on the `brandDisputes` client, and no control anywhere in the brand UI.

The capability exists and is explicitly two-sided. `DealController.java:177` serves `POST /deals/{dealId}/disputes`, and this repo's own client JSDoc describes it as:

> **"either party may open"** — `POST /deals/:dealId/disputes` (`DealController.java:167`)

**Only the creator side calls it** (`creator-disputes.tsx:106`). So of the two parties the endpoint is built for, one has no path to it. A brand facing a bad deliverable or a non-delivering creator can read disputes but cannot raise one.

*(This confirms and re-files **M-5** from Part 3, now verified at both ends: endpoint present, brand client and UI both absent.)*

### ⚠️ Important nuance — this is DELIBERATE, not an oversight

Verification surfaced a comment in the client that this audit initially missed. `api.ts` (the `brandDisputes` block) states plainly:

> Opening a dispute is intentionally **NOT** wired here: this page is read-only by design.

**That changes what DP-1 is.** It is not a wiring bug or a forgotten endpoint — it is a recorded product decision, and the code says so at the point of omission. Judged as engineering, there is nothing broken here.

It remains on the register as a **product gap, not a defect**, on one ground only: `DealController.java:177` is documented as *"either party may open"*, and today only one party can. That asymmetry deserves an explicit product answer — brands may be expected to raise disputes through support rather than self-serve, which would be a perfectly reasonable design. **Route this to product, not to engineering.**

**If product decides to wire it:** add `brandDisputes.create(dealId, payload)` and a "Raise dispute" control in the deal room — `creator-disputes.tsx:106` is the pattern to copy.

### 🔵 DP-2 — Superseded paginated endpoint has no caller (LOW)

`BrandDisputeController` carries two list routes:

| Route | Line | Purpose | Caller |
|-------|------|---------|:------:|
| `GET /brand/disputes/list` | `:38` | display-shaped rows (P2-14) | ✅ FE |
| `GET /brand/disputes` | `:45` | *"Original paginated dispute list (B7 — returns DisputeResponse without display fields)"* | ❌ none |

The bare route is superseded by `/list` and self-documents as the original. Dead surface — remove it or note it as intentionally retained.

### What Disputes does right

**The "View deal room" link uses a real id** (`:146`):

```tsx
<Link to={`/brand/chat?deal=${dispute.collaborationId}`}>
```

This is exactly what the Contracts page gets wrong in **C-2**, where the same navigation falls through a demo map to a hardcoded `?? 'deal-1'`. Same link, same destination, one surface derives the id from the record and the other does not.

---

## 70. NOT CHECKED — Part 7 (law 5)

| Not verified | Why |
|-------------|-----|
| Runtime behaviour against a live server | Static trace + unit tests only |
| **The disputes tests only exercise MOCK mode** | Both suites are green, but `brand-disputes-api.test.ts` is titled *"(mock mode)"* and `brand-disputes.test.tsx` asserts *"mock dispute rows"*. **Neither covers the live `GET /brand/disputes/list` path.** Green here does not mean the live path works |
| Whether analytics metric values are correct | Reachability only; values are `CREATOR_REPORTED` per Part 2 |
| Whether review eligibility rules are enforced server-side | FE maps the errors; the service rules were not traced |
| Whether `listDisplayForBrand` returns correctly scoped rows | Repository/service query not traced |
| Whether a brand-side dispute would be authorized if wired | `DealController:177` guards not executed |
| Client/server logic disagreement (the PL-2 class) | No duplicated status/bucket switch was found on these three surfaces — but this was a targeted search, not a proof of absence |

---

## 71. Master Defect Register — all seven audits · brand surface complete

**All 12 sidebar navigation items are now traced** (7 MAIN + 5 MANAGE), plus Settings, the Command Bar, and the money path.

| ID | Surface | Severity | Evidence | Summary |
|----|---------|----------|----------|---------|
| **P-1′** | Payments | 🔴 HIGH | believed | Meera-funded escrow holds releasable by neither path |
| **P-3** | Contracts | 🔴 HIGH | believed | `\|\| 50000` — both parties told "₹50,000 secured in escrow" on a zero-value deal |
| **P-4** | Payments | 🔴 HIGH | believed | `withdraw-${Date.now()}` idempotency key defeats server dedupe |
| **D-11** | Discover → Chat | 🔴 HIGH | **proved** | Invite/offer redirect drops the deal ID (2 failing tests) |
| **D-1** | Dashboard | 🔴 HIGH | believed | Null runway renders as red "CRITICAL / 0d" on funded wallets |
| **D-2** | Campaigns | 🔴 HIGH | believed | List truncates at 100; meta discarded, no pager |
| **D-5** | Campaigns | 🔴 HIGH | believed | `collaboratorsCount` always 0, rendered as fact |
| **C-2** | Contracts | 🟡 MEDIUM | believed | Demo `deal-1` id breaks "Open in Deal Room" on every real contract |
| **PL-2** | Pipeline | 🟡 MEDIUM | believed | 5th copy of the stage switch (Java) disagrees with the board on 3 statuses |
| **PL-1** | Pipeline | 🟡 MEDIUM | believed | SLA "at-risk" feature dead in live mode; filter button never renders |
| **PL-3** | Dashboard | 🟡 MEDIUM | believed | Pipeline card has no `STAGE_COLOR` for `Completed` — unreadable segment |
| **DP-1** | Disputes | 🟡 MEDIUM* | believed | Brand cannot open a dispute — endpoint is two-sided, only the creator calls it. **\*Documented as intentional ("read-only by design"); a product question, not an engineering defect** |
| **T-1** | Timeline | 🟡 MEDIUM | believed | No realtime — never opens the SSE stream |
| **D-13** | Messages | 🟡 MEDIUM | believed | No realtime — `/brand/messages` never opens the SSE stream |
| **D-12** | Messages | 🟡 MEDIUM | believed | 10 enabled controls with no handler (incl. destructive "Delete") |
| **P-2** | Payments | 🟡 MEDIUM | believed | Escrow funding reachable only via the Meera AI chat |
| **D-8** | Deal Room | 🟡 MEDIUM | believed | Sidebar "Deals" → room missing the shipment control |
| **D-9** | Deal Room | 🟡 MEDIUM | believed | Deliverable **Reject** route unreachable |
| **D-3** | Campaigns | 🟡 MEDIUM | believed | Progress bar always 0%; "Sort by Progress" dead |
| **D-6** | Campaigns | 🟡 MEDIUM | believed | `sortBy`/`sortOrder` accepted by backend, never sent |
| **DP-2** | Disputes | 🔵 LOW | believed | Superseded `GET /brand/disputes` has no caller |
| **C-3** | Contracts | 🔵 LOW | believed | "Download PDF" disabled behind a factually wrong comment |
| **C-1** | Contracts | 🔵 LOW | believed | `?dealId=` filter dropped for brands — latent, no live caller |
| **PL-4** | Pipeline | 🔵 LOW | believed | `platforms: []` / `creatorFollowers: ''` hardcoded empty, undocumented |
| **PL-5** | Pipeline | 🔵 LOW | believed | `isAtRisk` short-circuits on `0` at the SLA boundary |
| **D-14** | Discover | 🔵 LOW | believed | 4 backend creator endpoints with no caller |
| **D-4** | Campaigns | 🔵 LOW | believed | Stale backend line-number citations |
| **D-7** | Campaigns | 🔵 LOW | believed | `/brand/campaigns/:id/tracking` has no inbound link |
| **P-1** | Payments | 🔵 LOW | believed | `/wallet/escrow/release` + `/payout` have no client caller *(was CRITICAL — false alarm)* |
| ~~M-1~~, ~~D-10~~ | — | 🚫 STRUCK | — | Both false alarms (see Parts 5, 3) |

**28 live defects · 7 HIGH · 0 CRITICAL · 1 oracle-proved · 2 struck.**
*(Plus P-5/P-6/P-7 MEDIUM and P-8 LOW in §53-58, and M-5 re-filed as DP-1.)*

---

## 72. Closing — the whole brand surface, in one finding

**Across every brand surface — dashboard, campaigns, deal room, discover, chat, messages, payments, pipeline, timeline, contracts, analytics, reviews, disputes — not one backend endpoint is broken, missing, or mismatched.** Every route exists. Every DTO aligns. Every backend line citation that survived verification was exact.

**All 28 defects are on the client side**, in five shapes:

| Shape | Defects |
|-------|---------|
| **A working API is never called, or is disabled** | DP-1, P-1, P-2, D-9, D-14, C-3, DP-2 |
| **A response is returned and discarded** | D-11, D-2, D-6 |
| **A zero or demo constant stands in for real data** | P-3, C-2, D-1, D-3, D-5, PL-4 |
| **A control is bound but unreachable, or unbound entirely** | PL-1, D-12, D-3 |
| **The same rule is implemented twice and the copies disagree** | PL-2, PL-3 |

### What the gates could and could not see

`tsc --noEmit` exited **0 on every run in this document**. Every defect above typechecks. In mock mode, nearly all of them demo correctly — which is why a demo has never surfaced them.

Three defects *were* caught by gates, and all three were caught by tests **someone chose to write**: D-11 (2 failing tests that were already red), Pipeline's CR-30 drift, and the review-eligibility error mapping. No linter or compiler found any of the other 25.

### The three gates that would close the most ground

1. **A live-mode smoke pass.** The single highest-yield gate. Mock mode masks D-5, PL-1, C-2, P-3 and D-3 completely — each renders plausibly on fixtures and wrongly on real data.
2. **A cross-language contract test.** PL-2 and PL-3 exist because the same status→bucket rule lives in five places, one of them Java. No TS test or typechecker spans that boundary.
3. **A reachability check in CI** — assert every exported API client method has a caller, and every backend `@Mapping` has a client. That alone would have flagged DP-1, D-9, D-14, C-3, DP-2 and P-1 on the commit that created them.

> ⚠️ **What this document does not establish.** Every finding is `believed` except D-11. **No live server was probed and no payment was executed.** This audit proves what the code *can* do, not what it *does* at runtime. Reachability is not correctness — and on the money path especially, the two should not be confused.

---

*Part 7 produced by static code trace, bidirectional caller census, `vitest` and `tsc` on branch `fix/brand-audit-remediation`. No live server was probed.*

---
---

# PART 8 — Settings · Help & Support · Notifications API Audit

> **Scope:** the avatar-menu surfaces and the notification bell. **This closes the brand audit** — every navigable brand surface is now traced.
> **Method:** Static code trace + bidirectional caller census + `vitest` + `tsc`.
> **Branch:** `fix/brand-audit-remediation` · **Date:** 2026-08-09

---

## 73. Audit Summary

> ⚠️ **Two rows below were wrong — corrected here; evidence in §80.** Help & Support was reported as 0 defects and has **3**. Notifications was reported as 2 and has **7**.

| Surface | APIs | Backend match | Defects (corrected) | Originally claimed |
|---------|-----:|:-------------:|---------------------|--------------------|
| **Settings** (`/brand/settings`) | 7 | ✅ **7/7 exact** | 1 LOW | 1 LOW |
| **Help & Support** (`/brand/help`) | 0 — static | n/a | **3** (1 MEDIUM, 2 LOW) | ~~0~~ |
| **Notifications** (bell, all pages) | 3 (via raw fetch) | ✅ 3/3 | **7** (3 MEDIUM, 4 LOW) | ~~2~~ |

| Oracle | Result |
|--------|--------|
| `npx tsc --noEmit` | ✅ **0 errors, exit 0** |
| `vitest api-contract.test.ts` | ✅ 3/3 passing |

**Verdict: every endpoint exists and matches. The notification bell is the finding — it bypasses the shared HTTP client and therefore has no token-refresh, on a component that renders on every brand page.**

> ⚠️ **This table is superseded by §80.6.** Independent verification confirmed all three named defects but found the counts understated: Help & Support is **1 MEDIUM + 2 LOW**, not `0`; Notifications carries **7**, not `2`. See **§80 — Part 8 Adversarial Verification**.

---

## 74. 🟡 DEFECT N-1 — The notification bell bypasses the shared HTTP client, losing 401 refresh (MEDIUM)

| Field | Detail |
|-------|--------|
| **Severity** | 🟡 MEDIUM — app-wide component silently dies on token expiry |
| **Where** | `src/hooks/useNotifications.ts:190, 216, 248` |
| **Blast radius** | `brand-layout.tsx` renders the bell on **every brand page** |

### Two implementations of the same endpoints

`useNotifications.ts` does **not** use the `api.ts` client. It declares its own base URL and calls `fetch` directly:

```ts
// :19  "API_BASE_URL is not exported from src/lib/api.ts"
const API_BASE_URL = import.meta.env?.VITE_API_BASE_URL || 'http://localhost:8080/api/v1';
…
await fetch(`${API_BASE_URL}/notifications`,          { headers: authHeader() })  // :190
await fetch(`${API_BASE_URL}/notifications/read`,     { … })                      // :216
await fetch(`${API_BASE_URL}/notifications/read-all`, { … })                      // :248
```

Meanwhile `api.ts` has `notifications.list` and `notifications.markRead` hitting the **same two routes** — with **zero callers** (see N-2).

### What the raw path loses

> ⚠️ **Citation correction (§80).** Three of the five `api.ts` line numbers cited below point at **JSDoc, not code** — `:237` is inside `bootstrap()`'s comment block, and proactive refresh is actually `ensureFreshToken` at `:304-313` (invoked `:332`); reactive-401 code is `:341`, `clearToken` is `:347`. The interceptor is real; the citations were sloppy. **In a document whose thesis is "comments are not evidence," three of five cites were comments.**
>
> ⚠️ **Trigger correction.** The scenario stated below — token expires mid-session — is **wrong**: the hook fires exactly one request, at mount, so nothing fires at expiry. The real trigger is **a page load while the stored token is already expired**. Recovery is *worse* than stated: `BrandLayoutWrapper` is the same component type at the same position for every brand route, so brand→brand navigation preserves the instance and the error state. **Only a hard reload clears it.**

`api.ts` implements a documented **H-19 401 → refresh → retry interceptor**:

| Feature | `api.ts` | `useNotifications.ts` |
|---------|:--------:|:---------------------:|
| Proactive token refresh before expiry | ✅ `:304-313` (`ensureFreshToken`), invoked `:332` — *corrected in verification; the original `:237` cite pointed into `bootstrap`'s JSDoc* | ❌ |
| Reactive 401 → refresh → single retry | ✅ `:256`, `:341` | ❌ |
| Concurrent-401 dedupe into one `/auth/refresh` | ✅ `:219` | ❌ |
| Clears stale token on failed refresh | ✅ `:317` | ❌ |
| Typed `ApiError` with server code/message | ✅ | ❌ — `throw new Error('Failed to fetch notifications')` |

The hook has **no 401 branch at all.** Every non-OK status collapses into one generic `Error`:

```ts
if (!res.ok) throw new Error('Failed to fetch notifications');   // :191
if (!res.ok) throw new Error('Failed to mark notification read'); // :221
if (!res.ok) throw new Error('Failed to mark all notifications read'); // :252
```

> ⚠️ The hook's `refresh` (`:59`, `:176`, `:270`) is a **data refetch callback**, not a token refresh. The names collide; the behaviours do not.

### What the brand experiences

When the access token expires, every other surface transparently refreshes and continues. The bell instead 401s, throws, and renders *"Couldn't load notifications"* — and because there is no retry-after-refresh, **it stays broken for the rest of the session** unless something else remounts it. Marking read fails the same way, silently reverting the optimistic update.

To the user this reads as "notifications are broken", on a component visible from every page.

**Fix:** route the hook through `http.request` (which is what `api.notifications.list`/`markRead` already do), and add a `readAll` method to the client for the third route. That deletes the duplicate and inherits refresh, dedupe and typed errors.

### 🔵 N-2 — The client methods it duplicates have zero callers (LOW)

`api.notifications.list` and `api.notifications.markRead` are correct, envelope-aware, refresh-capable — and **called from nowhere** (verified against git-tracked files with loose patterns, per §66). They are the shadow of the raw-fetch implementation that replaced them.

`POST /notifications/read-all` (`NotificationController:127`) exists on the backend and in the hook, but has **no client method** — the inverse gap. Fixing N-1 resolves both.

---

## 75. SETTINGS — wired; one stale comment

| # | UI Action | FE Call | HTTP | Endpoint | Backend | Status |
|---|-----------|---------|------|----------|---------|--------|
| 1 | Update workspace profile | `workspaces.updateMe(payload)` | PATCH | `/workspaces/me` | `WorkspaceController:65` | ✅ |
| 2 | Team member list | `workspaceMembers.list()` | GET | `/workspace/members` | `WorkspaceMemberController:74` | ✅ |
| 3 | Invite a member | `workspaceMembers.invite(...)` | POST | `/workspace/members/invite` | `:51` | ✅ |
| 4 | **Change password** | `auth.changePassword(...)` | POST | `/me/password` | `AccountController:105` (`@RequestMapping("/me")` `:47`) | ✅ — see C-4 |
| 5 | Notification preferences (read) | `notifications.getPreferences('brand')` (`:198`) | GET | `/notifications/preferences` | `NotificationController:224` | ✅ |
| 6 | Notification preferences (write) | `notifications.setPreference('brand', type, on)` (`:231`, `:270`) | POST | `/notifications/preferences` | `:241` | ✅ |
| 7 | Log out | `auth.logout(role)` | POST | `/auth/logout` | ✅ | ✅ |

Sub-routes `/brand/settings/verification` and `/brand/settings/billing` exist; billing uses `billing.initiateCheckout`, `cancelSubscription`, `downloadInvoicePdf` via `useBilling` (out of scope — money path covered in Part 5).

### 🔵 C-4 — Stale JSDoc claims the password endpoint may not exist (LOW)

`api.ts` (the `changePassword` block) states:

> Landing in parallel on the backend (Vikram); **a 404 here means it hasn't shipped yet**, not that the client is wrong.

**It has shipped.** `AccountController.java:105` serves `POST /me/password`, `AuthService.java:466` implements it (BR-05 — *corrected in verification from `:445`, which was stale*), and `AuthRateLimitFilter.java:366` rate-limits it by path. The comment describes a state of the world that no longer holds.

> 🔎 **Method note:** taking that comment at face value would have produced a **false HIGH** ("Change Password is broken — no backend"). It was the *comment* that was stale, not the code. This is the third time in this document a source comment contradicted the source (see also C-3, and Part 6's falsified exhaustiveness claim). **Comments in this repo are not evidence.**

Same class as C-3 and D-4 — a stale comment that misleads the next maintainer. Delete the caveat.

---

## 76. HELP & SUPPORT — static by design, and correctly so

> ⛔ **VERDICT OVERTURNED BY VERIFICATION (§80.4).** The "zero defects / both controls genuinely wired" claim below is **FALSE**. `openTour()` sets a store flag (`store.ts:127`) that **nothing in the repo reads** — "Take the tour again" is a dead control, the exact PL-1 failure mode this section claims to have checked for. Read §80.4 before trusting anything in this section.

`brand-help.tsx` (135 lines) makes **zero API calls**. That is a design choice, not a gap: the page is a router into two live destinations, and **both controls are genuinely wired** (checked against the PL-1 "bound but unreachable" failure mode):

| Control | Handler | Behaviour | Verified ruling |
|---------|---------|-----------|-----------------|
| "Take a tour" card (`:81`) | `handleTakeTour` (`:56-60`) | navigates to `/brand/dashboard`, then `openTour()` after 150 ms | ❌ **DEAD (H-1)** — `openTour()` sets `tourOpen` (`store.ts:127`), which **nothing reads**. No tour component, no tour library. It flips a boolean into the void |
| "Ask Meera" card (`:94`) + link (`:127`) | `handleAskMeera` (`:62-64`) | navigates to `/brand/meera` with a pre-seeded prompt | ✅ **genuinely wired** — chain confirmed `meera-help.ts:25` → `MeeraWorkspace.tsx:51` → `MeeraChatPanel.tsx:804` → `Composer.tsx:52` |

~~No dead controls, no fabricated content, no mock data.~~ **All three clauses are false:**

- **Dead controls:** one of the two (H-1 above).
- **Fabricated content:** the file's own JSDoc (`:19-23`) states the copy is **placeholder**, and `:38` tells brands escrow funding *"triggers automatically"* — contradicted by this document's own **P-2** (funding is Meera-only) and **P-1′** (Meera-funded holds cannot be released). **H-3**.
- **Accessibility:** both quick actions are `<Card onClick>` (`:81`, `:94`) — non-interactive `div`s with no `role`, `tabIndex`, or key handler. The tour has no keyboard-reachable path at all. **H-2**.

### How this section got it wrong

§76 claimed it checked for the PL-1 "bound but unreachable" failure mode. It checked that `handleTakeTour` **had a body** and stopped — it never asked whether `openTour()` did anything. **The tell was inside this document the whole time:** §77 lists *"Whether the product tour (`openTour`) works"* under **NOT CHECKED**, two sections after §76 called that control "genuinely wired." Asserting a control works and admitting it was never verified, in the same document, is the failure — and it is the second time (after Pipeline) that a "clean" verdict came from stopping early.

> 🔎 **Product observation (not a defect):** there is **no human-support escalation path** — no ticket form, no support email, no live-chat handoff. Help routes entirely to the product tour and the AI assistant. That is a coherent strategy; it is worth confirming it is the intended one, since a brand blocked by a bug the AI cannot resolve has nowhere in-product to go.

---

## 77. NOT CHECKED — Part 8 (law 5)

| Not verified | Why |
|-------------|-----|
| Runtime behaviour against a live server | Static trace + unit tests only |
| **That the bell actually fails on a real expired token** | N-1 is derived from code paths — the absence of a 401 branch is certain; the lived symptom was not reproduced |
| Whether `/auth/refresh` itself works | Out of scope; assumed from the H-19 implementation |
| Whether workspace-member role gates are enforced server-side | Annotations not executed |
| Whether preference writes persist per event type | `CATEGORY_EVENT_TYPES` fan-out (`:270`) traced to the call, not to storage |
| Whether the product tour (`openTour`) works | UI state, no API |
| Billing sub-route correctness | Covered in Part 5 scope |

---

## 78. FINAL Master Defect Register — brand surface complete

**Every brand surface is now traced:** 12 sidebar items · 3 avatar-menu items · the Command Bar · the notification bell · the money path.

| ID | Surface | Severity | Evidence | Summary |
|----|---------|----------|----------|---------|
| **P-1′** | Payments | 🔴 HIGH | believed | Meera-funded escrow holds releasable by neither path |
| **P-3** | Contracts | 🔴 HIGH | believed | `\|\| 50000` — both parties told "₹50,000 secured in escrow" on a zero-value deal |
| **P-4** | Payments | 🔴 HIGH | believed | `withdraw-${Date.now()}` idempotency key defeats server dedupe |
| **D-11** | Discover → Chat | 🔴 HIGH | **proved** | Invite/offer redirect drops the deal ID (2 failing tests) |
| **D-1** | Dashboard | 🔴 HIGH | believed | Null runway renders as red "CRITICAL / 0d" on funded wallets |
| **D-2** | Campaigns | 🔴 HIGH | believed | List truncates at 100; meta discarded, no pager |
| **D-5** | Campaigns | 🔴 HIGH | believed | `collaboratorsCount` always 0, rendered as fact |
| **C-2** | Contracts | 🟡 MEDIUM | believed | Demo `deal-1` id breaks "Open in Deal Room" on every real contract |
| **M-B** | Notifications | 🟡 MEDIUM | **static-certain** | **The bell never refetches** — data frozen at page-load for the whole session; no interval, SSE, focus, or on-open refetch. **Subsumes N-1** |
| **H-1** | Help | 🟡 MEDIUM | **static-certain** | **"Take the tour again" is dead** — `openTour()` sets `tourOpen`, which nothing reads. No tour component exists |
| **M-A** | Notifications | 🟡 MEDIUM | **static-certain** | `NotificationBell.tsx` (204 lines) has **zero importers** — real bell is inlined in `brand-layout.tsx`; dead copy calls `useNotifications()` with no role |
| **N-1** | Notifications | 🟡 MEDIUM | believed | Bell bypasses shared HTTP client — no 401 refresh. Trigger: page load with already-expired token; only a hard reload recovers |
| **PL-2** | Pipeline | 🟡 MEDIUM | believed | 5th copy of the stage switch (Java) disagrees with the board |
| **PL-1** | Pipeline | 🟡 MEDIUM | believed | SLA "at-risk" feature dead in live mode; filter button never renders |
| **PL-3** | Dashboard | 🟡 MEDIUM | believed | Pipeline card has no `STAGE_COLOR` for `Completed` |
| **DP-1** | Disputes | 🟡 MEDIUM* | believed | Brand cannot open a dispute — *documented as intentional; product question* |
| **T-1** | Timeline | 🟡 MEDIUM | believed | No realtime — never opens the SSE stream |
| **D-13** | Messages | 🟡 MEDIUM | believed | No realtime — `/brand/messages` never opens the SSE stream |
| **D-12** | Messages | 🟡 MEDIUM | believed | 10 enabled controls with no handler |
| **P-2** | Payments | 🟡 MEDIUM | believed | Escrow funding reachable only via the Meera AI chat |
| **D-8** | Deal Room | 🟡 MEDIUM | believed | Sidebar "Deals" → room missing the shipment control |
| **D-9** | Deal Room | 🟡 MEDIUM | believed | Deliverable **Reject** route unreachable |
| **D-3** | Campaigns | 🟡 MEDIUM | believed | Progress bar always 0%; "Sort by Progress" dead |
| **D-6** | Campaigns | 🟡 MEDIUM | believed | `sortBy`/`sortOrder` accepted by backend, never sent |
| **N-2** | Notifications | 🔵 LOW | believed | `notifications.list`/`markRead` have zero callers; no client for `/read-all` |
| **M-C** | Notifications | 🔵 LOW | believed | "View all notifications" only closes the popover — no `/brand/notifications` route exists |
| **M-D** | Notifications | 🔵 LOW | believed | Hook emits literal `Authorization: Bearer null` when no token stored; `api.ts:250` correctly omits the header |
| **M-E** | Notifications | 🔵 LOW | believed | Hook sends no `credentials: 'include'` |
| **H-2** | Help | 🔵 LOW | believed | Quick-action cards are `<Card onClick>` — non-interactive divs, no keyboard path |
| **H-3** | Help | 🔵 LOW | believed | Placeholder copy (own JSDoc `:19-23`); `:38` claims escrow funding "triggers automatically" — contradicted by P-2 and P-1′ |
| **C-4** | Settings | 🔵 LOW | believed | Stale JSDoc claims `POST /me/password` may not exist — it does |
| **DP-2** | Disputes | 🔵 LOW | believed | Superseded `GET /brand/disputes` has no caller |
| **C-3** | Contracts | 🔵 LOW | believed | "Download PDF" disabled behind a factually wrong comment |
| **C-1** | Contracts | 🔵 LOW | believed | `?dealId=` filter dropped for brands — latent |
| **PL-4** | Pipeline | 🔵 LOW | believed | `platforms: []` / `creatorFollowers: ''` hardcoded empty |
| **PL-5** | Pipeline | 🔵 LOW | believed | `isAtRisk` short-circuits on `0` at the SLA boundary |
| **D-14** | Discover | 🔵 LOW | believed | 4 backend creator endpoints with no caller |
| **D-4** | Campaigns | 🔵 LOW | believed | Stale backend line-number citations |
| **D-7** | Campaigns | 🔵 LOW | believed | `/brand/campaigns/:id/tracking` has no inbound link |
| **P-1** | Payments | 🔵 LOW | believed | `/wallet/escrow/release` + `/payout` have no client caller *(was CRITICAL — false alarm)* |
| ~~M-1~~, ~~D-10~~ | — | 🚫 STRUCK | — | Both false alarms |

**39 live defects · 7 HIGH · 17 MEDIUM · 15 LOW · 0 CRITICAL · 1 oracle-proved · 2 struck.**
*(Corrected from 31 by red-team verification §80 — Part 8 missed 8. Plus P-5/P-6/P-7 MEDIUM and P-8 LOW in §53-58.)*

> **Three defects here are `static-certain`, not `believed`** — H-1, M-A and M-B rest on a symbol having **zero readers** or a function having **zero callers**. A symbol nothing reads cannot behave; no runtime probe can overturn that. This is the strongest evidence class in the document after D-11's failing test.

---

## 79. Closing — the complete brand surface

**Across every brand surface, not one backend endpoint is broken, missing, or mismatched.** Every route exists. Every DTO aligns. Every backend line citation that survived verification was exact. **All 31 defects are client-side.**

### One pattern accounts for a third of them

**The same capability implemented twice, then diverging.** It is the root of N-1/N-2 (bell vs `api.ts` client), PL-2/PL-3 (five copies of one status switch, one in Java), D-8 (two Deal Rooms), and C-2 (a demo map standing in for a real id the record already carries). In every case both implementations typecheck, and the older one keeps working just well enough that nobody notices the newer one drifted.

### Three lessons this audit learned the hard way

1. **Comments in this repo are not evidence.** Three times a comment contradicted its own source — C-3 ("no PDF endpoint" — there is one, with three callers), C-4 ("may not have shipped" — it shipped), and Part 6's exhaustiveness claim, which this audit repeated as fact and verification falsified. Trusting C-4's comment would have produced a false HIGH.
2. **"No caller" needs more than one grep.** Four distinct patterns defeat a naive census: aliased imports, a second API layer, runtime-selected clients, and — found here — a **raw-fetch hook that bypasses the client entirely**. One produced a false CRITICAL.
3. **A clean-looking surface deserves the same adversarial pass as a suspect one.** Pipeline was declared clean and had five defects.

### What would actually close this ground

1. **A live-mode smoke pass** — mock mode masks D-5, PL-1, C-2, P-3, D-3 completely.
2. **A cross-language contract test** — for the status→bucket rule behind PL-2/PL-3.
3. **A reachability check in CI** — assert every exported client method has a caller and every `@Mapping` has a client. It would have caught N-2, DP-2, D-9, D-14, C-3, P-1 at commit time.
4. **A lint rule banning raw `fetch` outside `src/lib/api.ts`** — N-1 is exactly what that rule exists to prevent.

> ⚠️ **What this document does not establish.** Every finding is `believed` except D-11. **No live server was probed and no payment was executed.** This audit proves what the code *can* do, not what it *does* at runtime. Reachability is not correctness — and on the money path especially, the two must not be confused.

---

*Part 8 produced by static code trace, bidirectional caller census, `vitest` and `tsc` on branch `fix/brand-audit-remediation`. No live server was probed.*

---
---

# PART 8 — ADVERSARIAL VERIFICATION (Red-Team, Kabir)

> **Scope:** §§73–79 only. Every claim re-derived from primary source; nothing accepted from the audit text.
> **Method:** git-tracked-only census (`git ls-files`), all four FE access paths, global-interceptor sweep, backend line-by-line.
> **Result: NEEDS_FIX.** All three named defects are real. **One clean verdict is false, and five findings were missed.**

---

## 80. Verification Findings

### 80.1 — N-1 · **REAL BUG** (mechanism restated, severity held)

Every structural claim checks out at the cited lines:

| Claim | Ruling | Evidence |
|---|---|---|
| Own `API_BASE_URL`, not `api.ts` | ✅ | `useNotifications.ts:22-23`; explanatory comment `:19` |
| Raw `fetch` at `:190`, `:216`, `:248` | ✅ exact | all three verified |
| No 401 branch; generic `Error` at `:191`, `:221`, `:252` | ✅ exact | all three verified |
| Nothing else in the chain refreshes | ✅ | **no** `window.fetch`/`globalThis.fetch` override, **no** service worker, **no** interceptor anywhere in tracked `src/**` |
| `api.ts` H-19 interceptor exists | ✅ | dedupe `:219-220`; reactive 401 JSDoc `:256`, **code `:341`**; `clearToken` JSDoc `:317`, **code `:347`**; proactive refresh `:304-313` invoked `:332` |
| Blast radius = every brand page | ✅ | `brand-layout.tsx:144` `useNotifications('brand')` |

**Two corrections to the write-up:**

1. **`:237` was a mis-citation** for "proactive refresh" — that line is inside `bootstrap()`'s JSDoc. Corrected in §74 above. Note that **three of the five cited `api.ts` lines were comment lines, not code** — in a document whose own thesis is "comments in this repo are not evidence."
2. **The stated trigger is wrong; the conclusion is right and understated.** §74 says *"when the access token expires, the bell 401s."* It cannot: the hook fires **exactly one** request, at mount. Nothing fires at expiry. The real trigger is a **page load while the stored token is already expired** (reload / returning to a backgrounded tab). Recovery is worse than "unless something else remounts it" — **nothing remounts it**: `BrandLayoutWrapper` is the same component type at the same position for every brand route, and `RoutedErrorBoundary` only resets when `hasError` is true (`ErrorBoundary.tsx:122`). Brand→brand navigation preserves the instance. **Only a hard reload clears the error state.**

**Severity — is MEDIUM justified? Held, but for a different reason.** On its own merits N-1 is at the *bottom* of MEDIUM: notifications-only, no money, no data loss, no auth bypass, optimistic writes revert with a toast, and the exposure window is narrow (reload-with-expired-token, not any expiry). It is not a security defect — the raw path is *more* conservative than `api.ts`, not less. What holds it at MEDIUM is **M-B below**, which N-1 is really a sub-case of. Rewrite the rationale; keep the band.

### 80.2 — N-2 · **REAL BUG**

Census run against `git ls-files` across `api.ts`, `meera-api.ts`, `src/hooks/**` and raw `fetch`, with loose patterns:

- `api.notifications.list` — **0 callers.** `api.notifications.markRead` — **0 callers.** The only live uses of the `notifications` client are `getPreferences`/`setPreference` (`brand-settings.tsx:197,231,270`; `creator-settings.tsx:97,127`). The `.markRead(` hits that surface belong to `messages.markRead` (`deal-room-dashboard.tsx:285`, `brand-chat.tsx:1021`, `brand-messages.tsx:418`, `collaboration-timeline.tsx:198`, `creator-chat.tsx:857`) — a different client. ✅
- `POST /notifications/read-all` — real at **`NotificationController.java:127`** ✅ exact; **no `markAllRead` exists in `api.ts`** ✅. The wrapper was deleted 2026-08-03, documented at `src/lib/__tests__/api-contract.test.ts:75-77`.

### 80.3 — C-4 · **REAL BUG** (one stale citation inside it)

- JSDoc verbatim at `api.ts:817`: *"Landing in parallel on the backend (Vikram); a 404 here means it hasn't shipped yet."* ✅
- `AccountController.java:105` = `@PostMapping("/password")` ✅ exact · `:47` = `@RequestMapping("/me")` ✅ exact
- `AuthRateLimitFilter.java:366` = `if (path.equals("/me/password"))` ✅ exact
- ❌ **`AuthService.java:445` is wrong — `changePassword` is at `:466`.** Corrected above. The audit filed **D-4 ("stale backend line-number citations")** and then committed one.

**All 7 §75 endpoints verified exact:** `WorkspaceController:65` (PATCH `/workspaces/me`), `WorkspaceMemberController:74` (GET members), `:51` (invite), `AccountController:105`, `NotificationController:224` / `:241` (preferences r/w), `AuthController:136` (logout). **7/7 correct.**

### 80.4 — §76 "HELP & SUPPORT — zero defects" · **VERDICT NOT DESERVED**

The pattern repeated: a surface declared clean was not adversarially probed.

| ID | Severity | Finding |
|----|----------|---------|
| **H-1** | 🟡 **MEDIUM** | **"Take the tour again" is a dead control.** `brand-help.tsx:59` calls `openTour()`, which sets `tourOpen: true` (`store.ts:127`). **`tourOpen` is read by nothing** — its only occurrences in the entire tracked repo are its own declaration and mutators (`store.ts:109,126,127,128`). No tour overlay component exists; no tour library is in `package.json`. Clicking navigates to `/brand/dashboard` and then flips a boolean into the void. **Identical to PL-1 — the very failure mode §76 claims it checked for.** §77 then lists *"Whether the product tour (openTour) works"* as NOT CHECKED: **the document asserts a control is "genuinely wired" while simultaneously admitting it never verified it.** |
| **H-2** | 🔵 LOW | Both quick-action cards are `<Card onClick>` (`:81`, `:94`) — non-interactive `div`s with no `role`, `tabIndex`, or key handler. Keyboard and screen-reader inaccessible (WCAG 2.1.1 / 4.1.2). "Ask Meera" has a reachable fallback (`Button` `:127`); **the tour has none**. |
| **H-3** | 🔵 LOW | §76 asserts "no fabricated content." The file's own JSDoc (`:19-23`) says *"Copy below is placeholder — TODO: final copy from Nisha,"* and the shipped copy makes product claims this same document contradicts — *"triggers escrow funding automatically"* (`:38`) versus **P-2** (escrow funding reachable only via Meera chat) and **P-1′**. The help page teaches brands a flow the audit found does not exist. |

Verified as genuinely wired: **"Ask Meera" only.** `MEERA_HELP_PRESEED_PARAM` (`meera-help.ts:25`) → `MeeraWorkspace.tsx:51` → `MeeraChatPanel.tsx:804` → `Composer.tsx:52` (`useState(() => initialDraft ?? '')`). Full chain confirmed. `/brand/help` route exists (`App.tsx:340-343`), reachable from `brand-layout.tsx:298,469`. File is 135 lines, zero API calls. ✅

### 80.5 — MISSED by Part 8

| ID | Severity | Finding |
|----|----------|---------|
| **M-A** | 🟡 MEDIUM | **`src/components/feature/meera/NotificationBell.tsx` (204 lines) has ZERO importers** anywhere in the tracked repo — the real bell is inlined in `brand-layout.tsx:359-444`. It is fully dead: calls `useNotifications()` with **no role** (silently defaults to `'brand'`, so a creator mount would authenticate with `brand_token`), and its "View all notifications" button (`:190-196`) has **no `onClick` at all**. `wiki/reports/final-signoff-2026-07-23.md:88` already recorded *"bell is unmounted today."* This is the cleanest instance of §79's own "implemented twice, then diverged" thesis — and §74 cited the file's blast radius as if it were live. |
| **M-B** | 🟡 MEDIUM | **The bell never refetches — notification data is frozen at page-load for the entire session.** `refresh()` is called only from the mount effect (`useNotifications.ts:269-271`; dep `[refresh]`, itself `[role]`, and `role` never changes). **Neither consumer even destructures `refresh`** (`brand-layout.tsx:144`, `NotificationBell.tsx:115`). No `setInterval`, no `EventSource`, no `visibilitychange`/`focus` handler, and **no refetch when the popover opens**. A brand can sit on the app all day and never receive a new notification. This is a larger functional gap than N-1 and **strictly subsumes it** — fixing the 401 path alone leaves the bell just as stale. Same class as T-1/D-13 ("no realtime"), which the audit filed for Timeline and Messages but not here. |
| **M-C** | 🔵 LOW | **"View all notifications" is a misleading control.** `brand-layout.tsx:436-443` labels a button as navigation; its `onClick` is `setNotificationsOpen(false)` — it just closes the popover. There is **no `/brand/notifications` route** in `App.tsx`. Same class as D-12. |
| **M-D** | 🔵 LOW | `authHeader()` (`useNotifications.ts:171`) interpolates a missing token into a **literal `Authorization: Bearer null`** header. `api.ts:250-251` correctly omits the header when there is no token. Turns "unauthenticated" into "malformed credential" and pollutes server-side auth telemetry. |
| **M-E** | 🔵 LOW | The hook sends no `credentials: 'include'`, unlike `http.request`. Even a future cookie-based refresh could never reach it — the divergence is structural, not just missing code. |

### 80.6 — Corrected register delta

**§78's count of 31 is low.** Add **H-1, H-2, H-3, M-A, M-B, M-C, M-D, M-E** → **39 live defects · 7 HIGH · 3 + 4 = 7 MEDIUM added (H-1, M-A, M-B) and 5 LOW added.** Revised bands: **7 HIGH · 17 MEDIUM · 15 LOW · 0 CRITICAL.**

**§73's summary table is wrong on two rows:** Help & Support is **1 MEDIUM + 2 LOW**, not `0`; Notifications is **2 + 5 = 7 defects**, not `2`.

### 80.7 — The lesson this verification adds

§79 lesson 3 reads *"A clean-looking surface deserves the same adversarial pass as a suspect one. Pipeline was declared clean and had five defects."* **The document then declared Help & Support clean in the very next section, and it has three.** Writing the lesson is not the same as applying it. The reliable tell is §77: **every item this audit listed as NOT CHECKED that it also described as working turned out to be broken.** A "not checked" row and a "genuinely wired" claim about the same control cannot both stand — and here the "not checked" row was the true one.

---

*Part 8 verification produced by independent primary-source re-derivation on branch `fix/brand-audit-remediation`: `git ls-files`-scoped census across all four FE access paths, global fetch/service-worker/interceptor sweep, React Router remount analysis, and line-exact backend confirmation. No live server was probed — H-1, M-A and M-B are static-certain (a symbol with zero readers cannot behave), the rest are `believed`.*

**— Kabir, Red-Team / Offensive Security**

---
---

# PART 9 — Two-Way Profile Visibility Audit

> **Scope:** a **field-level** trace of what each party sees of the other — brand→creator and creator→brand. This is a data-exposure audit, not an endpoint audit: the question is *which fields cross the boundary*, and what the receiving UI does with them.
> **Branch:** `fix/brand-audit-remediation` · **Date:** 2026-08-09

---

## 81. Summary — the flow is deeply asymmetric, by design and by accident

> ⚠️ **Corrected after verification (§86-88).** The wire-count row overstated what is actually populated, and **PR-2 was backwards.**

| Direction | Surface | Fields declared | Actually populated | Rendered as real data |
|-----------|---------|:---------------:|:------------------:|:---------------------:|
| **Brand → sees Creator** | `/brand/creators/:id`, a dedicated profile page | 20 (`CreatorResponse`) | **18** — `portfolioItems` always `[]` (**M-2**), `averageRate` dropped in the mapper (**M-3**) | 18 + **18 fabricated zeros** |
| **Creator → sees Brand** | ❌ **no profile page exists** | 3 (`BrandSummary`) | 3 | 3 + **1 fabricated `true`** (**M-1**) |

**The findings, one on each side — and they are the same defect mirrored:**

- **PR-1 (🔴 HIGH)** — every creator profile shows **Rating 0 · 0 stars · 0% completion · 0% on-time · 0 campaigns · 0 reviews**. The DTO carries none of those fields; the mapper zeroes them; the render treats them as fact. The data exists on a *different* endpoint that nothing calls (**D-14**).
- **M-1 (🔴 HIGH)** — every brand shows creators a **"Verified Brand" badge**, hardcoded `true`, regardless of the workspace's actual `verificationStatus` (which defaults to `UNVERIFIED`).

**Each side is shown a fabricated value about the other, in opposite directions:** the brand sees a creator's track record fabricated *down* to zero; the creator sees a brand's verification fabricated *up* to true. Both appear at the moment of commitment — the hiring screen and the deal room.

---

## 82. DIRECTION A — What the brand sees of a creator

### 82a. The wire — `GET /creators/{creatorId}` → `CreatorResponse` (`CreatorDtos.java:21-47`)

| # | Field | Notes |
|---|-------|-------|
| 1-5 | `id`, `userId`, `username`, `displayName`, `bio` | `userId` is an internal identifier exposed to the brand |
| 6-8 | `avatarUrl`, `coverImageUrl`, `location` | |
| 9-11 | `categories`, `languages`, `contentStyles` | |
| 12 | `platforms` (`PlatformStatResponse[]`) | per-platform handle, followers, engagement, verified |
| 13-14 | `totalFollowers`, `engagementRate` | |
| 15-16 | **`averageRate`**, `currency` | the creator's pricing, disclosed to brands |
| 17 | `isVerified` | |
| 18 | `portfolioItems` | ⚠️ **ALWAYS EMPTY on the wire** — `CreatorMapper.java:47` passes `Collections.emptyList()` unconditionally. The shape is declared (id, title, description, thumbnailUrl, mediaUrl, platform) and never populated. **M-2** |
| 19 | `saved` | brand's own bookmark state |
| 20 | **`scores`** | `quality` / `authenticity` / `brandSafety` — nullable, **never coerced to zero** (the DTO comment names coercion as "the wrong way to do this") |

**No PII crosses this boundary** — no email, phone, address, or bank detail. The exposure is appropriate for a marketplace.

### 82b. 🔴 PR-1 — Every creator profile renders six fabricated zeros (HIGH)

| Field | Detail |
|-------|--------|
| **Severity** | 🔴 HIGH — misrepresents every creator on the screen the brand hires from |
| **Where** | `brand-creator-profile.tsx:300-305, 336-340` (mapper) → `:595, 596, 662-664, 881, 888, 896` (render) |

`buildLiveCreatorView` maps `CreatorResponse` → `CreatorDisplayModel`. Because the DTO has no track-record fields, the mapper **zeroes 18 of them**, each with an honest `TODO(vikram)` naming the missing field:

```ts
completedCampaigns: 0,   // TODO(vikram): DTO has no completed-campaign count
rating: 0,               // TODO(vikram): DTO has no rating
reviewCount: 0,          // TODO(vikram): DTO has no reviewCount
completionRate: 0,       // TODO(vikram): DTO has no work-quality metrics
onTimeDelivery: 0,
repeatClients: 0,
```

**The mapper is honest. The render is not.** Six of those zeros reach the screen unguarded, as facts:

| Render site | What the brand sees |
|-------------|---------------------|
| `:595` | **Campaigns: 0** |
| `:596` | **Rating: 0** |
| `:662` | **Completion Rate: 0%** |
| `:663` | **On-Time Delivery: 0%** |
| `:664` | **Repeat Clients: 0%** |
| `:881` | **`0`** in 4xl type as the headline rating |
| `:888` | `i < Math.floor(0)` → **zero of five stars filled** |
| `:896` | **0 reviews** |

So **every creator, without exception**, presents to a brand as a zero-star operator with a 0% completion rate and no delivery history. This is not a new-account edge case like D-1 — the DTO never carries these fields, so it is the permanent state of the page.

### ⚠️ It is worse than "missing data shown as zero" — the page attests to provenance

Verification found copy this audit missed, printed **directly beside** the fabricated `0` (`:900-903`):

> **"Based on verified brand collaborations"**
> **"All reviews are from completed campaigns"**

The page does not merely display a zero — it **certifies where the zero came from**. That converts absent data into an **affirmative false claim about a third party's professional track record**: the brand is told, in writing, that this creator has been verified across collaborations and has a rating of 0 from completed campaigns. This is what fixes the severity at HIGH rather than MEDIUM.

### This exact bug was already fixed once, on the field next to it

The mapper's `authenticity` line documents the precedent (`:321-323`):

> BR-18 fix: was hardcoded to `0`, which rendered as **"0% — Excellent authenticity"** — live misleading UI. `row.scores.authenticity` is the real value; `null` (not yet scored) stays **null** all the way to the ring below.

The lesson was applied to one field and not to the six beside it.

### The data exists — on an endpoint nothing calls

`CreatorPublicProfileResponse` (`DiscoveryDtos.java:62-82`) carries exactly the missing signals:

```java
long completedCampaigns,
BigDecimal avgRating,
```

It is served by `GET /creators/profile/{usernameOrId}` (`CreatorController:159`) — **the endpoint filed in Part 4 as D-14, "no FE caller."**

**D-14 and PR-1 are the same defect seen from two ends:** the client calls the thinner endpoint and zero-fills the fields the richer one would have supplied.

**Fix (either):**
1. Switch the profile page to `GET /creators/profile/{usernameOrId}` and render `avgRating` / `completedCampaigns` — closes D-14 at the same time; **or**
2. Keep `null` through to render and show `—` / hide the tiles, exactly as BR-18 did for `authenticity`.

Option 2 is the smaller change and is strictly honest. Option 1 is the one that actually gives the brand the information.

---

## 83. DIRECTION B — What the creator sees of a brand

### 83a. There is no brand profile page

**All 23 creator routes** (`App.tsx:377-535`) were enumerated: login, register, forgot-password, meta callback, onboarding, dashboard, deals, inbox, active, copilot, wallet, profile, settings, chat, portfolio, analytics, campaigns, campaigns/:id, applications, disputes, reviews, coupons, affiliate.

**None is a brand profile.** There is no `/creator/brands/:id`, and no client method fetches one. The only `BrandProfileResponse` in the codebase is `MeeraDtos:83`, returned by `MeeraController:200` — the brand's **own** profile for the AI assistant, not a creator-facing view.

### 83b. Brand data reaches creators only as an embedded summary

| Surface | Shape | Fields |
|---------|-------|--------|
| Campaign browse / detail | `CreatorCampaignDtos.BrandSummary` (`:21`) | **`workspaceId`, `name`, `logoUrl`** — 3 |
| Deal room / deal list | `DealDtos.DealResponse` `counterparty*` (`:27-30`) | `counterpartyId`, `counterpartyName`, `counterpartyAvatar`, `counterpartyHandle` |

`DealResponse` is a **symmetric abstraction** — one DTO serves both roles and the server fills in whichever party is "other". That is good design and means the deal room exposes the same shape in both directions.

### The narrowness is deliberate — and correctly so

`CreatorCampaignDtos.java:10-15`:

> Deliberately narrower than the brand-facing `CampaignDtos` (e.g. no `workspaceId`, no internal metrics) — see `05_CREATOR_CAMPAIGNS_SPEC.md` §7.1: **brand contact info and internal fields must never leak into a creator-visible campaign listing.**

`CreatorCampaignMapper.java:60` honours it exactly:

```java
return new BrandSummary(workspace.getId(), workspace.getName(), workspace.getLogoUrl());
```

> 🚫 **"Spec'd and implemented faithfully" was NOT VERIFIED — and the spec does not exist.** `wiki/tech/creator/05_CREATOR_CAMPAIGNS_SPEC.md` is **not in the repo** (`git ls-files` returns nothing; `wiki/tech/creator/` contains only three `AGENT_LOOP_WAKE_CREATOR.*` files). This section certified a design against a reference it never opened — in a document whose own stated thesis is **"comments in this repo are not evidence."** That makes it the *third* time this audit trusted a comment it had explicitly warned itself not to trust.

**Partially correct, and over-stated.** The *exclusion* rule holds on inspection: no contact details or internal metrics do leak into the creator-visible listing, and that part is sound privacy design.

But the verdict "not a defect" is not supported:

- The cited spec **does not exist**, so nothing here is "spec'd."
- **This section contradicts §83c two paragraphs later** — §83b calls the 3-field shape correct and complete; §83c argues a 4th field (`verificationStatus`) belongs in it. Both cannot be true.

The honest statement: **the exclusion set is right; the inclusion set is under-specified and undocumented.** What must never be shown is clear. What *should* be shown was never decided.

### 83c. 🔴 PR-2 — ~~A creator cannot tell whether a brand is verified~~ → **INVERTED: creators are shown a FABRICATED "Verified Brand" badge** (HIGH)

> 🚫 **THIS FINDING WAS BACKWARDS.** Verification (§86-88) proved creators are **not** denied the signal — **they are given a false positive one.**
>
> - `src/lib/creator-deal-mappers.ts:184` hardcodes **`brandVerified: true`** — rendered at `creator-deals.tsx:577-579` (live path via `:248`/`:281`).
> - `creator-chat.tsx:1774-1777` renders a **"Verified Brand" badge as unconditional JSX** (live via `:661`).
> - `Workspace.java:126` defaults every new workspace to **`UNVERIFIED`**.
>
> So an unverified brand displays as **verified** — in the deal room, at the moment a creator agrees to ship physical product to them. And `creator-deal-mappers.ts:198-202` claims the mapper shows *"never a fabricated rating/badge"* **fourteen lines below the fabricated badge.**
>
> **Re-rated 🟡 MEDIUM → 🔴 HIGH.** Withholding a trust signal is a gap; **manufacturing a false one is a safety defect.** Filed as **M-1**; the section below describes the withholding half, which is also real but is the lesser problem.

| Field | Detail |
|-------|--------|
| **Severity** | 🔴 HIGH *(was MEDIUM — see inversion above)* |
| **Where** | `CreatorCampaignMapper.java:60` (withheld) · `creator-deal-mappers.ts:184` (**fabricated**) |

`Workspace` carries a **`verificationStatus`**, and it is surfaced — to the brand's own team (`WorkspaceMemberDtos.java:57`). It is **not** among the three fields passed into `BrandSummary`.

So a creator deciding whether to apply to a campaign, accept an offer, sign a contract, or **ship physical product to a stranger's address** can see the brand's name and logo, and nothing else.

This is not the same as the §83b privacy rule. `verificationStatus` is not contact information and not an internal metric — it is precisely the kind of trust signal a marketplace exists to publish, and the platform already computes it.

### The asymmetry, stated plainly

| Trust signal available *before* committing | Brand about creator | Creator about brand |
|-------------------------------------------|:-------------------:|:-------------------:|
| Verified badge | ✅ `isVerified` | ❌ |
| Quality / authenticity / brand-safety scores | ✅ `scores` | ❌ |
| Audience size & engagement | ✅ | ❌ n/a |
| Portfolio / past work | ✅ `portfolioItems` | ❌ |
| Pricing | ✅ `averageRate` | ❌ (budget shown per campaign) |
| Rating / track record | ⚠️ exists but zeroed — **PR-1** | ❌ |

Reviews are **symmetric on the write side** — `brandReviews` and `creatorReviews` mirror each other exactly (`create`, `listReceived`, `flag`), so brands do accumulate reviews. But `listReceived` is **self-scoped on both sides**: neither party can read the other's reviews before a deal. Brands compensate via `scores`; creators have no equivalent.

---

## 84. NOT CHECKED — Part 9 (law 5)

| Not verified | Why |
|-------------|-----|
| Runtime behaviour against a live server | Static trace only; no live probe. **PR-1's render sites are unguarded in source — but the lived screen was not observed** |
| Whether `CreatorPublicProfileResponse` is fully populated at runtime | Its `avgRating` / `completedCampaigns` may themselves be null/zero server-side — **verify before adopting fix option 1** |
| Whether `scores` are populated for real creators | The DTO comment says `brandSafety` is null for every creator until BR-42 ships |
| Whether creator-side surfaces render `BrandSummary` honestly | Direction B was traced to the DTO, not through the creator UI — the creator surface is outside this document's brand scope |
| Whether `userId` exposure on `CreatorResponse` is exploitable | Noted as observation; no authz testing performed |
| Whether any admin surface exposes more of either party | Admin scope not traced here |

---

## 85. Register additions

| ID | Surface | Severity | Evidence | Summary |
|----|---------|----------|----------|---------|
| **M-1** | Brand badge (creator view) | 🔴 HIGH | believed | **Fabricated "Verified Brand" badge** — `creator-deal-mappers.ts:184` hardcodes `brandVerified: true`; `creator-chat.tsx:1774-1777` renders it unconditionally. Workspaces default to `UNVERIFIED` |
| **PR-1** | Creator profile (brand view) | 🔴 HIGH | believed | Every creator renders **Rating 0 · 0 stars · 0% completion · 0 campaigns · 0 reviews**, under the caption *"Based on verified brand collaborations"*. Same root as **D-14** |
| **PR-2** | Brand summary (creator view) | 🟡 MEDIUM | believed | `verificationStatus` withheld from `BrandSummary` — the withholding half of M-1 |
| **M-2** | Creator profile (brand view) | 🟡 MEDIUM | believed | `portfolioItems` is **always `[]`** — `CreatorMapper.java:47` passes `Collections.emptyList()` unconditionally; Portfolio tab has no empty state |
| **M-3** | Creator profile (brand view) | 🔵 LOW | believed | Real `averageRate` dropped at `:329`; Rates tab renders two empty boxes plus a disclaimer about rates it isn't showing |

**Running total: 44 live defects · 10 HIGH · 0 CRITICAL · 1 oracle-proved · 2 struck.**

### Two open items from §84, now closed by verification

- ✅ **`userId` exposure is benign.** `/creators/{id}` calls `brandContext.requireBrandWorkspace` first (`CreatorDiscoveryService.java:222-223`); it is an opaque join key, never dereferenced cross-tenant. **§82a's "no PII crosses this boundary" is verified correct.**
- ✅ **Fix option 1 for PR-1 is safe.** `completedCampaigns` is a real COMPLETED-collaboration count (`:247-249`) and `avgRating` is a real brand-review mean that returns **`null`, not `0`**, when absent (`:846-853`). Adopt option 1 **and** keep option 2's null-handling — the endpoint hands back `null` and the render must not re-zero it.

> **D-14 is now upgraded in importance.** Filed in Part 4 as a LOW ("4 backend creator endpoints with no caller"), it is the direct cause of a HIGH: `GET /creators/profile/{usernameOrId}` carries the very fields PR-1 fabricates. An unused endpoint is not always dead weight — sometimes it is the fix already sitting on the shelf.

---

*Part 9 produced by field-level DTO and render-site trace on branch `fix/brand-audit-remediation`. No live server was probed.*

---
---

# PART 9 — CTO VERIFICATION (Priya)

> **Verifier:** Priya, CTO · **Date:** 2026-08-09 · **Branch:** `fix/brand-audit-remediation`
> **Scope:** Part 9 only (§81–85). Parts 1–8 verified separately, untouched here.
> **Method:** every claim re-traced to primary source. Every cited line number opened individually. Searches scoped to `git ls-files` (sibling worktrees under `.claude/worktrees/` excluded). Comments treated as claims to be tested, never as evidence.

## 86. Verdict — **NEEDS_FIX**

Part 9's two filed defects are **both real**. The document is not wrong about what it found — it is wrong about what it *concluded*, and it missed the mirror-image defect sitting on the other side of the very asymmetry it set out to map.

| # | Item | Ruling |
|---|------|--------|
| **PR-1** | Creator profile renders fabricated zeros | ✅ **REAL BUG** — every citation verified, HIGH justified |
| **PR-2** | Creator cannot see brand verification | ✅ **REAL BUG** — but **understated and mis-framed**; severity → 🔴 HIGH |
| **§83a** | No creator-facing brand profile exists | ✅ **CONFIRMED** — verified past routes, into components |
| **§83b** | 3-field `BrandSummary` is "by design, not a defect" | ❌ **VERDICT NOT DESERVED** — rests on a comment citing a **document that does not exist** |
| **§81/§82a** | "20 fields rendered as real data" | ❌ **FALSE** — at least 2 of the 20 are dead on the wire or dropped |
| — | Fabricated brand-verified badge shown to creators | 🔴 **MISSED** (new, HIGH) |

---

### 86.1 — PR-1 · **REAL BUG** (all 8 render citations verified individually)

Nothing in this finding failed verification. Re-traced end to end:

**The zeroing is real** — `src/pages/brand-creator-profile.tsx`:

| Cited | Actual | Content |
|-------|--------|---------|
| `:300-305` | **`:303-305`** | `completedCampaigns: 0`, `rating: 0`, `reviewCount: 0` |
| `:336-340` | **`:337-339`** | `completionRate: 0`, `onTimeDelivery: 0`, `repeatClients: 0` |

Both citations are ranges that *contain* the real lines. Accepted.

**All 8 render sites are unguarded — each opened and read in context.** There is no conditional, no empty state, no `isApiLive` branch, no null-coalesce anywhere in the enclosing JSX:

| Line | Verified content | Guard? |
|------|------------------|:------:|
| `:595` | `value: creator.stats.completedCampaigns.toString()` | none — literal array element |
| `:596` | `value: creator.stats.rating.toString()` | none |
| `:662` | `` value: `${creator.metrics.completionRate}%` `` | none |
| `:663` | `` value: `${creator.metrics.onTimeDelivery}%` `` | none |
| `:664` | `` value: `${creator.metrics.repeatClients}%` `` | none |
| `:881` | `<p className="text-4xl font-semibold">{creator.stats.rating}</p>` | none |
| `:888` | `i < Math.floor(creator.stats.rating)` | none |
| `:896` | `{creator.stats.reviewCount} reviews` | none |

**The live path is real.** `liveApi = isApiLive()` → `API_MODE === 'live'` (`src/lib/api.ts:53-58`), and `src/lib/api.ts:81-88` makes `VITE_API_MODE=live` **mandatory in production builds**. `api.creators.get(id)` issues `GET /creators/${id}` (`src/lib/api.ts:1353-1357`) and the result goes straight into `buildLiveCreatorView`. A real brand on a real deployment hits this mapper on every creator profile view. Confirmed.

**The DTO really lacks the fields.** `CreatorDtos.java:21-47` — exactly 20 components, counted by hand. No `rating`, no `completedCampaigns`, no `reviewCount`, no completion/on-time/repeat. Confirmed.

**The richer DTO really has them.** `DiscoveryDtos.java:62-82` — `long completedCampaigns` and `BigDecimal avgRating` both present. Confirmed at the cited lines.

#### Severity: 🔴 HIGH is justified — and if anything the write-up undersells it

Arguing the downgrade first, honestly: this is a display defect, not a money or authz defect; no wrong value is written anywhere; a brand can still read followers, engagement and platform stats, which are real. That is a genuine MEDIUM case.

It loses, for a reason Part 9 identified but did not press hard enough — **and for one it missed entirely.**

The one it identified: this is not a new-account edge case. The wire *never* carries these fields, so **every creator on the platform, permanently**, presents to a paying brand as a zero-star operator with a 0% completion rate. It is the input to a hiring and spending decision.

The one it missed — **`:900-903`, directly beside the fabricated `0`:**

```
<p>Based on verified brand collaborations</p>
<p className="mt-1">All reviews are from completed campaigns</p>
```

The screen does not merely show a wrong number. It shows a wrong number **under a printed attestation that the number is verified and sourced from completed campaigns.** That converts a missing-data bug into an affirmative false statement about a third party's professional track record, on the screen where money is committed. HIGH holds.

#### §84's open question, now closed

Part 9 correctly refused to certify that fix option 1 is safe. I checked it:

- `completedCampaigns` — `CreatorDiscoveryService.java:247-249`, a real count of `CollaborationStatus.COMPLETED` rows.
- `avgRating` — `CreatorDiscoveryService.java:846-853` (`computeAvgRating`), a real mean of `BRAND`-authored star reviews, returning **`null`, not `0`**, when there are none. The `H-22` note at `:271-275` records that a previous version passed the AI quality score off as a rating and that this was fixed.

**Fix option 1 is safe and is the correct fix.** It also closes D-14. The one condition: `avgRating` is nullable by design, so the render must handle `null` as `—` rather than re-zeroing it — which is precisely fix option 2's discipline. **Do both.**

---

### 86.2 — PR-2 · **REAL BUG**, but the finding is inverted — severity → 🔴 HIGH

The mechanical claims all verify:

- `CreatorCampaignMapper.java:60` — `return new BrandSummary(workspace.getId(), workspace.getName(), workspace.getLogoUrl());` — exact line, exactly 3 fields. Confirmed.
- `Workspace` really carries verification — `Workspace.java:48` (`private VerificationStatus verificationStatus`), getter at `:191`. Confirmed.
- `BrandSummary` really is 3 fields — `CreatorCampaignDtos.java:21`. Confirmed.
- No creator-facing surface exposes brand verification **as data** — confirmed across every access path (see §86.3).

**But the framing is wrong, and the wrong framing hides a worse bug.** Part 9 says the creator "cannot tell whether a brand is verified." The truth is the opposite and is materially more damaging: **the creator is told, on two separate live screens, that every brand is verified.** See §86.5 / M-1.

A withheld trust signal is a gap. A **fabricated** trust signal is a misrepresentation — the same defect class as PR-1, pointed the other way. PR-2 and M-1 are one defect with two halves, and the pair is **HIGH**, not MEDIUM: it is the signal a creator relies on before shipping physical product to a stranger's address, and the platform currently answers "yes, verified" for an `UNVERIFIED` workspace (`Workspace.java:126` sets `UNVERIFIED` as the default on creation).

---

### 86.3 — §83a · **CONFIRMED** (and verified past routes, as challenged)

Part 9 enumerated 23 routes and stopped. I did not accept a route-list as proof of absence. Verified four further ways:

1. **Components** — no brand modal, drawer, `HoverCard`, `Popover` or `Sheet` anywhere in `src/components/creator/**`. Every brand name and logo rendered to a creator is **non-interactive text/avatar**: no `onClick`, no `Link`. Every creator-side navigation target is `/creator/*`.
2. **FE clients, all paths** — `src/lib/api.ts`, `src/lib/meera-api.ts`, `src/hooks/**`, and raw `fetch(`. No `/brands/:id`, `/brand/profile`, or `/creator/brands` exists anywhere in `src/`. `useBrandProfile.ts:43` → `meera-api.ts:525-537` is `GET /meera/brand-profile` on a **brand** token.
3. **Backend role guards** — `MeeraController.java:202` gates `BrandProfileResponse` behind `brandContextService.requireBrandWorkspace` → `BrandContextService.java:36-40` throws `WRONG_USER_TYPE` for a non-`BRAND` principal. Every `Brand*` DTO in the codebase is brand-only or `hasRole("ADMIN")`.
4. **Field union across all creator surfaces** — campaigns, applications, coupons, affiliate earnings, deals, chat, disputes, portfolio collabs. Union is exactly **three real fields**: `workspaceId`, `name`, `logoUrl`. `DealService.java:1123-1135` returns `handle == null` for a `CREATOR` viewer.

§83a is correct and now properly evidenced.

---

### 86.4 — §83b · **"BY DESIGN, NOT A DEFECT" — VERDICT NOT DESERVED**

Part 9 wrote: *"This is not a defect… correct privacy design, **spec'd and implemented faithfully**."*

It certified that on the strength of a source comment (`CreatorCampaignDtos.java:10-15`) which cites:

> `wiki/tech/creator/05_CREATOR_CAMPAIGNS_SPEC.md` section 7.1

**That document does not exist.** It is not in `git ls-files`. `wiki/tech/creator/` contains exactly three tracked files, none of them a spec:

```
wiki/tech/creator/AGENT_LOOP_WAKE_CREATOR.log
wiki/tech/creator/AGENT_LOOP_WAKE_CREATOR.pid
wiki/tech/creator/AGENT_LOOP_WAKE_CREATOR.ps1
```

So the "spec" half of "spec'd and implemented faithfully" is **unverifiable — there is nothing to be faithful to.** Part 9 read a comment, could not have opened the document it names, and issued an absolution in the document's voice. That is the exact failure this audit's own method warnings were written to prevent, committed while quoting the artifact that triggers it.

The verdict fails on its own internal logic too. §83b declares the 3-field shape correct; §83c, one subsection later, says a 4th field belongs there. Both cannot stand. **§83c is right**, so §83b's absolution is wrong.

**Corrected ruling:** the *narrow* privacy principle — brand contact details must not appear in a creator-visible campaign listing — is sound and the code does honour it (`CreatorCampaignMapper.java:60` passes no email, phone, address, GST/PAN, spend or member list; I confirmed none of those reach any creator surface). **That much is good design.** But "no contact info leaks" does not establish "these 3 fields are the right 3 fields." `BrandSummary` is **under-specified, not correct** — and it is under-specified in a way that costs the creator the one signal that matters most. Downgrade §83b from *"not a defect"* to *"correct on exclusion, incomplete on inclusion."*

---

### 86.5 — MISSED by Part 9

#### 🔴 **M-1 (HIGH) — Creators are shown a fabricated "Verified Brand" badge on every deal. Live path.**

This is the single most important thing Part 9 failed to catch, and it sits directly inside its declared scope — a trust signal crossing the creator↔brand boundary as fabricated data.

**Two independent surfaces, both live:**

**(a) `src/pages/creator-deals.tsx:577-579`** — blue verified check next to every brand name:
```tsx
{deal.brandVerified && (
  <CheckCircle2 className="h-3.5 w-3.5 text-blue-500 shrink-0" />
)}
```
Fed by `src/lib/creator-deal-mappers.ts:184`:
```ts
brandVerified: true,   // <- hardcoded, not from any field
```
Live path confirmed: `creator-deals.tsx:248` and `:281` both call `remote.map(mapDealToDealsPageRow)`. The guard at `:577` is a guard on a constant — it never fails. **Every brand gets a blue check.**

**(b) `src/pages/creator-chat.tsx:1774-1777`** — deal-room header, unconditional JSX with no guard at all:
```tsx
<Badge variant="outline" className="text-[10px]">
  <Building2 className="h-3 w-3 mr-1" />
  Verified Brand
</Badge>
```
Live path confirmed: `creator-chat.tsx:661` — `setDealRooms(remote.map(mapDealToChatRoom))`.

`Workspace.java:126` sets `VerificationStatus.UNVERIFIED` as the default at creation. So an unverified brand is displayed to a creator as **"Verified Brand"** in the room where the creator agrees to ship product.

**And the comment 14 lines below the fabrication says the opposite** (`creator-deal-mappers.ts:198-202`):

> *"The page renders each behind a truthy guard, so they simply don't show — an honest empty state, **never a fabricated rating/badge**."*

That is true of `brandRating` and `brandPaymentSpeed`, which really are `undefined`. It is false of `brandVerified: true` sitting at `:184` in the same object literal. A comment contradicting its own source, in the same function — precisely the hazard the method warnings name. Part 9 did not reach this file.

**M-1 supersedes PR-2's framing.** File them together; fix them together: plumb `Workspace.verificationStatus` into `BrandSummary` and `DealResponse`, delete the hardcoded `true` at `creator-deal-mappers.ts:184`, and put a real guard on `creator-chat.tsx:1774-1777`.

#### 🟡 **M-2 (MEDIUM) — `portfolioItems` is always empty on the wire. §81 and §82a are wrong about it.**

§82a lists field 18 `portfolioItems` as carrying *"id, title, description, thumbnailUrl, mediaUrl, platform"*, and §81's table asserts all **"20 — rendered as real data."** Both read the DTO signature and stopped there. The mapper tells a different story.

`GET /creators/{id}` → `CreatorDiscoveryService.get()` (`:222`) → `toResponseForWorkspace` (`:473-482`) → `CreatorMapper.toResponse`. And **`CreatorMapper.java:47` passes `Collections.emptyList()`** for `portfolioItems`. Unconditionally. The field is on the wire and is **always `[]`** — the brand-facing profile endpoint never carries portfolio data at all.

The FE then drops it a second time (`brand-creator-profile.tsx:329`, `portfolio: []`), and the Portfolio tab maps over the empty array at `:794` **with no empty state** — a blank grid under a tab labelled "Portfolio", for every creator.

So §81's "20 rendered as real data" is false, and the true count of fabricated/dead surface is larger than the 18 claimed. Two of the 20 wire fields never render.

#### 🟢 **M-3 (LOW) — Rates tab renders two empty framed boxes plus a disclaimer.**

`averageRate` and `currency` **are** on the wire (fields 15–16, real values via `CreatorMapper.averageRate`). The FE discards both at `brand-creator-profile.tsx:329` (`rates: { instagram: [], youtube: [] }`). The tab then renders two bordered cards headed "Instagram" and "YouTube" containing nothing, followed by `:872-874`:

> *"\* Rates are indicative and may vary based on campaign requirements, exclusivity, and usage rights."*

A disclaimer about rates that are not shown, while the real rate sits unused on the response. Same family as PR-1: real data available, dropped, replaced with a confident-looking empty shell.

#### ⚪ **M-4 — `userId` exposure: NOT a defect. Close §84's open item.**

Part 9 flagged it and declined to rule. Ruling: **benign.**

`GET /creators/{id}` is not public — `CreatorDiscoveryService.java:222-223` calls `brandContext.requireBrandWorkspace(principal)` before anything else, so only an authenticated brand workspace member reaches it. `userId` is an opaque internal identifier; no creator- or brand-side endpoint dereferences a *foreign* `userId` into anything privileged (it is a join key for collaborations and reviews, always re-scoped server-side). No authz consequence found.

The related claim in §82a — *"No PII crosses this boundary — no email, phone, address, or bank detail"* — **verified and correct.** `CreatorMapper.toResponse` (`CreatorMapper.java:21-49`) maps none of them; `city` is the finest-grained location field and is coarse. §82a is right about this.

#### ⚪ **M-5 — minor citation drift (no impact)**

- BR-18 comment cited `:321-323`; actual comment `:322-324`, value at `:325`. Off by one.
- Mapper cited `:300-305` / `:336-340`; actual `:303-305` / `:337-339`. Ranges contain the real lines. Accepted.

Both immaterial. Noted for the record, since this document has previously cited JSDoc as code.

---

## 87. Corrected register

| ID | Surface | Filed | Corrected | Ruling |
|----|---------|:-----:|:---------:|--------|
| **PR-1** | Creator profile (brand view) | 🔴 HIGH | 🔴 **HIGH** | ✅ REAL — all 8 render citations verified unguarded; aggravated by the false "verified collaborations" attestation at `:900-903` |
| **PR-2** | Brand verification (creator view) | 🟡 MEDIUM | 🔴 **HIGH** | ✅ REAL — but inverted: signal is not withheld, it is **fabricated positive** (see M-1) |
| **M-1** | Deals list + deal-room chat | — | 🔴 **HIGH** | 🆕 `brandVerified: true` hardcoded (`creator-deal-mappers.ts:184`) + unconditional badge (`creator-chat.tsx:1774-1777`), both live |
| **M-2** | Creator profile portfolio tab | — | 🟡 **MEDIUM** | 🆕 `CreatorMapper.java:47` always sends `Collections.emptyList()`; blank grid, no empty state |
| **M-3** | Creator profile rates tab | — | 🟢 **LOW** | 🆕 real `averageRate` dropped; empty boxes + disclaimer |

**Corrected running total: 44 live defects · 10 HIGH · 0 CRITICAL · 1 oracle-proved · 2 struck.**
*(Part 9 filed 41/8. +M-1 HIGH, +M-2 MEDIUM, +M-3 LOW; PR-2 upgraded MEDIUM→HIGH.)*

**Struck claims:** §81 "20 fields rendered as real data" (false — at least 2 dead); §82a field 18 `portfolioItems` described as carrying data (false — always `[]`); §83b "not a defect… spec'd and implemented faithfully" (unsupported — the cited spec does not exist).

---

## 88. The lesson this verification adds

Part 9 found two real bugs and then made one method error twice, in opposite directions.

It read `CreatorDtos.java` and concluded the wire carries 20 fields of real data — **without opening the mapper that fills them.** `CreatorMapper.java:47` hardcodes an empty list. A DTO signature is a *promise*, not a *fact*; only the mapper is the fact.

Then it read `CreatorCampaignDtos.java:10-15` and concluded the design was correct — **without opening the spec that comment cites**, which does not exist. It applied the same trust to a comment that it correctly refused to apply to the `TODO(vikram)` lines a few files away.

The document already knows this. Its own §82b praises the mapper for being "honest" in its TODOs while the render lies — the insight that *a truthful annotation next to untruthful behaviour is still untruthful behaviour*. It then failed to apply that insight to `creator-deal-mappers.ts`, where a comment claiming "never a fabricated rating/badge" sits fourteen lines under `brandVerified: true`.

**Trace the field, not the type. Open the document, not the reference to it.** An audit that stops at the signature will certify a fabrication as a feature — which is exactly what §83b did, and exactly what M-1 cost.

---

*Part 9 verification: 100% of cited line numbers opened individually against git-tracked sources; all four access paths (`api.ts`, `meera-api.ts`, `hooks/**`, raw `fetch`) searched; sibling worktrees excluded. No live server probed — M-1 and PR-1 are source-proved, not screen-observed.*

**Signed: Priya, CTO**

---
---

# PART 10 — Brand Onboarding · Billing / Subscription API Audit

> **Scope:** the two largest remaining brand-side gaps — how a brand workspace is created, and the subscription money path (distinct from the wallet/escrow path audited in Part 5).
> **Method:** Static code trace + bidirectional caller census + `tsc`.
> **Branch:** `fix/brand-audit-remediation` · **Date:** 2026-08-09

---

## 89. Audit Summary

| Surface | APIs | Backend match | Callers | Defects |
|---------|-----:|:-------------:|:-------:|---------|
| **Onboarding** | 4 | ✅ 4/4 | ✅ all | 1 LOW |
| **Billing / subscription** | 6 | ✅ 6/6 | ✅ all | 1 LOW |
| **Brand invoicing (GST docs)** | 4 | ✅ 4/4 | ✅ all | 0 |

| Oracle | Result |
|--------|--------|
| `npx tsc --noEmit` | ✅ **0 errors, exit 0** |

**Verdict: all 14 endpoints exist, match, and are called. Zero zero-caller findings — the first surface in this document with none. The subscription money path is the best-defended code in the codebase.**

---

## 90. ~~✅ BILLING — the strongest money code in this repo~~ → **PRAISE NOT DESERVED · 2 HIGH defects (§96-107)**

> 🚫 **VERDICT OVERTURNED.** This section certified six risk guards as holding. **Two do not.** The praise was reached by (a) accepting a **class javadoc as evidence**, and (b) never tracing the subscription lifecycle past `cancel()` — which §93 below *itself records as untraced*. Certifying a money path while admitting its exit path was never traced is the same failure as §76 (Help) and §60 (Pipeline), for the third time.
>
> | Guard originally certified | Verified |
> |---|---|
> | Client cannot influence amount | ✅ holds |
> | `RAZORPAY_MISCONFIGURED` per-request check | ✅ holds — **earned praise**, correctly placed before `createSubscription:198` |
> | Free tier not purchasable / plan-disabled guard | ✅ holds |
> | **Duplicate-charge protection** | ❌ **BL-3 HIGH** — `ALREADY_SUBSCRIBED` cannot fire before the first payment |
> | **Subscription row only from verified webhook** | ❌ **BL-5** — false; two other write paths exist |
> | **Cancel at period end** | ❌ **BL-2 HIGH** — the job that ends it does not exist |

### 🔴 BL-2 — Cancelling a subscription never ends it; the workspace keeps Pro free, forever (HIGH)

`SubscriptionService.java:231-233` states the design:

> *"The renewal/dunning job (Phase 4, Task 24) is what flips status to CANCELLED once `currentPeriodEnd` passes with `cancelAtPeriodEnd` set."*

**That job does not exist.** Repo-wide, the only readers of `cancelAtPeriodEnd` are a response DTO (`BillingController:202`) and two label strings (`brand-billing-settings.tsx:458,462`). Nothing acts on it.

Three independent confirmations:

| Evidence | Finding |
|---|---|
| `SubscriptionDunningJob.java:125` | queries `findByStatus(PAST_DUE)` only — never sees a cancelled row |
| `RazorpayWebhookController.java:98-139` | has **no `subscription.cancelled` case**; falls through to `default -> {}` |
| `SubscriptionRenewalResetJob.java:99-102` | queries `findByStatus(ACTIVE)` + `currentPeriodEnd < now()` — **a cancel-at-period-end row matches this exactly** — then calls `applyRenewalSafetyNet` with no `cancelAtPeriodEnd` check |

So the row is not merely left ACTIVE — it is **actively renewed**. The reset job advances the period and re-allots Pro AI credits **daily, indefinitely**. `getActivePlanForWorkspace` keeps returning Pro, so fee bps, seat limits, exports and templates all persist **at zero cost**.

`SubscriptionStatus.CANCELLED` is written by nothing in production — only a unit test (`SubscriptionServiceTest.java:161`). **Churn therefore reads 0 by construction.**

### 🔴 BL-3 — No idempotency on checkout; a double-submit orphans a live Razorpay subscription (HIGH)

The `ALREADY_SUBSCRIBED` guard (`:185-190`) requires `existing.getStatus() == ACTIVE && proPlan.getId().equals(existing.getPlanId())`. But `initiateCheckout` **writes no row** — so on a first purchase there is nothing to match, and the guard **cannot fire**. There is no lock, and the `workspace_id` UNIQUE constraint (`V54:36`) never engages because nothing is inserted.

The only protection is `checkoutBusy`, a **client-side** flag (`brand-billing-settings.tsx:253`).

Two authorised checkouts → the upsert at `:426-427` falls through to `findByWorkspaceId`, and `:468` `linkRazorpaySubscription` **silently overwrites**. The first Razorpay subscription is orphaned: **still charging the customer, invisible locally, and uncancellable through the product.**

### 🟡 BL-4 — Any workspace member can start or cancel the company's subscription (MEDIUM)

`BillingController:161,170` guard only with `requireBrandWorkspace`, which checks `userType` and nothing else (`BrandContextService:43-66`). **A VIEWER-role member can initiate a recurring charge or cancel the company's plan.**

Every comparable surface gates properly — `WorkspaceMemberService:130,262,318,333` and `WalletController:86` all use `requireRole(OWNER, ADMIN)`.

### 🔵 BL-5 — "Only ever written from a verified webhook" is false (LOW)

Two additional write paths exist: `createFreeSubscription:117-132` — reachable from a **GET** (`/billing/plan` via `BillingController:87`) — and `grantAdminPlan:271-322`. The class javadoc says otherwise, and this audit repeated it verbatim as verified fact.

### ⚠️ Citation error in this section

§90 cited `SubscriptionService:205-207` as evidence for at-period-end cancel. Those three lines are **one javadoc line, a `*/`, and `@Transactional`** — zero executable code. The real evidence is `:229` and `:234`. **This is the fourth time this document cited a comment as code.**

---

## 90a. What the billing path genuinely does well

| # | UI Action | FE Call | HTTP | Endpoint | Backend | Status |
|---|-----------|---------|------|----------|---------|--------|
| 1 | Plan + subscription state | `billing.getPlan()` | GET | `/billing/plan` | `BillingController:83` | ✅ |
| 2 | Invoice history | `billing.getInvoices()` | GET | `/billing/invoices` | `:93` | ✅ |
| 3 | Usage meters | `billing.getUsage()` | GET | `/billing/usage` | `:105` | ✅ |
| 4 | Download invoice PDF | `billing.downloadInvoicePdf(id)` | GET | `/billing/invoices/{id}/pdf` | `:144` | ✅ raw bytes, not envelope |
| 5 | **Upgrade to Pro** | `billing.initiateCheckout('PRO')` | POST | `/billing/checkout` | `:158` | ✅ |
| 6 | **Cancel subscription** | `billing.cancelSubscription()` | POST | `/billing/cancel` | `:168` | ✅ |
| 7-10 | GST Doc#2/#3a invoices + PDFs | `brandInvoicing.*` (`api.ts:3026-3041`) | GET | `/billing/campaign-invoices`, `/commission-invoices` (+ PDFs) | `BrandInvoicingController:48,59,68,79` | ✅ |

> Note: `BillingController` and `BrandInvoicingController` **share the `/billing` prefix** — two controllers, one namespace. Legal in Spring, and worth knowing before adding a route.

### Why this path is the counterexample to Part 5

The wallet/escrow path produced P-1′, P-3 and P-4. The subscription path, tracing the same class of risk, holds on every one:

| Risk | Subscription path |
|------|-------------------|
| **Client sets the price** | ❌ impossible — only `planCode` crosses the wire; the amount is derived server-side from the `Plan` entity |
| **Duplicate charge on double-submit** | ✅ guarded — `ALREADY_SUBSCRIBED` 409 when an ACTIVE Pro subscription already exists (`SubscriptionService:185-190`) |
| **Paying for a disabled product** | ✅ guarded — `PLAN_NOT_AVAILABLE` 409 if the Pro plan is inactive (`:179-182`) |
| **Charging for a free tier** | ✅ guarded — `FREE_PLAN_NO_CHECKOUT` 400 (`:151-156`) |
| **Local state written without proof of payment** | ✅ the `Subscription` row is **only ever written from a verified webhook** (class javadoc) |
| **Hard mid-cycle cancellation** | ✅ cancel is **at period end**, never mid-cycle (`:205-207`) |

### The standout: it refuses to take money it could not confirm

`SubscriptionService:170-176` checks, **per request**, that Razorpay is not half-configured:

```java
if (razorpayClient.isConfigured() && !razorpayClient.isFullyConfigured()) {
    throw new ApiException("RAZORPAY_MISCONFIGURED",
        "Payment is not fully configured (webhook secret missing) — refusing to start"
        + " a real payment that could not be confirmed. …", HttpStatus.SERVICE_UNAVAILABLE);
```

The reasoning is written out in full: `keyId`/`keySecret` and `webhookSecret` are provisioned independently, so a rotation or partial migration can leave checkout live while signature verification fails closed forever — taking a real payment on Razorpay's hosted page while the local `Subscription` row is never written, *"silently stranding a paying customer with no Pro access."* It is checked per-request rather than at boot specifically so a bypassed startup validator cannot defeat it.

**This is the discipline P-4 lacked** — and it originated as a red-team HIGH-1 fix, which is the loop working.

### 🔵 BL-1 — `parsePlanCode` silently coerces invalid input (LOW)

`BillingController:175-181`:

```java
private static PlanCode parsePlanCode(String raw) {
    try { return PlanCode.valueOf(raw); }
    catch (Exception e) { return PlanCode.PRO; }   // ← any invalid value becomes PRO
}
```

`CheckoutRequest(String planCode)` (`BillingDtos:66`) carries **no validation annotation** — no `@NotBlank`, no `@Pattern`. A null, typo, or malformed value is silently coerced to `PRO` instead of returning 400.

> ⚠️ **On first read this looks like "fails open to the paid tier." It is not, and this audit initially misread it.** `PlanCode` has exactly two values (`FREE`, `PRO`), and `initiateCheckout:151` rejects anything that isn't `PRO` immediately — so the default lands on the only purchasable plan. **Nobody is charged**: the caller still receives a Razorpay hosted-checkout URL they must actively complete.

Real impact is hygiene, not money: an invalid request on a payment endpoint is answered with a checkout instead of an error, so a client-side bug is masked rather than surfaced.

**Fix — corrected:** `@NotBlank` alone **would not fire**. `checkout()` at `BillingController:160` takes a bare `@RequestBody` with **no `@Valid`**, so bean validation never runs. The fix is `@Valid @RequestBody` **plus** the constraint, or an explicit 400 in `parsePlanCode` instead of the silent `PRO` default.

---

## 91. ONBOARDING — a clean 3-step flow with deferred KYC

| # | Step | FE Call | HTTP | Endpoint | Backend | Status |
|---|------|---------|------|----------|---------|--------|
| 1 | Create account | `auth.brandRegister(payload)` (`:63`) | POST | `/auth/brand/register` | `AuthController:64` | ✅ |
| 2 | Company details | `onboarding.saveBrandCompany(...)` (`:75`) | POST | `/onboarding/brand/company` | `OnboardingController:29` | ✅ |
| 3 | Finish → dashboard | `onboarding.completeBrand()` (`:97`) | POST | `/onboarding/brand/complete` | `:36` | ✅ |
| — | **Deferred** KYC | `onboarding.submitBrandKyc(...)` (`brand-kyc-prompt.tsx:131`) | POST | `/onboarding/brand/kyc` | `:42` | ✅ |

**Progressive disclosure is done properly.** Only three steps block entry; two heavier obligations are deferred to the moment they are actually needed:

- **GSTIN/PAN KYC** → first campaign creation
- **Wallet funding** → first deal acceptance

The call sites were verified individually (`:63`, `:75`, `:97`, `:100`) rather than accepted from the file's header comment.

### 🔵 OB-1 — The KYC prompt tracks dismissal in localStorage, and its "no status endpoint" note is stale (LOW)

`brand-kyc-prompt.tsx` is a soft, dismissible prompt (CTO ruling 2026-07-11: *"KYC is OPTIONAL"*) — correct as designed. Dismissal is stored client-side:

```ts
const DISMISS_KEY = 'influora_brand_kyc_prompt_dismissed';
```

The file's own follow-up note says to *"hide automatically once a brand-KYC status endpoint exists."*

**That endpoint exists.** `GET /workspaces/me` returns `verificationStatus` — declared on the FE type at `api.ts:850` (`verificationStatus: VerificationStatus | null`) and carried in the login response too (`AuthDtos.java:20`).

**Consequence:** because dismissal is per-browser and submission status is never read, a brand that completes KYC on desktop is prompted again on mobile, in a private window, or after clearing storage — asked to re-submit GSTIN/PAN it already provided.

**Fix:** read `verificationStatus` from `workspaces.me` and hide the prompt when it is not `UNVERIFIED`; keep localStorage only as the "remind me later" mechanism.

---

## 92. 🟡 VER-1 — Brand verification status is stored, computed and readable — and three surfaces ignore it (MEDIUM)

> This is a **cross-audit connective finding.** It is the shared root cause behind **M-1** and **PR-2** from Part 9, and **OB-1** above. Filed once here rather than three times.

The data is unambiguously available:

| Layer | Evidence |
|-------|----------|
| Entity | `Workspace.verificationStatus`, defaulting to `UNVERIFIED` (`Workspace.java:126`) |
| Read endpoint | `GET /workspaces/me` → `verificationStatus` (`api.ts:850`) |
| Login response | `AuthDtos.java:20` carries it on the workspace summary |
| Team surface | `WorkspaceMemberDtos.java:57` exposes it to the brand's own members |

**Three surfaces that should use it, and do not:**

| Surface | What it does instead | Filed as |
|---------|---------------------|----------|
| Brand KYC prompt | localStorage dismissal flag; never reads status | **OB-1** |
| `BrandSummary` (creator-facing campaign listing) | omits the field entirely — creator cannot see it | **PR-2** |
| `creator-deal-mappers.ts:184` | **hardcodes `brandVerified: true`** — shows every brand as verified | **M-1** |

The third is the dangerous one, and this part's evidence sharpens it: M-1 is not "the status was unavailable so a default was chosen." **The status is one field on an endpoint the frontend already calls.** A verified-brand badge is rendered from a literal `true` while the real value sits one property away.

> ⚠️ **"One field away" and "single change closes three findings" are BOTH WRONG — corrected.** `GET /workspaces/me` returns the **caller's own** workspace. A creator cannot read a *brand's* status from it, and `DealDtos.DealResponse` (`:24-43`) carries **no verification field at all**.

**Corrected fix — this is two changes, not one:**

| Finding | Fix | Cost |
|---|---|---|
| **OB-1** (brand's own KYC prompt) | read `verificationStatus` from `workspaces.me` — genuinely one field away | small, FE-only |
| **M-1 + PR-2** (creator sees brand) | **requires a backend DTO change** — add `verificationStatus` to `BrandSummary` and/or `DealResponse`, then delete the hardcoded `true` | backend + FE |

The M-1 badge is still indefensible — it renders a literal `true` rather than nothing — but the honest statement is that **the creator-facing surfaces have no field to read yet.** This audit's original framing understated the work.

---

## 93. NOT CHECKED — Part 10 (law 5)

| Not verified | Why |
|-------------|-----|
| Runtime behaviour against a live server | Static trace only; **no subscription was purchased and no checkout completed** |
| Whether Razorpay webhook signature verification actually succeeds | Requires live Razorpay + webhook delivery |
| Whether `Subscription` rows are written correctly on `subscription.activated` | Webhook handler not traced |
| ~~Whether plan **entitlements** are enforced (`@RequiresPlan`)~~ | ✅ **CLOSED — they ARE enforced.** Verification traced `PlanGateWebConfig:36-42` + `PlanGateInterceptor:32-54`. This row was wrong to list it as unknown |
| Whether GST invoice **numbering/content** is correct | Doc#2/#3a generation logic not traced — and Part 5 noted a known release-time invoice gap |
| Whether `/onboarding/brand/complete` gates anything server-side | Only the client navigation to `/brand/dashboard` was traced |
| Whether an incomplete onboarding can reach the dashboard by direct URL | Route guards not traced |
| Usage-meter accuracy (`/billing/usage`) | Reachability only |

---

## 94. Register additions

| ID | Surface | Severity | Evidence | Summary |
|----|---------|----------|----------|---------|
| **BL-2** | Billing | 🔴 **HIGH** | believed | **Cancellation never takes effect** — no job flips `CANCELLED`; the renewal job *matches* cancelled rows and re-allots Pro credits daily. Free Pro forever; churn metric reads 0 by construction |
| **BL-3** | Billing | 🔴 **HIGH** | believed | **No server-side idempotency on checkout** — `ALREADY_SUBSCRIBED` cannot fire pre-first-payment; a double-submit orphans a live Razorpay subscription that still charges and cannot be cancelled in-product |
| **BL-4** | Billing | 🟡 MEDIUM | believed | **No role gate** — any VIEWER-role member can start or cancel the company's subscription; every comparable surface uses `requireRole(OWNER, ADMIN)` |
| **VER-1** | Cross-cutting | 🟡 MEDIUM | believed | Brand `verificationStatus` ignored by the KYC prompt (fixable now) and absent from creator-facing DTOs (needs backend change) — root cause of **M-1**, **PR-2**, **OB-1** |
| **OB-1** | Onboarding | 🔵 LOW | believed | KYC prompt re-prompts across devices — dismissal is localStorage-only; its "no status endpoint" note is stale |
| **BL-1** | Billing | 🔵 LOW | believed | `parsePlanCode` silently coerces invalid input to `PRO`; no `@Valid` on the request body |
| **BL-5** | Billing | 🔵 LOW | believed | "Subscription row only written from a verified webhook" is false — `createFreeSubscription` (reachable from a GET) and `grantAdminPlan` also write it |

**Running total: 52 live defects · 12 HIGH · 0 CRITICAL · 1 oracle-proved · 2 struck.**

### Verified clean (no false alarms raised)

Tenant isolation on `getPlan`/`getUsage`/`getInvoices` — all principal-derived, no path params. Invoice-PDF ownership (`InvoiceService:91-102`, resolve-then-check). Webhook HMAC fails closed (`:92-95`). Plan gating **is** wired. Onboarding 4/4 endpoints and call sites correct. And the `RAZORPAY_MISCONFIGURED` guard is real, correctly placed, and **the one piece of praise in §90 that survived**.

---

## 95. Closing — two money paths, opposite outcomes

This audit has now traced **both** brand money paths, and they are not built to the same standard:

| | Wallet / escrow (Part 5) | Subscription (Part 10) |
|---|---|---|
| Client can influence amount | escrow amount re-derived ✅ | ✅ only `planCode` crosses |
| Double-submit protection | ❌ **P-4** — `Date.now()` key defeats server dedupe | ❌ **BL-3** — guard cannot fire pre-first-payment; FE flag only |
| Refuses unconfirmable payment | ❌ not present | ✅ per-request `RAZORPAY_MISCONFIGURED` check |
| Fabricated figures on screen | ❌ **P-3** — "₹50,000 secured in escrow" on a zero-value deal | ✅ none found |
| Exit path reachable | ❌ **P-1′** — Meera-funded holds releasable by neither path | ❌ **BL-2** — cancel is recorded but **never takes effect** |
| Local state written without proof | — | ❌ **BL-5** — two non-webhook write paths |

> 🚫 **The original conclusion here was wrong.** This section claimed the subscription path was the disciplined counterexample to the escrow path. **It is not.** Both money paths fail on the *same two axes* — double-submit protection and a working exit — and both do so for the same reason: **the guard that was written is not the guard that runs.** Escrow's release exists but is unreachable; billing's cancellation is recorded but never executed.

**What actually distinguishes the two is narrower than claimed:** the subscription path has one genuinely excellent guard — the per-request `RAZORPAY_MISCONFIGURED` check — and it is annotated as a **red-team HIGH-1 fix**, with its reasoning written into the source.

That is the real lesson, and it now cuts both ways: **the one guard that had been adversarially reviewed is the one that held.** The five certified from reading the code and its comments include two HIGH failures. Review is what produced the working guard — and the absence of review is what let a cancellation path ship that renews the customer instead of ending them.

> ⚠️ **Everything here is `believed`.** No subscription was purchased, no checkout completed, no webhook observed. This part establishes that the subscription code is *well-constructed*, not that it *works in production*.

---

*Part 10 produced by static code trace, bidirectional caller census and `tsc` on branch `fix/brand-audit-remediation`. No live server was probed and no payment was made.*


---

# PART 10 — RED-TEAM VERIFICATION (Kabir)

> **Method:** every claim in §89-95 re-derived from primary source on `fix/brand-audit-remediation`, scoped to `git ls-files` (no worktree copies). Every cited line opened individually. No claim accepted from a comment or a javadoc.
> **Date:** 2026-08-09

## 96. Verdict — **NEEDS_FIX**

| | |
|---|---|
| Line citations checked | 14 |
| Accurate | 13 |
| **Wrong (javadoc cited as code)** | **1** — §90 `SubscriptionService:205-207` |
| Defects Part 10 filed | 3 (BL-1, OB-1, VER-1) |
| **Defects Part 10 MISSED** | **5** — 2 HIGH, 1 MEDIUM, 2 LOW |
| Claims that verified clean | tenant isolation, invoice ownership, plan-gate wiring, onboarding endpoint census |

**§90's "the strongest money code in this repo" is NOT DESERVED.** Two of the six risk guards it certifies do not hold. The audit reached the praise verdict by accepting a class javadoc as evidence and by never tracing the subscription lifecycle past `cancel()` — which §93 itself admits ("Webhook handler not traced"). A praise verdict was issued on a money path with the terminal half of that path explicitly unread.

---

## 97. §90 guard-by-guard — the audit's own table, re-checked

| # | Risk | Part 10 verdict | Kabir ruling |
|---|------|-----------------|--------------|
| 1 | Client sets the price | ❌ impossible | ✅ **CORRECT** |
| 2 | Duplicate charge on double-submit | ✅ guarded | 🔴 **WRONG — see BL-3** |
| 3 | Paying for a disabled product | ✅ guarded | ✅ **CORRECT** |
| 4 | Charging for a free tier | ✅ guarded | ✅ **CORRECT** |
| 5 | Local state written without proof of payment | ✅ only from verified webhook | 🔵 **FALSE AS WRITTEN — see BL-5** |
| 6 | Hard mid-cycle cancellation | ✅ cancel at period end | 🔴 **WRONG — see BL-2** |

### 1, 3, 4 — CORRECT, verified

`CheckoutRequest` (`BillingDtos.java:66`) is `record CheckoutRequest(String planCode)` — one field, no amount. `initiateCheckout` derives everything from the `Plan` entity; `ensureRazorpayPlanId` (`SubscriptionService:329-347`) prices from `fresh.getPriceInr()`. **No client-supplied amount reaches Razorpay.** Confirmed.

`PLAN_NOT_AVAILABLE` at `SubscriptionService:179-182` and `FREE_PLAN_NO_CHECKOUT` at `:151-156` — both citations exact, both before any Razorpay call. Confirmed.

### The `RAZORPAY_MISCONFIGURED` check — **REAL, and correctly placed.** §90 is right about this one

`SubscriptionService:170-176` is exactly as quoted, and sits **before** `ensureRazorpayPlanId` (`:192`) and `createSubscription` (`:198`) — so no Razorpay-side object is created in the half-configured state. `RazorpayClient:75-76` backs it:

```java
public boolean isFullyConfigured() {
    return isConfigured() && props.getWebhookSecret() != null && !props.getWebhookSecret().isBlank();
}
```

This is genuinely good code and the praise for *this specific guard* is earned. It is not enough to carry the verdict for the path.

### 🔴 Citation error — `:205-207` is javadoc, not code

§90 cites `SubscriptionService:205-207` as evidence that cancel is at period end. Those three lines are:

```
205      * subscription; a Free-tier row (no {@code razorpaySubscriptionId}) has nothing to cancel.
206      */
207     @Transactional
```

One javadoc line, a comment terminator, and an annotation. **Zero lines of cancellation logic.** The real evidence is `:229` (`razorpayClient.cancelSubscription(id, true)`) and `:234` (`setCancelAtPeriodEnd(true)`). This is another occurrence in this document of the exact failure its own method warning names. The *behaviour* claimed is real; the *citation offered for it* is documentation.

---

## 98. 🔴 BL-2 — **Cancelling a subscription never ends it. The workspace keeps Pro forever, for free** (HIGH)

This is the defect §90's guard #6 was one step away from finding. `cancel()` is at-period-end, as claimed. **Nothing ever executes the "at period end" part.**

`SubscriptionService:231-233` states the mechanism in a comment:

```java
// Status is intentionally left ACTIVE here — the subscription keeps working until the
// current paid period actually elapses. The renewal/dunning job (Phase 4, Task 24) is
// what flips status to CANCELLED once currentPeriodEnd passes with cancelAtPeriodEnd set.
```

**That job does not exist.** Repo-wide census of every reader of `cancelAtPeriodEnd` (git-tracked only):

| Reader | What it does |
|--------|--------------|
| `BillingController.java:202` | copies it into the response DTO |
| `brand-billing-settings.tsx:458,462` | renders "Access until" vs "Next billing date" |

That is the complete list. **No job, no service, no webhook handler ever reads it.** Three independent confirmations:

1. **`SubscriptionDunningJob.doRun():125`** — queries `findByStatus(PAST_DUE)` only. A cancelled subscription is `ACTIVE`, so it is never in the result set. The job never mentions `cancelAtPeriodEnd`.
2. **`SubscriptionRenewalResetJob.doRun():99-102`** — queries `findByStatus(ACTIVE)` filtered to `currentPeriodEnd < now()`. **A cancelled-at-period-end subscription matches this query exactly**, because it is still `ACTIVE` and its period has lapsed. The job then calls `applyRenewalSafetyNet` (`SubscriptionService:556-567`), which advances the period and re-allots Pro AI credits — **with no `cancelAtPeriodEnd` check anywhere.** The safety net designed to catch a missed webhook cannot distinguish a missed webhook from a deliberate cancellation, and renews the cancellation away. Every day. Forever.
3. **`RazorpayWebhookController.receive():98-139`** — the dispatch switch handles `subscription.activated`, `charged`, `halted`, `pending`. **There is no `subscription.cancelled` case and no `subscription.completed` case.** Razorpay's own terminal cancellation event falls to `default -> {}` and is acknowledged with a 200 and discarded.

`SubscriptionStatus.CANCELLED` exists in the enum and is *read* by `AdminBillingService:193` for the churn metric — but the only code in the repo that ever *writes* it is a unit test (`SubscriptionServiceTest.java:161`, driving the webhook method by hand). **In production nothing can ever set it.**

**Impact.** After `POST /billing/cancel`: Razorpay stops charging (the remote cancel at `:229` is real), but locally `status` stays `ACTIVE`, so `getActivePlanForWorkspace` (`:99-105`) keeps returning Pro. That single method is, by its own javadoc, what `BrandCampaignFeeService` (the platform fee bps), `WorkspaceMemberService` (seat limits) and `PlanGateInterceptor:45-49` (export + campaign templates) all derive from live. So the workspace permanently retains: the Pro fee rate, Pro seat limits, Pro tracked-creator and analytics caps, exports, campaign templates, and the Pro AI-credit allotment re-applied every cycle by `applyRenewalSafetyNet:563-566`. **The brand pays nothing and keeps everything.** The admin console's churn number reads 0 forever, so it is also invisible.

The `@Version`/`saveAndFlush` optimistic-lock work (V56, `:488`) and the staleness guard (`:456-466`) are careful, correct code guarding a lifecycle that has no terminal state.

**Fix:** add a `subscription.cancelled`/`subscription.completed` case to the webhook switch, AND exclude `cancelAtPeriodEnd && currentPeriodEnd < now()` from `SubscriptionRenewalResetJob`'s query while flipping those rows to `CANCELLED`. Both — the webhook alone would still be undone by the renewal job.

---

## 99. 🔴 BL-3 — Checkout has no idempotency and no lock; `ALREADY_SUBSCRIBED` cannot fire in the window that matters (HIGH)

§90 certifies `ALREADY_SUBSCRIBED` (`:185-190`) as the double-submit guard. The citation is exact, but the guard is a **read-then-check with no lock, no idempotency key, and no DB constraint that can engage.**

```java
Subscription existing = getByWorkspaceId(workspaceId).orElse(null);
if (existing != null
        && existing.getStatus() == SubscriptionStatus.ACTIVE
        && proPlan.getId().equals(existing.getPlanId())) {   // ← only fires if ALREADY on Pro
```

Three separate reasons it does not hold:

1. **It cannot fire during a first purchase.** `initiateCheckout` deliberately writes no local row (class javadoc, `:39-43`). So on the very first upgrade the row is either absent or the Free row — `existing.getPlanId()` is Free's id, the condition is false, and **every** concurrent request proceeds. The double-submit window is precisely the window the guard does not cover.
2. **`subscriptions.workspace_id` UNIQUE cannot help.** V54 line 36 does make it unique — but no row is inserted at checkout, so the constraint never engages here. §90's guard rests on nothing at the DB layer.
3. **The only protection is client-side.** `brand-billing-settings.tsx:253` sets `checkoutBusy` before the call and `:541` disables the button. A second tab, a replayed request, or curl bypasses it entirely.

**Consequence — this is the money part.** Each pass calls `razorpayClient.createSubscription` (`:198`), producing a *distinct* `sub_*` at Razorpay, each with `notes.workspaceId` pointing at the same workspace. If two get authorised, the webhook upsert resolves them into one local row:

```java
subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId)
        .or(() -> subscriptionRepository.findByWorkspaceId(workspaceId))   // SubscriptionService:426-427
```

The second event misses on `sub_B`, falls through to the workspace lookup, finds the row already linked to `sub_A`, and `linkRazorpaySubscription(sub_B)` (`:468`) **silently overwrites the link**. `sub_A` is now: still active at Razorpay, still charging the customer monthly, absent from the local database, invisible in `GET /billing/plan`, and **uncancellable** — `cancel()` (`:229`) can only pass the one id the row still holds. There is no reconciliation job for orphaned Razorpay subscriptions.

Note the asymmetry: `grantAdminPlan:276-282` raises `ALREADY_PAID_SUBSCRIBER` specifically to prevent clobbering a real billing record, and the webhook path carries `IdempotencyService.executeOnce` (`RazorpayWebhookController:264`). **The customer-facing checkout entry point is the one place with neither.** §95's scorecard row "Double-submit protection: Wallet ❌ P-4 / Subscription ✅" is inverted in kind, not degree — P-4 is a weak idempotency key; this is no idempotency key at all.

**Fix:** an `IdempotencyService` reservation keyed on `workspaceId + PRO` around `initiateCheckout`, or a `PENDING` subscription row written under the existing UNIQUE constraint before calling Razorpay.

---

## 100. 🟡 BL-4 — Any workspace member can buy or cancel the company's subscription (MEDIUM)

`BillingController.checkout():161` and `cancel():170` both gate with `brandContextService.requireBrandWorkspace(principal)` and nothing else. That method (`BrandContextService:43-66`) checks `userType == BRAND` and resolves a workspace. **It performs no role check.**

The codebase has the mechanism and uses it everywhere else:

- `WorkspaceMemberService.java:130, 262, 318, 333` — `brandContext.requireRole(actingMember, MemberRole.OWNER, MemberRole.ADMIN)`
- `WalletController.java:86` — the escrow-fund path is explicitly role-gated
- `PATCH /workspaces/me` — OWNER/ADMIN only (`WorkspaceController:60`)

So editing the workspace name requires ADMIN, but **starting a recurring charge on the company card, and cancelling the company's plan, require only membership.** A VIEWER-role member can cancel Pro for the whole workspace — and per BL-2 that cancellation is silent and locally invisible, so no one would see it. OWASP A01, and a direct inconsistency with the wallet path §95 holds up as the *worse*-built one.

**Fix:** `requireRole(..., OWNER, ADMIN)` on both write endpoints, matching `EscrowController#fund`.

---

## 101. 🔵 BL-5 — "the `Subscription` row is only ever written from a verified webhook" is false (LOW, but it is the load-bearing sentence of §90)

§90 row 5 offers "(class javadoc)" as its evidence — and the javadoc says something narrower than the audit reports. It says *"No local `Subscription` row is created **at checkout-initiation time**"* (`:39`). The audit generalised that to "only ever written from a verified webhook." Three non-webhook writers exist:

| Writer | Line | Writes |
|--------|-----:|--------|
| `createFreeSubscription` | `:117-132` | a new `ACTIVE` row — **reached from `GET /billing/plan`** via `getOrCreateFreeSubscription` (`BillingController:87`) |
| `grantAdminPlan` | `:271-322` | `ACTIVE` at any plan, `comp=true` |
| `applyRenewalSafetyNet` | `:556-567` | advances the period (the BL-2 mechanism) |

None of these is a paid-entitlement-without-payment hole on its own — Free is harmless and `grantAdminPlan` is SUPER_ADMIN-gated with MFA (`AdminBillingService:185`). The finding is the method: **a praise verdict on a money path was awarded on the strength of a comment, and the comment does not say what the audit reports it saying.** Worth noting `GET /billing/plan` performs a DB INSERT — a write on a GET.

---

## 102. 🔵 OB-2 — `/brand/dashboard` has no onboarding guard, and `/onboarding/brand/complete` gates nothing server-side (LOW)

Both open items in §93 are now answered.

**Route guard.** `App.tsx:79-87`:

```tsx
const ProtectedRoute = ({ children }) => {
  const isAuthenticated = localStorage.getItem('brand_token');
  ...
  return isAuthenticated || isDemoMode ? <>{children}</> : <Navigate to="/brand/login" />;
};
```

Presence of a token, nothing more. `brandRegister` issues that token at step 1, so **yes — a brand can type `/brand/dashboard` after step 1 and get in**, skipping company details and completion. (The dev-only `?demo=true` bypass at `:85` is correctly dead-stripped in production — that part is sound.)

**Server-side gate.** `OnboardingService.completeBrand():70-83` sets `user.setOnboardingCompleted(true)` and saves. Repo-wide, `isOnboardingCompleted()` is read in exactly two places — `AuthService.java:574` and `CreatorProfileService.java:197` — both of which only copy it into a response DTO. **No endpoint anywhere gates on it.** `/onboarding/brand/complete` sets a flag that is reported to the client and never enforced.

**Severity is LOW, not higher, and this is the part that needed checking:** `AuthService.brandRegister` (`:140-158`) creates the `Workspace`, the owner `WorkspaceMember` and the `Wallet` at registration, using the company name/industry/size collected in step 1. A brand that skips steps 2-3 lands on a functional dashboard with a real workspace, not a broken state. The gap is that onboarding is advisory, not that skipping it breaks anything.

---

## 103. Per-defect ruling — BL-1, OB-1, VER-1

### BL-1 — the downgrade is **CORRECT**. FALSE ALARM as a money bug, REAL as hygiene

Part 10's self-correction holds up on every line. `PlanCode.java` has exactly two constants:

```java
public enum PlanCode { FREE, PRO }
```

`BillingController:175-181` is quoted verbatim and correctly. `initiateCheckout:151` rejects everything that is not `PRO`, so the coercion default lands on the only purchasable plan and cannot escalate anything. `CheckoutRequest` (`BillingDtos:66`) genuinely carries no annotation, and `checkout():160` takes a bare `@RequestBody` with no `@Valid` — so even adding `@NotBlank` would not fire without also adding `@Valid`, which Part 10's fix line omits. **The first reading ("fails open to paid") was wrong; the downgrade to LOW is right.** One correction to the fix, and one addition: a garbage `planCode` still reaches `ensureRazorpayPlanId` and `createSubscription`, so an invalid request creates real objects in Razorpay's account before returning a URL. Still no charge. Still LOW.

### OB-1 — **REAL BUG**, confirmed on every line

`brand-kyc-prompt.tsx` uses a localStorage dismissal key as described, and never reads verification status. The claim that the status endpoint exists is **verified end-to-end, not just on the FE type** — which matters, because a declared TypeScript field is not proof the server sends it:

- `WorkspaceMemberDtos.java` — `record WorkspaceReadResponse(..., String verificationStatus)`. The backend really returns it. (§92 cites `WorkspaceMemberDtos.java:57`; the field is at line 56 of `web/dto/workspace/WorkspaceMemberDtos.java`. Off by one, and §92 omits the `web/dto/workspace/` path segment — the file does not exist at the path implied. Cosmetic.)
- `AuthDtos.java` — `record WorkspaceDto(String id, String name, String slug, VerificationStatus verificationStatus)`, in `web/dto/auth/`. Carried on login, as claimed (§92 cites `:20`; it is at `:19-20`).
- `api.ts:850` — `verificationStatus: VerificationStatus | null;` on `WorkspaceMeResponse`. Exact.

The re-prompt-across-devices consequence follows. Confirmed LOW.

### VER-1 — **REAL BUG, but the "one field away" claim is overstated on the one surface it calls dangerous**

The three cited lines are all exact:

| Citation | Actual content | |
|---|---|---|
| `Workspace.java:126` | `w.verificationStatus = VerificationStatus.UNVERIFIED;` | ✅ exact |
| `api.ts:850` | `verificationStatus: VerificationStatus \| null;` | ✅ exact |
| `creator-deal-mappers.ts:184` | `brandVerified: true,` | ✅ exact |

The hardcoded badge is real and should be fixed. **But §92's sharpening — "The status is one field on an endpoint the frontend already calls" — is wrong for M-1, the surface it singles out as dangerous.**

`GET /workspaces/me` returns the **caller's own** workspace. The caller at `creator-deal-mappers.ts:184` is a *creator* rendering a *brand's* badge; `/workspaces/me` would return the creator's own workspace, not the brand's, and `WorkspaceController.getMyWorkspace` is brand-scoped besides. The field would have to arrive on the deal payload — and `DealDtos.DealResponse` (`web/dto/deal/DealDtos.java:24-43`) has `counterpartyId`, `counterpartyName`, `counterpartyAvatar`, `counterpartyHandle` and **no verification field of any kind**.

So VER-1's fix line — *"single change, closes three findings"* — is not achievable. OB-1 genuinely is one field away on an endpoint already called. **M-1 and PR-2 require a backend DTO change first.** The defect stands; the remediation estimate does not, and shipping against it would leave the badge lying.

---

## 104. Claims that verified CLEAN — no false alarms raised here

Checked adversarially and found genuinely sound. §90 deserves credit for these:

- **Cross-workspace data leakage on `getPlan`/`getUsage`/`getInvoices` — none.** All three derive `workspaceId` from `principal` via `requireBrandWorkspace`. No path or query parameter is accepted on any read. There is no IDOR surface to attack.
- **Invoice PDF ownership — correctly enforced.** `InvoiceService:91-102` resolves by id *then* compares `invoice.getWorkspaceId()` against the caller's, 403 on mismatch. Resolve-then-check, not trust-the-param. Correct.
- **Plan entitlement enforcement — actually wired**, contrary to §93's "annotation seen, enforcement not executed." `PlanGateWebConfig:36-42` registers `PlanGateInterceptor` globally; `PlanGateInterceptor:32-54` reads the resolved `Plan` and throws `UPGRADE_REQUIRED` 402. Live on `CampaignTemplateController:53` and `ReportExportController:36`. **No entitlement bypass found** — but note BL-2 defeats it by keeping the plan Pro rather than by bypassing the gate.
- **Webhook signature verification — fails closed before parsing.** `RazorpayWebhookController:92-95` HMAC-verifies the raw body and throws before any dispatch. Per-delivery idempotency at `:264` keyed on `eventType + subscriptionId + created_at`.
- **Onboarding endpoint census — 4/4 correct.** `OnboardingController` `/company`, `/complete`, `/kyc` all present; call sites at `brand-onboarding.tsx:63, 75, 97` and `brand-kyc-prompt.tsx:131` all verified individually. §91's table omits the filename for rows 1-3 (the bare `:63/:75/:97` are `brand-onboarding.tsx`, not `api.ts`), but the citations resolve correctly.

---

## 105. Corrected Part 10 register

| ID | Surface | Severity | Evidence | Summary |
|----|---------|----------|----------|---------|
| **BL-2** | Billing | 🔴 **HIGH** | static-certain | **Cancellation never terminates.** Nothing in the repo reads `cancelAtPeriodEnd`; no `subscription.cancelled` webhook case; `SubscriptionRenewalResetJob` renews cancelled subs forever. Pro entitlements + AI credits retained indefinitely at zero cost, and invisible in churn metrics |
| **BL-3** | Billing | 🔴 **HIGH** | static-certain | **No idempotency or lock on `/billing/checkout`.** `ALREADY_SUBSCRIBED` cannot fire before the first payment; duplicate Razorpay subscriptions are creatable, and the webhook upsert silently orphans the first — still charging, uncancellable |
| **BL-4** | Billing | 🟡 MEDIUM | static-certain | No role gate on `/billing/checkout` or `/billing/cancel` — any active member can subscribe or cancel; every comparable write in the codebase requires OWNER/ADMIN |
| **VER-1** | Cross-cutting | 🟡 MEDIUM | believed | *(upheld, scope corrected)* `brandVerified: true` hardcoded — but the fix is NOT one field: `DealDtos.DealResponse` carries no verification field, so M-1/PR-2 need a backend change |
| **BL-1** | Billing | 🔵 LOW | believed | *(upheld as downgraded)* `parsePlanCode` coerces to `PRO`; fix also needs `@Valid` on the `@RequestBody`, which Part 10's fix line omits |
| **BL-5** | Billing | 🔵 LOW | static-certain | §90's "row only ever written from a verified webhook" is false — `createFreeSubscription` (reached from a **GET**) and `grantAdminPlan` both write it |
| **OB-2** | Onboarding | 🔵 LOW | static-certain | `/brand/dashboard` guards on token presence only; `onboardingCompleted` is never read by any endpoint. No broken state — the workspace is created at register |
| **OB-1** | Onboarding | 🔵 LOW | believed | *(upheld)* KYC prompt re-prompts across devices |

**Revised running total: 52 live defects · 12 HIGH · 0 CRITICAL · 1 oracle-proved · 2 struck.**

---

## 106. What this verification did NOT check

| Not verified | Why |
|-------------|-----|
| Runtime behaviour | Static only. No subscription purchased, no webhook delivered, no cancellation executed |
| Whether BL-2 has ever fired in production | Requires a real cancelled subscription plus a `subscriptions` table read |
| Whether Razorpay actually emits `subscription.cancelled` on a cycle-end cancel | Razorpay-side behaviour; BL-2 holds regardless, since the renewal job would undo it either way |
| GST invoice numbering/content | Same gap Part 10 declared |
| Whether `PlanGateFilter` (distinct from the Interceptor) resolves the plan correctly for every route | Wiring confirmed; per-route resolution not traced |

---

## 107. The lesson this verification adds

Part 5 was audited pessimistically and produced three HIGH findings. Part 10 was audited admiringly and produced three LOW/MEDIUM ones. **The subscription path is not better built than the escrow path — it was read more generously.**

Two specific habits produced the wrong verdict:

1. **A comment was accepted as evidence on a money path.** §90's "only ever written from a verified webhook" cites "(class javadoc)". The javadoc says something narrower, and three other writers exist. This document's own method warning says comments here lie; the warning was applied to defect-hunting and dropped for praise.
2. **The lifecycle was traced forward and never to its end.** `cancel()` was read, found correct, and certified — while §93 simultaneously recorded that the webhook handler and the jobs were untraced. Everything needed to find BL-2 sat in the half that was skipped, and `cancel()`'s own comment pointed straight at it.

> A "well-built" verdict is a claim, and it needs the same adversarial standard as a defect. §95's closing — *"The difference is not skill — it is review"* — is right, and it turns out to describe the audit as much as the code.

*Part 10 verification by adversarial re-derivation from primary source, `git ls-files`-scoped, on branch `fix/brand-audit-remediation`. Every cited line opened individually. No live server probed, no payment made.*

---
---

# PART 11 — Meera (Brand AI) API Audit

> **Scope:** the last unaudited brand-side surface. Meera is a **two-hop architecture** — browser → Spring (`influora-api`) → Python (`influora-ai`) — so this part traces both hops and the service-to-service boundary between them.
> **Method:** Static code trace + bidirectional caller census across both services + `tsc`.
> **Branch:** `fix/brand-audit-remediation` · **Date:** 2026-08-09

---

## 108. Audit Summary

| Layer | Endpoints | Backend match | Callers | Defects |
|-------|----------:|:-------------:|:-------:|---------|
| **Brand-facing** (`MeeraController`) | 7 | ✅ 7/7 | ✅ all | 0 |
| **Interaction** (`MeeraInteractionController`) | 1 | ✅ 1/1 | ❌ **0** | 1 LOW |
| **Internal S2S** (`MeeraInternalController`) | 11 | ✅ 11/11 | ✅ **11/11** | 0 |

| Oracle | Result |
|--------|--------|
| `npx tsc --noEmit` | ✅ **0 errors, exit 0** |

**Verdict: 19 of 20 endpoints exist, match, and are reached. The internal service-to-service boundary is a perfect 11/11 — the cleanest surface in this entire document. Two defects previously recorded against Meera were re-tested and are FIXED.**

---

## 109. Two prior claims re-tested — both now STALE

This audit carried two specific prior findings about Meera. Both were verified against current source rather than repeated:

| Prior claim | Status | Evidence |
|-------------|--------|----------|
| *"Outcome digest broken in the live path — `chat.py` drops the field"* | ✅ **FIXED** | `chat.py:180` now forwards `"outcome_digest": context_data.get("outcome_digest")`. The comment at `:179` describes the **old** behaviour (*"was always None"*). Java produces it (`MeeraContextService:140,152`), Python receives it, `assembler.py:314-315` renders it |
| *"On-behalf JWT minted read-only scope — 4/6 tools incl. analytics silently 403"* | ✅ **FIXED (partially, deliberately)** | `OnBehalfTokenService:68-69` — `SCOPE_DEFAULT` now carries `show_creators calculate_budget create_campaign get_campaign_performance`. The javadoc names this as **fix M-1, 2026-07-23**, for tools that *"were silently 403-ing in production"* |

> 🔎 **Method note:** both were reported as live defects in carried-forward notes. Re-testing cost two greps and prevented two false findings. **A recorded defect is a hypothesis about the past, not a fact about the present.**

---

## 110. Brand-facing surface — all 7 reached

| # | UI Action | FE Call (`meera-api.ts`) | HTTP | Endpoint | Backend | Status |
|---|-----------|--------------------------|------|----------|---------|--------|
| 1 | Open a Meera session | `:470` | POST | `/meera/sessions` | `MeeraController:95` | ✅ |
| 2 | Send a turn | `:498` | POST | `/meera/sessions/{id}/messages` | `:117` | ✅ returns `streamToken` + `streamUrl` |
| 3 | Load history | `:600` | GET | `/meera/sessions/{id}/messages` | `:166` | ✅ |
| 4 | AI credit meter | `:519` | GET | `/meera/credits` | `:181` | ✅ |
| 5 | Brand profile / analysis state | `:537` | GET | `/meera/brand-profile` | `:199` | ✅ |
| 6 | **Text-to-speech** (Sarvam) | `:651` `speak()` | POST | `/meera/voice/speak` | `:237` | ✅ via `useVoiceOutput.ts` |
| 7 | **Speech-to-text** | `transcribe()` | POST | `/meera/voice/transcribe` | `:279` | ✅ via `useVoiceInput` / `VoiceMode` / `Composer` |

`meera-api.ts` also reaches the money path directly — `POST /wallet/escrow/fund` (`:561`) and `GET /wallet/escrow/{id}` (`:583`). **The second of those is the client whose absence produced this document's false CRITICAL in Part 5.**

> ⚠️ **Near-miss recorded:** an initial census reported `voice/speak` as having **0 callers** — false. The search matched the URL string, but `useVoiceOutput.ts:4` imports `meeraApi` and calls `meeraApi.speak(text)`. **Fifth distinct false-zero pattern in this document: the caller uses the method name, never the path.**

---

## 111. ✅ Internal service-to-service boundary — 11/11, perfect

Every endpoint `MeeraInternalController` exposes is called by the Python service. No dead surface in either direction:

| Endpoint | Purpose |
|----------|---------|
| `/internal/meera/context` | brand context assembly for the prompt |
| `/show_creators` · `/calculate_budget` | read tools |
| `/create_campaign` · `/get_campaign_performance` | write/read tools (enabled by fix M-1) |
| `/request_payment` · `/confirm_launch` | **money tools — see ME-2** |
| `/messages` · `/turns/release` | turn lifecycle |
| `/analyze_site_result` | async site-analysis callback |
| `/interaction-log` | flywheel logging |

**This is the only surface in the entire audit with a perfect two-way match** — no endpoint without a caller, no caller without an endpoint.

### Security posture is real and reviewed

| Control | Evidence |
|---------|----------|
| **Per-tool scope enforcement** | `OnBehalfAuthResolver:143-159` asserts the required tool name is present in the token's space-delimited `scope` claim. Annotated as **Kabir's SECURITY FIX #1 follow-up #1** — before it, *"a read-scoped token was accepted by every route"* |
| **Stream token is single-use and narrowly scoped** | `StreamTokenService:67,91` — scoped to exactly one workspace + conversation + message, carrying `scope: chat:stream` |
| **Tool failures never break the turn** | `loop.py:451-467` converts a `SpringCallError` into a `tool_result` block with `is_error: true`, yields it, and continues |

---

## 112. 🟡 ME-2 — Meera cannot execute either money tool, and answers by narrating a refusal (MEDIUM)

| Field | Detail |
|-------|--------|
| **Severity** | 🟡 MEDIUM — deliberate security posture with an unhandled UX cost |
| **Where** | `OnBehalfTokenService:62-69` vs `influora-ai` tool registry |

`request_payment` and `confirm_launch` are **excluded from `SCOPE_DEFAULT` on purpose** (`:62-66`):

> The two money tools … are DELIBERATELY excluded here as defense-in-depth on the money path. They remain scope-gated by the resolver in addition to their OWNER/ADMIN check; **enable them via a dedicated scope only after Kabir's security review sign-off.**

**The exclusion is correct.** The defect is that nothing downstream knows about it:

- The Python service **still registers and calls both tools** (both appear in its `internal/meera/*` call set).
- So the model selects the tool, Spring rejects it on scope, and `loop.py` feeds the error back as a `tool_result`.
- The model then **narrates a refusal to the user** — the exact symptom the M-1 fix was written to remove for `create_campaign` and `get_campaign_performance`, whose javadoc records it as *"Meera could not create campaigns — narrated a refusal."*

A brand asking Meera to take payment or confirm a launch gets a model-improvised apology rather than a designed answer.

> 🔎 **Relationship to P-2 (Part 6).** These are **two different paths**, and P-2 remains correct: escrow funding works from `MeeraWorkspace.tsx` because the **browser** calls `/wallet/escrow/fund` with the user's own token. What is blocked is **Meera acting on the user's behalf** via the on-behalf token. The button works; the AI cannot do it for you.

**Fix:** remove both tools from the Python registry until the dedicated scope ships, so the model never offers a capability the platform will refuse. Then re-enable tool + scope together.

---

## 113. 🔵 ME-1 — The interaction-logging endpoint has no caller (LOW)

`MeeraInteractionController:46` serves `POST /workspaces/{workspaceId}/meera/interactions/option-tapped`. **Zero callers**, verified against every pattern that has previously produced a false zero in this document: `optionTapped`, `option_tapped`, `interactions/option`, `meera/interactions`, and a scan of both FE API layers.

Note the platform has a *second*, working interaction-logging path — `/internal/meera/interaction-log`, called by the Python service (§111). So option-tap telemetry from the browser is simply never sent.

Same class as **N-2**, **DP-2**, **D-14**: a built endpoint with no client.

---

## 114. NOT CHECKED — Part 11 (law 5)

| Not verified | Why |
|-------------|-----|
| Runtime behaviour of either service | Static trace only; **no Meera turn was executed** |
| Whether the SSE stream actually delivers tokens end-to-end | Requires both services running + a live model call |
| Whether the model *actually* selects the money tools in practice | ME-2's user-visible symptom is inferred from the tool registry + the M-1 precedent, **not observed** |
| Whether JWKS verification between Spring and Python succeeds at runtime | Key exchange not executed |
| Prompt-assembly correctness (`assembler.py`) | Only the `outcome_digest` field path was traced |
| AI credit accounting accuracy | `AICreditService` not traced |
| Whether Sarvam TTS/STT return usable audio | Provider behaviour out of scope |
| Whether `speak()`'s raw `fetch` (`meera-api.ts:659`) inherits token refresh | Noted, not traced — same *shape* as **N-1**, but not confirmed to share the defect |

---

## 115. Register additions

| ID | Surface | Severity | Evidence | Summary |
|----|---------|----------|----------|---------|
| **ME-2** | Meera | 🟡 MEDIUM | believed | Money tools are scope-gated out by design, but the Python service still offers them — Meera attempts, is refused, and narrates an improvised apology |
| **ME-1** | Meera | 🔵 LOW | believed | `POST …/meera/interactions/option-tapped` has no caller; browser-side option-tap telemetry is never sent |

**Running total: 54 live defects · 12 HIGH · 0 CRITICAL · 1 oracle-proved · 2 struck.**

---

## 116. Closing — the brand surface is now fully traced

**Every brand-reachable area has been audited:** 12 sidebar items · 3 avatar-menu items · Command Bar · notification bell · both money paths (wallet/escrow and subscription) · onboarding · two-way profiles · and now the AI.

### Meera is the best-engineered surface in this audit

It is the only one with a **perfect two-way endpoint/caller match** (11/11 across a service boundary), and its two strongest controls — per-tool scope enforcement and the single-use stream token — are both annotated as **red-team fixes**.

That repeats Part 10's finding exactly: **the guards that survive adversarial review are the ones that hold.** Across this document, every control traceable to a named security review held; several certified by reading code and comments did not.

### And it is where two carried-forward "defects" turned out to be already fixed

Both prior Meera findings were stale. Re-testing them cost minutes and prevented two false reports — the mirror image of the false CRITICAL in Part 5, which was produced by *not* checking the second API layer that this very surface owns.

> ⚠️ **Everything in Part 11 is `believed`.** No Meera turn was executed, no stream observed, no model call made. This establishes that the wiring is correct and the security posture is real — **not that the assistant works.**

---

*Part 11 produced by static code trace across both `influora-api` and `influora-ai`, bidirectional caller census, and `tsc` on branch `fix/brand-audit-remediation`. No live server was probed.*

---
---

# PART 12 — Store Integrations API Audit

> **Scope:** Shopify OAuth + webhooks, WooCommerce connect + webhooks, store integration status/disconnect, and Meta OAuth. Six controllers, 10 endpoints.
> **Method:** Static code trace + bidirectional caller census + security-posture review + `tsc`.
> **Branch:** `fix/brand-audit-remediation` · **Date:** 2026-08-09

---

## 117. Audit Summary

| Layer | Endpoints | Exist | Reachable from the product |
|-------|----------:|:-----:|:--------------------------:|
| Shopify OAuth (`/shopify/oauth`) | 2 | ✅ | ❌ **SI-1** |
| Shopify webhook (`/webhooks/shopify`) | 1 | ✅ | ✅ provider-called |
| WooCommerce connect (`/woocommerce`) | 1 | ✅ | ❌ **SI-1** |
| WooCommerce webhook (`/webhooks/woocommerce`) | 1 | ✅ | ✅ provider-called |
| Integration status/disconnect (`/integrations/store`) | 2 | ✅ | ❌ **SI-1** |
| Meta OAuth (`/meta/oauth`) | 2 | ✅ | n/a — **creator-scoped**, outside brand scope |

| Oracle | Result |
|--------|--------|
| `npx tsc --noEmit` | ✅ **0 errors, exit 0** |

**Verdict: this is the best-secured backend surface in the entire audit — and its user interface is not mounted. All 10 endpoints exist and are correctly built; the single component that would let a brand connect a store is rendered by nothing.**

---

## 118. 🔴 SI-1 — A brand cannot connect a store, and that permanently blocks DIRECT campaigns (HIGH)

| Field | Detail |
|-------|--------|
| **Severity** | 🔴 HIGH — an entire campaign type is uncreatable, and its error message names a remedy the product cannot perform |
| **Where** | `src/components/brand/settings/StoreIntegrationSetup.tsx` |

### The component is orphaned

`StoreIntegrationSetup.tsx` is **git-tracked** (so this is not the deleted-file trap that produced the struck D-10) and is the only surface that calls the store-integration APIs. It is rendered by nothing:

| Check | Result |
|-------|--------|
| `git ls-files` | ✅ file exists |
| `<StoreIntegrationSetup` (JSX usage) | **0** |
| `from '…/StoreIntegrationSetup'` (component import) | **0** |
| Lazy / dynamic import | **0** |
| `useStoreIntegration` consumers | **1 — the orphaned component itself** |

So the hook, the component, and all four FE client methods (`/shopify/oauth/authorize`, `/woocommerce/connect`, `/integrations/store/status`, `/integrations/store/disconnect`) are unreachable together.

### The consequence is not cosmetic

`CampaignService.java:119-125` hard-blocks campaign creation when a store integration is required:

```java
if (IntegrationHealthService.requiresStoreIntegration(campaignType)
        && !integrationHealthService.hasActiveStoreIntegration(workspace.getId())) {
    throw new ApiException("NO_STORE_INTEGRATION",
        "Connect a store (Shopify) before creating a sale/conversion campaign — order"
        + " attribution has nothing to attribute to otherwise", HttpStatus.CONFLICT);
}
```

`IntegrationHealthService:74-76` — `requiresStoreIntegration` returns true for **`CampaignIntentType.DIRECT`**.

**Therefore:** a brand creating a DIRECT (sale/conversion) campaign receives a 409 instructing them to *"Connect a store (Shopify)"* — and **there is no screen in the product where that can be done.** The instruction is unfollowable, so the campaign type is permanently uncreatable.

The backend gate is correct: attribution genuinely needs a store. The defect is that the remedy was built and never mounted.

### ⛔ The fix originally proposed here is FALSE

> This section claimed: *"a routing/mounting change, not new functionality — the component, hook, client and backend are all complete."* **Verification (§123-132) proved otherwise.** Mounting the component would surface a screen that still cannot complete a connection. Two further blockers sit behind it:

**🔴 SI-2 (HIGH) — the Shopify OAuth return leg cannot authenticate.** `ShopifyConnectController:81` requires `@AuthenticationPrincipal`, but `JwtAuthenticationFilter:29-30` reads **`Authorization: Bearer` only — no cookie fallback**. Shopify's callback is a **top-level browser navigation**, which carries no such header. The standard remedy — a frontend redirect page that re-attaches the token, exactly as Meta does at `App.tsx:391` — **does not exist**: there is no Shopify route in `App.tsx`. And `ShopifyProperties:27` defaults `redirectUri` to `""` with no `application.yml` block backing it.

**🟡 SI-3 (MEDIUM) — the form's own placeholder fails validation.** `StoreIntegrationSetup.tsx:56,64` sends the **bare subdomain**; the label `.myshopify.com` at `:221` is static text and never appended. `ShopifyOAuthService:54-55` requires `^[a-z0-9][a-z0-9-]*\.myshopify\.com$`, so the UI's own placeholder (`your-store`, `:218`) returns `INVALID_SHOP_DOMAIN` 400.

**Corrected fix — four changes, not one:**

1. Mount `StoreIntegrationSetup` (`brand-settings.tsx:428-445` currently mounts four tabs — general / notifications / billing / security — with no integrations tab).
2. Add a Shopify OAuth-return route in `App.tsx` that re-attaches the bearer token, mirroring Meta's at `:391`.
3. Append `.myshopify.com` before submitting, or accept a bare subdomain server-side.
4. Provide `influora.shopify` / `influora.woocommerce` config outside `application-dev.yml` (**SI-8**).

---

## 119. ~~✅ The backend security posture — the strongest in this audit~~ → **SUPERLATIVE NOT DESERVED**

> 🚫 **Verification upheld all five controls individually — and rejected the verdict.** Each control below is real and was independently re-verified in executable code (state store single-use via `pending.remove` at `ShopifyOAuthStateStore:46`, bound to user **and** shop at `:53`, 10-minute TTL at `:25`; HMAC-before-parse with fail-closed null/placeholder-secret handling and **constant-time compare** at `ShopifyWebhookSignatureVerifier:46-81`).
>
> **A posture verdict must account for absences, not just present controls.** Four were found: **no role gate anywhere (SI-6)**, an **enumeration oracle** this section certified as absent (**SI-7**), a **broken reconnect** (**SI-4**), and a **silent identity mismatch** (**SI-5**). "Strongest in the audit" is withdrawn; "the controls that exist are correctly built" is what the evidence supports.
>
> ⚠️ **And one citation below was the exact trap this document warns about.** §119 cited `ShopifyIntegration:39-40` as evidence of encryption — those lines are a `@Column` annotation and a field declaration, i.e. **a field name, not encryption**. The real evidence that encryption is *applied on write* is `ShopifyTokenStorage:82` and `WooCommerceIntegrationService:85`. The `[SEC]` annotation is class javadoc at `:15`. **Fifth time this document cited a non-executable line as proof.**

Every control below is real; the citations are corrected where verification found them pointing at declarations rather than behaviour.

| Control | Evidence |
|---------|----------|
| **OAuth CSRF state is bound and single-use** | `ShopifyConnectController:69` issues via `stateStore.issue(userId, validatedShop)`; `:88` `stateStore.consume(state, userId, validatedShop)` rejects state that is *"invalid, expired, already used, or does not match the requested shop"* — bound to **both** user and shop |
| **Shopify webhook HMAC verified before any parsing** | `:122` `signatureVerifier.verify(rawPayload, signature, null)`; `:124` rejects `INVALID_WEBHOOK_SIGNATURE` **401**. Explicitly documented as mirroring `RazorpayWebhookController`'s discipline, including the base64-vs-hex encoding difference |
| **WooCommerce webhook likewise** | Verified on the raw body before dispatch; the javadoc explains why site-resolution-then-signature is still faithful to "verify before parse", and notes unknown-site and bad-signature both surface identically |
| **Secrets encrypted at rest** | `ShopifyIntegration:39-40` `encryptedAccessToken` and `WooCommerceIntegration:42-43` `encryptedWebhookSecret` are both **AES-256-GCM ciphertext**, each carrying a `[SEC: Kabir sign-off gate]` annotation |
| **Disconnect actually takes effect** | `StoreIntegrationStatusController:113` is `@Transactional`, calls `integration.revoke()` + `save()` for both providers, and 404s when there is nothing active |

### Two candidate findings that verification killed

Both are recorded because *not* reporting them is the result of the checks this audit has learned to run:

1. **"Disconnect only sets a boolean — the token stays live."** **Correctly killed — but the reasoning given was false.**

   The stated ground was *"exactly two queries and both filter `RevokedFalse`… there is no unfiltered accessor."* **That is wrong twice.** `ShopifyIntegrationRepository:7` is `extends JpaRepository<ShopifyIntegration, String>`, so `findAll()` and `findById()` are **inherited and unfiltered**; and the interface declares **three** methods, not two.

   The conclusion survives only on evidence this section did not gather: a **consumer census** confirming every caller uses a `*AndRevokedFalse` finder exclusively, with no JPQL, `@Query`, native query or Specification reaching the entity another way. Retained ciphertext with no *actual* reader is defensible. **Not a defect — but this was right by luck, not by argument.**

   *(The BL-2 check — "is the exit actually executed?" — passed. The supporting claim about the repository did not.)*

2. **"Meta OAuth is an unaudited brand surface."** `MetaOAuthController:23` states the flow is *for creators*, and the class depends on `CreatorProfileRepository` and `CreatorMetaOAuthService`. Verified from the imports, not the comment. **Correctly outside brand scope**, and consistent with the `/creator/settings/meta/callback` route.

---

## 120. NOT CHECKED — Part 12 (law 5)

| Not verified | Why |
|-------------|-----|
| Runtime behaviour against a live server | Static trace only; **no store was connected and no webhook received** |
| Whether the AES-256-GCM key management is sound | Ciphertext columns and the sign-off annotation were verified; **the KMS/key-rotation path was not traced** |
| Whether `ShopifyOAuthStateStore` expiry/TTL is correct | `consume()` rejects expired state; the TTL value itself was not read |
| ~~Whether webhook replay is prevented~~ | ✅ **CLOSED — it IS prevented.** `IdempotencyService.executeOnce` at `ShopifyWebhookController:161-184` and `WooCommerceWebhookController:198-220`. This row over-claimed ignorance |
| ~~SSRF via the WooCommerce site URL~~ | ✅ **CLOSED — genuinely absent.** No outbound HTTP client exists anywhere in the Woo path |
| ~~Rate limiting on these endpoints~~ | ✅ **CLOSED — present on all ten.** `AuthRateLimitFilter:280-302` |
| Whether Shopify's app-uninstall webhook revokes the local row | Only the in-product disconnect path was traced |
| WooCommerce `POST /connect` credential validation | Endpoint and encryption verified; input validation not traced |
| Whether `StoreIntegrationSetup` renders correctly once mounted | It has never been rendered — **untested by construction** |
| Order-attribution correctness downstream of the webhooks | Out of scope |

---

## 121. Register additions

> Corrected 1 → 8 findings by verification (§123-132).

| ID | Surface | Severity | Evidence | Summary |
|----|---------|----------|----------|---------|
| **SI-1** | Store integrations | 🔴 HIGH | believed | `StoreIntegrationSetup.tsx` is rendered by nothing — a brand cannot connect a store, so **DIRECT campaigns are permanently uncreatable** and their 409 names an unreachable remedy |
| **SI-2** | Shopify OAuth | 🔴 HIGH | believed | **The OAuth return leg cannot authenticate** — callback requires `@AuthenticationPrincipal` but the filter reads `Bearer` only; Shopify returns via top-level navigation, and no redirect route exists (Meta has one; Shopify does not). `redirectUri` defaults to `""` |
| **SI-3** | Shopify connect UI | 🟡 MEDIUM | believed | Form sends the bare subdomain; backend requires `*.myshopify.com` — **the UI's own placeholder 400s** |
| **SI-4** | Both providers | 🟡 MEDIUM | believed | **Reconnect after disconnect violates the unique key** — `ShopifyTokenStorage:85-99` vs `V27__shopify_integrations.sql:29`; same for Woo at `V29:32` |
| **SI-5** | Both providers | 🟡 MEDIUM | believed | **Silent identity mismatch** — `ShopifyIntegration:104-107` / `WooCommerceIntegration:92-94` never update `shopDomain`/`siteUrl`; `WooCommerceConnectController:79` echoes the new URL while the DB keeps the old |
| **SI-6** | Store integrations | 🟡 MEDIUM | believed | **No role gate** — `BrandContextService:43-64` has no `MemberRole` check despite `requireRole` existing at `:77-84`. Any member can connect or disconnect a store |
| **SI-7** | Woo webhook | 🔵 LOW | believed | **Enumeration oracle** on a `permitAll` endpoint — 404 at `:170-175` vs 401 at `:178-181` distinguishes connected from unconnected sites. §119 certified this as absent |
| **SI-8** | Config | 🔵 LOW | believed | No `influora.shopify` / `influora.woocommerce` block outside `application-dev.yml:29-32` |

**Running total: 62 live defects · 14 HIGH · 0 CRITICAL · 1 oracle-proved · 2 struck.**

---

## 122. Closing — the sharpest instance of this audit's central pattern

Across twelve parts, the recurring finding has been **built-but-unreachable**. Store integrations is its purest form — and, after verification, its most complete:

- The backend's **individual controls are correctly built** — bound single-use OAuth state, HMAC-before-parse with constant-time compare on both webhook paths, encryption genuinely applied on write, idempotent webhook handling, rate limits, a disconnect that genuinely revokes.
- **Nothing renders the connect component**, so none of it is reachable.
- A *correct* backend gate turns that into a hard product block: DIRECT campaigns cannot be created, with an error naming a remedy the product does not provide.

> 🚫 **This section originally ended: *"Every layer is right except the one line that mounts it."* That is FALSE, and it is the same mistake as §90's billing praise — certifying a path without tracing it end to end.**
>
> Mounting the component would reveal **three more blockers**: the OAuth return leg cannot authenticate at all (**SI-2**), the form's own placeholder fails server validation (**SI-3**), and the production config block does not exist (**SI-8**). Reconnect after disconnect then violates a unique key (**SI-4**).
>
> **The honest reading:** this feature was never wired end-to-end, and the missing mount is the *first* thing you would hit, not the only one. A surface that has never been rendered has never been exercised — so "complete except for mounting" was an assumption, not a finding.

**The lesson this part adds:** an orphaned component is not evidence that everything behind it works. It is evidence that **nothing behind it has ever been tested.**

> ⚠️ **Everything in Part 12 is `believed`.** No store was connected, no OAuth round-trip completed, no webhook received. The security posture is verified **as written**, not as executed.

---

*Part 12 produced by static code trace, bidirectional caller census, security-posture review and `tsc` on branch `fix/brand-audit-remediation`. No live server was probed.*


---

# PART 12 — RED-TEAM VERIFICATION (Kabir)

> **Scope:** §117–122 only. Parts 1–11 verified separately, untouched here.
> **Method:** every claim re-derived from git-tracked executable code. `git ls-files`-scoped (no worktree ghosts). All five false-zero-caller patterns run against the FE census. Every javadoc-sourced claim in §119 re-derived from the statements it describes.
> **Branch:** `fix/brand-audit-remediation` · **Date:** 2026-08-09

---

## 123. Verdict — **NEEDS_FIX**

| Question | Ruling |
|---|---|
| **SI-1 — is the component really orphaned?** | ✅ **REAL BUG**, and **HIGH is justified** — if anything understated |
| **§119 — "strongest security posture in this audit"** | ❌ **NOT DESERVED as written.** All 5 named controls verified real; the superlative is not, and one of the five rests on a javadoc that contradicts its own code |
| **Killed finding 1 (disconnect/boolean)** | ✅ Correctly killed — ❌ **on materially false reasoning** |
| **Killed finding 2 (Meta = creator-scoped)** | ✅ Correctly killed, confirmed in executable code |
| **`npx tsc --noEmit`** | ✅ **CONFIRMED — exit 0, zero diagnostics.** Re-run independently |

**Seven defects Part 12 missed, two of them HIGH.** Part 12's central claim is right and its security review is competent, but it stopped at the connect *button* and never traced the connect *round-trip* — where two further hard blocks sit. And its own headline fix instruction is wrong.

---

## 124. SI-1 — **REAL BUG. HIGH justified.** But the stated fix does not fix it

### The orphan claim survives every check

| Check | Method | Result |
|---|---|---|
| Git-tracked, not a worktree ghost | `git ls-files` (2630 files) | ✅ `src/components/brand/settings/StoreIntegrationSetup.tsx` tracked |
| JSX usage `<StoreIntegrationSetup` | `git grep` over tracked files | **0** |
| Static import | `git grep "StoreIntegrationSetup" -- src/` | **0** (only its own `export function` :42 and `export default` :291) |
| Lazy / dynamic import | same census | **0** |
| Barrel / index re-export | `src/components/brand/settings/` contains **only this file** | **0** |
| Route config / string-keyed registry | `src/App.tsx` is the only route file; no entry | **0** |
| **Pattern 2 — second API layer** | `src/lib/meera-api.ts`, `src/hooks/**`, raw `fetch` | **0** |
| **Pattern 5 — caller uses the method name, not the URL** | `git grep "storeIntegrations\|authorizeShopify\|connectWooCommerce"` | only the orphan + `src/lib/api.ts` + `src/lib/__tests__/api-contract.test.ts:34` (a shape assertion, not a caller) |

**No alternative connect path exists.** `brand-settings.tsx:428-445` mounts exactly four tabs — `general`, `notifications`, `billing`, `security`. No integrations tab. No admin screen, no deep link, and the Meera/AI service (`influora-ai/`) has no store-connect tool — its only Shopify references are HTML-scraping fixtures in `app/prompt/structured_extract.py`.

### The consequence claim is exact

- `CampaignService.java:119-125` — **verbatim match** to §118's quoted block, including the `NO_STORE_INTEGRATION` / 409 CONFLICT / "Connect a store (Shopify)" string.
- `IntegrationHealthService.java:74-76` — `requiresStoreIntegration` returns `campaignType == CampaignIntentType.DIRECT`. **Exact.**

### 🔴 But §118's fix instruction is **FALSE**

> §118: *"This is a routing/mounting change, not new functionality — the component, hook, client and backend are all complete."*

Mounting the component does **not** make a store connectable. Two independent blocks sit downstream, both missed:

---

## 125. 🔴 SI-2 — The Shopify OAuth **callback leg has no reachable endpoint** (HIGH)

`ShopifyConnectController.java:79-84`:

```java
@GetMapping("/callback")
public ApiResponse<ShopifyCallbackResponse> callback(
        @AuthenticationPrincipal AuthPrincipal principal,
        @RequestParam String code, @RequestParam String state, @RequestParam String shop) {
    Workspace workspace = brandContextService.requireBrandWorkspace(principal);
```

The callback **requires an authenticated brand principal**. `JwtAuthenticationFilter.java:29-30` authenticates **from the `Authorization: Bearer` header only** — there is no cookie fallback. Shopify's return leg is a **top-level browser navigation**, which carries no `Authorization` header. So:

- If `redirect_uri` points at `/shopify/oauth/callback`, the browser lands there unauthenticated → `requireBrandWorkspace` → 401/403. **The handshake can never complete.**
- If `redirect_uri` points at a frontend page that re-calls the endpoint with the code — the pattern the class javadoc :27-30 describes, and the pattern Meta actually uses (`src/App.tsx:391` → `/creator/settings/meta/callback`) — **that page does not exist.** `git grep -i shopify -- src/` returns the orphaned component and `src/lib/api.ts` only. There is no Shopify callback route in `App.tsx`.
- `ShopifyProperties.java:27` — `redirectUri` defaults to `""`, and there is **no `influora.shopify` block in `application.yml`** and no `SHOPIFY_*` entry in any `env.example`.

**Severity HIGH.** Even with SI-1 fixed, Shopify remains unconnectable. This is new functionality (a frontend callback route), not a mount.

---

## 126. 🟡 SI-3 — The component sends a bare subdomain; the backend requires an FQDN (MEDIUM)

`StoreIntegrationSetup.tsx:56,64`:

```tsx
const shop = shopDomain.trim();
const { authorizationUrl } = await api.storeIntegrations.authorizeShopify(shop);
```

The input's placeholder is `your-store` (`:218`) with a literal, non-editable `.myshopify.com` rendered **beside** it (`:221`) — the suffix is never appended. The mock in `src/lib/api.ts:3636` confirms the FE's contract is subdomain-only: it builds `https://${shop}.myshopify.com/...` itself.

The backend requires the full host — `ShopifyOAuthService.java:54-55`:

```java
private static final Pattern SHOP_DOMAIN_PATTERN =
        Pattern.compile("^[a-z0-9][a-z0-9-]*\\.myshopify\\.com$");
```

`validateShopDomain` (`:80-88`) throws `INVALID_SHOP_DOMAIN` **400** on `your-store`. A user following the UI's own placeholder gets a hard 400 on click. The `tsc` oracle cannot see this — both sides are `string`.

---

## 127. §119 — the five controls, attacked individually

| Control | §119's evidence | My ruling |
|---|---|---|
| **OAuth state bound + single-use** | `:69` issue, `:88` consume | ✅ **CONFIRMED, stronger than claimed.** `ShopifyOAuthStateStore.java:46` uses `pending.remove(state)` — genuinely single-use, not a check-then-delete race. `:53` requires `userId.equals()` **AND** `shopDomain.equalsIgnoreCase()`. TTL is real: `:25` `Duration.ofMinutes(10)`, enforced `:50`. §120 listed the TTL value as unread — it is 10 minutes and it is correct |
| **Shopify webhook HMAC before parse** | `:122` verify, `:124` 401 | ✅ **CONFIRMED.** `ShopifyWebhookController.java:122` is the first statement in `receive`; `ShopifyOrderWebhookPayload.parse` is not reached until `:153`. **No bypass:** `ShopifyWebhookSignatureVerifier.java:46-48` rejects a blank header, `:53-56` fails closed on a null/blank/`REPLACE_WITH_` secret, `:73-81` is a genuine constant-time compare |
| **WooCommerce likewise** | javadoc's site-resolve-then-verify explanation | ⚠️ **Ordering CONFIRMED in code, but the audit repeated a javadoc claim that its own code contradicts — see SI-7.** The ordering itself is sound: `WooCommerceWebhookController.java:166-175` is a header normalize + indexed lookup, `:177-181` verifies, and `:190` `parse()` is the first parser touch — verify-before-parse holds |
| **Secrets AES-256-GCM at rest** | `ShopifyIntegration:39-40`, `WooCommerceIntegration:42-43` | ✅ **True — but the cited lines do not prove it.** `:39-40` is a `@Column(...)` + field declaration, and the `[SEC: Kabir sign-off gate]` text is class javadoc at `:15`, not an annotation on those lines. This is the "field name ≠ encryption" trap. **Encryption-on-write independently confirmed elsewhere:** `ShopifyTokenStorage.java:82` calls `encrypt(accessToken)` before any `save()`, and `:147-163` is real `AES/GCM/NoPadding`, 12-byte `SecureRandom` IV, 128-bit tag, key length-checked to 32 bytes at `:62-66`. Same for `WooCommerceIntegrationService.java:85` / `:149-165` / `:58-68` |
| **Disconnect takes effect** | `StoreIntegrationStatusController:113` `@Transactional` | ✅ Substance correct — ⚠️ **off-by-one:** `:113` is `@DeleteMapping("/disconnect")`; `@Transactional` is `:114`. `revoke()` + `save()` at `:127-128` / `:140-141`, `INTEGRATION_NOT_FOUND` 404 at `:122-125` / `:135-138` — all correct |

### Ruling on the superlative: **NOT DESERVED**

The five controls are real and I would not downgrade any of them. But §119 is a **posture verdict on an auth surface**, and a posture verdict must account for what is absent. This surface has **no role gate on any of its six authenticated endpoints** (SI-6), an **unauthenticated enumeration oracle** the section explicitly certified as absent (SI-7), a **reconnect path that 500s** (SI-4), and a **silent store-identity mismatch** (SI-5). "Correctly built" is the most dangerous sentence in an audit; here it was written after checking the five controls that exist and none of the ones that don't.

**Rewrite as: "five specific controls verified correct" — not "the strongest security posture in this audit."**

---

## 128. The two killed findings — re-tested

### 1. "Disconnect only sets a boolean" — ✅ **correctly killed**, ❌ **on false reasoning**

§119's stated ground: *"`ShopifyIntegrationRepository` exposes **exactly two queries and both filter `RevokedFalse`** (`:10`, `:21`) — there is no unfiltered accessor."*

Both halves are wrong:
- It exposes **three** declared methods — `:10`, `:21`, and `:23` `existsByShopDomainAndRevokedFalse` (harmless, also filtered, but the count is wrong).
- `:7` — `extends JpaRepository<ShopifyIntegration, String>`. **`findAll()`, `findById()`, `findAllById()`, `getReferenceById()` are all inherited and none filter `revoked`.** "There is no unfiltered accessor" is false on its face.

**The conclusion nevertheless holds**, on the ground the audit should have used — an exhaustive consumer census, which I ran. Between them the two repositories have exactly five consumers (`ShopifyTokenStorage`, `WooCommerceIntegrationService`, `IntegrationHealthService`, `ShopifyWebhookController`, `WooCommerceWebhookController`, `StoreIntegrationStatusController`), and **every one calls only a `*AndRevokedFalse` finder**. No JPQL, no `@Query`, no native query, no `Specification`, no `findAll`. Revoked credentials are unreachable. **Not a defect — for a different reason than the one recorded.**

### 2. "Meta OAuth is a brand surface" — ✅ **correctly killed, confirmed**

`MetaOAuthController.java:55` — `requireCreator(principal);` — **executable code, not a comment**, first statement in `/authorize`. Constructor `:41-49` takes `CreatorProfileRepository` and `CreatorMetaOAuthService`. Consistent with `src/App.tsx:391`'s `/creator/settings/meta/callback`. **Correctly outside brand scope.**

---

## 129. What Part 12 missed

### 🟡 SI-4 — Reconnecting after a disconnect violates a UNIQUE constraint (MEDIUM)

`revoke()` retains the row (`ShopifyIntegration.java:111-112`). `ShopifyTokenStorage.java:85` then looks up `findByWorkspaceIdAndRevokedFalse`, which **excludes the revoked row** — so `:90-99` takes the fresh-insert branch, inserting a second row with the same `shop_domain`. `V27__shopify_integrations.sql:29` declares `UNIQUE KEY uq_shopify_shop_domain (shop_domain)` with no revoked-aware partial index. **Reconnecting the same store after disconnecting it throws a constraint violation.** Identical shape for WooCommerce: `WooCommerceIntegrationService.java:87-101` vs `V29__woocommerce_integrations.sql:32` `uq_woocommerce_site_url`.

Every unit test in `ShopifyTokenStorageTest` / `WooCommerceIntegrationServiceTest` mocks the repository, so the constraint is never exercised.

### 🟡 SI-5 — Connecting a *different* store silently keeps the old store's identifier (MEDIUM)

`ShopifyIntegration.rotateToken` (`:104-107`) sets `encryptedAccessToken`, `grantedScopesJson`, `revoked=false` — **it never sets `shopDomain`**. `WooCommerceIntegration.rotateSecret` (`:92-94`) never sets `siteUrl`.

So a brand with store A connected who connects store B gets: store B's credential stored **under store A's domain**, HTTP 200, and — for WooCommerce — `WooCommerceConnectController.java:79` returns `new WooCommerceConnectResponse(true, normalizedSiteUrl)` echoing **B's** URL while the database still holds **A's**. Every subsequent webhook from B is rejected `SITE_NOT_CONNECTED`, and `/integrations/store/status` reports A. The success response is a lie.

### 🟡 SI-6 — No role gate on any of the brand-facing endpoints (MEDIUM)

`BrandContextService.java:43-64` `requireBrandWorkspace` checks `UserType.BRAND` and resolves a workspace. **It never checks `MemberRole`.** The service has a `requireRole` (`:77-84`) used elsewhere in this codebase (e.g. `WalletController.java:86`), and Part 10 raised exactly this gap as BL-4. It is not applied to `/shopify/oauth/authorize`, `/shopify/oauth/callback`, `/woocommerce/connect`, `/integrations/store/status`, or `/integrations/store/disconnect`.

**Any workspace member — of any role — can disconnect the company's store**, silently killing all order attribution and blocking DIRECT campaign creation workspace-wide. The same actor can connect an arbitrary store of their own.

### 🔵 SI-7 — The WooCommerce webhook is a site-enumeration oracle, and §119 certified the opposite (LOW)

§119: *"notes unknown-site and bad-signature both surface identically."* **They do not.**

- Unknown site → `WooCommerceWebhookController.java:170-175` → `SITE_NOT_CONNECTED`, **404**
- Known site, bad signature → `:178-181` → `INVALID_WEBHOOK_SIGNATURE`, **401**

Distinct status codes and distinct error codes, on an endpoint that is `permitAll` (`SecurityConfig.java:120-121`). An unauthenticated caller can determine whether any given site URL is connected to Influora, at 30 req/window (`AuthRateLimitFilter.java:127`). The javadoc the audit paraphrased actually contradicts itself in its own parenthetical (`:79-82`, "…can independently reject with 401 — see the two distinct failure branches below"). **This is the exact failure the brief warned about: a javadoc's self-assessment accepted in place of the code.**

### 🔵 SI-8 — Neither integration has a production config block (LOW, deploy-blocking)

`ShopifyTokenStorage.java:57-67` and `WooCommerceIntegrationService.java:58-68` both **throw `IllegalStateException` in their constructors** if the encryption key is blank — correct fail-closed design, but it means the Spring context will not start without a key. There is **no `influora.shopify` or `influora.woocommerce` block in `application.yml`** (keys exist only in `application-dev.yml:29-32`) and **no `SHOPIFY_*`/`WOOCOMMERCE_*` entry in any `env.example`**. Booting a non-`dev` profile requires undocumented relaxed-binding env vars (`INFLUORA_SHOPIFY_TOKEN_ENCRYPTION_KEY`, `INFLUORA_WOOCOMMERCE_TOKEN_ENCRYPTION_KEY`). Nothing in the repo tells a deployer this.

---

## 130. Corrections to §120's NOT-CHECKED list

Three of its eight rows are checkable from the code and are now checked:

| §120 row | Correction |
|---|---|
| *"Whether webhook replay is prevented — not verified"* | ✅ **It is prevented, on both paths.** `ShopifyWebhookController.java:161-184` and `WooCommerceWebhookController.java:198-220` both wrap the handler in `IdempotencyService.executeOnce`, keyed by a SHA-256 of `identifier|topic|orderId`, with the same key passed through to `RedemptionService#redeem`'s own dedup. Two independent layers agreeing on one key. **Part 12 under-credited its own subject** |
| *"`ShopifyOAuthStateStore` expiry/TTL not read"* | ✅ 10 minutes (`:25`), enforced at `:50` |
| *"WooCommerce `POST /connect` credential validation not traced"* | ✅ Traced. `WooCommerceSiteUrl.normalize` (`:37-55`) requires an absolute `http(s)` URI and canonicalizes scheme+host+port lower-cased; `WooCommerceConnectController.java:69-75` rejects a blank secret with `WEBHOOK_SECRET_REQUIRED` 400. **SSRF: genuinely absent** — this integration makes no outbound call to the submitted URL anywhere, verified across `WooCommerceIntegrationService` (no HTTP client injected at all). Shopify's own SSRF surface is guarded by `SHOP_DOMAIN_PATTERN` before every interpolation (`ShopifyOAuthService.java:92`, `:111`) |

**Also verified clean and worth recording:** rate limits exist on all ten endpoints (`AuthRateLimitFilter.java:280-302` — `meta-oauth` bucket for the connect endpoints, `tracking` for the webhooks). **No secrets in logs** — `ShopifyOAuthService.java:125-128` logs status codes only; audit-log details carry `siteUrl`/`shopDomain`/`scopeCount` only (`ShopifyTokenStorage.java:110-112`, `WooCommerceIntegrationService.java:112`). **Cross-tenant coupon redemption is closed on both webhooks** — both call the workspace-scoped 6-arg `RedemptionService#redeem` (`ShopifyWebhookController.java:216`, `WooCommerceWebhookController.java:246`).

---

## 131. Corrected Part 12 register

| ID | Surface | Severity | Evidence | Summary |
|----|---------|----------|----------|---------|
| **SI-1** | Store integrations FE | 🔴 HIGH | verified | `StoreIntegrationSetup.tsx` rendered by nothing; no alternate connect path exists; DIRECT campaigns hard-blocked by `CampaignService.java:119-125`. **Confirmed — but §118's "just mount it" fix is wrong** |
| **SI-2** | Shopify OAuth | 🔴 HIGH | verified | Callback requires a Bearer principal (`ShopifyConnectController.java:81`, `JwtAuthenticationFilter.java:29`); no frontend callback route exists; `redirectUri` unconfigured. Shopify is unconnectable even after SI-1 is fixed |
| **SI-3** | Store integrations FE | 🟡 MEDIUM | verified | FE sends bare subdomain (`StoreIntegrationSetup.tsx:56,64`), BE requires FQDN (`ShopifyOAuthService.java:54`) → `INVALID_SHOP_DOMAIN` 400 on the UI's own placeholder |
| **SI-4** | Both providers | 🟡 MEDIUM | verified | Disconnect→reconnect inserts a duplicate row against `uq_shopify_shop_domain` / `uq_woocommerce_site_url` → constraint violation |
| **SI-5** | Both providers | 🟡 MEDIUM | verified | `rotateToken`/`rotateSecret` never update `shopDomain`/`siteUrl`; connecting a different store stores the new credential under the old identifier and returns a success response that contradicts the DB |
| **SI-6** | 5 brand endpoints | 🟡 MEDIUM | verified | `requireBrandWorkspace` applies no `MemberRole` gate; any member can connect or disconnect the workspace's store |
| **SI-7** | Woo webhook | 🔵 LOW | verified | 404 `SITE_NOT_CONNECTED` vs 401 `INVALID_WEBHOOK_SIGNATURE` on a `permitAll` endpoint = unauthenticated site-enumeration oracle. §119 certified the opposite from a self-contradicting javadoc |
| **SI-8** | Deploy config | 🔵 LOW | verified | No `influora.shopify`/`influora.woocommerce` block in `application.yml`, no `env.example` entry; both storage beans fail-closed at construction → non-`dev` boot blocked |

**Part 12's register was 1 defect. Corrected: 8 — 2 HIGH, 4 MEDIUM, 2 LOW.**

---

## 132. Closing — the audit stopped at the button

§122 calls store integrations "the purest form" of built-but-unreachable. It is worse than that, and the section's own framing is what hid it: having found that nothing renders the component, Part 12 concluded everything *behind* the component was therefore complete and only needed mounting. It never walked the round-trip. Behind that unmounted button sit a callback with no landing page, a shop-domain contract the two sides disagree on, a reconnect that violates a unique constraint, and a re-connect that silently doesn't re-point.

And §119's superlative was earned by counting the controls that are present. Five are, and all five hold up under attack. But six endpoints carry no role check, a public webhook leaks which stores are connected, and the section's own killed finding was killed on a claim — "there is no unfiltered accessor" — that a single glance at `extends JpaRepository` refutes. The right conclusion survives; the reasoning that produced it does not.

> ⚠️ **Everything in this verification is static.** No server was run, no OAuth round-trip attempted, no webhook delivered. `npx tsc --noEmit` (exit 0) is the only executed oracle. SI-2, SI-4 and SI-5 are derived from code + schema and are **believed**, not runtime-proved — though SI-4 and SI-5 are each provable with one integration test against a real database.

---

*Part 12 verification by Kabir — red-team, OWASP. Static trace of git-tracked sources only; `tsc` re-run independently. Branch `fix/brand-audit-remediation`, 2026-08-09.*

---
---

# PART 13 — BLIND-SPOT SWEEP: Two-Party Consent & Turn Integrity

> **Why this part exists.** Parts 1-12 traced ~120 endpoints and found 62 defects. **Not one of them was a consent defect** — because the audit never asked the question. Every guard traced was checked for **role** ("are you a brand?") and **status** ("is the deal negotiable?"). None was checked for **turn** ("is it your move?") or **counterparty identity** ("did the other side actually act?").
>
> A user question about the deal-room UI — *"if the brand clicks Counter on its own offer, does it error?"* — exposed the gap. It does not error. Nothing checks.
>
> **Method:** enumerate every state transition in the brand surface that requires two parties, and test each for a turn/identity guard rather than a role/status guard.

---

## 133. Sweep Results

| # | Transition | Guard found | Turn / identity enforced? |
|---|-----------|-------------|:--------------------------:|
| 1 | **Contract signature** | `role` string **from the client** | 🔴 **NO — CS-1** |
| 2 | **Deal accept** | status allowlist | 🔴 **NO — CS-2** |
| 3 | **Deal counter** | same allowlist (`canCounter()` → `canAccept()`) | 🔴 **NO — CS-2** |
| 4 | Deal reject | same allowlist | ⚠️ same class, lower impact |
| 5 | Shipment — mark shipped | `requireBrandCollaboration` | ✅ **YES** |
| 6 | Shipment — confirm receipt | `requireCreatorCollaboration` | ✅ **YES** |
| 7 | Deliverable approve / revise | `requireBrandWorkspace` + status | ✅ cross-party by construction |
| 8 | Review create / flag | `requireReviewForParty`, principal-scoped | ✅ **YES** |

**3 of 8 fail. And the codebase already contains the fix** — see §136.

---

## 134. ~~🔴 CS-1 — CRITICAL~~ → 🚫 **FALSE ALARM. STRUCK.** (survivor: CS-1′ MEDIUM)

> 🚫 **The central claim — "there is no creator-authenticated signing path at all" — is FALSE.** Verification (§140-149) refuted it five ways, every one in executable code:
>
> | # | Refutation | Evidence |
> |---|---|---|
> | 1 | **`recordSignatureForCreator` exists** | `ContractService:576-578` — `creatorContext.requireCreator(principal)` → `requireContractForCreator(...)` → `findByIdAndCreatorId` (`:916-920`). **The exact principal-scoped pattern §136 claimed contracts lacked** |
> | 2 | It is FE-wired and mounted | `creator-deal-contract-tab.tsx:124`, `creator-contract-panel.tsx:82`, rendered at `creator-chat.tsx:2387/2575` |
> | 3 | `role` is **never sent in the body** | `api.ts:2157-2162` uses it to select the JWT (`:222-223, 250-251`). `body.role()` is always null → `ContractController:102-104` defaults to `BRAND`. **Server-derived on 100% of real traffic** |
> | 4 | "`requireBrandWorkspace` runs first" is false on the line | `ContractController:83` is the creator branch; `:86` is `requireBrandWorkspace` |
> | 5 | "Load-bearing" is false | `recordSignatureForCreator` reaches fully-executed through the same `doRecordSignature`. Only two direct unit tests depend on the `role` param |
>
> **How this happened:** all three quotes this section leaned on came from **one stale javadoc** (`ContractService:483-493`). I never opened the method 90 lines below it. In a document that had already warned itself six times that comments are not evidence, the central claim of a CRITICAL rested entirely on a comment.
>
> **The prior LOW-4 rating was defensible.** What survives is narrower:

### 🟡 CS-1′ — The legacy `role=CREATOR` relay branch should be deleted (MEDIUM)

The branch still exists and still accepts a client-supplied role from an OWNER/ADMIN/MANAGER. No UI reaches it, and `recordSignatureForCreator` now covers the real path — so it is **removable at zero functional cost**, which this section's "load-bearing" framing wrongly ruled out.

### 🟡 CS-3 — Signatures carry no audit trail (MEDIUM) — *the finding that was actually there*

§137 listed "whether an audit log records the acting principal per signature" as **NOT CHECKED**, and noted it would materially affect impact. Verification checked it:

- `Contract.java:136-143` writes **only `Instant.now()`** — no acting principal, no name, no IP.
- `record ContractSignRequest(String role)` (`MoneyDtos.java:231`) declares **only** `role` — **the signer's typed name is silently dropped**, and `.agreedAt()` has zero call sites.

**A relayed signature is byte-identical to a genuine one.** There is no forensic way to tell them apart after the fact. That is the real defect on this surface — and it sat in the "not checked" list while a false CRITICAL was written above it.

---

## 134a. ~~Original CS-1 text (retained for the record)~~

| Field | Detail |
|-------|--------|
| **Severity** | 🔴 **CRITICAL** — legal/evidentiary integrity; every "fully executed" contract in the system was signed entirely by one party |
| **Where** | `ContractService.sign` (party resolution at `:532-545`) |
| **Status in source** | **Known and documented** — previously rated LOW-4 |

`ContractService.sign` resolves the signing party from a **client-supplied `role` string**:

```java
if ("BRAND".equalsIgnoreCase(role))        { isBrand = true;  }
else if ("CREATOR".equalsIgnoreCase(role)) { isBrand = false;
    // [SEC: Kabir, E2 LOW-4] Recording a signature ON BEHALF OF the creator is the one
    // residual-forgery-risk path … restrict it to OWNER/ADMIN/MANAGER
    brandContext.requireRole(member, MemberRole.OWNER, MemberRole.ADMIN, MemberRole.MANAGER); }
```

The method's own javadoc states the situation plainly:

> *"…record the CREATOR's signature on a legal document, a **signature-attribution forgery**"*
>
> *"**every principal that can reach this method today is a BRAND principal, full stop — there is no creator-authenticated call path into `/contracts/{id}/sign` at all**"*
>
> *"`role=CREATOR` is the ONLY existing mechanism this product has for a contract to ever reach [fully executed]"*

### What this means in practice

- `ContractController.sign` runs `requireBrandWorkspace` first, which throws unless the principal is a **BRAND**.
- `WorkspaceMember`/`MemberRole` is a brand-side concept; creators are never workspace members.
- **Therefore no creator can ever sign a contract.** The brand signs its own leg as `role=BRAND`, then signs the creator's leg as `role=CREATOR`.

**Every fully-executed contract this platform has produced was executed by the brand alone.**

### Why the severity is raised from LOW-4 to CRITICAL

The prior review scoped this as a *permission* question — "who inside the workspace may record the creator's signature?" — and mitigated it correctly at that level (restricting the CREATOR leg to OWNER/ADMIN/MANAGER).

That framing understates it. The defect is not that the wrong workspace member can forge a signature. It is that **the counterparty has no way to sign, so the signature is always attributed rather than given.** A two-party e-signature that only one party can ever perform is not a two-party signature.

### ⚠️ It is load-bearing — this cannot simply be deleted

The javadoc records that rejecting `role=CREATOR` was tried and reverted, because it is the only path by which a contract reaches fully-executed state. Two tests depend on it. **Removing the forgery path without building creator-side signing would break contract completion entirely.**

**Fix (a feature, not a patch):** build a creator-authenticated signing route — `POST /creator/contracts/{id}/sign`, principal-scoped via `CreatorContextService`, mirroring how `requireCreatorCollaboration` already works for shipments (§136). Then delete the client-supplied `role`.

---

## 135. 🔴 CS-2 → **SPLIT: the accept leg is a FALSE ALARM; the counter leg is real but LOW**

> 🚫 **`doAccept` DOES enforce turn.** `DealService:632-646` — the delegate this section never opened:
>
> ```java
> if (lastOffer.isPresent() && lastOffer.get().getSenderType() == actingAs) {
>     throw new ApiException("CANNOT_ACCEPT_OWN_OFFER", …, HttpStatus.CONFLICT);
> }
> ```
>
> **Both citations below are real lines that are not where the decision is made:** `canAccept():185` is status-only and irrelevant to acceptance authority; `accept():255` is the **idempotency wrapper**, not the logic. The guard lives in the delegate.
>
> **What survives:** `doCounter:721` genuinely has no turn guard — but a counter **closes nothing** (`:734` → back to `IN_NEGOTIATION`; the new offer must still be accepted by the other side). **Re-rated HIGH → LOW.**

### 🟡 CS-2b — The turn guard is inert on invited deals (MEDIUM) — *new*

The guard is conditioned on `lastOffer.isPresent()`. **The invite path persists no `DealMessage` at all:**

- `Collaboration.invite` (`Collaboration.java:64-78`) writes no message row
- `CreatorDiscoveryService:453` — zero `DealMessage` references
- `ConfirmLaunchExecutor:472` (Meera's launch path) — identical

`INVITED` is on the `canAccept` allowlist, so with no last offer the guard never fires: **a brand can self-accept an invited deal straight into `TERMS_AGREED`, with `agreedRate` null.**

This is the real turn defect — narrower than claimed, and reached by a different door than the one this section described.

---

## 135a. ~~Original CS-2 text (retained for the record)~~

| Field | Detail |
|-------|--------|
| **Severity** | 🔴 HIGH — one party can close a two-party negotiation unilaterally |
| **Where** | `Collaboration.canAccept():185-190`, `canCounter():192-194`; `DealService.accept():255`, `doCounter():386` |

Both gates are a pure **status allowlist**:

```java
public boolean canAccept() {
    return status == INVITED || status == APPLIED
        || status == SHORTLISTED || status == IN_NEGOTIATION;
}
public boolean canCounter() { return canAccept(); }
```

**Nothing records or checks who made the pending offer.** `DealService.accept()` verifies role and ownership (`:256-257`) — never *whose turn it is*. The only error that exists, `DEAL_NOT_NEGOTIABLE` (`:386-390`), fires on state, never on turn.

So after a brand sends a proposal the deal sits in `IN_NEGOTIATION`, which is on the allowlist:

| Brand action on its own pending offer | Result |
|---|---|
| **Counter** | ✅ succeeds — counters itself, overwriting the pending terms |
| **Accept** | ✅ succeeds — moves to `TERMS_AGREED` with **no creator involvement** |

The frontend gates on `rawStatus` (`brand-chat.tsx:157,226`) — the same status-only logic — so the UI does not stop it either.

---

## 136. The chain — a binding contract with zero creator actions

CS-2 and CS-1 compose. Every step below is a brand-authenticated call that the code permits today:

> ⚠️ **The chain holds — but every step as written here is wrong.** Corrected by verification:

| Step as written | Verdict |
|---|---|
| 1. `POST /deals` (priced offer) | ❌ **self-defeating** — it persists a `senderType=brand` proposal that **arms** the `CANNOT_ACCEPT_OWN_OFFER` guard and blocks step 2 |
| 2. Self-accept | ✅ possible, but **only via the invite path** (CS-2b), not this one |
| 3. Generate contract | ✅ — and **CS-4 (LOW, new):** no `TERMS_AGREED` precondition exists. `CollaborationStatus` appears in `ContractService` at exactly `:205` and `:640`, both `CANCELLED` checks. **Step 2 is not even required** |
| 4-5. Sign both legs | ⚠️ only via the legacy relay branch (**CS-1′**) — hand-crafted request, OWNER/ADMIN/MANAGER only, **no UI reaches it** |

**Corrected chain:** `invite` → self-accept (CS-2b) → generate contract (CS-4 — no precondition) → sign BRAND → sign `role=CREATOR` (CS-1′, hand-crafted).

**It still ends in a fully-executed contract with no creator action — but it requires a hand-crafted request from an elevated member, not a sequence of ordinary UI clicks.** That is a materially smaller claim than the one this section originally made.

### The fix already exists in this codebase, one file away

Shipment enforces exactly what contracts and deals do not:

```java
// ShipmentService:153  — brand-only
Collaboration collaboration = requireBrandCollaboration(principal, dealId);

// ShipmentService:209  — creator-only
Collaboration collaboration = requireCreatorCollaboration(principal, dealId);
```

`requireCreatorCollaboration` resolves the party **from the authenticated principal**, not from a client-supplied string. It is the precise helper CS-1 needs. The shipment state machine is correct *because it asks who you are*; the contract path is wrong *because it asks who you say you are*.

Reviews do it correctly too — `requireReviewForParty` is principal-scoped.

---

## 137. NOT CHECKED — Part 13 (law 5)

| Not verified | Why |
|-------------|-----|
| Runtime exploitation of either finding | **Static trace only — the chain in §136 was not executed** |
| Whether any production contract was in fact signed this way | Requires live data; the code path permits it, which is what is claimed |
| Whether an audit log records the acting principal per signature | `doRecordSignature` internals not traced — **this materially affects CS-1's forensic impact** |
| Whether the creator is notified of a signature recorded on their behalf | `ContractPendingSignatureEvent` exists; its recipients were not traced |
| Creator-side transitions (creator accepting a brand offer) | Symmetric risk likely; **brand surface only** was swept |
| Escrow release / dispute resolution consent | Not part of this sweep — release is service-triggered (Part 5), disputes are admin-resolved |
| Whether `TERMS_AGREED` from a self-accept has downstream effects | State reached; consequences not traced |

---

## 138. Register additions

> Corrected by verification: **−1 CRITICAL, −1 HIGH, +3 MEDIUM, +2 LOW, +2 struck.**

| ID | Surface | Severity | Evidence | Summary |
|----|---------|----------|----------|---------|
| **CS-3** | Contracts | 🟡 MEDIUM | believed | **Signatures carry no audit trail** — `Contract:136-143` writes only a timestamp; the signer's typed name is silently dropped (`MoneyDtos:231`). A relayed signature is byte-identical to a genuine one |
| **CS-2b** | Deals | 🟡 MEDIUM | believed | **Turn guard is inert on invited deals** — it requires `lastOffer.isPresent()`, and the invite path persists no `DealMessage`. Brand self-accepts `INVITED` → `TERMS_AGREED` with `agreedRate` null |
| **CS-1′** | Contracts | 🟡 MEDIUM | believed | Legacy `role=CREATOR` relay branch is unreachable from any UI and **removable at zero functional cost** |
| **CS-2** | Deals | 🔵 LOW | believed | `doCounter:721` has no turn guard — but a counter closes nothing (returns to `IN_NEGOTIATION`) |
| **CS-4** | Contracts | 🔵 LOW | believed | **No `TERMS_AGREED` precondition on contract generation** — `ContractService` checks only `CANCELLED` (`:205`, `:640`) |
| ~~CS-1~~ | ~~Contracts~~ | 🚫 **STRUCK** | — | False alarm — `recordSignatureForCreator` exists, is principal-scoped, FE-wired and mounted |
| ~~CS-2 (accept)~~ | ~~Deals~~ | 🚫 **STRUCK** | — | False alarm — `doAccept:632-646` enforces `CANNOT_ACCEPT_OWN_OFFER` |

**Running total: 66 live defects · 14 HIGH · 0 CRITICAL · 1 oracle-proved · 4 struck.**

---

## 139. What this part says about the audit itself

Twelve parts, ~120 endpoints, 62 defects — and **zero consent defects**, because the question was never asked. The sweep that found them took under an hour once the right question existed.

> 🚫 **This paragraph was wrong, and verification said so directly.** It claimed Part 13 failed on its *question set* rather than on verification. **It failed verification — by the widest margin in the document.**
>
> - The CRITICAL rested on **one stale javadoc**, with the refuting method 90 lines below it, unopened.
> - The HIGH cited a status helper and an idempotency wrapper, never opening the `doAccept` delegate that holds the actual guard.
> - `brand-chat.tsx:157` and `:226` — cited as gating logic — are a **TypeScript interface field** and an **object-literal assignment**. Neither is a conditional. **Seventh** non-executable citation in this document.
>
> Framing that as a question-set gap rather than a verification failure was self-flattering. The new question *was* worth asking — it found CS-2b, CS-3 and CS-4, all real. But the two headline findings it produced were both wrong, and both would have been caught by opening the file.

The honest version: the new question had real yield, and **the answers were not verified to the standard this document had already written down for itself.**

The three questions the audit asked of every endpoint were:

1. Does it exist? *(always yes — 0 broken endpoints in 12 parts)*
2. Does anything call it? *(the built-but-unreachable class — the largest finding group)*
3. Does the UI render its result honestly? *(the fabricated-value class)*

The question it never asked:

4. **Can one party perform both halves of a two-party action?**

That question found a CRITICAL in the first surface it touched. It has not yet been asked of the creator surface, the admin surface, or escrow/dispute resolution.

> ⚠️ **Both findings are `believed`.** The chain in §136 was traced through source, **not executed**. No contract was signed and no deal accepted.

---

*Part 13 produced by targeted static trace of two-party state transitions across `influora-api`, on branch `fix/brand-audit-remediation`. No live server was probed.*

---
---

# PART 13 — RED-TEAM VERIFICATION (Kabir)

> **Method.** Every claim in §§133-139 re-derived from executable source only, scoped to `git ls-files` (`.claude/worktrees/` excluded). Comments, javadoc, `@Column`/`@Transactional` annotations and TypeScript interface declarations were treated as **non-evidence** and re-verified against the statements they sit on. Frontend checked across every API layer.

## 140. Verdict — **NEEDS_FIX**

| Finding | Part 13 rating | Verified ruling |
|---|---|---|
| **CS-1** — brand signs the creator's signature; no creator-authenticated path exists | 🔴 CRITICAL | ❌ **FALSE ALARM as written.** A fully-wired creator-authenticated signing path exists, front to back. **CRITICAL is not justified.** A narrower real defect survives at 🟡 MEDIUM |
| **CS-2** — brand can accept **and** counter its own offer | 🔴 HIGH | ⚠️ **SPLIT.** Accept leg = ❌ **FALSE ALARM** (an explicit turn guard exists). Counter leg = ✅ **REAL** but 🔵 **LOW**, not HIGH |
| **§136** 5-step chain | asserted | ⚠️ **HOLDS — but every step as written is wrong.** Step 1 as specified *self-defeats* the chain. A different, shorter chain does work |
| **§133** sweep table | 5 of 8 pass | ❌ **2 rows wrongly FAILED** (rows 1, 2). Rows 5-8 verified — **no wrongly-passed transition** |

**This document has now produced its second false CRITICAL, by the identical mechanism as the first: a non-executable line quoted as authoritative.**

---

## 141. CS-1 — **FALSE ALARM.** The creator-authenticated signing path exists and is wired end-to-end

§134's entire case rests on three quoted sentences from **the javadoc on `ContractService#recordSignature`** (`influora-api/src/main/java/com/influora/service/ContractService.java:483-493`). That javadoc is **stale**. It describes the code as it stood *before* creator signing shipped, and the audit never checked it against the methods 90 lines below it.

### Refutation 1 — `ContractController.sign` does **not** run `requireBrandWorkspace` first

`influora-api/src/main/java/com/influora/web/ContractController.java:83-86`:

```java
if (principal.getUserType() == UserType.CREATOR) {
    return ApiResponse.ok(contractService.recordSignatureForCreator(principal, contractId));
}
var workspace = brandContext.requireBrandWorkspace(principal);   // <-- line 86, NOT first
```

The creator branch **precedes** the brand gate. §134's bullet *"`ContractController.sign` runs `requireBrandWorkspace` first, which throws unless the principal is a BRAND"* is false on the executable line.

### Refutation 2 — the creator signing method exists and is principal-scoped

`ContractService.java:576-578`:

```java
public ContractResponse recordSignatureForCreator(AuthPrincipal principal, String contractId) {
    creatorContext.requireCreator(principal);
    Contract contract = requireContractForCreator(contractId, principal.getUserId());
```

`ContractService.java:916-920`:

```java
private Contract requireContractForCreator(String contractId, String creatorUserId) {
    return contractRepository
            .findByIdAndCreatorId(contractId, creatorUserId)
```

This is **exactly** the `findByIdAnd<Party>Id(id, principal.getUserId())` pattern §136 praises `requireCreatorCollaboration` for and claims contracts lack. It was already there, in the same file, at the time of the audit.

### Refutation 3 — the frontend is wired, and it is *not* a dead component

| Layer | File:line | Executable code |
|---|---|---|
| Creator UI | `src/components/creator/deal-room/creator-deal-contract-tab.tsx:124` | `const result = await signContract(contractId, 'creator', trimmedName);` |
| Creator UI | `src/components/creator/deal-room/creator-contract-panel.tsx:82` | `const result = await signContract(contractId, 'creator', trimmedName);` |
| Rendered | `src/pages/creator-chat.tsx:2387`, `:2575` | both components mounted |

Five creator-reachable contract routes exist, all principal-scoped: `GET /contracts`, `GET /contracts/unsigned`, `GET /contracts/{id}`, `POST /contracts/{id}/sign`, `GET /contracts/{id}/pdf-download-url` (`ContractController.java:50, 61, 71, 83, 117`).

### Refutation 4 — `role` is **not** client-supplied on any real call path

`src/lib/api.ts:2157-2162` passes `role` as a **transport option that selects which JWT to attach** (`api.ts:222-223` `localStorage.getItem(TOKEN_KEYS[role])`; `api.ts:250-251` sets the `Authorization` header) — **not** into the request body. The body is `{name, agreedAt}`.

Therefore `body.role()` is **always null** on FE traffic, and `ContractController.java:102-104` defaults to `"BRAND"` — server-derived from the authenticated principal. **On 100% of real traffic the signing party is derived from the JWT, not from a client string.**

### Refutation 5 — the "load-bearing" claim is false

§134 asserts rejecting `role=CREATOR` *"would break contract completion entirely."* `recordSignatureForCreator` (:576) reaches fully-executed through the **same** `doRecordSignature` delegate (:586 → :619 → :661-666). Removing the `role` parameter breaks **no production path** — only two unit tests that invoke `recordSignature` directly. The javadoc's "load-bearing" note was true when written and has been obsolete since creator signing landed.

Consequently *"Every fully-executed contract this platform has produced was executed by the brand alone"* (§134) is **unsupported** — the audit had no live data, and the code contradicts it.

### 🟡 CS-1′ — what actually survives (MEDIUM, downgraded from CRITICAL)

The **legacy brand-relay branch was never removed** after creator signing shipped:

- `ContractController.java:102-104` — still honors an explicit `role` if a caller supplies one
- `ContractService.java:535-541` — `role=CREATOR` still records the creator's leg, gated only on `MemberRole.OWNER/ADMIN/MANAGER`

A brand OWNER/ADMIN/MANAGER can still forge the creator's signature via a **hand-crafted API call** (no UI reaches it). This is the original **LOW-4**, still open. Raised one notch to **MEDIUM** — not because the risk grew, but because it is now **removable at zero functional cost**, which the audit's own "load-bearing" framing wrongly ruled out.

---

## 142. 🟡 CS-3 — **NEW.** No signature evidence is persisted at all. §137 left this unchecked; it is the finding Part 13 should have led with (MEDIUM)

§137 flagged *"whether an audit log records the acting principal per signature"* as unverified and *"materially affects CS-1's forensic impact."* Verified — and the answer is worse than either rating assumed.

`influora-api/src/main/java/com/influora/domain/entity/Contract.java:136-143`:

```java
public void recordBrandSignature()   { this.brandSignedAt = Instant.now(); }
public void recordCreatorSignature() { this.creatorSignedAt = Instant.now(); }
```

The entity has exactly **two** signature columns — `brandSignedAt:52`, `creatorSignedAt:55`. `doRecordSignature` (:619-651) writes nothing else. **No acting principal, no user id, no signer name, no IP, no user-agent.**

Worse — the signer's typed name, which the UI collects *as* the e-signature, never reaches the database:

```java
// MoneyDtos.java:231
public record ContractSignRequest(String role) {}
```

The DTO declares **only** `role`. The FE sends `{name, agreedAt}` (`contract-generator.ts:203-217`); both fields are silently dropped as unknown properties, and `.agreedAt()` has **zero call sites repo-wide**.

**Consequences:**
1. A brand-relayed `role=CREATOR` signature is **byte-for-byte indistinguishable** from a genuine creator signature. CS-1′ is therefore *undetectable after the fact* — there is no forensic trail to audit.
2. This affects **every** signature, relayed or not. A "fully executed" contract records two timestamps and nothing tying either to a human.
3. §134's claim that existing contracts were brand-signed is **not provable in either direction** — the data to answer it was never captured.

This is a genuine evidentiary defect in a legal-signature flow, and it is the one Part 13 correctly identified as decision-relevant and then did not check.

---

## 143. CS-2 — **SPLIT.** The accept leg is guarded; the audit never opened the method

### Accept — ❌ **FALSE ALARM**

`DealService.java:632-646` — inside `doAccept`, the delegate:

```java
DealSenderType actingAs = role == UserType.CREATOR ? DealSenderType.creator : DealSenderType.brand;
Optional<DealMessage> lastOffer =
        dealMessageRepository.findFirstByCollaborationIdAndKindOrderByCreatedAtDesc(
                collaboration.getId(), DealMessageKind.proposal);
if (lastOffer.isPresent() && lastOffer.get().getSenderType() == actingAs) {
    throw new ApiException(
            "CANNOT_ACCEPT_OWN_OFFER",
            "You cannot accept the offer you last made — waiting on the other party",
            HttpStatus.CONFLICT);
}
```

This is **precisely** the turn guard §135 says does not exist — it resolves the last offer's author from the persisted `DealMessage.senderType` and compares it to the acting role.

Both of §135's citations are real lines that are simply **not where the decision is made**: `Collaboration.canAccept():185-190` is status-only (true, and irrelevant), and `DealService.accept():255` is the idempotency **wrapper** — the guard lives in `doAccept` at `:624`. The audit stopped at the wrapper.

§135's table row *"Accept → ✅ succeeds — moves to `TERMS_AGREED` with no creator involvement"* is **false** for any deal created through `POST /deals` (`createProposal:174` persists a `senderType=brand` proposal at `:213-224`, arming the guard).

### Counter — ✅ **REAL, but 🔵 LOW not HIGH**

`doCounter:721-782` and the `counter()` wrapper `:383-424` contain **no** self-offer check. A brand can counter its own pending offer. Confirmed.

But the stated impact — *"one party can close a two-party negotiation unilaterally"* — **does not follow.** A counter closes nothing: `:734` transitions to `IN_NEGOTIATION`, settles the prior card as `"countered"` (`:744-745`) and persists a new offer (`:746-754`) that the **counterparty must still accept** — through the guard above. Functionally this is *"edit my own offer."* **LOW.**

### ⚠️ CS-2b — **NEW, and it is the accept bypass Part 13 was reaching for** (MEDIUM)

The `CANNOT_ACCEPT_OWN_OFFER` guard is conditioned on **`lastOffer.isPresent()`**. It is inert when no proposal message exists.

`Collaboration.invite` (`Collaboration.java:64-78`) creates an `INVITED` collaboration and its two callers persist **no `DealMessage`**:

- `CreatorDiscoveryService.java:453-462` (`POST /creators/{id}/invite`) — the class contains **zero** `DealMessage` references
- `ConfirmLaunchExecutor.java:472-476` — **Meera's campaign-launch path**, same pattern

`INVITED` is on the `canAccept()` allowlist (`Collaboration.java:186`). So on **any invite-originated deal**, `lastOffer` is empty → guard skipped → **the brand accepts its own invitation into `TERMS_AGREED` with zero creator involvement.**

`Collaboration.invite` also never sets `agreedRate` (compare `:38` — the field is left null). The deal therefore reaches `TERMS_AGREED` **with no agreed price**.

Part 13 asserted this outcome for the wrong path and missed the path where it is real.

---

## 144. §136 — the chain **holds**, but not one step of it is correct as written

| Step (as written) | Verified |
|---|---|
| 1 · `POST /deals` — brand sends priced offer | ⚠️ **Self-defeating.** Persists a `senderType=brand` proposal (`:213-224`), which **arms** the guard and blocks step 2 |
| 2 · `POST /deals/{id}/accept` | ❌ **Blocked** on this path — 409 `CANNOT_ACCEPT_OWN_OFFER` |
| 3 · `POST /contracts` | ✅ works |
| 4 · sign `role=BRAND` | ✅ works |
| 5 · sign `role=CREATOR` | ✅ works — **but only via a hand-crafted request** by an OWNER/ADMIN/MANAGER; no UI reaches it |

**The chain that actually works:**

| Step | Call | Why it passes |
|---|---|---|
| 1 | `POST /creators/{id}/invite` | `INVITED`, **no proposal message persisted** |
| 2 | `POST /deals/{id}/accept` | ✅ **CS-2b** — `lastOffer` empty, guard inert → `TERMS_AGREED` |
| 3 | `POST /contracts` | ✅ **no `TERMS_AGREED` precondition exists** — see below |
| 4 | `POST /contracts/{id}/sign` (body omitted) | ✅ defaults to `BRAND` |
| 5 | `POST /contracts/{id}/sign` `{"role":"CREATOR"}` | ✅ **CS-1′** — OWNER/ADMIN/MANAGER only, hand-crafted |

### 🔵 CS-4 — NEW: step 2 is **not even required** (LOW)

`ContractService.generate:136-210` gates on workspace ownership (`:163`), elevated `MemberRole` (`:139`), duplicate contract, and `CANCELLED` (`:205`) — **and nothing else.** `CollaborationStatus` appears in the whole of `ContractService` at exactly two lines, `:205` and `:640`, both `CANCELLED` checks.

**There is no `TERMS_AGREED` precondition on contract generation.** A brand can generate a binding contract directly on an `INVITED` collaboration, skipping negotiation entirely. Contract amounts come from the request body (`:246`, `:258`), not from `collaboration.agreedRate`, so the null rate from CS-2b is never noticed.

**Net:** the §136 conclusion — *a fully-executed contract naming a creator who never took a single action* — **is reachable.** The audit reached the right destination on a map where every landmark is misplaced.

---

## 145. §133 sweep table — corrected

| # | Transition | Part 13 | Verified |
|---|---|:---:|---|
| 1 | Contract signature | 🔴 NO | ⚠️ **Wrongly failed.** Creator path is principal-scoped (`:576-578`); brand path server-derives (`Controller:102-104`). Residual = optional legacy `role` relay (CS-1′, MEDIUM) |
| 2 | Deal accept | 🔴 NO | ⚠️ **Wrongly failed.** `CANNOT_ACCEPT_OWN_OFFER` guard at `doAccept:641`. Real gap is the invite path (CS-2b) |
| 3 | Deal counter | 🔴 NO | ✅ **Correctly failed** — severity LOW not HIGH |
| 4 | Deal reject | ⚠️ | ✅ Fair. `reject:307` is dual-role + `requireOwnedCollaboration`; `canReject()` narrowed to a pre-contract allowlist (CR-22a) |
| 5 | Shipment — mark shipped | ✅ | ✅ **Confirmed.** `ShipmentService:368-374` `requireBrandWorkspace` → `findByIdAndWorkspaceId` |
| 6 | Shipment — confirm receipt | ✅ | ✅ **Confirmed.** `ShipmentService:360-366` — `creatorContext.requireCreator(principal)` then `findByIdAndCreatorId(dealId, principal.getUserId())`. **Principal-resolved; no client-supplied creator id.** The helper is real |
| 7 | Deliverable approve / revise | ✅ | ✅ **Confirmed.** `BrandDeliverableService:87-88, 133-134` `requireBrandWorkspace` → `findByIdAndWorkspaceId`; state gate `:235-237` admits only `SUBMITTED`/`RESUBMITTED` — a brand cannot manufacture the creator's submission |
| 8 | Review create / flag | ✅ | ✅ **Confirmed.** `ReviewService:149-175` — creator leg scoped by `principal.getUserId()`, brand leg by `requireBrandWorkspace`; `partyType` is caller-pinned (`:68-69`, `:76-77`), never client input |

**No wrongly-passed transition.** Rows 5-8 all hold. **Missing row: the invite transition itself** (`POST /creators/{id}/invite`) — the one place the guard is genuinely absent (CS-2b).

---

## 146. The citation failure — **seventh instance**

§135 cites `brand-chat.tsx:157,226` as evidence *"the frontend gates on `rawStatus` — the same status-only logic."* Both lines were read:

- **`:157`** → `rawStatus: CollaborationStatus;` — a **TypeScript interface field declaration**. Non-executable.
- **`:226`** → `rawStatus: deal.status,` — an **object-literal mapping assignment**. Not a gate.

Neither line is a conditional. Neither gates anything. This is the **seventh** time this document has advanced a non-executable line as proof — after JSDoc ×2, `@Column`, `@Transactional`, a spec file that does not exist, and now a stale javadoc (§141) plus a type declaration.

**The pattern has a shape.** Every one of these failures cites something that *describes* code instead of something that *is* code. §139 concluded Part 13's blind spot was "a failure of the question set, not of verification." That is wrong. **Part 13 failed verification in the same way as Parts 1-12**, and by the largest margin yet: it quoted a stale javadoc's self-description across three separate load-bearing claims and never read the two methods below it.

---

## 147. Corrected Part 13 register

| ID | Surface | Part 13 | Verified | Summary |
|----|---------|:---:|:---:|---------|
| ~~CS-1~~ | Contracts | 🔴 CRITICAL | ⛔ **STRUCK** | Creator-authenticated signing exists end-to-end (`ContractController:83`, `ContractService:576`, `creator-chat.tsx:2387/2575`). Evidence was a stale javadoc |
| **CS-1′** | Contracts | — | 🟡 MEDIUM | Legacy brand-relay `role=CREATOR` (`Controller:102-104` + `Service:535-541`) never removed after creator signing shipped. OWNER/ADMIN/MANAGER, hand-crafted request only. **Now removable at zero functional cost** |
| **CS-3** | Contracts | — | 🟡 MEDIUM | **No signature evidence persisted.** `Contract:136-143` writes only `Instant.now()`; `ContractSignRequest:231` declares only `role`, so the signer's typed name is dropped. Relayed and genuine signatures are indistinguishable |
| ~~CS-2 (accept)~~ | Deals | 🔴 HIGH | ⛔ **STRUCK** | `doAccept:641` throws `CANNOT_ACCEPT_OWN_OFFER`. Audit read the wrapper, not the delegate |
| **CS-2 (counter)** | Deals | 🔴 HIGH | 🔵 **LOW** | Real — `doCounter:721` has no self-offer check. But a counter closes nothing; the counterparty must still accept through the guard |
| **CS-2b** | Deals | — | 🟡 MEDIUM | Invite-originated deals persist no proposal message (`CreatorDiscoveryService:453`, `ConfirmLaunchExecutor:472`), so the accept guard is inert → brand self-accepts into `TERMS_AGREED`, with `agreedRate` null. **Includes every Meera-launched campaign** |
| **CS-4** | Contracts | — | 🔵 LOW | `ContractService.generate` has **no `TERMS_AGREED` precondition** — a contract can be generated directly on an `INVITED` deal, skipping negotiation |

**Severity movement: −1 CRITICAL, −1 HIGH, +3 MEDIUM, +2 LOW.**
**Corrected running total: 66 live defects · 14 HIGH · 0 CRITICAL · 1 oracle-proved · 4 struck.**

> ⚠️ All rulings above are **proved against source**, not executed. No live server was probed; no contract was signed and no deal accepted. The §144 chain remains **believed**, though its individual guards are now **proved** present or absent.

---

## 148. Corrections to §137's NOT-CHECKED list

| §137 entry | Status after verification |
|---|---|
| Whether an audit log records the acting principal per signature | ✅ **CHECKED — none exists.** Promoted to CS-3 (MEDIUM) |
| Creator-side transitions | ⚠️ Partly answered — the creator *contract* surface is principal-scoped throughout (`ContractService:576, 835, 843, 855, 881`) |
| Whether the creator is notified of a signature recorded on their behalf | ❌ Still unchecked |
| Whether `TERMS_AGREED` from a self-accept has downstream effects | ⚠️ **Partly answered — and it does not matter.** CS-4 shows contract generation never required `TERMS_AGREED` in the first place |
| Runtime exploitation | ❌ Still unchecked — correctly flagged |

**Newly unchecked, introduced by this verification:**

- Whether any other FE surface exposes `role` in the sign body (only `api.ts` traced; `meera-api.ts` and `admin/services/api-contracts.ts` were listed but their contract methods not individually read)
- Whether Jackson is configured with `FAIL_ON_UNKNOWN_PROPERTIES` disabled — assumed from Spring Boot defaults, **not verified**. If it were enabled, every FE sign call would 400 and the whole signing surface would be dead
- Whether `GET /contracts/unsigned` having **no frontend caller** leaves creators without a way to *discover* a contract awaiting signature (the sign path works; the route to it may not)

---

## 149. Closing — the audit graded its own blind spot wrong

Part 13 opened by claiming Parts 1-12 missed consent defects because *the question was never asked*, and closed (§139) by ruling this *"different — a failure of the question set, not of verification."*

Both halves are wrong.

The question **had** been asked and **had** been answered — in code. `CANNOT_ACCEPT_OWN_OFFER` (`DealService:641`) is a turn guard with a bespoke error code and an eight-line comment explaining the product assumption it encodes. `recordSignatureForCreator` (`ContractService:576`) is a principal-scoped creator signing path with a wired UI. Someone asked question 4 before this sweep did, and shipped the answers. Part 13 declared both absent without opening either method.

What it did instead was quote a **stale javadoc** — three times, for three separate load-bearing claims — in a document whose own preamble warns that comments in this repo lie.

The genuine defects are real but smaller and differently shaped: a **legacy relay parameter** left behind after its replacement shipped (CS-1′), an **empty signature record** with no principal and no name (CS-3), an accept guard that is **inert on invite-originated deals** including every Meera launch (CS-2b), and **contract generation with no negotiation precondition** (CS-4). Three of the four are in the gaps *between* correctly-built guards — which is where they usually are, and which you only find by reading the guards.

> The fifth question this audit should now add: **when a guard is reported missing, was the delegate read, or only the wrapper?**

---

*Part 13 verification by Kabir (red-team), branch `fix/brand-audit-remediation`. Executable source only; javadoc, annotations, and type declarations excluded as evidence. Scoped to `git ls-files`. No live server probed.*

---
---

# PART 14 — Uploads · Ops/Governance · Webhooks · Infra

> **Scope:** the final 11 controllers on the brand-side list — Upload, Portfolio, ApprovalWorkflow, AuditLog, DeliverableMetric, ClientError, ConversionWebhook, ConversionWebhookSecret, Health, Jwks, PublicConfig. **24 endpoints. This closes the brand-side controller inventory.**
> **Branch:** `fix/brand-audit-remediation` · **Date:** 2026-08-09
>
> **Method change, after two false CRITICALs.** Part 13's headline rested on a stale javadoc whose refutation sat 90 lines below, unopened; Part 13's HIGH cited a status helper and an idempotency wrapper instead of the delegate holding the guard. Two rules were applied here without exception:
> 1. **No finding from a comment.** Every claim below cites executable code.
> 2. **When a controller is thin, open the delegate.** `UploadController` is 3 lines of pass-through; the entire upload security posture lives in `UploadService`.
>
> Both rules changed the outcome: they killed two candidate findings (§143) that a shallower pass would have reported.

---

## 150. Summary

| Group | Controllers | Endpoints | Verdict |
|-------|------------|----------:|---------|
| **Uploads / media** | Upload, Portfolio | 8 | 1 MEDIUM |
| **Ops / governance** | ApprovalWorkflow, AuditLog | 5 | **out of brand scope** — both are `/admin/*` |
| **Ops / governance** | DeliverableMetric, ClientError | 2 | clean |
| **Webhooks** | ConversionWebhook, ConversionWebhookSecret | 5 | clean |
| **Infra** | Health, Jwks, PublicConfig | 4 | clean |

**Verdict: 1 defect across 24 endpoints. The security-sensitive surfaces here — file upload, JWKS, public config — are the best-implemented code in this audit, and unlike Part 10's billing praise, that claim is made only where the delegate was actually opened.**

---

## 151. 🟡 UX-1 — The public portfolio contact form has no rate limit (MEDIUM)

| Field | Detail |
|-------|--------|
| **Severity** | 🟡 MEDIUM — unauthenticated, unthrottled, triggers outbound notification per request |
| **Where** | `PortfolioController:54` · `AuthRateLimitFilter.bucketFor:273-369` |

`POST /portfolio/{username}/contact` is **public and unauthenticated**. It validates the email format (`PortfolioService:345-346`) and then **publishes a notification event** to the creator (`:356-359`) — an email per request.

**It is in no rate-limit bucket.** `bucketFor` enumerates buckets by path and **returns `null` at `:369`** for anything unmatched; `null` means no throttle. Searching the entire filter for `portfolio` returns exactly one hit — **a comment at `:134`** referencing the client-error sink, not a rule.

So an unauthenticated caller can post unlimited contact messages to any creator's public portfolio, each generating a notification. This is targeted flooding of a creator's inbox and an unbounded outbound-email cost to the platform.

> Not an open relay — the recipient is fixed by `{username}`, and the attacker cannot choose it. That caps the severity at MEDIUM.

> ⚠️ **Corrected:** this section originally called `/portfolio/*/contact` *"the single public endpoint with no rate limit."* **False — there are two.** See **UX-2** below.

**The codebase already does this correctly elsewhere**, which is what makes it an omission rather than a design choice:

| Public endpoint | Throttled? |
|---|---|
| `POST /client-errors` | ✅ own bucket, IP-keyed, **plus a manual 16 KB streaming cap** (`ClientErrorController:45,146`) |
| `GET /track/click/*` | ✅ bucketed (`:284`) |
| `POST /webhooks/redemption`, `/webhooks/conversion` | ✅ bucketed (`:296`) |
| `POST /portfolio/{username}/contact` | ❌ **none** |

**Fix:** add a bucket in `bucketFor` for `/portfolio/*/contact`, IP-keyed, reusing the existing `tracking` or `clientError` limit shape.

---

## 151a. 🟡 UX-2 — `GET /portfolio/{username}` is also unbucketed, **and it is a write** (MEDIUM) — *found by verification*

`bucketFor`'s GET branch returns `null` at `AuthRateLimitFilter:290`, so the public portfolio page is unthrottled too. That would be minor for a read — **except it is not a read.**

`PortfolioController.getPublic:47` → `recordPublicView:156-162` performs an **unconditional `portfolioEventRepository.save(...)`: one database row per anonymous GET**, with no dedup and no cap.

| Consequence | Detail |
|---|---|
| **Unbounded storage growth** | Anyone with `curl` can insert rows into `portfolio_events` indefinitely |
| **Forgeable creator analytics** | `computePageViews:285-296` derives the creator's "Page views (30d)" from those rows — **any third party can inflate or fabricate a creator's traffic figures** |

The second is the more serious: it is a data-integrity defect on a number creators may use to price themselves, and brands may use to evaluate them.

**Fix:** bucket the GET as well, and dedup view records (per IP+day, or a session cookie).

---

## 151b. 🔵 UX-3 — A declared anti-spam control that does not exist, and a security comment asserting it does (LOW) — *found by verification*

`PortfolioDtos:103` declares a **`captchaToken`** field. A repo-wide search returns **that declaration and nothing else** — it is never read, validated, or forwarded.

Meanwhile `SecurityConfig:154` states:

> *"Server-side anti-spam on contact is enforced in PortfolioService."*

**It is not.** `PortfolioService.contact:342-353` performs field validation only.

> 🔎 **This is the same stale-comment mechanism that produced this document's false CRITICAL in Part 13 — running in the opposite direction.** There, a comment invented a defect that the code had already fixed. Here, a comment in the **security configuration** conceals a gap the code never closed. Both are cases of a reader trusting prose over source; only the sign differs.

**Fix:** either implement the captcha check or delete both the field and the comment. A security config that describes a control it does not have is worse than one that admits the gap.

---

## 152. ✅ Verified clean — and how deeply

Verification depth is stated per controller, because "clean" without that is the claim this audit has been wrong about most often.

### Upload — traced into the service (deep)

`UploadController:32-35` is pass-through. `UploadService.upload:59-98` carries the whole posture, and every control below was read as executable code:

| Control | Evidence |
|---|---|
| Empty / missing file | `:60-61` → `INVALID_FILE` 400 |
| Storage unavailable | `:63-65` → `STORAGE_UNAVAILABLE` 503 |
| Size cap | `:67-71` → `FILE_TOO_LARGE` 400 |
| **MIME is sniffed, not trusted** | `:74-75` — `sniffMime(file)` then `validateMime(file, sniffedMime)` |
| **Malware scan before storage** | `:79` — `malwareScanService.requireClean(...)`; ClamAV in prod, NoOp stub outside (Kabir M-K6-C3-3) |
| **Path traversal structurally impossible** | `:81-88` — the key is `uploads/{userType}/{userId}/{ULID}` + an extension derived from the **sniffed** MIME. **The user's original filename is never used** |
| Tenant partitioning | key embeds `principal.getUserId()` |
| Defence-in-depth on a lying size header | `:92-94` — `LimitedInputStream(raw, MAX_BYTES)` wraps the stream regardless of declared size |

This is the strongest single implementation encountered in fourteen parts.

### Jwks — traced into the key service (deep)

`SpringJwksKeyService.publicJwkSet:146-155` builds `Jwks.builder().key(publicKey).id(kid).operations().add(Jwks.OP.VERIFY)`. **`publicKey`, verified in code** — not asserted from the class javadoc — with operations restricted to VERIFY. No private material is reachable from the controller.

### PublicConfig — read both endpoints in full (deep)

- `/config/public` → returns a single boolean, `requireEmailOtpBeforeRegister`. Not a secret: it mirrors a rule the server enforces regardless of what the client believes.
- `/config/razorpay` → returns **`razorpayProperties.getKeyId()`** — the *publishable* key, **not** `getKeySecret()` — and is authenticated (no `permitAll`). Verified by reading the getter called, not the comment describing it.

### ClientError — read the controller (deep)

Unauthenticated by contract (a crash may occur pre-login), and defended accordingly: body read via a **manual 16 KB streaming cap** rather than a `@RequestBody` DTO, so an oversized payload is rejected without buffering — **executable code at `readCapped:152-169`** — plus its own IP-keyed rate bucket.

> ⚠️ **Citation corrected.** This section originally cited `:45` and `:146` — **both javadoc.** The substance was right and the evidence was not. **Eighth non-executable citation in this document**, in the very part that opened by declaring "no finding from a comment." The rule was applied to findings and not to praise.

### Health — read the payload (deep)

Returns `status` from the actuator `HealthEndpoint` (which really pings the DB) plus a binary `r2: configured|not_configured`. **No versions, no stack traces, no connection strings.** Minimal disclosure.

### Traced at mapping level only (shallow — stated honestly)

**ConversionWebhook** (3), **ConversionWebhookSecret** (2), **DeliverableMetric** (1), **Portfolio's** other 6 endpoints: mappings confirmed to exist and paths recorded; **their service delegates were not opened.** Given that opening the delegate is exactly what changed the outcome for Upload, these are **not certified clean — only unexamined.**

### Out of brand scope

**ApprovalWorkflow** (`/admin/moderation`, 2) and **AuditLog** (`/admin/audit`, 3) are admin controllers, confirmed from their `@RequestMapping`. They belong to the unaudited admin surface (32 controllers), not the brand surface.

---

## 153. Two candidate findings the method killed

Both would have been reported by a shallower pass. Recording them because avoiding them *is* the result.

1. **"The click tracker is an open redirect."** `ConversionWebhookController:288-304` does `ResponseEntity.status(FOUND).location(URI.create(utm.getFullTrackingUrl()))` — a redirect to a stored URL. But the value is constrained at creation: `TrackingDtos:60` declares `@NotBlank @Pattern(regexp = "^(?i)https?://\\S+$")`. Scheme-restricted, and the destination is set by an authenticated brand — which is the feature. **Not a defect.**

2. **"Upload accepts arbitrary filenames."** `UploadController` shows no validation at all. The validation is entirely in `UploadService` (§152). **Not a defect** — and this is the exact `doAccept` mistake from Part 13, avoided by opening the delegate.

---

## 154. NOT CHECKED — Part 14 (law 5)

| Not verified | Why |
|-------------|-----|
| Runtime behaviour | Static trace only; **no file uploaded, no webhook received, no contact form submitted** |
| **Service delegates for ConversionWebhook, ConversionWebhookSecret, DeliverableMetric, and 6 Portfolio endpoints** | Mapping-level only — see §152. **The Upload finding shows this is where posture actually lives** |
| ~~Whether `validateMime`'s allowlist is appropriately narrow~~ | ✅ **CLOSED by verification — it is.** `sniffMime` is genuine **magic-byte** sniffing (`MediaMimeSniffer:20-73`), not the client header. The allowlist is a **closed 7-format set**; SVG/HTML/JS carry no magic bytes, so they sniff to `null` and are rejected at `UploadService:152-157`. Video sniffs but is rejected at `:167-169`. Survivors: jpeg, png, gif, webp, pdf |
| Whether ClamAV is actually configured in production | Code selects `NoOpMalwareScanService` outside prod — the **deployed** profile was not checked |
| Whether R2 objects are served with a safe `Content-Disposition` | Storage write traced; the read/serve path was not |
| Whether the JWKS private key is sourced safely at runtime | Public-only emission verified; key provisioning not traced |
| Admin surface (ApprovalWorkflow, AuditLog + 30 more) | Out of scope — **still entirely unaudited** |
| Whether UX-1 is exploitable end-to-end | Absence of a bucket is certain; delivery of the resulting email was not observed |

---

## 155. Register addition

| ID | Surface | Severity | Evidence | Summary |
|----|---------|----------|----------|---------|
| **UX-1** | Portfolio | 🟡 MEDIUM | believed | `POST /portfolio/{username}/contact` is public, unauthenticated and **in no rate-limit bucket**; each request publishes a creator notification (`NotificationListener:373-387`) |
| **UX-2** | Portfolio | 🟡 MEDIUM | believed | `GET /portfolio/{username}` is also unbucketed **and performs a write** — one `portfolio_events` row per anonymous GET. Unbounded storage growth, and **creator page-view analytics are forgeable by any third party** |
| **UX-3** | Portfolio | 🔵 LOW | believed | `captchaToken` is declared (`PortfolioDtos:103`) and **never read anywhere**, while `SecurityConfig:154` asserts *"Server-side anti-spam on contact is enforced in PortfolioService."* It is not |

**Running total: 69 live defects · 14 HIGH · 0 CRITICAL · 1 oracle-proved · 4 struck.**

### Verification also strengthened one kill

§153 correctly cleared the open-redirect, but on the **weakest available evidence**. The `@Pattern` is live (`@Valid` confirmed at `CampaignTrackingController:71` — the missing-`@Valid` trap from Part 10 does not apply here). But the **load-bearing** gate is `CampaignLinkService.validateBaseUrl:232-250`, called unconditionally at `:172` — which this part never opened. Right verdict, thin proof.

---

## 156. Closing — the brand-side controller inventory is complete

Every controller on the brand-side list has now been traced across fourteen parts. **~145 endpoints. Zero broken, missing or mismatched backend routes, in any part.**

**This part is the cleanest of the fourteen — 1 defect in 24 endpoints — and the reason is worth stating.** The surfaces here are the ones with named security review baked in: the upload path cites a red-team fix, JWKS cites a security ADR, the client-error sink cites a written contract. That is now the third time this pattern has held (Meera in Part 11, the Razorpay guard in Part 10): **wherever a control traces to an adversarial review, it holds; wherever a control was certified by reading the code and its comments, it sometimes did not.**

**And the defects fit the same shape.** ~~`/portfolio/*/contact` is the single public endpoint with no rate limit~~ — **corrected: both public portfolio endpoints are unbucketed**, in a filter that explicitly buckets every other one, and one of them writes a row per anonymous request. They were not designed unthrottled; they were missed.

**The public portfolio pair is the one surface here with no named security review attached** — and it is the only surface here with defects. That is now the fourth independent instance of the same correlation.

> ⚠️ **Everything here is `believed`**, and §154 is unusually load-bearing: four controllers were traced only at mapping level. **The Upload finding demonstrates that mapping-level tracing would have missed the entire upload security posture** — so those four are recorded as *unexamined*, not clean.

---

*Part 14 produced by static code trace of `influora-api` on branch `fix/brand-audit-remediation`, with service delegates opened for every controller marked "deep". No live server was probed.*

---

# PART 14 — RED-TEAM VERIFICATION (Kabir)

> **Method:** every citation in §§150–156 re-opened from primary source, scoped to `git ls-files`. Delegates opened for all six "clean" verdicts *and* for the four controllers §152 admitted were traced at mapping level only. Line numbers below are executable lines unless explicitly marked.

## 157. Verdict — **NEEDS_FIX**

**Part 14's headline is right and its six deep verdicts survive.** UX-1 is real, and — unlike Parts 10 and 13 — no finding here rests on a comment. This is the first part in the audit whose *findings* are all citation-clean.

**But the part is not done, on four counts:**

| # | Defect in Part 14 | Class |
|---|---|---|
| 1 | §151 and §156 both assert `/portfolio/*/contact` is **"the single public endpoint with no rate limit."** False. `GET /portfolio/{username}` is equally unbucketed **and it performs an unauthenticated DB write per request.** | Missed defect (new: UX-2) |
| 2 | The contact DTO carries a `captchaToken` field that **nothing anywhere reads**, and `SecurityConfig:154` states anti-spam "is enforced in PortfolioService" — it is not. A phantom control §151 walked past. | Missed defect (new: UX-3) |
| 3 | §151 and §152 both cite `ClientErrorController:45,146` as proof of the 16 KB cap. **Both are javadoc lines.** The cap is at `:152-169`. **Eighth instance** of the citation failure this part's own preamble swore off. | Citation |
| 4 | §153's open-redirect kill cites `TrackingDtos:60` — an **annotation on a record component**, a declaration. The authoritative gate is `CampaignLinkService.validateBaseUrl:232-250`, invoked at `:172`, which the audit never opened. Right verdict, wrong evidence. | Citation |

Counts 3 and 4 matter beyond bookkeeping: in both cases the executable proof existed and was one file away.

---

## 158. §152's six "clean" verdicts — ruling, one by one

### 1. Upload — DESERVED, and stronger than claimed

All eight controls re-read as executable code at the cited lines. Two claims re-tested adversarially:

- **`sniffMime` is real content sniffing, not the client header.** `UploadService:126-148` calls `MediaMimeSniffer.detectMimeType(file.getInputStream())` (`MediaMimeSniffer:20-73`), which reads 16 leading bytes and matches magic numbers — JPEG `FF D8 FF`, PNG `89 50 4E 47`, `GIF87a`/`GIF89a`, `RIFF….WEBP`, `ftyp`, EBML. `file.getContentType()` is read only at `:151`, and only as a *second* constraint that must agree.
- **§154 left "is the allowlist narrow enough?" open. It is — closed by construction.** The sniffer is a **closed 7-format set**; anything else returns `null`. SVG, HTML and JS carry no magic bytes, so they sniff to `null` and die at `:152-157`. Video sniffs successfully but is then rejected at `:167-169` (`declared` must `startsWith("image/")` while `mimeTypesCompatible` requires the same family). The surviving set is exactly **jpeg / png / gif / webp / pdf** — the same five `extensionFor:177-186` maps, so its `default -> ""` branch is unreachable. **§154's open question can be closed.**
- **Storage key genuinely excludes the filename.** `:81-88` concatenates only `userType`, `userId`, a fresh ULID, and a server-derived extension. `file.getOriginalFilename()` appears nowhere in the class.

### 2. Jwks — DESERVED

`publicJwkSet:146-156` builds from field `publicKey`, whose declared type is `ECPublicKey` (`:138`, assigned at `:107` from `parseX509EcPublicKey`). `privateKey` is a separate field never referenced in the method. Operations restricted to `VERIFY`. Verified from types and assignments, not the javadoc.

### 3. PublicConfig — DESERVED

`:71` returns `razorpayProperties.getKeyId()`; `getKeySecret()` does not appear in the file. And I checked `SecurityConfig` rather than the javadoc: the permit list (`:85-189`) contains `GET /config/public` at `:98` and **no entry for `/config/razorpay`**, so it falls to `anyRequest().authenticated()` at `:190-191`. Claim holds.

### 4. ClientError — DESERVED on substance, WRONG CITATION

The cap is real and does prevent buffering: `readCapped:152-169` allocates `MAX_BODY_BYTES + 1` and loops bounded by `buffer.length`, returning `null` past the cap. `Content-Length` is a fast-path only (`:153-156`); the byte count is the enforcement. **The cited `:45` and `:146` are both javadoc.**

### 5. Health — DESERVED

`health():26-31` returns exactly `status` (from `healthEndpoint.health()`) and `storage.r2` as `configured|not_configured`. No versions, build info, or connection strings.

### 6. "Upload is the strongest implementation in fourteen parts" — upheld.

---

## 159. §153 — both kills re-tested

1. **Open redirect — correctly killed, on evidence the audit did not use.** `@Valid` *is* present (`CampaignTrackingController:71`), so unlike the earlier `@NotBlank` that never fired, this annotation is live. But the load-bearing gate is `CampaignLinkService.validateBaseUrl:232-250` — `new URI(baseUrl)` then an explicit `http`/`https` scheme check throwing `INVALID_TRACKING_URL` — called unconditionally at `:172`, **before** the value is concatenated or persisted. That is enforced regardless of caller; the DTO annotation is not. Verdict correct, citation should be replaced.
2. **Upload filename — correctly killed.** Confirmed: `UploadController` is a 3-line pass-through and every control lives in `UploadService`.

---

## 160. The four controllers §152 recorded as unexamined — now audited

| Controller | Ruling | Executable evidence |
|---|---|---|
| **ConversionWebhook** | clean | Signature verification is real and correctly **ordered**: `verifySignatureOrReject` (`:334-340`) runs at `:209` / `:254` before any dispatch, over the raw body string (`@RequestBody String rawPayload`), and rejects uniformly `INVALID_WEBHOOK_SIGNATURE` 401 whether the workspace failed to resolve, has no secret, or the HMAC mismatched — so no enumeration oracle. Idempotency key is namespaced by the verified `workspaceId` (`:366-371`), closing cross-tenant replay. |
| **ConversionWebhookSecret** | clean | No secret-read endpoint exists. `generate:50-55` and `revoke:63-68` both call `brandContextService.requireBrandWorkspace(principal)` first and scope to `workspace.getId()`; plaintext is returned only from `generate`. `/webhook-secret` is absent from `SecurityConfig`'s permit list, so it is authenticated. |
| **DeliverableMetric** | clean, **no IDOR** | `DeliverableMetricService.submit:91-94` — `if (!collaboration.getCreatorId().equals(principal.getUserId())) throw FORBIDDEN`. Plus a status gate `:96-101` and a proof-key ownership check `:107-112` (`ProofObjectKeys.isOwnedByCreator`). |
| **Portfolio (other 6)** | **no IDOR, but two new defects on the public pair** | The five `/me/portfolio*` endpoints take `@AuthenticationPrincipal` only and expose **no user-supplied id** — IDOR is structurally impossible. `uploadCover` re-verified: sniff + family match (`:640-664`), malware gate `:323`, `LimitedInputStream` `:669`. The two public endpoints are where the defects are — §161, §162. |

---

## 161. UX-2 — `GET /portfolio/{username}` is also unthrottled, and it is a **write** (MEDIUM)

| Field | Detail |
|-------|--------|
| **Severity** | MEDIUM — unauthenticated, unthrottled, **inserts a DB row per request** |
| **Where** | `AuthRateLimitFilter.bucketFor:277-291` · `PortfolioService.recordPublicView:151-163` |

`bucketFor`'s GET branch matches only `/meta/oauth/*`, `/shopify/oauth/*`, `/track/click*`, `/creators`, `/creators/search`, then **`return null` at `:290`**. `/portfolio/*` is not among them. `SecurityConfig:156-157` makes `GET /portfolio/*` `permitAll`.

`PortfolioController.getPublic:47` then calls `portfolioService.recordPublicView(username)`, which at `:156-162` executes an unconditional `portfolioEventRepository.save(...)` — **one new `portfolio_events` row per anonymous GET, with no dedup, no visitor key, no cap.**

Two consequences: unbounded storage growth driven by an unauthenticated caller, and the creator's "Page views (30d)" figure (`computePageViews:285-296`, surfaced by `analytics`) is **trivially forgeable by anyone who can curl the page.**

**This directly corrects §151 and §156**, both of which state `/portfolio/*/contact` is the *only* unbucketed public endpoint. It is not — and the other one is the one that writes.

**Fix:** the same `bucketFor` addition UX-1 needs, extended to the GET, plus visitor-keyed dedup on `recordPublicView`.

## 162. UX-3 — `captchaToken` is a phantom control, and `SecurityConfig` documents an anti-spam gate that does not exist (LOW)

`PortfolioDtos:103` declares `String captchaToken` on `PortfolioContactRequest`. A repo-wide search across `influora-api/src` and `influora-frontend/src` returns **that declaration and nothing else** — no verifier, no service read, no frontend sender. `PortfolioController.contact:59` forwards only `name`, `email`, `message`; `PortfolioService.contact:337-373` contains only field validation.

Meanwhile `SecurityConfig:154` (comment) reads: *"Server-side anti-spam on contact is enforced in PortfolioService, not here."* **It is not.** That sentence is the reason a reviewer would stop looking — and it is exactly the stale-comment pattern that produced Part 13's false CRITICAL, this time working in the opposite direction: a comment concealing a real gap rather than inventing one.

Severity LOW on its own; it is the *documentation* half of UX-1 and should be fixed in the same change (delete the field or implement it, and correct the comment).

## 163. Ruling on UX-1 — **REAL. MEDIUM confirmed.**

Every leg re-verified as executable code:

- **No bucket:** `bucketFor`'s POST branch (`:293-368`) matches no `/portfolio` path and **returns `null` at `:369`**. Confirmed the "one hit, a comment at `:134`" claim — `grep -i portfolio` over the filter returns exactly that line.
- **No other throttle exists.** I enumerated every filter and interceptor in the codebase (`CorrelationIdFilter`, `PlanGateFilter`, `PlanGateInterceptor`, `AnalyticsUsageCapInterceptor`, `JwtAuthenticationFilter`, `InternalServiceTokenFilter`, `AuthRateLimitFilter`). None throttles this path; there is no `@RateLimit` annotation and no Bucket4j dependency. `PortfolioService.contact:342-353` contains **only** field validation — no honeypot, no per-IP counter, no captcha (see UX-3).
- **The outbound notification is real.** `:369` publishes `PortfolioContactEvent`; `NotificationListener:373-387` is an `@Async @TransactionalEventListener(AFTER_COMMIT)` handler calling `notificationService.notify(..., emailOf(event.userId()), "portfolio.contact", ...)` with the attacker-supplied `senderName`/`message` in the payload. One email per accepted request.
- **MEDIUM is right.** The recipient is pinned by `{username}` (`:338`) so this is not an open relay; `@Valid` is present at `PortfolioController:56`, so `@Size(max=100)` / `@Size(max=2000)` do fire, capping amplification per message. Not HIGH. But it is unauthenticated, unbounded in *rate*, and carries real per-request cost — not LOW.

---

## 164. Corrections to §154's NOT-CHECKED list

| §154 entry | Status after this pass |
|-------------|------------------------|
| "Whether `validateMime`'s allowlist is appropriately narrow" | **CLOSED — it is.** Closed 7-format magic-byte set; SVG/HTML/JS structurally unreachable. §158.1 |
| "Service delegates for ConversionWebhook, ConversionWebhookSecret, DeliverableMetric, 6 Portfolio endpoints" | **CLOSED.** Three clean; Portfolio's public pair yielded UX-2 and UX-3. §160 |
| ClamAV configured in the deployed profile | Still open — profile selection not traced to a deployed env |
| R2 `Content-Disposition` on the serve path | Still open. Noted: `uploadCover` stores the **client-declared** content type (`PortfolioService:325-328`) where `UploadService:94` stores the **sniffed** one. Capped to `image/*` by `:642` and family-matched by `:654`, so not exploitable today — but an inconsistency worth aligning |
| JWKS private-key provisioning at runtime | Still open |
| Admin surface (32 controllers) | Still open — unaudited |

## 165. Corrected Part 14 register

| ID | Surface | Severity | Evidence | Summary |
|----|---------|----------|----------|---------|
| **UX-1** | Portfolio | MEDIUM | **verified** | `POST /portfolio/*/contact` — public, unauthenticated, in no bucket; each request sends a creator email. Every leg re-confirmed executable |
| **UX-2** | Portfolio | MEDIUM | **verified, NEW** | `GET /portfolio/{username}` — equally unbucketed, and inserts a `portfolio_events` row per anonymous request. Forgeable analytics + unbounded growth. Refutes §151/§156's "single public endpoint" claim |
| **UX-3** | Portfolio | LOW | **verified, NEW** | `captchaToken` accepted and read by nothing; `SecurityConfig:154` claims an anti-spam gate in `PortfolioService` that does not exist |

**Part 14 revised: 3 defects across 24 endpoints, all on the two public portfolio endpoints.** The six deep verdicts stand; the four "unexamined" controllers are now examined, and three of the four are clean.

## 166. Closing

§156 claimed this was the cleanest part of fourteen because its surfaces carry named adversarial review. That pattern **holds** — Upload, JWKS, ClientError and the conversion webhook all trace to a red-team fix and all survived attack. The three defects sit on the two endpoints that carry **no** such marker: the public portfolio pair, opened in `SecurityConfig` for a product reason, with a comment asserting a control that was never built.

The part's own closing sentence — *"it was not designed unthrottled; it was missed"* — is correct, and applies once more than it knew.

---

*Part 14 verification by Kabir. Static trace of `influora-api` on `fix/brand-audit-remediation`, scoped to `git ls-files`. Every citation above is an executable line unless labelled otherwise. No live server was probed.*

