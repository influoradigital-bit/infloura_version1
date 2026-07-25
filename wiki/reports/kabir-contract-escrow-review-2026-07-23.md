# Kabir — Contract + Escrow Backend Security Review (BE-1, BE-2)

**Date:** 2026-07-23 · **Reviewer:** Kabir (red-team) · **Author under review:** Vikram
**Spec:** `wiki/build/contract-flow-architecture-2026-07-23.md` §6 · **Writeup:** `wiki/build/vikram-contract-be-guards-2026-07-23.md`
**Scope:** BE-1 duplicate/immutability guard, BE-2 escrow-gated-on-ACTIVE, plus the touched sign/create/fund paths.

## VERDICT: SHIP-WITH-CHANGES

No cross-tenant sign/create/fund. No escrow path pays a creator against an unsigned contract. The two new guards (BE-1, BE-2) are correctly placed and correct for the sequential case. Two real weaknesses remain — one is a genuine gap in the new code (duplicate-create is still race-open because the guard is read-then-write with no DB uniqueness), the other is a pre-existing, documented signature-integrity residual the escrow gate now inherits. Neither is a fund-theft/cross-tenant bypass, so this is not a BLOCK, but BE-1's race should be closed before this is trusted as the "at most one contract" invariant.

---

## Check 1 — Signing authz — PASS (with documented residual)

**Can a party sign on behalf of the other? / role client-supplied?**
- Role is SERVER-derived from `principal.getUserType()` in `ContractController.sign` (`ContractController.java:83-106`). Creator branch → `recordSignatureForCreator` (`:84`), body fully ignored, role forced CREATOR (`ContractService.java:500-518`). Brand branch → `recordSignature` with role defaulting to `"BRAND"` (`ContractController.java:102-104`).
- A CREATOR principal can NEVER reach the brand relay branch and can never self-attribute as BRAND. PASS.

**Cross-tenant / cross-party scoping:**
- Creator sign/read/pdf scoped by `findByIdAndCreatorId` — a subquery on `collaborations.creator_id` (`ContractRepository.java:38-42`, used at `ContractService.java:502,722,769`). A creator cannot touch a contract on a collaboration they don't own. PASS.
- Brand sign/read/pdf scoped by `findByIdAndWorkspaceId` (`ContractService.java:454,702,757` → `:794`). A brand in another workspace gets `CONTRACT_NOT_FOUND` (enumeration-oracle-safe). Vikram's new test `testRecordSignatureRejectsContractFromAnotherWorkspace` covers this. PASS.
- `contractId` is always taken from the path and re-resolved through the scoped lookup — no contractId from client state is ever trusted to point at a stranger's row. PASS.

**Residual (NOT introduced by this change, documented in spec §6.2 and `ContractService.recordSignature` javadoc):** a brand `OWNER/ADMIN/MANAGER` can relay `role=CREATOR` (`ContractController.java:102`, `ContractService.java:459-465`) and record the creator's signature without the creator's own assent. Gated to elevated membership, but it means the brand can single-handedly drive a contract to ACTIVE (both timestamps set) — see Check 3. Real legal-integrity gap; closing it needs a real creator-auth signing ceremony (product decision). Not a blocker for THIS change; the FE does not expose the relay path.

## Check 2 — Immutability — PARTIAL PASS / one real gap

**Post-signature mutation:** there is no contract update endpoint and no API path that sets `status = CANCELLED` (grep: `ContractStatus.CANCELLED` appears only in the BE-1 guard itself, `ContractService.java:180` / `ContractRepository.java:21`). Signatures are append-only timestamps with an already-signed no-op guard (`ContractService.java:527-536`). `totalAmount`/`termsJson` have no exposed setter. So once created, a contract's amount and terms are immutable and cannot be superseded. PASS.

**Cancel-then-recreate bypass:** NOT reachable — there is no cancel path, so the `!= CANCELLED` branch that would permit a fresh contract is currently dead. Safe direction (a signed deal cannot be cancelled-and-rewritten today). PASS. Note as tech-debt: this also means no legitimate re-negotiation path exists yet.

