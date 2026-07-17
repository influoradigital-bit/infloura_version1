# QA Review: Wave B Task B2 (MetaTokenRefreshService + StaleTokenCleanupJob) + B1 Timestamp Fix

**Date:** 2026-07-07  
**Reviewer:** Kavya (QA Lead)  
**Status:** ✅ **APPROVED**

---

## Executive Summary

**B1 Timestamp Fix:** APPROVED. The fallback formatter correctly handles Meta's `+0000` offset format. Implementation is sound, never-throws contract preserved, test coverage proves the fix.

**B2 Token Refresh + Cleanup:** APPROVED. Token refresh correctly re-encrypts through the same storage seam (no plaintext detour), scopes preserved, null/empty responses handled. Cleanup timing gap analysis shows 7-day safety buffer between refresh window and 14-day grace period. No token values logged. Test quality is excellent (13 tests, all coverage claims verified).

**Verdict:** Code quality 9.5/10. Ready for Kabir security review (token-handling is security-sensitive per plan).

---

## B1 Timestamp Fix Review

### ✅ APPROVED — All checks pass

**File:** `influora-api/src/main/java/com/influora/job/MetricsPollingJob.java`

#### Correctness Verification

1. **Fallback ordering correct** (lines 387-399):
   - First tries `Instant.parse` (handles `Z` and colon-offset like `+00:00`)
   - Falls back to `META_OFFSET_FORMATTER` (`yyyy-MM-dd'T'HH:mm:ssZ`) for Meta's `+0000` format
   - Returns `null` on both failures with a log.warn (no throw)
   - ✅ Never-throws contract preserved

2. **Formatter definition correct** (lines 75-78):
   ```java
   private static final DateTimeFormatter META_OFFSET_FORMATTER =
       DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");
   ```
   - `Z` pattern (capital Z) in Java DateTimeFormatter parses RFC-822 numeric offsets (`+0000`, `-0500`)
   - ✅ Matches Meta's actual format

3. **Test coverage proves the fix** (`MetricsPollingJobTest.java:544-568`):
   - Test uses the literal string `"2026-07-01T18:00:00+0000"` (the exact shape Kabir flagged)
   - Asserts `saved.getPostedAt()` equals `Instant.parse("2026-07-01T18:00:00Z")`
   - ✅ Test would fail if the fallback didn't work
   - Additional test (lines 571-590) proves unparseable input → `null`, no throw

**Regression check:** `Instant.parse`-first ordering means existing `Z`/colon-offset strings still work (no regression).

**Finding:** NONE. Implementation correct.

---

## B2 Token Refresh Review

### ✅ APPROVED — All critical checks pass

**File:** `influora-api/src/main/java/com/influora/job/MetaTokenRefreshService.java`

#### 1. Token Encryption Path (CRITICAL for Kabir security review)

**Claim:** Refreshed token is re-encrypted through the exact same storage path, no plaintext persistence.

**Verification:**
- Line 129: `MetaTokenResponse refreshed = oAuthService.refreshLongLivedToken(currentToken.get())`
- Lines 130-135: Null/empty token check (returns `false`, never stores bad data)
- Line 140-141: `tokenStorage.storeToken(creatorProfileId, workspaceId, refreshed.accessToken(), ...)`

**Cross-reference to `MetaTokenStorage.storeToken` (checked via grep):**
```java
public void storeToken(..., String accessToken, ...) {
    String encrypted = encrypt(accessToken);  // Line 84
    // ... saves encrypted value to DB
}
```

✅ **Confirmed:** The plaintext `refreshed.accessToken()` passes directly into `tokenStorage.storeToken`, which immediately encrypts it (line 84 of `MetaTokenStorage`) before database persistence. No intermediate plaintext storage, no logging of the token value.

#### 2. Scope Preservation

**Claim:** Granted scopes survive the refresh cycle.

**Verification:**
- Line 117: `refreshOne(workspaceId, creatorProfileId, tokenRow.getGrantedScopesJson())`
- Line 138: `List<String> grantedScopes = JsonLists.stringListFromJson(grantedScopesJson)`
- Line 140-141: `tokenStorage.storeToken(..., grantedScopes)`

✅ **Confirmed:** The original `grantedScopesJson` from the database row is deserialized and passed through to `storeToken`. Meta's refresh response doesn't include scopes, so the code correctly preserves the original grant.

**Test verification:** `MetaTokenRefreshServiceTest.java:58-91` explicitly captures the scopes argument (line 70-77 ArgumentCaptor) and asserts it equals the input scopes list.

#### 3. Null/Empty Token Response Handling

**Code path:** Lines 130-135
```java
if (refreshed == null || refreshed.accessToken() == null || refreshed.accessToken().isBlank()) {
    log.error("MetaTokenRefreshService: Meta returned an empty refreshed token for creator {}", creatorProfileId);
    return false;
}
```

✅ **Confirmed:** All three null/empty cases handled (null response, null token field, blank string). The service returns `false` (counted as `failed++`), never stores garbage.

**Test verification:** `MetaTokenRefreshServiceTest.java:196-208` proves empty-string token is not stored.

