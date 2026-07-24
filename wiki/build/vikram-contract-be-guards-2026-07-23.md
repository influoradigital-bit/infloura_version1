# Vikram — Contract Backend Guards (BE-1, BE-2) — 2026-07-23

**Spec:** `wiki/build/contract-flow-architecture-2026-07-23.md` §6.4, §6.5, §7 (Vikram tasks)
**Pipeline:** this doc → Kabir (security review) → Kavya (QA) → Meera (local verify)

**Update (same day):** Kabir's review (`wiki/reports/kabir-contract-escrow-review-2026-07-23.md`) —
verdict SHIP-WITH-CHANGES, no blockers — found two MEDIUM issues in the initial cut. Both closed;
see "Kabir round-2 fixes" section below.

---

## BE-1 — Immutability / duplicate-contract guard (real gap, closed)

### The gap
`ContractService.generate` had no awareness of any contract already existing for a collaboration.
A second `POST /contracts` for the same `collaborationId` created a second `Contract` +
`PaymentMilestone` set. Every `Contract` defaults to `version=1`
(`Contract.Builder#build`, `influora-api/src/main/java/com/influora/domain/entity/Contract.java:234`),
so with two rows for one collaboration, `ContractRepository#findByCollaborationIdOrderByVersionDesc`
(the lookup `DealService` uses to pick "the" contract for `DealResponse.contractId`/`contractStatus`)
had no reliable secondary sort among the tied `version=1` rows — which contract displayed as
"current" was query-plan-dependent.

### The fix
1. **New repository guard** — `ContractRepository.existsByCollaborationIdAndStatusNot(collaborationId, ContractStatus.CANCELLED)`
   `influora-api/src/main/java/com/influora/repository/ContractRepository.java:18`
2. **Guard wired into `generate`** — rejects with `CONTRACT_ALREADY_EXISTS` (409) immediately after
   the existing workspace-ownership check, before milestones are even validated. A `CANCELLED`
   contract does not block a fresh one (future re-negotiation support).
   `influora-api/src/main/java/com/influora/service/ContractService.java:168-183`
3. **Deterministic "current contract" lookup** — new
   `ContractRepository.findByCollaborationIdOrderByVersionDescCreatedAtDesc`
   (`influora-api/src/main/java/com/influora/repository/ContractRepository.java:32`), adding
   `createdAt DESC` as a secondary sort so "most recently created wins" is deterministic regardless
   of how many rows exist. Wired into `DealService.toDealResponse`
   (`influora-api/src/main/java/com/influora/service/DealService.java:732-738`), replacing the old
   `findByCollaborationIdOrderByVersionDesc` call (that method is left intact in the repository —
   unused now, not deleted, in case another caller needs the unfiltered ordering later).

### Tests (`influora-api/src/test/java/com/influora/service/ContractServiceTest.java`)
- `testGenerateSucceedsForSameWorkspaceCollaboration` (pre-existing, still green) — proves (a) first
  create still succeeds.
- `testGenerateRejectsDuplicateContractForCollaboration` (new, :212) — proves (b) a second create
  for a collaboration with an existing non-CANCELLED contract is rejected `CONTRACT_ALREADY_EXISTS`
  409, and asserts `contractRepository.save`/`milestoneRepository.saveAll` are **never** called.
- `testGenerateAllowsNewContractWhenOnlyCancelledContractExists` (new, :244) — edge case: a
  CANCELLED-only history does not block a new contract.
- `testGenerateRejectsCrossWorkspaceCollaboration` (pre-existing, still green) — tenant isolation on
  **create**: brand A supplying brand B's `collaborationId` gets `COLLABORATION_NOT_FOUND`, no rows
  persisted.
- `testRecordSignatureRejectsContractFromAnotherWorkspace` (new, :266) — proves (c) tenant isolation
  on **sign**: a brand principal authenticated into `OTHER_WORKSPACE_ID` cannot sign a contract that
  belongs to `WORKSPACE_ID` — `CONTRACT_NOT_FOUND` 404, no signature recorded (mirrors the existing
  `findByIdAndWorkspaceId`-scoped `requireContract`, unchanged).

