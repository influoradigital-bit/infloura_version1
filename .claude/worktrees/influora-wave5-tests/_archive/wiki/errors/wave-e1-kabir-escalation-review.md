# Wave E1 — Kabir Escalation Review (Adversarial Confirmation)

**Auditor:** Kabir (Red-Team)
**Date:** 2026-07-07
**Input:** `wiki/errors/wave-e1-workspace-isolation-audit.md` (Kavya, Part 3 items #4/#8/#2 → "Kabir Escalation" section)
**Method:** Manual file:line trace of every call site, no reliance on Kavya's structural read.

---

## Verdict summary

| # | Item | Verdict |
|---|------|---------|
| 1 | Collaboration workspace propagation | **GENUINE — HIGH, one call site broken** (`ContractService.generate`) |
| 2 | IdempotencyKeyRecord global key namespace | **NOT GENUINE** |
| 3 | AffiliateEarning settlement isolation | **NOT GENUINE** |

---

## 1. Collaboration workspace propagation — GENUINE (HIGH)

Traced every real `collaborationRepository.findById(...)` call site in `influora-api` (6 total; `existsByCampaignIdAndCreatorId`/`findByCampaignId`/`.save()` call sites excluded as not relevant — they don't accept a caller-supplied collaboration id from outside its own workspace).

| Call site | Post-`findById` workspace check? | Verdict |
|---|---|---|
| `PayoutService.validateForPayout` (`PayoutService.java:187-195`) | Milestone→EscrowHold is resolved and `hold.getWorkspaceId().equals(workspaceId)` is checked **before** the collaboration lookup (line 177); collaboration is only used afterward for the payee id. Safe. | PASS |
| `EscrowService.release` (`EscrowService.java:253-267`) | Collaboration is loaded to get `payeeUserId` only; the actual authorization is `hold.getWorkspaceId().equals(workspaceId)` at line 265, checked on the escrow hold reached via `milestone.getEscrowHoldId()`. Since `milestone` itself was found by raw `findById` too, the real guarantee rests entirely on the hold check, which is present. Safe. | PASS |
| `CampaignLinkService.createTrackingLink` (`CampaignLinkService.java:110-125`) | Explicit `if (!collaboration.getCampaignId().equals(campaign.getId()))` → 403, where `campaign` was already resolved via `findByIdAndWorkspaceId`. Textbook resolve-then-scope. Safe. | PASS |
| `DeliverableMetricService.submit` (`DeliverableMetricService.java:80-93`) | No workspace check at all — but the authorization here is intentionally creator-identity-based (`collaboration.getCreatorId().equals(principal.getUserId())`, line 90), not workspace-based, because the caller is the CREATOR reporting their own deliverable, not a brand. A creator can only ever supply a `milestoneId`, and can only pass the check if they themselves are that milestone's collaboration's creator — there is no cross-workspace angle here since no workspace is asserted or spoofed. Safe (different threat model, correctly handled). | PASS |
| `ContractService.generate` (`ContractService.java:100-108`) | **NONE.** See below. | **FAIL** |
| `ContractService.generateAndDeliverContractPdf` (`ContractService.java:309-317`) | This is a private, internal helper called only from within `generate`/`recordSignature` using `contract.getCollaborationId()` — i.e., the collaboration id it reads back was the one `generate` already wrote onto the `Contract` row, not a fresh caller-supplied value. Not independently exploitable. | PASS (derivative of #5) |

### The genuine gap: `ContractService.generate`

`influora-api/src/main/java/com/influora/service/ContractService.java:96-108`:

```java
public ContractResponse generate(AuthPrincipal principal, String workspaceId, ContractGenerateRequest req) {
    WorkspaceMember member = brandContext.requireMember(principal, workspaceId);
    brandContext.requireRole(member, MemberRole.OWNER, MemberRole.ADMIN, MemberRole.MANAGER);

    Collaboration collaboration =
            collaborationRepository
                    .findById(req.collaborationId())
                    .orElseThrow(...);
    ...
    Contract contract =
            Contract.builder()
                    .id(Ulids.newUlid())
                    .collaborationId(collaboration.getId())
                    .workspaceId(workspaceId)          // <-- caller's OWN workspace
                    .totalAmount(totalAmount)
                    ...
```

`brandContext.requireMember(principal, workspaceId)` only proves the caller is a member of *their own* workspace (`workspaceId` comes from `ContractController.generate:38`, resolved via `brandContext.requireBrandWorkspace(principal)` — i.e. "whichever workspace this authenticated brand user belongs to"). It proves nothing about `req.collaborationId()`, which is fully attacker-controlled input (`ContractGenerateRequest.collaborationId` is `@NotBlank String`, `MoneyDtos.java:116-117` — no format/ownership constraint beyond non-blank).

`Collaboration` (`domain/entity/Collaboration.java`) has **no `workspace_id` column at all** — only `campaignId`/`creatorId`. Every other call site in the codebase that loads a `Collaboration` by raw id (`PayoutService`, `EscrowService`, `CampaignLinkService`) either derives workspace from a sibling `EscrowHold`/`Campaign` row that IS independently workspace-checked, or checks `collaboration.getCampaignId()` against an already-workspace-scoped `Campaign`. `ContractService.generate` does neither — it never loads the `Campaign` at all, so there is nothing to cross-check `collaboration.getCampaignId()` against.

**Exact exploitable scenario:**

1. Brand B has a legitimate campaign with creator X: `Collaboration{id=COLLAB_B, campaignId=CAMP_B, creatorId=X}`.
2. Brand A (a completely unrelated, authenticated brand user, member of `WORKSPACE_A` only) discovers or guesses `COLLAB_B` (26-char ULID — not brute-forceable blind, but trivially obtainable if Brand A ever had ANY shared visibility into it, e.g. via a leaked/shared link, a former joint campaign, support screenshare, or simply because ULIDs are not designed as secrets and this is the only place in the codebase that treats them as one).
3. Brand A calls `POST /contracts` with `{"collaborationId": "COLLAB_B", "milestones": [...]}` while authenticated into `WORKSPACE_A`.
4. `ContractService.generate` happily creates a `Contract` row with `workspaceId = WORKSPACE_A` but `collaborationId = COLLAB_B` (Brand B's real creator relationship), plus a full set of `PaymentMilestone` rows (`collaborationId = COLLAB_B`) for whatever amounts Brand A specifies.
5. Consequences: (a) data corruption — a `Contract`/`PaymentMilestone` set now exists that is owned by Brand A but references Brand B's creator and campaign; (b) `generateAndDeliverContractPdf` (called from `recordSignature`, once Brand A signs and — per the class's own documented flow — a BRAND principal can also record the CREATOR's signature on elevated roles, see `ContractService.java:200-211`) can generate and email a PDF contract, addressed using **creator X's real name/email** (`ContractService.java:318-326`, resolved via `collaboration.getCreatorId()`), to Brand A's chosen recipient, misrepresenting a contractual relationship with a creator Brand A has no real relationship with; (c) if this contract's milestones are ever funded/paid via the normal Escrow/Payout flow (which DOES correctly check `hold.getWorkspaceId()`), the money-movement paths are not directly bypassed, but the Contract/Milestone records themselves are already cross-tenant-corrupted before that point, and the milestone `collaborationId` still resolves to creator X — meaning if Brand A funds escrow and releases it, `EscrowService.release` pays **creator X** (Brand B's creator) using Brand A's own escrowed funds. This is not a data leak — it is Brand A being able to unilaterally attach itself to another brand's creator relationship and push money to that creator, all validated as "normal" downstream because every later check only verifies workspace-of-hold, never verifies the creator/collaboration actually belongs to a campaign that workspace owns.

This is the load-bearing case Kavya's caution correctly flagged as "Phase 1 money code, 3-hop FK chain, no repository-level enforcement" — on inspection, 5 of 6 call sites correctly close the gap through an escrow-hold or campaign cross-check; `ContractService.generate` is the one that does not.

**Fix needed (routed to Vikram):**

Add a `Campaign` lookup and ownership check in `ContractService.generate`, mirroring `CampaignLinkService.createTrackingLink`'s pattern exactly:

```java
Collaboration collaboration = collaborationRepository.findById(req.collaborationId())
        .orElseThrow(() -> new ApiException("COLLABORATION_NOT_FOUND", ..., HttpStatus.NOT_FOUND));

Campaign campaign = campaignRepository.findByIdAndWorkspaceId(collaboration.getCampaignId(), workspaceId)
        .orElseThrow(() -> new ApiException("COLLABORATION_NOT_FOUND", "Collaboration not found", HttpStatus.NOT_FOUND));
```

Use `COLLABORATION_NOT_FOUND` (not a distinct "forbidden"/"campaign mismatch" code) for the negative case, matching the enumeration-oracle discipline already established in `PayoutService.validateForPayout` (E2 LOW-2: never let an unauthorized caller distinguish "not yours" from "doesn't exist"). `ContractService` already injects `campaignRepository` (used elsewhere in the class, e.g. `generateAndDeliverContractPdf:318-321`), so this is a small, contained change. Add an adversarial test to `ContractServiceTest`: Brand A calls `generate` with Brand B's `collaborationId` → expect `COLLABORATION_NOT_FOUND`, and assert no `Contract`/`PaymentMilestone` rows were persisted.

---

## 2. IdempotencyKeyRecord global key namespace — NOT GENUINE

Kavya's report described the repository method as `findByKey(key)`; on inspection the actual method is `IdempotencyKeyRecordRepository.findByIdempotencyKey(String idempotencyKey)` (`IdempotencyKeyRecordRepository.java:12`) — global (no `workspaceId` in the WHERE clause), confirming the structural premise. The question is whether this is exploitable, which depends entirely on whether keys are guessable/predictable by an outside brand. Traced every key-deriving call site:

| Service | Key shape | Derived from | Guessable by outside brand? |
|---|---|---|---|
| `PayoutService` | `"payout:" + milestoneId` (`PayoutService.java:101`) | `milestoneId` — server-generated ULID, 26 chars, assigned at `ContractService.generate` time, never exposed except to workspace members via the contract itself | No — ULID space is 128-bit, not sequential/enumerable |
| `EscrowService` | `"release:" + hold.getId()` / `"refund:" + hold.getId()` (`EscrowService.java:276,316`) | `EscrowHold.id` — server ULID | No |
| `AffiliateEarningsService` | `"affearn:" + redemption.getId()` (`AffiliateEarningsService.java:160`, prefix at line 87) | `CouponRedemption.id` — server ULID, only created internally by `RedemptionService.doRedeem` | No |
| `AffiliateSettlementJob` | `"affiliate.settlement:" + creatorId + ":" + periodYearMonth` (`AffiliateSettlementJob.java:272-274`) | `creatorId` (server-side sweep, DB-resident) + `periodYearMonth` (server clock) — entirely internal, never touches an HTTP request body | No — not reachable from any brand-facing endpoint at all |
| `ConversionTrackingService` | `"convd:" + SHA-256(utmCampaignId, orderId, orderAmount)` when key omitted (`ConversionTrackingService.java:149`, `DERIVED_KEY_PREFIX`) | This is the ONE path with a partially attacker-visible input (`utmCampaignId` is embedded in a public tracking link) — but this was already identified and fixed by Kabir in a prior round (E2 HIGH-2, documented extensively in the class javadoc, lines 103-148): the reserved-prefix rejection (400 `RESERVED_IDEMPOTENCY_KEY_PREFIX` for any caller-supplied key starting with `convd:`) plus hashing the money-bearing `orderAmount` into the derived key together close both the squatting vector and the prediction vector. Re-verified the fix is present and correct in current code. | No (already closed) |
| `ContractService` | Uses `"payout:"+milestoneId` convention referenced in comments (`ContractService.java:244,249`) — actually the idempotency for contract PDF generation, keyed off contract/milestone ULIDs | Server ULIDs | No |

**Why global (non-workspace-scoped) `findByIdempotencyKey` is safe here:** the vulnerability Kavya hypothesized — "Brand A predicts/guesses Brand B's key and pre-reserves it, causing Brand B's real operation to no-op" — requires the key to be predictable from information Brand A has access to *before* Brand B's operation runs. Every money-relevant key in this codebase is built from a server-generated ULID that is not exposed to any workspace other than the one that owns the underlying entity (milestone/hold/redemption ids are never returned in a cross-workspace-readable API response — confirmed no endpoint exists that would leak Brand B's milestone/hold/redemption id to Brand A). The one path that ever incorporated externally-visible input (`ConversionTrackingService`'s UTM-derived fallback) was already hardened against exactly this attack in a prior audit round (reserved prefix + hash-the-amount), which I independently re-verified is intact in the current code.

The architectural question ("should this repository method be workspace-scoped as a matter of defense-in-depth") remains a reasonable hygiene recommendation, but there is no exploitable cross-workspace collision or poisoning path today. **Verdict: NOT GENUINE** — Kavya's caution was warranted (the structural pattern looks like a red flag in isolation) but unfounded once the actual key-derivation schemes are traced.

**Recommendation (non-blocking, hygiene only):** if `Priya` wants defense-in-depth against a *future* key-derivation scheme that accidentally reintroduces caller-visible input, adding a `UNIQUE(workspace_id, idempotency_key)` composite constraint (keeping keys workspace-scoped) would make this class of bug structurally impossible rather than "impossible because every current caller happens to be careful." Not required before launch.

---

## 3. AffiliateEarning settlement isolation — NOT GENUINE

Traced `AffiliateSettlementJob` end to end (`influora-api/src/main/java/com/influora/job/AffiliateSettlementJob.java`, full file read).

**How the sweep works:**
1. `executeSettlementBatch` (line 145) calls `findDistinctCreatorIdByStatusIn` — a global (not workspace-scoped) query for creator ids with PENDING/FAILED earnings. This is intentional: one creator can have earnings from multiple brands/workspaces, and this loop needs to visit that creator once regardless of how many workspaces they've earned from.
2. `settleOneCreator(creatorId, ...)` (line 209) then calls `findByCreatorIdAndStatusIn(creatorId, SETTLEABLE_STATUSES)` — this pulls **all** of that one creator's settleable `AffiliateEarning` rows, which may span multiple workspaces. This is where Kavya's concern (batch processes "ALL creators' pending earnings in one run") would matter if amounts were being merged.
3. Critically, `doSettleCreator` (line 255-261) does **not** aggregate or merge anything — it iterates the list and calls `earning.markSettled(batch.getId())` on **each row individually**. Each `AffiliateEarning` retains its own `workspaceId`/`campaignId`/`creatorId`/`commissionAmount` (`AffiliateEarning.java:49-56`, no setters that mutate these fields — `markSettled` only touches `status`/`settlementBatchId`/`settledAt`, confirmed by reading the full entity). No cross-workspace sum is ever computed or written back onto any single row.
4. The only aggregate values are on `AffiliateSettlementBatch` (`totalCreators`, `totalAmount`, incremented via `recordCreatorSettled` once per creator with that creator's own already-correctly-scoped total) — this is bookkeeping metadata about the batch run itself, not a per-creator payout amount, and the job explicitly does not disburse money (class javadoc, lines 29-40: "does NOT itself call RazorpayX or move real money" — disbursement is an intentionally deferred follow-up).
5. `AffiliateEarningRepository.findByWorkspaceIdAndCreatorId` (line 38) exists precisely for a future brand-facing view to read back a creator's earnings scoped to one workspace, confirming the per-row workspace attribution is intact and independently queryable after settlement.

**Why "cross-workspace mixing" cannot happen here:** there is no code path where one creator's Brand-A-sourced earning and Brand-B-sourced earning are ever combined into a single value, a single row, or a single money-movement action. The batch is a grouping/bookkeeping construct only; the unit of settlement truth remains the individual `AffiliateEarning` row, which was correctly workspace-attributed at creation time (`AffiliateEarningsService.doRecordEarning`, `workspaceId(coupon.getWorkspaceId())` — reviewed in this session's earlier D4 pass) and is never rewritten during settlement.

**Verdict: NOT GENUINE.** Kavya's caution was warranted — "processes ALL creators' pending earnings in one run" is exactly the shape of a bug class that causes cross-tenant mixing in poorly-written batch jobs — but on trace, this job's per-row (never per-creator-aggregate) mutation discipline structurally prevents it. No fix needed. Recommend (non-blocking) adding the adversarial test Kavya's Part 3 item #2 already calls for — a `AffiliateSettlementJobTest` asserting that a creator with earnings from both Workspace A and Workspace B ends up with two independently-settled rows, each still tagged with its original `workspaceId`, and that `AffiliateEarningRepository.findByWorkspaceIdAndCreatorId` returns the correct subset for each workspace post-settlement — to lock in this guarantee against regression, since the current safety is structural but, as Kavya noted, untested.

---

## Action items routed to Vikram

1. **FIX REQUIRED (HIGH):** `ContractService.generate` (`influora-api/src/main/java/com/influora/service/ContractService.java:100-108`) — add `campaignRepository.findByIdAndWorkspaceId(collaboration.getCampaignId(), workspaceId)` check immediately after the collaboration lookup, throwing `COLLABORATION_NOT_FOUND` (404) on mismatch, before any `Contract`/`PaymentMilestone` rows are built. Add adversarial test to `ContractServiceTest`.
2. No fix required for items 2 or 3 — both confirmed structurally safe on adversarial trace. Recommend (non-blocking, can ride with Wave E's test-gap backlog) the two test additions named above (idempotency defense-in-depth is a Priya architecture call, not urgent; settlement cross-workspace test is a good regression lock).

---

**Files for orchestrator:**
- This report: `wiki/errors/wave-e1-kabir-escalation-review.md`
- Route item 1 to Vikram as a HIGH-priority fix before Wave E sign-off.
