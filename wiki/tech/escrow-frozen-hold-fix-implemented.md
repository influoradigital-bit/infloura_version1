# Frozen-escrow settlement defect — implementation record

> **Status:** Code + migration implemented on `cr-08-deal-lifecycle-sse` by Vikram (Backend).
> **NOT YET MERGED / NOT YET KABIR-REVIEWED.** Spec (`wiki/tech/escrow-frozen-hold-fix-spec.md`)
> carries a mandatory Kabir red-team gate on `adminReleaseForDispute`'s own javadoc — that gate is
> still open. Do not deploy until it clears.

Implements all four required fixes from `wiki/tech/escrow-frozen-hold-fix-spec.md`, addressing
`wiki/errors/CR-22a-withdrawal-money-path-audit.md` finding #2 (§3.2): dispute settlement could
not reach holds funded through the ordinary brand escrow flow, so `resolveDispute` marked disputes
resolved and audit-logged an escrow settlement that never happened.

## Fix 1 — one lookup, not two that can drift

`influora-api/src/main/java/com/influora/service/EscrowService.java`: extracted
`resolveHoldsForCollaboration(collaborationId, status)` — queries the direct `collaboration_id`
column and falls back to the milestone → collaboration link. `findFundedHoldsForCollaboration`
and `requireFrozenHoldsForCollaboration` both now delegate to it instead of each carrying its own
copy (the second previously had no fallback at all).

## Fix 2 — bind `collaborationId` at creation

Same file, `initiateFund`: `assertContractActiveForMilestone` now returns the resolved
`PaymentMilestone` (previously `void`) instead of a second lookup being added. When a
`milestoneId` is supplied, the new hold is built with `collaborationId` set from that milestone's
(NOT NULL) `collaboration_id` — this is the actual root-cause fix; every ordinary
`POST /wallet/escrow/fund` call now binds the column instead of leaving it null forever.
Campaign-level funding (`milestoneId == null`) has no collaboration yet at that point and is left
null, deliberately, with a comment explaining why — safe only because of Fix 1.

## Fix 3 — make the invariant real

`influora-api/src/main/java/com/influora/service/DisputeService.java`, `resolveDispute`: added
`EscrowService.hasFrozenEscrow(collaborationId)` (new method, same fallback-aware lookup as
`requireFrozenHoldsForCollaboration`), called **before** the settlement runs. If the collaboration
had frozen escrow and the settlement moved zero holds, throws `DISPUTE_SETTLEMENT_EMPTY` (409)
before `dispute.resolve(...)` is ever called — the dispute is never persisted as resolved. A
collaboration with no escrow at all still resolves cleanly (checked flag is `false`, guard never
trips).

**Known interaction, flagged rather than special-cased:** in the pre-existing concurrent
double-resolve race described in `resolveDispute`'s own Finding #1 javadoc, it's possible for the
*second* concurrent caller to now surface `DISPUTE_SETTLEMENT_EMPTY` instead of
`DISPUTE_RESOLVE_CONFLICT` if its (non-locking) `hasFrozenEscrow` read and its settlement call
straddle the first caller's commit under REPEATABLE READ semantics. Both are safe 409s — no money
moves and the dispute is not double-resolved either way — but the error code the second admin sees
may differ from before. Not reproduced in the test suite; noted here rather than papered over.

## Fix 4 — backfill migration

`influora-api/src/main/resources/db/migration/V20260728120000__backfill_escrow_hold_collaboration_id.sql`
— idempotent `UPDATE ... JOIN` backfilling `escrow_holds.collaboration_id` from
`payment_milestones.collaboration_id` wherever it's null and resolvable. Logged in
`wiki/processes/schema-changes.md`.

## Revert-proof verification (per spec's testing bar)

1. **Reproduction test** — `EscrowServiceTest.adminReleaseForDisputeSettlesOrdinaryFlowHoldWithNullCollaborationId`:
   funds a hold the ordinary way (no `.collaborationId(...)` on the builder), freezes it via a
   dispute open, resolves via `adminReleaseForDispute`. Reverting Fix 1 (bare
   `findByCollaborationIdAndStatus`, no fallback) makes it fail:
   `org.opentest4j.AssertionFailedError: expected: <1> but was: <0>` — confirmed by temporarily
   reverting, running, and restoring.
2. **Invariant test** — `DisputeServiceTest.resolveRefusesWhenEscrowExistsButSettlementIsEmpty`:
   stubs `hasFrozenEscrow = true` and an empty settlement list, asserts `DISPUTE_SETTLEMENT_EMPTY`.
   Reverting Fix 3's guard makes it fail: `org.opentest4j.AssertionFailedError: Expected
   com.influora.common.ApiException to be thrown, but nothing was thrown.` — confirmed the same
   way.
3. **Regression guard** — `DisputeServiceTest.resolveStillSucceedsWhenNoEscrowExistedAtAll`: stubs
   `hasFrozenEscrow = false` with an empty settlement list, asserts the dispute still resolves
   (the case Fix 3 must not break).

Full suite: `mvn -o test` → **1499 tests, 0 failures, 0 errors, 3 skipped** (baseline 1496 + 3 new
tests, all passing).
