# Security Review: Wave C Task C1 (Caption Persistence — PII / Retention)

Date: 2026-07-07
Reviewer: Kabir (Red-Team / CISO)
Load-bearing: YES (plan marks Kabir PII/retention sign-off as gating)
Governing ADR: `wiki/decisions/2026-07-06-brand-safety-caption-storage.md` (LOCKED)

## Verdict: SIGN-OFF (conditional)

0 CRIT / 0 HIGH / 2 MED / 2 LOW. No non-DTO leak path found in the current tree.
The stored caption cannot reach a brand-facing response, a log line, or an error
body today. The two MED findings are **retention/deletion gaps** that C1 *inherits*
from the whole metrics layer rather than introduces — but the ADR's binding retention
constraint (§ "Privacy / retention constraint", lines 42-49) makes them C1's problem
to at least explicitly defer, and the creator-data-deletion gap escalates the moment
captions (third-party PII) sit in a table with no erasure path.

---

## 1. Leakage paths beyond DTOs (all traced — CLEAN)

### 1a. Logs — NO LEAK (verified)
- `MediaMetric` is a hand-written entity with **no `toString()`, no Lombok `@Data`/`@ToString`**
  (MediaMetric.java — grep for `toString|@ToString|@Data|lombok` → zero matches). An accidental
  `log.x("{}", mediaMetric)` would print the JVM identity hash, not the caption.
- `InstagramMediaResponse.MediaItem` **IS a record** (InstagramMediaResponse.java:12-20) with a
  `caption` component, so its auto-generated `toString()` *does* include the raw caption. Traced
  every use of `mediaItem`/`mediaResponse`: **no code ever logs the object itself** — every log
  statement passes `mediaItem.id()` / `mediaType` / `creatorProfileId` / `e.getMessage()` only
  (MetricsPollingJob.java:270-274, 321-333; InstagramMetricsFetcher.java:191-202). No `log.*(...MediaItem...)`
  match anywhere. Caption never enters an SLF4J call.
