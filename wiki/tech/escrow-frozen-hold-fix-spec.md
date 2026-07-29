# Frozen-escrow settlement defect — fix spec

> **Owner:** Priya (CTO). **Severity:** CRITICAL — live money, silent.
> **Source:** `wiki/errors/CR-22a-withdrawal-money-path-audit.md` finding #2, independently
> re-verified against source before this spec was written.

## The defect

Three facts, each verified:

1. **`EscrowHold.collaborationId` is almost never set.** `EscrowService.initiateFund` builds the
   hold (`EscrowService.java:201-211`) without it. The only caller of `bindCollaboration` in the
   entire main tree is `ConfirmLaunchExecutor.java:501` — Meera's AI launch tool. So every hold
   created through the ordinary brand escrow flow has `collaborationId == null`.

2. **Sibling lookups disagree about that.** `findFundedHoldsForCollaboration` (`:1020-1045`) and
   `resolveCollaborationId` both fall back to the milestone table when the direct column is null.
   **`requireFrozenHoldsForCollaboration` (`:978-986`) does not** — it is a bare
   `findByCollaborationIdAndStatus`.

3. **So dispute settlement moves nothing, and says it did.** `freezeUnreleasedForDispute` uses
   the fallback lookup and freezes correctly. `adminReleaseForDispute` / `adminRefundForDispute` /
   `adminSplitForDispute` (`:691`, `:729`, `:786`) use the non-fallback lookup and iterate an
   **empty list**. `DisputeService.resolveDispute` then marks the dispute resolved and audit-logs
   `ESCROW_RELEASE` / `ESCROW_REFUND`.

**Net effect: money is frozen permanently while the books record it as settled.**

### The invariant the code already claims

`DisputeService.java:233-236` says, verbatim, that money is moved before the status is persisted
so *"the dispute never ends up marked resolved without the money having actually moved."*

That holds for a **thrown** exception. It does **not** hold for an **empty** settlement list —
the loop simply completes, `settlements` is `[]`, and resolution proceeds. **The guard the comment
promises does not exist for the zero-holds case.** That is the real bug; the null column is just
what triggers it.

---

## Required fixes — all four

### Fix 1 — one lookup, not two that can drift *(defence)*

Extract a single private helper resolving holds for a collaboration **by status**, carrying the
milestone fallback, and have `findFundedHoldsForCollaboration` and
`requireFrozenHoldsForCollaboration` both delegate to it. Do not simply copy the fallback into
the second method.

This repo has now paid four times for the same shape (CR-05, CR-24, CR-30, CR-34): two copies of
one rule drift, and the drift is invisible until it costs something. Same doctrine here.

### Fix 2 — bind `collaborationId` at creation *(root cause)*

Set it in `initiateFund` wherever it is resolvable — the milestone knows its collaboration. Where
it genuinely cannot be resolved, leaving it null is acceptable **because Fix 1 makes the lookup
robust**, but it must be a deliberate, commented decision rather than an oversight.

Keep `bindCollaboration`'s existing idempotent "only if null" semantics. Do not break
`ConfirmLaunchExecutor:501`.

### Fix 3 — make the invariant real *(the important one)*

`resolveDispute` must refuse to mark a dispute resolved when it was supposed to move money and
moved none.

- If the collaboration has **no** escrow at all, an empty settlement is legitimate — a dispute on
  an unfunded deal must still be resolvable. Do not break that.
- If the collaboration **has** funded/frozen escrow and the settlement moved **zero** holds, that
  is an invariant violation. Throw, roll back, and make it loud.

**Fix 3 is the one that matters most.** Fixes 1 and 2 correct today's bug; Fix 3 means the next
regression in this area fails visibly instead of silently mis-stating the books. Ship it even if
it feels redundant once 1 and 2 are in — that redundancy is the point.

### Fix 4 — backfill existing rows *(migration)*

Flyway, `influora-api/src/main/resources/db/migration`, next free `V<n>__` number. Backfill
`escrow_holds.collaboration_id` from `payment_milestones` where it is null and resolvable.
Idempotent, and it must not touch rows that already have a value.

---

## Testing bar

Ordinary green tests are not evidence here. Required:

1. A test reproducing the **exact** defect: a hold created the ordinary way (null
   `collaboration_id`, linked only via milestone), a dispute opened, then resolved — asserting the
   money actually moved. **This test must fail if Fix 1 is reverted.** Demonstrate that.
2. A test that resolution is **refused** when there is escrow but nothing settles (Fix 3),
   demonstrated by reverting Fix 3 and watching it fail.
3. A test that a dispute on a collaboration with **no** escrow still resolves cleanly — the
   regression Fix 3 could plausibly introduce.

`mvn -o test` must run WITH tests (never `-DskipTests`, which compiles them but hides failures).
Baseline is **1496 tests, 0 failures, 0 errors, 3 skipped**.

## Process gate

`adminReleaseForDispute`'s own javadoc carries: *"[SEC: money-movement path — mandatory Kabir
red-team gate before merge]"*. That gate applies to this change. Kabir reviews **after** the fix
exists; it is not parallelisable with writing it.
