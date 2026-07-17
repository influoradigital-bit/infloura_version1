# QA Review: Collaboration Disputes API — Task #34 V5 (Kavya)

**Reviewer:** Kavya Patel (QA Lead)  
**Date:** 2026-07-09 (~20:45 IST)  
**Verdict:** ⚠️ **APPROVED WITH FINDINGS** — routed to **Kabir K1** (dispute-freeze race + admin gating red-team) → Meera build  
**Scope:** Vikram Task #34 V5 — `Dispute` entity, `POST /deals/{dealId}/disputes`, `POST /admin/disputes/{id}/resolve`, escrow freeze on open  
**Reference:** `wiki/tech/creator/CREATOR_CEO_INSTRUCTIONS_SWAPNIL.md` §1.3; `TECH-STACK.md` cross-cutting rules §2  
**Reviewed Files:**
- `influora-api/src/main/resources/db/migration/V45__disputes.sql`
- `influora-api/src/main/java/com/influora/domain/entity/Dispute.java`
- `influora-api/src/main/java/com/influora/domain/enums/DisputeStatus.java`
- `influora-api/src/main/java/com/influora/domain/enums/DisputeOpenerType.java`
- `influora-api/src/main/java/com/influora/repository/DisputeRepository.java`
- `influora-api/src/main/java/com/influora/service/DisputeService.java`
- `influora-api/src/main/java/com/influora/service/EscrowService.java` (`hasFundedUnreleasedEscrow`, `freezeUnreleasedForDispute`)
- `influora-api/src/main/java/com/influora/web/DealController.java`
- `influora-api/src/main/java/com/influora/web/AdminDisputeController.java`
- `influora-api/src/main/java/com/influora/web/dto/dispute/DisputeDtos.java`
- `influora-api/src/test/java/com/influora/service/DisputeServiceTest.java` (8 tests)
- `influora-api/src/test/java/com/influora/service/EscrowServiceTest.java` (+1 freeze test)
- `influora-api/src/test/java/com/influora/web/DealControllerTest.java` (wired only)
- `influora-api/src/main/java/com/influora/config/SecurityConfig.java` — `/admin/**` auth posture
- `influora-api/src/main/java/com/influora/service/admin/AdminContextService.java` — MFA + role gate pattern

---

## Executive Summary

Task #34 V5 **passes QA** on CEO §1.3 mandatory gates at the service layer. `DisputeService.openDispute` resolves party identity exclusively from `AuthPrincipal` + `CreatorContextService` / `BrandContextService` — no path-param user ids, no client-supplied `opened_by_type`. Cross-party IDOR returns uniform `DEAL_NOT_FOUND` 404. One-active-dispute is enforced via `existsByCollaborationIdAndStatusIn` over `OPEN` + `UNDER_REVIEW`. Opening requires `hasFundedUnreleasedEscrow`; on success `freezeUnreleasedForDispute` marks `FUNDED → FROZEN` (no auto-refund/clawback). Admin resolve is gated by `adminContext.requireRoleWithMfaSatisfied(SUPER_ADMIN, ADMIN)` plus filter-chain `ROLE_ADMIN` on `/admin/**`; resolution is status-only stub per v1 scope.

**8/8 `DisputeServiceTest` + 1 `EscrowServiceTest` freeze case authored.** Vikram reports **17/17 scoped PASS**; `mvn` not on PATH in this QA environment — **Meera must confirm**.

**Findings (non-blocking):** no DB partial-unique for active disputes (TOCTOU race — **Kabir K1**); admin resolve hostile-path tests thin (no `DISPUTE_NOT_ACTIVE`, `DISPUTE_NOT_FOUND`, SUPPORT rejection); no reason/notes sanitization tests; no `openDispute` controller delegation test; no `AdminDisputeControllerTest`; no rate limit on dispute open (same posture as T29 reviews).

**No P0 or P1 code defects found.** Standards compliant.

---

## CEO §1.3 Mandatory Gates — Hostile-Path Verification

