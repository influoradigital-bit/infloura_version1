# Brand Deliverable Approve/Revise API — Task #21 (Kabir Red-Team)

**Auditor:** Kabir Singh (Offensive Security / Red-Team Lead)  
**Date:** 2026-07-09  
**Scope:** `BrandDeliverableService.approve()` / `requestRevision()`, `BrandDeliverableController`, `Deliverable.applyApprove()` / `applyRevision()`, `ReviseRequest` / `ReviewResponse`, `DeliverableRepository.findByIdAndWorkspaceId`, `BrandContextService.requireBrandWorkspace`, downstream creator egress (`CreatorDeliverableService.toStatusResponse`, `toListItem`), frontend sinks (`revision-handler.tsx`, `deliverable-review-panel.tsx`), cross-check against Task #9 brand isolation, Task #20 M-2 (`TextSanitizer`), Task #20 L-20-2 (TOCTOU)  
**Reference Spec:** `wiki/tech/creator/12_CREATOR_SECURITY_SPEC.md` §6.2; Kavya `wiki/errors/creator-deliverable-review-T21-kavya-qa.md`; Task #20 `wiki/errors/creator-deliverable-submit-T20-kabir-redteam.md`  
**Reviewed Files:**
- `influora-api/src/main/java/com/influora/service/BrandDeliverableService.java`
- `influora-api/src/main/java/com/influora/web/BrandDeliverableController.java`
- `influora-api/src/main/java/com/influora/service/BrandContextService.java`
- `influora-api/src/main/java/com/influora/repository/DeliverableRepository.java` — `findByIdAndWorkspaceId`
- `influora-api/src/main/java/com/influora/domain/entity/Deliverable.java` — `applyApprove()`, `applyRevision()`
- `influora-api/src/main/java/com/influora/web/dto/deliverable/BrandDeliverableDtos.java`
- `influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java` — `toStatusResponse`, `toListItem`
- `influora-api/src/test/java/com/influora/service/BrandDeliverableServiceTest.java` (9 tests)
- `influora-api/src/test/java/com/influora/web/BrandDeliverableControllerTest.java` (2 tests)
- `src/lib/api.ts` — `deliverables.approve`, `deliverables.requestRevision`
- `src/components/creator/deal-room/revision-handler.tsx` — `brandFeedback` render
- `src/components/brand/timeline/panels/deliverable-review-panel.tsx` — feedback ingress UI (not yet wired to live API)

---

## Executive Summary

**VERDICT: ✅ PASS WITH FINDINGS**

Task #21's brand review surface inherits Task #9's workspace-scoped join-through architecture and adds correct state-machine fail-closed behavior. No new Critical or High findings. No IDOR regression on approve/revise paths.

**Closed / PASS:**

