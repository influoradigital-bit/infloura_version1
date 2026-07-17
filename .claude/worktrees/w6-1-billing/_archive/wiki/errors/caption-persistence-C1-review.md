# QA Review: Wave C Task C1 (Caption Column + Persistence)
Date: 2026-07-07
Reviewer: Kavya
Status: **REJECTED** (1 MEDIUM issue blocking — DTO coverage gap)

---

## 1. ADR Compliance (PASS)

**Verified:** Implementation matches `wiki/decisions/2026-07-06-brand-safety-caption-storage.md` precisely.

- **Decision: "store raw, redact/exclude on read"** — CORRECT. `MetricsPollingJob.java:307` stores `mediaItem.caption()` directly (no redaction on write). The ADR's no-brand-surfacing rule is enforced at the DTO/serialization boundary (MediaMetric.java:48-52 javadoc, builder javadoc:232-235), not by mutating stored text.
- **Retention:** follows media_metrics lifecycle (no separate retention job introduced). Migration V26 comment restates this. CORRECT per ADR line 13-14.
- **Logging discipline:** no log statements found that expose raw caption text (grep confirmed zero matches for `log.*caption` patterns in service/job layers). CORRECT per ADR binding constraint line 48-49.
- **Internal-only constraint:** javadoc on entity field, getter, and builder all state "never surface in brand-facing DTO/response, keep out of logs." CORRECT per ADR line 46-48.

**ADR compliance: 10/10** — zero divergence.

---

## 2. No-Brand-Leak Verification (REJECTED — DTO coverage gap)

### 2.1 Current State: No Leak (VERIFIED)

Independently verified caption cannot leak today:
- Only brand-facing DTO class that reads `MediaMetric` is `AnalyticsDtos.java` (confirmed by grep — only file in `web/dto/**` importing MediaMetric).
- `AnalyticsDtos` exposes **zero per-post/per-media records** — only aggregates: `CreatorMetricsResponse` (line 85), `CreatorScoresResponse` (line 102), `CreatorDemographicsResponse` (line 128). Structurally impossible for caption to appear.
- `MediaMetric.getCaption()` has **zero callers** outside `MediaMetric.java` itself (grep found only the entity definition + test files, no service/controller usage).
- Scoring services (`QualityScoreService`, `FakeFollowerDetectionService`, `ScoreCalculationJob`) all import `MediaMetric` but none call `getCaption()` (grep confirmed zero matches).

**Current leak risk: ZERO.**

### 2.2 Reflection Test Coverage Gap (MEDIUM — BLOCKING)

**Issue:** `NoBrandFacingCaptionExposureTest.java` is missing **`EmailOtpDtos.class`** from the `DTO_CONTAINER_CLASSES` array (line 53-67).

**Evidence:**
- Filesystem lists **14 DTO container classes** under `web/dto/`:
  ```
  AnalyticsDtos, AuthDtos, EmailOtpDtos, CampaignDtos, CreatorDtos, 
  MeeraDtos, MeeraToolDtos, MetaDtos, MoneyDtos, NotificationDtos, 
  OnboardingDtos, TrackingDtos, WebhookDtos, UserDtos
  ```
- Test array contains **only 13** (line 53-67) — `EmailOtpDtos` is absent.

**Impact:**
- If a future developer adds a `caption` field to any nested record inside `EmailOtpDtos`, the reflection test will NOT catch it (test never scans that class tree).
- This is exactly the "silent narrowing" failure mode the test's javadoc warns against (line 49-51): "omission would silently narrow this guardrail's coverage rather than fail loudly."
- Today `EmailOtpDtos` has zero caption fields (verified — only 4 simple OTP records, no caption/mediaCaption/text/content), so no **active leak** exists. But the guardrail is incomplete.

**Severity:** MEDIUM (not HIGH) because:
- EmailOtpDtos is auth-domain, structurally unrelated to media/captions (very low probability a caption field would ever be added there).
- No active leak exists today.
- But: the test's stated purpose is **durable structural coverage**, and it is not durable if it silently omits classes.

