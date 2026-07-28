# CR-35 — Red-team gate: frozen-escrow settlement fix

> **Reviewer:** Kabir (Red-Team / offensive security). **Date:** 2026-07-28.
> **Scope:** the uncommitted working-tree change on `cr-08-deal-lifecycle-sse` implementing
> `wiki/tech/escrow-frozen-hold-fix-spec.md` (Fixes 1–4).
> **Gate:** `EscrowService.adminReleaseForDispute` javadoc —
> *"[SEC: money-movement path — mandatory Kabir red-team gate before merge]"*. This is that gate.

## Verdict

**PASS — conditional on HIGH-1 landing in the same commit.**

Blockers: **0**. High: **2**. Medium: **3**. Low: **3**.

The change is correct, and it is a large net reduction in live money risk. It closes a certain,
silent, currently-live defect (every ordinary-flow dispute settlement moves zero rupees while the
books record a settlement) and it does not introduce any new authorisation surface. Do **not** send
it back wholesale — reverting or delaying it is strictly worse than shipping it.

One consequence of the change, however, is that a pre-existing TOCTOU on the settlement path goes
from *unreachable for ordinary holds* to *reachable for essentially every hold*. That is HIGH-1. It
is a three-line addition mirroring a pattern already present 340 lines up in the same file. It
should be in this commit, not a follow-up.

### What I could not verify

I was instructed not to run Maven (a concurrent suite run would collide on `target/`). I therefore
**cannot confirm** the implementer's claimed `1499 tests, 0 failures, 0 errors, 3 skipped`, nor the
claimed revert-proof demonstrations. Everything below is source-level analysis only. The test
*sources* are present and their assertions do look revert-sensitive as described (see LOW-1 for the
caveat).

---

## Attack results, question by question

### Q1 — Does Fix 1 actually close it, or only for the tested shape?

**Closed for every shape I could construct.** `resolveHoldsForCollaboration` unions:

* `escrow_holds.collaboration_id = ?` AND `status = ?` (direct column), and
* `payment_milestones.collaboration_id = ?` → `milestone.escrow_hold_id` → `findById` → filter on
  status.

Shapes I probed and the result:

| Shape | Reachable? | Why |
|---|---|---|
| Ordinary hold, `collaboration_id` NULL, milestone-linked | **found** (this is the defect being fixed) | milestone walk |
| Orphaned milestone (`escrow_hold_id` NULL) | n/a — no hold exists to miss | explicit `continue` |
| Milestone `escrow_hold_id` stale (points at an older hold for the same milestone) | **not a gap** | Fix 2 + the migration bind `collaboration_id` on the older hold too, so the direct-column arm finds it even though the milestone pointer moved on. Pre-fix this hold was invisible to *both* arms. |
| Two holds funded against one milestone (no refund between) | **both found** post-fix (direct column); only the last was found pre-fix | Fix 2 / migration |
| Campaign-level pool hold (`milestone_id` NULL, never bound) | **not found — by design** | documented in `initiateFund`; only `ConfirmLaunchExecutor` binds these |
| Status transition mid-read | **gap — see HIGH-1** | the union filters on status, then `requireFrozenHoldsForCollaboration` re-reads under lock and never re-checks |

Fix 2 is load-bearing beyond the lookup: `payment_milestones.collaboration_id` is `NOT NULL` with an
FK (`V10__contracts_and_milestones.sql:27,44`), and `escrow_holds.milestone_id` carries an FK to it
(`V10:51`), so `assertContractActiveForMilestone`'s returned milestone always yields a real, valid
collaboration. Reusing the already-fetched milestone rather than issuing a second lookup is right.

**Unadvertised benefit worth recording:** Fix 2 also narrows a pre-existing mis-binding window in
`ConfirmLaunchExecutor.java:494-504`, which positionally pairs *any* `collaboration_id == NULL`
funded hold to the next invited collaboration (`hold.bindCollaboration(invited.get(i).getId())`,
skipping only already-bound holds). Pre-fix, an ordinary brand-funded, milestone-linked hold
belonging to collaboration C1 was NULL and therefore eligible to be bound to an unrelated
collaboration C2 by a subsequent AI launch. Post-Fix-2 (and post-migration for historical rows)
those holds are already bound and are skipped. Nobody flagged this; it is a genuine improvement.

