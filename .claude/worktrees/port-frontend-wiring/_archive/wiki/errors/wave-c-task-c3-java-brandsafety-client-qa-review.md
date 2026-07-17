# QA Review: Wave C Task C3 — Java BrandSafetyAiClient Integration
**Date:** 2026-07-07  
**Reviewer:** Kavya (QA Lead)  
**Task:** Spring-side consumer of influora-ai's `/internal/brand-safety` endpoint  
**Developer:** Vikram  
**Status:** ✅ **APPROVED — All blockers resolved**  
**Final Re-QA:** 2026-07-07 (second pass)

---

## Executive Summary

**VERDICT: REJECTED**

Wave C3 implementation is architecturally sound, follows established patterns correctly, has strong test coverage (23 new tests, all passing), and implements graceful degradation properly. However, **a CRITICAL configuration mismatch in the auth secret wiring makes the integration non-functional in local dev**, and **NO production path exists today** (Spring has no JWKS endpoint, Python rejects HS256 on JWKS path per `ALLOWED_ALGS`).

This is not a code defect — the code is correct. This is a **deployment/configuration gap** that must be explicitly documented and resolved before C3 can be considered shippable.

---

## 1. Contract Correctness: PASS ✅

### Request Shape Match
**VERIFIED CORRECT.** `BrandSafetyAiClient.classify()` sends:
```java
{
  "workspace_id": String,
  "items": [
    {
      "content_id": String,  // MediaMetric.mediaId
      "caption": String,      // MediaMetric.caption
      "media_type": String?,  // MediaMetric.mediaType
      "posted_at": String?    // ISO-8601 formatted from MediaMetric.postedAt
    }
  ]
}
```

Matches `influora-ai/app/routes/brand_safety.py` exactly:
- ✅ `workspace_id` in request body (line 261, required)
- ✅ `items` array with `content_id`/`caption`/`media_type?`/`posted_at?` (lines 108-183, validated)
- ✅ Field names match Python's snake_case (via `@JsonProperty` annotations in `BrandSafetyDtos.java`)

### GARM Response Mapping
**VERIFIED CORRECT — ALL 10 CATEGORIES PRESERVED.**

Python contract (`influora-ai/app/tools/schemas.py` lines 184-195):
```python
GARM_CATEGORIES: tuple[str, ...] = (
    "adult_explicit_sexual_content",
    "arms_ammunition",
    "crime_harmful_acts_to_individuals",
    "death_injury_military_conflict",
    "hate_speech_acts_of_aggression",
    "illegal_drugs_tobacco_alcohol",
    "obscenity_profanity",
    "spam_or_harmful_content",
    "terrorism",
    "debated_sensitive_social_issues",
)
```

Java mapping (`BrandSafetyScoreService.java` lines 169-176):
```java
// Stores entire ClassifiedItem list as JSON via objectMapper.writeValueAsString(classified)
// Each ClassifiedItem contains List<GarmFlag>, each GarmFlag has {category, risk, rationale}
// Python's _validate_model_result ensures ALL 10 categories are present before returning
```

**CRITICAL VERIFICATION:** Python validates ALL 10 categories are present (lines 226-230) BEFORE returning response:
```python
if flag_categories != _GARM_CATEGORY_SET:
    # Every category must be scored — a partial set could quietly
    # imply "no concern" for a category the model skipped.
    return None
```

This means Java's `garmFlagsJson` will ALWAYS contain all 10 GARM categories (or the entire call fails 502). **No silent category dropping possible.**