**Required fix:** Add `EmailOtpDtos.class` to the array at line 53-67.

### 2.3 Reflection Test Edge-Case Coverage (ADVISORY — non-blocking)

The reflection test checks for fields/record-components named `caption` / `mediaCaption` / `*caption` (endsWith check, line 118). This **does NOT catch**:
1. A field named `text` / `content` / `postText` that actually holds caption data (different name, same data).
2. A getter method named `getCaptionText()` that returns `MediaMetric.caption` without a field named `caption`.
3. Nested serialization via Jackson `@JsonUnwrapped` / `@JsonValue` where caption data leaks indirectly through an embedded object.

**Impact:** The test is **name-based**, not **data-flow-based**. It proves "no field literally named caption exists in the DTO tree" but not "caption data cannot reach JSON serialization." A developer could bypass it with a differently-named field.

**Mitigation:** Today this is acceptable because:
- Zero callers of `MediaMetric.getCaption()` exist (data-flow analysis confirmed no read path).
- AnalyticsDtos exposes zero per-media DTOs (structural gap — even if caption were read, no DTO exists to carry it).
- The test is load-bearing for the **most likely leak vector** (direct field mapping in a future per-post DTO).

**Recommendation (non-blocking):** When C4 adds `BrandSafetyBadge` (the first per-media DTO), manually verify at code-review time that the new DTO does not map caption under **any field name**, not just `caption`.

---

## 3. Persistence Correctness (PASS)

### 3.1 Caption Source (VERIFIED)

**Claim:** caption is from the already-fetched `getMedia` response, zero extra API call.

**Verified:**
- `InstagramInsightsClient.MEDIA_FIELDS` (line 18-19): `"id,caption,media_type,media_url,permalink,timestamp,like_count,comments_count"` — caption IS requested in the existing field list.
- `MetricsPollingJob.pollRecentMedia` (line 245): calls `instagramClient.getMedia(...)` once.
- `MetricsPollingJob.pollOneMedia` (line 307): reads `mediaItem.caption()` from the `InstagramMediaResponse.MediaItem` record already returned by the above call.
- `InstagramMediaResponse.MediaItem` record (line 12-20): has `String caption` field, populated by Jackson from the API response.
- Test `testMediaMetricsPersistsCaptionFromPayloadWithNoExtraApiCall` (MetricsPollingJobTest.java:430-431): asserts `getMedia` called exactly once, `getMediaInsights` called exactly once (for engagement metrics, not caption).

**Conclusion:** Zero extra Meta API call. Claim verified.

### 3.2 Null-Safety (VERIFIED)

- V26 column is `NULL` (line 24).
- Entity field is nullable: `@Column(name = "caption", columnDefinition = "TEXT")` with no `nullable = false` (MediaMetric.java:54).
- Builder accepts `null`: `builder.caption(String caption)` (line 237) — no null-guard, stores as-is.
- Test `testMediaMetricsNullCaptionPersistsAsNull` (MetricsPollingJobTest.java:435-453): passes `null` caption, verifies `getCaption()` returns `null`, no NPE.

**Conclusion:** Null-safe end-to-end.

### 3.3 UTF-8/Emoji Safety (VERIFIED)

- V26 column: `TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci` (line 24-25).
- V21 table default (line 64): `DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci` — column charset matches table default (redundant but explicit, acceptable).
- Entity: `@Column(name = "caption", columnDefinition = "TEXT")` — JPA uses the DB column's charset, no Java-side encoding issue.
- Test `testMediaMetricCaptionMapping` (MediaMetricsRepositoryTest.java:73-88): round-trips `"Loving this collab! 🔥💯 #ad café vibes"` (4-byte emoji + Latin extended) and asserts exact equality.
- Test `testMediaMetricsPersistsCaptionFromPayloadWithNoExtraApiCall` (MetricsPollingJobTest.java:411): `"New drop 🔥🎉 #sponsored café collab"` (4-byte emoji).

