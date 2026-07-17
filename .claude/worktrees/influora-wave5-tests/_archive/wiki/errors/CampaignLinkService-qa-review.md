# QA Review: CampaignLinkService (Phase 4 UTM Foundation)
Date: 2026-07-06  
Reviewer: Kavya  
Status: **APPROVED WITH MINOR GAPS** (3 missing test cases found, no bugs in production code)

---

## Executive Summary

**Verdict: APPROVED** — `mvn test` confirms 202/202 pass (189 baseline + 13 new), 0 failures, 0 errors. Production code is correct. Workspace authorization is properly implemented with the required "resolve-then-scope" pattern. URL generation logic (separator, encoding, idempotency) is correct. Counter increments are correct.

**3 test gaps found** (not bugs, just incomplete coverage):
1. Blank `visitorId` string (e.g., `""` or `"   "`) not tested — code handles it correctly via `!visitorId.isBlank()` guard at line 228, but no test exercises this branch.
2. Null `platform` not tested — code handles it correctly at line 157 (`platform == null ? "" : platform.toLowerCase()`), but no test exercises this branch.
3. Workspace-rejection test incomplete — verifies collaboration repo never called, but does NOT verify `creatorProfileRepository` was never touched (should add `verify(creatorProfileRepository, never()).findById(any())` to line 91).

**No production code changes needed.** Vikram may add the 3 missing tests (5-10 minutes work), or ship as-is since the actual service logic is correct.

---

## Detailed Findings

### ✅ 1. URL Generation Correctness (All Correct)

**Separator logic (`?` vs `&` depending on existing query string):**
- Tested both branches: `testBuildsUrlWithQuestionMarkSeparator` (line 202-230) and `testBuildsUrlWithAmpersandSeparator` (line 234-257).
- Production code at line 188: `baseUrl.contains("?") ? "&" : "?"` — correct.

**URL-encoding of special characters:**
- Test `testUrlEncodesSpecialCharacters` (line 261-294) covers ampersand (`"Diwali & New Year Sale!"`).
- `SlugUtils.slugify` (checked separately) normalizes Unicode → ASCII, strips non-word chars, and lowercases — correctly defensive.
- `encode()` helper at line 201-203 uses `URLEncoder.encode(..., StandardCharsets.UTF_8)` and handles `value == null` → empty string.
- **GAP:** No test for actual Unicode (Hindi/Chinese/emoji in campaign title or creator name). `SlugUtils` would convert `"दिवाली Sale"` → `"sale"` (strips Unicode after normalization) — this is correct behavior, but untested. Not a bug, just a missing test case.

**Idempotency (same campaign+creator → return existing row, not duplicate):**
- Test `testReturnsExistingLinkWithoutDuplicating` (line 297-331) mocks `findByCampaignIdAndCreatorProfileId` returning an existing `UtmCampaign` and asserts `utmCampaignRepository.save()` is `never()` called.
- Production code at line 145-148: `findByCampaignIdAndCreatorProfileId(...).orElseGet(() -> buildAndSave(...))` — correct lazy-save pattern.

---

### ✅ 2. Not-Found / Mismatch Handling (All 4 Error Paths Correct)

All 4 exception codes individually tested with correct HTTP status:

1. **`CAMPAIGN_NOT_FOUND` (404):** `testRejectsWhenWorkspaceDoesNotOwnCampaign` (line 70-92) — campaign lookup by `(campaignId, workspaceId)` returns empty → throws 404. This is ALSO the workspace-authorization enforcement (see §3 below).
2. **`COLLABORATION_NOT_FOUND` (404):** `testCollaborationNotFound` (line 151-171).
3. **`COLLABORATION_CAMPAIGN_MISMATCH` (403):** `testRejectsCollaborationCampaignMismatch` (line 95-118) — collaboration's `campaignId` doesn't match the resolved campaign's id → 403.
4. **`CREATOR_NOT_FOUND` (404):** `testCreatorNotFound` (line 174-195).
5. **`CREATOR_COLLABORATION_MISMATCH` (403):** `testRejectsCreatorCollaborationMismatch` (line 121-144) — creator's `userId` doesn't match collaboration's `creatorId` → 403.

All exception assertions check both `.getCode()` and `.getStatus().value()` — correct.

---

### ⚠️ 3. Workspace-Ownership Rejection Test (INCOMPLETE VERIFICATION)

**Production code is correct** (line 99-108): `campaignRepository.findByIdAndWorkspaceId(campaignId, workspaceId)` is the FIRST lookup, and `orElseThrow(CAMPAIGN_NOT_FOUND)` ensures a workspace that doesn't own the campaign never proceeds to line 110 (collaboration lookup).

**Test `testRejectsWhenWorkspaceDoesNotOwnCampaign` (line 70-92) is INCOMPLETE:**
- ✅ Correctly mocks `campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, OTHER_WORKSPACE_ID)` returning empty.
- ✅ Asserts exception code/status.
- ✅ Verifies `collaborationRepository.findById` is `never()` called (line 90).
- ✅ Verifies `utmCampaignRepository.save` is `never()` called (line 91).
- ❌ **MISSING:** does NOT verify `creatorProfileRepository.findById` is `never()` called.