- `InstagramMetricsFetcher` javadoc (lines 66-70) explicitly states it never reads/logs caption — verified true.
- ADR compliance: §"Privacy/retention" line 48-49 ("keep it out of logs — same redaction discipline
  as AuditLogService") — **MET.**

### 1b. BrandSafety egress to influora-ai (C2/C3) — NOT YET WIRED (in scope for C3)
- Grep for `BrandSafetyAiClient|brand-safety|BrandSafetyScore` in `influora-api/src/main` → hits are
  only in `AnalyticsDtos`/`CreatorScore`/`ScoreCalculationJob` **javadoc/field names** for the
  *derived* score columns; **no Java client that sends caption to influora-ai exists in this tree yet.**
- So today C1 has **zero live egress** of caption from the DB. The only planned egress (C3's
  `BrandSafetyAiClient` → `POST /internal/brand-safety`) is a future task. Per my C2 review
  (SHARED_CONTEXT line 5) that endpoint is service-scope internal-auth, fail-closed. **PROBE FOR C3:**
  when the Java client lands, confirm it is the *only* reader of `getCaption()` and that it sends over
  the internal service-token channel — not into any brand-facing response.

### 1c. Analytics / export / admin — NO LEAK (verified)
- `AnalyticsDtos` (the ONLY brand-facing surface reading MediaMetric-derived data) exposes **zero
  per-media records** — only aggregates `CreatorMetricsResponse`/`CreatorScoresResponse`/
  `CreatorDemographicsResponse` (AnalyticsDtos.java:85-140). Structurally impossible for caption to appear.
- **No CSV/export/admin endpoint exists** — grep `export|csv|dataExport|/account|deleteAccount` over
  `src/main/java` → zero files. Nothing bulk-serializes MediaMetric.
- `GlobalExceptionHandler` (GlobalExceptionHandler.java:44-48): the catch-all returns a fixed string
  `"An unexpected error occurred"` — **never** serializes the exception message or any entity. No caption
  leak via an error body even if a MediaMetric-touching path throws.

### 1d. DTO leak under a DIFFERENT field name — NO LEAK today, RESIDUAL guardrail gap (LOW-1)
- `MediaMetric.getCaption()` has **zero callers** anywhere in `src/main` (grep `getCaption` → only the
  getter definition + write-path builder javadoc; the scoring services `QualityScoreService`,
  `FakeFollowerDetectionService`, `ScoreCalculationJob` import MediaMetric but **never call getCaption**).
  With zero read callers, no DTO — regardless of field name (`text`/`content`/`postText`) — can be fed
  caption data today. Data-flow-clean, not just name-clean.
- The `NoBrandFacingCaptionExposureTest` guardrail is **name-based only** (isCaptionName, line 118-121:
  matches `caption`/`mediacaption`/`endsWith("caption")`). It would NOT catch a future DTO that maps
  `getCaption()` into a component named `text`/`content`/`description`. See LOW-1.

**Conclusion for §1: no leak path exists in the current tree via any channel — DTO, log, error body,
export, or AI egress.**

---

## 2. Retention / deletion (MED-1, MED-2 — the load-bearing findings)

### MED-1 — No creator-data-deletion / erasure path purges captions (or any metrics)
- There is **no account-deletion, GDPR right-to-erasure, or "forget creator" path anywhere** in the
  backend: grep `deleteAccount|eraseUser|rightToErasure|closeAccount` → zero files. Token handling is
  **soft-revoke only, never hard-delete** by deliberate design (StaleTokenCleanupJob.java:26-29 —
  "soft-revoke, never hard-delete"; AuthService revoke paths at :208/:226/:261 only mark
  `refresh_tokens` revoked). `MediaMetricsRepository` exposes **no `deleteBy*`/`@Modifying`/`delete`**
  finder at all (MediaMetricsRepository.java — grep confirms none).
- **Consequence:** when a creator disconnects Meta, has their token revoked, or (hypothetically) asks
  to be deleted, their `media_metrics` rows — now including **raw caption text** — persist
  indefinitely with no purge. Before C1 this table held only numbers; C1 makes it hold **free-form
  human-authored text that can contain third-party PII** (see §3). That materially changes the
  privacy posture of an un-erasable table.
- **ADR position:** §"Privacy/retention constraint" line 46-47 mandates "apply a retention limit
  consistent with the metrics data." Today the metrics data has **no retention limit and no purge job
  of any kind** — so C1 is *technically* consistent with the (empty) status quo, and the ADR line
  53-54 explicitly says "Retention follows the same lifecycle as the rest of media_metrics — no
  separate retention job introduced by this migration." So C1 **is ADR-compliant by deferral.**
- **Why this is still a finding (not a rejection):** the ADR authorized deferral of a *retention TTL*,
  but it did **not** contemplate that there is *also* no erasure-on-deletion path. Storing already-public
  captions long-term is defensible; storing them with **no mechanism to ever delete them on creator
  request** is a GDPR/DPDP erasure-obligation gap the moment a real deletion request arrives.
- **Severity MED** (not HIGH): no live deletion endpoint exists to be non-compliant *at* today, and
  captions are creator-authored public content. Escalates to HIGH the moment (a) a creator-deletion
  feature ships, or (b) captions start being replicated into influora-ai (C3) without a matching purge.
- **Required (tracked, non-gating for C1):** file a follow-up so that whenever a creator-deletion /
  Meta-disconnect-purge path is built, it MUST `deleteBy creatorProfileId` on `media_metrics`
  (and `creator_metrics`/`audience_demographics`). Arjun/Priya to log this against the deletion epic,
  not against C1.

### MED-2 — No retention TTL/purge job → captions accumulate unbounded
- Same root cause as MED-1's first half: no scheduled purge trims old `media_metrics` rows. Every
  6-hour poll writes a **new** row per media item (rows are immutable, MediaMetric.java:16-17 "one row
  per poll per media item"), each now carrying the caption. A creator's caption is thus stored dozens
  of times and never aged out.
- **ADR:** explicitly deferred (line 53-54, quoted above). So **compliant-by-deferral**, logged here so
  it is a *conscious* accepted risk rather than an oversight. When BrandSafetyScoreService is fully wired,
  a retention/redaction-on-age policy should be revisited per ADR line 46-47.
- **Severity MED**, accepted-risk, non-gating.

---

## 3. PII inside captions (LOW-2 — ADR-consistent, no redaction-on-write required)
- Captions are creator-authored **public** Instagram content, but can embed third-party PII (tagged
  handles, phone numbers, emails in the text). C1 stores them **raw**, no redaction-on-write
  (MetricsPollingJob.java:307 `.caption(mediaItem.caption())`; builder javadoc MediaMetric.java:231-235
  explicitly "store raw as fetched ... not by mutating the stored text").
- **ADR stance:** §"Privacy/retention" line 44-45 — "Captions are already-public Instagram content, so
  storing them for brand-safety analysis is defensible." The ADR's model is **store-raw + exclude-on-read**,
  NOT store-redacted-on-write (it needs the full text for GARM/NLP analysis at C3 — redacting on write
  would defeat the brand-safety purpose). So raw storage is **explicitly ADR-consistent; redaction-on-write
  is NOT required and correctly NOT done.**
- Residual: the third-party-PII exposure risk is entirely bounded by the retention/erasure gap in §2 —
  i.e. it's the *duration* of raw storage, not the fact of it, that's the concern. Folded into MED-1/MED-2.
- **Severity LOW**, ADR-consistent, no action on C1.

---

## 4. Migration / DDL (LOW-3 — ops concern for Meera)
V26.sql:23-26 — `ALTER TABLE media_metrics ADD COLUMN caption TEXT ... AFTER media_type`.
- **Charset: CORRECT.** `CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci` — required for 4-byte
  emoji (V26 comment lines 20-22). Matches V21 table default. Redundant-but-safe (Kavya's advisory).
- **Nullable: SANE.** `NULL`, no default — correct for a backfill-nothing column; existing rows get NULL.
- **Lock/rewrite risk (the real ops item):** `ADD COLUMN ... AFTER media_type` forces a **specific
  column position**, which on MySQL/InnoDB generally requires a **full table rebuild** (ALGORITHM=COPY),
  not an in-place instant-add. `ADD COLUMN` at the *end* of the table can be `ALGORITHM=INSTANT` on
  MySQL 8.0.12+; forcing a position defeats INSTANT. On a large populated `media_metrics` (6-hourly
  writes accumulate fast), this ALTER will **hold a metadata lock and rewrite the whole table** —
  a potential write-stall for the polling job during migration. **LOW** because on a fresh/small dev DB
  it's instant, and it's a one-time cost — but Meera must measure it on realistic row counts.

---

## Probes for Meera's live-MySQL V26 check
1. **utf8mb4 4-byte round-trip:** INSERT a caption with a 4-byte emoji + non-Latin (`"drop 🔥 日本 café @someone +1-555-0100"`), read back via the entity; assert `CHAR_LENGTH` counts the emoji as 1 char (not mangled bytes) and the string is byte-identical. Confirm `information_schema.COLUMNS` shows `media_metrics.caption` = `DATA_TYPE=text`, `CHARACTER_SET_NAME=utf8mb4`, `COLLATION_NAME=utf8mb4_unicode_ci`, `IS_NULLABLE=YES`, `COLUMN_DEFAULT` NULL.
2. **Column position:** confirm `caption` lands `AFTER media_type` (`ORDINAL_POSITION` immediately after `media_type`) — matches the entity field order and the DDL intent.
3. **ALTER algorithm / lock cost (LOAD-BEARING ops check):** run V26 against a `media_metrics` **pre-populated with a realistic row count** (seed a few hundred k rows) and capture whether MySQL uses `ALGORITHM=INSTANT` or falls back to `COPY`/`INPLACE` with a rebuild (e.g. `ALTER ... ADD COLUMN caption ... AFTER media_type, ALGORITHM=INSTANT` will **error** if INSTANT is impossible — try it explicitly to prove the point). Report the lock duration and whether a bare append (no `AFTER`) would have been instant — input for Priya/Meera on whether to keep the positional clause.
4. **NULL backfill:** confirm pre-existing rows get `caption = NULL` (not empty string), no NPE on read, and the entity maps NULL → `getCaption()` returns null.
5. **No purge exists (confirm the §2 gap on the live schema):** confirm there is **no** trigger/event/scheduled purge on `media_metrics` (`SHOW TRIGGERS`, `information_schema.EVENTS`) — i.e. the retention gap is real at the DB layer too, not just absent in Java.

---

## Files reviewed
- `influora-api/src/main/resources/db/migration/V26__media_metrics_caption.sql`
- `influora-api/src/main/java/com/influora/domain/entity/MediaMetric.java`
- `influora-api/src/main/java/com/influora/job/MetricsPollingJob.java`
- `influora-api/src/main/java/com/influora/job/StaleTokenCleanupJob.java`
- `influora-api/src/main/java/com/influora/integration/meta/service/InstagramMetricsFetcher.java`
- `influora-api/src/main/java/com/influora/integration/meta/dto/InstagramMediaResponse.java`
- `influora-api/src/main/java/com/influora/repository/MediaMetricsRepository.java`
- `influora-api/src/main/java/com/influora/web/dto/analytics/AnalyticsDtos.java`
- `influora-api/src/main/java/com/influora/service/analytics/AnalyticsService.java` (no caption read)
- `influora-api/src/main/java/com/influora/web/AnalyticsController.java` (no caption read)
- `influora-api/src/main/java/com/influora/job/ScoreCalculationJob.java`, `service/scoring/*` (no getCaption caller)
- `influora-api/src/main/java/com/influora/service/AuthService.java` (soft-revoke only, no purge)
- `influora-api/src/main/java/com/influora/common/GlobalExceptionHandler.java`
- `influora-api/src/test/java/com/influora/web/dto/NoBrandFacingCaptionExposureTest.java`
- `wiki/decisions/2026-07-06-brand-safety-caption-storage.md` (governing ADR)

## Verdict: SIGN-OFF (conditional)
C1 ships. The PII/retention posture is ADR-compliant: raw-store + exclude-on-read is exactly the ADR
model, no leak path exists via any channel, logging discipline holds. The two MED retention/erasure
gaps are **compliant-by-deferral per ADR lines 53-54** and are logged here as *conscious accepted risk*,
not blockers — with a **hard condition**: a creator-deletion / Meta-disconnect purge of `media_metrics`
(MED-1) MUST be implemented before any user-facing account-deletion feature ships, and re-escalates to
HIGH if C3 replicates captions into influora-ai without a matching purge. LOW-1 (name-based guardrail)
and LOW-3 (positional-ALTER lock cost) are for Kavya-C4 / Meera respectively.