**Conclusion:** utf8mb4 emoji-safe end-to-end.

---

## 4. Migration Correctness (PASS)

### 4.1 Version Collision (VERIFIED)

- Latest migration before V26: `V25__audience_demographics.sql` (confirmed by listing migration dir, sorted).
- V19 is `deliverable_metrics` (untracked per git status, unrelated table, no collision).
- V26 is next in sequence. **No collision.**

### 4.2 Schema Consistency: V26 vs Entity (PASS with 1 ADVISORY)

| Aspect | V26 Migration | MediaMetric.java Entity | Match? |
|--------|---------------|------------------------|--------|
| Type | `TEXT` | `columnDefinition = "TEXT"` | ✅ |
| Nullable | `NULL` (explicit) | No `nullable = false` | ✅ |
| Charset | `CHARACTER SET utf8mb4` | (inherits from columnDefinition) | ✅ |
| Collation | `COLLATE utf8mb4_unicode_ci` | (inherits from columnDefinition) | ✅ |
| Position | `AFTER media_type` | Field declared after `mediaType` (line 40 vs 54) | ✅ |
| Default | (none) | (none) | ✅ |

**ADVISORY (non-blocking):** V26's `CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci` is redundant (V21 line 64 sets table default). MySQL will use the explicit column charset if specified, so this is **correct but verbose**. Acceptable — explicit is safer than implicit when dealing with 4-byte emoji.

### 4.3 Column Placement (VERIFIED)

V26 line 26: `AFTER media_type` — matches entity field order (media_type at line 40, caption at line 54, both before permalink at line 57). Consistent.

---

## 5. Test Quality (PASS)

### 5.1 New Tests Summary

| Test | File | Purpose | Result |
|------|------|---------|--------|
| `testMediaMetricCaptionMapping` | MediaMetricsRepositoryTest.java:69-94 | Builder maps caption (emoji-safe), null caption accepted | Builder round-trip PASS |
| `testMediaMetricsPersistsCaptionFromPayloadWithNoExtraApiCall` | MetricsPollingJobTest.java:406-432 | Caption persisted from getMedia response, zero extra call | API-cost claim VERIFIED |
| `testMediaMetricsNullCaptionPersistsAsNull` | MetricsPollingJobTest.java:435-453 | Null caption no NPE | Null-safety VERIFIED |
| `testNoDtoExposesCaptionField` | NoBrandFacingCaptionExposureTest.java:73-85 | Reflection guard — no caption-named field in DTO tree | Structural guardrail (with coverage gap — see §2.2) |

**+** Existing test `testMediaMetricsMapsVideoViewsOnlyForVideoLikeTypes` (line 456+) now includes caption in the test data (line 462: `"caption"`), extending coverage to the VIDEO code path.

### 5.2 Proof Properties (VERIFIED)

✅ **Caption IS persisted:** `testMediaMetricsPersistsCaptionFromPayloadWithNoExtraApiCall` asserts `captor.getValue().getCaption()` equals the input unicode string (line 425).

✅ **Zero extra API call:** same test verifies `getMedia` called **times(1)**, `getMediaInsights` called **times(1)** (line 430-431). If caption required a separate fetch, call count would be 2 for getMedia or a new method would be called.

✅ **Null caption accepted:** `testMediaMetricsNullCaptionPersistsAsNull` asserts `getCaption()` returns `null` when `mediaItem.caption()` was `null` (line 452).

✅ **Reflection guard fails when caption is added:** The test's implementation (line 88-92 recursive scan + line 95-109 field/record-component check) **will fail** if any field named `caption`/`mediaCaption`/`*caption` is added to a scanned class. No "negative case" test exists (e.g. a dummy DTO with caption field to prove the test catches it), but the implementation is **assert-by-construction** — `assertTrue(offenders.isEmpty())` at line 80-84 will fail with a named-offender message.