## BE-2 — Escrow gated on contract ACTIVE (real gap, closed)

### The gap
`EscrowService.initiateFund` — the actual money-moving path that creates the escrow hold and the
Razorpay order — had **zero** awareness of `Contract`/signature state. It checked workspace
membership, role (`OWNER`/`ADMIN`), positive amount, idempotency-key replay, and wallet balance —
but nothing tied to whether the governing contract was signed at all. The only place "both signed"
was ever checked was `ContractService.promptEscrowFundingIfNeeded` (`ContractService.java:548`),
which only fires a **notification**, not a gate — so a brand could call `POST /wallet/escrow/fund`
against a milestone whose contract was still `DRAFT` (or single-signed) and the money would move.

**Verdict: newly added.** This was not a "confirm it's already enforced" — the gate did not exist.

### The fix
1. **New dependency** — `EscrowService` now takes a `ContractRepository`
   (`influora-api/src/main/java/com/influora/service/EscrowService.java:75,93,111`).
2. **Gate in `initiateFund`** — when `milestoneId` is supplied, resolves the milestone via the
   existing workspace-scoped `findByIdAndWorkspaceId` lookup (same discipline as
   `deriveFundAmount`), then its `Contract` via `contractId` (a `payment_milestones.contract_id`
   NOT NULL FK — every milestone always has a contract), and requires
   `brandSignedAt != null && creatorSignedAt != null` (i.e. `ContractStatus.ACTIVE`) — checked
   against the raw timestamps directly, matching `Contract.advanceIfFullySigned`'s own derivation
   rather than trusting the persisted enum alone. Throws `CONTRACT_NOT_ACTIVE` (409) before any
   `EscrowHold` row or Razorpay order is created.
   `influora-api/src/main/java/com/influora/service/EscrowService.java:155-164` (call site),
   `:246-266` (`assertContractActiveForMilestone` helper).
3. **Scope of the gate** — campaign-level funding (`milestoneId == null`, a pre-contract-model path
   with no `Contract` to check against) is intentionally **not** gated — there is nothing to check.
   Every milestone-scoped fund (the deal/contract path this spec is about) is gated.

### Tests (`influora-api/src/test/java/com/influora/service/EscrowServiceTest.java`)
- `initiateFundRejectsWhenContractNotSigned` (new) — `DRAFT` contract → `CONTRACT_NOT_ACTIVE` 409,
  no hold saved, wallet never even looked up.
- `initiateFundRejectsWhenOnlyBrandSigned` (new) — half-signed (`brandSignedAt` set,
  `creatorSignedAt` null) → still `CONTRACT_NOT_ACTIVE` 409, proving the gate needs **both**
  signatures, not just one.
- `initiateFundProceedsWhenContractActive` (new) — both signed → gate passes, reaches
  `escrowHoldRepository.save`.
- `initiateFundSkipsContractGateWhenNoMilestoneId` (new) — campaign-level funding (no
  `milestoneId`) never touches `milestoneRepository`/`contractRepository`, preserving pre-existing
  behavior for that path.
- `initiateFundThrowsInsufficientFundsWithExactServerFigures` /
  `initiateFundBoundaryBalanceEqualsAmountDoesNotThrowInsufficientFunds` (pre-existing, still
  green, both use `milestoneId=null`) — confirms the new gate doesn't disturb the existing
  wallet-balance path.

**Compile-only fixes** (new `ContractRepository` constructor param, no functional change) in the
other two direct `EscrowService` constructors:
`influora-api/src/test/java/com/influora/service/DisputeEscrowConcurrencyTest.java`,
`influora-api/src/test/java/com/influora/service/EscrowServiceReleaseTest.java`.

Also updated the 4 stale mock stubs in `DealServiceTest.java` (:159,215,270,391) from
`findByCollaborationIdOrderByVersionDesc` → `findByCollaborationIdOrderByVersionDescCreatedAtDesc`
to match the BE-1 lookup swap in `DealService`.

