# Red-Team Re-Check: CRITICAL-1 Fix (Meera AI `confirm_launch` fee bypass)
**Date:** 2026-07-14
**Reviewer:** Kabir (Red-Team / Offensive Security Lead)
**Scope:** Targeted re-check of Vikram's fix to the CRITICAL-1 finding in
`subscription-phase3a-kabir-redteam.md`, plus re-confirmation that Phase 3a's own fee-override
logic verdict still holds. Not a full re-audit.
**Status:** ✅ **PASS**

---

## 1. Fee call genuinely wired in, inside the real transaction boundary

Traced `ConfirmLaunchExecutor.doExecute` (`influora-api/src/main/java/com/influora/service/meera/tool/ConfirmLaunchExecutor.java`):
- `@Transactional` sits on `doExecute` itself (line 187) — same method, not a helper called after commit.
- Real transition gated correctly: the `campaign.getStatus() == ACTIVE` branch (already-active no-op /
  `CAMPAIGN_ACTIVATED_WITHOUT_LAUNCH` reject) returns *before* line 301, so the code below it only
  runs on a genuine DRAFT/PAUSED/PENDING_APPROVAL → ACTIVE transition.
- Line 301 `campaign.setStatus(ACTIVE)` → line 312 `brandCampaignFeeService.chargeOnPublish(campaign, workspaceId)`
  → line 313 `campaignRepository.save(campaign)` → then invites, escrow-hold binding, AI-credit reset,
  all still inside the same `@Transactional` method. `chargeOnPublish` itself is `@Transactional`
  (default `REQUIRED` propagation, confirmed no `REQUIRES_NEW` anywhere in the chain per original
  Phase 3a pass) — it joins the caller's transaction rather than opening its own.
- Confirmed via test `testFeeChargeFailureRollsBackWholeLaunch`: when `chargeOnPublish` throws,
  `campaignRepository.save`, `collaborationRepository.save`, `escrowHoldRepository.save`,
  `aiCreditService.applyEscrowFundedReset`, and `toolCallRepository.save` are all `never()` called —
  real rollback, not merely a claimed one.
- Confirmed via `testRealTransitionChargesFeeBeforeSaveAndDownstreamSideEffects` (`InOrder`):
  charge → campaign save → collaboration save → credit reset, in that literal order — charge-then-save,
  as claimed, matching `CampaignService.update()`'s pattern exactly.

**Verdict: the transactional guarantee is real, independently traced through the annotation scope and proven by the rollback test, not just claimed.**

## 2. No new fee-bypass or double-charge

- Exactly two call sites for `chargeOnPublish`/`resolveBrandFeeBps` in the whole codebase
  (`grep -rn "chargeOnPublish\|resolveBrandFeeBps" influora-api/src/main/java`): `CampaignService.java:273`
  and `ConfirmLaunchExecutor.java:312`. No other path reaches either method.
- The idempotency key (`"brand-fee-publish:" + campaign.getId()`) is generated **inside**
  `chargeOnPublish` itself (`BrandCampaignFeeService.java:174`), not duplicated per-caller — both call
  sites necessarily produce the identical deterministic key for a given campaign, since they both call
  the same method. There is no risk of the two paths drifting to different key formats; "replicated" in
  Vikram's summary is loose phrasing for "uses the same shared method," which is actually a stronger
  guarantee than independent replication would be.
- Even under a race (no explicit row lock protecting `ConfirmLaunchExecutor`'s campaign read the way
  `CampaignService.loadOwnedForUpdate` does), a genuine double-call for the same campaign lands on
  `WalletLedgerService.post`'s insert-first-wins unique constraint on that idempotency key — second
  call replays the first posting rather than debiting twice. This is existing, previously-verified
  `BrandCampaignFeeService` behavior, unaffected by this fix.
- `ConfirmLaunchExecutor`'s own guard (`campaign.getStatus() == ACTIVE` → no-op or reject, never
  reaching line 312) additionally prevents the AI path from even attempting a charge on a non-transition,
  independent of the ledger-level protection. Confirmed by test
  `testAlreadyActiveCampaignWithPriorConfirmLaunchIsCleanNoOp`.

**Verdict: campaigns launched via the AI path are now charged exactly once — no bypass, no double-charge risk under any path traced, including races.**

## 3. Stale doc corrections — spot-checked, accurate now

- `ConfirmLaunchExecutor.java` class javadoc (lines 54-61): now correctly states the fee is charged
  inside the same `@Transactional` method, names the prior "P3-20" removal as the bug, and points at
  the test. Accurate.
- `ConfirmLaunchExecutorTest.java` class javadoc (lines 66-75): now correctly narrates the CRITICAL-1
  bug and fix, references the two new tests by name — both exist and do what's described. Accurate.
- `ConfirmLaunchExecutorTest.java` (lines 380-386, replacing the old `// NOTE: ... removed from
  constructor` × 4 comments): now correctly explains what the old "P3-20" comment falsely claimed and
  what actually happened. Accurate — checked this isn't just reworded-but-still-wrong; it names the
  real defect (AI path silently charged 0%) and points at the real fix location.
