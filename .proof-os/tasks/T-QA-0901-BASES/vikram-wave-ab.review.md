# Priya / CTO review — Vikram wave A+B (F-0402, F-0406, F-0489, F-0399, F-0476, F-0400, F-0503, F-0417, F-0418)

Reviewer: Priya (CTO), fresh context, 2026-09-04.
Reviewed against: working tree on `fix/f0390-money-flags-build-pipeline`, uncommitted.
Nothing in this wave is journalled or ledger-closed — all nine ids are still `status: open` in
`.proof-os/ledger/failures.jsonl`.

---

## 0. Test-environment note (read this before trusting any "Tests run" line)

**The live working tree did not compile when I first ran maven, twice, for two different reasons —
neither caused by the five files under review.**

Run 1:
```
influora-api/src/main/java/com/influora/service/ContractService.java:[306,41] cannot find symbol
  method latestMilestoneDueDate(java.util.List<...MilestoneWriteRequest>)
```
Run 2 (four minutes later, different file):
```
influora-api/src/main/java/com/influora/service/portfolio/PortfolioService.java:[251,21] cannot find symbol
  method setRateCard(java.util.List<...PortfolioRateRow>)
  ...:[750,22] / [751,28] cannot find symbol  method getRateCard()
```
Between my first and second `git status` the set of modified files grew (`ContractService.java`,
`Contract.java`, `MoneyDtos.java`, `PortfolioService.java`, `PortfolioSettings.java`, plus
untracked `src/main/java/com/influora/ops/`). A **concurrent Claude Code session is editing this
repo right now**, mid-write. Both breakages are **UNAVAILABLE, not Vikram's fault** — neither
`ContractService` nor `PortfolioService`/`PortfolioSettings` is one of the five assigned files, and
`latestMilestoneDueDate` exists at `ContractService.java:578` by the time I looked, i.e. it was a
race, not a defect.

To get a reproducible result I built an isolated tree instead of testing on the moving one:

```
git archive HEAD influora-api | tar -x -C <scratch>/base
# then overlaid ONLY the seven files this wave touches:
#   main: CampaignService.java, DealService.java, EscrowService.java
#   test: DealServiceBudgetTest.java, CampaignActivationGatesTest.java,
#         DealServiceCollaboratorCapVerificationTest.java, EscrowServiceReleaseOutcomeTest.java
```

Every "Tests run" line below is from that tree. Snapshot verified byte-identical to the repo copies
of all three main files after every probe.

---

## 1. Real test results

### The four assigned test classes — all green

| Test class | Result |
|---|---|
| `DealServiceBudgetTest` (F-0399/F-0476) | `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` |
| `CampaignActivationGatesTest` (F-0503) | `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` |
| `DealServiceCollaboratorCapVerificationTest` (F-0400) | `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0` |
| `EscrowServiceReleaseOutcomeTest` (F-0406) | `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0` |
| combined run of the last three | `Tests run: 11, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS |

Also green, pulled in because they cover the same paths:

| Test class | Result |
|---|---|
| `DealServiceTest` | `Tests run: 66, Failures: 0, Errors: 0, Skipped: 0` |
| `EscrowServiceTest` | `Tests run: 38, Failures: 0, Errors: 0, Skipped: 0` |
| `EscrowServiceReleaseTest` | `Tests run: 13, Failures: 0, Errors: 0, Skipped: 0` |
| `BrandDeliverableServiceTest` | `Tests run: 22, Failures: 0, Errors: 0, Skipped: 0` |
| `CreatorAffiliateEarningSettlementTest` (F-0402) | `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` |
| `AffiliateSettlementJobTest` (F-0402) | `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0` |

### The four green classes hid a broken suite

**Full backend suite: `Tests run: 2328, Failures: 2, Errors: 3, Skipped: 12` — BUILD FAILURE.**

Exactly one surefire report in the whole run contains a failure, and it is
`com.influora.service.CampaignServiceTest`:

```
Tests run: 35, Failures: 2, Errors: 3
  testUpdatePausedToActiveChargesFee            ERROR   ApiException: Campaign has no secured
                                                        payment in FUNDED status — cannot activate
                                                        at CampaignService.requireFundedEscrow(CampaignService.java:700)
                                                        at CampaignService.update(CampaignService.java:287)
  testUpdateDraftToActiveChargesFeeOnce         ERROR   (same, same two frames)
  testPartialHypeDraftCannotBePublished         FAILURE expected: <VALIDATION_ERROR>
                                                        but was: <ESCROW_NOT_FUNDED>
  testUpdateInsufficientBalanceRollsBack        FAILURE expected: <INSUFFICIENT_WALLET_BALANCE_FOR_PUBLISH>
                                                        but was: <ESCROW_NOT_FUNDED>
  testUpdateGenericFeeChargeExceptionRollsBack  ERROR   UnnecessaryStubbingException
