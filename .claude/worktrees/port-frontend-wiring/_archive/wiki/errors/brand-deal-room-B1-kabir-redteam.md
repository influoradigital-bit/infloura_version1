# Brand Deal Room (B-1, 60% → live) — Kabir red-team / OWASP security pass

**Item:** `wiki/tech/BRAND_ADMIN_PENDING_WORK.md` PART 1 / P1 — "Deal Room (60% → live)."
**Reviewer:** Kabir (Red-Team). **Date:** 2026-07-11.
**Inputs read:** Vikram backend handoff, Ananya frontend handoff, Kavya QA note (SHARED_CONTEXT
M-2), and the actual working-tree code (not the narratives).

## VERDICT: PASS WITH FINDINGS

**Critical: 0 · High: 0 · Medium: 1 · Low: 1** (+ 1 process/traceability blocker, non-severity).

The new backend route `GET /deals/{dealId}/deliverables` is **clean** — workspace-isolated,
IDOR-safe, uniform 404, no field leak. The Medium/Low are on the **shared `sendMessage` path** that
the brand role now exercises (pre-existing, in-scope per the brief). The blocker is that the frontend
half of the requested review is **not present in the working tree** (see §Blocker).

---

## What I verified SECURE

### 1. IDOR / workspace isolation on `GET /deals/{dealId}/deliverables` — PASS
- `DealService.listDeliverables` (`DealService.java:312-320`) resolves the collaboration via
  `requireOwnedCollaboration` (`DealService.java:354-366`) **before** any deliverable read.
- Brand path → `collaborationRepository.findByIdAndWorkspaceId(dealId, workspace.getId())`. The query
  (`CollaborationRepository.java:38-42`) scopes `c.id = :id AND c.campaignId IN (campaigns WHERE
  workspaceId = :workspaceId)` — a brand in workspace A probing a deal in workspace B gets an empty
  Optional → uniform `404 DEAL_NOT_FOUND`. No existence oracle.
- Workspace id comes from the JWT (`BrandContextService.requireBrandWorkspace` →
  `principal.getWorkspaceId()` / active `WorkspaceMember`, `BrandContextService.java:34-56`) — **never**
  a request/path param. Not spoofable.
- Creator path → `findByIdAndCreatorId` (owner-scoped), same uniform 404.
- Deliverables are then fetched by the **already-verified** `collaboration.getId()`, not by user input
  (`DealService.java:315-318`) — no second IDOR surface.
- `/deals/**` is `authenticated()` (SecurityConfig.java:89-90; not in the permitAll set); fails closed
  even if principal were null (`requireRole` → 403).

### 2. Widened `CreatorDeliverableService.toListItem` visibility — PASS (no field leak)
- Change is a Java access modifier only (`private` → package-`static`, `CreatorDeliverableService.java:673`).
  It does **not** alter what is serialized. `DeliverableListItem` shape is byte-identical for brand and
  creator: `id, title, description, status, completed, currentRevision, maxRevisions`.
- `description` falls back to `reviewNotes` (`:679-682`) — brand-authored review feedback on the brand's
  own deal; not a cross-party leak. No files/URLs/PII/internal keys exposed (contrast the richer
  `DeliverableStatusResponse`, which is a different DTO and unchanged).

### 3. TextSanitizer on message ingress — PASS
- `sendMessage` applies `TextSanitizer.sanitizePlainText(body.content())` before persist
  (`DealService.java:283`). Same on reject reason (`:203`), proposal (`:459`), system (`:472`).
- `SendMessageRequest.content` is `@NotBlank @Size(max=5000)` (`DealDtos.java:73`) — bounded.

### 4. Cross-workspace on brand message send/list — PASS
- `listMessages` / `sendMessage` / `markRead` all gate on `requireOwnedCollaboration`
  (`DealService.java:253, 271, 291`) — same load-bearing boundary as the deliverables list. senderType
  is forced from JWT role (`:273`), so a caller cannot forge `senderId`/`senderType`.

### 5. Idempotency on money-adjacent writes — PASS
- `accept` / `counter` use `idempotencyService.executeOnce` (`DealService.java:177-187, 238-248`).
  The new deliverables route is a read-only GET — no idempotency needed.

---

## Findings

