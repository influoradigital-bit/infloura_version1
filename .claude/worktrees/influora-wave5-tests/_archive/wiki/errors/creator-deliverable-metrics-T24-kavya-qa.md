# QA Review: Creator Deliverable Metrics API — Task #24 (Kavya)

**Reviewer:** Kavya Patel (QA Lead)  
**Date:** 2026-07-09 (~21:00 IST)  
**Verdict:** ✅ **APPROVED** — routed to Kabir (metrics surface + T19–T20 carry-forward) → Meera build  
**Scope:** Vikram Task #24 — `POST /creator/deliverables/{id}/metrics`  
**Reference:** `wiki/tech/creator/09_CREATOR_DELIVERABLES_SPEC.md` §4.6; Priya `CREATOR_EXEC_PLAN_PRIYA.md` §1.3 lean entity + milestone-keyed `deliverable_metrics`  
**Reviewed Files:**
- `influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java` — `reportMetrics`, `canReportMetrics`, `calculateEngagementRate`, `validateNonNegativeMetrics`
- `influora-api/src/main/java/com/influora/web/CreatorDeliverableController.java` — `POST /{deliverableId}/metrics`
- `influora-api/src/main/java/com/influora/web/dto/deliverable/CreatorDeliverableDtos.java` — `MetricsPayload`, `MetricsReportRequest`, `MetricsReportResponse`
- `influora-api/src/main/java/com/influora/domain/entity/Deliverable.java` — `applyMetricsReport()`
- `influora-api/src/main/java/com/influora/domain/entity/DeliverableMetric.java` — lean milestone-keyed row + `applyReport()`
- `influora-api/src/main/java/com/influora/repository/DeliverableMetricRepository.java` — `findByMilestoneId`
- `influora-api/src/main/java/com/influora/repository/DeliverableRepository.java` — `findByIdAndCreatorUserId`
- `influora-api/src/main/java/com/influora/service/CreatorContextService.java`
- `influora-api/src/test/java/com/influora/service/CreatorDeliverableServiceTest.java` (24 tests, +6 metrics)
- `influora-api/src/test/java/com/influora/web/CreatorDeliverableControllerTest.java` (5 tests, +1 metrics)

---

## Executive Summary

Task #24 **passes QA**. `reportMetrics` lets a creator report self-declared post performance on an owned deliverable, persists aggregated numbers to the existing lean `deliverable_metrics` table (keyed by `milestone_id`), transitions the deliverable row to `METRICS_REPORTED`, and returns the spec-shaped response with computed engagement rate and `verificationStatus: PENDING`.

Access isolation matches Tasks #19–#21: `CreatorContextService.requireCreatorProfile` → `DeliverableRepository.findByIdAndCreatorUserId` (collaboration join-through on `collaborations.creator_id`). No path-param creator id is trusted. Foreign deliverable → uniform `DELIVERABLE_NOT_FOUND` 404.

State gate allows `APPROVED`, `POSTED`, and `METRICS_REPORTED` (idempotent re-report). Invalid states (`DRAFT`, `SUBMITTED`, etc.) fail closed with `INVALID_STATE` 409. Deliverables without `milestone_id` fail with `MILESTONE_NOT_LINKED` 409.

**29 scoped unit tests** authored (24 service + 5 controller). **`mvn` not on PATH** in this QA environment — Meera must confirm **29/29 PASS**.

**Kabir carry-forward:** IDOR posture unchanged from Task #19 PASS; proof-screenshot key injection surface; no metrics rate limit (same posture as M-19-2/M-20-9).