```

All five trace to the F-0503 change and nothing else. See §3 F-0503.

---

## 2. Revert-probes

The brief asked for one. I ran three, because the first one turned up a regression and I wanted to
know whether the pattern repeated.

**Probe A (the designated spot-check) — `CampaignService.java` reverted to `git show HEAD:`, F-0503.**
- `CampaignActivationGatesTest`: **3 run, 2 failures + 1 error** — both "rejected" tests fail with
  *"Expected ApiException to be thrown, but nothing was thrown"*; the happy-path test errors on
  `UnnecessaryStubbingException` (its funded-escrow stub goes unused because nothing reads it).
  **The test is not vacuous — it genuinely detects the defect.**
- `CampaignServiceTest`: **35 run, 0 failures, 0 errors.** This is the load-bearing half of the
  probe: those five cases are **green at HEAD and red with the fix**, so the five failures in §1 are
  a regression this wave introduced, not pre-existing fixture debt.
- Restored; snapshot re-verified identical to repo.

**Probe B — `EscrowService.java` reverted to HEAD, F-0406.**
- `EscrowServiceReleaseOutcomeTest`: **6 run, 3 failures.** The three `assertThrows` tests
  (`MILESTONE_NOT_FOUND`, `COLLABORATION_NOT_FOUND`, `ESCROW_NOT_FOUND`) go red; the three
  held/released tests stay green because they assert behaviour the old code already had. Correct
  shape. Not vacuous. Restored.

**Probe C — F-0400 gate neutered in place** (`// NEUTERED-PROBE requireWithinCollaboratorCap(...)`
at `DealService.java:1141`).
- `DealServiceCollaboratorCapVerificationTest`: **2 run, 1 failure.** `testAcceptRejectsNPlusOnethCollaborator`
  goes red; `testAcceptAllowsNthCollaboratorAtCap` stays green (it is a happy path — expected).
  Restored.

---

## 3. Per-finding verdicts

### F-0402 — affiliate settlement never posts to the wallet ledger → **FALSE FINDING (as scoped) / already FIXED at HEAD**

The assigned path `influora-api/.../service/CreatorAffiliateEarningService.java` **does not exist**
and never has (`git log` on it is empty). The real writer is
`influora-api/src/main/java/com/influora/job/AffiliateSettlementWriter.java`.

The defect is closed **and was already closed at `HEAD`** — `git show HEAD:...AffiliateSettlementWriter.java`
contains `creditCreatorWallet` at lines 76 and 101. Nothing in this wave's diff touches it.

- `AffiliateSettlementWriter.java:76` — `creditCreatorWallet(earning)` inside the `@Transactional`
  `doSettleCreator` loop, right after `earning.markSettled(...)`.
- `:107-113` — real `walletLedgerService.post(clearingWallet, creatorWallet, ...,
  WalletTransactionType.AFFILIATE_COMMISSION, ...)`, keyed on `earning.getIdempotencyKey()`.
- `CreatorAffiliateEarningSettlementTest` asserts on `Wallet.getBalance()` state, not call counts
  (`:145-157` documents that choice deliberately). 3/3 green.

**One residual worth recording, not blocking.** `AffiliateSettlementWriter.java:51` keeps a legacy
1-arg constructor that delegates with all three wallet collaborators `null`, and
`creditCreatorWallet` (`:102-104`) **silently `return`s** when they are null. That is a money-path
no-op behind a null check. It is safe *today* — only `AffiliateSettlementJobTest:67` uses it and the
`@Autowired` constructor always supplies the collaborators — but it means `AffiliateSettlementJobTest`
(9/9 green) proves settlement **without** proving payment. If anyone ever adds a non-Spring
construction site, affiliate money stops moving with zero test signal and zero log line. Recommend a
new ledger entry: make the no-op branch `log.error` at minimum.

