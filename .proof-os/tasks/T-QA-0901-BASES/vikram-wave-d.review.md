# Priya (CTO) — Wave D review: CampaignService F-0503 + BrandDeliverableService F-0417

Reviewer: Priya, fresh context. Neither report taken at face value — every claim below was
re-derived from source and re-proved by running the tests myself, plus two independent
revert-probes.

Branch: `fix/f0390-money-flags-build-pipeline`. Both changes are uncommitted working-tree edits.

---

## Item 1 — CampaignService.java (F-0503, funded-escrow gate on human PATCH -> ACTIVE)

### VERDICT: **FIXED**

**Placement — correct.** `influora-api/src/main/java/com/influora/service/CampaignService.java:368-370`

```java
if (transitioningToActive) {
    requireFundedEscrow(campaign.getId());
}
```

Helper at `CampaignService.java:696-707`.

This is the regression a previous review caught (the gate sitting too early and short-circuiting
the validation branches). It is now positioned after every validation and before the money move.
Ordering verified line-by-line in the real file, not from the report:

| Line | What runs |
|------|-----------|
| 250 | `validator.validateBudget` |
| 252 | `validateMaxCollaborators` |
| 254-260 | `validator.validateTimeline` |
| 263-267 | `validateResumeActive` / `validateStatusForWorkspace` |
| 273-274 | `transitioningToActive` snapshot (pre-`applyPatch`, so a no-op re-PATCH of ACTIVE is excluded) |
| 314-316 | `validator.validateHypeConfig` (unconditional at the ->ACTIVE edge) |
| 326-347 | `campaign.applyPatch` — pure in-memory mutation, not a commit |
| **368-370** | **`requireFundedEscrow`** |
| 375-377 | `brandCampaignFeeService.chargeOnPublish` — real money |
| 379 | `campaignRepository.save` — the ACTIVE transition commits |

So a malformed patch still surfaces its own `VALIDATION_ERROR` rather than being masked by
`ESCROW_NOT_FUNDED`, and nothing irreversible happens before the gate. Correct.

**The check itself is not spoofable.** `requireFundedEscrow` reads
`escrowHoldRepository.findByCampaignId(...)` fresh and requires `>=1` hold at
`EscrowStatus.FUNDED`. Nothing from `CampaignPatchRequest` is consulted — there is no
client-supplied boolean that could assert "funded" instead of it being so. Error contract
(`ESCROW_NOT_FUNDED` / 409 CONFLICT) matches the `ConfirmLaunchExecutor` path it mirrors, so the
two entry points to ACTIVE no longer drift.

### Both test classes green together — real output

Command run by me, exactly as specified, after `mvn -o clean` (the first run reported
`Nothing to compile — all classes are up to date`, which is not proof of anything; I forced a
full recompile of 775 main + 274 test sources and reran):

```
cd influora-api && mvn -o -Dtest=CampaignServiceTest,CampaignActivationGatesTest test

[INFO] Compiling 775 source files with javac [debug parameters release 21] to target\classes
[INFO] Compiling 274 source files with javac [debug parameters release 21] to target\test-classes
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in com.influora.service.CampaignActivationGatesTest
[INFO] Tests run: 35, Failures: 0, Errors: 0, Skipped: 0 -- in com.influora.service.CampaignServiceTest
[INFO] Tests run: 38, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**Tests run: 38, Failures: 0, Errors: 0, Skipped: 0.** The prior regression is gone —
`CampaignServiceTest` (35) and `CampaignActivationGatesTest` (3) are green *in the same JVM run*,
not separately.

The 4 `CampaignServiceTest` call sites that now stub `escrowHoldRepository.findByCampaignId`
(lines 188, 208, 242, 270) are legitimate: each exercises other behavior at the same
`transitioningToActive` edge (fee-charged-once, fee-exception rollback) and would otherwise trip
the new gate before reaching its own subject. They stub a `FUNDED` hold, i.e. they weaken nothing
about what the gate rejects — `CampaignActivationGatesTest` owns that.

---

## Item 2 — BrandDeliverableService.java (F-0417, `Collaboration.maxRevisions` cap)

### VERDICT: **FIXED**

**Gate:** `influora-api/src/main/java/com/influora/service/BrandDeliverableService.java:346-352`

```java
Collaboration collaboration = requireCollaborationForRevisionCap(deliverable.getCollaborationId());
if (deliverable.getRevisionCount() >= collaboration.getMaxRevisions()) {
    throw new ApiException("REVISION_LIMIT_REACHED", ..., HttpStatus.CONFLICT);
}
```

Helper at `BrandDeliverableService.java:441-450`.

**It genuinely enforces the persisted per-deal value, not a constant.** Verified independently:

- `Collaboration.java:87` — `@Column(name = "max_revisions", nullable = false) private int maxRevisions;`
  A primitive `int`, so no null/NPE path.
- `Collaboration.java:234` — `getMaxRevisions()` returns that persisted field.
- `Collaboration.java:252,259` — `applyDealTerms(..., int maxRevisions)` writes it, so a per-deal
  value negotiated through `DealService.java:2117` (`terms.maxRevisions() != null ? ... : 2`) is
  what the gate reads. The cap is genuinely per-deal, not the default 2 hard-coded.
- `Collaboration.java:121,146,318,343` — every construction path seeds `DEFAULT_MAX_REVISIONS`.

**Legacy-row check (my own, not in the report).** A cap read from a column added later is a live
regression risk if existing rows default to 0 — that would reject *every* revision request in
production. `V72__meera_creator_deal_terms.sql:18` is
`ADD COLUMN max_revisions INTEGER NOT NULL DEFAULT 2`, so pre-V72 rows backfill to 2, not 0.
No regression. Clean.

**Coverage is complete, not partial.** `grep -rn "applyRevision(" src/main/java` returns exactly
two hits: the entity method (`Deliverable.java:251`) and one production caller
(`BrandDeliverableService.java:354`). `revisionCount` cannot be incremented anywhere else, so
gating this single call site closes the whole surface. Off-by-one is right too: `>=` means a
request at `revisionCount == maxRevisions - 1` is still the allowed last revision.

**Their new tests run green (run by me):**

```
cd influora-api && mvn -o -Dtest=BrandDeliverableServiceTest test
[INFO] Tests run: 24, Failures: 0, Errors: 0, Skipped: 0 -- in com.influora.service.BrandDeliverableServiceTest
[INFO] BUILD SUCCESS
```

---

## My own revert-probes

The brief asked for one. I ran both, because item 1 is the known-regressing one.

### Probe A — BrandDeliverableService (my chosen item-2 probe)

Replaced the 6-line cap check with a bare
`requireCollaborationForRevisionCap(deliverable.getCollaborationId());` — i.e. kept the repository
lookup so the test's `collaborationRepository.findById` stub stays used, and removed **only** the
enforcement. Result:

```
[ERROR] BrandDeliverableServiceTest.testReviseRejectedAtRevisionLimit:436
        Expected com.influora.common.ApiException to be thrown, but nothing was thrown.
