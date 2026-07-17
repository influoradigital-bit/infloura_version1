# P2-7 — Admin campaign-monitoring controller

**Owner:** Vikram (backend) → Ananya (wire FE) · **Reviewers:** Kavya → Meera · **Priority:** P2 · **Depends on:** P0-1
**Status:** ✅ DONE (Meera verified 2026-07-13)

## Goal
No `AdminCampaignController` (campaign-monitoring) exists → `useCampaignList` serves a hardcoded fixture. Build endpoint, wire UI.

## Files
- **Backend (Vikram):** create `influora-api/src/main/java/com/influora/web/admin/AdminCampaignController.java`
- **Frontend (Ananya):** `src/admin/hooks/useCampaignList.ts` (remove MOCK; move sort/filter to server params if provided)

## Acceptance criteria
- [x] `GET /api/v1/admin/campaigns` (or agreed path) returns real list
- [x] `useCampaignList` off mock
- [x] Kavya QA ✅ PASS (2026-07-13)
- [x] Meera verify ✅ PASS (2026-07-13)

## Completion log
**2026-07-12 — Vikram (backend complete)**

**Created files:**
1. `influora-api/src/main/java/com/influora/web/dto/admin/AdminCampaignDtos.java`
   - `CampaignSummaryDto` record matching FE `CampaignSummary` type (src/admin/types/admin.types.ts:431)
   - Includes all fields: id, name, brandName, type, status, budget, spent, creatorCount, deliverablesPending, deliverablesApproved, slaBreachRate, createdAt, endsAt

2. `influora-api/src/main/java/com/influora/service/admin/AdminCampaignService.java`
   - `list(AuthPrincipal)` method returning all campaigns platform-wide
   - Role-guarded: SUPPORT/ADMIN/SUPER_ADMIN with MFA satisfied
   - Bulk-loads workspace names (no N+1 queries)
   - MVP: real campaign rows, placeholder aggregates (spent=0, creatorCount=0, etc.) — computed aggregates flagged as follow-up

3. `influora-api/src/main/java/com/influora/web/AdminCampaignController.java`
   - Mounted at `/admin/campaigns` → full path `/api/v1/admin/campaigns`
   - `GET /admin/campaigns` endpoint (no pagination/filters/sort params this cycle)
   - Returns unwrapped DTOs (matches `apiRequest()` contract in api-contracts.ts)

**Security implementation:**
- ✅ Role-based access: `requireRoleWithMfaSatisfied(SUPER_ADMIN, ADMIN, SUPPORT)`
- ✅ MFA enforcement for ADMIN/SUPER_ADMIN tiers
- ✅ Same access pattern as AdminDashboardController (operational monitoring surface)

**API contract:**
- ✅ Path: `GET /api/v1/admin/campaigns`
- ✅ Response: `List<CampaignSummaryDto>` (unwrapped, no ApiResponse envelope)
- ✅ Matches FE `CampaignSummary` type exactly

**Acceptance criteria met:**
- [x] `GET /api/v1/admin/campaigns` returns real campaign list
- [x] Role-guarded (SUPPORT/ADMIN/SUPER_ADMIN, MFA satisfied)
- [x] No stubs/TODOs in controller (service has TODOs for future aggregate computation)

**Next steps for Ananya:**
- Wire `src/admin/hooks/useCampaignList.ts` to real `campaignApi.list()` call
- Remove mock data (lines 24-148)
- Keep client-side sort/filter logic (lines 208-243) — server-side params not implemented this cycle

**QA handoff → Kavya:**
Files ready for review. Note: aggregates (spent, creatorCount, slaBreachRate) return zeros for MVP — acceptance only requires real campaign rows.

### 2026-07-13 — Vikram (Frontend wiring)

Wired `useCampaignList.ts` to the real `AdminCampaignController` endpoint.

**Contract gap found and fixed:** the pre-existing `campaignApi.list()` in `api-contracts.ts` assumed a paginated envelope (`PaginatedResponse<CampaignSummary>` — `{data, total, page, pageSize, totalPages}`) with server-side filter/page/pageSize query params. The actual MVP backend (`GET /admin/campaigns`) takes no params and returns a plain `CampaignSummaryDto[]` JSON array (per its class javadoc — "no pagination/filters/sort params yet"). `campaignApi.list()` was unused anywhere in the codebase, so this mismatch had never surfaced. Added a new `campaignApi.listAll()` method (`apiRequest<CampaignSummary[]>('/campaigns')`) that matches the real contract, and wired `useCampaignList` to that instead.

**Changes:**
- `src/admin/services/api-contracts.ts` — added `campaignApi.listAll()`.
- `src/admin/hooks/useCampaignList.ts` — removed the 120-line mock fixture and the `setTimeout`-simulated loading hook; now uses `useQuery(['admin','campaigns'], campaignApi.listAll)`. Kept the existing client-side search/status/type/brand/atRisk filter and multi-field sort logic unchanged (still operates on the full returned list).

**Verification:**
- `npx tsc --noEmit -p .` — clean, exit 0.
- `mvn -o compile` (influora-api) — BUILD SUCCESS, no regression.

**2026-07-13 — Kavya (QA Review):** ✅ PASS — contract gap correctly resolved via new `campaignApi.listAll()` for unwrapped array (MVP), cleanly separated from pre-existing unused `list()` (future paginated envelope). Frontend mock removed, client-side logic preserved (acceptable for MVP). Security ✅ (role-guarded SUPPORT/ADMIN/SUPER_ADMIN, MFA-satisfied). TECH-STACK ✅ (no violations). Full review: `wiki/errors/P2-7-qa-review.md`.

**Next:** Meera runtime verify (`npm run build` + `mvn -o test` + dev server/curl if possible).

- **2026-07-13 — Meera (Local Verification):** ✅ PASS.
  - `mvn -o test` (same run as P2-6, log: `meera-mvn-test-2026-07-13.log`): **890 run / 11 failures / 9 errors** — identical to P0-1 baseline, no regression from `AdminCampaignController`/`AdminCampaignService`.
  - `npx tsc --noEmit -p .`: exit 0 (covers `useCampaignList.ts` + `campaignApi.listAll()`).
  - `npm run build`: exit 0, built in 1m17s.
  - **VERDICT: ✅ DONE — no regressions, safe to close.**
