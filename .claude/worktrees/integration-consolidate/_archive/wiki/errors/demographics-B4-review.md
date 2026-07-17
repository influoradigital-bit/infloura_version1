# QA Review: Wave B Task B4 — Audience Demographics
**Date:** 2026-07-07  
**Reviewer:** Kavya (QA Lead)  
**Status:** ✅ **APPROVED**  
**Developer:** Vikram  
**Pipeline Step:** QA → Kabir (security) → Meera (live-MySQL migration check)

---

## Summary

Wave B task B4 (audience demographics) is **APPROVED for advancement to Kabir's security review**. Code quality: **9.5/10**. Vikram followed all architectural patterns correctly — the migration is structurally consistent with sibling migrations V21–V24, the entity mapping is column-for-column correct, authorization flows through the exact same `MetricsAuthorizationService.resolveAuthorizedCreatorProfileId` gate as `/metrics` and `/scores`, the job semantics mirror `MetricsPollingJob`'s conventions precisely, and the 20 new tests are load-bearing (cross-workspace rejection test is present and correctly rejects BEFORE any data read, sub-100-follower no-fabrication test confirms graceful skip, overlap-guard test actually proves concurrency safety).

Zero CRITICAL or HIGH findings. One MEDIUM advisory (non-blocking) noted below for documentation clarity.

---

## Review Checklist Results

### ✅ 1. Migration Correctness (V25 SQL vs Entity Mapping)

**Files reviewed:**
- `influora-api/src/main/resources/db/migration/V25__audience_demographics.sql`
- `influora-api/src/main/java/com/influora/domain/entity/AudienceDemographics.java`

**Column-for-column verification:**

| DDL Column | Entity Field | Type Match | Nullability Match | Notes |
|------------|--------------|------------|-------------------|-------|
| `id VARCHAR(26)` | `@Column(length=26) String id` | ✅ | ✅ | PRIMARY KEY mapped |
| `time DATETIME(6) NOT NULL` | `@Column(columnDefinition="DATETIME(6)") Instant time` | ✅ | ✅ | UTC convention correct |
| `creator_profile_id VARCHAR(26) NOT NULL` | `@Column(length=26) String creatorProfileId` | ✅ | ✅ | FK target correct |
| `platform VARCHAR(20) NOT NULL` | `@Column(length=20) String platform` | ✅ | ✅ | Default "INSTAGRAM" in builder |
| `age_gender_breakdown JSON NULL` | `@JdbcTypeCode(SqlTypes.JSON) String ageGenderBreakdownJson` | ✅ | ✅ | Nullable, no NOT NULL |
| `country_breakdown JSON NULL` | `@JdbcTypeCode(SqlTypes.JSON) String countryBreakdownJson` | ✅ | ✅ | Nullable |
| `city_breakdown JSON NULL` | `@JdbcTypeCode(SqlTypes.JSON) String cityBreakdownJson` | ✅ | ✅ | Nullable |
| `locale_breakdown JSON NULL` | `@JdbcTypeCode(SqlTypes.JSON) String localeBreakdownJson` | ✅ | ✅ | Nullable |
| `data_source VARCHAR(20) NOT NULL DEFAULT 'META_API'` | `@Column(length=20) String dataSource` | ✅ | ✅ | Default in builder |
| `fetched_at DATETIME(6) NOT NULL` | `@Column(columnDefinition="DATETIME(6)") Instant fetchedAt` | ✅ | ✅ | App-side Instant.now() |
| `created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP` | `@Column(updatable=false) Instant createdAt` | ✅ | ✅ | DB default + app-side fallback |

**DDL constraints verified:**
- ✅ FK `fk_audience_demographics_creator` targets `creator_profiles(id)` — matches V21/V22 pattern exactly
- ✅ Index `idx_audience_demographics_creator_time (creator_profile_id, time)` mirrors `idx_creator_scores_creator_time` from V22
- ✅ `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci` — consistent with ALL sibling migrations (checked V20–V24)
- ✅ JSON column syntax `JSON NULL` is MySQL 8+ valid (not `JSONB`, which is Postgres-only) — same syntax as V22's `fake_follower_reasons JSON NULL`
- ✅ No CREATE EXTENSION or hypertable syntax — per CTO ruling wiki/decisions/2026-07-06-phase2-timescaledb-datastore.md
- ✅ No DEFAULT NOW() on DATETIME(6) columns — app sets via Instant.now(), matching V21/V22 convention (avoids DB-side timezone ambiguity)

