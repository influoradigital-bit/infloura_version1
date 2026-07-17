# QA Review: Wave B Task B1 - Media Metrics Polling
Date: 2026-07-07
Reviewer: Kavya Reddy (QA Lead)
Status: **APPROVED WITH ONE ADVISORY NOTE**

---

## Executive Summary

**VERDICT: APPROVED** for Kabir security review and integration into main branch.

Vikram's implementation of per-post media metrics polling (`MetricsPollingJob` + 7 new tests) meets all acceptance criteria for Wave B task B1. The code is production-ready with robust error handling, graceful degradation, and comprehensive test coverage.

**Quality score: 9/10**

All CRITICAL and HIGH priority checks pass. One ADVISORY note on schema semantics (duplicate row handling) — clarified as "design-by-intent" per entity/migration review, not a defect.

---

## Files Reviewed

- `influora-api/src/main/java/com/influora/job/MetricsPollingJob.java` (+203/-18 lines)
- `influora-api/src/test/java/com/influora/job/MetricsPollingJobTest.java` (+234 lines, 7 new tests)
- `influora-api/src/main/java/com/influora/domain/entity/MediaMetric.java` (entity verification)
- `influora-api/src/main/resources/db/migration/V21__creator_metrics.sql` (schema verification)
- `influora-api/src/main/java/com/influora/repository/MediaMetricsRepository.java` (repository verification)
- `influora-api/pom.xml` (dependency verification)

---

## CRITICAL Checks (must pass before any testing)

### ✅ PASS: No API keys or sensitive data hardcoded
- All Meta API calls use constructor-injected `InstagramInsightsClient`
- Access tokens passed as method parameters, never hardcoded
- `creatorProfileId` properly scoped via existing token resolution pattern

### ✅ PASS: No cross-workspace data leakage
**File:** `MetricsPollingJob.java` line 92, 250-262
- Uses identical `creatorProfileId` pattern as existing profile-level polling (line 92: `pollOneMedia(creatorProfileId, token.get())`)
- No new workspace column introduced in `MediaMetric` entity
- `MediaMetricsRepository` has same workspace-scoping discipline as `CreatorMetricsRepository` (javadoc confirmed, lines 16-23)
- Per acceptance criteria: "Kabir confirms no cross-workspace leak in the batch" — forwarding to Kabir for independent verification, but pattern matches proven-safe profile polling

### ✅ PASS: Input validation and null handling
**File:** `MetricsPollingJob.java` lines 270-285, 329-355, 357-375
- Line 270: `mediaType` defaults to "IMAGE" if null from Meta response
- Line 291-309: Null checks on `insights` and `insights.data()` before mapping
- Line 357-363: `firstLongValue()` handles null metric values gracefully
- Line 367-375: `parseTimestamp()` never throws into caller (try-catch with null return)
- Line 306: Empty media list short-circuits (no crash on null/empty `mediaResponse.data()`)

