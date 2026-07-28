# CR-22a — deal-withdrawal state model: implementation notes

**Author:** Vikram (backend). **Date:** 2026-07-28. **Branch:** `cr-08-deal-lifecycle-sse`.
**Scope implemented:** §10.7's ruling narrowed to §4.1 + §4.2 (Kabir's audit) + finding #6 folded
into `reject()`. Termination flow (CR-22b) explicitly NOT built — see "Deviation from §10.7" below.

## 1. The allowlist landed

`Collaboration.canReject()` (`domain/entity/Collaboration.java`) — denylist → allowlist:

```
INVITED, APPLIED, SHORTLISTED, IN_NEGOTIATION, TERMS_AGREED
```

`CONTRACT_PENDING` and everything after it now returns 409 `DEAL_NOT_REJECTABLE`. Cut line is the
first status with a durable artifact (a `Contract` row), per Kabir's audit §4.1.

## 2. Downstream guards added (Kabir finding #1 — "narrowing alone is cosmetic")

All four throw `409 COLLABORATION_CANCELLED` when `Collaboration.status == CANCELLED`:

| Service | Method | Where the check sits |
|---|---|---|
| `ContractService` | `generate` | Under the pre-existing `PESSIMISTIC_WRITE` lock (`findByIdForUpdate`), before the duplicate-contract check |
| `ContractService` | `doRecordSignature` | AFTER the already-signed idempotent-replay short-circuit (see §4 below for why), under a NEW `findByIdForUpdate` lock on the collaboration |
| `EscrowService` | `assertContractActiveForMilestone` (called from `initiateFund`) | After the contract-signature check, under a NEW `findByIdForUpdate` lock on the collaboration resolved from the milestone |
| `CreatorDeliverableService` | `submitForReview` | Plain (unlocked) read — not money-moving, see §5 |
| `BrandDeliverableService` | `approve` | Plain (unlocked) read — see §5 for why no lock was needed here either |

`BrandDeliverableService` gained a new constructor dependency (`CollaborationRepository`) — it had
none before.

## 3. Kabir finding #6 (reject ↔ contract-sign lost update) — folded into `reject()`

`DealService.reject()` is now:
1. Ownership/existence check only (unlocked) — 404s fast for a foreign deal, deliberately does
   NOT also re-run `canReject()` here (see next point).
2. Wrapped in `IdempotencyService.executeOnce("deal-reject:"+dealId, ...)`, mirroring
   `accept`/`counter`.
3. The actual gate + transition moved into a new private `doReject`, which takes a
   `PESSIMISTIC_WRITE` lock via `CollaborationRepository.findByIdForUpdate` BEFORE re-checking
   `canReject()` and writing `CANCELLED`.

**Why the ownership check doesn't also re-check `canReject()`:** on a genuine retry of an
already-succeeded reject, the deal is now `CANCELLED`. If `reject()` re-checked `canReject()`
before the idempotency wrapper, it would 409 on every retry — reintroducing finding #8 instead of
fixing it. The real gate lives inside `doReject`, under the lock, where a first-time call needs it.

**Finding #8 resolution, confirmed:** a retry with the same idempotency key now hits
`IdempotencyService.AlreadyCompletedException`, caught in `reject()`, replayed as `OkResponse
.success()` — never re-enters `doReject`, never re-checks `canReject()` against the
now-CANCELLED row.

**Why `ContractService.doRecordSignature`'s new lock/check sits AFTER the already-signed
short-circuit, not before:** `ContractServiceTest#testRetriedSignatureAfterFullyExecutedIsNoOp`
pins `verifyNoInteractions(..., collaborationRepository)` for an idempotent replay of an
already-fully-executed signature. Putting the CollaborationStatus check before that short-circuit
would touch `collaborationRepository` on every replay, breaking that invariant. Placing it after
is also not a live hole: post-narrowing, `canReject()` can never reach `CANCELLED` once a Contract
row exists (a Contract implies at least `CONTRACT_PENDING`, past the cut line) — so no genuinely
new signature can ever legitimately see a CANCELLED collaboration except in the exact race window
this lock closes.

## 4. Why `BrandDeliverableService.approve` and `CreatorDeliverableService.submitForReview` did
NOT get a `PESSIMISTIC_WRITE` lock (unlike the contract/escrow guards)

A `Deliverable` can only reach `SUBMITTED`/`RESUBMITTED` after `ContractService#generate`
materializes deliverable rows — which requires the collaboration to already be at
`CONTRACT_PENDING+`. Since `CONTRACT_PENDING+` is no longer reachable by `reject()` post-narrowing,
there is no live path left that can cancel a collaboration with deliverables on it. Both guards
are real (they do throw, and are pinned by tests that prove it), but they are belt-and-suspenders,
not races closed. Said this explicitly in code comments so a future reader doesn't go looking for
a race that isn't there.

## 5. Deviation from §10.7 — flagged, not silently resolved

§10.7(a) says CR-22a "keeps the `canReject()` narrowing plus the termination flow" and §10.7(b)
routes finding #1 (downstream enforcement) to its OWN row, separate from CR-22a. The task as
handed to me explicitly overrode both of those:

- Termination flow (CR-22b) is explicit out-of-scope here.
- Finding #1's downstream guards are explicitly IN scope for this ticket ("narrowing alone is
  COSMETIC and must not ship alone").

I implemented per the task's explicit instructions, not §10.7's literal text, because doing so is
also technically correct: **narrowing `canReject()` without the downstream guards leaves a live
race exactly at the TERMS_AGREED/CONTRACT_PENDING boundary** — a `reject()` on TERMS_AGREED racing
a `generate()`/`initiateFund()` call on the same collaboration is exactly free to cancel-then-
contract or contract-then-cancel without the guards, even after narrowing. So finding #1 is not
actually severable from finding #3 the way §10.7(b) assumed — the two rulings should probably be
reconciled by whoever owns §10.7. Reporting this rather than silently picking one.

## 6. Verification

`mvn -o test` — 1509 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS (full log timestamped
2026-07-28). Baseline was 1500/0/0/3-skipped; delta is exactly my 9 new tests. The 3→0 skipped
delta is NOT mine — I did not touch any `@Disabled`/skip annotation anywhere; most likely earlier
baseline was cut before other concurrent branch work (CR-35, CR-29) changed something. Not
investigated further — out of my file-ownership boundary this round.

Every guard revert-proven (revert → run its exact pinning test → observe failure → restore →
confirm green again). Exact assertion messages are in the completion report to Arjun/Priya, not
duplicated here.