- `@DisplayName`s spot-checked against test bodies (lines 122-159, 161-187): the "charges the fee once"
  language is now backed by real `verify(brandCampaignFeeService, times(1)).chargeOnPublish(...)` calls,
  not aspirational text over an empty assertion list (the original CRITICAL-1 defect).

**Verdict: all 3 flagged stale-doc claims are now genuinely true, not just reworded.**

## 4. Phase 3a fee-override logic — unaffected, no regression

- `resolveBrandFeeBps`/`chargeOnPublish` signatures unchanged; `ConfirmLaunchExecutor` is purely a new
  caller, not a modification to `BrandCampaignFeeService`.
- `workspaceId` passed to `chargeOnPublish(campaign, workspaceId)` at line 312 is `doExecute`'s own
  first parameter — the same `workspaceId` used earlier in the method for
  `campaignRepository.findByIdAndWorkspaceId(campaignId, workspaceId)`, so the campaign is proven to
  belong to that workspace before the charge is attempted. This `workspaceId` originates from
  `MeeraInternalController`'s on-behalf-JWT resolution (`OnBehalfAuthResolver`), not from AI tool-call
  input — traced in the original Phase 3a pass and unchanged here. No stale/wrong workspaceId risk.
- `plan.getCode() == PRO` check, PAST_DUE/CANCELLED/HALTED → 10% fallback, and the fail-open direction
  in `tryResolvePlanFeeBps`/`resolveBrandFeeBps` are byte-for-byte unchanged from the version verified
  in the original Phase 3a pass (`BrandCampaignFeeService.java` diff-checked — only new caller added
  elsewhere, this file's fee-calculation logic untouched).

**Verdict: original Phase 3a verdict on the fee-override logic itself stands unmodified — this fix introduces a new caller only, no regression in the logic it calls.**

## 5. Sibling-executor claim — `RequestPaymentExecutor` spot-checked

Read `influora-api/src/main/java/com/influora/service/meera/tool/RequestPaymentExecutor.java` in full.
Confirmed:
- No `WalletLedgerService`, `EscrowService`, `BrandCampaignFeeService`, or any wallet/ledger dependency
  injected into the class at all.
- `doExecute` only reads `amount`/`display_amount_hint` from AI input for **drift detection** against a
  server-derived figure (`AmountDerivationService.deriveForCampaignIntent`) — never as a chargeable
  value — and writes a `MeeraToolCall` row with status `PENDING_CONFIRM`. No campaign status mutation,
  no wallet debit, no escrow-hold write anywhere in the class.
- The actual money movement is explicitly a separate leg per the class javadoc: the browser calls
  `POST /brand/escrow/fund` on the human JWT, returned via `confirmActionUrl` — this class has no code
  path into that endpoint.

This is a genuinely different (and safe) design from the CRITICAL-1 bug: `ConfirmLaunchExecutor`'s bug
was a class that *does* flip campaign status and *should* charge a fee but didn't; `RequestPaymentExecutor`
never claims to move money and structurally cannot — no injected dependency could even be wired to do so.
Not the same "assumed covered elsewhere" pattern.

**Verdict: Vikram's claim holds — no analogous bypass in `RequestPaymentExecutor`.**

## 6. Build / test verification (self-run, not re-trusting Vikram's numbers)

- `mvn -o compile` (via bundled `.tools/apache-maven-3.9.10`): BUILD SUCCESS.
- `mvn -o test -Dtest=ConfirmLaunchExecutorTest`: **7 run / 0 failures / 0 errors** — matches Vikram's claim exactly.
- `mvn -o test` (full suite): **979 run / 0 failures / 1 error** — the 1 error is
  `DatabaseConstraintIntegrationTest` ("Could not find a valid Docker environment"), the same
  pre-existing Testcontainers/Docker-gated test called out in every prior pass on this sandbox (no
  Docker daemon here). Matches Vikram's claimed 977-baseline + 2 new = 979. Zero new failures.

---

## WHAT TO DO NEXT

1. **PASS.** CRITICAL-1 is genuinely closed: fee charged exactly once on the AI launch path, inside the
   real transaction, with real rollback on failure, no double-charge risk, docs now accurate, Phase 3a
   logic unaffected, sibling executor spot-checked clean.
2. Route to **Meera** for local build/test re-verify (standard gate for a money-path change).
3. Then flag ready for **Priya's** sign-off — this is a money-path architecture change now spanning 2
   code paths (`CampaignService.update()` + `ConfirmLaunchExecutor.doExecute`), both converging on the
   same shared `BrandCampaignFeeService.chargeOnPublish`.
4. No further Kabir re-audit needed on this specific item unless the fix changes again.

**Reviewed by:** Kabir, Red-Team / Offensive Security Lead
**Date:** 2026-07-14
**Files reviewed this pass:** `ConfirmLaunchExecutor.java`, `ConfirmLaunchExecutorTest.java`,
`BrandCampaignFeeService.java`, `CampaignService.java` (call site only), `RequestPaymentExecutor.java`,
plus `mvn -o compile` / `mvn -o test` run directly.
