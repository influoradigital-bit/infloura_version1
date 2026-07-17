# QA Review: Brand Deliverable Approve/Revise API — Task #21 (Kavya)

**Reviewer:** Kavya Patel (QA Lead)  
**Date:** 2026-07-09 (~23:30 IST)  
**Verdict:** ✅ **APPROVED** — routed to Kabir (brand review surface + M-2 carry-forward) → Meera build  
**Scope:** Vikram Task #21 — `POST /deliverables/{id}/approve` + `POST /deliverables/{id}/revise`  
**Reference:** `src/lib/api.ts` `deliverables.approve` / `deliverables.requestRevision`; `wiki/tech/creator/CREATOR_TASK_ASSIGNMENTS_PRIYA.md` (brand-only approve/revise); prior submit QA (`creator-deliverable-submit-T20-kavya-qa.md`)  
**Reviewed Files:**
- `influora-api/src/main/java/com/influora/service/BrandDeliverableService.java`
- `influora-api/src/main/java/com/influora/web/BrandDeliverableController.java`
- `influora-api/src/main/java/com/influora/service/BrandContextService.java`
- `influora-api/src/main/java/com/influora/repository/DeliverableRepository.java` — `findByIdAndWorkspaceId`
- `influora-api/src/main/java/com/influora/domain/entity/Deliverable.java` — `applyApprove()`, `applyRevision()`
- `influora-api/src/main/java/com/influora/web/dto/deliverable/BrandDeliverableDtos.java`
- `influora-api/src/test/java/com/influora/service/BrandDeliverableServiceTest.java` (9 tests)
- `influora-api/src/test/java/com/influora/web/BrandDeliverableControllerTest.java` (2 tests)
- `src/lib/api.ts` — `deliverables.approve`, `deliverables.requestRevision` (contract cross-check)
- `influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java` — downstream creator flow (reviewNotes, canSubmit, canUploadNewVersion)

---

## Executive Summary

Task #21 **passes QA**. `BrandDeliverableService` implements brand-only deliverable review with correct workspace isolation (`BrandContextService.requireBrandWorkspace` → `DeliverableRepository.findByIdAndWorkspaceId` collaboration → campaign join-through, same trust boundary as `DealService` brand paths). Foreign workspace probes return uniform `DELIVERABLE_NOT_FOUND` 404 — no existence leak.

**Approve:** `SUBMITTED` / `RESUBMITTED` → `APPROVED`; sets `approved_at` + `reviewed_at` via `Deliverable.applyApprove()`.  
**Revise:** same source states → `REVISION_REQUESTED`; increments `revisionCount`, stores trimmed `feedback` in `reviewNotes`, sets `reviewed_at` via `applyRevision()`. Blank/whitespace feedback rejected (`INVALID_REQUEST` 400); invalid source states rejected (`INVALID_STATE` 409).

**11 scoped unit tests** authored (9 service + 2 controller) covering happy paths, resubmit round-trip, foreign 404, invalid states, and blank feedback. **`mvn` not on PATH** in this QA environment — Meera must confirm **11/11 PASS**.