**Why this matters:** The "resolve-then-scope" pattern (Kabir's concern from Analytics API review) requires proof that ZERO downstream repos are touched after the workspace-authorization gate fails. The test proves collaboration and UTM repos are untouched, but does NOT prove the creator-profile repo is untouched. Per the actual code flow (line 110 → line 120 → line 127), the creator repo IS in fact never reached if the campaign lookup fails — but the test does not assert this.

**Fix (optional, 1 line):** Add after line 91:
```java
verify(creatorProfileRepository, never()).findById(any());
```

This would bring the test up to the same rigor as `AnalyticsServiceTest.testGetCreatorMetricsRejectsUnauthorizedCreator` (line 97 in that file, which uses `verifyNoInteractions()` on the metrics repo).

---

### ✅ 4. `recordClick` Counter Tests (Correct)

**Click count always increments:**
- `testRecordClickIncrementsClickAndVisitorCounters` (line 351-362) — with non-null `visitorId`, both counters increment.
- `testRecordClickWithoutVisitorIdOnlyIncrementsClicks` (line 366-374) — with `null` `visitorId`, only `clickCount` increments.
- `testRecordClickAccumulatesAcrossCalls` (line 378-388) — 3 calls (2 with visitor ids, 1 without) accumulate correctly: `clickCount=3`, `uniqueVisitors=2`.

**Unique-visitor count increments only for non-null/non-blank `visitorId`:**
- Production code at line 228: `if (visitorId != null && !visitorId.isBlank())` — correct guard.
- ⚠️ **GAP:** Test only covers `null` case (line 370), NOT blank string (e.g., `""` or `"   "`). The guard itself is correct, but the branch is untested.

**Not-found path:**
- `testRecordClickNotFound` (line 338-347) — UTM id doesn't exist → `UTM_NOT_FOUND` (404). Correct.

---

### ⚠️ 5. Null/Edge Cases (2 Gaps)

**`platform` null:**
- Production code at line 157: `platform == null ? "" : platform.toLowerCase()` — correct defensive handling.
- ❌ **GAP:** No test exercises this branch. Every test passes a non-null platform string (`"instagram"` or `"Instagram"`).

**Blank `visitorId` string (not just null):**
- Production code at line 228: `!visitorId.isBlank()` — correct (`.isBlank()` returns `true` for null, empty, or whitespace-only strings).
- ❌ **GAP:** Test `testRecordClickWithoutVisitorIdOnlyIncrementsClicks` only passes `null` (line 370), not `""` or `"   "`.

**Unicode in campaign title/creator name:**
- `SlugUtils.slugify` (checked separately) normalizes Unicode → ASCII (e.g., `"दिवाली"` → empty after stripping non-Latin), then URL-encodes the result.
- ❌ **GAP:** No test with actual Unicode input (Hindi, Chinese, emoji). Test `testUrlEncodesSpecialCharacters` only covers ASCII special chars (`"Diwali & New Year Sale!"`).

---

## Summary of Missing Tests (Not Bugs, Just Coverage Gaps)

1. **Blank `visitorId` string** (`recordClick(UTM_ID, "")` or `recordClick(UTM_ID, "   ")`) — should NOT increment `uniqueVisitors`, only `clickCount`. Code is correct, test is missing.
2. **Null `platform`** — should result in `utmSource=""` in the saved entity. Code is correct, test is missing.
3. **Workspace-rejection test completeness** — add `verify(creatorProfileRepository, never()).findById(any())` to line 91 of the test to match the same rigor as Analytics API's `verifyNoInteractions()` proof.

**Optional:** Unicode campaign title/creator name test (not a real-world blocker — Unicode gets stripped by `SlugUtils`, which is correct behavior for URL slugs).

---

## Test Count Verification

**Claimed:** 13 tests in `CampaignLinkServiceTest.java`.  
**Actual (own count from test file):** 13 `@Test` methods — confirmed.  
**Maven output:** `Tests run: 13, Failures: 0, Errors: 0, Skipped: 0` — confirmed.  
**Total suite:** 202 tests (189 baseline + 13 new) — matches Vikram's claim exactly.

---

## No QA Checklist Violations

- ✅ No `'any'` TypeScript type — N/A (Java).
- ✅ All fields properly typed — checked: `UtmCampaign` entity uses correct types (`String` for ids/URLs, `long` for counters).
- ✅ No console.log — N/A (Java).
- ✅ No API keys hardcoded — checked: no env vars, no secrets, pure service logic.
- ✅ Input validation — all 5 entity lookups throw typed `ApiException` with correct HTTP status.
- ✅ SQL queries use repository methods — checked: all JPA-based, no raw SQL.

---

## Verdict

**APPROVED** — production code is correct, all critical paths tested, 202/202 tests pass. The 3 missing test cases are low-priority coverage gaps, not bugs. Vikram may add them (5-10 minutes) or ship as-is.

**No blocking issues.**

---

## Next Steps

Route to Kabir for workspace-isolation security review (same concern class as Analytics API — this is the SECOND real caller of the "resolve-then-scope" pattern after `AnalyticsService`). Meera will verify V23 migration boots cleanly on live schema.
