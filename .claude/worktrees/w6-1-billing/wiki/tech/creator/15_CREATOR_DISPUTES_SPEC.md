# Creator Disputes Specification (v1)

> **Owner:** Vikram (Backend)  
> **Security:** Kabir  
> **QA:** Kavya  
> **Policy:** `CREATOR_CEO_INSTRUCTIONS_SWAPNIL.md` §1.3 (Swapnil interim ruling 2026-07-09)  
> **Shipped:** Task #34 V5 (backend stub — in flight / code landed)  
> **Admin UI:** Phase 2 — separate admin-portal ticket (not creator-surface scope)

---

## 1. Policy Summary (CEO §1.3)

| Rule | v1 implementation |
|------|-------------------|
| **Admin arbitrates** | Dispute creates admin-facing case; no auto brand-vs-creator resolution |
| Unreleased escrow **freezes on open** | `FUNDED` holds → `FROZEN`; no auto-refund, no auto-release |
| Released funds **not clawed back** | Already-`RELEASED` holds untouched; disputes on released funds are admin-mediated outside payment rail |
| **Either party** may open | Creator or brand via `POST /deals/{id}/disputes` |
| **One active dispute** per collaboration | At most one `OPEN` or `UNDER_REVIEW` dispute per `collaboration_id` |
| Prerequisite | At least one **funded, unreleased** escrow hold on the collaboration |
| Resolution | Admin-only; status transition stub — **no money movement in v1** |

Full legal policy (refund percentages, SLA, appeals) is a **follow-up**, not a blocker for v1 shipping.

---

## 2. Dispute Flow Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    DISPUTE LIFECYCLE (v1)                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Either party (creator or brand) on a deal with funded escrow                │
│         │                                                                    │
│         ▼                                                                    │
│  POST /deals/{dealId}/disputes  { reason }                                   │
│         │                                                                    │
│         ├─► disputes row (status=OPEN)                                       │
│         ├─► EscrowService.freezeUnreleasedForDispute()  FUNDED → FROZEN     │
│         └─► Collaboration.status → DISPUTED (if not already)                 │
│         │                                                                    │
│         ▼                                                                    │
│  Admin reviews case (admin console — Phase 2 UI)                             │
│         │                                                                    │
│         ▼                                                                    │
│  POST /admin/disputes/{disputeId}/resolve                                    │
│         { resolution: RESOLVED_*, notes? }                                   │
│         │                                                                    │
│         └─► status → RESOLVED_BRAND | RESOLVED_CREATOR | RESOLVED_SPLIT      │
│             (no automatic refund/release/clawback in v1)                     │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Status Machine

```
                    ┌─────────────┐
                    │    OPEN     │  ← created on POST /deals/{id}/disputes
                    └──────┬──────┘
                           │ admin picks up (future) or direct resolve
                           ▼
                 ┌──────────────────┐
                 │  UNDER_REVIEW      │  ← optional; markUnderReview() exists on entity
                 └────────┬───────────┘
                          │ admin resolve (v1: may skip UNDER_REVIEW)
          ┌───────────────┼───────────────┐
          ▼               ▼               ▼
   RESOLVED_BRAND   RESOLVED_CREATOR   RESOLVED_SPLIT
          │               │               │
          └───────────────┴───────────────┘
                    terminal (isResolved() = true)
```

| Status | Active? | Escrow effect | Who can set |
|--------|---------|---------------|-------------|
| `OPEN` | ✅ | Freeze unreleased holds | System on create |
| `UNDER_REVIEW` | ✅ | Holds stay `FROZEN` | Admin (future) or entity helper |
| `RESOLVED_BRAND` | ❌ | **No auto money movement v1** | Admin `SUPER_ADMIN` or `ADMIN` |
| `RESOLVED_CREATOR` | ❌ | **No auto money movement v1** | Admin |
| `RESOLVED_SPLIT` | ❌ | **No auto money movement v1** | Admin |

**v1 resolve stub:** `DisputeService.resolveDispute()` transitions status + stores `resolved_by_admin_id`, `resolution_notes`, `resolved_at` only. Escrow unfreeze, partial refund, and milestone release are **Phase 2 money-path work**.

---

## 4. Database Schema

### 4.1 Migration

File: `influora-api/src/main/resources/db/migration/V45__disputes.sql`

```sql
CREATE TABLE disputes (
  id                    VARCHAR(26) PRIMARY KEY,
  collaboration_id      VARCHAR(26) NOT NULL,
  opened_by_type        ENUM('CREATOR','BRAND') NOT NULL,
  opened_by_user_id     VARCHAR(26) NOT NULL,
  reason                TEXT NOT NULL,
  status                ENUM(
                          'OPEN',
                          'UNDER_REVIEW',
                          'RESOLVED_BRAND',
                          'RESOLVED_CREATOR',
                          'RESOLVED_SPLIT'
                        ) NOT NULL DEFAULT 'OPEN',
  resolved_by_admin_id  VARCHAR(26) NULL,
  resolution_notes      TEXT NULL,
  resolved_at           TIMESTAMP NULL,
  created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_dispute_collaboration (collaboration_id),
  INDEX idx_dispute_status (status),
  CONSTRAINT fk_dispute_collaboration FOREIGN KEY (collaboration_id) REFERENCES collaborations(id),
  CONSTRAINT fk_dispute_opened_by FOREIGN KEY (opened_by_user_id) REFERENCES users(id),
  CONSTRAINT fk_dispute_resolved_by FOREIGN KEY (resolved_by_admin_id) REFERENCES admin_users(id)
);
```