### Q2 — Can the union return a hold that does NOT belong to this collaboration?

**No live path found.** I traced the milestone→hold edge to its only writer:

* `PaymentMilestone.escrowHoldId` is written in exactly one place —
  `EscrowService.java:386-394`, inside `applyFunding`, as
  `milestoneRepository.findById(hold.getMilestoneId()).ifPresent(m -> m.markFunded(hold.getId()))`.
  The milestone is looked up **by the hold's own `milestoneId`**, so the edge is always reflexive:
  `milestone.escrow_hold_id = H` implies `H.milestone_id = milestone.id`.
* `H.milestone_id` is only ever set at creation from a milestone resolved via
  `milestoneRepository.findByIdAndWorkspaceId(milestoneId, workspaceId)`
  (`assertContractActiveForMilestone`), i.e. **workspace-scoped**.

Therefore the milestone walk cannot cross a workspace boundary, and cannot cross a collaboration
boundary either, because the collaboration is *defined by* the milestone it walked through.

**But the assertion is absent, not just unnecessary — MEDIUM-1.** `adminReleaseForDispute` pays
`collaboration.getCreatorId()` (the *target* collaboration's creator) the full `hold.getAmount()`
of every hold the union returns, and nothing between the union and `escrowBackend.release(...)`
asserts `hold.getWorkspaceId()` equals the collaboration's workspace, or that
`hold.getCollaborationId()`, when non-null, equals `collaborationId`. Compare
`EscrowService.refund` (`:584-586`) and `release` (`:531-533`), which both carry an explicit
`if (!hold.getWorkspaceId().equals(workspaceId)) → ESCROW_NOT_FOUND`. The dispute-settlement
siblings, which move strictly more money in one call, carry no equivalent. Note the refund
direction is self-correcting (it refunds to `hold.getWorkspaceId()`), but the **release and split
directions pay out to a payee derived from the collaboration, not from the hold** — that asymmetry
is exactly where a future mis-binding would become a cross-tenant payout rather than an error.

### Q3 — Fix 3's guard: bypassable, or wrongly blocking?

**Not bypassable in the single-dispute case; blind in one two-dispute case; and "non-empty" is
weaker than the spec's intent. See HIGH-2.**

*Can it wrongly block a partial settlement?* No, and not for the reason one might assume. All three
settlement methods append to `results` on **every** iteration of `requireFrozenHoldsForCollaboration`
— `adminSplitForDispute` does so even when `creatorAmount == 0` and no release leg posts. So a
partial outcome cannot produce a non-empty-but-incomplete list *from the loop*; the only way to
settle some and not others is a thrown exception, which rolls the whole transaction back. The
"partial settlement returns non-empty and skips the guard" attack does not land as posed.

*Is "non-empty" the right invariant?* **No.** Two concrete divergences:

1. **`hadFrozenEscrow` measures "has FROZEN escrow *right now*", which the spec asked to be
   "has *no escrow at all*".** These differ whenever the escrow existed and was already consumed.
   Concretely: `openDispute`'s one-active-dispute-per-collaboration rule
   (`DisputeService.java:122`) is a bare `existsByCollaborationIdAndStatusIn` SELECT with **no row
   lock and no unique index** (`V45__disputes.sql` indexes `collaboration_id` non-uniquely). Two
   concurrent opens — brand and creator, which is the realistic case — both pass. Now resolve D1 as
   `RESOLVED_BRAND`: the hold refunds and leaves FROZEN. Then resolve D2 as `RESOLVED_CREATOR`:
   `hasFrozenEscrow` is now **false**, settlements are empty, the guard **does not trip**, D2
   resolves cleanly and `adminAuditLogService` logs `ESCROW_RELEASE`. That is the exact
   "books say settled, nothing moved" failure Fix 3 exists to eliminate, still reachable, and
   **no race is required** for this variant — only two active disputes.
2. **The count is discarded.** `hasFrozenEscrow` computes a full list and throws away everything
   but `!isEmpty()`. A hold funded *during* an open dispute stays `FUNDED` (nothing re-freezes it),
   so settlement moves the frozen ones, returns non-empty, and the guard passes while unsettled
   money remains. Lower severity than the original bug (that money is still releasable/refundable,
   not stuck), but the spec's own words are *"moved none"* vs *"was supposed to move money"* — the
   defensible invariant is `settlements.size() == frozenHoldCount`, which is one signature change
   away and would close both divergences.

*Bypass by an attacker?* No. The guard sits after the settlement switch and before
`dispute.resolve(...)`, in the same transaction, and `body.resolution()` is validated to a terminal
`RESOLVED_*` twice. There is no input that reaches `dispute.resolve` without passing it.

### Q4 — The flagged concurrent double-`resolveDispute` race

**For the same dispute: genuinely money-safe, not cosmetic. Confirmed.**

Traced both isolation levels (MySQL default is REPEATABLE READ; `application.yml:12` confirms
MySQL):

* **READ COMMITTED:** caller B's `hasFrozenEscrow` and its settlement read both see A's committed
  result → settlements empty → `DISPUTE_SETTLEMENT_EMPTY` (409). Correct.
* **REPEATABLE READ:** B's snapshot read still shows FROZEN; `findByIdForUpdate` is a *current*
  read, so it blocks on A's row lock and then returns the post-commit (RELEASED) row. B proceeds to
  release — but with **the same idempotency key** (`"dispute-release:" + hold.getId()`), which
  `WalletLedgerService.post` dedupes via `findExistingPosting` + the `uq_wtx_idem` constraint
  (`:91-94`, `:189-196`) and `assertReplayMatches`. No second ledger movement. B then reaches
  `disputeRepository.saveAndFlush`, fails the `@Version` check, throws `DISPUTE_RESOLVE_CONFLICT`,
  and **the entire transaction rolls back** — including anything B did to the hold.

So: no double-movement, no double-resolve, and the error-code difference the implementer flagged is
indeed cosmetic. The honest flagging was correct and the analysis holds.

**The variant they did not flag is the dangerous one — HIGH-1.** The same-key dedupe that saves the
same-dispute race does **not** apply across *different* resolutions, because the keys differ:
`dispute-release:H`, `dispute-refund:H`, `dispute-split-release:H`, `dispute-split-refund:H`. Two
concurrent settlements of *different* disputes on one collaboration (enabled by the missing unique
index above) with *different* resolutions therefore both post, against a hold neither re-checks the
status of. Details in HIGH-1.

### Q5 — The migration

**Sound. Idempotent, cannot bind a wrong collaboration, safe on re-run and on partial failure.**

```sql
UPDATE escrow_holds eh
INNER JOIN payment_milestones pm ON pm.id = eh.milestone_id
SET eh.collaboration_id = pm.collaboration_id
WHERE eh.collaboration_id IS NULL
  AND eh.milestone_id IS NOT NULL;
```

* **Wrong-collaboration binding: impossible.** It joins on the hold's *own* `milestone_id`, which is
  the same edge `initiateFund` validated workspace-scoped at creation. This is a strictly safer
  direction than the app-layer milestone walk (which traverses collaboration → milestone → hold).
* **Idempotent:** `collaboration_id IS NULL` guard; a second run matches zero rows. Rows already
  bound by `ConfirmLaunchExecutor` are untouched — matching `bindCollaboration`'s own "only if null"
  semantics, so Meera's launch tool and this migration cannot fight.
* **Referential integrity:** `payment_milestones.collaboration_id` is `NOT NULL` with an FK, and
  `escrow_holds.collaboration_id` has `fk_escrow_collab` — the write cannot produce a null or a
  dangling reference.
* **Partial failure:** single DML statement on InnoDB → atomic, rolls back whole. Flyway records
  failure and blocks; re-run is safe by the idempotency above.
* **Syntax:** `UPDATE ... INNER JOIN ... SET` is MySQL-correct. Version prefix `V20260728120000__`
  matches the repo's established timestamp convention (22 other `V2026*` migrations) and sorts after
  `V68`. No collision.
* `AND eh.milestone_id IS NOT NULL` is redundant given the INNER JOIN. Harmless.

**Undocumented blast radius — MEDIUM-2.** Neither the spec nor the implementation writeup mentions
that the backfill changes the answer of three *other* queries that read `escrow_holds.collaboration_id`
directly with no fallback:

| Call site | Effect of backfill |
|---|---|
| `DeliverableCleanupJob.java:258` `canDelete` | Was returning "no unreleased escrow" for ordinary-flow holds → **the job was deleting deliverable media on collaborations with live escrow.** Backfill stops it. This is a live data-destruction bug the migration silently fixes. |
| `ContractService.java:624` `promptEscrowFundingIfNeeded` | Stops sending "please fund escrow" prompts for already-funded ordinary-flow collaborations. |
| `DealService.java:975` `escrowFunded` flag on `DealResponse` | Frontend flag becomes correct (was always `false`). |

Every one of these moves in the **safe** direction — the backfill only ever turns `NULL` into a
value, so these gates can only go `false → true`, never looser. I found no query anywhere that keys
on `collaboration_id IS NULL`. But a money-path migration whose real blast radius includes silencing
a destructive scheduled job needs that written down before it runs in production.

### Q6 — Authorisation

**Unchanged. No widening.** Specifically:

* `resolveDispute` still gates on `adminContext.requireRoleWithMfaSatisfied(principal,
  SUPER_ADMIN, ADMIN)`. Untouched.
* `initiateFund` still gates on `brandContext.requireRole(member, OWNER, ADMIN)`. Untouched.
* `hasFrozenEscrow` is a **new public method on `EscrowService`** but takes a bare `collaborationId`
  and no principal. It is called from exactly one place (`DisputeService.resolveDispute`, already
  MFA-admin-gated) and is not reachable from any controller. Correct today; it is the kind of
  unauthenticated-by-design service method that later grows a caller, so keep the javadoc's
  "called by DisputeService" note if it is ever refactored.
* **My earlier finding #5** (`CR-22a` §5: any active brand workspace member including `VIEWER` can
  cancel a contracted, funded deal via `DealService.reject`, while fund/release/refund/contract all
  require `OWNER`/`ADMIN`): **unchanged.** I re-checked `DealService` — it holds
  `EscrowHoldRepository` but its only use is the read-only `existsByCollaborationIdAndStatus` at
  `:975`; `reject` moves no escrow. Neither better nor worse. Still open, still MEDIUM, still
  independent of this change.