**Ananya follow-up (out of backend scope):** `api.creatorDeliverables.reportMetrics` client exists in `api.ts`; UI wire (Task #24b) still pending. Mock engagement-rate formula diverges from backend (see L-24-8).

---

## Task #24 Definition of Done — Verification

| DoD Item | Result | Evidence |
|----------|--------|----------|
| `POST /creator/deliverables/{id}/metrics` | ✅ PASS | `CreatorDeliverableController.reportMetrics` L83–89 |
| Request: `metrics`, `proofScreenshots`, `reportedDaysAfterPosting` | ✅ PASS (partial persist) | `MetricsReportRequest` record; `reportedDaysAfterPosting` accepted but not stored (L-24-1) |
| Response: `deliverableId`, `status`, `metrics`, `engagementRate`, `verificationStatus`, `message` | ✅ PASS | `MetricsReportResponse`; message matches spec copy |
| `CreatorContextService` + ownership gate | ✅ PASS | L185–186 `requireCreatorProfile` + `requireOwnedDeliverable` |
| State: `APPROVED` / `POSTED` / `METRICS_REPORTED` → `METRICS_REPORTED` | ✅ PASS | `canReportMetrics` L310–314; `applyMetricsReport` L260–262 |
| Persists `deliverable_metrics` (milestone-keyed) | ✅ PASS | `findByMilestoneId` upsert + `applyReport` + save L221–240 |
| Engagement rate `(likes + comments) / reach × 100` | ✅ PASS | `calculateEngagementRate` L346–354; test asserts **16.3** for sample payload |
| `verificationStatus: PENDING` | ✅ PASS | Hard-coded in response L251 |
| Unit tests 24/24 + 5/5 = **29/29** | ⚠️ AUTHORED | +6 metrics service, +1 controller; not executed here (L-24-7) |
| TECH-STACK.md compliance | ✅ PASS | Thin controller, `ApiException` codes, JWT auth, `@Transactional` service, no debug code |

---

## Test Execution

| Test Class | Authored | Executed | Failures | Notes |
|------------|----------|----------|----------|-------|
| `CreatorDeliverableServiceTest` | 24 | ❌ Not run | — | +6 metrics tests; `mvn` unavailable |
| `CreatorDeliverableControllerTest` | 5 | ❌ Not run | — | +1 metrics delegation test |
| **Total** | **29** | **0** | — | **Meera gate required** |

**Command for Meera:**
```bash
cd influora-api && mvn test -Dtest=CreatorDeliverableServiceTest,CreatorDeliverableControllerTest
```

---

## Service Review: `reportMetrics`

```182:253:influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java
    @Transactional
    public MetricsReportResponse reportMetrics(
            AuthPrincipal principal, String deliverableId, MetricsReportRequest request) {
        creatorContext.requireCreatorProfile(principal);
        Deliverable deliverable = requireOwnedDeliverable(principal, deliverableId);

        if (!canReportMetrics(deliverable.getStatus())) {
            throw new ApiException(
                    "INVALID_STATE",
                    "Metrics can only be reported once the deliverable is approved or posted",
                    HttpStatus.CONFLICT);
        }

        if (request == null || request.metrics() == null) {
            throw new ApiException(
                    "INVALID_REQUEST", "metrics payload is required", HttpStatus.BAD_REQUEST);
        }

        String milestoneId = deliverable.getMilestoneId();
        if (milestoneId == null || milestoneId.isBlank()) {
            throw new ApiException(
                    "MILESTONE_NOT_LINKED",
                    "Deliverable is not linked to a payment milestone",
                    HttpStatus.CONFLICT);
        }

        MetricsPayload metrics = request.metrics();
        validateNonNegativeMetrics(metrics);
        // ... aggregate reach/impressions/engagements, upsert DeliverableMetric ...
        deliverable.applyMetricsReport();
        deliverableRepository.save(deliverable);

        Double engagementRate = calculateEngagementRate(metrics);
        return new MetricsReportResponse(
                deliverable.getId(),
                DeliverableStatus.METRICS_REPORTED,
                metrics,
                engagementRate,
                "PENDING",
                "Metrics submitted. They will be verified.");
    }
```

**Ordering:** Profile → ownership → state gate → payload gate → milestone link → validation → persist metric → transition deliverable → respond. Correct fail-fast sequence.

**Transactional integrity:** Metric row and deliverable status update share one `@Transactional` boundary — partial writes cannot leak on failure.

---

## State Machine Review

| From Status | `canReportMetrics` | To Status | Notes |
|-------------|-------------------|-----------|-------|
| `APPROVED` | ✅ | `METRICS_REPORTED` | Tested `testReportMetricsFromApproved` |
| `POSTED` | ✅ | `METRICS_REPORTED` | Tested `testReportMetricsFromPosted` |
| `METRICS_REPORTED` | ✅ | `METRICS_REPORTED` | Re-report allowed (idempotent overwrite on milestone row) |
| `DRAFT` / `PENDING` / `SUBMITTED` / `REVISION_REQUESTED` / `RESUBMITTED` | ❌ | — | `INVALID_STATE` 409 (tested DRAFT) |
| `VERIFIED` / `REJECTED` | ❌ | — | Structurally blocked — no re-report after verification |

`canReportMetrics` is wired into status endpoint action flags via `toStatusResponse` L548–551 — UI can gate the metrics form without guessing state.

**Idempotency:** Re-report on `METRICS_REPORTED` overwrites the same `deliverable_metrics` row (`findByMilestoneId` upsert) and refreshes `reported_at` via `applyReport`. Deliverable status remains `METRICS_REPORTED`. Acceptable for creator correction flow; no `Idempotency-Key` header (consistent with T20 L-20-8).

---

## `deliverable_metrics` Persistence Review

Lean schema (Priya §1.3 / P0 #3) stores **aggregates**, not per-field likes/comments:

| Request field | Persisted column | Mapping |
|---------------|------------------|---------|
| `metrics.reach` | `reach` | `> 0` else `null` |
| `metrics.impressions` or `metrics.views` | `impressions` | `impressions` preferred; `views` fallback |
| likes + comments + shares + saves | `engagements` | Summed; `> 0` else `null` |
| `deliverable.postUrl` | `link` | From deliverable row, not request body |
| `proofScreenshots[0]` (first non-blank) | `proof_screenshot_r2_key` | Single column — list truncated (L-24-2) |
| — | `reported_by_creator_id` | `principal.getUserId()` |
| — | `reported_at` / `updated_at` | `applyReport` → `Instant.now()` |

Individual `MetricsPayload` integers are **echoed in the HTTP response** but not stored column-wise — intentional lean deviation from spec §3.4 rich entity. Brand campaign analytics (`DeliverableMetricService#getCampaignAnalytics`) reads the same aggregated columns.

**Milestone gate:** Reporting requires `deliverable.milestone_id` — prevents orphan metric rows without a payment-milestone anchor. Tested `testReportMetricsMissingMilestone`.

**Cross-path note:** Legacy `PUT /deliverables/{milestoneId}/metrics` (`DeliverableMetricService`) gates on milestone `FUNDED`/`RELEASED`, not deliverable `APPROVED`/`POSTED`. The new creator-journey path is the correct gate for Week 3 deliverables slice; dual-path divergence is a Priya architecture watch item (L-24-9), not a Task #24 blocker.

---

## Engagement Rate Review

Spec §5.2 (referenced in service javadoc): `(likes + comments) / reach × 100`.

```346:354:influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java
    private static Double calculateEngagementRate(MetricsPayload metrics) {
        int reach = intOrZero(metrics.reach());
        if (reach <= 0) {
            return null;
        }
        double engaged = intOrZero(metrics.likes()) + intOrZero(metrics.comments());
        return Math.round(engaged / (double) reach * 1000.0) / 10.0;
    }
```

Sample test payload: likes=15000, comments=450, reach=95000 → **16.3%** (one decimal). Shares/saves excluded from rate — matches spec formula, not total engagements sum.

---

## Access Isolation Review

### Gate chain (unchanged from Tasks #19–#21)

1. **JWT required** — `SecurityConfig` `anyRequest().authenticated()` for `/creator/**`
2. **Creator role** — `CreatorContextService.requireCreator` → `WRONG_USER_TYPE` 403 for brand JWT
3. **Creator profile exists** — `requireCreatorProfile` → `CREATOR_PROFILE_NOT_FOUND` 404
4. **Deliverable ownership** — `findByIdAndCreatorUserId(id, principal.getUserId())` join-through `Collaboration.creatorId`

```289:298:influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java
    private Deliverable requireOwnedDeliverable(AuthPrincipal principal, String deliverableId) {
        return deliverableRepository
                .findByIdAndCreatorUserId(deliverableId, principal.getUserId())
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "DELIVERABLE_NOT_FOUND",
                                        "Deliverable not found",
                                        HttpStatus.NOT_FOUND));
    }
```

Foreign creator → uniform 404 (no enumeration). Tested: `testReportMetricsForeignDeliverable`.

---

## Controller Review

```83:89:influora-api/src/main/java/com/influora/web/CreatorDeliverableController.java
    @PostMapping("/{deliverableId}/metrics")
    public ResponseEntity<ApiResponse<MetricsReportResponse>> reportMetrics(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String deliverableId,
            @RequestBody MetricsReportRequest body) {
        return ResponseEntity.ok(
                ApiResponse.ok(creatorDeliverableService.reportMetrics(principal, deliverableId, body)));
    }
```

Thin delegation — no business logic in controller. Returns `200 OK` (consistent with submit/review mutations). `@RequestBody` required — null body surfaces Spring 400 before service (acceptable).

---

## Unit Test Matrix (Metrics)

| Test | Coverage |
|------|----------|
| `testReportMetricsFromPosted` | Happy path; status transition; engagement rate 16.3; `PENDING`; metric save |
| `testReportMetricsFromApproved` | `APPROVED` state allowed |
| `testReportMetricsInvalidState` | `DRAFT` → `INVALID_STATE`; no metric save |
| `testReportMetricsNegativeValues` | Negative likes → `INVALID_METRIC_VALUE` |
| `testReportMetricsMissingMilestone` | Null `milestone_id` → `MILESTONE_NOT_LINKED` |
| `testReportMetricsForeignDeliverable` | Empty repo → `DELIVERABLE_NOT_FOUND` |
| `testReportMetrics` (controller) | Delegation + 200 response |

**Gaps (non-blocking):** `METRICS_REPORTED` re-report idempotency, `SUBMITTED`/`VERIFIED` rejection, all-zero metrics payload, null `metrics` inner fields only, proof key persistence assertion, `ArgumentCaptor` on `DeliverableMetric` field mapping.

---

## Code Quality Checklist

| Check | Result |
|-------|--------|
| Follows TECH-STACK.md (thin controller, service transactions) | ✅ |
| No `console.log` / debug code | ✅ |
| Proper error handling via `ApiException` | ✅ |
| Comments explain WHY (lean row, milestone key, spec §) | ✅ |
| No hardcoded secrets | ✅ |
| Input validation at service layer | ✅ |
| Negative metric guard | ✅ |
| `@Valid` / `@Size` on proof keys | ⚠️ NONE — user-controlled string stored as-is (L-24-4) |

---

## Findings (Non-Blocking)

### L-24-1: `reportedDaysAfterPosting` accepted but not persisted
Request DTO includes the field per §4.6; neither `DeliverableMetric` nor `Deliverable` stores it. Trend-tracking deferred — document in API contract; add column in a later wave if product needs it.

### L-24-2: `proofScreenshots` list truncated to first key
Lean `deliverable_metrics.proof_screenshot_r2_key` is a single `VARCHAR(500)`. Service uses `firstNonBlank(request.proofScreenshots())`. Multi-screenshot support requires JSON column or child table — aligns with §4.7 proof-upload slice (not yet built).

### L-24-3: Per-field metrics not stored in DB
`MetricsPayload` granular fields exist only in the HTTP response echo. Campaign analytics and brand DTOs see aggregated reach/impressions/engagements only. Intentional per Priya lean schema; UI must not assume brand can read per-metric breakdown from persistence layer.

### L-24-4: Proof screenshot key not validated
`proofScreenshots` values stored without format/ownership check (no verify that `scr_xxx` was uploaded via §4.7). **Escalate to Kabir** — injection / cross-creator key reference risk until proof-upload endpoint exists.

### L-24-5: All-zero metrics allowed
No requirement that at least one metric field be positive. Empty report persists null aggregates and returns `engagementRate: null`. Low severity; product may want `INVALID_REQUEST` if all fields null/zero.

### L-24-6: `METRICS_REPORTED` re-report untested
Logic present via `canReportMetrics`; no dedicated unit test for overwrite semantics on existing milestone row.

### L-24-7: Tests not executed in QA environment
`mvn` unavailable; no Surefire artifacts for metrics slice. **Meera must confirm 29/29 PASS.**

### L-24-8: Frontend mock engagement-rate formula mismatch
`api.ts` `creatorDeliverables.reportMetrics` mock uses `(likes+comments+shares+saves)/reach` — backend uses `(likes+comments)/reach` per §5.2. **Ananya Task #24b** must align mock with backend before UI ships.

### L-24-9: Dual metrics reporting paths (architecture watch)
`POST /creator/deliverables/{id}/metrics` (deliverable status gate) vs `PUT /deliverables/{milestoneId}/metrics` (milestone funded gate). Different authorization shapes and gates can diverge. Week 3 creator journey should use the new path; flag for Priya if legacy path remains public.

### L-24-10: No metrics rate limit
No per-creator throttle on metrics POST. Same posture as M-19-2 upload / M-20-9 submit — pre-prod hardening.

### L-24-11: Spec §4.6 deferred features (documented)
Not in scope for Task #24: API-fetched metrics (`tryFetchApiMetrics`), auto-verify, milestone completion hook, brand notification. Correctly omitted per sprint slice.

---

## Kabir Escalation Items (Security Gate)

1. **IDOR on metrics report** — confirm uniform `404 DELIVERABLE_NOT_FOUND` for foreign creators (architecture closed in T19; re-verify on metrics path).
2. **Proof screenshot key injection** — unvalidated `proofScreenshots` strings persisted; no ownership binding until §4.7 proof upload ships.
3. **Cross-tenant enumeration** — metrics + upload + submit share same scoped query; no new vector expected.
4. **Metrics inflation / spam** — no rate limit; creator can overwrite own milestone metrics repeatedly (L-24-10).
5. **Carry-forward T19–T21** — M-19-2 rate limit, M-19-4 public URLs still prod NO-GO for upload surface.

---

## QA Sign-Off

- [x] `reportMetrics` state transitions verified (`APPROVED`/`POSTED`/`METRICS_REPORTED` → `METRICS_REPORTED`)
- [x] `deliverable_metrics` milestone-keyed upsert verified (code path + save verification in happy-path test)
- [x] `CreatorContextService` + `findByIdAndCreatorUserId` scoping verified
- [x] Negative metric validation verified
- [x] `MILESTONE_NOT_LINKED` gate verified
- [x] Engagement rate formula verified against spec §5.2
- [x] Response contract matches §4.6 (`verificationStatus: PENDING`, user message)
- [x] Controller thin delegation verified
- [x] Key hostile paths covered in unit tests (foreign, invalid state, negative values, missing milestone)
- [ ] Scoped `mvn test` **29/29** — **Meera gate**
- [ ] Kabir metrics security review — **NEXT GATE**

**Kavya verdict: ✅ APPROVED.** Route to Kabir for deliverable metrics red-team (Task #24 security gate) → Meera build verify.

---

**Document Control:** Created 2026-07-09 by Kavya (Task #24). Next: Kabir deliverable metrics security review; Ananya metrics reporting UI wire (Task #24b).