**One-active-dispute:** enforced in `DisputeService` via `existsByCollaborationIdAndStatusIn(collaborationId, {OPEN, UNDER_REVIEW})`. No partial unique index in v1 — service-layer gate + Kabir race review required.

### 4.2 Entity (shipped)

```java
@Entity
@Table(name = "disputes")
public class Dispute {
    @Id private String id;
    private String collaborationId;
    @Enumerated(EnumType.STRING) private DisputeOpenerType openedByType;
    private String openedByUserId;           // JWT-derived
    private String reason;                     // TextSanitizer; required
    @Enumerated(EnumType.STRING) private DisputeStatus status;
    private String resolvedByAdminId;          // admin_users.id
    private String resolutionNotes;
    private Instant resolvedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
```

### 4.3 Enums

```java
public enum DisputeOpenerType { CREATOR, BRAND }

public enum DisputeStatus {
    OPEN, UNDER_REVIEW,
    RESOLVED_BRAND, RESOLVED_CREATOR, RESOLVED_SPLIT;

    public boolean isActive() { return this == OPEN || this == UNDER_REVIEW; }
    public boolean isResolved() { /* RESOLVED_* only */ }
}
```

---

## 5. Escrow Freeze Integration

### 5.1 Open dispute — freeze path

On successful `openDispute()`:

1. `escrowService.hasFundedUnreleasedEscrow(collaborationId)` must be true — else `409 NO_FUNDED_ESCROW`
2. Save `Dispute` with `status = OPEN`
3. `escrowService.freezeUnreleasedForDispute(collaborationId)` — all `FUNDED` holds for collaboration → `FROZEN`
4. `collaboration.transitionTo(DISPUTED)` if not already

### 5.2 EscrowService methods (shipped)

```java
/** FUNDED → FROZEN for all unreleased holds on collaboration. Idempotent if already FROZEN. */
public int freezeUnreleasedForDispute(String collaborationId);

/** True if ≥1 FUNDED hold linked to collaboration (direct or via milestone.escrow_hold_id). */
public boolean hasFundedUnreleasedEscrow(String collaborationId);
```

### 5.3 Release blocked while frozen

`EscrowService.release()` must reject or no-op on `FROZEN` holds (verify in implementation — Kabir K1 race review scope). **Attack surface:** concurrent `POST /wallet/escrow/release` vs `POST /deals/{id}/disputes` — ledger/hold-status serialization required.

### 5.4 Released funds

Holds already `RELEASED` are **never** modified by dispute open. CEO §1.3: no automatic clawback mechanism.

---

## 6. API Endpoints (v1)

### 6.1 Open dispute (creator or brand)

```
POST /api/v1/deals/{dealId}/disputes
Authorization: Bearer <creator-jwt | brand-jwt>

Request:
{
  "reason": "Deliverable approved but brand refuses to acknowledge completion."
}

Response 201:
{
  "success": true,
  "data": {
    "id": "01H...",
    "collaborationId": "01H...",
    "openedByType": "CREATOR",
    "openedByUserId": "01H...",
    "reason": "Deliverable approved but brand refuses to acknowledge completion.",
    "status": "OPEN",
    "createdAt": "2026-07-09T15:00:00Z"
  }
}
```

**Controller:** `DealController.openDispute()`  
**Service:** `DisputeService.openDispute()`

**Identity:**
- Creator: `CreatorContextService.requireCreator` → `findByIdAndCreatorId(dealId, principal.getUserId())`
- Brand: `BrandContextService.requireBrandWorkspace` → `findByIdAndWorkspaceId(dealId, workspace.getId())`
- Admin JWT → `403 FORBIDDEN` ("Only creators and brands can open disputes")

### 6.2 Admin resolve (status stub)

```
POST /api/v1/admin/disputes/{disputeId}/resolve
Authorization: Bearer <admin-jwt> + MFA satisfied

Request:
{
  "resolution": "RESOLVED_CREATOR",
  "notes": "Deliverable met contract terms; brand acknowledgment overdue."
}

Response 200:
{
  "id": "01H...",
  "collaborationId": "01H...",
  "openedByType": "CREATOR",
  "openedByUserId": "01H...",
  "reason": "...",
  "status": "RESOLVED_CREATOR",
  "createdAt": "..."
}
```

**Controller:** `AdminDisputeController`  
**Service:** `DisputeService.resolveDispute()`  
**Auth:** `AdminContextService.requireRoleWithMfaSatisfied(principal, SUPER_ADMIN, ADMIN)`

**v1 constraint:** resolution does **not** call `EscrowService.release()`, `refund()`, or wallet ledger mutations.

### 6.3 Not in v1