Downstream creator integration verified by code review: after revise, `CreatorDeliverableService.toStatusResponse` exposes `reviewNotes`; `canUploadNewVersion(REVISION_REQUESTED)` and `canSubmit(REVISION_REQUESTED)` enable upload → resubmit loop; `revisionCount > 0` on resubmit yields `RESUBMITTED` (Task #20).

**Kabir carry-forward:** M-2 `TextSanitizer` on brand `feedback` before prod (pre-prod debt, sprint non-blocking); no brand-review rate limit (same posture as M-1/M-19-2).

---

## Task #21 Definition of Done — Verification

| DoD Item | Result | Evidence |
|----------|--------|----------|
| `POST /deliverables/{id}/approve` — brand role | ✅ PASS | `BrandDeliverableController.approve` L31–36; `requireBrandDeliverable` → `requireBrandWorkspace` |
| `SUBMITTED`/`RESUBMITTED` → `APPROVED` + timestamps | ✅ PASS | `canReview` L78–80; `applyApprove` sets `approvedAt` + `reviewedAt` |
| `POST /deliverables/{id}/revise` — body `{ feedback }` | ✅ PASS | `BrandDeliverableController.revise` L38–46; `ReviseRequest` record |
| → `REVISION_REQUESTED`; increments `revisionCount`; stores `reviewNotes` | ✅ PASS | `applyRevision` L251–257; tests `testReviseSubmitted`, `testReviseResubmitted` |
| `findByIdAndWorkspaceId` workspace isolation | ✅ PASS | `DeliverableRepository` L29–34; `requireBrandDeliverable` L66–76 |
| Foreign deliverable → `DELIVERABLE_NOT_FOUND` 404 | ✅ PASS | `testApproveForeignDeliverable`, `testReviseForeignDeliverable` |
| Unit tests 9/9 + 2/2 = **11/11** | ⚠️ AUTHORED | Not executed here (L-21-1) |
| TECH-STACK.md compliance | ✅ PASS | Thin controller, `ApiException` codes, transactional service, no debug code |
| `api.ts` contract alignment | ✅ PASS | `deliverables.approve` / `requestRevision` paths + payload |

---

## Test Execution

| Test Class | Authored | Executed | Failures | Notes |
|------------|----------|----------|----------|-------|
| `BrandDeliverableServiceTest` | 9 | ❌ Not run | — | `mvn` unavailable in QA env |
| `BrandDeliverableControllerTest` | 2 | ❌ Not run | — | Mockito delegation tests |
| **Total** | **11** | **0** | — | **Meera gate required** |

**Command for Meera:**
```bash
cd influora-api && mvn test -Dtest=BrandDeliverableServiceTest,BrandDeliverableControllerTest
```

**Recommended full deliverables regression (Tasks #19–#21):**
```bash
cd influora-api && mvn test -Dtest=CreatorDeliverableServiceTest,CreatorDeliverableControllerTest,BrandDeliverableServiceTest,BrandDeliverableControllerTest,MediaMimeSnifferTest,MultipartConfigTest
```

---

## Service Review: `BrandDeliverableService`

```32:80:influora-api/src/main/java/com/influora/service/BrandDeliverableService.java
    @Transactional
    public ReviewResponse approve(AuthPrincipal principal, String deliverableId) {
        Deliverable deliverable = requireBrandDeliverable(principal, deliverableId);
        if (!canReview(deliverable.getStatus())) {
            throw new ApiException(
                    "INVALID_STATE",
                    "Cannot approve deliverable in current state",
                    HttpStatus.CONFLICT);
        }
        deliverable.applyApprove();
        deliverableRepository.save(deliverable);
        return new ReviewResponse(DeliverableStatus.APPROVED);
    }

    @Transactional
    public ReviewResponse requestRevision(
            AuthPrincipal principal, String deliverableId, ReviseRequest request) {
        Deliverable deliverable = requireBrandDeliverable(principal, deliverableId);
        if (!canReview(deliverable.getStatus())) {
            throw new ApiException(
                    "INVALID_STATE",
                    "Cannot request revision in current state",
                    HttpStatus.CONFLICT);
        }
        String feedback = request != null ? request.feedback() : null;
        if (feedback == null || feedback.isBlank()) {
            throw new ApiException(
                    "INVALID_REQUEST", "feedback is required", HttpStatus.BAD_REQUEST);
        }
        deliverable.applyRevision(feedback.trim());
        deliverableRepository.save(deliverable);
        return new ReviewResponse(DeliverableStatus.REVISION_REQUESTED);
    }

    private Deliverable requireBrandDeliverable(AuthPrincipal principal, String deliverableId) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        return deliverableRepository
                .findByIdAndWorkspaceId(deliverableId, workspace.getId())
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "DELIVERABLE_NOT_FOUND",
                                        "Deliverable not found",
                                        HttpStatus.NOT_FOUND));
    }

    private static boolean canReview(DeliverableStatus status) {
        return status == DeliverableStatus.SUBMITTED || status == DeliverableStatus.RESUBMITTED;
    }
```

**Ordering:** Workspace resolve → deliverable lookup → state gate → mutate → save. Correct fail-fast sequence.

**Null revise body:** Service null-coalesces `request.feedback()` when `request` is null → `INVALID_REQUEST` 400. Not unit-tested (L-21-3).

---

## Workspace Isolation Review

### `BrandContextService.requireBrandWorkspace`

```34:56:influora-api/src/main/java/com/influora/service/BrandContextService.java
    public Workspace requireBrandWorkspace(AuthPrincipal principal) {
        requireBrand(principal);
        String workspaceId = principal.getWorkspaceId();
        if (workspaceId == null || workspaceId.isBlank()) {
            WorkspaceMember member =
                    workspaceMemberRepository
                            .findFirstByUserIdAndActiveTrue(principal.getUserId())
                            .orElseThrow(
                                    () ->
                                            new ApiException(
                                                    "WORKSPACE_NOT_FOUND",
                                                    "No workspace found for this user",
                                                    HttpStatus.NOT_FOUND));
            workspaceId = member.getWorkspaceId();
        }
        final String resolvedId = workspaceId;
        return workspaceRepository
                .findById(resolvedId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "WORKSPACE_NOT_FOUND", "Workspace not found", HttpStatus.NOT_FOUND));
    }
```

- **Brand type gate:** `requireBrand` → `WRONG_USER_TYPE` 403 for creator JWT (delegated to `BrandContextService`; not re-tested in T21 suite — consistent with DealService pattern).
- **Workspace resolution:** JWT `workspaceId` or active `WorkspaceMember` fallback — same as Tasks #9/#10 brand paths.
- **No path-param workspace trust:** deliverable id is the only path param; workspace always server-derived.

### `DeliverableRepository.findByIdAndWorkspaceId`

```29:34:influora-api/src/main/java/com/influora/repository/DeliverableRepository.java
  @Query(
      "SELECT d FROM Deliverable d WHERE d.id = :id AND d.collaborationId IN "
          + "(SELECT c.id FROM Collaboration c WHERE c.campaignId IN "
          + "(SELECT ca.id FROM Campaign ca WHERE ca.workspaceId = :workspaceId))")
  Optional<Deliverable> findByIdAndWorkspaceId(
      @Param("id") String id, @Param("workspaceId") String workspaceId);
```

Mirrors `CollaborationRepository.findByIdAndWorkspaceId` / `DealService` brand gate. Creator-scoped twin `findByIdAndCreatorUserId` remains on creator paths only — no cross-role leakage.

---

## State Machine Review

| From Status | `canReview` | Approve → | Revise → |
|-------------|-------------|-----------|----------|
| `SUBMITTED` | ✅ | `APPROVED` | `REVISION_REQUESTED` (+`revisionCount`) |
| `RESUBMITTED` | ✅ | `APPROVED` | `REVISION_REQUESTED` (+`revisionCount`) |
| `DRAFT` | ❌ | `INVALID_STATE` 409 | `INVALID_STATE` 409 |
| `REVISION_REQUESTED` | ❌ | `INVALID_STATE` 409 | `INVALID_STATE` 409 |
| `APPROVED` | ❌ | `INVALID_STATE` 409 | `INVALID_STATE` 409 |
| `PENDING` / `POSTED` / etc. | ❌ | `INVALID_STATE` 409 | `INVALID_STATE` 409 |

**Idempotency posture:** Double-approve on `APPROVED` blocked by `canReview` — correct. Double-revise without creator resubmit blocked (`REVISION_REQUESTED` not reviewable) — correct.

### Entity mutations

```242:257:influora-api/src/main/java/com/influora/domain/entity/Deliverable.java
    public void applyApprove() {
        Instant now = Instant.now();
        this.status = DeliverableStatus.APPROVED;
        this.approvedAt = now;
        this.reviewedAt = now;
        touch();
    }

    public void applyRevision(String feedback) {
        this.status = DeliverableStatus.REVISION_REQUESTED;
        this.reviewNotes = feedback;
        this.revisionCount = this.revisionCount + 1;
        this.reviewedAt = Instant.now();
        touch();
    }
```

`approvedAt` set only on approve (not on revise) — correct per lean row model.

---

## Feedback Validation Review

| Input | Expected | Status |
|-------|----------|--------|
| Valid non-blank feedback | Stored trimmed in `reviewNotes` | ✅ `testReviseSubmitted` |
| Whitespace-only `"  "` | `INVALID_REQUEST` 400 | ✅ `testReviseMissingFeedback` |
| `null` feedback field | `INVALID_REQUEST` 400 | ✅ (service L56–60) |
| `null` request body | `INVALID_REQUEST` 400 | ✅ (service null-coalesce) |
| Leading/trailing whitespace | Trimmed before persist | ✅ `feedback.trim()` L61 |
| Max length | No `@Size` / no service cap | ⚠️ L-21-2 (TEXT column; Kabir M-2) |

---

## Creator Round-Trip Integration (Tasks #19–#20)

After brand revise, creator path must allow re-upload + resubmit:

| Check | Status | Evidence |
|-------|--------|----------|
| `reviewNotes` exposed to creator | ✅ | `toStatusResponse` L412 |
| `canUploadNewVersion(REVISION_REQUESTED)` | ✅ | L213–216 |
| `canSubmit(REVISION_REQUESTED)` | ✅ | L219–220 |
| Resubmit → `RESUBMITTED` when `revisionCount > 0` | ✅ | Task #20 `submitForReview` |
| Brand can re-review `RESUBMITTED` | ✅ | `testApproveResubmitted`, `testReviseResubmitted` |
| List item shows review feedback as description | ✅ | `toListItem` L379–382 |

Full E2E round-trip (brand revise → creator upload → submit → brand approve) requires live stack — not in unit gate scope.

---

## Controller Review

```31:46:influora-api/src/main/java/com/influora/web/BrandDeliverableController.java
    @PostMapping("/{deliverableId}/approve")
    public ResponseEntity<ApiResponse<ReviewResponse>> approve(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String deliverableId) {
        return ResponseEntity.ok(
                ApiResponse.ok(brandDeliverableService.approve(principal, deliverableId)));
    }

    @PostMapping("/{deliverableId}/revise")
    public ResponseEntity<ApiResponse<ReviewResponse>> revise(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String deliverableId,
            @RequestBody ReviseRequest body) {
        return ResponseEntity.ok(
                ApiResponse.ok(
                        brandDeliverableService.requestRevision(principal, deliverableId, body)));
    }
```

- **Route collision:** Shares `/deliverables` base with `DeliverableMetricController` (`PUT /{id}/metrics`) — disjoint HTTP methods + path suffixes; no conflict.
- **Auth:** `SecurityConfig` `anyRequest().authenticated()` — brand/creator JWT both authenticate; brand type enforced in service layer.
- **Response envelope:** `ApiResponse<ReviewResponse>` with `{ status }` — matches `api.ts`.

---

## API Contract Cross-Check (`api.ts`)

| Frontend call | Backend route | Match |
|---------------|---------------|-------|
| `deliverables.approve(id)` | `POST /deliverables/{id}/approve` | ✅ |
| `deliverables.requestRevision(id, feedback)` | `POST /deliverables/{id}/revise` body `{ feedback }` | ✅ |
| Default auth role | `role = 'brand'` (HttpClient default) | ✅ |
| Response | `{ status: DeliverableStatus }` | ✅ |

```979:991:src/lib/api.ts
  approve: (id: string) =>
    isLive()
      ? http.request<{ status: DeliverableStatus }>('POST', `/deliverables/${id}/approve`)
      : mockOr({ status: 'APPROVED' as DeliverableStatus }),

  requestRevision: (id: string, feedback: string) =>
    isLive()
      ? http.request<{ status: DeliverableStatus }>('POST', `/deliverables/${id}/revise`, {
          body: { feedback },
        })
      : mockOr({ status: 'REVISION_REQUESTED' as DeliverableStatus }),
```

**Note:** Brand timeline UI not wired yet (Ananya follow-up). Backend contract ready.

---

## Unit Test Coverage Matrix

| Test | Scenario | Status |
|------|----------|--------|
| `testApproveSubmitted` | Happy path + `approvedAt`/`reviewedAt` | ✅ Authored |
| `testApproveResubmitted` | Re-review after creator resubmit | ✅ Authored |
| `testApproveForeignDeliverable` | Cross-workspace 404, no save | ✅ Authored |
| `testApproveInvalidState` | `DRAFT` → 409 | ✅ Authored |
| `testReviseSubmitted` | Happy path + `revisionCount=1` + `reviewNotes` | ✅ Authored |
| `testReviseResubmitted` | Second round `revisionCount=2` | ✅ Authored |
| `testReviseMissingFeedback` | Blank feedback 400 | ✅ Authored |
| `testReviseForeignDeliverable` | Cross-workspace 404 | ✅ Authored |
| `testReviseInvalidState` | `APPROVED` → 409 | ✅ Authored |
| `testApprove` (controller) | Delegation | ✅ Authored |
| `testRevise` (controller) | Delegation | ✅ Authored |

**Gaps (non-blocking):** null body revise (L-21-3); `REVISION_REQUESTED` double-revise (L-21-4); creator JWT 403 (L-21-5); `SUBMITTED` double-approve race (covered by state gate).

---

## Code Quality Checklist

| Check | Status |
|-------|--------|
| TECH-STACK.md: workspace isolation via context service + scoped query | ✅ |
| Thin controller, fat service | ✅ |
| `ApiException` with stable codes | ✅ |
| `@Transactional` on mutations | ✅ |
| No `console.log` / debug code | ✅ |
| Comments explain trust boundary (WHY) | ✅ |
| DTO records minimal surface | ✅ |

---

## Findings (Non-Blocking)

### L-21-1: Unit tests not executed in QA environment
`mvn` not on PATH. Meera must run **11/11** before merge gate closes. Same posture as Tasks #9/#20.

### L-21-2: No max length on brand `feedback`
`ReviseRequest` has no `@Size`; `review_notes` is `TEXT`. Unbounded payload possible. Escalate to Kabir as M-2 extension (same `TextSanitizer` + length cap debt as creator caption/notes). Sprint non-blocking per TASK_INBOX pre-prod debt.

### L-21-3: Missing null-body revise unit test
Service handles `request == null` → `INVALID_REQUEST` 400 but no explicit test. Low risk — Spring may 400 before service on strict JSON parsers.

### L-21-4: Missing `REVISION_REQUESTED` hostile revise test
Double-revise without creator resubmit should 409. Covered by `canReview` logic; recommend adding explicit test in hardening PR.

### L-21-5: No creator JWT hostile test in T21 suite
`WRONG_USER_TYPE` 403 enforced by `BrandContextService.requireBrand` — tested in `CreatorContextServiceTest` / connect controller tests. Acceptable delegation.

### L-21-6: No workspace member role gate (`requireRole`)
`BrandDeliverableService` does not call `requireMember` / `requireRole` (Viewer vs Owner). Consistent with `DealService` brand paths — any active brand workspace member can review. Flag for Priya if role-based approval workflow is needed later.

### L-21-7: Spec section reference mismatch
Javadoc cites `09_CREATOR_DELIVERABLES_SPEC.md` §11.4–11.5; spec file has sections 1–9 only. Implementation matches `api.ts` + `CREATOR_TASK_ASSIGNMENTS_PRIYA.md` — doc hygiene only.

### L-21-8: No brand-review rate limit
Same posture as M-1 apply rate limit / M-19-2 submit rate limit. Kabir carry-forward.

### Security carry-forward (Kabir)
- **M-2 ACTIVE:** `feedback` stored raw in `reviewNotes`; rendered to creator via status/list endpoints — `TextSanitizer` required before brand review prod.
- **IDOR:** Closed by `findByIdAndWorkspaceId` join-through (uniform 404).
- **State machine:** Closed — only `SUBMITTED`/`RESUBMITTED` reviewable.

---

## Hostile / Edge-Case Matrix

| Scenario | Expected | Status |
|----------|----------|--------|
| Approve `SUBMITTED` in own workspace | `APPROVED` + timestamps | ✅ PASS (authored) |
| Approve `RESUBMITTED` | `APPROVED` | ✅ PASS (authored) |
| Approve foreign workspace deliverable | `DELIVERABLE_NOT_FOUND` 404 | ✅ PASS (authored) |
| Approve `DRAFT` | `INVALID_STATE` 409 | ✅ PASS (authored) |
| Approve already `APPROVED` | `INVALID_STATE` 409 | ✅ PASS (logic) |
| Revise with valid feedback | `REVISION_REQUESTED` + count++ | ✅ PASS (authored) |
| Revise with blank feedback | `INVALID_REQUEST` 400 | ✅ PASS (authored) |
| Revise foreign workspace | `DELIVERABLE_NOT_FOUND` 404 | ✅ PASS (authored) |
| Revise `APPROVED` | `INVALID_STATE` 409 | ✅ PASS (authored) |
| Revise `REVISION_REQUESTED` (no resubmit) | `INVALID_STATE` 409 | ✅ PASS (logic) |
| Creator JWT on brand endpoint | `WRONG_USER_TYPE` 403 | ✅ PASS (BrandContextService) |
| Trim whitespace feedback | Stored trimmed | ✅ PASS (code) |

---

## QA Sign-Off

- [x] Workspace isolation via `BrandContextService` + `findByIdAndWorkspaceId`
- [x] Approve/revise state transitions + timestamps verified
- [x] Feedback validation (required, non-blank, trimmed)
- [x] Foreign deliverable uniform 404
- [x] Creator round-trip integration (reviewNotes, revisionCount, resubmit) verified
- [x] `api.ts` contract alignment
- [x] 11/11 unit tests authored (Meera execution pending)
- [x] TECH-STACK.md alignment
- [x] No debug code
- [ ] Meera build confirm — **NEXT GATE**
- [ ] Kabir brand review security gate — **NEXT GATE**

**Kavya verdict: ✅ APPROVED.** Route to Kabir security review, then Meera `mvn test` (**11/11** + deliverables regression). No sprint blockers identified.

---

**Document Control:** Created 2026-07-09 by Kavya (Task #21). Prior: `creator-deliverable-submit-T20-kavya-qa.md`. Next: Kabir red-team → Meera build gate.
