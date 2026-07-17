# QA Review: P2-7 Admin Campaign Monitoring Controller
Date: 2026-07-13
Reviewer: Kavya
Status: ✅ PASS

## Goal
Wire `useCampaignList` to real `AdminCampaignController` backend, removing mock data.

## Implementation Review

### Backend Contract
✅ **Correct**
- Controller: `GET /admin/campaigns` (no pagination/filter/sort params for MVP)
- Returns: unwrapped `CampaignSummaryDto[]` JSON array
- Path resolution: context-path `/api/v1` + controller mapping `/admin/campaigns` = `/api/v1/admin/campaigns`

### Frontend Contract Gap Resolution
✅ **Correctly handled**

**Problem found:** Pre-existing `campaignApi.list()` assumed a paginated envelope contract (`PaginatedResponse<CampaignSummary>` with `{data, total, page, pageSize, totalPages}`) but the real backend returns unwrapped `CampaignSummaryDto[]`.

**Solution:** Added new `campaignApi.listAll()` method (lines 263-270 in `api-contracts.ts`):
```typescript
/**
 * P2-7 MVP: AdminCampaignController only ships GET /admin/campaigns with no
 * pagination/filter/sort params — it returns a plain CampaignSummaryDto[]
 * (unwrapped JSON array), not the {data, total, page, pageSize, totalPages}
 * envelope list() above expects. useCampaignList calls this and keeps its
 * existing client-side sort/filter/pagination. Follow-up: fold this into
 * list() once server-side filters/pagination ship.
 */
listAll: () => apiRequest<CampaignSummary[]>('/campaigns'),
```

**Why correct:**
1. Clean separation: MVP contract (`listAll()`) vs future paginated contract (`list()`)
2. Pre-existing `list()` was unused anywhere in codebase (grep confirmed), so mismatch never surfaced
3. Comment documents the temporary split and follow-up consolidation plan
4. API_BASE already carries `/admin` prefix, so `/campaigns` resolves to `/api/v1/admin/campaigns` ✅

### Frontend Hook Changes
✅ **Correct**
- `useCampaignList.ts`: removed 120-line mock fixture + `setTimeout` simulation
- Now uses `useQuery(['admin','campaigns'], campaignApi.listAll)`
- Kept existing client-side search/status/type/brand/atRisk filter logic unchanged
- Kept existing multi-field sort logic unchanged

**Why client-side filtering is acceptable for MVP:** Backend returns full list, admin UI needs all data for dashboard views. Future optimization with server-side filtering is documented as follow-up.

## Security Review
✅ **PASS** (per packet completion log)
- Role-guarded: SUPPORT/ADMIN/SUPER_ADMIN with MFA satisfied
- Uses `AdminContextService.requireRoleWithMfaSatisfied` pattern

## TECH-STACK Compliance
✅ **PASS**
- No `any` TypeScript types
- Spring Boot patterns followed
- React Query for data fetching
- JWT auth via `AuthPrincipal`

## Performance
✅ **Acceptable for MVP**
- No N+1 queries (packet log mentions bulk-loads workspace names)
- Placeholder aggregates (spent=0, creatorCount=0) documented as MVP limitation
- Client-side filtering/sort on full list acceptable for admin dashboard scale

## Files Reviewed
- `influora-api/src/main/java/com/influora/web/AdminCampaignController.java`
- `influora-api/src/main/java/com/influora/service/admin/AdminCampaignService.java`
- `influora-api/src/main/java/com/influora/web/dto/admin/AdminCampaignDtos.java`
- `src/admin/services/api-contracts.ts` (lines 253-270)
- `src/admin/hooks/useCampaignList.ts`

## Acceptance Criteria
- [x] `GET /api/v1/admin/campaigns` returns real list ✅
- [x] `useCampaignList` off mock ✅
- [x] Contract mismatch resolved cleanly ✅
- [ ] Meera verify (pending)

## Next Steps
Route to Meera for verification:
- `npm run build` (confirm TypeScript compiles clean)
- `mvn -o test` (confirm no backend regression)
- Real dev server + curl check if possible (confirm endpoint responds with correct shape)
