# P0-1 — Backend test suite won't compile

**Owner:** Vikram · **Reviewers:** Kavya (QA) → Meera (`mvn test` verify) · **Priority:** P0 · **Depends on:** — · **Blocks:** all backend items (P1-2..P2-17)
**Status:** ✅ DONE  ·  *(agent: → 🟡 IN PROGRESS on start, ✅ DONE when acceptance met, then update INDEX.md)*

## Goal
`../.tools/apache-maven-3.9.9/bin/mvn -o test` currently **BUILD FAILURE** — test sources don't compile even though main compiles. Make the test suite compile and run.

## Root causes (verified 2026-07-12)
1. **Missing test dependency:** `src/test/java/com/influora/testsupport/AbstractIntegrationTest.java:6-9` imports `org.testcontainers.*` — not in `pom.xml`.
2. **Test↔prod signature drift** (production refactored, tests not updated):
   - `IdempotencyService.executeOnce(...)` — arg mismatch in `IdempotencyServiceTest`, `MeeraSessionServiceTest`
   - `WalletService` constructor — `WalletServiceTest:45`
   - `ConfirmLaunchExecutor` / `CreateCampaignExecutor` constructors — respective tests
   - `MetaOAuthController` ctor + `MetaOAuthStateStore.issue(...)` + `MetaOAuthService` methods — `MetaOAuthControllerTest`

## Files
- `influora-api/pom.xml` (add `org.testcontainers:testcontainers` + `:junit-jupiter`, test scope — **needs Priya sign-off in `wiki/tech/approved-deps.md` first**)
- `influora-api/src/test/java/com/influora/testsupport/AbstractIntegrationTest.java`
- test files listed above under `influora-api/src/test/java/com/influora/**`

## Steps
1. Get Priya to log the testcontainers dep in `wiki/tech/approved-deps.md`, then add to `pom.xml` (test scope, matching Spring Boot BOM version).
2. Update each drifted test to the current production signature (read the prod class, fix the mock/ctor call — do NOT change prod to match stale tests without CTO review).
3. `mvn -o compile` still green, then `mvn -o test` to BUILD SUCCESS.

## Acceptance criteria
- [x] Priya-approved testcontainers dep logged + added to `pom.xml`
- [x] All drifted test files compile against current prod signatures
- [x] `mvn -o test` → BUILD SUCCESS, tests actually execute (report `Tests run: N`)
- [x] Kavya QA pass → Meera verify pass

## Completion log
- Meera · 2026-07-12 · Tests run: 888, Failures: 11, Errors: 9, Skipped: 0 — **BUILD SUCCESS** ✅ (tests EXECUTE, goal was compile+run not all-pass)
- Vikram · 2026-07-12 (re-drift fix) · In-flight P2 work (P2-9 WooCommerce, P2-12 payout KYC, MediaMetric analytics, portfolio notification/user wiring) added new constructor deps to `IntegrationHealthService`, `PayoutService`, `AnalyticsService`, `PortfolioService` — their tests weren't updated, so `mvn -o test-compile` broke again even though `mvn -o compile` stayed green. Fixed all 4 test files against current prod signatures (did not touch prod code):
  - `IntegrationHealthServiceTest` — added `WooCommerceIntegrationRepository` mock + ctor arg, stubbed its `findByWorkspaceIdAndRevokedFalse` for the two false-path tests (short-circuited OR, no stub needed for the true-path test).
  - `PayoutServiceTest` — added `PayoutRepository`, `CreatorProfileRepository`, `CreatorBankAccountRepository`, `RazorpayFundAccountService`, `ObjectMapper` mocks + ctor args. `doQueuePayout` now resolves a Razorpay fund-account id via `creatorBankAccountRepository`/`fundAccountService` instead of using the creator id directly, so added a `mockFundAccountResolution()` helper (one bank account on file, `resolveFundAccountId` returns `CREATOR_ID`) so the existing `initiatePayout(eq(CREATOR_ID), ...)` assertions still hold for the two tests that reach `doQueuePayout`.
  - `AnalyticsServiceTest` — added `MediaMetricsRepository` mock + ctor arg (unused by existing test cases, no additional stubbing needed).
  - `PortfolioServiceTest` — added `NotificationService`, `UserRepository` mocks + ctor args. Confirmed `NoOpMalwareScanService implements MalwareScanService` (no prod change needed there).
  - Fresh `../.tools/apache-maven-3.9.9/bin/mvn -o test`: **Tests run: 879, Failures: 11, Errors: 9, Skipped: 0**. All 4 target files: 0 failures/0 errors (19 tests: 3+9+6+1). Remaining 11F/9E are pre-existing and unrelated to this fix (MultipartConfigTest, DealServiceTest, MeeraSessionServiceTest, ConfirmLaunchExecutorTest, CreateCampaignExecutorTest, RedemptionServiceTest, DatabaseConstraintIntegrationTest — the last needs Docker, not available in this environment).