| Endpoint | Phase |
|----------|-------|
| `GET /deals/{id}/disputes` | P2 — party read own dispute status |
| `GET /admin/disputes` | P2 — admin dispute console list |
| `POST /admin/disputes/{id}/under-review` | P2 — explicit UNDER_REVIEW transition |
| Money movement on resolve | P2 — requires Rohan/legal + Priya money-path approval |

---

## 7. State Gates & Error Codes

### 7.1 Open dispute gates

| Gate | Error |
|------|-------|
| Not deal party | `404 DEAL_NOT_FOUND` |
| No funded unreleased escrow | `409 NO_FUNDED_ESCROW` |
| Active dispute already exists | `409 DISPUTE_ALREADY_OPEN` |
| Not creator/brand JWT | `403 FORBIDDEN` |
| Blank reason after sanitize | `400` / `IllegalArgumentException` |

### 7.2 Resolve gates

| Gate | Error |
|------|-------|
| Not admin / MFA | `403` via `AdminContextService` |
| Dispute not found | `404 DISPUTE_NOT_FOUND` |
| Already resolved | `409 DISPUTE_NOT_ACTIVE` |
| Invalid resolution enum | `400 INVALID_RESOLUTION` |

---

## 8. DTO Validation

```java
public record OpenDisputeRequest(@NotBlank @Size(max = 2000) String reason) {}

public record ResolveDisputeRequest(
    @NotNull DisputeStatus resolution,   // must be RESOLVED_*
    @Size(max = 2000) String notes) {}
```

`opened_by_type` and `opened_by_user_id` are server-derived from JWT + endpoint role.

---

## 9. Collaboration Status Interaction

| Event | `Collaboration.status` |
|-------|------------------------|
| Dispute opened | → `DISPUTED` (if not already) |
| Dispute resolved (v1) | **Unchanged** — status transition on resolve is Phase 2 |

**Cross-policy note:** Reviews require `COMPLETED` (§1.2); `DISPUTED` collaborations cannot be reviewed. Disputes opened before `COMPLETED` are allowed when escrow is funded — typical mid-deal disagreement scenario.

---

## 10. Security Requirements (Kabir K1 scope)

| Requirement | v1 posture |
|-------------|------------|
| IDOR on open | Join-scoped `dealId` — same pattern as `DealService` |
| One active dispute race | Service check + Kabir concurrent-open review |
| Dispute-freeze vs release race | **LOAD-BEARING** — freeze must win or release must reject `FROZEN` holds |
| Admin-only resolve | `AdminContextService` + MFA |
| XSS on reason/notes | `TextSanitizer` on open + resolve |
| Rate limits on open | Recommend `deal-dispute-open` bucket (follow-up) |

---

## 11. Tests (shipped / target)

| Class | Target | Coverage |
|-------|--------|----------|
| `DisputeServiceTest` | 8/8 | IDOR, one-active-dispute, NO_FUNDED_ESCROW, freeze called, admin resolve |
| `EscrowServiceTest` | +1 | `freezeUnreleasedForDispute` |
| `DealControllerTest` | updated | Delegation to `DisputeService` |

**Meera gate command:**
```bash
cd influora-api && mvn test -Dtest=DisputeServiceTest,EscrowServiceTest
```

---

## 12. Frontend (out of v1 creator scope)

No creator-facing dispute UI in Task #34. Future options:
- "Open dispute" action in `creator-chat.tsx` / `brand-chat.tsx` deal room (gated on funded escrow)
- Dispute status badge on deal timeline
- Admin dispute console (`src/admin/`) — separate ticket

Until UI ships, API is callable by integrated clients only.

---

## 13. Phase 2 — Money Movement on Resolve (not v1)

Requires separate CEO/legal sign-off and Priya architecture review:

| Resolution | Intended money outcome (policy TBD) |
|------------|-----------------------------------|
| `RESOLVED_BRAND` | Refund unreleased escrow to brand wallet |
| `RESOLVED_CREATOR` | Release frozen holds to creator (net of platform fee) |
| `RESOLVED_SPLIT` | Partial release/refund per admin notes |

**v1 explicitly does not implement any of the above.** Admin records outcome; finance executes manually or via Phase 2 automation.

---

## 14. Definition of Done (v1)

- [x] `Dispute` entity + `V45__disputes.sql`
- [x] `POST /deals/{dealId}/disputes` (creator + brand)
- [x] `POST /admin/disputes/{disputeId}/resolve` (admin + MFA)
- [x] One active dispute per collaboration
- [x] Unreleased escrow freeze on open (`FUNDED` → `FROZEN`)
- [x] No automatic refund/clawback
- [x] `TextSanitizer` on reason + resolution notes
- [x] Unit tests (`DisputeServiceTest` 8/8 target)
- [ ] Kabir K1 — dispute-freeze vs concurrent release race
- [ ] Kavya Kv1 hostile-path QA
- [ ] Meera build verify
- [ ] Admin dispute console UI (admin portal — separate)
- [ ] Phase 2 money movement on resolve

---

*Priya Sharma (CTO) — spec authored 2026-07-09. Aligned to shipped Task #34 V5 code in flight.*