### M-1 (MEDIUM) — `sendMessage` trusts attacker-controlled `kind`; no server-side allow-list
**`DealService.java:274` + `DealDtos.java:72-74`**
`SendMessageRequest.kind` (`DealMessageKind`) has **no validation constraint**, and
`sendMessage` uses it verbatim: `kind = body.kind() != null ? body.kind() : text`. An authenticated
party **in a deal they legitimately own** can POST a message with
`kind = system | proposal | contract | payment | deliverable | shipment`. `senderType` is still forced
to `brand`/`creator` (not spoofable) and `content` is XSS-sanitized, but a chat feed that renders inline
cards by `kind` (creator-chat.tsx already does; brand-chat is slated to) would display a forged
"system"/"payment"/"contract" card carrying attacker-chosen text — an intra-deal message-integrity /
social-engineering vector ("Payment of ₹X released", fake system notice).
- **Bounded:** requires legitimate collaboration ownership (no cross-tenant escalation), sender identity
  intact, `metadata` is null for user messages (`:284`) so rich cards degrade.
- **Pre-existing** in the shared path, but explicitly **in scope** — the brand role now exercises this
  send path and the feed will render kind-based cards.
- **Fix:** allow-list user-settable kinds server-side (`text`, arguably `shipment`); keep
  `system/proposal/contract/payment/deliverable` server-generated only (as `persistProposalMessage` /
  `appendSystemMessage` already do). One guard in `sendMessage` or a DTO validator.

### L-1 (LOW, non-blocking) — `sendMessage` write has no idempotency
**`DealService.java:268-287`** — POST with no idempotency key; a double-submit persists a duplicate
message. Not money-adjacent. Consistent with the LOW logged on `deals.counter` in the earlier
Deals/Contracts cycles and with Vikram's own carried-forward note (client sends an `Idempotency-Key`
that the server ignores). Carry forward; do not block on it.

---

## BLOCKER (process / traceability — not a CVSS severity, but gates the frontend half of this review)

**The frontend wiring I was asked to review is not in the working tree.**
- `src/pages/brand-chat.tsx` and `src/lib/api.ts` show **zero diff vs HEAD** (`git diff HEAD` empty; both
  are the committed baseline).
- `brand-chat.tsx` (1197 lines) still uses the **local-only** synchronous chat: `chatMessages`
  (`:404`) + non-async `handleSendMessage` (`:491`). Symbol counts in the tree:
  `liveMessages=0, loadMessages=0, brandDeliverableRows=0, loadBrandDeliverables=0,
  deliverables.list=0, isApiLive=0` — i.e. **none** of Ananya's described wiring is present.
- Kavya's M-2 line ref (`brand-chat.tsx:830-838`, "move `setBrandDeliverableRows([])`") points at a
  **proposal card render** in the current tree; `setBrandDeliverableRows` does not exist here.
- The frontend work appears to live elsewhere — `git stash list` shows two WIP stashes on this branch,
  and there are `claude/*` side branches. I did **not** apply/inspect them (READ-ONLY; would pull in
  another agent's uncommitted work).

**Consequence:** the backend security posture is enforced server-side and stands on its own (PASS). But
the **frontend render/XSS pass** the brief asked for (how the now-live `DealMessage[]` — including
attacker-influenced `content`/`kind` from M-1 — is rendered) **cannot be completed** against code that
isn't in the tree. The M-1 spoofed-card impact specifically depends on the unreviewed render layer.

**Needed before final gate:** land Ananya's brand-chat.tsx / api.ts wiring into the reviewed tree
(un-stash or merge the correct branch), then re-request the frontend security pass. Confirm no
`dangerouslySetInnerHTML` on message `content` and that card rendering does not treat a
user-`kind` message as authoritative.

---

## Scope reviewed (working tree, as-is)
- `influora-api/.../web/DealController.java` (new `@GetMapping("/{dealId}/deliverables")`, untracked)
- `influora-api/.../service/DealService.java` (new `listDeliverables`, untracked)
- `influora-api/.../service/CreatorDeliverableService.java` (`toListItem` visibility, untracked)
- `influora-api/.../web/dto/deal/DealDtos.java`, `CollaborationRepository.java`,
  `DeliverableRepository.java`, `BrandContextService.java`, `SecurityConfig.java` (isolation supports)
- `src/lib/api.ts`, `src/pages/brand-chat.tsx` — **baseline only; wiring absent (see Blocker)**

## Recommendation to Arjun / Kavya
Backend B-1 deliverables route: **clear to proceed to Meera build gate.** Land M-1 (server-side kind
allow-list) in the same cycle since brand chat is about to render kind-based cards — cheap, one guard.
Resolve the Blocker (get the real frontend into the tree) and re-run the brand-chat render pass before
Priya's final sign-off. L-1 is non-blocking, track as backlog with the existing idempotency note.
