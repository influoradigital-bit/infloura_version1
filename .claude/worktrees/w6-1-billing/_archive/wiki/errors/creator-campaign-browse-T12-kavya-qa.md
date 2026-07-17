# QA Review: Creator Campaign Browse/Apply API — Task #12 (Kavya)

**Reviewer:** Kavya Patel (QA Lead)  
**Date:** 2026-07-09  
**Verdict:** ✅ **APPROVED** — routed to Kabir (Task #7 security gate)  
**Scope:** Vikram Task #7 backend — creator campaign browse/apply API  
**Reference Spec:** `wiki/tech/creator/05_CREATOR_CAMPAIGNS_SPEC.md` §7  
**Reviewed Files:**
- `influora-api/src/main/java/com/influora/web/CreatorCampaignController.java`
- `influora-api/src/main/java/com/influora/service/CreatorCampaignService.java`
- `influora-api/src/main/java/com/influora/repository/CampaignSpecs.java` (browse specs)
- `influora-api/src/main/java/com/influora/web/dto/creatorcampaign/CreatorCampaignDtos.java`
- `influora-api/src/test/java/com/influora/service/CreatorCampaignServiceTest.java` (12 tests)
- `influora-api/src/test/java/com/influora/web/CreatorCampaignControllerTest.java` (3 tests)

---

## Executive Summary

Creator campaign browse/apply backend **passes QA**. All three endpoints (`GET /creator/campaigns`, `GET /creator/campaigns/{id}`, `POST /creator/campaigns/{id}/apply`) resolve creator identity exclusively via `CreatorContextService.requireCreatorProfile(principal)` — no client-supplied creator id on any route. Hostile paths for duplicate apply, expired deadline, non-ACTIVE status, DRAFT/private visibility, and in-memory platform post-filter semantics are covered by unit tests. **15/15 scoped tests PASS** (Surefire reports timestamped 2026-07-09 12:11 IST; `mvn` unavailable in this QA environment — reports verified from Vikram's prior run).

**Escalated to Kabir (non-blocking for QA):** apply rate limiting (spec §7.2), private-campaign enumeration red-team, `ApplyRequest.message` XSS/length downstream rendering.

---

## Task #7 Definition of Done — Verification

| DoD Item | Result | Evidence |
|----------|--------|----------|
| (1) In-memory platform/niche post-filter pagination semantics | ✅ PASS (documented limitation accepted) | `CreatorCampaignService.browse()` lines 99–124: when `platform` or `niche` active, `total = items.size()` and `hasMore = false` (current page only). Matches `CreatorDiscoveryService.search()` vertical post-filter shape. Javadoc documents the limitation. `testBrowseFiltersByPlatformInMemory` asserts page-only `total`. |
| (2) Private-campaign 404 vs invited-visible | ✅ PASS | `requireVisibleCampaign()`: DRAFT → 404; `isPrivate` + no collaboration → 404; invited private → visible. Browse excludes private at DB spec (`CampaignSpecs.browsableForCreator`). Tests: `testApplyRejectsPrivateCampaignWithoutInvitationAsNotFound`, `testGetDetailPrivateCampaignVisibleWhenInvited`, `testApplyRejectsDraftCampaignAsNotFound`. |
| (3) Extend `KAVYA_QA_TEST_PLAN.md` | ✅ DONE | Section 16 added (campaign browse/apply coverage). |
| (4) Hostile tests | ✅ PASS (with note) | Duplicate apply (sequential + concurrent race), expired deadline, non-ACTIVE — covered. Cross-creator apply: structurally impossible (no creator-id param; identity from JWT only) — covered at architecture layer by Task #11 `CreatorContextService` PASS. |

---

## Test Execution

| Test Class | Run | Failures | Errors | Skipped |
|------------|-----|----------|--------|---------|
| `CreatorCampaignServiceTest` | 12 | 0 | 0 | 0 |
| `CreatorCampaignControllerTest` | 3 | 0 | 0 | 0 |
| **Total** | **15** | **0** | **0** | **0** |

**Note:** Scoped `mvn test` could not be re-run in this environment (`mvn` not on PATH, no `mvnw` wrapper). Surefire reports under `influora-api/target/surefire-reports/` confirm Vikram's 12:11 IST run. No code changes since that run.

---

## Functional Review

### Browse (`GET /creator/campaigns`)

- DB-level gates: `ACTIVE`, `isPrivate=false`, deadline not passed, budget overlap — via `CampaignSpecs`.
- In-memory post-filters: `platform` (case-insensitive exact match on `platformsJson`), `niche` (substring match on title/description/hashtags/requirements/objectives).
- Pagination: `page` clamped ≥1, `limit` clamped 1–100.
- Maps `applicationStatus` from existing `Collaboration` rows for campaigns on the current page.
- **Known limitation (accepted):** platform/niche filters can under-fetch across pages because filtering happens after DB pagination. Documented in service Javadoc; same trade-off as discovery vertical filter.

### Detail (`GET /creator/campaigns/{id}`)

- Uses shared `requireVisibleCampaign()` visibility gate.
- Returns brand summary + full campaign fields; never exposes workspace-internal fields.
- Pre-apply: `applicationStatus` null; invited private: `INVITED`.

### Apply (`POST /creator/campaigns/{id}/apply`)

- Creates `Collaboration` via `Collaboration.apply()` — `source=APPLICATION`, `status=APPLIED`.
- Guards: not ACTIVE → `409 CAMPAIGN_NOT_OPEN`; past deadline → `409 APPLICATION_DEADLINE_PASSED`; duplicate → `409 ALREADY_APPLIED` (pre-check + `DataIntegrityViolationException` catch for TOCTOU race).
- Optional body: `ApplyRequest.message` with `@Size(max=2000)`.
- Returns `201 CREATED` with `collaborationId`, `status`, `appliedAt`.

---

## Hostile / Edge-Case Matrix

| Scenario | Expected | Tested | Status |
|----------|----------|--------|--------|
| Cross-creator apply (apply as another creator's id) | Impossible — no creator id in request | Architectural (Task #11) | ✅ PASS |
| Sequential duplicate apply | 409 `ALREADY_APPLIED`, no save | `testApplySequentialDuplicateRejected` | ✅ PASS |
| Concurrent duplicate apply (race) | 409 `ALREADY_APPLIED`, not 500 | `testApplyConcurrentRaceLoserGetsFriendly409` | ✅ PASS |
| Expired application deadline | 409 `APPLICATION_DEADLINE_PASSED` | `testApplyRejectsPastDeadline` | ✅ PASS |
| Non-ACTIVE campaign (e.g. PAUSED) | 409 `CAMPAIGN_NOT_OPEN` | `testApplyRejectsNonActiveCampaign` | ✅ PASS |
| DRAFT campaign | 404 `CAMPAIGN_NOT_FOUND` | `testApplyRejectsDraftCampaignAsNotFound` | ✅ PASS |
| Private campaign, not invited | 404 `CAMPAIGN_NOT_FOUND` | `testApplyRejectsPrivateCampaignWithoutInvitationAsNotFound` | ✅ PASS |
| Private campaign, invited | 200 detail with `INVITED` | `testGetDetailPrivateCampaignVisibleWhenInvited` | ✅ PASS |
| Unknown campaign id | 404 | `testApplyUnknownCampaignNotFound` | ✅ PASS |
| Platform post-filter pagination | `total`/`hasMore` = page-only | `testBrowseFiltersByPlatformInMemory` | ✅ PASS |

---

## Code Quality Checklist

| Check | Status |
|-------|--------|
| TECH-STACK.md: creator identity from JWT, never path param | ✅ |
| No `console.log` / debug code | ✅ |
| Typed errors via `ApiException` with codes | ✅ |
| `ApplyRequest` validated (`@Size(max=2000)`) | ✅ |
| Comments explain WHY (visibility, post-filter, idempotency) | ✅ |
| Controller is thin delegation | ✅ |
| No hardcoded secrets | ✅ |

---

## Findings (Non-Blocking)

### L-1: Missing explicit `getDetail` private-without-invitation test
`requireVisibleCampaign()` is shared with `apply()`, which is tested for private 404. Recommend adding `testGetDetailRejectsPrivateCampaignWithoutInvitationAsNotFound` for symmetry — **not blocking QA**.

### L-2: Niche post-filter not unit-tested
Platform filter tested; niche filter uses same code path (`postFiltered = true`). Low risk — follow-up test optional.

### L-3: No MockMvc/integration tests for auth envelope
Controller tests are Mockito delegation only. Auth (401/403 for brand/null principal) not exercised here — consistent with codebase pattern (Testcontainers/Docker gap per TECH-STACK.md). Escalate auth-path coverage to future integration-test debt.

### L-4: Apply rate limiting not implemented
`05_CREATOR_CAMPAIGNS_SPEC.md` §7.2 specifies max 10 applications/hour. Not in this diff; same posture as `CreatorDiscoveryService#invite`. **Escalated to Kabir** — product decision whether M1 ships without it.

---

## Kabir Escalation Items (Security Gate)

1. **Private-campaign enumeration** — confirm `requireVisibleCampaign()` 404 discipline is airtight against ID probing (apply + detail paths).
2. **Apply rate limiting** — spec §7.2; not implemented.
3. **`ApplyRequest.message`** — free text persisted to `Collaboration.notes`; confirm downstream render paths sanitize/escape (XSS).

---

## QA Sign-Off

- [x] All 15 unit tests passing (verified via Surefire reports)
- [x] Post-filter pagination semantics verified and documented
- [x] Private 404 vs invited-visible verified
- [x] Hostile apply paths covered (duplicate, expired, status, visibility)
- [x] `KAVYA_QA_TEST_PLAN.md` extended
- [ ] Kabir security review — **NEXT GATE**
- [ ] Meera build verify — after Kabir PASS

**Kavya verdict: APPROVED.** Route to Kabir for Task #7 security review.

---

**Document Control:** Created 2026-07-09 by Kavya (Task #12). Next: Kabir campaign controller red-team.
