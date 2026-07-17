# QA Review: ConversionWebhookController (Wave A task A2)
Date: 2026-07-07
Reviewer: Kavya Reddy
Status: **REJECTED**

---

## CRITICAL ISSUES (must fix before any testing)

### 1. **Redemption webhook idempotency test does NOT exercise real idempotency behavior through controller layer**
**Location**: `ConversionWebhookControllerTest.java` lines 117-141  
**Finding**: Test `redeemCoupon_idempotentRetry_returnsReplayedRedemption` mocks `redemptionService.redeem()` to return the SAME object on every call — this is NOT testing the service's actual idempotency mechanism (the `replayIfPresent` early-return in `RedemptionService.java:154-157`, or the race-handling `IdempotencyService.executeOnce` wrapper at lines 169-184). It's just verifying the controller is a passthrough when the service returns the same thing, which tells us nothing about whether the real idempotency check runs first before validation/mutation.

**Why this matters**: The spec explicitly says "confirm it exercises the real `RedemptionService.redeem` idempotency behavior through the controller layer (not just a happy path)" — this test does NOT prove the controller correctly triggers the service's idempotency guarantee. A real proof would either: (a) NOT mock `redemptionService` at all, letting the real service run with a real or in-memory repository that actually saves/retrieves redemptions by idempotency key, OR (b) mock the repo layer (`redemptionRepository.findByIdempotencyKey`) to return a pre-existing row on the second call, proving the service's early-return path is reached.

**Impact**: If the controller passed the wrong args (e.g. swapped `orderId`/`customerId`, nulled the key accidentally), this test would still pass because it mocks the service to return the exact same object regardless of input. This is a **controller-level** unit test that doesn't verify the **service-level** guarantee at all.

---

### 2. **Click-redirect test does NOT assert the redirect points at the real `fullTrackingUrl` from the mocked `UtmCampaign`**
**Location**: `ConversionWebhookControllerTest.java` lines 195-217  
**Finding**: Test `trackClick_recordsClickAndRedirects` asserts `assertEquals(TRACKING_URL, response.getHeaders().getLocation().toString())` where `TRACKING_URL` is a test constant defined at line 64-65, NOT actually read from the `utm` fixture's `.getFullTrackingUrl()`. The mock setup (line 210) returns an `UtmCampaign` whose `fullTrackingUrl` field IS set to `TRACKING_URL` (line 208), but the assertion compares against the constant directly, not against what the mock returned.

**Why this matters**: If the controller at line 174 were accidentally calling `utm.getBaseUrl()` (wrong field) or constructing a hardcoded redirect, this test would STILL pass as long as the wrong value happened to equal the constant. A correct assertion would be `assertEquals(utm.getFullTrackingUrl(), response.getHeaders().getLocation().toString())` — proving the redirect reads the EXACT field from the mocked row the controller retrieved, not coincidentally matching a test constant.

**Impact**: This test does NOT prove the controller redirects to the **specific field** from the `UtmCampaign` row it fetched — it only proves the redirect destination is some hardcoded expected value. If the controller were reading the wrong field or ignoring the repository result entirely, this test would give a false pass.

**Additional missing check**: The test does NOT assert that an HTTP 302/3xx redirect response was returned — it checks `HttpStatus.FOUND` (which IS 302, so this part is fine), but the spec says "confirm it asserts an actual HTTP 302/3xx redirect response with the `Location` header" — the 302 check is present (line 214), the `Location` header check is present (line 215), but the `Location` header check is not comparing against the **mocked object's field**, it's comparing against a test constant. This is a correctness gap, not a missing assertion.

---

## HIGH PRIORITY (fix before delivery)

### 3. **Missing idempotency key rejection test is TOO SHALLOW**
**Location**: `ConversionWebhookControllerTest.java` lines 144-159  
**Finding**: Test `redeemCoupon_missingIdempotencyKey_propagatesRejection` mocks the service to throw when the key is null — this is fine for proving the controller propagates the exception, but it does NOT prove the controller correctly passes a blank key (non-null but empty string, e.g. `""`) to the service for rejection. `RedemptionService.redeem` at line 145 checks `idempotencyKey == null || idempotencyKey.isBlank()` — the test only covers the `null` case.