1. **IDOR — CLOSED** — `approve` / `requestRevision` resolve scope exclusively via `brandContext.requireBrandWorkspace(principal)` → `findByIdAndWorkspaceId(deliverableId, workspace.getId())`. Foreign workspace probes return uniform `404 DELIVERABLE_NOT_FOUND` (`testApproveForeignDeliverable`, `testReviseForeignDeliverable`). No path-param or body-supplied workspace id is trusted.
2. **Workspace isolation — CLOSED** — Campaign join-through query mirrors audited `DealService` / `CollaborationRepository.findByIdAndWorkspaceId` pattern (Task #9). Creator-scoped twin `findByIdAndCreatorUserId` remains on creator paths only — no cross-role leakage.
3. **State transition abuse — CLOSED** — `canReview` allows only `SUBMITTED` and `RESUBMITTED`; terminal / in-flight states return `409 INVALID_STATE`; double-approve on `APPROVED` and double-revise on `REVISION_REQUESTED` blocked. `revisionCount` is server-owned via `applyRevision()` — client cannot decrement or skip.
4. **Role separation — CLOSED** — `BrandContextService.requireBrand` → `403 WRONG_USER_TYPE` for creator JWT (delegated; consistent with all brand paths since Task #9).

**Carry-forward (pre-prod, non-blocking sprint gate):**

- **M-2 (MEDIUM → ACTIVE, extended):** Brand `feedback` is persisted raw to `review_notes` without `TextSanitizer` or `@Size` bounds. Egress surfaces: `GET /creator/deliverables/{id}/status` (`reviewNotes`), list picker `description` (`toListItem`), and `RevisionHandler` `brandFeedback`. React text interpolation mitigates DOM XSS in wired components today; spec §6.2 server-side rejection still violated — same debt as Tasks #7/#9/#20. **Prod blocker for brand review UI** (unchanged from Task #20 gate).
- **M-21-1 (MEDIUM, new scope label):** No per-brand rate limit on `POST /deliverables/{id}/approve` or `/revise`. `AuthRateLimitFilter.bucketFor()` returns `null` for `/deliverables/**` — approve/revise POSTs are unthrottled. Same abuse class as M-1 apply / M-19-2 submit. Recommend `"brand-deliverable-review"` bucket (e.g. 30/min keyed by `principal.getUserId()`) when rate-limit hardening PR lands.

**New LOW (non-blocking):**

- **L-21-1:** `ReviseRequest` lacks `@Valid` / `@Size` — unbounded `TEXT` column writes from authenticated brand member.
- **L-21-2:** Concurrent approve/revise TOCTOU — no `@Version` optimistic lock; two parallel requests on `SUBMITTED` can both pass `canReview` before either commits (last writer wins; no financial impact — annoyance / duplicate notification risk when events land).
- **L-21-3:** No XSS persistence unit test on brand `feedback` ingress.
- **L-21-4:** `requireBrandWorkspace` does not call `requireMember` to re-validate JWT `workspaceId` against `workspace_members` — pre-existing DealService pattern; JWT is server-minted at login so exploit requires auth-layer compromise, not deliverable-id probing.
- **L-21-5:** No workspace role gate (`requireRole`) — any active brand workspace member (incl. Viewer) can approve/revise; consistent with `DealService`; flag for Priya if approval workflow needs Owner-only gate.

Meera scoped gate **11/11** pending execution. **Does not block** sprint integration or Ananya brand timeline UI wiring. **Blocks production deploy of brand review UI** until shared `TextSanitizer` lands (M-2).

---

## 1. IDOR — `approve` / `requestRevision`

### 1a. Gate chain

```66:76:influora-api/src/main/java/com/influora/service/BrandDeliverableService.java
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
```

Workspace scope is always server-derived from authenticated principal — never from path beyond deliverable id (which is then join-scoped).

### 1b. IDOR exploit matrix

| Attack | Result |
|---|---|
| Brand A approves Brand B's `deliverableId` | **BLOCKED** — `404 DELIVERABLE_NOT_FOUND` |
| Brand A revises Brand B's `deliverableId` with XSS payload | **BLOCKED** on cross-tenant write (404); payload class tracked under M-2 for own-workspace writes |
| Creator JWT on approve/revise | **BLOCKED** — `403 WRONG_USER_TYPE` at `requireBrand` |
| Unauthenticated POST | **BLOCKED** — `SecurityConfig` `anyRequest().authenticated()` |
| Spoof workspace in request body | **N/A** — `ReviseRequest` is `{ feedback }` only; no identity fields |
| Enumerate foreign id → existence oracle | **BLOCKED** — uniform 404, same code/message as missing id |

**IDOR on brand review: CLOSED. Consistent with Task #9 brand deal isolation.**

---

## 2. Workspace Isolation

### 2a. Join-through query

```29:34:influora-api/src/main/java/com/influora/repository/DeliverableRepository.java
  @Query(
      "SELECT d FROM Deliverable d WHERE d.id = :id AND d.collaborationId IN "
          + "(SELECT c.id FROM Collaboration c WHERE c.campaignId IN "
          + "(SELECT ca.id FROM Campaign ca WHERE ca.workspaceId = :workspaceId))")
  Optional<Deliverable> findByIdAndWorkspaceId(
      @Param("id") String id, @Param("workspaceId") String workspaceId);
```

Deliverables carry no `workspace_id` column — trust boundary is `campaign.workspace_id` via collaboration hop. Identical structural pattern to Task #9 `CollaborationRepository.findByIdAndWorkspaceId` (Kabir **PASS** 2026-07-09).

### 2b. Workspace resolution

```34:56:influora-api/src/main/java/com/influora/service/BrandContextService.java
    public Workspace requireBrandWorkspace(AuthPrincipal principal) {
        requireBrand(principal);
        String workspaceId = principal.getWorkspaceId();
        if (workspaceId == null || workspaceId.isBlank()) {
            WorkspaceMember member =
                    workspaceMemberRepository
                            .findFirstByUserIdAndActiveTrue(principal.getUserId())
                            ...
            workspaceId = member.getWorkspaceId();
        }
        return workspaceRepository.findById(resolvedId)...
    }
```

- JWT `workspaceId` is server-minted at login (`AuthService.issueTokens`) — not client-writable.
- **L-21-4:** When JWT carries `workspaceId`, membership is not re-checked via `requireMember`. Pre-existing across `DealService` brand paths. Risk is limited to compromised/stale JWT scenarios, not deliverable-id enumeration.

**Workspace isolation: PASS (no new vector vs Task #9).**

---

## 3. State Transition Abuse

### 3a. `canReview` + ordering

```32:64:influora-api/src/main/java/com/influora/service/BrandDeliverableService.java
    @Transactional
    public ReviewResponse approve(AuthPrincipal principal, String deliverableId) {
        Deliverable deliverable = requireBrandDeliverable(principal, deliverableId);
        if (!canReview(deliverable.getStatus())) {
            throw new ApiException("INVALID_STATE", ..., HttpStatus.CONFLICT);
        }
        deliverable.applyApprove();
        deliverableRepository.save(deliverable);
        ...
    }
```

Fail-fast order: **workspace scope → deliverable lookup → state gate → mutate → save**. Correct.

### 3b. State machine probe matrix

| From status | Approve | Revise | Notes |
|---|---|---|---|
| `SUBMITTED` | ✅ → `APPROVED` | ✅ → `REVISION_REQUESTED` + `revisionCount++` | Happy paths tested |
| `RESUBMITTED` | ✅ → `APPROVED` | ✅ → `REVISION_REQUESTED` + `revisionCount++` | Re-review loop tested |
| `DRAFT` | ❌ `409` | ❌ `409` | `testApproveInvalidState` |
| `APPROVED` | ❌ `409` | ❌ `409` | `testReviseInvalidState` |
| `REVISION_REQUESTED` | ❌ `409` | ❌ `409` | Logic closed; no explicit test (Kavya L-21-4) |
| `PENDING` / `POSTED` / etc. | ❌ `409` | ❌ `409` | Structural — `canReview` false |

### 3c. Abuse scenarios probed

| Scenario | Verdict |
|---|---|
| Approve without prior creator submit | **BLOCKED** — deliverable must be `SUBMITTED`/`RESUBMITTED` |
| Revise with blank feedback | **BLOCKED** — `400 INVALID_REQUEST` (`testReviseMissingFeedback`) |
| Double-approve same deliverable | **BLOCKED** — second call `INVALID_STATE` on `APPROVED` |
| Double-revise without creator resubmit | **BLOCKED** — `REVISION_REQUESTED` ∉ `canReview` |
| Force `revisionCount` via request body | **N/A** — no client field; `applyRevision` increments server-side |
| Mass-assign `status` via body | **N/A** — `ReviseRequest` has only `feedback`; entity methods set status |
| Approve after revise to skip creator work | **BLOCKED** — post-revise status is `REVISION_REQUESTED`, not reviewable until resubmit |
| Trim bypass on feedback | **SAFE** — `feedback.trim()` before persist |

### 3d. Concurrent approve/revise TOCTOU (L-21-2)

`Deliverable` entity has no `@Version` column. Two concurrent `POST .../approve` on the same `SUBMITTED` row can both read `canReview == true`, both `save()` — last writer wins; both may return `200`. End state remains `APPROVED` (no escrow/payout side effect on this path). **Severity: LOW** — recommend optimistic locking or `UPDATE ... WHERE status IN ('SUBMITTED','RESUBMITTED')` row-count check before prod scale.

**State transition abuse: PASS (fail-closed). TOCTOU filed LOW.**

---

## 4. XSS — Brand `feedback` → `reviewNotes` (M-2 carry-forward)

### 4a. Ingress — no sanitization

```10:11:influora-api/src/main/java/com/influora/web/dto/deliverable/BrandDeliverableDtos.java
    /** {@code POST /deliverables/{id}/revise} — required brand feedback. */
    public record ReviseRequest(String feedback) {}
```

```56:61:influora-api/src/main/java/com/influora/service/BrandDeliverableService.java
        String feedback = request != null ? request.feedback() : null;
        if (feedback == null || feedback.isBlank()) {
            throw new ApiException("INVALID_REQUEST", "feedback is required", HttpStatus.BAD_REQUEST);
        }
        deliverable.applyRevision(feedback.trim());
```

```251:256:influora-api/src/main/java/com/influora/domain/entity/Deliverable.java
    public void applyRevision(String feedback) {
        this.status = DeliverableStatus.REVISION_REQUESTED;
        this.reviewNotes = feedback;
        this.revisionCount = this.revisionCount + 1;
        ...
    }
```

- No `@Valid`, no `@Size`, no `TextSanitizer` — raw string persisted to `review_notes` (`TEXT`).
- Controller omits `@Valid` on `@RequestBody ReviseRequest body`.
- Task #21 is a **third ingress** for stored deliverable text (after creator upload + submit from Task #19/#20).

### 4b. Egress — active and planned render paths

| Surface | Field | Render method | DOM XSS today? |
|---|---|---|---|
| `GET .../creator/deliverables/{id}/status` | `reviewNotes` | JSON API → SPA | Depends on consumer |
| `GET .../creator/deliverables?collaboration_id=` list | `description` ← `reviewNotes` | `toListItem` L379–382 | Depends on consumer |
| `revision-handler.tsx` L80 | `brandFeedback` | `<p>{brandFeedback}</p>` React text | **Not exploitable** (escaped) |
| `deliverable-review-panel.tsx` | feedback textarea (brand ingress) | Controlled input | N/A (not wired live yet) |
| Brand timeline UI (Ananya follow-up) | TBD | — | **Future HIGH risk** without sanitizer |

Payload probe: `POST /deliverables/{id}/revise` with `{"feedback":"<img src=x onerror=alert(1)>"}` on owned `SUBMITTED` deliverable → persisted verbatim → returned on creator `getStatus` / list. Stored XSS becomes exploitable when any consumer uses `dangerouslySetInnerHTML` or non-React renderer.

### 4c. M-2 status

Task #7 filed M-2 on `Collaboration.notes`. Task #9 escalated M-2 to **ACTIVE** on deal messages. Task #20 extended M-2 to creator `caption` / `creator_notes` / `hashtags_json`. Task #21 **extends M-2** to brand `feedback` → `review_notes`.

**Remediation (shared hardening PR):** `TextSanitizer.sanitizePlainText()` at write time in `applyRevision` (and all other deliverable text ingress); `@Size(max=2000)` on `ReviseRequest.feedback`; `@Valid` on controller.

**M-2 on brand feedback: MEDIUM (ACTIVE extension). Prod blocker for brand review surface — not sprint integration gate.**

---

## 5. Rate Limiting (M-21-1)

### 5a. Current posture

`POST /deliverables/{id}/approve` and `POST /deliverables/{id}/revise` → `AuthRateLimitFilter.bucketFor()` returns **`null`** — no throttle. Same as creator submit/upload (M-19-2) and campaign apply (M-1).

### 5b. Abuse scenario (brand-review-specific)

Authenticated brand member hammers approve/revise across N deliverables in `SUBMITTED`:
- First action per row succeeds.
- Subsequent actions on same row → `409 INVALID_STATE` (cheap).
- Cross-row spam causes N state mutations + future notification noise when event bus lands.

Cost bounded by deliverable cardinality per workspace campaign, not request rate — identical class to M-19-2.

**Recommended fix (batch with M-19-2 / M-1 hardening):**

```java
// AuthRateLimitFilter.bucketFor — brand deliverable review mutations
if (path.matches("/deliverables/[^/]+/(approve|revise)")) {
    return "brand-deliverable-review"; // e.g. 30 / 60s keyed by principal.getUserId()
}
```

**M-21-1: MEDIUM, OPEN. Sprint carry-forward.**

---

## 6. Input Validation Gaps (L-21-1)

| Field | DTO constraint | DB column | Risk |
|---|---|---|---|
| `feedback` | none | `review_notes` TEXT | Unbounded write / storage bloat |

Contrast: deal `ApplyRequest.message` has `@Size(max=2000)`; `ReviseRequest` has **no** jakarta.validation annotations.

**Severity: LOW** for authenticated brand self-DoS; becomes **MEDIUM** when combined with M-2 XSS (large payload + script tags).

---

## 7. Test Coverage (security-relevant)

| Hostile path | Test | Status |
|---|---|---|
| Foreign deliverable approve 404 | `testApproveForeignDeliverable` | ✅ Authored |
| Foreign deliverable revise 404 | `testReviseForeignDeliverable` | ✅ Authored |
| `DRAFT` approve rejection | `testApproveInvalidState` | ✅ Authored |
| `APPROVED` revise rejection | `testReviseInvalidState` | ✅ Authored |
| Blank feedback 400 | `testReviseMissingFeedback` | ✅ Authored |
| Happy path timestamps + `revisionCount` | `testApproveSubmitted`, `testReviseSubmitted`, etc. | ✅ Authored |
| Creator JWT 403 | — | ❌ Delegated to `BrandContextService` (L-21-5) |
| `REVISION_REQUESTED` double-revise | — | ❌ L-21-6 |
| XSS payload persistence | — | ❌ L-21-3 |
| Concurrent double-approve | — | ❌ L-21-2 |
| Null revise body | — | ❌ Kavya L-21-3 |

Meera gate **11/11** execution pending — sufficient for sprint integration; hostile XSS/state-matrix tests recommended before prod.

---

## Findings Summary

| ID | Severity | Area | Status |
|---|---|---|---|
| — | — | IDOR (`findByIdAndWorkspaceId` join-through) | **CLOSED** — PASS |
| — | — | Workspace isolation (DealService pattern) | **CLOSED** — PASS |
| — | — | State transition abuse (`canReview` + feedback gate) | **CLOSED** — PASS |
| — | — | Cross-tenant enumeration | **CLOSED** — uniform 404 |
| M-2 | **MEDIUM (ACTIVE, extended)** | Brand `feedback` → `reviewNotes` stored raw — extends Task #7/#9/#20 debt to brand review ingress | **OPEN** — prod blocker for brand review UI |
| M-21-1 | **MEDIUM** | No rate limit on `POST /deliverables/{id}/approve` / `/revise` | **OPEN** — sprint carry-forward |
| L-21-1 | LOW | `ReviseRequest` missing `@Valid` / `@Size` | Open |
| L-21-2 | LOW | Concurrent approve/revise TOCTOU (no optimistic lock) | Open |
| L-21-3 | LOW | No XSS persistence unit test on brand feedback | Open |
| L-21-4 | LOW | JWT `workspaceId` not re-validated via `requireMember` (pre-existing pattern) | Open — defense-in-depth |
| L-21-5 | LOW | No creator JWT hostile test in T21 suite | Open — delegated |
| L-21-6 | LOW | No explicit `REVISION_REQUESTED` hostile revise test | Open |

---

## Go/No-Go Decision

| Sub-scope | Decision |
|---|---|
| Task #21 IDOR / workspace isolation / state machine | **GO** |
| Meera scoped unit-test gate (11/11) | **GO** (execution pending) |
| Ananya brand timeline review UI wiring | **GO** |
| Kavya QA Task #21 | **GO** (already APPROVED) |
| Sprint integration / dev deploy | **GO** |
| Production deploy of brand deliverable review UI | **NO-GO** until M-2 `TextSanitizer` on deliverable text fields incl. brand `feedback` |
| Production deploy of deliverable upload | **NO-GO** — M-19-2/3/4 unchanged (Task #19) |

**Pipeline position:** Task #21 security gate **✅ PASS WITH FINDINGS** — cleared for Meera build verify and Priya sign-off on brand review API integration. Vikram batches M-2 sanitizer + M-21-1/M-19-2 rate limits in pre-prod hardening PR (shared with deal room Task #9).

---

## Kabir Sign-Off

- [x] IDOR on approve/revise re-verified — uniform `404`, campaign join-through unchanged from Task #9
- [x] Workspace isolation probed — no cross-tenant write path; JWT workspace server-minted
- [x] State transition abuse probed — fail-closed on invalid states, blank feedback, double-review
- [x] XSS on brand `feedback` — M-2 extended; no active DOM XSS in reviewed SPA paths (`revision-handler.tsx`)
- [x] Rate limiting — M-21-1 filed; brand review unthrottled (same class as M-19-2)
- [x] No Critical or High findings — pipeline **not blocked**
- [ ] M-2 `TextSanitizer` on deliverable ingress incl. brand `feedback` — **pre-prod required before brand review prod**
- [ ] M-21-1 brand-deliverable-review rate limit — **pre-prod recommended**

**Kabir verdict: ✅ PASS WITH FINDINGS.** Route to Meera build verify (`mvn test` **11/11**), then Priya Task #21 integration sign-off. Escalation to Priya/Swapnil: **none** (no Critical/High).

---

**Document Control:** Created 2026-07-09 by Kabir (Task #21). Carry-forward: M-2 TextSanitizer, M-21-1 rate limit. Prior: `creator-deliverable-submit-T20-kabir-redteam.md`. Next: Meera build gate → Priya sign-off.