---

## Findings

### HIGH-1 — `requireFrozenHoldsForCollaboration` locks the row but never re-checks its status; this change makes that reachable

`EscrowService.java:1033-1040`:

```java
private List<EscrowHold> requireFrozenHoldsForCollaboration(String collaborationId) {
    List<EscrowHold> frozen = resolveHoldsForCollaboration(collaborationId, EscrowStatus.FROZEN);
    List<EscrowHold> locked = new ArrayList<>();
    for (EscrowHold snapshot : frozen) {
        locked.add(requireHoldForUpdate(snapshot.getId()));   // <-- no status re-check
    }
    return locked;
}
```

The status filter runs on the **unlocked snapshot**. `requireHoldForUpdate` then takes the row lock
and returns whatever is committed *now* — which under REPEATABLE READ is a different row version
than the one that passed the filter. Its own sibling 340 lines up gets this right:

```java
// freezeUnreleasedForDispute, :690-696
EscrowHold hold = escrowHoldRepository.findByIdForUpdate(snapshot.getId())...;
if (hold.getStatus() == EscrowStatus.FUNDED) {   // <-- re-checked after the lock
```

Also relevant: `EscrowHold.markReleased` / `markRefunded` / `markFrozen`
(`EscrowHold.java:151-176`) are **unguarded setters** — no state-machine validation. A
`REFUNDED` hold passed to `markReleased` silently becomes `RELEASED`. There is no domain-layer
backstop.

