# QA Review: Creator Deliverable List API + Live Frontend — Task #19c (Kavya)

**Reviewer:** Kavya Patel (QA Lead)  
**Date:** 2026-07-09 (~15:30 IST)  
**Verdict:** ✅ **APPROVED** — routed to Kabir (carry-forward Task #19 security items) → Priya deliverables slice sign-off  
**Scope:** Vikram Task #19c — `GET /creator/deliverables?collaboration_id=` + Ananya live picker wiring  
**Reference:** `09_CREATOR_DELIVERABLES_SPEC.md` (deal-room picker); Task #19b gap-banner contract  
**Reviewed Files:**
- `influora-api/src/main/java/com/influora/web/CreatorDeliverableController.java`
- `influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java`
- `influora-api/src/main/java/com/influora/service/CreatorContextService.java`
- `influora-api/src/main/java/com/influora/web/dto/deliverable/CreatorDeliverableDtos.java`
- `influora-api/src/main/java/com/influora/repository/DeliverableRepository.java`
- `influora-api/src/test/java/com/influora/service/CreatorDeliverableServiceTest.java` (11 tests)
- `influora-api/src/test/java/com/influora/web/CreatorDeliverableControllerTest.java` (3 tests)
- `src/lib/api.ts` — `creatorDeliverables.listForDeal`
- `src/pages/creator-chat.tsx` — `loadDealDeliverables`, gap banner, submit gating

---

## Executive Summary

Task #19c **passes QA**. The list endpoint returns slot-ordered `DeliverableListItem` rows for creator-owned collaborations only. Access isolation mirrors `DealService#requireCreatorCollaboration`: `CreatorContextService.requireCreatorProfile` → `CollaborationRepository.findByIdAndCreatorId` → `DEAL_NOT_FOUND` on foreign/missing deal; blank `collaboration_id` → `INVALID_REQUEST` 400. Frontend `listForDeal` is live-wired with `query: { collaboration_id: dealId }` and `role: 'creator'`. In live mode, a successful list fetch clears `deliverablesListGap` (gap banner hidden; submit button enabled when uploadable rows exist).

**14 scoped unit tests** (11 service + 3 controller) cover happy path, foreign deal 404, blank param 400, and controller delegation. Meera reported **19/19** full Task #19 gate PASS (2026-07-09 ~15:13 IST). `mvn` unavailable in this QA environment — execution credited to Meera gate.

**Kabir carry-forward:** No new security blockers for list-only surface. Reuse Task #19 PASS on IDOR/MIME/R2; M-19-2/3/4 still prod NO-GO from upload path.

---

## Task #19c Definition of Done — Verification

| DoD Item | Result | Evidence |
|----------|--------|----------|
| `GET /creator/deliverables?collaboration_id=` — slot-ordered rows | ✅ PASS | `listForCollaboration` L140–150; `findByCollaborationIdOrderBySlotIndexAsc` |
| `DeliverableListItem` DTO shape | ✅ PASS | `CreatorDeliverableDtos.DeliverableListItem` L49–57; aligns with `CreatorDeliverableListItem` in `api.ts` |
| `CreatorContextService` scoping | ✅ PASS | `requireCreatorProfile` on every list call L142 |
| Foreign deal → 404 `DEAL_NOT_FOUND` | ✅ PASS | `requireOwnedCollaboration` L153–159; test `testListForCollaborationForeignDeal` |
| Blank `collaboration_id` → 400 | ✅ PASS | L143–146; test `testListForCollaborationMissingParam` |
| `api.ts` live wire | ✅ PASS | `listForDeal` L1146–1148: `GET /creator/deliverables`, `query: { collaboration_id: dealId }` |
| Gap banner clears on live list success | ✅ PASS | `loadDealDeliverables` L784 `setDeliverablesListGap(false)` before fetch; success path leaves gap false |
| Unit tests 11/11 + 3/3 | ✅ PASS (Meera) | +3 list service tests, +1 list controller test; Meera **19/19** gate |
| TECH-STACK.md compliance | ✅ PASS | Thin controller, `ApiException` codes, JWT auth, no debug code |

---

## Access Isolation Review

### List gate: `requireOwnedCollaboration`

```153:159:influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java
    private void requireOwnedCollaboration(AuthPrincipal principal, String collaborationId) {
        collaborationRepository
                .findByIdAndCreatorId(collaborationId, principal.getUserId())
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "DEAL_NOT_FOUND", "Deal not found", HttpStatus.NOT_FOUND));
    }
```

Pattern is **identical** to `DealService#requireCreatorCollaboration` (L344–351). No path-param creator id is trusted. Deliverable rows are fetched only after collaboration ownership is proven.

**Defense-in-depth note (L-19c-3):** `findByCollaborationIdOrderBySlotIndexAsc` has no creator join at repository level. Safe because `requireOwnedCollaboration` always runs first; recommend keeping call order enforced in code review.

---

## Frontend Live Path Review

### `api.creatorDeliverables.listForDeal`

- Live branch calls `http.request` with `role: 'creator'` and `collaboration_id` query param — matches backend `@RequestParam("collaboration_id")`.
- No `NOT_IMPLEMENTED` stub on list (unlike pre-ship gaps elsewhere in `api.ts`) — correct for shipped endpoint.

### `creator-chat.tsx` — gap banner + submit gating

| Scenario | Expected | Verified |
|----------|----------|----------|
| Live + list succeeds | `deliverablesListGap === false`; banner hidden | ✅ L784, L787–792 |
| Live + `NOT_IMPLEMENTED` | Gap banner shown; submit disabled | ✅ L796–797, L1186, L1682–1693 |
| Live + all items completed | `dealDeliverables` empty; submit disabled with honest title | ✅ L760–765, L1193–1194 |
| Mock mode | Gap always cleared on mock list | ✅ L775 |

Uploadable filter (`PENDING` / `DRAFT` / `REVISION_REQUESTED`, not `completed`) matches backend `canUploadNewVersion` states.

---

## Test Matrix (Task #19c additions)

| Test | Class | Status |
|------|-------|--------|
| Happy path — slot-ordered rows, `completed` flag | `CreatorDeliverableServiceTest#testListForCollaborationHappyPath` | ✅ Authored |
| Foreign deal 404, no repo leak | `CreatorDeliverableServiceTest#testListForCollaborationForeignDeal` | ✅ Authored |
| Blank param 400 | `CreatorDeliverableServiceTest#testListForCollaborationMissingParam` | ✅ Authored |
| Controller delegates `list()` | `CreatorDeliverableControllerTest#testList` | ✅ Authored |

**Meera execution:** `mvn test -Dtest=CreatorDeliverableServiceTest,CreatorDeliverableControllerTest` + full Task #19 gate **19/19 PASS** (2026-07-09 ~15:13 IST).

---

## Findings (Non-Blocking)

### L-19c-1: Empty list on owned deal untested
Valid owned collaboration with zero deliverable rows should return `[]`. Behavior is correct; add `testListForCollaborationEmpty` for regression.

### L-19c-2: List fetch errors lack deliverables-specific UI
On live failure (network, 500, 404), `loadDealDeliverables` clears rows but does not surface `deliverableSubmitError` or tab-level alert. Acceptable for P0 — deal picker is already creator-scoped; recommend Ananya add retry copy in a follow-up.

### L-19c-3: Repository list query not creator-scoped
Mitigated by prior ownership gate (see Access Isolation). Document for Kabir; no change required for 19c.

### L-19c-4: `maxRevisions` hardcoded to 2
Pre-existing from Task #19 `toListItem` / `mapStatusToListItem`. Track when revision policy is spec'd.

### L-19c-5: Missing Spring param vs blank param
Blank/whitespace → service `INVALID_REQUEST` 400. Omitted `collaboration_id` → Spring `MissingServletRequestParameterException` 400. Both acceptable; no controller test for omitted param.

---

## Kabir Escalation (Informational — No New 19c Blockers)

1. **Cross-tenant enumeration** — list returns uniform `DEAL_NOT_FOUND` for foreign collaborations ✅ (same as deals API).
2. **Metadata exposure** — list returns id/title/status only for owned deals; no file URLs in list DTO ✅.
3. **Carry-forward from Task #19** — M-19-2 rate limit, M-19-3 streaming, M-19-4 signed URLs still prod NO-GO for upload path; list endpoint unaffected.

---

## QA Sign-Off

- [x] List API ownership isolation verified
- [x] Foreign deal 404 + blank param 400 verified in unit tests
- [x] Frontend live wire + gap-banner contract verified
- [x] DTO / TypeScript shape alignment verified
- [x] Meera scoped tests **19/19** PASS (credited)
- [x] No new security blockers for list surface

**Kavya verdict: ✅ APPROVED.** Route to Kabir (carry-forward only) → Priya deliverables slice sign-off.

---

**Document Control:** Created 2026-07-09 by Kavya (Task #19c). Supersedes gap-banner “list pending” state for live deployments with Task #19c shipped.