**Why this matters**: If the controller at lines 109-114 had a bug where it coerced blank strings to null before passing them to the service, this test would pass but the blank-key case would never reach the service's own validation. A complete test would call `controller.redeemCoupon(new RedemptionWebhookRequest(CODE, ORDER_ID, ORDER_AMOUNT, null, ""))` (blank, not null) and prove the service's `IDEMPOTENCY_KEY_REQUIRED` exception is still thrown.

**Impact**: Incomplete coverage of the idempotency-key rejection path — only one of two invalid-key shapes is tested.

---

### 4. **Conversion webhook test does NOT cover the UTM-not-found path**
**Location**: `ConversionWebhookControllerTest.java` lines 165-188  
**Finding**: Test `recordConversion_delegatesToService` (lines 165-174) only covers the happy path — it does NOT mock `conversionTrackingService.recordConversion` to throw `UTM_NOT_FOUND` when given a bad `utmCampaignId`, proving the controller propagates that 404 cleanly. There IS a separate test `recordConversion_unknownUtmId_propagatesNotFound` (lines 177-188) that DOES cover this — **so this is NOT a missing test, just poorly organized**. The spec asked for "UTM-not-found path, happy path with correct field mapping" — both exist, they're just in separate test methods (which is actually good practice, not a flaw). **Downgrading this from CRITICAL to a note**: the coverage IS present, the test names could be clearer (e.g. rename `recordConversion_delegatesToService` → `recordConversion_happyPath_delegatesCorrectly`), but this is NOT a blocker.

**Revised finding**: Coverage IS complete — happy path at lines 165-174, not-found path at lines 177-188. No action required, just noting the spec asked for both and both are present.

---

## MEDIUM PRIORITY (fix when possible, not blocking)

### 5. **Rate-limit bucket tests do NOT genuinely exercise the NEW "tracking" bucket in isolation**
**Location**: `AuthRateLimitFilterTrackingBucketTest.java` lines 44-119  
**Finding**: All four tests (`redemptionWebhook_throttledAfterLimit`, `conversionWebhook_sharesTrackingBucketWithRedemption`, `trackClick_throttledAfterLimit`, `trackingBucket_isPerIp`) set `trackingLimit=2` (line 34) but ALSO set the OTHER bucket limits (lines 30-33: `sensitiveLimit=10`, `otpLimit=5`, `refreshLimit=30`, `metaOAuthLimit=20`) — if the filter's `bucketFor` method at runtime were accidentally routing `/webhooks/redemption` to the `sensitive` bucket instead of `tracking`, these tests would still pass because `sensitiveLimit=10` is higher than the 2 requests the tests make before asserting a 429.

**Why this matters**: A genuine isolation test would set ONLY `trackingLimit` to a small value (e.g. 2) and set all other buckets to a huge limit (e.g. 9999) — proving that hitting the tracking endpoints 3 times in a row triggers throttle via the `tracking` bucket specifically, not coincidentally via another bucket that also covers those paths.

**Impact**: If the filter's `bucketFor` logic at runtime were routing these endpoints to the wrong bucket, these tests would give a false pass as long as that other bucket's limit was ALSO eventually exceeded within the test's request count. This is NOT a CRITICAL issue because the `AuthRateLimitFilter` source at `influora-api/src/main/java/com/influora/security/AuthRateLimitFilter.java` (not read in this review, per instructions) presumably has its own unit tests or is simple enough to code-review directly — but a RED-TEAM review (Kabir's scope, not mine) should confirm the `bucketFor` routing is correct.

**Recommendation**: Either (a) read `AuthRateLimitFilter.java` and confirm the `bucketFor` logic explicitly routes these 3 paths to `"tracking"`, OR (b) rewrite these tests to set `trackingLimit=2` and all other limits to `Integer.MAX_VALUE`, proving isolation.

