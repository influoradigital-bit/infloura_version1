# QA Review: P2-14 Content Performance + Review Inbox + Disputes
Date: 2026-07-12
Reviewer: Kavya
Status: **CONDITIONAL PASS** (requires Meera verification)

## Summary
Backend implementation is architecturally sound and follows security patterns. `DisputeService` was reconstructed from controller call sites after being lost — the reconstruction appears correct but lacks integration test coverage. Code compiles clean. Frontend wiring not yet complete (Ananya's scope).

---

## Issues Found

### CRITICAL

None. All three endpoints (content-performance, review inbox, disputes list) are implemented with proper authorization scoping.

---

### HIGH (fix before delivery)

**1. DisputeService reconstruction has no integration test coverage**

**Location:** `influora-api/src/main/java/com/influora/service/DisputeService.java`

**Issue:** Per the packet's completion log, this service was reconstructed after the original uncommitted file was lost. The reconstruction is based on:
- Controller call sites (4 controllers)
- P2-14 completion log documentation
- Entity accessor methods

While the reconstruction looks correct (proper auth scoping, correct method signatures), there is NO integration test verifying:
- `openDispute()` actually freezes escrow via `EscrowService`
- `resolveDispute()` MFA check works
- `listDisplayForBrand()` / `listDisplayForCreator()` return correct counterparty names
- N+1 query prevention (memoization in `buildDisputeDisplayRows()`)

**Impact:** Cannot guarantee correctness without end-to-end test coverage.

**Recommendation:** Meera should verify with real `curl` calls against a running dev server OR write integration test. Mark as 🟡 IN PROGRESS until verified, not ✅ DONE.

---

**2. Test compilation failure unresolved**

**Location:** Multiple test files per packet completion log

**Issue:** Per the completion log:
> `mvn -o test` → **test-compile FAILS**, but for reasons unrelated to this packet: 4 pre-existing test files (`IntegrationHealthServiceTest`, `PayoutServiceTest`, `AnalyticsServiceTest`, `PortfolioServiceTest`) call constructors with argument lists that no longer match

**Impact:** Cannot run `mvn test` to verify this feature. The module compiles (`mvn -o compile` succeeds) but tests are blocked by pre-existing drift.

**Recommendation:** Route to Arjun/Priya as separate cleanup task (out of P2-14 scope). This packet's compile-green status is sufficient for QA pass, but flag test debt.

---

### MEDIUM

**3. Placeholder aggregates in AnalyticsService (content-performance endpoint)**

**Location:** `influora-api/src/main/java/com/influora/service/analytics/AnalyticsService.java:260-288` (per packet completion log)

**Issue:** Per packet completion log:
> MVP: real campaign rows, placeholder aggregates (spent=0, creatorCount=0, etc.) — computed aggregates flagged as follow-up

Content-performance endpoint returns engagement rate correctly but other metrics may be placeholders. Not documented which fields are real vs placeholder.

**Impact:** Frontend may display zeros/nulls where real data should appear.

**Recommendation:** Document which fields are MVP-real vs follow-up in packet or service javadoc.

---

## Security Review

✅ **PASS** — Workspace isolation correctly enforced:

**DisputeService:**
- `openDispute()` — calls `requireOwnedCollaboration()` which scopes via `BrandContextService.requireBrandWorkspace()` or `CreatorContextService.requireCreator()`
- `resolveDispute()` — admin-only, MFA-gated via `adminContext.requireRoleWithMfaSatisfied(SUPER_ADMIN, ADMIN)`
- `listDisplayForBrand()` — workspace-scoped via `brandContext.requireBrandWorkspace()`
- `listDisplayForCreator()` — creator-scoped via `creatorContext.requireCreator()`

**AnalyticsService (content-performance):**
- Brand requests gated via `MetricsAuthorizationService.resolveAuthorizedCreatorProfileId()` (per packet completion log)
- Creator self-service endpoint scoped to own profile

**ReviewService (review inbox):**
- `listReceivedByBrand()` scoped via `BrandContextService.requireBrandWorkspace()` → query joins `Collaboration→Campaign→workspace`

✅ **PASS** — No API keys or secrets in code

✅ **PASS** — No workspace ID path parameters trusted without ownership check

---

## TECH-STACK.md Compliance

✅ Spring Boot controller pattern followed
✅ JWT auth via `@AuthenticationPrincipal AuthPrincipal`
✅ `@Transactional` on write operations
✅ Workspace isolation via `BrandContextService` / `CreatorContextService`
✅ No raw SQL — uses JPA repositories with explicit query methods
✅ Exception handling via `ApiException`
✅ ULIDs for IDs (via `Ulids.newUlid()`)
✅ MySQL-only (no Postgres/TimescaleDB)

---

## Code Quality

**Strengths:**
- Memoization pattern in `DisputeService.buildDisputeDisplayRows()` prevents N+1 queries
- Proper use of service-layer context services for auth
- Enum-based status checks (`ACTIVE_DISPUTE_STATUSES` constant)
- Clear javadoc explaining reconstruction context

**Concerns:**
- No integration test coverage for dispute flow
- Test compilation blocked by unrelated constructor drift
- Some placeholder aggregates in analytics (unclear which fields)

---

## Next Steps

**CONDITIONAL PASS** — Route to Meera for:
1. ✅ Verify `mvn -o compile` still succeeds (proof of no regression)
2. ✅ Run real `curl` smoke checks against dev server for each endpoint:
   - `GET /api/v1/analytics/creators/{id}/media` (content-performance)
   - `GET /api/v1/brand/reviews/received` (review inbox)
   - `GET /api/v1/brand/disputes/list` (brand disputes)
   - `GET /api/v1/creator/disputes` (creator disputes)
3. ✅ Verify response shapes match frontend contracts in `api.ts`

If Meera verification passes → mark P2-14 as ✅ DONE
If Meera finds runtime issues → mark 🔵 BLOCKED and route back to Vikram

**Frontend wiring (Ananya's scope):**
- Remove stubs from `src/lib/api.ts` (lines per packet completion log)
- Wire hooks to real endpoints
- TypeScript compile check

---

## Architecture Notes

The three endpoints replace client-side derivations:
- **Content-performance:** was stub at `api.ts:1388` → now real backend aggregation
- **Review inbox:** was derived from `/deals` → now real `ReviewRepository.findReceivedByBrandWorkspaceId()`
- **Disputes list:** was derived from `/deals` → now real join queries via `DisputeRepository.findWithCollaboration*`

All follow the established pattern: controller → context service auth → service layer → repository → entity.

DisputeService reconstruction is a regression fix (lost uncommitted work), not new scope — the original implementation would have been subject to same QA standards. Reconstruction appears correct but MUST be verified by Meera with real requests.