### ✅ PASS: Error boundaries and exception handling
**File:** `MetricsPollingJob.java` lines 242-258, 291-309
- Line 242-258: Per-media-item try-catch prevents one bad item from aborting batch (mirrors `runPoll`'s per-creator pattern)
- Line 291-295: `MetaRateLimitException` caught separately, marks tracker limited, persists base row
- Line 296-309: `MetaApiException` (includes Meta 400 for unsupported metric/type) logged as warning, base row still persisted
- Builder always creates row with base fields BEFORE attempting insights call (line 267-288)

### ✅ PASS: No new Maven dependencies for this task
**File:** `pom.xml` diff review
- Only new dependency in diff is `openpdf` version 1.3.42 (approved per comment for contract PDF generation, not related to this task)
- B1 implementation uses existing `InstagramInsightsClient`, `MetaRateLimitTracker`, `MediaMetricsRepository` — all already in classpath

---

## HIGH Priority (fix before delivery)

### ✅ PASS: Rate-limit tracker respected
**File:** `MetricsPollingJob.java` lines 217-225, 227-236
- Line 217-225: Pre-flight check before media-list call (>=90% usage defers to next cycle)
- Line 227-236: `MetaRateLimitException` on media-list call marks limited and skips gracefully
- Line 291-295: Per-item insights rate-limit also marks tracker and degrades (base row still saved)
- Pattern consistent with profile polling's existing rate-limit discipline

### ✅ PASS: Test quality — assertions prove claims, not rubber-stamp
Reviewed all 7 new tests (lines 356-562 in `MetricsPollingJobTest.java`):

1. **testMediaMetricsMapsImageInsights** (line 357): ✅ Asserts ALL 13 mapped fields individually, not just "save was called"
2. **testMediaMetricsMapsVideoViewsOnlyForVideoLikeTypes** (line 403): ✅ Proves VIDEO type gets `videoViews`, gating logic verified
3. **testMediaMetricsDegradesGracefullyWhenInsightsRejected** (line 429): ✅ **EXCELLENT** — explicitly asserts base fields ARE persisted after Meta 400, insight fields stay null, AND profile metric still saved (line 451-463)
4. **testMediaMetricsInsightsRateLimitedStillPersistsBaseRow** (line 467): ✅ Asserts `rateLimitTracker.markLimited()` called AND `mediaMetricsRepository.save()` called
5. **testMediaMetricsSkipsWhenMediaListRateLimited** (line 487): ✅ Asserts media save never called BUT profile save still called
6. **testMediaMetricsEmptyMediaListNoOp** (line 501): ✅ Negative assertions (never saved, never called insights)
7. **testMediaMetricsOneItemFailureDoesNotAbortOthers** (line 514): ✅ Asserts exactly 2 rows saved (good1 + good2), bad item skipped — proves batch resilience

**No rubber-stamp tests found.** Every test has specific, verifiable assertions tied to the claimed behavior.

### ✅ PASS: Per-media-type metric gating logic
**File:** `MetricsPollingJob.java` lines 315-341
- Line 315: `isVideoLike = MEDIA_TYPE_VIDEO.equals(mediaType) || MEDIA_TYPE_REELS.equals(mediaType)`
- Line 329-336: `video_views` and `plays` metric names only applied when `isVideoLike` is true
- Constants defined at class level (lines 68-70): `MEDIA_TYPE_VIDEO = "VIDEO"`, `MEDIA_TYPE_REELS = "REELS"`
- Test coverage: line 403-426 explicitly verifies VIDEO gets views, IMAGE test (line 357-400) asserts null for `videoViews`

### ✅ PASS: Timestamp parsing edge cases
**File:** `MetricsPollingJob.java` lines 367-375
- Null/blank check before parse (line 368-370)
- Wrapped in try-catch (line 371-375), logs unparseable timestamp, returns null (never throws)
- Test coverage: line 363 in test uses valid ISO-8601 format; edge-case behavior (null return on bad input) proven by code inspection (no NPE possible)

---

## MEDIUM Priority (fix when possible)

### ✅ PASS: Repository save semantics / duplicate row handling
**File:** `MediaMetric.java` + `V21__creator_metrics.sql` review

**Initial concern:** Repeated polls of the same `media_id` — does this create duplicate rows or upsert?

**Resolution:** Design-by-intent, NOT a defect.
- Entity javadoc (line 15-17): "Rows are immutable — one row per poll per media item."
- Schema (V21 lines 39-64): No UNIQUE constraint on `media_id` + `time` — multiple snapshots allowed by design
- Each poll generates a NEW `id` (ULID) and NEW `time`/`fetched_at` (line 268-269 in job code: `Ulids.newUlid()`, `Instant.now()`)
- Repository finder `findFirstByMediaIdOrderByTimeDesc` (line 28) explicitly returns "most recent snapshot" — implies multiple rows per media_id expected
- This is time-series behavior: same media polled on Day 1 (100 likes) and Day 2 (150 likes) → 2 rows, not an update

**ADVISORY:** Consider adding a migration comment or entity javadoc clarification that duplicate `media_id` rows across time are EXPECTED (time-series snapshots), not a schema oversight. Current code is CORRECT; documentation could prevent future misinterpretation.

### ✅ PASS: Code style and consistency
**File:** `MetricsPollingJob.java` entire diff
- Follows existing job code patterns (mirrors `pollOne`'s structure)
- Javadoc on every new method (lines 211-216, 264-278, 343-356, 367-375)
- Error logging follows established pattern: `log.warn` for expected failures (rate-limit, Meta 400), `log.error` for unexpected
- Constants at class level, not magic numbers
- Builder pattern used consistently with existing `CreatorMetric` code

---

## ACCEPTANCE CRITERIA VERIFICATION

Per `wiki/tech/REMAINING_WORK_PLAN.md` Wave B task B1:

| Criterion | Status | Evidence |
|-----------|--------|----------|
| "Media rows persist" | ✅ VERIFIED | Test line 383-399: `ArgumentCaptor` proves `mediaMetricsRepository.save()` called with correct field mapping |
| "`mvn test` + new job tests" | ✅ VERIFIED | Vikram reports 269/269 green (7 new tests added), test file reviewed above |
| "Kabir confirms no cross-workspace leak in the batch" | ⏳ PENDING | Pattern matches proven-safe profile polling; forwarding to Kabir for independent security audit |
| "Map `InstagramInsightsResponse` → `MediaMetric`" | ✅ VERIFIED | `applyInsights()` method (lines 311-341) maps 9 insight metrics via switch statement |
| "Handle per-media-type metric availability" | ✅ VERIFIED | `video_views`/`plays` gated on `isVideoLike`, Meta 400 degrades gracefully (test line 429-464) |
| "Respect the rate-limit tracker" | ✅ VERIFIED | Pre-flight check (line 217-225), `markLimited()` on exception (line 232, 293) |

---

## SECURITY REVIEW HANDOFF NOTES FOR KABIR

**Cross-workspace isolation audit (your scope per acceptance criteria):**

1. **creatorProfileId provenance:** Line 209 → `pollRecentMedia(creatorProfileId, token.get())` uses the SAME `creatorProfileId` resolved in `pollOne` via `token.getCreatorProfileId()` (line 159 in existing code). If the existing profile polling is workspace-safe, this inherits that safety by construction.

2. **No new workspace surface:** `MediaMetric` entity has NO `workspace_id` column (by design, per `MediaMetricsRepository` javadoc lines 16-23). Workspace scoping happens upstream via `creatorProfileId` → `creator_profiles` FK (V21 line 63).

3. **Batch processing:** Line 305-309 loops `mediaResponse.data()` — all items share the same `creatorProfileId` passed to `pollRecentMedia`. No user-supplied `media_id` lookup; all media items fetched FROM Meta FOR that creator.

4. **Rate-limit tracker keyed by creatorProfileId:** Lines 218, 232, 293 — tracker calls use `creatorProfileId`, not a workspace-level key. Verify `MetaRateLimitTracker` implementation doesn't have a cross-workspace leak via shared rate-limit state.

**Recommend focus areas:**
- `MetaRateLimitTracker.getCurrentUsage(creatorProfileId)` / `.markLimited(creatorProfileId)` — ensure these don't inadvertently share state across workspaces
- Existing `pollOne` workspace isolation (if that's safe, B1 is safe by extension)

---

## FINAL NOTES

**What impressed me (genuine quality indicators):**
1. Test line 429-464: The "Meta 400 graceful degradation" test explicitly asserts BOTH that base fields persist AND that the profile metric still saved — proves the per-item catch doesn't poison the creator's overall poll. This is load-bearing correctness.
2. Defensive null handling throughout — no lazy `!!` assumptions that Meta always returns complete data
3. Error messages include context (`creatorProfileId`, `mediaId`) for production debugging

**What could improve (not blockers):**
1. Consider extracting magic string "IMAGE" default (line 270) to a class constant `DEFAULT_MEDIA_TYPE`
2. `applyInsights()` default case (line 337-339) silently ignores unknown metric names — could log at TRACE level for future Meta API changes

**Test suite health:**
- 269 total tests (Vikram's report)
- 18/18 in `MetricsPollingJobTest` (11 pre-existing + 7 new)
- Zero test smells detected (no `@Disabled`, no commented-out assertions, no `assertTrue(true)` no-ops)

---

## RECOMMENDATION

**APPROVE** for progression to Kabir's security review. Code quality, test coverage, and error-handling discipline all meet production standards. The one ADVISORY note on schema semantics is a documentation opportunity, not a functional defect.

**Next steps:**
1. Route to Kabir for cross-workspace-leak security audit (acceptance criteria gating item)
2. After Kabir approval: Meera runs full `mvn clean install` + Spring Boot smoke test (if environment allows)
3. After Meera verification: Arjun clears for merge to feature branch

---

**Kavya Reddy, QA Lead**
2026-07-07 | Sage Digital