**Duplicate-create race — FAIL (the gap the task called out).**
`ContractService.generate` (`:179-185`) does `existsByCollaborationIdAndStatusNot(...)` (a SELECT) then `save` (an INSERT). There is **no DB uniqueness** backing it — migration `V10__contracts_and_milestones.sql:17` declares only `INDEX idx_contract_collab (collaboration_id)`, not a UNIQUE key. Two concurrent `POST /contracts` for the same collaboration each pass the `exists` check before either commits (standard non-blocking SELECTs under MySQL REPEATABLE_READ), then both INSERT → **two non-CANCELLED contracts for one collaboration**, exactly the state BE-1 was meant to prevent. The new `findByCollaborationIdOrderByVersionDescCreatedAtDesc` (`ContractRepository.java:32`) only makes *which one displays* deterministic — it does not stop two signable contracts from existing (each with its own milestone set, each independently fundable once signed).
- Blast radius is bounded: same-workspace, brand racing its own create, narrow window. Not cross-tenant, not fund-theft. Rated **MEDIUM**.
- **Required fix:** enforce uniqueness at the DB. MySQL has no filtered unique index, so options: (a) a unique key on `collaboration_id` plus moving cancelled rows out of the way (nullable discriminator / archive), or (b) a `SELECT ... FOR UPDATE` / advisory-lock on the collaboration row inside `generate` so concurrent creates serialize, or (c) accept the app-level guard only after documenting that duplicate contracts are possible under race and downstream code tolerates it. Vikram's tests cover the *sequential* duplicate (`testGenerateRejectsDuplicateContractForCollaboration`) but not the concurrent one.

## Check 3 — Escrow gate — PASS (money cannot reach a creator pre-signature); one scoped-out path noted

**Enforcement point & coverage:** the only code that creates an `EscrowHold` + Razorpay order is `EscrowService.initiateFund` (`:206-219`). `confirmFunded`/`release`/`refund`/`adminRelease*` all operate on holds that already exist, so gating `initiateFund` covers every entry into hold/order creation. The gate `assertContractActiveForMilestone` (`:271-292`) runs at `:172-174`, **before** the idempotency lookup, wallet read, hold save, and `razorpayClient.createOrder` — so a `CONTRACT_NOT_ACTIVE` (409) is thrown before any row or order exists. It checks `brandSignedAt != null && creatorSignedAt != null` on the raw timestamps (not the persisted enum), matching `Contract.advanceIfFullySigned` (`Contract.java:148-154`). PASS. Vikram's tests cover DRAFT, brand-only-signed, both-signed, and no-milestone cases.

**Milestone-vs-campaign distinction — is campaign-level a bypass?** Campaign-level funding (`milestoneId == null`) is intentionally ungated (`:172`). A brand CAN move its own money (campaign `budgetMax`) into escrow with no signed contract. But that hold is built with **no `collaborationId`** (`initiateFund` `:206-216` sets workspaceId/campaignId/milestoneId only; `EscrowHold.collaborationId` stays null). Every creator-payout path is milestone- or collaboration-keyed: `release` requires a `milestoneId` and a milestone whose `escrowHoldId` points back (`releaseInternal :465-497`); dispute release/split (`adminReleaseForDispute`/`adminSplitForDispute`) resolve holds via `findByCollaborationIdAndStatus` (`:1015-1017`), which cannot match a null-collaboration hold. So a campaign-level hold's only exit is **refund back to the brand**. It is therefore NOT a bypass for paying a creator against an unsigned contract. Rated **LOW / accept-with-note**: it does allow escrow holds to exist with no governing contract; confirm product intent, or gate campaign-level funding behind campaign state if that is not desired.

**Brand-relay interaction (from Check 1):** because a brand OWNER/ADMIN/MANAGER can relay the creator's signature, the "both signatures" gate can be satisfied by the brand alone → contract ACTIVE → milestone fundable. This does not let the brand extract a creator's money (the brand funds its OWN wallet into escrow; the creator is only ever *credited*), so it is not a theft vector, but it means the ACTIVE gate is only as strong as signature integrity. Inherited residual, not introduced by BE-2.

## Check 4 — Amount integrity — PASS on injection, MEDIUM on binding

