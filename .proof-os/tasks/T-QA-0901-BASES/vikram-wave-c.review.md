# Priya — Wave C fresh-context review (F-0403 / F-0413 / F-0414 / F-0498 / ADMIN-BOOTSTRAP-0829)

Reviewer: Priya (CTO), fresh context, no wave-C report read as authority.
Date: 2026-09-04
Branch: `fix/f0390-money-flags-build-pipeline`
Method: read current code directly, ran every test class myself, two independent revert probes.

---

## Verdict table

| Finding | Verdict | Primary citation |
|---|---|---|
| F-0403 — no contract-cancel path | **FIXED** | `ContractService.java:919` / `:929` / `:935`, `Contract.java:243` |
| F-0413 — `expiration_date` never populated | **FIXED** (data only; no enforcement job, correctly declared) | `ContractService.java:306`, `:520`, `:578` |
| F-0414 — no contract-amend path | **FIXED**, but ships two new residue defects (see §3.4) | `ContractService.java:441`, `ContractController.java:145` |
| F-0498 — portfolio rate card collapsed to row[0] / uncapped | **FIXED** | `PortfolioService.java:110`, `:235`, `:766`, `:874`, `:949` |
| ADMIN-BOOTSTRAP #1 — first SUPER_ADMIN provisioning | **FIXED**, blocked on `git add` (§5.3) | `ProvisionSuperAdminRunner.java:65`, `:125`, `:142-147` |
| ADMIN-BOOTSTRAP #2 — SUPER_ADMIN MFA reset | **FIXED**, authorization independently confirmed | `AdminAuthService.java:333-343`, `AdminContextService.java:104-131` |

Nothing is NOT FIXED. Nothing is a FALSE FINDING. Nothing is BLOCKED.

---

## 1. Test runs — real output

All four run from a **`mvn -o clean test`** (full recompile: 775 main + 274 test sources). See §4 for why `clean` is not optional here.

