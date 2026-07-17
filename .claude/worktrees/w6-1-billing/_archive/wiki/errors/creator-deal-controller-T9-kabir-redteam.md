# DealController + DealMessage Timeline Review — Task #9 (Kabir Red-Team)

**Auditor:** Kabir Singh (Offensive Security / Red-Team Lead)  
**Date:** 2026-07-09  
**Scope:** `DealController.java`, `DealService.java`, `DealMessage.java`, `DealDtos.java`, `CollaborationRepository` workspace/creator scoped lookups, `IdempotencyService` wiring on `accept`/`counter`, cross-check against Task #7 finding M-2 (`Collaboration.notes` XSS), frontend render paths (`MessageEventCard`, `creator-chat.tsx`)  
**Reference Spec:** `wiki/tech/creator/12_CREATOR_SECURITY_SPEC.md` §6.2–6.3, §7.1; Task #7 `wiki/errors/creator-campaign-apply-T7-kabir-redteam.md` (M-2 gate)

---

## Executive Summary

**VERDICT: PASS WITH FINDINGS**

Task #9's core security invariants hold:

1. **Access isolation is airtight** — every deal/message route resolves ownership before touching data. Creators use `findByIdAndCreatorId(dealId, principal.getUserId())`; brands use `findByIdAndWorkspaceId(dealId, workspace.getId())` with campaign join-through (mirrors audited `CollaborationRepository.findByWorkspaceId` pattern from Task #11). Uniform `404 DEAL_NOT_FOUND` on cross-tenant probes — no IDOR oracle.
2. **Role separation is enforced** — `accept`/`reject` are creator-only (`creatorContext.requireCreator` + `requireCreatorCollaboration`); `createProposal` is brand-only (`brandContext.requireBrandWorkspace`); `counter`/`messages` are correctly bi-directional behind `requireOwnedCollaboration`.
3. **Idempotency on money-adjacent transitions** — `accept` and `counter` wrap state mutations in `IdempotencyService.executeOnce` with scoped `scopeId` (creator `userId` / brand `workspaceId`). TOCTOU-safe insert-first pattern from Wave E2 audit.

**Blocking pre-prod finding (failed Task #7 gate condition):**

- **M-2 (MEDIUM → ACTIVE):** `Collaboration.notes` is now returned raw via `GET /deals/:id/messages` (`seedNotesMessage`) and `GET /deals/:id` (`lastMessage` fallback). Task #7 explicitly required XSS sanitization **before** Task #9 exposes `notes`. That gate was not met. Severity remains **MEDIUM** (not HIGH) because current SPA render paths use React text interpolation (`{event.content}`) — no `dangerouslySetInnerHTML` on message bodies — but spec §6.2 server-side rejection is still violated and the API surface is live.

**Additional MEDIUM (new in Task #9):**

- **M-9-1 (MEDIUM):** `DealMessage.content` from `sendMessage`, `counter`, and `createProposal` is stored and returned without sanitization — same class as M-2, expanded attack surface beyond legacy `notes`.

No Critical or High findings. **Does not block Meera build verify or Ananya deal-room wiring.** **Blocks production deploy of deal room** until shared `TextSanitizer` lands on all message ingress + egress paths (M-2 + M-9-1).

---

## 1. Access Isolation — `findByIdAndCreatorId` vs `findByIdAndWorkspaceId`

### 1a. Repository join-through (brand path)

```38:42:influora-api/src/main/java/com/influora/repository/CollaborationRepository.java
    @Query(
            "SELECT c FROM Collaboration c WHERE c.id = :id AND c.campaignId IN "
                    + "(SELECT ca.id FROM Campaign ca WHERE ca.workspaceId = :workspaceId)")
    Optional<Collaboration> findByIdAndWorkspaceId(
            @Param("id") String id, @Param("workspaceId") String workspaceId);
```

Collaborations carry no `workspace_id` column — trust boundary is `campaign.workspace_id`. This matches the audited pattern from Task #11 (`findByWorkspaceId` list query uses the same subselect). A brand cannot pivot a known `dealId` ULID into another workspace's collaboration.

### 1b. Central ownership gate

```330:352:influora-api/src/main/java/com/influora/service/DealService.java
    private Collaboration requireOwnedCollaboration(AuthPrincipal principal, String dealId) {
        UserType role = requireRole(principal);
        if (role == UserType.CREATOR) {
            return requireCreatorCollaboration(principal, dealId);
        }
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        return collaborationRepository
                .findByIdAndWorkspaceId(dealId, workspace.getId())
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "DEAL_NOT_FOUND", "Deal not found", HttpStatus.NOT_FOUND));
    }

    private Collaboration requireCreatorCollaboration(AuthPrincipal principal, String dealId) {
        creatorContext.requireCreator(principal);
        return collaborationRepository
                .findByIdAndCreatorId(dealId, principal.getUserId())
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "DEAL_NOT_FOUND", "Deal not found", HttpStatus.NOT_FOUND));
    }
```

Identity is always server-derived from JWT `AuthPrincipal` via `CreatorContextService` / `BrandContextService` — never from path-param user ids. Consistent with Task #11 PASS.

### 1c. IDOR probe matrix

| Endpoint | Ownership check | Foreign dealId probe |
|---|---|---|
| `GET /deals` | `loadCollaborations` scoped list | N/A (no id param) |
| `GET /deals/:id` | `requireOwnedCollaboration` | `404 DEAL_NOT_FOUND` |
| `POST /deals` (create) | `requireBrandWorkspace` + `requireWorkspaceCampaign` | Brand-only; campaign workspace match |
| `POST /deals/:id/accept` | `requireCreatorCollaboration` | `404` (brand gets `403 WRONG_USER_TYPE` first) |
| `POST /deals/:id/reject` | `requireCreatorCollaboration` | `404` |
| `POST /deals/:id/counter` | `requireOwnedCollaboration` | `404` |
| `GET /deals/:dealId/messages` | `requireOwnedCollaboration` | `404` |
| `POST /deals/:dealId/messages` | `requireOwnedCollaboration` | `404` |
| `POST /deals/:dealId/messages/read` | `requireOwnedCollaboration` | `404` |

Verified in `DealServiceTest`: `testAcceptRejectsForeignDeal`, `testBrandCannotReadForeignDeal`. **No IDOR found.**

**TODO #1 verdict: PASS.**

---

## 2. Idempotency Keys — `accept` / `counter`

### 2a. Wiring

```165:181:influora-api/src/main/java/com/influora/service/DealService.java
    public DealResponse accept(AuthPrincipal principal, String dealId, String idempotencyKey) {
        creatorContext.requireCreator(principal);
        Collaboration collaboration = requireCreatorCollaboration(principal, dealId);
        String scopeId = principal.getUserId();
        String key = resolveIdempotencyKey(idempotencyKey, "deal-accept:" + dealId);

        try {
            return idempotencyService.executeOnce(
                    key,
                    scopeId,
                    "deal.accept",
                    () -> doAccept(collaboration, principal));
        } catch (IdempotencyService.AlreadyInProgressException
                | IdempotencyService.AlreadyCompletedException raced) {
            Collaboration refreshed = requireCreatorCollaboration(principal, dealId);
            return toDealResponse(refreshed, principal, UserType.CREATOR);
        }
    }
```

```223:242:influora-api/src/main/java/com/influora/service/DealService.java
        String scopeId =
                role == UserType.CREATOR
                        ? principal.getUserId()
                        : brandContext.requireBrandWorkspace(principal).getId();
        String key = resolveIdempotencyKey(idempotencyKey, "deal-counter:" + dealId + ":" + body.amount());
        ...
            return idempotencyService.executeOnce(
                    key,
                    scopeId,
                    "deal.counter",
                    () -> doCounter(collaboration, principal, body, senderType));
```

- **Validation-before-mutation:** `canAccept()` / `canCounter()` run inside the wrapped action after key reservation — correct per Wave E2 HIGH-1 fix pattern (state checks inside `executeOnce` callback is acceptable here because failed transitions mark key FAILED and are reclaimable).
- **Scope partitioning:** creator accept keys scoped to `principal.getUserId()`; brand counter keys scoped to `workspace.getId()`. Prevents cross-principal replay within the flat `idempotency_keys` PK namespace for auto-derived keys (deal ULIDs are globally unique).
- **Race handling:** `AlreadyInProgress` / `AlreadyCompleted` catch returns refreshed deal state — acceptable UX; no double state transition observed.

### 2b. Gaps (non-blocking)

| Item | Severity | Notes |
|---|---|---|
| `sendMessage` / `markRead` / `reject` lack idempotency | **LOW** | Duplicate messages possible on double-submit; `reject` is state-machine-idempotent (`canReject()` blocks second call) |
| Auto-derived counter key includes `body.amount()` | **LOW** | Different amounts = different keys (intentional — each counter is a distinct negotiation beat) |
| Flat `idempotency_keys` PK (E2 MEDIUM-2) | **LOW** | Pre-existing structural debt; mitigated here by globally unique deal ULIDs in auto-fallback keys |

**TODO #2 verdict: PASS** (money-adjacent transitions protected; minor gaps are LOW).

---

## 3. M-2 Severity Re-Assessment — `Collaboration.notes` via `GET /deals/:id/messages`

### 3a. New API render paths (Task #9)

**Path A — timeline seed:**

```252:254:influora-api/src/main/java/com/influora/service/DealService.java
        if (rows.isEmpty() && collaboration.getNotes() != null && !collaboration.getNotes().isBlank()) {
            return List.of(seedNotesMessage(collaboration));
        }
```

```525:543:influora-api/src/main/java/com/influora/service/DealService.java
    private DealMessageResponse seedNotesMessage(Collaboration collaboration) {
        ...
        return new DealMessageResponse(
                "seed-" + collaboration.getId(),
                ...
                collaboration.getNotes(),
                ...
    }
```

**Path B — deal list/detail preview:**

```491:491:influora-api/src/main/java/com/influora/service/DealService.java
                lastMsg.map(DealMessage::getContent).orElse(collaboration.getNotes()),
```

Task #7 M-2 status was **OPEN — fix before Task #9 exposes `notes`**. Task #9 merged without sanitizer. Gate condition **not met**.

### 3b. Frontend sink analysis

| Component | Render method | XSS executable? |
|---|---|---|
| `MessageEventCard` (`message-card.tsx:48`) | `<p>{event.content}</p>` | **No** — React escapes text nodes |
| `creator-chat.tsx:895` | `<p>{event.content}</p>` | **No** |
| `collaboration-timeline.tsx` | delegates to `TimelineEventCard` → `MessageEventCard` | **No** |

No `dangerouslySetInnerHTML` on deal message content anywhere in `src/`. Current SPA is **not exploitable** for script execution via stored notes.

### 3c. Severity decision

| Factor | Assessment |
|---|---|
| Task #7 gate ("fix before Task #9") | **Failed** — notes now in API responses |
| Spec §6.2 server-side XSS rejection | **Violated** |
| Active DOM XSS in current SPA | **Not exploitable** (React default escaping) |
| Future clients / unsafe renderers | **Risk remains** |
| Stored payload in `lastMessage` deal list | Visible as literal text to brand user |

**M-2 severity: remains MEDIUM, status upgraded from LATENT → ACTIVE.**

Not elevated to **HIGH** because: (1) no script execution path exists in the wired/reviewed UI, (2) CSP is deployed as secondary mitigation per `SecurityConfig`, (3) attack requires a creator to poison their own apply message visible only to the counterparty brand on owned deals — limited blast radius.

**Does block production deploy of deal room** (same posture as Task #7 prod gate). **Does not block sprint QA/build gates.**

---

## 4. Message Content XSS — `DealMessage` Timeline (Task #9-native vectors)

### 4a. Unsanitized ingress paths

| Source | Field | Persisted to | Returned via |
|---|---|---|---|
| `POST /deals/:dealId/messages` | `SendMessageRequest.content` | `deal_messages.content` | `GET /deals/:id/messages` |
| `POST /deals/:id/counter` | `CounterRequest.message` | `deal_messages.content` (proposal kind) | messages + `lastMessage` |
| `POST /deals` (create) | `CreateDealRequest.message` | `deal_messages.content` (proposal kind) | messages + `lastMessage` |
| `POST /deals/:id/reject` | `RejectRequest.reason` | `deal_messages.content` (system kind) | messages |

All paths: `@Size` bounded, **no HTML sanitization**.

### 4b. Finding M-9-1

**M-9-1 (MEDIUM):** Deal room message content is stored and served raw. Same remediation as M-2: shared `TextSanitizer.sanitizePlainText()` at write time (preferred) or consistent egress encoding policy documented and enforced.

**TODO #4 verdict: MEDIUM finding filed; PASS WITH FINDINGS for sprint gate.**

---

## 5. Additional Checks

### 5a. Privilege separation — PASS

- Creator calling `POST /deals` → `brandContext.requireBrandWorkspace` → `403 WRONG_USER_TYPE`
- Brand calling `POST /deals/:id/accept` → `creatorContext.requireCreator` → `403 WRONG_USER_TYPE`
- `requireRole` rejects non-brand/non-creator principals → `403`

### 5b. Mass assignment — PASS

DTOs are narrow records. No client control of `collaboration.status`, `agreedRate`, or `senderId` (derived from principal).

### 5c. `createProposal` creatorId param — ACCEPTED

`CreateDealRequest.creatorId` is intentional (brand selects target creator). Campaign workspace scoping prevents cross-tenant proposal injection.

### 5d. Rate limiting — OPEN (inherits M-1 posture)

No deal-message rate limit. Same class as apply/invite gap. **LOW** for Task #9 scope (not escalated).

---

## Findings Summary

| ID | Severity | Area | Status |
|---|---|---|---|
| M-2 | **MEDIUM (ACTIVE)** | `Collaboration.notes` returned raw via `seedNotesMessage` + `lastMessage` fallback — Task #7 gate not met | **OPEN — prod blocker for deal room** |
| M-9-1 | **MEDIUM** | `DealMessage.content` unsanitized on send/counter/create/reject | **OPEN — prod blocker (same fix as M-2)** |
| M-1 | MEDIUM | Apply rate limit (Task #7 carry-over) | OPEN — pre-prod |
| L-9-1 | LOW | `sendMessage`/`markRead` no idempotency — duplicate messages on retry | Open |
| L-9-2 | LOW | Flat `idempotency_keys` PK namespace (E2 structural debt) | Accepted |
| L-9-3 | LOW | No hostile integration test for cross-creator message IDOR (unit tests cover service layer only) | Kavya Task #13 scope |

---

## Go/No-Go Decision

| Gate | Decision |
|---|---|
| Task #9 Kabir security sign-off | **PASS WITH FINDINGS** |
| Block Meera build verify (`mvn test` + V33) | **NO** |
| Block Ananya deal-room API wiring | **NO** |
| Block production deploy of deal room | **YES until M-2 + M-9-1 resolved** (shared sanitizer) |

**Follow-up for Vikram:** Land `TextSanitizer.sanitizePlainText()` on `Collaboration.apply()`/`invite()`/`propose()` (M-2 ingress) **and** `DealService.sendMessage` / `persistProposalMessage` / `appendSystemMessage` (M-9-1) in a small hardening PR before prod. Kabir will re-check on request.

**Escalation:** None to Priya/Swapnil — no Critical/High. M-2 ACTIVE status is expected debt from Task #7 routing, now triggered.