**Migration will execute on live MySQL 8:** ✅ SAFE (no syntax errors predicted; Meera's live throwaway-DB check is next step per pipeline)

---

### ✅ 2. Authorization: Resolve-Then-Scope Pattern

**File reviewed:** `influora-api/src/main/java/com/influora/service/analytics/AnalyticsService.java` (lines 220-241)

**Authorization flow (method `getCreatorDemographics`):**

```java
String workspaceId = brandContext.requireBrandWorkspace(principal).getId();  // Line 221 — resolve trustworthy workspace
String authorizedCreatorId = 
    metricsAuthorizationService.resolveAuthorizedCreatorProfileId(workspaceId, creatorId);  // Line 222-223 — gate throws FORBIDDEN if no active token links workspace→creator
Optional<AudienceDemographics> snapshot =
    audienceDemographicsRepository.findFirstByCreatorProfileIdOrderByTimeDesc(authorizedCreatorId);  // Line 225-227 — ONLY the resolved id is passed to repository
```

**Pattern consistency check vs `/metrics` and `/scores`:**

| Endpoint | Workspace Resolution | Authorization Gate | Repository Call Uses | Pattern Match |
|----------|---------------------|-------------------|---------------------|---------------|
| `/analytics/creators/{id}/metrics` | `brandContext.requireBrandWorkspace` line 90 | `metricsAuthorizationService.resolve...` line 91-92 | `authorizedCreatorId` line 94 | ✅ Reference |
| `/analytics/creators/{id}/scores` | `brandContext.requireBrandWorkspace` line 176 | `metricsAuthorizationService.resolve...` line 177-178 | `authorizedCreatorId` line 181 | ✅ Identical |
| `/analytics/creators/{id}/demographics` | `brandContext.requireBrandWorkspace` line 221 | `metricsAuthorizationService.resolve...` line 222-223 | `authorizedCreatorId` line 226 | ✅ **IDENTICAL** |

✅ **VERIFIED:** The endpoint's resolve-then-scope call is **genuinely identical** to `/metrics` and `/scores`.

**Cross-workspace test coverage:** `AnalyticsServiceTest.java` line 258 — `testGetCreatorDemographicsRejectsUnauthorizedCrossWorkspaceCreator`:
- ✅ Test passes `OTHER_WORKSPACES_CREATOR_ID` to the service
- ✅ Mock `metricsAuthorizationService.resolveAuthorizedCreatorProfileId` throws `ApiException("FORBIDDEN", ..., 403)`
- ✅ Test asserts exception code = "FORBIDDEN", status = 403
- ✅ **CRITICAL:** Test verifies `verifyNoInteractions(audienceDemographicsRepository)` — proves FORBIDDEN thrown **BEFORE any data read**
- ✅ This is the MANDATORY isolation test per REMAINING_WORK_PLAN.md line 32 acceptance criteria

**Authorization verdict:** ✅ CORRECT — follows exact pattern, cross-workspace test proves rejection before data access.

---

### ✅ 3. hasData:false Design (No Fabrication)

**Files reviewed:**
- `influora-api/src/main/java/com/influora/web/dto/analytics/AnalyticsDtos.java` lines 119-140
- `influora-api/src/main/java/com/influora/service/analytics/AnalyticsService.java` lines 220-241

**DTO shape when no snapshot exists:**

```java
// AnalyticsService.java line 229-231
if (snapshot.isEmpty()) {
    return CreatorDemographicsResponse.empty();  // Static factory method
}
```

**AnalyticsDtos.java lines 136-139:**
```java
public static CreatorDemographicsResponse empty() {
    return new CreatorDemographicsResponse(false, null, null, null, null, null);
    // hasData=false, all 4 breakdowns null, fetchedAt null
}
```

**Verification:**
- ✅ `hasData` boolean field explicitly set to `false` (line 129 record signature, line 138 factory)
- ✅ All 4 breakdown maps are `null`, not empty `{}` or fabricated `{"US": 0}`
- ✅ `fetchedAt` is `null` (no fake timestamp)
- ✅ No code path anywhere in `AudienceDemographicsJob.java` writes a row when response is empty — lines 171-179 skip with `return false`, never call `demographicsRepository.save()`
- ✅ Controller (`AnalyticsController.java` line 79-80) wraps `AnalyticsService` response directly in `ApiResponse.ok()` — no DTO mutation, no 404
- ✅ ControllerTest line 76-86 `testGetDemographicsPassesThroughGracefulEmptyResponse` confirms `HttpStatus.OK` + `hasData=false`

**Frontend-friendliness check (Ananya's B5 will consume this):**
- ✅ Single boolean `hasData` field is unambiguous (no need to check 4 nulls separately)
- ✅ All breakdowns are `Map<String, Long>` (standard JSON shape, no nested records)
- ✅ `@JsonInclude(JsonInclude.Include.NON_NULL)` on the DTO means null breakdowns omit from JSON entirely (cleaner wire format)
- ✅ Javadoc line 123-125 explicitly documents "never a 404, never zero-filled fake buckets" — clear contract for frontend

**No-fabrication verdict:** ✅ CORRECT — graceful empty response, no synthetic data.

---

### ✅ 4. Job Semantics (Weekly Cron, Overlap Guard, Per-Creator Isolation, Sub-100-Follower No-Fabrication)

**File reviewed:** `influora-api/src/main/java/com/influora/job/AudienceDemographicsJob.java`

**4.1 Weekly Cron:**
- Line 94: `@Scheduled(cron = "0 30 3 * * SUN")` — **every Sunday at 3:30 AM**
- ✅ Cron syntax correct: minute=30, hour=3, day-of-month=*, month=*, day-of-week=SUN
- ✅ Offset from daily token jobs (V20 token refresh at 2:30 AM per `MetaTokenRefreshService`, cleanup at 4:00 AM per `StaleTokenCleanupJob`) — no overlap with those daily jobs
- ✅ Javadoc line 40-42 justifies weekly cadence: "demographic breakdowns shift slowly compared to engagement metrics, and Meta's own `audience_*` metrics are a `period=lifetime` snapshot recomputed server-side rather than a per-interaction counter, so polling more often buys nothing."

**4.2 Overlap Guard:**
- Line 76: `private final AtomicBoolean running = new AtomicBoolean(false);`
- Line 96-98: 
  ```java
  if (!running.compareAndSet(false, true)) {
      log.warn("...previous run still in progress, skipping this trigger");
      return;
  }
  ```
- Line 100-104: `try { runPoll(); } finally { running.set(false); }`
- ✅ Pattern matches `MetricsPollingJob.running` exactly (javadoc line 35 cites this as convention)
- ✅ Test coverage: `AudienceDemographicsJobTest.java` line 270-301 `testOverlapGuardPreventsConcurrentRuns` — spawns thread1 with a sleep-delayed mock, calls job again on main thread, verifies only 1 token fetch + 1 audit call occurred (proves second call was skipped)

**4.3 Per-Creator Isolation (Covers the Mapping Code):**
- Lines 114-132 outer loop over `connectedTokens`:
  ```java
  for (MetaOAuthToken tokenRow : connectedTokens) {
      try {
          if (pollOne(workspaceId, creatorProfileId)) { polled++; } else { failed++; }
      } catch (Exception e) {  // Line 124 — defensive catch-all
          failed++;
          log.error("...unexpected failure polling creator {}", creatorProfileId, e);
      }
  }
  ```
- ✅ Outer `catch (Exception e)` at line 124 genuinely wraps the call to `pollOne()`, which contains ALL the mapping logic (lines 167-208: API call, response null check, `extractBreakdown()` for all 4 dimensions, `JsonLists.toJsonObject()` serialization, entity build, `demographicsRepository.save()`)
- ✅ One creator's mapping/serialization/save failure cannot abort the batch — exception is logged, `failed` counter incremented, loop continues
- ✅ Test coverage: `AudienceDemographicsJobTest.java` line 224-252 `testPerCreatorFailureIsolationDoesNotAbortBatch` — creator1's `tokenStorage.getValidToken` throws `RuntimeException`, creator2 succeeds; test verifies exactly 1 `save()` call with creator2's id

**4.4 Rate-Limit Pre-Flight Check:**
- Line 157-165:
  ```java
  int usage = rateLimitTracker.getCurrentUsage(creatorProfileId);
  if (usage >= RATE_LIMIT_THRESHOLD_PERCENT) {  // Line 60: threshold = 90
      log.warn("...creator {} at {}% Meta rate-limit usage, deferring to next cycle", creatorProfileId, usage);
      return false;
  }
  ```
- ✅ Check runs BEFORE the `instagramClient.getAudienceDemographics()` call (line 168) — no API budget spent when at/above threshold
- ✅ Pattern matches `MetricsPollingJob` (javadoc line 35-36 cites this as convention)
- ✅ Test coverage: `AudienceDemographicsJobTest.java` line 174-185 `testRateLimitedCreatorIsDeferred` — mocks `getCurrentUsage` returning 95, verifies `never().getAudienceDemographics()` and `never().save()`

**4.5 Sub-100-Follower / Empty-Response No-Fabrication:**
- Lines 171-179:
  ```java
  if (response == null || response.data() == null || response.data().isEmpty()) {
      // Accounts under Meta's 100+ follower threshold (or with no audience data yet) return
      // an empty payload — logged and skipped, never persisted as a fabricated empty row.
      log.warn("...empty audience-demographics response for creator {} (likely below Meta's 100+ follower threshold), skipping", creatorProfileId);
      return false;
  }
  ```
- Lines 186-192 (second no-data check):
  ```java
  if (ageGender.isEmpty() && country.isEmpty() && city.isEmpty() && locale.isEmpty()) {
      log.warn("...no recognized breakdowns in response for creator {}, skipping", creatorProfileId);
      return false;
  }
  ```
- ✅ Both code paths `return false` (signal "not persisted") WITHOUT calling `demographicsRepository.save()` — no fabricated row written
- ✅ Lines 200-203 call `nullIfEmpty(map)` helper (line 251-253) which converts empty map → `null` before `JsonLists.toJsonObject()`, so even if one breakdown was present, empty siblings stay `null` in the JSON columns (not serialized as `"{}"`)
- ✅ Test coverage: `AudienceDemographicsJobTest.java`:
  - Line 143-155 `testEmptyResponseIsSkippedNotFabricated` — mocks `getAudienceDemographics` returning `new AudienceDemographicsResponse(Collections.emptyList())`, verifies `never().save()`
  - Line 159-170 `testNullResponseIsSkipped` — mocks returning `null`, verifies `never().save()`

**Job semantics verdict:** ✅ CORRECT — all conventions followed, tests prove the claims.

---

### ✅ 5. Test Quality (20 New Tests)

**Files reviewed:**
- `influora-api/src/test/java/com/influora/job/AudienceDemographicsJobTest.java` — 10 tests
- `influora-api/src/test/java/com/influora/repository/AudienceDemographicsRepositoryTest.java` — 4 tests
- `influora-api/src/test/java/com/influora/service/analytics/AnalyticsServiceTest.java` — 4 new tests (lines 254-359)
- `influora-api/src/test/java/com/influora/web/AnalyticsControllerTest.java` — 2 new tests (lines 49-86)

**Job tests (10):**

| Test Name | Line | What It Proves | Rubber-Stamp? |
|-----------|------|----------------|---------------|
| `testSuccessfulPollPersistsSnapshotWithAllBreakdowns` | 74-125 | ArgumentCaptor verifies all 4 JSON columns contain expected substrings (F.25-34, US, New York, en_US) | ❌ Real |
| `testNoValidTokenSkipsCreator` | 129-139 | `tokenStorage` returns empty → `never().getAudienceDemographics()` + `never().save()` | ❌ Real |
| `testEmptyResponseIsSkippedNotFabricated` | 143-155 | Empty response list → `never().save()` (proves no fabricated row) | ❌ **Key** |
| `testNullResponseIsSkipped` | 159-170 | Null response → `never().save()` | ❌ Real |
| `testRateLimitedCreatorIsDeferred` | 174-185 | `getCurrentUsage` = 95 → `never().getAudienceDemographics()` | ❌ Real |
| `testMetaRateLimitExceptionDuringFetchIsHandled` | 189-202 | Exception thrown → `verify(rateLimitTracker).markLimited()` + `never().save()` | ❌ Real |
| `testMetaApiExceptionDuringFetchIsHandled` | 206-220 | MetaApiException → `never().save()` + audit still called | ❌ Real |
| `testPerCreatorFailureIsolationDoesNotAbortBatch` | 224-252 | creator1 throws → creator2 still processes; verify exactly 1 `save()` with creator2's id | ❌ **Key** |
| `testNoTokensIsNoOp` | 256-266 | Empty token list → `never().getAudienceDemographics()` + audit called (proves zero-count recording) | ❌ Real |
| `testOverlapGuardPreventsConcurrentRuns` | 270-301 | Thread1 + main both call `pollDemographics()` → verify only 1 token fetch (proves guard works) | ❌ **Key** |

**Repository tests (4):**

| Test Name | Line | What It Proves | Rubber-Stamp? |
|-----------|------|----------------|---------------|
| `testEntityBuilderRoundTripsFieldsAndAppliesDefaults` | 35-65 | All getters return what builder set; defaults (INSTAGRAM, META_API, createdAt) applied when omitted | ❌ Real |
| `testEntityBuilderPreservesNullBreakdowns` | 69-80 | Builder with no breakdown setters called → all 4 getters return null (no fabricated empty JSON) | ❌ Real |
| `testFindFirstByCreatorProfileIdOrderByTimeDesc` | 84-97 | Derived query callable, returns entity | ⚠️ Routine |
| `testFindFirstByCreatorProfileIdOrderByTimeDescReturnsEmptyWhenNoneExist` | 100-109 | Empty result doesn't throw | ⚠️ Routine |

**Service tests (4):**

| Test Name | Line | What It Proves | Rubber-Stamp? |
|-----------|------|----------------|---------------|
| `testGetCreatorDemographicsRejectsUnauthorizedCrossWorkspaceCreator` | 258-281 | Other workspace's creator → FORBIDDEN + `verifyNoInteractions(audienceDemographicsRepository)` | ❌ **MANDATORY** |
| `testGetCreatorDemographicsSucceedsForAuthorizedCreatorWithSnapshot` | 285-317 | Authorized + snapshot exists → deserialize all 4 breakdowns, assert bucket counts correct | ❌ Real |
| `testGetCreatorDemographicsReturnsGracefulEmptyWhenNoSnapshotYet` | 323-341 | Empty repository result → `hasData=false` + all breakdowns null + fetchedAt null | ❌ Real |
| `testGetCreatorDemographicsNeverPassesRawCreatorIdWhenAuthorizationRemapsIt` | 345-359 | Authorization returns different id → verify repository called with resolved id, never with raw caller-supplied id | ❌ **Key** |

**Controller tests (2):**

| Test Name | Line | What It Proves | Rubber-Stamp? |
|-----------|------|----------------|---------------|
| `testGetDemographicsReturnsServiceResponseWrappedInApiResponse` | 50-72 | Controller delegates to service, wraps in ApiResponse.ok(), returns 200 + DTO | ❌ Real |
| `testGetDemographicsPassesThroughGracefulEmptyResponse` | 76-86 | Service returns empty() → controller returns 200 + `hasData=false` (not 404) | ❌ Real |

**Test quality summary:**
- ✅ **18 of 20 tests are load-bearing** (2 repository tests are routine but harmless — they confirm the derived query compiles and the entity builder works)
- ✅ 3 **MANDATORY/Key tests present**:
  1. Cross-workspace rejection + no data read (`testGetCreatorDemographicsRejectsUnauthorizedCrossWorkspaceCreator`)
  2. Sub-100-follower no-fabrication (`testEmptyResponseIsSkippedNotFabricated`)
  3. Per-creator isolation (`testPerCreatorFailureIsolationDoesNotAbortBatch`)
  4. Overlap guard concurrency (`testOverlapGuardPreventsConcurrentRuns`)
  5. Authorization resolve-vs-raw-id verification (`testGetCreatorDemographicsNeverPassesRawCreatorIdWhenAuthorizationRemapsIt`)
- ✅ All tests use real assertions (ArgumentCaptor verifies captured values, `never()` verifiers prove no unwanted side effects, explicit field equality checks)
- ✅ Zero padding tests found

**Test verdict:** ✅ EXCELLENT — quality 9/10, all key acceptance scenarios covered.

---

## Findings

### MEDIUM (non-blocking advisory for Vikram)

**M-1: Cron expression could benefit from an inline comment in the code**
- **File:** `AudienceDemographicsJob.java` line 94
- **Issue:** The cron `"0 30 3 * * SUN"` is correct, but developers unfamiliar with Spring cron syntax might not immediately parse "3:30 AM Sunday" from the 6-field string. The javadoc above line 93 does explain it, but an inline comment on the annotation itself would help.
- **Suggested fix (next time Vikram touches this file):**
  ```java
  @Scheduled(cron = "0 30 3 * * SUN") // Every Sunday at 3:30 AM (weekly cadence)
  ```
- **Why non-blocking:** The cron expression is verifiably correct (minute 30, hour 3, Sunday), and the javadoc documents it. This is a readability/maintainability suggestion, not a functional bug.

---

## Files Changed (Uncommitted)

✅ All files exist and are syntactically valid (no compilation errors predicted):

**Backend (Java):**
1. `influora-api/src/main/resources/db/migration/V25__audience_demographics.sql` — NEW migration
2. `influora-api/src/main/java/com/influora/domain/entity/AudienceDemographics.java` — NEW entity
3. `influora-api/src/main/java/com/influora/repository/AudienceDemographicsRepository.java` — NEW repository
4. `influora-api/src/main/java/com/influora/job/AudienceDemographicsJob.java` — NEW scheduled job
5. `influora-api/src/main/java/com/influora/service/analytics/AnalyticsService.java` — MODIFIED (added `getCreatorDemographics` method, lines 211-241)
6. `influora-api/src/main/java/com/influora/web/AnalyticsController.java` — MODIFIED (added `getDemographics` endpoint, lines 72-81; removed stale "coming soon" javadoc)
7. `influora-api/src/main/java/com/influora/web/dto/analytics/AnalyticsDtos.java` — MODIFIED (added `CreatorDemographicsResponse` record, lines 119-140)

**Tests (Java):**
8. `influora-api/src/test/java/com/influora/job/AudienceDemographicsJobTest.java` — NEW (10 tests)
9. `influora-api/src/test/java/com/influora/repository/AudienceDemographicsRepositoryTest.java` — NEW (4 tests)
10. `influora-api/src/test/java/com/influora/service/analytics/AnalyticsServiceTest.java` — MODIFIED (+4 demographics tests, lines 254-359)
11. `influora-api/src/test/java/com/influora/web/AnalyticsControllerTest.java` — MODIFIED (+2 demographics tests, lines 49-86)

---

## What Meera Must Verify (Next Pipeline Step)

Per standing rule (REMAINING_WORK_PLAN.md line 14): **New migration ⇒ Meera does a live-MySQL throwaway-DB check**.

1. **Live MySQL migration check:**
   - Create a throwaway test database with the V1–V24 baseline schema applied
   - Run `V25__audience_demographics.sql` via Flyway migrate
   - Verify no SQL syntax errors
   - Verify the table exists: `SHOW CREATE TABLE audience_demographics;`
   - Verify FK constraint exists: `SELECT CONSTRAINT_NAME, REFERENCED_TABLE_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_NAME='audience_demographics' AND CONSTRAINT_NAME='fk_audience_demographics_creator';`
   - Verify index exists: `SHOW INDEX FROM audience_demographics WHERE Key_name='idx_audience_demographics_creator_time';`
   - Verify JSON columns are correctly typed: `SELECT COLUMN_NAME, DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_NAME='audience_demographics' AND DATA_TYPE='json';` (expect 4 rows)
   - Drop the throwaway DB

2. **Build verification (after Kabir's security review):**
   - `mvn clean compile` — must succeed
   - `mvn test` — Arjun will independently re-run to confirm 345/345 green

---

## Decision

✅ **APPROVED** — advance to Kabir for security/workspace-isolation review, then Meera for live-MySQL migration check.

**Rationale:**
- Migration DDL is structurally consistent with V20-V24 (same MySQL conventions, no Postgres syntax, correct FK/index/charset)
- Entity mapping is column-for-column correct (types, nullability, JSON annotations all match)
- Authorization follows the exact resolve-then-scope pattern as `/metrics` and `/scores`, with the MANDATORY cross-workspace rejection test present and proving FORBIDDEN before any data read
- Job semantics mirror `MetricsPollingJob` conventions exactly (overlap guard, per-creator isolation, rate-limit pre-flight, graceful degradation)
- No fabrication anywhere (empty responses are skipped, not persisted as synthetic rows; `hasData:false` DTO is graceful and unambiguous)
- Test quality is excellent (18 of 20 tests are load-bearing, all key scenarios covered)
- One MEDIUM advisory is documentation/readability only, not a functional issue

**Next Steps:**
1. **Kabir:** Security review (public endpoints are out of scope for B4 — this is a brand-authed internal API + a system-wide scheduled job, same trust boundary as B2/B3; focus on workspace-isolation verification and the 4 JSON deserialization paths in `AnalyticsService.readBreakdown`).
2. **Meera:** Live-MySQL throwaway-DB migration check (V25 DDL must execute without errors).
3. **Arjun:** Independent `mvn test` re-run before advancing to B5 (Ananya's frontend wiring).

---

**Quality Score:** 9.5/10  
**Reviewer Confidence:** High
