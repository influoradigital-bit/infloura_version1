# P2-11 — Fake-follower NLP + QualityScore audience-match

**Owner:** Priya (architecture first) → Vikram (impl) · **Reviewers:** Kavya → Meera · **Priority:** P2 · **Depends on:** P0-1 + Priya arch sign-off
**Status:** ✅ DONE (Priya-verified 2026-07-13)

## Goal
`FakeFollowerDetectionService` is a deliberate stub ("requires NLP integration") and `QualityScoreService.audienceMatch` is hardcoded to 50. Design the approach (Priya), then implement.

## Files
- `influora-api/src/main/java/com/influora/service/scoring/FakeFollowerDetectionService.java:27,104`
- `influora-api/src/main/java/com/influora/service/scoring/QualityScoreService.java:23-63`
- possibly `influora-ai/app/*` if NLP runs in the Python service

## Steps
1. **Priya:** decide where detection runs (Java heuristics vs Python NLP), data sources, cost — write arch note in `wiki/tech/architecture.md`, approve any new dep.
2. **Vikram:** implement per the arch note.

## Acceptance criteria
- [x] Priya architecture note + sign-off — `wiki/tech/architecture.md` § ADR-2026-07-12 (LOCKED)
- [x] Real fake-follower signal (not stub); `audienceMatch` computed from real demographics
- [x] Kavya QA · Meera verify (real `mvn -o test` run)

## Completion log
- **2026-07-13 · Priya (CTO) — architecture decided & LOCKED** → `wiki/tech/architecture.md` § ADR-2026-07-12.
  Two rulings, both grounded in the real data model (read `MediaMetric`/`CreatorMetric`/`AudienceDemographics`/`CreatorScore` + `ScoreCalculationJob`/`RateEstimationService` first):
  1. **Fake-follower = Java heuristics, no Python NLP, no new dep.** NLP-on-comment-text rejected: comment
     *text* is stored nowhere (`MediaMetric.comments` is a count `Long`; only `caption` is free text, already
     used by BrandSafety). Fetching follower comments = new Graph edge + table + Meta scope + third-party PII
     liability = out of scope. Implemented instead a real **comment-to-like ratio anomaly** signal from
     existing like/comment counts (floor L≤1000 → 0; ratio<0.001 → +20; ratio>0.5 → +15).
  2. **`audienceMatch` from the real `AudienceDemographics` (V25) snapshot, brand-agnostically** — the daily job
     has no brand context, so this is audience *definition/targetability* (`topTwoShare` over age-gender 0.6 /
     country 0.4, present-dimension renormalization), matching the V22 column's own "typical brand targets"
     wording. **Null (never 50/0) when no snapshot** — matches the LOCKED BrandSafety null discipline. Composite
     `overall` re-normalizes (÷0.85) when audienceMatch is null; `overall` stays non-null so RateEstimation is
     unaffected.
- **2026-07-13 · Vikram — implemented** (no signature drift left un-fixed). Files: `common/JsonLists.java`
  (`longMapFromJson`), `service/scoring/QualityScoreService.java` (3rd param, `calculateAudienceMatch`/`topTwoShare`,
  nullable audienceMatch + composite re-normalization, javadoc), `service/scoring/FakeFollowerDetectionService.java`
  (comment-to-like signal, javadoc), `job/ScoreCalculationJob.java` (inject `AudienceDemographicsRepository`, fetch
  latest snapshot), + tests `QualityScoreServiceTest` / `FakeFollowerDetectionServiceTest` / `ScoreCalculationJobTest`.
- **2026-07-13 · Kavya QA — FAIL→fix→PASS.** Found one real honest-null violation: empty-metric defensive branch
  returned `BigDecimal.ZERO` for `audienceMatch` instead of `null`. CTO adjudicated valid; her secondary
  renormalization ⚠️ was a misread (empty branch hardcodes `overall=ZERO`, bypasses the composite). Vikram
  applied the 2-line fix (`QualityScoreService` ZERO→null + test `assertNull`).
- **2026-07-13 · Priya — real verification (ran `mvn -o test` herself, Maven 3.9.6 / JDK 21, offline):**
  - Targeted: `QualityScoreServiceTest,FakeFollowerDetectionServiceTest,ScoreCalculationJobTest` →
    **Tests run: 43, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS.**
  - Full suite: **Tests run: 890, Failures: 11, Errors: 9, Skipped: 0.** The 11F/9E are the **exact pre-existing
    P0-1 baseline** (MultipartConfigTest, DealServiceTest, MeeraSessionServiceTest, ConfirmLaunchExecutorTest,
    CreateCampaignExecutorTest, RedemptionServiceTest, DatabaseConstraintIntegrationTest[docker]) — **none** are
    scoring tests. Net +11 tests vs the 879 baseline, all passing; **zero new failures/errors → zero regression.**
  - **SIGNED-OFF.**