```
[INFO] Tests run: 15, Failures: 0, Errors: 0, Skipped: 0 -- com.influora.service.ContractCancelAmendTest
[INFO] Tests run:  5, Failures: 0, Errors: 0, Skipped: 0 -- com.influora.service.portfolio.PortfolioServiceRateCardTest
[INFO] Tests run:  3, Failures: 0, Errors: 0, Skipped: 0 -- com.influora.service.admin.AdminAuthServiceTest
[INFO] Tests run:  2, Failures: 0, Errors: 0, Skipped: 0 -- com.influora.ops.ProvisionSuperAdminRunnerTest
[INFO] Tests run: 25, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**`src/main` compiles clean** — 775 files, no errors. No file is broken by this wave, and no unrelated concurrent breakage is present at review time.

---

## 2. ADMIN-BOOTSTRAP MFA reset — independent privilege-escalation scrutiny

This is the item I trusted least going in. Both required rejections are real, confirmed from code, not from the report.

### 2.1 Is a non-SUPER_ADMIN actually rejected? — YES

`AdminAuthService.java:334`
```java
AdminUser caller = adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN);
```

The real gate is `AdminContextService.java:104-108`, which is three checks, not one:

1. `loadActive(requireAdminId(principal))` — `requireAdminId` (`:69`) 403s any non-`UserType.ADMIN` token; `loadActive` (`:133`) filters on `isActive` and 401s `ADMIN_NOT_FOUND`, so a suspended admin cannot call it.
2. `requireMfaSatisfied(admin)` (`:122`) — 403 `MFA_SETUP_REQUIRED` unless the **caller's own** MFA is enabled. A stolen password alone cannot reach this route.
3. `requireRole(admin, SUPER_ADMIN)` (`:110`) — 403 `INSUFFICIENT_ROLE` otherwise.

The decisive detail: **the role is loaded fresh from `admin_users`, never read from the JWT** (`AdminContextService` class javadoc, `:40-43`). An `ADMIN` or `SUPPORT` token is rejected on the current DB row, and a demotion takes effect on the very next request. This is not "any authenticated user."

Defense in depth at the filter layer confirmed too: `SecurityConfig.java:194-197` `permitAll`s only `POST /admin/auth/login` and `POST /admin/auth/refresh`. Everything else under `/admin/**` sits behind the `hasRole(ADMIN)` matcher, so `/admin/auth/mfa/reset/{id}` is not anonymously reachable.

### 2.2 Is a self-reset actually rejected? — YES

`AdminAuthService.java:335-340`
```java
if (caller.getId().equals(targetAdminId)) {
    throw new ApiException("CANNOT_RESET_OWN_MFA", ..., HttpStatus.FORBIDDEN);
}
```

The id on the left comes from the `AdminUser` **the context service just loaded from the database**, not from the request. `targetAdminId` is a `@PathVariable` (`AdminAuthController.java:135-137`). There is no request-body or JWT-claim input to either side, so the comparison cannot be spoofed. The guard runs **before** `loadActive(targetAdminId)` (`:341`), so a self-reset never touches a row.

I did not take this on faith — see the mutation probe in §4.1, which proves the guard is load-bearing.

### 2.3 Residual policy question (not a code defect)

There is deliberately **no restriction on the target's role** — a SUPER_ADMIN may reset another SUPER_ADMIN's MFA. That is the recovery case working as designed, but note the consequence: with exactly two SUPER_ADMIN accounts under one operator, A resets B and B resets A, which is a self-reset in everything but the id comparison. **Ops policy question for Swapnil, not a fix**: either keep ≥3 SUPER_ADMINs with separate custodians, or accept that mutual reset exists. I am not asking for a code change.

### 2.4 Test weakness worth naming

`AdminAuthServiceTest.java:100-116` proves the non-SUPER_ADMIN rejection by **mocking `adminContext` to throw**. That proves the service does not swallow the exception; it does not prove the real gate rejects. I verified the real gate by reading `AdminContextService` directly (§2.1) and it holds — but the test is a mock asserting a mock, the same class of weakness as our Mockito/schema-validation note. There is also no test of the controller route mapping or its `SecurityConfig` reachability.

---

## 3. Per-finding detail

### 3.1 F-0403 — contract cancel — **FIXED**

- `Contract.canCancel()` (`Contract.java:243`) is a positive allowlist: `DRAFT || PENDING_SIGNATURES`. `ACTIVE`, `COMPLETED`, `CANCELLED` excluded. Enum confirmed to have exactly those five values (`ContractStatus.java`), so nothing falls through an unhandled state.
- `ContractService.cancel` (`:919`) — brand path: `requireMember` **then** `requireRole(OWNER, ADMIN, MANAGER)` **then** `requireContract(contractId, workspaceId)` → `findByIdAndWorkspaceId` (`:1312-1317`). Workspace-scoped, tier-gated.
- `ContractService.cancelForCreator` (`:929`) — `requireCreator` then `findByIdAndCreatorId` (`:1319-1324`). A creator cannot cancel a stranger's contract; it 404s.
- `doCancel` (`:935`) 409s `CONTRACT_NOT_CANCELLABLE` rather than silently no-op'ing on a terminal state.
- Dual-role authorization (brand OR creator) is the right shape — it mirrors `DealService#reject` for a not-yet-executed agreement, and full execution requires both signatures.

**Caveat (mine, not theirs):** the OWNER/ADMIN/MANAGER tier gate is **untested**. `ContractCancelAmendTest` mocks `brandContext` (`setUp`, `:104-126`), so `requireRole` is a no-op in all 15 tests. No test proves a VIEWER-tier member is rejected. The gate is real in code; it just has no gate of its own.

**Caveat (theirs, verified true):** no `PESSIMISTIC_WRITE` row lock — `ContractRepository` genuinely has no `findByIdForUpdate`. A cancel racing a `recordSignature` on the same contract is open. Honestly declared in the javadoc; accept as a follow-up.

### 3.2 F-0413 — expiration date — **FIXED**

- Populated at `ContractService.java:306` (generate) and `:520` (amend), via `latestMilestoneDueDate` (`:578`), which takes the max non-null milestone due date and returns `null` when there are none. Nothing fabricated.
- Column is real and always was: `Contract.java:89-90`, `V10__contracts_and_milestones.sql:14`. Exposed on the DTO at `MoneyDtos.java:252`.
- **I verified their frontend claim independently rather than accepting it.** `src/components/brand/contracts/contracts-and-deliverables.tsx:562` maps `rec.expirationDate → expiryDate`; `:1258` renders "Timeline: Content due by", `:1282` and `:1786` render "Due". So the field is consumed as a **deliverable deadline**, not a signature deadline — binding it to the latest milestone due date is the correct semantics, not a convenient reinterpretation. Before this fix that surface always rendered "—".
- Scope: no `@Scheduled` job enforces expiry. Declared explicitly and accurately. The finding was "never populated", and it is now populated; enforcement is a separate ticket for whoever owns `com.influora.job`.

### 3.3 F-0498 — portfolio rate card — **FIXED**

- Cap: `MAX_RATE_CARD_ROWS = DeliverableType.values().length` (= 9) at `:110`, enforced in `validateRateCard` (`:949`) called at `:235`. Previously uncapped.
- Per-row `min > max` now 400s `INVALID_RATE_RANGE`, matching onboarding/profile-edit.
- Real rows persist via `writeSettings(settings, rateCard)` and read back via `loadRateCard` (`:874`); `buildRateCard` prefers stored rows at `:766` with the fabricated pair retained as a fallback for profiles that never used this editor.
- `extractRateMin`/`extractRateMax` now compute the true floor/ceiling across all rows instead of `rateCard.get(0)` — this matters because `rateMin` feeds `CreatorDiscoveryService`'s rate filter.
- Two subtle round-trip bugs found and fixed by them, both real: `JsonNodeFactory.withExactBigDecimals(true)` (`:100`) for the `stripTrailingZeros` scale change, and `USE_BIG_DECIMAL_FOR_FLOATS` on the reader for the `5000 → 5000.0` double coercion.
- **I checked the privacy ordering myself:** the `publicView && !"public".equals(rateCardVisibility)` early-return still precedes the new stored-rows branch, so the fix does not leak a private rate card onto a public page.
- Gap: no test covers the `publicView = true` path in the new class.

### 3.4 F-0414 — contract amend — **FIXED, with two new defects it introduces**

The route exists, is brand-only (correct — authoring terms is a brand action), and its authorization is real: `requireMember` + `requireRole(OWNER, ADMIN, MANAGER)` + workspace-scoped `requireContract` (`ContractService.java:442-446`). Validation mirrors `generate` exactly (non-empty milestones, per-row positive, total positive, total ≤ `agreedRate`). Latest-version guard (`CONTRACT_NOT_LATEST_VERSION`) and terminal-state guard (`CONTRACT_NOT_AMENDABLE`) both present. The versioned-new-row design, rather than mutating a signed row, is the right call.

Two defects it ships, neither caught by its own 15 tests. **File as follow-ups; do not block the wave on them.**

**(a) MEDIUM — amendment permanently inflates campaign analytics.**
`amend` inserts new `PaymentMilestone` rows but never removes or marks the superseded contract's rows, which stay keyed on the same `collaborationId`. `DeliverableMetricService.java:168` reads `findByCollaborationIdIn(collaborationIds)` and `:170` derives `deliverablesTotal` from that — **contract-blind**. Every amendment therefore double-counts. This residue class is already documented in-repo at `CollaborationReviveService.java:190-195` ("cancelling a contract leaves its PaymentMilestone and Deliverable rows keyed on this collaboration, and nothing deletes them"), so it was knowable from the codebase.

**(b) MEDIUM, money-adjacent — amending an ACTIVE contract breaks the funded↔current linkage.**
By design the ACTIVE row is left untouched and a fresh unsigned DRAFT becomes "the" contract for `DealService.java:2031-2034`'s most-recent-version lookup. But funded `EscrowHold`s are bound to the **old** contract's milestones, and `escrowFunded` in the deal response is a collaboration-wide probe. Result: the deal reads "escrow funded" while every milestone on the current contract has `escrowHoldId = null`.
No money is lost — I checked `EscrowService.resolveHoldsForCollaboration` (`:1558-1581`), which de-dupes by hold id and skips null `escrowHoldId`. But the linkage is broken and nothing tests it. **Recommend: block `amend` on an ACTIVE contract that has funded escrow until this is designed properly**, rather than leaving the ACTIVE-amend path live.

**(c) LOW — amendments are invisible in the audit trail.** `generate` records two `ApplicationHistory` events (`ContractService.java:~345-370`); `amend` records none. A contract can be superseded with no history entry.

---

## 4. Revert probes — I ran two

### 4.1 Probe A — surgical mutation of the privilege-escalation guard (`AdminAuthService.java`)

Replaced `if (caller.getId().equals(targetAdminId)) {` at `:335` with `if (false) {` — a targeted falsification rather than a whole-file revert, so a pass could not be explained by a compile error.

```
[ERROR] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0
[ERROR] AdminAuthServiceTest.selfResetIsRejected:128
        expected: <CANNOT_RESET_OWN_MFA> but was: <ADMIN_NOT_FOUND>
```

The guard is load-bearing and the test binds to the guard itself, not to incidental behavior. File restored; diff stat back to 50 insertions / 6 deletions.

### 4.2 Probe B — full revert of `PortfolioService.java` — **and the false green it exposed**

`git checkout --` on the file, then ran the test class. **First result: `Tests run: 5, Failures: 0 — BUILD SUCCESS` against fully reverted source.**

I did not accept that. `javap -p -classpath target/classes com.influora.service.portfolio.PortfolioService` still listed `loadRateCard`, `validateRateCard`, and the two-arg `writeSettings`. **maven-compiler-plugin's incremental compilation had not recompiled the reverted file, and surefire ran the old `.class`.**

After `rm target/classes/.../PortfolioService.class` and re-running:

```
[ERROR] Tests run: 5, Failures: 5, Errors: 0, Skipped: 0
  updateMine_multiRowRateCard_roundTripsRealRows:139
      every row the client sent must come back, not just row[0] ==> expected: <3> but was: <2>
  updateMine_exactlyAtCap_isAccepted:202                 expected: <9> but was: <2>
  updateMine_patchWithoutRateCard_preservesStoredRows:220 expected: <1> but was: <2>
  updateMine_rowMinExceedsMax_throwsInvalidRateRange:163  Expected ApiException, nothing was thrown
  updateMine_tooManyRows_throwsRateCardTooLarge:182       Expected ApiException, nothing was thrown
```

All five red on real assertions, and the `<2>` is exactly the pre-fix "collapse to row[0] + fabricate two generic rows" behavior. The gate is genuine. File restored; diff stat back to 143 insertions / 4 deletions.

### 4.3 Process finding — applies to every wave, not just this one

**`mvn -o -q -Dtest=X test` in this repo can report BUILD SUCCESS against code that is no longer on disk.** Any producer-run gate in this project that used that command without `clean` is unproven, including any pre-fix "exit 1" evidence. This is the same failure class as our existing "CI gates passing vacuously" note.

**Ruling (CTO, effective now):** all proof-os gate runs in `influora-api` use `mvn -o clean test`, or a `javap` spot-check of the class under test. A producer-reported green from a non-clean run is not evidence.

---

## 5. Blocking and near-blocking items

### 5.1 Not blocking — role gate untested on cancel/amend
`brandContext` is mocked throughout `ContractCancelAmendTest`. Add one test per route proving a below-MANAGER member is rejected. Follow-up.

### 5.2 Not blocking — non-SUPER_ADMIN rejection is mock-asserted
See §2.4. The real gate holds on inspection. Add an `AdminContextService`-level test. Follow-up.

### 5.3 **BLOCKING before push** — two wave-C files are untracked
```
?? influora-api/src/main/java/com/influora/ops/ProvisionSuperAdminRunner.java
?? scripts/provision-super-admin.sh
```
Both pass every local gate and would not exist on a pushed branch. This is precisely our F-0324 lesson. **`git add` both before this wave is committed.** (The new test classes are untracked too and need the same treatment.)

### 5.4 Concurrent-session warning
`BrandDeliverableService.java` and `BrandDeliverableServiceTest.java` appeared as modified **during** this review. `CampaignService` / `DealService` / `EscrowService` / `MoneyDtos` changes in the working tree belong to other waves. My final `git status` confirms both files I probed are restored to their wave-C state and nothing of mine leaked into the tree.

---

## 6. Sign-off

Wave C is **accepted**. All six findings are genuinely fixed, the authorization on all three new routes is real and not "any authenticated user", and both revert probes confirm the tests bind to the fixes.

Conditional on:
1. `git add` the two untracked main/script files and the new test classes (§5.3) — **hard blocker**.
2. Ticket the two amend residue defects (§3.4a, §3.4b); decide whether to gate ACTIVE-contract amend on funded escrow before shipping that path.
3. Adopt `mvn -o clean test` for all gate runs (§4.3).

— Priya, CTO