- **Contract total:** server-summed from milestone amounts (`ContractService.java:193-196`); `INVALID_CONTRACT_TOTAL` if sum ≤ 0 (`:197-200`). Client cannot supply a total directly — `ContractGenerateRequest` has no total field (`MoneyDtos.java:188`). PASS.
- **At sign time:** `ContractSignRequest` carries only `role` (`MoneyDtos.java:209`) — no amount can be altered when signing. PASS.
- **Escrow fund amount:** server-derived from the persisted, immutable `milestone.getAmount()` (`deriveFundAmount :237-244`, called in the controller `EscrowController.java:82-83`); `EscrowFundRequest` has no amount field (`MoneyDtos.java:124`). Client cannot inject a fund amount. PASS.
- **MEDIUM finding — milestone amounts are 100% client-supplied and not bound to the negotiated deal value.** `MilestoneWriteRequest.amount` (`MoneyDtos.java:185`) has no `@Positive`/`@NotNull`, and `generate` validates only the *aggregate* total > 0 — a negative milestone (e.g. `[500, -200]`) is accepted as long as the net is positive, and nothing checks the total against `collaboration.agreedRate`. The brand thus sets an arbitrary contract amount at create time. Impact is limited because (a) it is the paying party choosing its own liability, and (b) the creator's signature is consent to whatever amount is in the row, which is then immutable (Check 2). Still, spec §2/§3 imply the amount should reflect the agreed rate. **Recommended fix:** add `@Positive` on `MilestoneWriteRequest.amount`, reject any non-positive milestone in `generate`, and (optionally) validate the summed total against `collaboration.agreedRate` within a tolerance.

## Check 5 — Injection / IDOR / missing-authz — PASS

- No SQL injection: every repository query is JPA-parameterized (`ContractRepository`, `PaymentMilestoneRepository`); no string-built SQL in the touched paths.
- IDOR: contract endpoints scoped by workspace or creator (Check 1). `assertContractActiveForMilestone` reaches the contract via a workspace-scoped milestone (`findByIdAndWorkspaceId :274`) then `milestone.getContractId()` — the contract lookup is `findById` but not attacker-controllable (you cannot pass an arbitrary contractId to it), so no IDOR. `initiateFund`'s idempotency replay rejects cross-workspace key reuse (`:179-186`).
- Missing-authz: `generate` requires brand membership + OWNER/ADMIN/MANAGER (`:136-137`); `initiateFund` requires OWNER/ADMIN (`EscrowService.java:157-158`). Both money/legal actions correctly gated.

---

## Required / recommended changes

1. **[MEDIUM — close before trusting the invariant]** BE-1 duplicate-create is race-open. Add DB-level serialization (unique key or `SELECT ... FOR UPDATE` on the collaboration in `generate`). Add a concurrent-create test. `ContractService.java:179-185`, `V10__contracts_and_milestones.sql:17`.
2. **[MEDIUM]** Validate milestone amounts: `@Positive` on `MilestoneWriteRequest.amount` + reject non-positive milestones in `generate`; optionally bind the total to `collaboration.agreedRate`. `MoneyDtos.java:185`, `ContractService.java:187-200`.
3. **[LOW]** Campaign-level escrow (`milestoneId == null`) is ungated — safe today (no creator-payout path, refund-to-brand only) but confirm product intent or gate on campaign state. `EscrowService.java:172`.
4. **[HIGH-integrity, pre-existing, product]** Brand-relay creator signature lets a brand drive a contract to ACTIVE without the creator's real assent, which the escrow gate then trusts. Not introduced here; needs a real creator-auth signing ceremony. `ContractController.java:102`, `ContractService.java:459-465`.

## What is solid

- BE-2 escrow gate is correctly placed before any hold/order and checks both raw timestamps — no milestone-scoped fund can move against a DRAFT/half-signed contract, and no creator can be paid against an unsigned contract via any path reviewed.
- Tenant isolation on create, sign, read, pdf, and fund is intact and consistently enforced via resolve-then-scope lookups.
- Amount/currency injection is closed at every layer (create total, sign, fund amount, webhook cross-check).
- Signed-contract terms/amount are immutable; no cancel-recreate path exists.