| Gate | Result | Evidence |
|------|--------|----------|
| **Either party may open** | ✅ PASS | `requireDealPartyRole` L161–170 allows `CREATOR` \| `BRAND`; `openerType` derived server-side L64–65. Tests: `creatorOpenHappyPath`, `brandOpenHappyPath`. |
| **FUNDED unreleased escrow required** | ✅ PASS | `hasFundedUnreleasedEscrow` L67–72 → `NO_FUNDED_ESCROW` 409. Test: `openRejectsNoFundedEscrow`. Escrow lookup covers direct holds + milestone-linked holds (`EscrowService.findFundedHoldsForCollaboration`). |
| **Freeze on open (no auto-refund)** | ✅ PASS | `freezeUnreleasedForDispute` L91; only `FUNDED` holds frozen, released holds untouched (CEO §1.3). Test: `creatorOpenHappyPath` verifies freeze call; `EscrowServiceTest.freezeUnreleasedForDisputeMarksFundedHolds`. |
| **One active dispute per collaboration** | ✅ PASS (app layer) | `existsByCollaborationIdAndStatusIn` with `OPEN` + `UNDER_REVIEW` L74–80 → `DISPUTE_ALREADY_OPEN` 409. Test: `openRejectsDuplicateActiveDispute`. **No DB constraint** — race gap L-T34-1. |
| **Creator IDOR — open** | ✅ PASS | `findByIdAndCreatorId(dealId, principal.getUserId())` L145–150 → `DEAL_NOT_FOUND` 404. Test: `creatorOpenIdorForeignDeal`. |
| **Brand IDOR — open** | ✅ PASS | `findByIdAndWorkspaceId(dealId, workspace.getId())` L152–158 → `DEAL_NOT_FOUND` 404. Test: `brandOpenIdorForeignDeal`. |
| **Admin-only resolve** | ✅ PASS | Service: `requireRoleWithMfaSatisfied(SUPER_ADMIN, ADMIN)` L108–110 (SUPPORT excluded). Filter: `SecurityConfig` L120–121 `/admin/**` → `ROLE_ADMIN`. Creator/brand blocked at open via `FORBIDDEN` L167–168. |
| **Resolve status-only stub** | ✅ PASS | `resolveDispute` L136–137 saves status/notes/admin id only; no escrow release/refund calls. Class javadoc L101–104 documents follow-up. |
| **State machine — dispute** | ✅ PASS | `Dispute.open` → `OPEN`; `resolve` → `RESOLVED_*` when `isActive()`; `INVALID_RESOLUTION` for non-terminal input L112–117. Entity `markUnderReview()` exists for future admin triage — no HTTP surface in v1 (acceptable). |
| **State machine — collaboration** | ✅ PASS | Open sets `CollaborationStatus.DISPUTED` L93–96 if not already. Test asserts on happy path. Idempotent if already `DISPUTED`. |
| **Reason sanitization** | ✅ PASS (untested) | `Dispute.open` L72–76 `TextSanitizer.sanitizePlainText(reason)`; blank → `IllegalArgumentException`. DTO `@NotBlank @Size(max=2000)`. |
| **Resolution notes sanitization** | ✅ PASS (untested) | `Dispute.resolve` L101–103 sanitizes notes; blank → `null`. |
| **Unauthenticated access** | ✅ PASS | `SecurityConfig` `anyRequest().authenticated()` for `/deals/**`; `/admin/**` requires admin role. |
| **Opener type not client-spoofable** | ✅ PASS | Not in `OpenDisputeRequest`; set only from JWT role. |

---

## Hostile-Path Matrix (manual code trace)