#### 4. Logging Audit (CRITICAL for Kabir security review)

**Grep results for all log statements in `MetaTokenRefreshService.java`:**
- Line 65: `log.warn("...previous run still in progress...")`  — no PII
- Line 95: `log.error("...unexpected failure refreshing token for creator {}", creatorProfileId, e)`  — logs creator ID (internal), not token
- Line 122: `log.warn("...no valid token to refresh for creator {}, skipping", creatorProfileId)`  — no token value
- Line 131: `log.error("...Meta returned an empty refreshed token for creator {}", creatorProfileId)`  — no token value
- Line 147: `log.error("...Meta refresh call failed for creator {}: {}", creatorProfileId, e.getMessage())`  — logs `e.getMessage()` ONLY (not full exception body; comment on line 145 flags this explicitly)

✅ **Confirmed:** No log statement in this class ever logs a token value. The only external data logged is `creatorProfileId` (internal ULID) and `e.getMessage()` (redacted by the calling service per the code comment).

#### 5. Expiry Computation

**Code path:** Lines 156-159
```java
private Instant computeExpiresAt(Long expiresInSeconds) {
    long seconds = (expiresInSeconds == null || expiresInSeconds <= 0) ? 60L * 24 * 60 * 60 : expiresInSeconds;
    return Instant.now().plusSeconds(seconds);
}
```

✅ **Confirmed:** Falls back to 60 days (5,184,000 seconds) if Meta's response is null or non-positive. Matches Meta's documented long-lived token TTL (~60 days).

**Test verification:** `MetaTokenRefreshServiceTest.java:94-112` asserts the computed expiry is within 5 seconds of the expected value (accounting for test execution skew).

---

## B2 Stale Token Cleanup Review

### ✅ APPROVED — All checks pass

**File:** `influora-api/src/main/java/com/influora/job/StaleTokenCleanupJob.java`

#### 1. Soft-Revoke Semantics (Never Delete)

**Code path:** Lines 116-123
```java
private void revokeOne(MetaOAuthToken tokenRow) {
    tokenRow.revoke();
    tokenRepository.save(tokenRow);
    log.warn("...revoked stale Meta token for creator {} (expired since {})", ...);
}
```

✅ **Confirmed:** Calls `tokenRow.revoke()` (sets `revoked = true`) and saves the row. Never calls `delete()` or `deleteById()`.

**Test verification:** `StaleTokenCleanupJobTest.java:49-63` explicitly verifies:
- `verify(tokenRepository, never()).delete(any())`
- `verify(tokenRepository, never()).deleteById(any())`
- `verify(tokenRepository).save(savedCaptor.capture())`

#### 2. Grace Period vs Refresh Window Timing (CRITICAL — potential gap analysis)

**Refresh window:** 7 days before expiry (default `token-refresh-days-before-expiry`, `application.yml:129`)  
**Cleanup grace period:** 14 days AFTER expiry (`STALE_GRACE_PERIOD`, line 48)  
**Refresh schedule:** Daily at 2:30 AM (line 62)  
**Cleanup schedule:** Daily at 4:00 AM (line 61)