**Why this change makes it matter.** Pre-fix, `requireFrozenHoldsForCollaboration` returned `[]` for
every ordinary-flow hold, so this loop never executed for them. Post-fix it executes for essentially
every hold in the system. A latent defect becomes a live one.

**Exploit path (no attacker required — two honest admins suffice):**

1. `openDispute`'s one-active-dispute rule is a bare SELECT with no lock and no unique index
   (`DisputeService.java:122`; `V45__disputes.sql` has only `INDEX idx_dispute_collaboration`).
   Brand and creator open simultaneously → two active disputes D1, D2 on one collaboration, one
   FROZEN hold H.
2. Admin A resolves D1 as `RESOLVED_BRAND`; admin B concurrently resolves D2 as `RESOLVED_CREATOR`.
3. A refunds H (key `dispute-refund:H`), commits. B's snapshot showed FROZEN; B's
   `findByIdForUpdate` blocks, then returns the now-`REFUNDED` H; **no status re-check**; B releases
   H (key `dispute-release:H` — a *different* key, so the ledger idempotency that saves the
   same-dispute race does not apply).
4. B's dispute is a *different row*, so `@Version` does not fire. B's transaction **commits**.

**Result:** one funded hold pays out twice — once to the brand, once to the creator.

**Nothing downstream catches it.** `WalletLedgerService.post` explicitly exempts the platform
clearing wallet from the non-negative balance check (`:120-138`, `[W1-1]`), which is correct for a
contra account but means the second payout does not hit `INSUFFICIENT_BALANCE`. The clearing wallet
just goes further negative and the loss is silent.