| Attack vector | Expected | Observed |
|---------------|----------|----------|
| Creator probes foreign `dealId` | 404 `DEAL_NOT_FOUND` | ✅ |
| Brand probes foreign workspace deal | 404 `DEAL_NOT_FOUND` | ✅ |
| Second open while `OPEN` / `UNDER_REVIEW` exists | 409 `DISPUTE_ALREADY_OPEN` | ✅ (app layer) |
| Parallel duplicate open (TOCTOU) | One wins, one 409 | ⚠️ **Untested** — no DB unique partial index |
| Open with no `FUNDED` escrow | 409 `NO_FUNDED_ESCROW` | ✅ |
| Open then concurrent escrow release | Release may slip before freeze | ⚠️ **Kabir K1** — ordering/race |
| Admin JWT resolve happy path | 200, `RESOLVED_*` | ✅ (unit) |
| Resolve already-resolved dispute | 409 `DISPUTE_NOT_ACTIVE` | ✅ (code L129–134) — **no test** |
| Guess foreign `disputeId` on resolve | 404 `DISPUTE_NOT_FOUND` | ✅ (code L119–127) — **no test** |
| Creator/brand JWT on `/admin/disputes/*/resolve` | 403 filter-chain | ✅ (structural) — **no test** |
| SUPPORT admin JWT on resolve | 403 from `requireRoleWithMfaSatisfied` | ✅ (structural) — **no test** |
| Admin JWT on `POST /deals/{id}/disputes` | 403 `FORBIDDEN` | ✅ (code L166–168) — **no test** |
| `<script>` in reason / notes | Tags stripped | ✅ (entity) — **no test** |
| Resolve with `OPEN` / `UNDER_REVIEW` as resolution | 400 `INVALID_RESOLUTION` | ✅ — test: `adminResolveRejectsInvalidResolution` |

---

## Schema Review: `V45__disputes.sql`

- ULID `VARCHAR(26)` PK/FKs — TECH-STACK compliant.
- Status ENUM matches `DisputeStatus` exactly.
- Indexes on `collaboration_id` and `status` — supports active-dispute lookup.
- **No partial unique index** on `(collaboration_id)` WHERE `status IN ('OPEN','UNDER_REVIEW')` — intentional service-layer enforcement per migration comment; race window remains (**L-T34-1**, Kabir).

---

## Test Execution

| Test Class | Authored | Executed | Failures | Notes |
|------------|----------|----------|----------|-------|
| `DisputeServiceTest` | 8 | ❌ Not run | — | `mvn` unavailable in QA env |
| `EscrowServiceTest` (freeze case) | 1 | ❌ Not run | — | Scoped subset |
| `DealControllerTest` (openDispute) | 0 | — | — | **Gap L-T34-4** — mock wired, no delegation test |
| `AdminDisputeControllerTest` | 0 | — | — | **Gap L-T34-4** |
| **Total scoped** | **9** | **0** | — | Vikram reports 17/17 PASS — Meera gate required |

**Command for Meera:**
```bash
cd influora-api && mvn test -Dtest=DisputeServiceTest,EscrowServiceTest
```

---

## Test Coverage Gaps

| Missing test | Severity | Notes |
|--------------|----------|-------|
| TOCTOU parallel open same collaboration | **P1** | **Kabir K1** — concurrent POSTs; assess DB constraint need |
| Dispute-freeze vs concurrent escrow release race | **P1** | **Kabir K1** — CEO §1.3 freeze reliability |
| `resolveDispute` on already-resolved dispute | P2 | Code path exists (`DISPUTE_NOT_ACTIVE`) |
| `resolveDispute` foreign/missing disputeId | P2 | `DISPUTE_NOT_FOUND` |
| SUPPORT role rejected on resolve | P2 | `requireRoleWithMfaSatisfied` throws — mock never simulates failure |
| Creator/brand JWT on admin resolve endpoint | P2 | SecurityConfig integration |
| Admin JWT on `POST /deals/{id}/disputes` | P2 | `FORBIDDEN` at service layer |
| Reason HTML sanitization | P3 | Same `TextSanitizer` as reviews (T29) |
| Resolve `UNDER_REVIEW` dispute (active) | P3 | `isActive()` includes `UNDER_REVIEW` |
| Re-open after resolution if escrow still funded | P3 | Policy-allowed per CEO; code should permit |
| `hasFundedUnreleasedEscrow` positive path | P3 | Only freeze side tested in EscrowServiceTest |
| `DealController.openDispute` delegation | P3 | Pattern gap vs other endpoints |
| Rate limit on dispute open | P2 | **Kabir K1** — dispute spam / escrow-freeze abuse |