### Score Fields
- ✅ `brand_safety_score` → `CreatorScore.brandSafetyScore` (0-100, worst-item-driven)
- ✅ `garm_flags` → `CreatorScore.garmFlagsJson` (full classified array as JSON)
- ✅ `content_sentiment` / `sentiment_score` → `CreatorScore.contentSentiment` (worst-item's sentiment, -1.0 to 1.0 scaled to BigDecimal)

**NOTE:** Worst-item-drives-score logic (`BrandSafetyScoreService.java` lines 139-147) is correct — matches C2's own aggregate philosophy (lowest score = most severe finding).

---

## 2. Graceful Degradation: PASS ✅

**VERIFIED LOAD-BEARING.** `ScoreCalculationJob` integration (`ScoreCalculationJob.java` lines 204-216):

```java
brandSafetyScoreService
    .scoreCreator(creatorProfileId, recentMedia)
    .ifPresent(result -> builder
        .brandSafetyScore(result.brandSafetyScore())
        .garmFlagsJson(result.garmFlagsJson())
        .contentSentiment(result.contentSentiment()));
```

- ✅ `BrandSafetyScoreService.scoreCreator()` **never throws** — returns `Optional.empty()` on ANY failure (lines 74-117)
- ✅ Per-creator try/catch in `ScoreCalculationJob.calculateScores()` (lines 134-142) is a second safety net
- ✅ Test coverage proves both paths: `BrandSafetyScoreServiceTest`:
  - `testScoreCreatorAiClientExceptionReturnsEmpty()` — BrandSafetyAiException caught
  - `testScoreCreatorUnexpectedExceptionReturnsEmpty()` — RuntimeException caught
  - `ScoreCalculationJobTest` has 2 new cases verifying brand-safety failure isolation

**Failure blast radius:** One creator's brand-safety call failure leaves ONLY that creator's 3 brand-safety columns null. Does NOT abort scoring for other creators. ✅

---

## 3. Caption Logging Discipline: PASS ✅

**Kabir's C1 MED-1 condition: no caption logging/caching in Java code.**

Audited:
- ✅ `BrandSafetyAiClient.java` lines 105-127: logs ONLY `workspace_id`, `items.size()`, `statusCode`, `errorCode` — **never logs request body**
- ✅ `BrandSafetyScoreService.java` lines 99-103, 109-113: logs ONLY `creatorProfileId`, `items.size()`, error messages — **never logs caption text**
- ✅ No caching anywhere — `BrandSafetyAiClient` is stateless, every call is a fresh HTTP round-trip (lines 67-145)

Python side (`brand_safety.py` lines 283-288) uses `app.security.redaction.shape_of` for caption logging, matching rest of that codebase. ✅

---

## 4. Test Quality: PASS ✅

**23 new tests, all meaningful assertions.**

### `BrandSafetyAiClientTest.java` (9 tests)
- ✅ Happy path verifies response parsing + Bearer token header
- ✅ Non-200, transport failure, malformed response all raise `BrandSafetyAiException`
- ✅ Item-count mismatch rejected (defense-in-depth)
- ✅ Empty items / over-max-batch / missing workspace_id rejected BEFORE HTTP call (fail-fast)

### `BrandSafetyServiceTokenServiceTest.java` (4 tests)
- ✅ Claim shape (`iss`/`aud`/`workspace_id`/`scope=service`) verified
- ✅ TTL ceiling enforced (line 91, capped at 60s)
- ✅ Distinct `jti` per mint
- ✅ Workspace scoping correct

### `BrandSafetyScoreServiceTest.java` (8 tests)
- ✅ Empty/null media returns empty without touching repo/client
- ✅ No workspace connection returns empty without calling AI client
- ✅ AI-client exception and unexpected exception both degrade to empty (**load-bearing**)
- ✅ Worst-item-drives-score mapping correct
- ✅ `ContentItem` field mapping (`content_id`/`caption`/`media_type`/`posted_at`) verified
- ✅ All-null-scores returns empty

### `ScoreCalculationJobTest.java` (2 new cases)
- ✅ Brand-safety-unavailable leaves columns null but row still saves
- ✅ Unexpected brand-safety exception isolated to one creator, batch continues

**Not just "doesn't throw" — real assertions on values, behavior, and isolation guarantees.** ✅

---

## 5. AUTH WIRING: **CRITICAL FAILURE ❌**

### The Core Problem

**LOCAL DEV IS NON-FUNCTIONAL TODAY.** The two sides use DIFFERENT secret values in their default `.env.example` files:

#### Python side (`influora-ai/.env.example` line 33):
```bash
DEV_SHARED_JWT_SECRET=dev-meera-stream-secret-change-in-production-min-32-chars
```

#### Java side (`influora-api/.env.example` line 26):
```bash
BRAND_SAFETY_SERVICE_TOKEN_SECRET=change-me-another-random-string-at-least-32-chars
```

**These are NOT the same string.** 

When `BrandSafetyServiceTokenService` mints a token signed with `BRAND_SAFETY_SERVICE_TOKEN_SECRET`, Python's `verify_token` will attempt to verify it against `DEV_SHARED_JWT_SECRET` (when `SPRING_JWKS_URL` is unset, which is the local-dev default). **Signature verification will fail 100% of the time** because the signing key does NOT match the verification key.

**Result:** Every call to `/internal/brand-safety` will return `401 UNAUTHORIZED` with `code: "invalid_token"` or `"jwks_lookup_failed"`, and the entire C3 integration is dead in the water in local dev.

### Why This Wasn't Caught

The implementation is **architecturally correct** — Vikram correctly:
1. Created a new dedicated secret per the blast-radius discipline
2. Mirrored the `StreamTokenService` pattern exactly
3. Documented the alignment requirement in javadoc

But the `.env.example` files were never sync'd to use the SAME placeholder value, so anyone following the "copy `.env.example` to `.env`" setup instructions will have mismatched secrets.

### Production Path Analysis

**THERE IS NO WORKING PRODUCTION PATH TODAY.**

From `BrandSafetyServiceTokenService.java` javadoc (lines 23-26, 59-67):
> In production this is verified against Spring's JWKS (asymmetric RS256/ES256 — {@code ALLOWED_ALGS} in {@code service_token.py} explicitly rejects HS256 on that path); in local dev, when {@code SPRING_JWKS_URL} is unset, Python falls back to a single shared HS256 secret...

From `influora-ai/app/auth/service_token.py` lines 36, 148-151:
```python
ALLOWED_ALGS = ("RS256", "ES256")  # asymmetric only; never accept HS256 from JWKS path

alg = unverified_header.get("alg")
if alg not in ALLOWED_ALGS and not (
    alg == "HS256" and isinstance(source, StaticDevJwksSource)
):
    raise AuthError(status.HTTP_401_UNAUTHORIZED, "invalid_alg", f"algorithm not allowed: {alg}")
```

**What this means:**
- `BrandSafetyServiceTokenService` always signs with **HS256** (line 109: `Keys.hmacShaKeyFor`)
- When `SPRING_JWKS_URL` is set (prod), Python uses `HttpJwksSource` and rejects HS256 (line 36: `ALLOWED_ALGS = ("RS256", "ES256")`)
- Spring has **no JWKS endpoint or asymmetric keypair** anywhere in this codebase (confirmed by Vikram's handoff, no `KeyPair`/`RSAKey`/`.well-known` artifact exists)

**Therefore:**
- **Local dev:** Can work IF secrets are aligned (HS256 dev-fallback path)
- **Prod:** Will NOT work because Spring can't sign RS256/ES256 tokens and Python rejects HS256 on the JWKS path

This is a **pre-existing gap in this codebase's auth design**, NOT newly introduced by C3. Vikram's handoff explicitly calls this out:
> Confirmed pre-existing gap, not introduced here: Spring has no JWKS endpoint or asymmetric keypair anywhere in this codebase (grepped — none), so in prod this token can only verify via influora-ai's HS256 dev-fallback (`DEV_SHARED_JWT_SECRET`) — same condition the existing Meera stream token is already under

**Kabir's C2 review already flagged the HS256 dev-fallback as LOW-2, accepted/non-gating.**

But this means:
1. **C3 cannot function in prod today** without either:
   - Building a Spring JWKS endpoint + asymmetric signing (out of scope for C3), OR
   - Relaxing Python's `ALLOWED_ALGS` to include HS256 on the JWKS path (security downgrade, needs Kabir sign-off)

2. **C3 cannot function in local dev today** without aligning the two `.env.example` placeholder values

---

## 6. BLOCKER RESOLUTION (Final Re-QA 2026-07-07)

### BLOCKER 1: Local Dev Secret Alignment — ✅ RESOLVED
**Verified independently by Kavya:**
- `influora-api/.env.example` line 28: `BRAND_SAFETY_SERVICE_TOKEN_SECRET=dev-meera-stream-secret-change-in-production-min-32-chars`
- `influora-ai/.env.example` line 42: `DEV_SHARED_JWT_SECRET=dev-meera-stream-secret-change-in-production-min-32-chars`
- **Both values now match BYTE-FOR-BYTE.** Local dev auth path is functional.
- Cross-reference comments present in both `.env.example` files AND in `BrandSafetyServiceTokenProperties` javadoc (lines 20-33).

### BLOCKER 2: Production Gap Documentation — ✅ RESOLVED
**Resolved via CTO ADR:** `wiki/decisions/2026-07-07-spring-python-service-auth-jwks-gap.md`
- Production gap is formally documented as a known, explicit launch blocker (not a C3 defect).
- Decision: Accept as staging-only until Wave E task E-JWKS delivers asymmetric signing.
- Does NOT block C3 from merging or Wave C/D work from continuing.

### BLOCKER 3: Kabir Strategy Sign-Off — ✅ RESOLVED
**Resolved via CTO ADR section "Decision: (a)" with hard condition:**
- Option (a) selected: C3 ships staging-only, tracked as hard E7 launch blocker (task E-JWKS).
- Kabir consulted before lock: "The state is fail-closed, not fail-open... I will not block Wave C/D on it."
- Hard condition binding: "The eventual fix MUST be asymmetric (RS256/ES256 via JWKS or mTLS). ALLOWED_ALGS MUST NOT be relaxed to accept HS256."
- C3 is CLEARED to proceed to Kabir's normal load-bearing C3 security review (separate from this auth-strategy ruling).

### Test Suite Re-Verification — ✅ CONFIRMED
**Independent verification by Kavya (final re-QA):**
- Parsed all 42 surefire XML reports in `influora-api/target/surefire-reports/`
- **Total tests: 386 | Errors: 0 | Failures: 0 | Skipped: 0**
- **Status: ALL PASSED ✅**
- Note: Could not run `mvn test` directly (Maven not in environment PATH, matching Vikram's reported constraint), but verified via existing test artifacts from most recent build.

---

## 7. Other Findings (Non-Blocking)

### MEDIUM: No Caption Logging Test
**WHAT:** Tests verify caption is SENT correctly (`BrandSafetyScoreServiceTest` line 194), but no test verifies caption is NEVER logged.

**MITIGATION:** Manual code audit (section 3 above) confirms no logging. Add a test that asserts log output does NOT contain caption text on failure paths for defense-in-depth.

### LOW: Workspace Resolution Could Be More Explicit
**WHAT:** `BrandSafetyScoreService` uses `MetaOAuthTokenRepository.findFirstByCreatorProfileIdAndRevokedFalse` (line 81) to get ANY active workspace connection for a creator. A creator can be connected under multiple workspaces (V20 unique key is `(workspace_id, creator_profile_id)`), so this picks an arbitrary one.

**IMPACT:** None — the workspace_id is only used to satisfy Python's token validation; it doesn't affect the classification result itself (captions are platform-global facts).

**RECOMMENDATION:** Add javadoc on that finder explaining why "any one workspace" is safe here.

---

## Summary (Final Re-QA 2026-07-07)

| Gate | Initial Status (2026-07-07) | Final Status (Re-QA) | Notes |
|------|---------------------------|---------------------|-------|
| Contract correctness | ✅ PASS | ✅ PASS | All 10 GARM categories preserved, request/response shapes match exactly |
| Graceful degradation | ✅ PASS | ✅ PASS | Never crashes job, per-creator isolation verified in tests |
| Caption logging discipline | ✅ PASS | ✅ PASS | No raw captions in Java logs or cache |
| Test quality | ✅ PASS | ✅ PASS | 23 tests, all load-bearing assertions; 386/386 total tests passing |
| **Auth wiring** | ❌ **CRITICAL FAIL** | ✅ **RESOLVED** | Local dev now functional (secrets aligned); prod gap documented and accepted via CTO ADR |

---

## Next Steps

**BLOCKER RESOLUTION COMPLETE.** Wave C task C3 is **APPROVED** by Kavya (QA Lead) and CLEARED to proceed to:

1. **Kabir's normal load-bearing C3 security review** (first live wiring of C1 caption storage + C2 endpoint into scoring; workspace isolation audit; distinct from the auth-strategy ruling already completed).
2. **Meera's local verification** after Kabir's approval (build check, dev server run, curl test of happy path).

Per `wiki/decisions/2026-07-07-spring-python-service-auth-jwks-gap.md` directive to Arjun:
- C3 does NOT need Spring JWKS or prod auth path to merge.
- Ships staging-only by design (fail-closed in prod is the accepted posture).
- Task E-JWKS tracked as E7 launch blocker in `REMAINING_WORK_PLAN.md` Wave E.

---

## Final QA Verdict

✅ **APPROVED**

All 3 original blockers are now closed:
1. BLOCKER 1 (dev secret mismatch): Secrets aligned in both `.env.example` files, verified byte-for-byte.
2. BLOCKER 2 (no prod auth path): Documented and accepted via CTO ADR as staging-only posture until Wave E task E-JWKS.
3. BLOCKER 3 (Kabir strategy sign-off): Option (a) selected with hard condition, C3 cleared to proceed.

Test suite: 386/386 passing (verified from surefire reports, Maven not runnable in QA environment).

Code quality: Contract correctness, graceful degradation, caption logging discipline, and test coverage all meet standards.

**Route to:** Kabir (Red-Team Lead) for normal C3 load-bearing security review (workspace isolation, caption-storage wiring audit).

---

**Signed:** Kavya Reddy, QA Lead  
**Initial Review:** 2026-07-07  
**Final Re-QA:** 2026-07-07  
**Reference:** `SHARED_CONTEXT.md` Wave C task C3 handoff from Vikram; `wiki/decisions/2026-07-07-spring-python-service-auth-jwks-gap.md`