**Fix (3 lines, same file, same pattern):**

```java
for (EscrowHold snapshot : frozen) {
    EscrowHold hold = requireHoldForUpdate(snapshot.getId());
    if (hold.getStatus() == EscrowStatus.FROZEN) {   // re-check AFTER the lock
        locked.add(hold);
    }
}
```

This also kills the concurrent half of HIGH-2: once a stale hold is dropped from the list,
`settlements` comes back empty and Fix 3's guard fires with `DISPUTE_SETTLEMENT_EMPTY` instead of
letting a phantom settlement satisfy it.

**Should be in this commit.** Also worth adding, as a separate change: a unique index on
`disputes (collaboration_id)` for active statuses, or a `SELECT ... FOR UPDATE` on the collaboration
in `openDispute`. That is the true root cause of the two-dispute condition, and it is pre-existing —
do not hold this commit for it, but do file it.

### HIGH-2 — `hadFrozenEscrow` is "frozen now", not "ever had escrow"; and "non-empty" is not "fully accounted for"

Full argument under Q3 above. Two concrete gaps:

* Second dispute on a collaboration whose escrow was already settled by the first → `hasFrozenEscrow`
  is `false`, guard never fires, dispute resolves and audit-logs `ESCROW_RELEASE` for zero movement.
  **No race required.** This is the same failure class the spec set out to eliminate.
* A hold funded during an open dispute stays `FUNDED`, is not settled, and the non-empty check
  passes anyway.

**Recommended:** change `hasFrozenEscrow(String)` to `countFrozenEscrowHolds(String)` and assert
`settlements.size() == frozenCount` (with `frozenCount == 0` remaining the legitimate
unfunded-deal case). That is what the spec's *"was supposed to move money and moved none"* actually
means, and it is strictly stronger with no extra query. For the already-settled-by-another-dispute
case, additionally consider recording on the `Dispute` row whether *this* dispute's settlement is
the one that consumed the escrow.

Not a blocker: today's guard is a strict improvement over no guard, and both gaps require the
missing dispute unique index to be reachable.

### MEDIUM-1 — No ownership assertion on holds before dispute settlement moves them

See Q2. `adminReleaseForDispute` / `adminSplitForDispute` pay
`collaboration.getCreatorId()` from holds whose `workspaceId` / `collaborationId` are never checked
against the collaboration, unlike `release()` (`:531-533`) and `refund()` (`:584-586`) which both do.
I could not construct a live path that reaches a foreign hold, so this is defense in depth — but it
is the cheapest possible guard on the highest-consequence path in the file, and the direction of
failure (paying the *wrong* creator) is worse than the bug being fixed. Add, inside
`requireFrozenHoldsForCollaboration` or at the top of each settlement loop:

```java
if (hold.getCollaborationId() != null && !hold.getCollaborationId().equals(collaborationId)) { reject }
```

plus a workspace equality check against the resolved `Collaboration`.

### MEDIUM-2 — Migration's real blast radius is undocumented

See Q5. The backfill silently changes the behaviour of `DeliverableCleanupJob.canDelete`
(a *destructive* scheduled job that was deleting media on escrow-backed collaborations),
`ContractService.promptEscrowFundingIfNeeded`, and `DealService`'s `escrowFunded` flag. All three
move in the safe direction, but this belongs in the deploy runbook and in
`wiki/processes/schema-changes.md`, not discovered at 2am. Also log the affected row count before
running it in production — the statement is unbatched and unbounded.

### MEDIUM-3 — Fix 1's "one lookup, not two that can drift" is only applied inside `EscrowService`

The spec's doctrine was explicit: *"two copies of one rule drift, and the drift is invisible until
it costs something."* Fix 1 unifies the two copies inside `EscrowService`. Three more direct,
fallback-free, collaboration-scoped escrow reads remain outside it:

* `DeliverableCleanupJob.java:258` — `existsByCollaborationIdAndStatusIn`
* `ContractService.java:624` — `existsByCollaborationIdAndStatus`
* `DealService.java:975` — `existsByCollaborationIdAndStatus`

The migration masks all three today (which is why they read as fixed), but the *rule* still exists in
four places, and any hold that is legitimately left `NULL` — campaign-level pool funding, the case
Fix 2 deliberately preserves — is still invisible to every one of them. The doctrine wants an
`escrowHoldRepository`-level or `EscrowService`-level `hasEscrowForCollaboration(id, statuses)` that
all four call. Follow-up, not a merge blocker.

### LOW-1 — The new `DisputeServiceTest` cases mock `EscrowService` entirely

`resolveRefusesWhenEscrowExistsButSettlementIsEmpty` stubs both `hasFrozenEscrow(...)` → `true` and
`adminReleaseForDispute(...)` → `List.of()`. That correctly proves the *guard*, and it is genuinely
revert-sensitive as claimed. But it proves nothing about the **coupling** between `hasFrozenEscrow`
and `requireFrozenHoldsForCollaboration` — which is the thing that must never diverge, and is
precisely the drift Fix 1 exists to prevent. Both new cases also only exercise `RESOLVED_CREATOR`;
`RESOLVED_BRAND` and `RESOLVED_SPLIT` reach the same guard untested. Worth one integration-level
case where a real `EscrowService` backs both calls.

### LOW-2 — Migration is a single unbounded `UPDATE`

No batching, no `LIMIT` loop, no row-count log. On a large `escrow_holds` table this holds row locks
on every matched row for the duration. Fine at current scale; note it.

### LOW-3 — `initiateFund` does not cross-check `campaignId` against the milestone's collaboration

A caller may pass `milestoneId` from collaboration C1 and `campaignId` of an unrelated campaign B
(both within their own workspace). The hold then carries `campaignId = B`, `collaborationId = C1`.
Not a money leak — settlement keys off `collaborationId`, and both are the caller's own — but it
produces incoherent rows and misleading ledger descriptions (`"Escrow fund for campaign " +
hold.getCampaignId()`). Pre-existing; Fix 2 makes the incoherence visible for the first time.

---

## What I would fix first

**HIGH-1.** Add the post-lock `status == FROZEN` re-check in
`requireFrozenHoldsForCollaboration`. It is three lines, it mirrors an existing correct pattern in
the same file, it is the only finding that is a *direct consequence of this change*, it removes the
double-payout path, and it also hardens Fix 3's guard for free.

Ship the rest. This is good work — the root-cause analysis is right, Fix 2 is the correct root fix
rather than a lookup patch, the migration is genuinely safe, and the implementer's own flagged race
was flagged honestly and analysed correctly.