**Timing analysis:**
- A token expiring on Day 0 will be caught by the refresh sweep between Day -7 and Day 0.
- If all 7 daily refresh attempts fail (or the user revoked access at Meta's end), the token sits expired but `revoked = false` until Day +14.
- Cleanup at Day +14 marks it `revoked = true`.

**Gap check:** Is there a window where a token is neither refreshed nor cleaned but also dead?

✅ **No gap:** A token that reaches the cleanup threshold (expired >14 days) has necessarily been in the refresh window for 21 days total (7 days pre-expiry + 14 days post-expiry). If it's still `revoked = false`, every daily refresh attempt in that window has failed. Cleanup is the correct action.

**Ordering check:** Cleanup runs 1.5 hours AFTER refresh (2:30 AM vs 4:00 AM), so cleanup always sees that day's refresh results. A token refreshed at 2:30 AM on Day +13 will have a new `expiresAt` ~60 days out, so it won't appear in the cleanup query (threshold = Day -14 from "now").

✅ **No race:** The two jobs cannot revoke the same token on the same day (refresh updates `expiresAt`, moving it out of cleanup's `findByExpiresAtBeforeAndRevokedFalse` query).

**Test verification:** `StaleTokenCleanupJobTest.java:66-82` asserts the threshold is "now - STALE_GRACE_PERIOD", not "now" (proving it's a different window from refresh).

---

## B2 Test Quality Review

### MetaTokenRefreshServiceTest (8 tests) — ✅ EXCELLENT

1. **Scope preservation** (lines 58-91): Explicitly captures and asserts the scopes list passed to `storeToken`. ✅ Proves the claim.
2. **Expiry computation** (lines 94-112): Asserts computed expiry is ~60 days out (within 5-second skew). ✅ Proves the fallback.
3. **Failure isolation #1** (lines 115-142): Two tokens, first throws `MetaApiException`, second succeeds. Verifies only the second is stored. ✅ Proves batch isolation.
4. **Failure isolation #2** (lines 145-166): Two tokens, first throws `RuntimeException` from `getValidToken`, second succeeds. ✅ Proves defensive catch-all.
5. **No-op case** (lines 169-179): Empty expiring-tokens list → no refresh calls, audit still logged. ✅ Correct.
6. **Token gone mid-sweep** (lines 182-193): `getValidToken` returns empty (token expired/revoked between query and refresh) → skipped, not thrown. ✅ Correct.
7. **Empty refreshed token** (lines 196-208): Meta returns blank access token → not stored. ✅ Proves the null/empty guard.
8. **Overlap guard** (lines 211-236): Two threads, second is blocked by AtomicBoolean. ✅ Proves single-threaded guarantee.

**Verdict:** All 8 tests are non-rubber-stamps. Coverage is comprehensive.

### StaleTokenCleanupJobTest (5 tests) — ✅ EXCELLENT

1. **Soft-revoke semantics** (lines 49-63): Explicitly verifies `never().delete`, `save` called, `revokeCalled = true`. ✅ Proves never-delete contract.
2. **Grace period threshold** (lines 66-82): Captures the `Instant` passed to `findByExpiresAtBeforeAndRevokedFalse`, asserts it's `>= 14 days before now`. ✅ Proves the grace-period calculation.
3. **No-op case** (lines 85-94): Empty list → no saves, audit still logged. ✅ Correct.
4. **Failure isolation** (lines 97-113): Two tokens, first throws on `revoke()`, second succeeds. ✅ Proves per-token isolation.
5. **Overlap guard** (lines 116-137): Two threads, second blocked. ✅ Proves single-threaded guarantee.

**Verdict:** All 5 tests are non-rubber-stamps. Coverage is comprehensive.

---

## Summary of Findings

### CRITICAL: 0
### HIGH: 0
### MEDIUM: 0
### LOW: 0
### ADVISORY: 1 (documentation quality note)

**ADVISORY 1:** Cleanup job's 1.5-hour offset from refresh (2:30 AM → 4:00 AM) is correct but undocumented in the code comment. Line 60's comment says "offset from the refresh sweep so cleanup always sees that day's refresh attempts first" — true, but the specific timing (1.5 hours) and the reason (refresh updates `expiresAt`, moving tokens out of cleanup's query) could be spelled out. Not a defect, just a future-maintainer clarity note.

**Recommendation:** Optionally add a comment in `StaleTokenCleanupJob.java` near line 60:
```java
// Runs 1.5 hours after MetaTokenRefreshService (2:30 AM → 4:00 AM), guaranteeing that any
// token successfully refreshed that day will have a new expiresAt ~60 days out and won't
// appear in this job's findByExpiresAtBeforeAndRevokedFalse(staleThreshold) query.
```
Not blocking; Kabir may prefer to keep comments terse.

---

## Constraints Honored

✅ MySQL only — no new database  
✅ No new Maven dependencies  
✅ No migration needed (reuses existing `expiresAt`, `revoked`, `lastRefreshedAt` columns)  
✅ Follows `MetricsPollingJob` conventions (AtomicBoolean, per-item try/catch, `AuditLogService`)  
✅ Did not touch files outside `job/` package  
✅ Did not git commit

---

## Next Steps

1. **Route to Kabir for security review** (token refresh is money-adjacent and security-sensitive per Wave B task B2 acceptance criteria).
2. After Kabir sign-off, route to Meera for build verification (`mvn -o test` green, no schema drift).
3. After Meera verification, Arjun re-runs `mvn test` independently before advancing to next task.

---

## Files Reviewed

### B1 Timestamp Fix
- `influora-api/src/main/java/com/influora/job/MetricsPollingJob.java` (lines 1-40, 75-78, 370-399)
- `influora-api/src/test/java/com/influora/job/MetricsPollingJobTest.java` (lines 544-590)

### B2 Token Refresh + Cleanup
- `influora-api/src/main/java/com/influora/job/MetaTokenRefreshService.java` (full file, 160 lines)
- `influora-api/src/main/java/com/influora/job/StaleTokenCleanupJob.java` (full file, 125 lines)
- `influora-api/src/test/java/com/influora/job/MetaTokenRefreshServiceTest.java` (full file, 272 lines, 8 tests)
- `influora-api/src/test/java/com/influora/job/StaleTokenCleanupJobTest.java` (full file, 176 lines, 5 tests)
- `influora-api/src/main/java/com/influora/integration/meta/oauth/MetaTokenStorage.java` (cross-reference for encryption path)
- `influora-api/src/main/java/com/influora/integration/meta/oauth/MetaOAuthService.java` (cross-reference for `refreshLongLivedToken`)
- `influora-api/src/main/java/com/influora/config/MetaApiProperties.java` (full file, token-refresh-days-before-expiry default)
- `influora-api/src/main/resources/application.yml` (lines 1-131, token-refresh-days-before-expiry = 7)

**Total lines reviewed:** ~1,100 (code + tests)

---

**Kavya Reddy**  
QA Lead, Sage Digital  
2026-07-07
