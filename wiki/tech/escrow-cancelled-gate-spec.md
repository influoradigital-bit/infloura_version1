# `EscrowService.release` / `refund` on a CANCELLED collaboration — fix spec

> **Owner:** Priya (CTO). **Source:** CR-36 residual, identified in `wiki/errors/CR-22a-implementation-notes.md`
> and confirmed independently. **Severity:** High — money-moving, but narrower than it looks.

## The defect

`release()` (`EscrowService.java:553`) and `refund()` (`:619`) both gate on
`assertEscrowNotBlockedByDispute`, which checks **`DISPUTED` only** (`:1214`). Neither considers
`CANCELLED`. So escrow can be released to a creator on a collaboration that has been cancelled.

CR-22a narrowed `canReject()` to pre-contract states and guarded the four downstream services, which
makes the *new* paths to this much harder to reach. It did not touch these two methods, and it does
nothing about collaborations already sitting at `CANCELLED` in the database.

## The ruling — and it is NOT symmetric

**This is the part to get right. Adding `CANCELLED` to `assertEscrowNotBlockedByDispute` would be
the obvious fix and it would be wrong**, because that helper is called by both methods and the two
need opposite answers.

| Method | On `CANCELLED` | Why |
|---|---|---|
| `release()` | **BLOCK** | Release pays the creator *forward* on a deal that is dead. This is the actual defect. |
| `refund()` | **ALLOW — do not block** | Refund returns the money to the brand. It is the **remedy** for a cancelled deal, not an abuse of one. |

Blocking `refund()` on `CANCELLED` would strand every rupee held against a cancelled collaboration
with no code path to return it — which is precisely the class of bug **CR-35** was opened for
(money frozen while the books moved on). A guard that creates the bug it was written to prevent is
worse than no guard. **Do not "simplify" this into one shared check.**

## Implementation notes

- Add a **release-specific** guard. Do not widen `assertEscrowNotBlockedByDispute`; it is shared.
  Give the new one its own name and its own javadoc stating why refund is deliberately exempt.
- `refund()` keeps the dispute gate exactly as it is. No change.
- **Do not touch the admin dispute-settlement paths** (`:780`, `:818`, `:883`, `:899`). Verified:
  they call `escrowBackend.release`/`refund` directly and bypass these methods entirely, so they are
  unaffected — and they *must* stay unaffected, because settling a dispute on a cancelled deal is
  legitimate and is how CR-35's guarantee is honoured.
- `tryReleaseOnApproval` routes through `release()`, so it inherits the guard. That is
  defence-in-depth behind CR-22a's `BrandDeliverableService.approve` check, not a replacement for it.

## Testing bar

Green tests are not evidence — the standard set by CR-28/CR-29 and every money-path change since:

1. Release on a `CANCELLED` collaboration is refused. **Revert the guard, watch this fail, restore.**
   Report the exact assertion message.
2. **Refund on a `CANCELLED` collaboration still succeeds.** This is the regression test for the
   over-broad fix, and it is the more important of the two — it is what stops someone "tidying" the
   two gates into one later.
3. Release on a healthy (non-cancelled, non-disputed) collaboration still succeeds — proves the
   guard is narrow rather than blanket.

`mvn -o test` WITH tests, never `-DskipTests`. Baseline **1513 tests, 0 failures, 0 errors,
0 skipped**.

## Not in scope

The broader CR-36 question — whether every service that reads a collaboration should assert its
status — is not settled by this and should not be attempted here. This spec covers exactly the two
methods named.