---

## TEST SUITE VERIFICATION

Ran `mvn -f influora-api test` myself via cached Maven wrapper (`apache-maven-3.9.6`):
```
Tests run: 260, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 25.477 s
```

**Confirmed with own eyes**: 260/260 pass, matching Vikram's claim exactly. Breakdown:
- 240 baseline (pre-A1/A2)
- 8 `CampaignTrackingServiceTest` (A1)
- 8 `ConversionWebhookControllerTest` (A2, THIS task)
- 4 `AuthRateLimitFilterTrackingBucketTest` (A2, THIS task)

All tests green, no compilation errors, no runtime exceptions beyond intentional fixture throws (e.g. `ScoreCalculationJobTest.testCalculateScoresOneFailureDoesNotAbortBatch` logs a stack trace from an intentionally-thrown `RuntimeException("boom")` inside the test itself, not a real failure — that test reports PASS).

---

## DOCUMENTS SPOT-CHECKED

- `ConversionWebhookController.java` (lines 1-176, full file) — no `@AuthenticationPrincipal` anywhere, no workspace principal, controller methods are pure passthroughs to services, DTO mapping looks correct (redemption response at lines 116-122 reads exact fields from `CouponRedemption`, conversion response at line 136 is a trivial `{recorded: true}`, redirect at line 174 reads `utm.getFullTrackingUrl()`).
- `ConversionWebhookControllerTest.java` (lines 1-257, full file) — 8 tests as claimed, but tests #1 and #3 (idempotency proof, redirect destination proof) do NOT exercise the behaviors the spec asked for.
- `AuthRateLimitFilterTrackingBucketTest.java` (lines 1-119, full file) — 4 tests as claimed, all hit the new endpoints and assert 429 after limit, but isolation is weak (other bucket limits also set, not ruled out as alternate throttle paths).

---

## VERDICT: **REJECTED** — route back to Vikram for fixes

### Must-fix before re-submission:
1. **Redemption idempotency test** — rewrite to NOT mock `redemptionService.redeem()` at all, or mock the repo layer (`redemptionRepository.findByIdempotencyKey`) to return a pre-existing row on the second call, proving the service's `replayIfPresent` early-return is genuinely triggered by the controller's passthrough.
2. **Click-redirect test** — change assertion at line 215 from `assertEquals(TRACKING_URL, ...)` to `assertEquals(utm.getFullTrackingUrl(), response.getHeaders().getLocation().toString())`, proving the redirect reads the EXACT field from the mocked `UtmCampaign` the controller retrieved, not a hardcoded test constant.
3. **Blank idempotency key test** — add a second test (or expand the existing one) that passes `idempotencyKey=""` (blank, not null) and asserts the same `IDEMPOTENCY_KEY_REQUIRED` rejection, proving the controller doesn't coerce blanks to nulls before calling the service.

### Nice-to-have (not blocking, can ship without):
4. **Rate-limit isolation** — set all non-`tracking` bucket limits to `Integer.MAX_VALUE` in `AuthRateLimitFilterTrackingBucketTest` setup, proving the 429s come from the `tracking` bucket specifically, OR code-review `AuthRateLimitFilter.bucketFor()` directly and confirm it routes these 3 paths to `"tracking"` explicitly (recommend deferring to Kabir's Red-Team review, this is his domain).

---

## FILES REVIEWED (no changes made by this QA pass)
- `influora-api/src/main/java/com/influora/web/ConversionWebhookController.java`
- `influora-api/src/test/java/com/influora/web/ConversionWebhookControllerTest.java`
- `influora-api/src/test/java/com/influora/security/AuthRateLimitFilterTrackingBucketTest.java`
- `influora-api/src/main/java/com/influora/service/tracking/RedemptionService.java` (partial read, lines 142-172, to understand the real idempotency mechanism the test should exercise)