## Kabir round-2 fixes (MEDIUM x2, closed same day)

### MEDIUM-1 — BE-1 duplicate-create was race-open

**Kabir's finding:** `existsByCollaborationIdAndStatusNot` (a SELECT) then `contractRepository.save`
(an INSERT) had no DB-level guarantee. V10 declares only a plain `INDEX idx_contract_collab
(collaboration_id)`, not a UNIQUE key, so two concurrent `POST /contracts` for the same
collaboration could each pass the exists-check before either commits (ordinary non-locking SELECTs
under MySQL REPEATABLE READ), then both INSERT — two non-CANCELLED, independently signable/fundable
contracts for one collaboration.

**Race closed via: pessimistic lock (fallback option (b) from Kabir's report), not a DB migration.**
Went with `SELECT ... FOR UPDATE` on the collaboration row rather than the `active_collaboration_id`
+ UNIQUE-index migration (preferred option (a)), for two reasons specific to this codebase right
now:
1. There is currently no `CANCELLED`/cancel path at all (Kabir's Check 2 confirmed this — grep for
   `ContractStatus.CANCELLED` hits only the BE-1 guard itself). A migration that maintains
   `active_collaboration_id = NULL` "on cancel" would have no real call site to wire it into yet —
   it would be new schema shipped ahead of the feature that needs it.
2. The exact `PESSIMISTIC_WRITE` row-lock pattern already exists, is already tested, and is already
   trusted in this codebase for the identical class of problem — `EscrowHoldRepository#findByIdForUpdate`
   (H-T34-1, `EscrowService.java` release/refund/freeze paths). Reusing it here is the smaller,
   more consistent change; a UNIQUE-index migration remains the better long-term fix once a real
   cancel/re-negotiation flow exists (tracked as tech-debt, not done here).

**Implementation:**
- New `CollaborationRepository.findByIdForUpdate(id)` — `@Lock(LockModeType.PESSIMISTIC_WRITE)`
  row lock, same annotation/query shape as `EscrowHoldRepository#findByIdForUpdate`.
  `influora-api/src/main/java/com/influora/repository/CollaborationRepository.java:14-25`
- `ContractService.generate` now acquires this lock on the collaboration row immediately AFTER the
  workspace-ownership check (an unauthorized caller fails fast without taking a row lock) and
  BEFORE the `existsByCollaborationIdAndStatusNot` exists-check + `save`. The lock is held for the
  rest of the `@Transactional` method and released at commit; a second concurrent `generate()` call
  for the same collaboration blocks on `findByIdForUpdate` until the first transaction commits, so
  its own exists-check then correctly observes the just-inserted row and throws
  `CONTRACT_ALREADY_EXISTS`. `influora-api/src/main/java/com/influora/service/ContractService.java:180-198`

**Tests** (`ContractServiceTest.java`):
- `testGenerateAcquiresRowLockBeforeExistsCheckAndSave` — `InOrder` verification that
  `findByIdForUpdate` → `existsByCollaborationIdAndStatusNot` → `save` happens in that exact
  sequence (the ordering that actually makes the guard race-safe, not just that the lock method
  exists).
- `testConcurrentGenerateCallsAreSerializedByCollaborationLock` — two real threads call `generate`
  concurrently against the same collaboration. Mockito mocks cannot reproduce real MySQL row-lock
  blocking, so the mocked `findByIdForUpdate` models what the lock guarantees: an `AtomicInteger`
  gives exactly one of the two racing threads the "lock" immediately (atomic
  `incrementAndGet() == 1`, not a racy check-then-act), the other blocks on a `CountDownLatch` until
  the winner's `save()` runs (the stand-in for that transaction's COMMIT) and only then re-checks
  `existsByCollaborationIdAndStatusNot`. Asserts exactly one of the two calls succeeds, the other
  gets `CONTRACT_ALREADY_EXISTS`, and `contractRepository.save` is invoked exactly once — proving
  the race can never produce two contract rows. Mirrors the exact latch-based simulation pattern
  already used for the escrow row lock
  (`DisputeEscrowConcurrencyTest#concurrentFreezeAndRelease_freezeWins`). Verified stable across 8
  isolated runs plus 2 full-suite runs (no flakiness) after fixing an initial race in the test's own
  synchronization logic (a non-atomic `CountDownLatch.getCount() == 0` check that could let both
  threads self-identify as "first" under different scheduling — replaced with `AtomicInteger`).

