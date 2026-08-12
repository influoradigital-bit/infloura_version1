# QA Review: CR-110 Test Coverage Fix
Date: 2026-08-10  
Reviewer: Kavya  
Artifact: influora-api/src/test/java/com/influora/integration/meta/oauth/MetaTokenStorageTest.java  
Status: **PASS**

---

## Task Summary
Verify that CR-110's test coverage additions are real, complete, and actually running in the build.

**done_when requirements:**
1. MetaTokenStorage.storeCreatorToken has real test coverage for both INSERT and UPDATE paths
2. igBusinessAccountId persistence is specifically asserted
3. Claims about MetricsPollingJobTest/AudienceDemographicsJobTest/CreatorCaptionSyncJobTest are independently verified against actual current file state

---

## Verification Results

### 1. MetaTokenStorageTest.java — storeCreatorToken Coverage ✅

**INSERT branch coverage:**
- Line 352-378: `testStoreCreatorTokenInsertBranchEncryptsAndSaves`
  - Mocks empty Optional to force INSERT path
  - Asserts workspaceId=null (creator-owned)
  - **Asserts igBusinessAccountId persisted** (line 373)
  - Verifies encryption
  
- Line 424-444: `testStoreCreatorTokenPersistsIgBusinessAccountId`  
  - **CR-110 regression test** explicitly labeled
  - Asserts igBusinessAccountId carried through on INSERT
  - Javadoc explicitly calls out CR-110 fix

**UPDATE branch coverage:**
- Line 381-421: `testStoreCreatorTokenUpdateBranchRevokesAndReinserts`
  - Mocks existing token to force UPDATE path
  - Verifies old token revoked, new token inserted
  - **Asserts new igBusinessAccountId persisted** (line 419)
  - Validates the revoke-then-insert behavior

**Additional storeCreatorToken tests:**
- Audit log verification (no token leakage)
- Encrypt/decrypt round-trip
- Null igBusinessAccountId allowed (not-yet-linked case)

### 2. MetricsPollingJobTest.java — IG ID Assertions ✅

**Current state verified:**
- Line 51-53: Test constants define BOTH `CREATOR_ID` (ULID) AND `IG_BUSINESS_ACCOUNT_ID` (numeric)
- Line 343-363: **CR-99/F-0113 regression test** `testPollMetricsUsesIgBusinessAccountIdNotUlid`
  - Line 361: `verify(instagramClient, never()).getProfile(eq(CREATOR_ID), anyString());`
  - Line 362: `verify(instagramClient).getProfile(eq(IG_BUSINESS_ACCOUNT_ID), eq(TOKEN_VALUE));`
  - **Explicitly guards against passing ULID instead of numeric IG ID**

**Ticket claim CONFIRMED:** MetricsPollingJobTest does assert the correct numeric IG ID vs the wrong ULID.

### 3. AudienceDemographicsJobTest.java — IG ID Assertions ✅

**Current state verified:**
- Line 51-53: Test constants define BOTH `CREATOR_ID` (ULID) AND `IG_BUSINESS_ACCOUNT_ID` (numeric)
- Line 258-281: **CR-99/F-0113 regression test** `testUsesIgBusinessAccountIdNotUlid`
  - Line 279: `verify(instagramClient, never()).getAudienceDemographics(eq(CREATOR_ID), anyString());`
  - Line 280: `verify(instagramClient).getAudienceDemographics(eq(IG_BUSINESS_ACCOUNT_ID), eq(TOKEN));`
  - **Explicitly guards against passing ULID instead of numeric IG ID**

**Ticket claim CONFIRMED:** AudienceDemographicsJobTest does assert the correct numeric IG ID vs the wrong ULID.

### 4. CreatorCaptionSyncJobTest.java — Existence & Coverage ✅

**File exists:** `influora-api/src/test/java/com/influora/job/CreatorCaptionSyncJobTest.java`

**Coverage verified:**
- 9 test methods covering all major branches
- Per-creator error isolation tested
- Per-item error isolation tested
- Blank caption skip logic
- Dedup logic
- No-linked-account skip path
- Batch size cap

**NOTE:** CreatorCaptionSyncJobTest does NOT have an explicit igBusinessAccountId regression test like the other two job tests. However:
- Line 46-47: Test constants define `IG_ID_1` and `IG_ID_2` (numeric IDs)
- Line 102, 119, 135, etc.: All `instagramClient.getMedia(IG_ID_1, ...)` calls use the numeric ID
- The test helper `tokenFor(creatorProfileId, igBusinessAccountId)` at line 65 explicitly binds both IDs

**Conclusion:** Test exists and covers the job's behavior, though it lacks the explicit never-use-ULID assertion that MetricsPollingJobTest and AudienceDemographicsJobTest have.

---

## Build Verification

**Command:** `mvn -o test -Dtest=MetaTokenStorageTest,MetricsPollingJobTest,AudienceDemographicsJobTest,CreatorCaptionSyncJobTest`

**Results:**
```
[INFO] Tests run: 52, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**Breakdown:**
- MetaTokenStorageTest: 19 tests — 0 failures
- AudienceDemographicsJobTest: 12 tests — 0 failures
- CreatorCaptionSyncJobTest: 9 tests — 0 failures
- MetricsPollingJobTest: 12 tests — 0 failures

All tests execute cleanly. No compilation errors, no runtime failures.

---

## Verdict: **PASS** ✅

### Summary
1. ✅ MetaTokenStorage.storeCreatorToken has real, comprehensive test coverage for both INSERT and UPDATE paths
2. ✅ igBusinessAccountId persistence is explicitly asserted in 3 separate test methods, including dedicated CR-110 regression tests
3. ✅ MetricsPollingJobTest and AudienceDemographicsJobTest both have explicit CR-99/F-0113 regression tests asserting correct numeric IG ID usage (never the ULID)
4. ✅ CreatorCaptionSyncJobTest exists, has 9 tests, all passing
5. ✅ All 52 tests pass in the build with zero failures

### What Changed (CR-110 Fix Confirmed)
- **Before:** storeCreatorToken had zero test coverage (per ticket)
- **After:** 7 dedicated tests covering both branches, including explicit igBusinessAccountId persistence assertions
- Regression guards in place to prevent re-introduction of the bug

### No Issues Found
- Tests are well-structured, use proper mocking patterns
- Assertions are specific and meaningful
- Test names clearly describe what they verify
- Build output clean (only expected warnings about Java agents)

---

## Next Steps
None. CR-110 test coverage fix is complete and verified. Ready for delivery.

---

**Reviewed by:** Kavya Reddy, QA Lead  
**Date:** 2026-08-10  
**Build:** influora-api 0.1.0-SNAPSHOT  
**Maven:** offline mode, surefire 3.2.5