[ERROR] Tests run: 24, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

Exactly one test fails, it is the new one, and it fails for the *right* reason — the throw is
absent, not a stubbing artifact. The test is real; it is not passing for an incidental reason.

### Probe B — CampaignService (extra, because of the prior regression)

Neutered the gate to `if (false) { requireFundedEscrow(...); }`:

```
[ERROR] CampaignActivationGatesTest.testHumanPatchToActiveRejectedWithoutFundedEscrow:101
        Expected com.influora.common.ApiException to be thrown, but nothing was thrown.
[ERROR] Tests run: 38, Failures: 2, Errors: 5, Skipped: 0
[INFO] BUILD FAILURE
```

Both negative gate tests fail on the missing throw, and the 5 `UnnecessaryStubbing` errors are the
strict-stubs proof that the `findByCampaignId` stubs in the 4 fee tests + the positive gate test
are consumed *only* by the new production code. The gate is load-bearing.

**Restoration verified.** Both files restored from backup; `BrandDeliverableService.java` md5
matches its pre-probe value (`db8e63335448b3e36d426103c308c5eb`),
`git diff --stat` is back to `+32 / +44` on the two service files, and
`grep -c "PRIYA REVERT PROBE"` returns `0` in both.

### Final combined confirmation run (post-restore)

```
mvn -o -Dtest=CampaignServiceTest,CampaignActivationGatesTest,BrandDeliverableServiceTest test
[INFO] Tests run: 24, Failures: 0, Errors: 0, Skipped: 0 -- BrandDeliverableServiceTest
[INFO] Tests run:  3, Failures: 0, Errors: 0, Skipped: 0 -- CampaignActivationGatesTest
[INFO] Tests run: 35, Failures: 0, Errors: 0, Skipped: 0 -- CampaignServiceTest
[INFO] Tests run: 62, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

---

## Summary

| # | Item | Verdict | Citation |
|---|------|---------|----------|
| 1 | CampaignService funded-escrow gate placement + both test classes green together | **FIXED** | `CampaignService.java:368-370` (gate), `:696-707` (helper); 38/38 green |
| 2 | BrandDeliverableService `maxRevisions` cap genuinely enforced | **FIXED** | `BrandDeliverableService.java:346-352` (gate), `:441-450` (helper); 24/24 green |

No blockers. Nothing is being taken on the submitter's word — placement re-derived from the file,
both suites re-run after a forced clean recompile, both fixes independently revert-probed, and the
tree restored bit-identical afterwards.

**Note for whoever commits this:** both changes are still uncommitted working-tree edits alongside
a large set of unrelated modified files. Stage the four reviewed paths deliberately —
`CampaignService.java`, `BrandDeliverableService.java`, `CampaignServiceTest.java`,
`BrandDeliverableServiceTest.java`, plus the untracked `CampaignActivationGatesTest.java`. That
last one is **untracked**: if it is not `git add`-ed, the gate ships with zero CI coverage and the
branch behaves exactly like the F-0324 untracked-file failure already in our ledger.
