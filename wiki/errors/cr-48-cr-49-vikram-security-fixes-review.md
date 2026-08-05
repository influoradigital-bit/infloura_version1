# QA Review: CR-48 + CR-49 Security Fixes
Date: 2026-07-30  
Reviewer: Kavya  
Status: **PASS with ADVISORY**

## Changes Reviewed
- `AuthRateLimitFilter.java` — /me/password → "sensitive" bucket (exact-match)
- `AuthService.java` — changePassword now revoke-except-current (4th param currentRawRefreshToken)
- `AccountController.java` — reads caller refresh token via authCookieService, passes through
- `RefreshTokenRepository.java` — new revokeAllForUserExcept(userId, keepId)
- `RefreshToken.java` — added getId()
- `AuthRateLimitFilterMePasswordBucketTest.java` (new, 4 tests)
- `AuthServiceTest.java` (3 new tests)

---

## Verification Results

### 1. Rate-limit bucket correctness
✅ **PASS**  
- `/me/password` exact-match returns "sensitive" via `path.equals("/me/password")` (line 366)
- Does NOT catch GET /me, PATCH /me, DELETE /me/account (verified by new tests)
- "sensitive" bucket limit = **10 requests per window** (line 111: `sensitiveLimit`)
- Filter runs for /me/password (no `shouldNotFilter` exclusion method exists in this class)

**ADVISORY:** 10 requests/window for BCrypt brute-force is reasonable for a fixed-window in-memory limiter, but not tight. For a password endpoint, consider:
- Dedicated bucket with limit=5 (half of login/register), OR
- Document that this is per-instance (horizontal scale = N × 10 req/window), and that a shared-store limiter (Redis/bucket4j) is required for production hardening

Current implementation is **acceptable** but **not optimal** for brute-force resistance. Recommend Vikram add a TECH DEBT ticket for a dedicated /me/password bucket with limit=5.

### 2. revoke-except-current correctness
✅ **PASS**
- `currentRefreshTokenId()` resolves via **IDENTICAL** validation logic as `refresh()`:
  - Hash lookup: `findByTokenHashAndRevokedFalse(JwtService.hashToken(rawRefreshToken))`
  - Expiry check: `.filter(t -> t.getExpiresAt().isAfter(Instant.now()))`
  - Same hash function, same repo method, same filter
- Fallback when cookie absent/null/blank → `revokeAllForUser` (safe, line 492)
- No NPE risk: `authCookieService.readRefreshToken` returns null when cookie absent (verified AuthCookieService.java:76-88), and `currentRefreshTokenId` null-guards (line 503)
- `revokeAllForUserExcept` JPQL correct: `AND r.id <> :keepId` (line 29, RefreshTokenRepository.java)
- `@Modifying(clearAutomatically = true)` present (line 25)

### 3. No regression
✅ **PASS**
- `AccountController.changePassword` signature change: added `HttpServletRequest request` param
- No existing `AccountControllerTest.java` found (verified via Glob) — no test to break
- `getId()` getter addition to RefreshToken is a safe read-only accessor

### 4. Wrong-password path
✅ **PASS**
- Line 476-481: `passwordEncoder.matches` fails → throws `INVALID_CURRENT_PASSWORD` 401 **BEFORE** any save/revoke
- Test `testChangePasswordWrongCurrentPasswordDoesNotRevokeAnything` confirms `verifyNoInteractions(refreshTokenRepository)` (line 697)
- No side effects on wrong password

### 5. Test quality
✅ **PASS**
- `testChangePasswordKeepsCallersOwnSession`: asserts `revokeAllForUserExcept(user.getId(), "01HKEEPTOKEN123456789ABCD")` called, `revokeAllForUser` never called
- `testChangePasswordFallsBackToRevokeAllWithNoRefreshToken`: asserts reverse (revokeAllForUser called, revokeAllForUserExcept never called), plus verifies no `findByTokenHashAndRevokedFalse` lookup when token is null
- `testChangePasswordWrongCurrentPasswordDoesNotRevokeAnything`: asserts 401 code, no userRepository.save, and `verifyNoInteractions(refreshTokenRepository)`
- `AuthRateLimitFilterMePasswordBucketTest`: covers exact-match /me/password → "sensitive", plus 3 near-misses (GET /me, PATCH /me, DELETE /me/account) → null

---

## TECH-STACK.md Compliance
✅ No violations found
- Java 21 + Spring Boot 3.x (confirmed)
- JPA @Query with @Modifying + @Transactional (standard pattern)
- JUnit tests with clear @DisplayName (standard)
- No banned patterns (no hardcoded secrets, no 'any' type, no security gates bypassed)

---

## Verdict: **PASS**

All 7 files ready for commit.

**Recommendation:** Vikram should open a follow-up ticket (not a blocker for this CR) to either:
- Lower the "sensitive" bucket limit to 5 for /me/password, OR
- Create a dedicated "password-change" bucket with limit=5

Current limit=10 is defensible but not hardened for production brute-force at scale.

---

## Next Steps
Route back to Arjun for commit + handoff to Meera (local run + build verify).