**All existing `generate()`-success tests** (`testGenerateSucceedsForSameWorkspaceCollaboration`,
`testGenerateAllowsNewContractWhenOnlyCancelledContractExists`,
`testGenerateRejectsDuplicateContractForCollaboration`) updated to stub the new
`findByIdForUpdate` call.

### MEDIUM-2 — milestone amounts client-supplied and unbound

**Kabir's finding:** `MilestoneWriteRequest.amount` had no `@Positive`/`@NotNull`, and `generate`
validated only the SUMMED total > 0 — a negative milestone (e.g. `[500, -200]`, net 300) was
accepted. Nothing bound the total to `collaboration.agreedRate`.

**Fix — three layers:**
1. **Request-validation boundary:** `MilestoneWriteRequest.amount` now carries `@NotNull
   @DecimalMin("0.01")`; `ContractGenerateRequest.milestones` now carries `@Valid` so bean
   validation cascades into each milestone (previously absent — a list of records is not validated
   without it). `influora-api/src/main/java/com/influora/web/dto/money/MoneyDtos.java:185-201`
2. **Service-level defensive re-check** (same belt-and-suspenders discipline as
   `EscrowService#adminSplitForDispute`'s own boundary re-check) — `ContractService.generate` now
   rejects any individual milestone with a null or non-positive amount with
   `INVALID_MILESTONE_AMOUNT` (400), BEFORE summing, so a caller that ever bypasses bean validation
   (e.g. a future internal caller) still cannot slip one through.
   `influora-api/src/main/java/com/influora/service/ContractService.java:210-226`
3. **Bound to the negotiated deal value:** when `collaboration.agreedRate` is set, the summed
   milestone total must not EXCEED it — `CONTRACT_TOTAL_EXCEEDS_AGREED_RATE` (400) otherwise. "Does
   not exceed," not "must equal exactly," so a brand can still split the agreed rate into
   fewer/rounder installments that sum to less than the full amount. Only enforced when
   `agreedRate` is actually set — older/edge-case collaborations negotiated without one have
   nothing to bind against, and rejecting those outright would be a functional regression, not a
   security fix. `influora-api/src/main/java/com/influora/service/ContractService.java:228-241`

**Tests** (`ContractServiceTest.java`):
- `testGenerateRejectsNegativeMilestoneAmount` — `[500, -200]` (net 300, still positive) →
  `INVALID_MILESTONE_AMOUNT` 400, nothing persisted.
- `testGenerateRejectsTotalExceedingAgreedRate` — milestone total 5000 vs `agreedRate` 3000 →
  `CONTRACT_TOTAL_EXCEEDS_AGREED_RATE` 400, nothing persisted.
- `testGenerateAllowsTotalBelowAgreedRate` — milestone total 5000 vs `agreedRate` 10000 → succeeds
  (proves the bound is "does not exceed," not exact-match).

## Build / test results (offline, `.tools/apache-maven-3.9.10`, JDK 21)

```
mvn -o -q compile                                                        → clean, no output
mvn -o -q test-compile                                                   → clean, no output
mvn -o -Dtest=ContractServiceTest,EscrowServiceTest,DisputeEscrowConcurrencyTest,
       EscrowServiceReleaseTest,DealServiceTest test
   ContractServiceTest ............ Tests run: 26, Failures: 0, Errors: 0   (21 + 5 new: BE-1 race, BE-1 lock-order, negative milestone, over-agreedRate, under-agreedRate)
   DealServiceTest ................ Tests run: 22, Failures: 0, Errors: 0
   DisputeEscrowConcurrencyTest ... Tests run: 5,  Failures: 0, Errors: 0
   EscrowServiceReleaseTest ....... Tests run: 8,  Failures: 0, Errors: 0
   EscrowServiceTest .............. Tests run: 17, Failures: 0, Errors: 0
   BUILD SUCCESS (78 total)

testConcurrentGenerateCallsAreSerializedByCollaborationLock run in isolation x8 → stable, 0 flakes

mvn -o -q test  (full suite, run twice)
   Tests run: 1451, Failures: 3, Errors: 1, Skipped: 3   (both runs identical)
```

The full-suite run has the same 4 pre-existing failures as the first pass, all in files this task
did not touch and unrelated to contracts/escrow: `WalletServiceTest.testGetBalanceThrowsForMissingWallet`
(NPE vs expected `ApiException`), `WalletControllerTest.testTransactionsDelegatesToService` (NPE,
same root cause), `NotificationEventContractTest.everyEventHasAPublisherOrADocumentedReason` (stale
`KNOWN_MISSING_PUBLISHERS` allowlist entry), `MeeraVoiceAiClientTest.testSpeakSendsBearerTokenAndBody`
(URL-assertion mismatch). Confirmed via `git status --porcelain -- influora-api` that none of these
4 test files were touched by this change. Every Contract/Escrow-related test — old and new — is
green across two full-suite runs.

## Files touched

- `influora-api/src/main/java/com/influora/repository/ContractRepository.java` — `existsByCollaborationIdAndStatusNot`, `findByCollaborationIdOrderByVersionDescCreatedAtDesc`
- `influora-api/src/main/java/com/influora/repository/CollaborationRepository.java` — `findByIdForUpdate` pessimistic-write row lock (:14-25, round-2)
- `influora-api/src/main/java/com/influora/service/ContractService.java` — duplicate-contract guard + row lock (round-2) in `generate` (:169-198), per-milestone amount + agreedRate-bound checks (round-2, :210-241)
- `influora-api/src/main/java/com/influora/service/DealService.java` — deterministic contract lookup (:732-738)
- `influora-api/src/main/java/com/influora/service/EscrowService.java` — `ContractRepository` dependency, `assertContractActiveForMilestone` gate in `initiateFund` (:155-164, :246-266)
- `influora-api/src/main/java/com/influora/web/dto/money/MoneyDtos.java` — `@NotNull @DecimalMin("0.01")` on `MilestoneWriteRequest.amount`, `@Valid` on `ContractGenerateRequest.milestones` (round-2, :185-201)
- `influora-api/src/test/java/com/influora/service/ContractServiceTest.java` — 8 new tests total (3 round-1 + 5 round-2), 3 existing tests updated to stub `findByIdForUpdate`
- `influora-api/src/test/java/com/influora/service/EscrowServiceTest.java` — 5 new tests
- `influora-api/src/test/java/com/influora/service/DealServiceTest.java` — 4 stub renames
- `influora-api/src/test/java/com/influora/service/DisputeEscrowConcurrencyTest.java`, `EscrowServiceReleaseTest.java` — compile-only constructor fixes

## Not done (per task boundary)

- BE-3 (readable `termsJson` snapshot) — nice-to-have, not attempted.
- No frontend or `src/lib/api.ts` changes — Ananya's lane, untouched.
- No change to existing signing endpoint behavior beyond the new create-time guard.
- Left alone per coordinator's explicit instruction (separate decisions, not this task): the LOW
  campaign-level-escrow-ungated item (§6.3 / Kabir Check 3) and the pre-existing HIGH-integrity
  brand-relays-creator-signature item (§6.2 / Kabir Check 1) — both documented in Kabir's report,
  neither introduced by this change.
- The `active_collaboration_id` + UNIQUE-index migration (Kabir's preferred BE-1 fix) was not
  built — the pessimistic-lock fallback was chosen instead; see "Kabir round-2 fixes" above for the
  reasoning. Flagged as tech-debt for whenever a real cancel/re-negotiation flow lands.