### F-0406 — approved deliverable does not imply paid → **FIXED (narrowed by design; residual named)**

- `EscrowService.java:811-818` — `isExpectedReleaseSkip` went from whitelisting all eight codes to
  four: `MILESTONE_NOT_FUNDED`, `INVALID_ESCROW_STATE`, `RELEASE_CONDITION_NOT_MET`,
  `ESCROW_BLOCKED_BY_DISPUTE`. `MILESTONE_NOT_FOUND`, `ESCROW_NOT_FOUND`, `COLLABORATION_NOT_FOUND`
  now propagate.
- `EscrowService.java:718` — new explicit `throw new ApiException("MILESTONE_NOT_FOUND", ...)` for
  the unscoped-lookup miss that used to `return ReleaseOutcome.held(...)`.
- The throw is genuinely reachable by the caller and genuinely rolls back:
  `BrandDeliverableService.java:170-180` catches `ApiException`, logs, and **rethrows**; the history
  writes and status recompute at `:185`/`:193` are downstream of it. And the non-loud path is still
  surfaced, not swallowed — `:241-242` returns `new ReviewResponse(APPROVED, release.released(),
  release.heldReason())`. This is not a guard the happy path never reaches.
- Probe B proves the test class detects the change.

I accept the four-held / three-loud split as correct engineering judgement — you cannot throw on
`MILESTONE_NOT_FUNDED` without breaking approve-before-funding, which is a supported flow.