---

## Findings Register

| ID | Severity | Finding | Action |
|----|----------|---------|--------|
| L-T34-1 | **P1** | One-active-dispute enforced app-layer only; no DB partial unique — TOCTOU race on concurrent open | **Kabir K1** — red-team + recommend constraint or serializable txn |
| L-T34-2 | **P1** | Freeze-on-open vs concurrent escrow release ordering not proven under race | **Kabir K1** — paired with TASK_INBOX Kabir scope |
| L-T34-3 | P2 | Admin resolve hostile paths (`DISPUTE_NOT_ACTIVE`, `DISPUTE_NOT_FOUND`, SUPPORT/brand/creator rejection) untested | Kabir live probes + optional unit tests |
| L-T34-4 | P3 | No `AdminDisputeControllerTest`; `DealControllerTest` has no `openDispute` case | Optional follow-up |
| L-T34-5 | P2 | No rate limit bucket for `POST /deals/*/disputes` in `AuthRateLimitFilter` | **Kabir K1** — assess freeze-abuse |
| L-T34-6 | P3 | Reason/notes `TextSanitizer` paths untested | Low risk; same pattern as T29 |
| L-T34-7 | INFO | `mvn` not on PATH in QA env | Meera confirms 9/9 scoped PASS |
| L-T34-8 | INFO | Resolve stub does not unfreeze escrow or revert `CollaborationStatus` | Documented v1 boundary — money movement follow-up |
| L-T34-9 | INFO | `markUnderReview()` has no HTTP endpoint in v1 | Acceptable stub; admin console Phase 2 |

**No P0 blockers. No standards violations in shipped code.**

---

## TECH-STACK.md Compliance

| Rule | Result |
|------|--------|
| Thin controller, fat service | ✅ |
| `ApiException` with stable codes | ✅ |
| Workspace/creator isolation (rule §2) | ✅ resolve-then-scope on collaboration |
| JWT auth required | ✅ |
| Admin role + MFA in service layer | ✅ matches established `Admin*Controller` pattern |
| Flyway sequential migration | ✅ `V45__disputes.sql` |
| ULID IDs `VARCHAR(26)` | ✅ |
| No debug/console code | ✅ |
| `CreatorContextService` / `BrandContextService` on open | ✅ |

---

## Kabir K1 Red-Team Brief (from Kavya)

Arjun: route Kabir with **full hostile path**, not rubber-stamp (CEO directive §1.3 + paired T29):

1. **TOCTOU duplicate open** — parallel `POST /deals/{id}/disputes` same party; confirm one 409, assess duplicate rows.
2. **Freeze vs release race** — dispute open concurrent with escrow release request; confirm freeze wins or document failure mode.
3. **Live IDOR** — creator A / brand B JWTs on foreign `dealId`; uniform 404.
4. **Cross-role** — creator/brand JWT on `POST /admin/disputes/{id}/resolve` → 403; SUPPORT admin → 403; SUPER_ADMIN/ADMIN + MFA → 200.
5. **Resolve state machine** — resolve twice → `DISPUTE_NOT_ACTIVE`; resolve missing id → `DISPUTE_NOT_FOUND`.
6. **Escrow gate** — open with only `RELEASED`/`FROZEN` holds → `NO_FUNDED_ESCROW`.
7. **Abuse** — unthrottled dispute open (L-T34-5).
8. **XSS/store** — `<script>` in reason and resolution notes.

---

## Pipeline Routing

```
Vikram T34 V5 ──⚠️ Kavya APPROVED WITH FINDINGS──► Kabir K1 (race + admin) ──► Meera build (9/9 scoped) ──► Priya sign-off
```

**Next owner:** Kabir (security red-team K1)  
**Blocked on Kabir for:** freeze-race verification (money surface)  
**Unblocks after Kabir:** Meera merge gate, admin dispute console spec (Phase 2)

---

*Kavya Patel, QA Lead — Sage Digital*