**Test quality: 9/10.** One point deducted for the DTO coverage gap (EmailOtpDtos missing from reflection test array).

---

## Summary

| Check | Status | Notes |
|-------|--------|-------|
| ADR compliance | ✅ PASS | Zero divergence — store raw, exclude on read |
| No caption leak (current) | ✅ PASS | Structurally impossible today (no per-media DTO exists) |
| Reflection test coverage | ❌ **REJECTED** | EmailOtpDtos missing from DTO_CONTAINER_CLASSES array (MEDIUM) |
| Reflection test edge-cases | ⚠️ ADVISORY | Name-based, not data-flow-based (acceptable today, revisit at C4) |
| Caption source | ✅ PASS | Zero extra Meta call verified |
| Null-safety | ✅ PASS | End-to-end verified |
| UTF-8/emoji | ✅ PASS | utf8mb4 round-trip verified |
| Migration version | ✅ PASS | V26 correct, no collision |
| Schema consistency | ✅ PASS | V26 ↔ entity mapping correct |
| Test quality | ✅ PASS | 4 new tests prove caption persistence + structural guardrail |

---

## Verdict: REJECTED

**Blocking issue:** Reflection test missing `EmailOtpDtos.class` in the `DTO_CONTAINER_CLASSES` array.

**Required fix (1 line):**
```java
// NoBrandFacingCaptionExposureTest.java line 53-67
private static final Class<?>[] DTO_CONTAINER_CLASSES = {
    AnalyticsDtos.class,
    AuthDtos.class,
    EmailOtpDtos.class,  // ← ADD THIS
    CampaignDtos.class,
    CreatorDtos.class,
    // ... rest unchanged
};
```

**Why this blocks:** The test's stated purpose is **durable structural coverage** (javadoc line 34-43). Omitting a DTO class silently narrows coverage, exactly the failure mode it was designed to prevent. One-line fix; re-submit after fix + re-run suite.

**Non-blocking advisories for follow-up:**
1. At C4 (BrandSafetyBadge — first per-media DTO), manually verify the new DTO does not map caption under **any field name**, not just `caption` (reflection test is name-based).
2. V26's explicit `CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci` is redundant (table default already utf8mb4) but harmless — consider omitting in future migrations for brevity.

**Re-QA after fix:** Run `mvn -o -f influora-api test` to confirm suite stays green (363/363), then route back to Kavya for re-review (should be instant approval if only the one line is added).

---

## Files Reviewed

- `influora-api/src/main/resources/db/migration/V26__media_metrics_caption.sql`
- `influora-api/src/main/java/com/influora/domain/entity/MediaMetric.java`
- `influora-api/src/main/java/com/influora/job/MetricsPollingJob.java`
- `influora-api/src/main/java/com/influora/integration/meta/dto/InstagramMediaResponse.java`
- `influora-api/src/main/java/com/influora/integration/meta/client/InstagramInsightsClient.java`
- `influora-api/src/main/java/com/influora/web/dto/analytics/AnalyticsDtos.java`
- `influora-api/src/test/java/com/influora/web/dto/NoBrandFacingCaptionExposureTest.java`
- `influora-api/src/test/java/com/influora/job/MetricsPollingJobTest.java`
- `influora-api/src/test/java/com/influora\repository\MediaMetricsRepositoryTest.java`
- `influora-api/src/main/resources/db/migration/V21__creator_metrics.sql` (baseline for V26 consistency check)
- `wiki/decisions/2026-07-06-brand-safety-caption-storage.md` (governing ADR)

---

**Next Steps:**
Route back to Vikram via SHARED_CONTEXT.md — add `EmailOtpDtos.class` to the reflection test array, re-run suite, re-submit. After fix: instant re-approval → Kabir PII/retention review (load-bearing) → Meera live-MySQL check (V26 migration execution).