**Residual, and it is the important one:** the headline symptom ("a brand sees an approved
deliverable while no money moved") is **narrowed, not closed**. `MILESTONE_NOT_FUNDED` is still a
silent held (`EscrowService.java:723`), and by Vikram's *own* F-0476 javadoc
(`DealService.java:1736-1738`) the campaign-level pool path — the one Meera funds — is exactly the
path that never stamps `escrowHoldId` onto a milestone. So the most likely real-world
approved-but-unpaid case still returns HTTP 200 `APPROVED`. Whether the brand UI renders
`ReviewResponse.heldReason` is a frontend question outside this file set. **Log a follow-up ledger
entry for "held reason must be visible to the brand, not only in `log.warn`" before F-0406 is
closed.**

### F-0489 — `escrowHoldId` release branch has no caller → **NOT FIXED**

No code change anywhere. The exact line the ledger cites is unchanged:

```
src/lib/api.ts:3617   releasePayout: (milestoneId: string) =>
src/lib/api.ts:3619     http.request<...>('POST', '/wallet/escrow/release', { ... })
```

Still one caller, still hardcoded to `milestoneId`, still no `escrowHoldId` path. A Meera-funded
campaign-level hold remains unreleasable from any brand screen. No test was written. **Re-dispatch.**
Note for routing: the fix lives in `src/lib/api.ts` + a brand screen, which is Ananya's jurisdiction,
not Vikram's — the assignment row put it under `EscrowService.java` and that mis-scoped it.

### F-0399 — per-offer budget check, never summed → **FIXED**

- `DealService.java:1135` — `requireWithinRemainingBudget(collaboration)` called in `doAccept`
  before the `TERMS_AGREED` transition at `:1143`.
- `DealService.java:1767-1782` — sums `committedValue` over every OTHER collaboration in
  `BUDGET_COMMITTED_STATUSES` (`:1696-1705`, correctly excludes INVITED/APPLIED/SHORTLISTED/
  IN_NEGOTIATION/CANCELLED), adds this offer, throws `AMOUNT_EXCEEDS_BUDGET`.
- `DealServiceBudgetTest` 3/3 green, `DealServiceTest` 66/66 green.
- I checked the obvious escape hatch and it is closed: a rate can only be raised via
  `Collaboration.updateAgreedRate`, whose only two call sites are `DealService.java:280`
  (`createProposal`, revive path) and `:1299` (`doCounter`), and `Collaboration.canCounter()`
  (`Collaboration.java:358-360`) delegates to `canAccept()` — pre-commitment states only. So a
  committed deal cannot have its rate raised after the gate has run.

### F-0476 — rate-less collaboration contributes nothing to the sum → **FIXED (with a product-behaviour flag for Swapnil)**

- `DealService.java:1795-1797` — `committedValue(c, budgetMax)` returns `c.getAgreedRate()` when
  set, else `budgetMax`. The old `if (collaboration.getAgreedRate() == null) return;` early-out and
  the `.filter(rate -> rate != null)` on the sum stream are both gone (see the diff hunk at
  `:1772-1777`).
- The previously-`@Disabled` reproduction in `DealServiceBudgetTest`
  (`testNullRateCommittedRowIsNotExcludedFromBudgetSum`) is **enabled and green**, and the `@Disabled`
  annotation + its import were removed rather than the test being weakened. Good.
- The valuation is defensible: `EscrowService#deriveFundAmount` really does fund the no-milestone
  pool path at `campaign.getBudgetMax()`, so `budgetMax` is the honest worst case, not a made-up
  number.

**Flag, not a defect:** valuing a rate-less collaboration at the full `budgetMax` means **one**
invite-then-accept consumes the entire campaign budget, and the *second* collaborator on that
campaign — priced, however small — is now refused `AMOUNT_EXCEEDS_BUDGET`. For any multi-creator
campaign that starts by inviting creators before negotiating rates, that is a hard block on
onboarding creator #2. There is no test covering "one rate-less accept, then a small priced accept",
so this behaviour is unexercised. The fix is *correct* (it never under-counts); whether it is
*acceptable* is a product call. **Escalating to Swapnil for a ruling**, with the proper fix being
what the F-0476 ledger text already prescribed: move enforcement to where the amount becomes known
(proposal / counter / escrow funding), not accept.

### F-0400 — `maxCollaborators` is decorative → **FIXED (but not by this wave; test-only addition)**

The gate already exists at `HEAD`: `git show HEAD:...DealService.java` has
`requireWithinCollaboratorCap` at line 1141 (call site) and 1808 (body), throwing
`MAX_COLLABORATORS_REACHED` at 1829. This wave's `DealService.java` diff contains **only** the
F-0476 hunk — it does not touch the cap gate. `DealServiceCollaboratorCapVerificationTest` is a new
**verification-only** test written against already-fixed code, so it *would* pass against
"the old code" in the sense of HEAD.

I falsified it properly instead (Probe C): with the call at `DealService.java:1141` commented out,
`testAcceptRejectsNPlusOnethCollaborator` goes red. The test is real; it just is not evidence of
work done in *this* wave. Current code: `DealService.java:1825-1850`, counted over the same
`BUDGET_COMMITTED_STATUSES`, `null`/`<= 0` treated as "no cap" (`:1836`) with a written
justification I accept.

Residual already disclosed in the javadoc at `:1820-1823` and I agree with leaving it: PATCHing
`maxCollaborators` downward below the current committed count is not rejected. No money moves; the
gate keeps refusing further accepts.

### F-0503 — human PATCH to ACTIVE bypasses the funded-escrow precondition → **NOT FIXED (regression; re-dispatch)**

The gate itself is correct in isolation — `CampaignService.java:286-288` calls
`requireFundedEscrow(campaign.getId())` on the `transitioningToActive` edge, and `:695-703` reads
`escrowHoldRepository.findByCampaignId(...)` fresh, `anyMatch(FUNDED)`, throws `ESCROW_NOT_FUNDED`/409,
consulting nothing client-supplied. Probe A proves the new test detects its absence.

**It fails review anyway, on two counts:**

1. **It breaks five existing tests that are green at HEAD** — proved by Probe A, not inferred.
   `CampaignServiceTest` 35/35 at HEAD → 2 failures + 3 errors with the fix. The full 2328-test
   suite is red and this is the only red report in it. A wave cannot ship leaving the suite red.

2. **It is placed at the wrong point in the method, and that is a real contract change, not just
   fixture churn.** The call sits at `:287`, ahead of both the HYPE-completeness validation
   (`:328`) and the publish fee charge (`:366`). Two of the five failures are not "the test needs a
   funded-escrow stub" — they are the error contract inverting:
   - `testPartialHypeDraftCannotBePublished`: expected `VALIDATION_ERROR`, got `ESCROW_NOT_FUNDED`.
   - `testUpdateInsufficientBalanceRollsBack`: expected `INSUFFICIENT_WALLET_BALANCE_FOR_PUBLISH`,
     got `ESCROW_NOT_FUNDED`.

   A brand publishing an incomplete HYPE draft will now be told to fund escrow instead of being told
   what is wrong with their campaign. That is a worse product, and it is a silent API-contract break
   for any client branching on the error code.

**Required before re-review:** move `requireFundedEscrow` to *after* the validation block so it is
the last precondition rather than the first; then update the three `ChargesFee`/`RollsBack` cases to
stub a FUNDED hold; then re-run the whole suite, not the new class.

### F-0417 — unlimited revision requests → **NOT FIXED**

No code change and no test. Current code is exactly the ledger symptom:

- `BrandDeliverableService.java:325-364` — `requestRevision` gates on `canReview(...)` (`:329`) and
  a non-blank `feedback` (`:336`), then calls `deliverable.applyRevision(...)` (`:341`)
  unconditionally.
- `Deliverable.java:254` — `this.revisionCount = this.revisionCount + 1;` with no ceiling.
- A ceiling field **already exists and is already populated**: `Collaboration.java:21`
  `DEFAULT_MAX_REVISIONS = 2`, field at `:87`, set at `:121`/`:146`/`:318`/`:343`. Its only reader in
  all of `src/main` is `DealService.java:2149`, a DTO fill. Nothing compares `revisionCount` to
  `maxRevisions` anywhere.
- `CreatorDeliverableService.java:1135-1139` even carries a comment stating the defect verbatim.

The fix is small and the data is already there. **Re-dispatch.**

### F-0418 — `deadline` is never acted on → **NOT FIXED**

No code change and no test. Every reader of `Deliverable#getDeadline()` in `src/main`:

- `AdminCampaignService.java:340`, `:354`, `:463` — admin reporting counters.
- `PortfolioService.java:699`, `:704` — retrospective on-time math.
- `CreatorDeliverableService.java:1167` — DTO fill.

No scheduled job, no auto-fail, no penalty, no status transition, no notification. Identical to the
ledger text. Note this one is genuinely larger than it looks — per `ContractService.java:570-576`,
a new job class lives outside the file boundary this pass was scoped to, so this may need to be
re-scoped rather than simply re-dispatched. **Re-dispatch with an explicit `com.influora.job`
jurisdiction grant.**

---

## 4. Summary

| Finding | Verdict | Anchor |
|---|---|---|
| F-0402 | **FALSE FINDING (as scoped) / already fixed at HEAD** | `AffiliateSettlementWriter.java:76,107` — file named in the assignment does not exist |
| F-0406 | **FIXED** (narrowed by design; residual logged) | `EscrowService.java:718`, `:811-818`; `BrandDeliverableService.java:179,241` |
| F-0489 | **NOT FIXED** | `src/lib/api.ts:3617` unchanged — no code, no test |
| F-0399 | **FIXED** | `DealService.java:1135`, `:1767-1782` |
| F-0476 | **FIXED** (product-behaviour flag → Swapnil) | `DealService.java:1795-1797`; `@Disabled` removed from `DealServiceBudgetTest` |
| F-0400 | **FIXED**, but pre-existing at HEAD — this wave added a test only | `DealService.java:1141,1825-1850` |
| F-0503 | **NOT FIXED — regression** | `CampaignService.java:287` placed ahead of `:328`/`:366`; 5 cases red in `CampaignServiceTest` |
| F-0417 | **NOT FIXED** | `BrandDeliverableService.java:329-341`; `Collaboration.maxRevisions` read only by `DealService.java:2149` |
| F-0418 | **NOT FIXED** | only readers: `AdminCampaignService.java:340/463`, `PortfolioService.java:699/704`, `CreatorDeliverableService.java:1167` |

**Not approved for close.** 3 of 9 are genuinely fixed by this wave (F-0406, F-0399, F-0476);
2 more were already fixed before it (F-0400, F-0402); 4 are open (F-0489, F-0503, F-0417, F-0418),
and F-0503 leaves the suite red.

**Ship gate:** the full backend suite must be green. It is not — `Tests run: 2328, Failures: 2,
Errors: 3`. Fix F-0503's placement first; everything else in this wave can ride behind it.

**New ledger entries recommended:**
1. `AffiliateSettlementWriter.creditCreatorWallet` silently no-ops on null collaborators — a money
   path behind a null check with no log and no test.
2. F-0406 residual — `ReviewResponse.heldReason` must reach the brand UI, not only `log.warn`.
3. F-0476 residual — one rate-less accept consumes the whole campaign budget; needs a Swapnil
   ruling and, properly, enforcement moved off the accept edge.
